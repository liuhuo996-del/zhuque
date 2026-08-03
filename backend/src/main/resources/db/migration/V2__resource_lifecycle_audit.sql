-- 回收站与操作审计是控制面的辅助元数据，不改变 CLAUDE.md 中冻结的核心业务表字段。
-- 不使用外键：资源被永久清除后，审计记录仍必须保留以回答“谁在何时删除了什么”。

create table resource_lifecycle (
  resource_type text not null check (resource_type in ('agent', 'api_source')),
  resource_id   uuid not null,
  state         text not null check (state in ('trashed')),
  changed_at    timestamptz not null default now(),
  changed_by    text not null,
  reason        text not null default '',
  primary key (resource_type, resource_id)
);

create table audit_event (
  id            uuid primary key default gen_random_uuid(),
  actor         text not null,
  action        text not null,
  resource_type text not null,
  resource_id   uuid not null,
  detail        jsonb not null default '{}',
  occurred_at   timestamptz not null default now()
);

create index idx_resource_lifecycle_state
  on resource_lifecycle(resource_type, state, changed_at desc);
create index idx_audit_event_resource
  on audit_event(resource_type, resource_id, occurred_at desc);
create index idx_audit_event_time on audit_event(occurred_at desc);
