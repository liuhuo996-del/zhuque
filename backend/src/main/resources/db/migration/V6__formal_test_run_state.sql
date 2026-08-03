-- 正式测试的运行状态独立于短生命周期的进程内 JobRegistry。
-- 这样服务重启、异步 L1/L2 半途失败或并发重复点击，都不能把部分 test_report
-- 当成完整证据推进到审批链。

create table test_run (
  release_id       uuid not null references release(id),
  layer            text not null check (layer in ('L0','L1','L2')),
  job_id           text not null,
  expected_cases   integer not null check (expected_cases >= 0),
  state            text not null check (state in ('running','completed','failed')),
  started_at       timestamptz not null default now(),
  completed_at     timestamptz,
  failure          text,
  primary key (release_id, layer)
);

create index idx_test_run_state on test_run(release_id, state);
