import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAgents, fetchDriftEvents, fetchReleases } from '@/lib/api'
import { useDepartment } from '@/state/department'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { formatDate } from '@/lib/utils'

export function Overview() {
  const { deptId } = useDepartment()
  const releases = useQuery({ queryKey: ['releases', deptId], queryFn: () => fetchReleases(deptId) })
  const drifts = useQuery({ queryKey: ['drifts'], queryFn: fetchDriftEvents })
  const agents = useQuery({ queryKey: ['agents', deptId], queryFn: () => fetchAgents(deptId) })

  if (releases.isLoading || drifts.isLoading || agents.isLoading) {
    return <div className="flex flex-col gap-4"><SkeletonTable rows={3} /><SkeletonTable rows={3} /></div>
  }

  const agentName = (id: string) => agents.data?.find((a) => a.id === id)?.name ?? id
  const pending = (releases.data ?? []).filter((r) => r.status === 'tested')
  const scopedAgentIds = new Set((agents.data ?? []).map((agent) => agent.id))
  const openDrifts = (drifts.data ?? []).filter((d) => d.status === 'open')
    .filter((d) => deptId === 'all' || (d.agentId !== undefined && scopedAgentIds.has(d.agentId)))
  const failedDeploys = (releases.data ?? []).flatMap((r) =>
    r.deploys.filter((d) => d.result === 'failed').map((d) => ({ release: r, deploy: d })),
  )
  const activeAgents = (agents.data ?? []).filter((agent) => agent.status === 'active').length
  const onlineReleases = (releases.data ?? []).filter((release) => release.status === 'released').length
  const stats = [
    { label: '数字员工', value: (agents.data ?? []).length, note: `${activeAgents} 个运行中`, color: 'bg-brand-tint text-brand-strong', icon: '◈' },
    { label: '在线 Release', value: onlineReleases, note: '已通过门禁并发布', color: 'bg-[var(--pass-tint)] text-pass', icon: '↗' },
    { label: '待审批', value: pending.length, note: '等待人工签发', color: 'bg-[var(--warn-tint)] text-warn', icon: '◷' },
    { label: '开放漂移', value: openDrifts.length, note: openDrifts.length ? '需要处理' : '线上配置一致', color: openDrifts.length ? 'bg-[var(--block-tint)] text-block' : 'bg-surface-subtle text-ink-muted', icon: '⌖' },
  ]

  return (
    <div className="page-shell">
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5">
        <div>
          <h1 className="page-title">能力治理概览</h1>
          <p className="page-description">聚合待审 Release、配置漂移与发布异常，从这里完成每天的治理工作。</p>
        </div>
        <Link to="/releases" className="text-sm font-medium text-brand-strong hover:text-brand">查看全部 Release →</Link>
      </div>

      <section className="grid grid-cols-1 gap-3 sm:grid-cols-2 2xl:grid-cols-4">
        {stats.map((stat) => (
          <div key={stat.label} className="panel flex items-center gap-4 p-4">
            <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-lg font-semibold ${stat.color}`}>{stat.icon}</span>
            <div className="min-w-0">
              <div className="flex items-baseline gap-2">
                <strong className="font-mono text-2xl font-semibold tracking-tight text-ink">{stat.value}</strong>
                <span className="text-sm font-medium text-ink">{stat.label}</span>
              </div>
              <p className="mt-0.5 truncate text-xs text-ink-faint">{stat.note}</p>
            </div>
          </div>
        ))}
      </section>

      <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-2">
      <section className="panel overflow-hidden">
        <header className="flex h-12 items-center justify-between border-b border-line px-4">
          <div><h2 className="text-sm font-semibold">待审 Release</h2><p className="text-[11px] text-ink-faint">测试通过，等待内容签发</p></div>
          <span className="rounded-full bg-brand-tint px-2 py-0.5 font-mono text-xs text-brand-strong">{pending.length}</span>
        </header>
        {pending.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-ink-muted">没有等待审批的 Release。</p>
        ) : (
          pending.map((r) => (
            <Link
              key={r.id}
              to={`/releases/${r.id}`}
              className="flex min-h-12 flex-wrap items-center gap-3 border-b border-line px-4 py-2 text-sm last:border-b-0 hover:bg-brand-tint"
            >
              <code className="font-mono text-xs">{r.version}</code>
              <span>{agentName(r.agentId)}</span>
              <StatusBadge status={r.status} />
              <span className="ml-auto text-xs text-ink-faint">{formatDate(r.createdAt)}</span>
            </Link>
          ))
        )}
      </section>

      <section className="panel overflow-hidden">
        <header className="flex h-12 items-center justify-between border-b border-line px-4">
          <div><h2 className="text-sm font-semibold">漂移告警</h2><p className="text-[11px] text-ink-faint">上游 Spec 与线上配置对账</p></div>
          <span className="rounded-full bg-[var(--warn-tint)] px-2 py-0.5 font-mono text-xs text-warn">{openDrifts.length}</span>
        </header>
        {openDrifts.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-ink-muted">没有未处理的配置漂移。</p>
        ) : (
          openDrifts.map((d) => (
            <div key={d.id} className="border-b border-line px-4 py-3 last:border-b-0 hover:bg-surface-subtle">
              <div className="flex items-center gap-2">
                <span className="rounded border border-warn/40 px-1.5 font-mono text-[10px] text-warn">{d.kind}</span>
                <span className="text-sm font-medium">{d.scopeName}</span>
                <span className="ml-auto text-xs text-ink-faint">{formatDate(d.detectedAt)}</span>
              </div>
              <p className="mt-1 text-xs text-ink-muted">{d.detail}</p>
            </div>
          ))
        )}
      </section>
      </div>

      <section className="panel overflow-hidden">
        <header className="flex h-12 items-center justify-between border-b border-line px-4">
          <div><h2 className="text-sm font-semibold">失败的发布</h2><p className="text-[11px] text-ink-faint">Nacos MCP Registry 发布异常</p></div>
          <span className="rounded-full bg-[var(--block-tint)] px-2 py-0.5 font-mono text-xs text-block">{failedDeploys.length}</span>
        </header>
        {failedDeploys.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-ink-muted">没有失败的发布，当前发布链路稳定。</p>
        ) : (
          failedDeploys.map(({ release, deploy }, i) => (
            <div key={i} className="border-b border-line px-4 py-3 last:border-b-0 hover:bg-surface-subtle">
              <div className="flex items-center gap-2 text-sm">
                <Link to={`/releases/${release.id}`} className="font-medium hover:underline">
                  {agentName(release.agentId)} <code className="font-mono text-xs">{release.version}</code>
                </Link>
                <code className="rounded border border-line px-1 font-mono text-[10px] text-ink-muted">{deploy.target}</code>
                <span className="ml-auto text-xs text-ink-faint">{formatDate(deploy.appliedAt)}</span>
              </div>
              <p className="mt-1 text-xs text-block">{deploy.error}</p>
            </div>
          ))
        )}
      </section>
    </div>
  )
}
