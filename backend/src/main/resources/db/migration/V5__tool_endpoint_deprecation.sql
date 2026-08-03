-- 上游 OpenAPI 移除 endpoint 后，工具不能继续进入新的能力包或 Release；
-- 但历史 Release 的 manifest 已冻结，工具行本身必须保留以供审计和对账。

alter table tool
  add column deprecated_at timestamptz,
  add column deprecation_reason text;

create index idx_tool_active_source
  on tool(api_source_id, created_at desc)
  where deprecated_at is null;
