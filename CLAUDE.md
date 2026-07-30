# 朱雀 v1

你在开发「朱雀」v1。以下是必须遵守的上下文，任何实现都不得违背。

## 产品是什么

朱雀是企业数字员工的能力发布控制面。它把企业的 REST API 池，编译成经过
测试、审批、版本化、可回滚的「数字员工能力」，以声明式方式发布到 Nacos，
由 Higress 网关执行。

一句话：朱雀不回答"怎么把 API 变成 MCP"，它回答"凭什么让这个数字员工
带着这些工具上线"。

## 三方分工（关键，不要越界）

```
朱雀   —— 纯控制面。不在任何调用链上，不收发 MCP 协议消息。
          只生产静态 JSON 配置 + 证据记录。
Nacos  —— 配置面。存 MCP service 定义（tools/描述/inputSchema/参数映射
          模板/后端引用），提供版本历史、灰度、回滚、密钥加密托管。
Higress—— 数据面，也是真正的 MCP Server。终结 MCP 协议
          （SSE / streamable HTTP + JSON-RPC），执行 MCP↔REST 转换、
          入口鉴权、限流、日志。
```

明确不要实现：MCP 协议服务端、API 网关、服务注册表、配置版本存储、
开发者门户 / 市场。这些由 Higress / Nacos 承担。

## 名词表（UI 用左列，代码用右列）

```
数字部门   Department   —— 映射到网关的一个 consumer group
数字员工   Agent        —— 一个 ServiceAccount，对外是一个 MCP URL
工具池     ToolPool     —— 富化过的 Tool 集合，公司级资产，跨部门共享
意图       Intent       —— 从职责描述拆出的原子任务，是可审核对象
能力包     Pack         —— 按业务意图组合的 Tool 集合，可跨多个 API 来源
闭包检查   ClosureCheck —— 验证包内每个 Tool 的必填参数是否都拿得到
Release                 —— 不可变快照 = 配置产物 + 证据包
门禁       Gate         —— 一组硬规则，决定 Release 能否发布
漂移       Drift        —— 上游 spec 变更 / 线上配置与记录不符
```

## 技术栈

```
后端：Java 17 + Spring Boot 3 + PostgreSQL 15（manifest / 报告用 JSONB）
      （若团队用 Go，换成 Go 1.22 + chi + sqlc，其余约束不变）
前端：React 18 + TypeScript + Vite + TanStack Query + Tailwind + shadcn/ui
外部：Nacos ≥ 3.0.1（走 Admin API，不是 client OpenAPI —— 后者发布不了配置）
      Higress（需支持同步 Nacos 原生 MCP Server 的版本）
      Higress 的 MCP 功能依赖 Redis，部署前置检查要校验
```

## 核心数据模型（前后端共同契约，不得擅自增删字段）

```
department(id, name, slug, consumer_group_ref, created_at)

agent(id, department_id, name, slug, description, forbidden_notes,
      status, mcp_url, created_at)
  status: draft | active | suspended | retired

intent(id, agent_id, text, order_no, source)
  source: ai | human          -- AI 拆的还是人写的，审计要用

api_source(id, name, spec_url, spec_hash, last_fetched_at, env_profile)

tool(id, api_source_id, name, description, input_schema jsonb,
     request_template jsonb, method, path, effect, enrichment_status,
     output_fields jsonb, sensitivity_flags jsonb, token_cost, created_at)
  effect: read | write | delete | unknown
  enrichment_status: raw | enriched | reviewed

pack(id, department_id, name, scope, created_at)
  scope: company | department        -- v1 只用 department，字段保留

pack_tool(pack_id, tool_id, added_by, reason, confidence)
  added_by: ai | human

projection(id, pack_id, name, visibility_condition jsonb)
  -- v1 固定每个 pack 一条 projection，代码里 1:1 写死
  -- 但表和外键必须存在，v2 的动态投影靠它

agent_pack(agent_id, pack_id)

release(id, agent_id, version, status, manifest jsonb, manifest_hash,
        nacos_payload jsonb, higress_auth_payload jsonb,
        source_spec_hashes jsonb, target_constraints jsonb, created_at)
  status: draft | candidate | tested | approved | released
          | superseded | rolled_back

test_report(id, release_id, layer, case_id, result, detail jsonb,
            model_meta jsonb)
  layer: L0 | L1 | L2
  model_meta: 评测用的模型名/版本/温度/prompt 模板版本（L2 必填）

gate_decision(id, release_id, rule_id, verdict, waived_by, waiver_reason)

approval(id, release_id, manifest_hash, approver, decided_at, decision)
  -- 审批签的是 manifest_hash，不是 release_id

deploy_record(id, release_id, target, payload_hash, applied_at, result)
  target: nacos | higress_auth

drift_event(id, scope_type, scope_id, kind, detail jsonb,
            detected_at, status)
  kind: spec | config

agent_key(id, agent_id, key_ref, rotated_at, revoked_at)
  -- 只存引用，明文 key 不落库
```

## 全局硬约束

1. 自动的是「生成候选 + 跑测试」，发布必须人点。任何"一键自动发布"
   的实现都是错的。
2. Release 冻结后 manifest 不可改；要改就开新 Release。
3. 审批绑定 manifest_hash：内容一变，已有审批自动失效。
4. Release 存全量快照，不存 diff。回滚 = 旧快照原样重放。
5. 发布是双 target 事务（Nacos + Higress 鉴权），任一失败整体回滚。
   绝不允许出现「工具已暴露、鉴权未配」的裸奔状态。
6. 命名规则 mcp-{department_slug}-{agent_slug} 生成后落库，
   后续 reconcile / 回滚全靠它对账，生成后不可变更。
7. 一切网关特有的代码只能出现在 HigressAuthTarget 一个类里，
   不得渗入业务逻辑。
