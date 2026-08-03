-- 正式测试证据与门禁判定必须可追溯，重跑不能覆盖或删除旧记录。
-- V6 最初以 (release_id, layer) 作为 test_run 主键；这里升级为每次运行一个不可变 run id，
-- 同一层只通过局部唯一索引限制“同时只能有一个 running”。

alter table test_run add column if not exists id uuid;
update test_run set id = gen_random_uuid() where id is null;
alter table test_run alter column id set not null;
alter table test_run drop constraint if exists test_run_pkey;
alter table test_run add primary key (id);
-- V7 之前没有 report → run 的确定性外键。不要猜测旧报告属于哪一次运行；保留为
-- legacy_unbound，读取时仍可按 release/layer 追溯。V7 之后新建的 run 一律 bound。
alter table test_run add column if not exists evidence_binding text not null default 'legacy_unbound'
  check (evidence_binding in ('bound', 'legacy_unbound'));

create unique index if not exists uq_test_run_one_running_per_layer
  on test_run(release_id, layer)
  where state = 'running';
create index if not exists idx_test_run_release_layer_started
  on test_run(release_id, layer, started_at desc);

alter table test_report add column if not exists test_run_id uuid references test_run(id);
create index if not exists idx_test_report_run on test_report(test_run_id, case_id);
create unique index if not exists uq_test_report_run_case
  on test_report(test_run_id, case_id)
  where test_run_id is not null;

-- gate_decision 改为追加式审计日志：当前值由最新一条记录导出，历史判定/豁免完整保留。
alter table gate_decision add column if not exists detail jsonb not null default '{}'::jsonb;
alter table gate_decision add column if not exists rule_set_version text not null default 'legacy';
alter table gate_decision add column if not exists decided_at timestamptz not null default now();
alter table gate_decision add column if not exists decided_by text not null default 'legacy';
create index if not exists idx_gate_decision_release_rule_time
  on gate_decision(release_id, rule_id, decided_at desc, id desc);
