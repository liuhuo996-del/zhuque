-- 保留恢复事件的最后时点。旧 Release 在恢复前创建，不能被重新发布；必须从恢复后的
-- draft 新建一份 Release，重新生成测试、门禁和审批证据。
-- 以独立 migration 变更约束，避免已执行 V2 的环境出现 Flyway checksum 漂移。

alter table resource_lifecycle drop constraint if exists resource_lifecycle_state_check;
alter table resource_lifecycle add constraint resource_lifecycle_state_check
  check (state in ('trashed', 'restored'));
