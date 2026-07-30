-- 朱雀 v1 核心数据模型。与 CLAUDE.md 契约一一对应，不得擅自增删字段。
-- id 统一 uuid；时间统一 timestamptz；大结构统一 jsonb。

create table department (
  id                 uuid primary key default gen_random_uuid(),
  name               text not null,
  slug               text not null unique,
  consumer_group_ref text,            -- M10：创建时同步在网关建 consumer group 后回填
  created_at         timestamptz not null default now()
);

create table agent (
  id              uuid primary key default gen_random_uuid(),
  department_id   uuid not null references department(id),
  name            text not null,
  slug            text not null,      -- 创建后不可变（Nacos/Higress 对账靠它）
  description     text not null default '',
  forbidden_notes text not null default '',
  status          text not null default 'draft'
                  check (status in ('draft','active','suspended','retired')),
  mcp_url         text,               -- 命名规则 mcp-{dept}-{slug} 生成后落库，不可变更
  created_at      timestamptz not null default now(),
  unique (department_id, slug)        -- 同一部门内 slug 唯一
);

create table intent (
  id       uuid primary key default gen_random_uuid(),
  agent_id uuid not null references agent(id),
  text     text not null,
  order_no int  not null,
  source   text not null check (source in ('ai','human'))  -- 审计要用
);

create table api_source (
  id              uuid primary key default gen_random_uuid(),
  name            text not null,
  spec_url        text,
  spec_hash       text,
  last_fetched_at timestamptz,
  env_profile     text not null default 'prod'
);

create table tool (
  id                uuid primary key default gen_random_uuid(),
  api_source_id     uuid not null references api_source(id),
  name              text not null unique,   -- 全局唯一：{source_slug}_{operationId}
  description       text not null default '',
  input_schema      jsonb not null default '{}',
  request_template  jsonb not null default '{}',
  method            text not null,
  path              text not null,
  effect            text not null default 'unknown'
                    check (effect in ('read','write','delete','unknown')),
  enrichment_status text not null default 'raw'
                    check (enrichment_status in ('raw','enriched','reviewed')),
  output_fields     jsonb not null default '[]',  -- 字段路径列表，M4 闭包检查完全依赖
  sensitivity_flags jsonb not null default '[]',
  token_cost        int not null default 0,
  created_at        timestamptz not null default now()
);

create table pack (
  id            uuid primary key default gen_random_uuid(),
  department_id uuid not null references department(id),
  name          text not null,
  scope         text not null default 'department'
                check (scope in ('company','department')),  -- v1 只用 department，字段保留
  created_at    timestamptz not null default now()
);

create table pack_tool (
  pack_id    uuid not null references pack(id),
  tool_id    uuid not null references tool(id),
  added_by   text not null check (added_by in ('ai','human')),
  reason     text,           -- M3 硬要求：ai 加入的必须带理由
  confidence numeric(3,2),
  primary key (pack_id, tool_id)
);

-- v1 固定每个 pack 一条 projection，代码里 1:1 写死；表和外键必须存在（v2 动态投影靠它）
create table projection (
  id                   uuid primary key default gen_random_uuid(),
  pack_id              uuid not null references pack(id),
  name                 text not null,
  visibility_condition jsonb not null default '{}'
);

create table agent_pack (
  agent_id uuid not null references agent(id),
  pack_id  uuid not null references pack(id),
  primary key (agent_id, pack_id)
);

create table release (
  id                    uuid primary key default gen_random_uuid(),
  agent_id              uuid not null references agent(id),
  version               text not null,
  status                text not null default 'draft'
                        check (status in ('draft','candidate','tested','approved',
                                          'released','superseded','rolled_back')),
  manifest              jsonb not null default '{}',  -- 全量快照，永不存 diff
  manifest_hash         text,                         -- 规范化后 SHA-256，冻结时计算
  nacos_payload         jsonb not null default '{}',
  higress_auth_payload  jsonb not null default '{}',
  source_spec_hashes    jsonb not null default '[]',
  target_constraints    jsonb not null default '[]',
  created_at            timestamptz not null default now()
);

create table test_report (
  id         uuid primary key default gen_random_uuid(),
  release_id uuid not null references release(id),
  layer      text not null check (layer in ('L0','L1','L2')),
  case_id    text not null,
  result     text not null,
  detail     jsonb not null default '{}',
  model_meta jsonb            -- L2 必填：模型名/版本/温度/prompt 模板版本
);

create table gate_decision (
  id            uuid primary key default gen_random_uuid(),
  release_id    uuid not null references release(id),
  rule_id       text not null,
  verdict       text not null,
  waived_by     text,
  waiver_reason text
);

create table approval (
  id            uuid primary key default gen_random_uuid(),
  release_id    uuid not null references release(id),
  manifest_hash text not null,   -- 审批签的是 manifest_hash，不是 release_id
  approver      text not null,
  decided_at    timestamptz not null default now(),
  decision      text not null
);

create table deploy_record (
  id           uuid primary key default gen_random_uuid(),
  release_id   uuid not null references release(id),
  target       text not null check (target in ('nacos','higress_auth')),
  payload_hash text not null,
  applied_at   timestamptz not null default now(),
  result       text not null
);

create table drift_event (
  id          uuid primary key default gen_random_uuid(),
  scope_type  text not null,
  scope_id    uuid not null,
  kind        text not null check (kind in ('spec','config')),
  detail      jsonb not null default '{}',
  detected_at timestamptz not null default now(),
  status      text not null default 'open'
);

create table agent_key (
  id         uuid primary key default gen_random_uuid(),
  agent_id   uuid not null references agent(id),
  key_ref    text not null,   -- 只存引用，明文 key 永不落库
  rotated_at timestamptz not null default now(),
  revoked_at timestamptz
);

create index idx_tool_source on tool(api_source_id);
create index idx_release_agent on release(agent_id);
create index idx_drift_open on drift_event(status) where status = 'open';
