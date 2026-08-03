import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAgents, fetchDriftEvents, fetchReleases } from '@/lib/api'
import { useDepartment } from '@/state/department'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { formatDate } from '@/lib/utils'

// 概览 = 待办：待审 Release、漂移告警、失败的发布。不是仪表盘，是闸门口的队列。
export function Overview() {
  const { deptId } = useDepartment()
  const releases = useQuery({ queryKey: ['releases', deptId], queryFn: () => fetchReleases(deptId) })
  const drifts = useQuery({ queryKey: ['drifts'], queryFn: fetchDriftEvents })
  const agents = useQuery({ queryKey: ['agents', 'all'], queryFn: () => fetchAgents('all') })

  if (releases.isLoading || drifts.isLoading || agents.isLoading) {
    return <div className="flex flex-col gap-4"><SkeletonTable rows={3} /><SkeletonTable rows={3} /></div>
  }

  const agentName = (id: string) => agents.data?.find((a) => a.id === id)?.name ?? id
  const pending = (releases.data ?? []).filter((r) => r.status === 'tested')
  const openDrifts = (drifts.data ?? []).filter((d) => d.status === 'open')
  const failedDeploys = (releases.data ?? []).flatMap((r) =>
    r.deploys.filter((d) => d.result === 'failed').map((d) => ({ release: r, deploy: d })),
  )

  return (
    <div className="flex max-w-4xl flex-col gap-5">
      <h1 className="text-lg font-semibold">概览</h1>

      <section className="rounded border border-line bg-surface">
        <header className="flex h-10 items-center justify-between border-b border-line px-4">
          <h2 className="text-sm font-medium">待审 Release</h2>
          <span className="font-mono text-xs text-ink-muted">{pending.length}</span>
        </header>
        {pending.length === 0 ? (
          <p className="px-4 py-5 text-sm text-ink-muted">没有等待审批的 Release。测试通过的 Release 会出现在这里。</p>
        ) : (
          pending.map((r) => (
            <Link
              key={r.id}
              to={`/releases/${r.id}`}
              className="flex h-9 items-center gap-3 border-b border-line px-4 text-sm last:border-b-0 hover:bg-canvas"
            >
              <code className="font-mono text-xs">{r.version}</code>
              <span>{agentName(r.agentId)}</span>
              <StatusBadge status={r.status} />
              <span className="ml-auto text-xs text-ink-faint">{formatDate(r.createdAt)}</span>
            </Link>
          ))
        )}
      </section>

      <section className="rounded border border-line bg-surface">
        <header className="flex h-10 items-center justify-between border-b border-line px-4">
          <h2 className="text-sm font-medium">漂移告警</h2>
          <span className="font-mono text-xs text-ink-muted">{openDrifts.length}</span>
        </header>
        {openDrifts.length === 0 ? (
          <p className="px-4 py-5 text-sm text-ink-muted">没有未处理的漂移。上游 spec 变更或线上配置不符时会出现在这里。</p>
        ) : (
          openDrifts.map((d) => (
            <div key={d.id} className="border-b border-line px-4 py-2.5 last:border-b-0">
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

      <section className="rounded border border-line bg-surface">
        <header className="flex h-10 items-center justify-between border-b border-line px-4">
          <h2 className="text-sm font-medium">失败的发布</h2>
          <span className="font-mono text-xs text-ink-muted">{failedDeploys.length}</span>
        </header>
        {failedDeploys.length === 0 ? (
          <p className="px-4 py-5 text-sm text-ink-muted">没有失败的发布。</p>
        ) : (
          failedDeploys.map(({ release, deploy }, i) => (
            <div key={i} className="border-b border-line px-4 py-2.5 last:border-b-0">
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
