import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchDashboard } from '@/lib/api'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { formatDate } from '@/lib/utils'

export function Overview() {
  const query = useQuery({ queryKey: ['dashboard'], queryFn: fetchDashboard })
  if (query.isLoading) return <SkeletonTable rows={8} />
  const data = query.data
  if (!data) return <p>无法读取工程概览。</p>
  const stats = [
    ['API 来源', data.sources, `${data.operations} 个接口操作`],
    ['可用工具', data.accepted_tools, `${data.rejected_operations} 个已过滤`],
    ['语义聚类', data.clusters, '按领域与意图推荐'],
    ['MCP 能力包', data.packs, `${data.ready_packs} 个可注册`],
    ['Nacos 注册', data.registered_packs, '只记录官方接口回读'],
    ['平均质量', `${Math.round(data.average_quality * 100)}%`, '描述 + 结构规范 + 测试'],
  ]
  return <div className="page-shell">
    <header className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5"><div><h1 className="page-title">MCP 工程化概览</h1><p className="page-description">把企业存量 API 加工为智能体更容易、安全、稳定调用的 MCP 能力包。</p></div><Link to="/intake" className="rounded-md bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-strong">导入 API</Link></header>
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{stats.map(([label, value, note]) => <div key={String(label)} className="panel p-5"><div className="text-xs font-medium text-ink-faint">{label}</div><div className="mt-2 font-mono text-3xl font-semibold text-ink">{value}</div><p className="mt-1 text-xs text-ink-muted">{note}</p></div>)}</section>
    <section className="grid gap-5 xl:grid-cols-[1.3fr_.7fr]">
      <div className="panel overflow-hidden"><header className="border-b border-line px-5 py-4"><h2 className="font-semibold">最近编译的能力包</h2><p className="text-xs text-ink-faint">不可变构建产物，不是自建版本发布系统</p></header>{data.recent_packs.length ? data.recent_packs.map((pack) => <Link key={pack.id} to={`/packs/${pack.id}`} className="flex items-center gap-4 border-b border-line px-5 py-4 last:border-0 hover:bg-brand-tint"><span className={`h-2.5 w-2.5 rounded-full ${pack.status === 'ready' ? 'bg-pass' : 'bg-block'}`} /><div className="min-w-0"><div className="font-medium">{pack.name}</div><div className="truncate font-mono text-[11px] text-ink-faint">{pack.artifact_hash}</div></div><span className="ml-auto text-xs text-ink-faint">{formatDate(pack.created_at)}</span></Link>) : <p className="p-8 text-center text-sm text-ink-muted">还没有能力包，先导入 API 并选择语义聚类。</p>}</div>
      <div className="panel p-5"><h2 className="font-semibold">固定系统边界</h2><div className="mt-4 space-y-4 text-sm">{[['GateForge','工程加工 / 编译 / 质量治理'],['Nacos','注册 / 版本 / 生命周期 / 服务发现'],['Higress','MCP 网关与运行数据面'],['智能体','规划与执行']].map(([name, role], i) => <div key={name} className="flex gap-3"><span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-tint font-mono text-xs text-brand-strong">{i + 1}</span><div><div className="font-medium">{name}</div><div className="text-xs text-ink-muted">{role}</div></div></div>)}</div></div>
    </section>
  </div>
}
