-- V1 把 source_spec_hashes 与 target_constraints 初始化为 JSON array，
-- 但控制面契约和 Repository 都把它们作为 JSON object 读取。保留 V1 checksum，
-- 用独立迁移修正新默认值。Repository 还兼容旧值，历史冻结快照不在迁移中改写。

alter table release
  alter column source_spec_hashes set default '{}'::jsonb,
  alter column target_constraints set default '{}'::jsonb;

update release
set source_spec_hashes = '{}'::jsonb
where source_spec_hashes = '[]'::jsonb
  and status = 'draft';

update release
set target_constraints = '{}'::jsonb
where target_constraints = '[]'::jsonb
  and status = 'draft';
