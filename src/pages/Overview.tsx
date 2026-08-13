import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, buildPack, fetchDashboard, fetchGraphs, recommendPack } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/ErrorState'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { useToast } from '@/components/ui/Toast'
import { formatDate, zh } from '@/lib/utils'

const pipeline = [
  ['01', 'API 池', '解析、过滤、补全'],
  ['02', '字段端口', '输入/输出 Schema'],
  ['03', '能力图', '反向回溯前置工具'],
  ['04', '图与工具测试', '结构、安全、闭包'],
  ['05', '描述匹配', '选图与原子工具'],
  ['06', 'MCP 能力包', '编译后交给 Nacos'],
]

export function Overview() {
  const navigate = useNavigate()
  const toast = useToast()
  const client = useQueryClient()
  const [description, setDescription] = useState('')
  const [name, setName] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [runL1, setRunL1] = useState(false)
  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: fetchDashboard })
  const graphQuery = useQuery({ queryKey: ['graphs'], queryFn: fetchGraphs })
  const recommendation = useMutation({
    mutationFn: () => recommendPack(description.trim()),
    onSuccess: (value) => setSelected(new Set(value.items.map((item) => `${item.kind}:${item.id}`))),
  })
  const build = useMutation({
    mutationFn: () => buildPack({
      name: name.trim() || undefined,
      description: description.trim(),
      graph_ids: recommendation.data?.graph_ids.filter((id) => selected.has(`graph:${id}`)) ?? [],
      tool_ids: recommendation.data?.tool_ids.filter((id) => selected.has(`tool:${id}`)) ?? [],
      run_l1: runL1,
      run_l2: true,
    }),
    onSuccess: async (pack) => {
      toast(`已编译能力包：${pack.name}`)
      await client.invalidateQueries({ queryKey: ['dashboard'] })
      navigate(`/packs/${pack.id}`)
    },
  })
  if (dashboard.isLoading) return <SkeletonTable rows={8} />
  const data = dashboard.data
  if (!data) return <p>无法读取工程概览。</p>
  const stats = [
    ['API 来源', data.sources, `${data.operations} 个接口操作`],
    ['API 池工具', data.accepted_tools, `${data.rejected_operations} 个已过滤`],
    ['能力图', data.capability_graphs, `${data.zero_input_graphs} 个零外部入参`],
    ['构图覆盖', `${Math.round(data.graph_coverage * 100)}%`, '已进入可用图的工具'],
    ['MCP 能力包', data.packs, `${data.ready_packs} 个通过检查`],
    ['Nacos 注册', data.registered_packs, '由官方接口回读'],
  ]
  const graphs = (graphQuery.data ?? []).slice(0, 4)
  const toggle = (key: string) => setSelected((prior) => {
    const next = new Set(prior)
    if (next.has(key)) next.delete(key); else next.add(key)
    return next
  })
  const canBuild = Boolean(description.trim() && recommendation.data && selected.size)
  return <div className="page-shell">
    <header className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5"><div><h1 className="page-title">API 能力编排工作台</h1><p className="page-description">先在 API 池中构建经过检查的能力图，再用能力包描述匹配图和原子工具。</p></div><Link to="/intake" className="rounded-md bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-strong">导入 API</Link></header>

    <section className="panel overflow-hidden"><div className="grid md:grid-cols-3 xl:grid-cols-6">{pipeline.map(([step, title, note], index) => <div key={step} className="relative border-b border-line p-4 last:border-0 md:border-r xl:border-b-0"><div className="font-mono text-[10px] font-semibold text-brand-strong">{step}</div><div className="mt-1 text-sm font-semibold">{title}</div><div className="mt-1 text-[11px] leading-4 text-ink-faint">{note}</div>{index < pipeline.length - 1 && <span className="absolute -right-2 top-1/2 z-10 hidden h-4 w-4 -translate-y-1/2 items-center justify-center rounded-full border border-line bg-surface text-[10px] text-brand-strong xl:flex">→</span>}</div>)}</div></section>

    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6">{stats.map(([label, value, note]) => <div key={String(label)} className="panel p-4"><div className="text-xs font-medium text-ink-faint">{label}</div><div className="mt-2 font-mono text-2xl font-semibold text-ink">{value}</div><p className="mt-1 text-[11px] text-ink-muted">{note}</p></div>)}</section>

    <section className="grid items-start gap-5 xl:grid-cols-[1.15fr_.85fr]">
      <div className="panel overflow-hidden">
        <header className="border-b border-line bg-[linear-gradient(135deg,var(--brand-tint),#fff_68%)] p-5"><div className="text-[11px] font-semibold tracking-[.08em] text-brand-strong">语义编译入口</div><h2 className="mt-1 text-lg font-semibold">用一段目标描述组装 MCP 能力包</h2><p className="mt-1 text-xs leading-5 text-ink-muted">先匹配已通过回溯与闭包检查的能力图，不足部分再用原子工具补齐。</p></header>
        <div className="space-y-4 p-5">
          <label className="block"><span className="text-xs font-medium text-ink-muted">能力包要解决什么问题？</span><textarea className="input mt-1.5 min-h-24 w-full resize-y" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="例如：查找当前用户最新可取消订单，并安全地完成取消，返回取消记录。" /></label>
          <div className="flex flex-wrap gap-3"><Button variant="primary" disabled={description.trim().length < 4 || recommendation.isPending} onClick={() => recommendation.mutate()}>{recommendation.isPending ? '匹配中…' : '匹配能力图与工具'}</Button><span className="self-center text-[11px] text-ink-faint">匹配结果会自动限定在同一后端来源内</span></div>
          {recommendation.error && <ErrorState what={(recommendation.error as ApiError).what} fix={(recommendation.error as ApiError).fix} />}
          {recommendation.data && <div className="space-y-2 border-t border-line pt-4">{recommendation.data.items.map((item) => { const key = `${item.kind}:${item.id}`; return <label key={key} className={`flex cursor-pointer gap-3 rounded-lg border p-3 ${selected.has(key) ? 'border-brand bg-brand-tint/60' : 'border-line'}`}><input type="checkbox" checked={selected.has(key)} onChange={() => toggle(key)} /><div className="min-w-0 flex-1"><div className="flex items-center gap-2"><span className="rounded bg-surface px-1.5 py-0.5 text-[10px] font-medium text-brand-strong">{item.kind === 'graph' ? '能力图' : '原子工具'}</span><strong className="truncate text-sm">{item.name}</strong><span className="ml-auto font-mono text-xs text-ink-faint">{Math.round(item.score * 100)}%</span></div><p className="mt-1 line-clamp-2 text-xs leading-5 text-ink-muted">{item.reason}</p></div></label>})}</div>}
          {recommendation.data && <div className="grid gap-3 border-t border-line pt-4 sm:grid-cols-[1fr_auto]"><div><label><span className="text-xs font-medium text-ink-muted">能力包名称（可选）</span><input className="input mt-1.5 w-full" value={name} onChange={(event) => setName(event.target.value)} placeholder="由目标描述自动命名" /></label><label className="mt-3 flex items-start gap-2 text-xs text-ink-muted"><input className="mt-0.5" type="checkbox" checked={runL1} onChange={(event) => setRunL1(event.target.checked)} /><span>运行 L1 后端连通性与响应 Schema 测试（只访问设置页允许的来源，写操作默认不执行）</span></label></div><Button className="self-end" variant="primary" disabled={!canBuild || build.isPending} onClick={() => build.mutate()}>{build.isPending ? '检查并编译中…' : '构建 MCP 能力包'}</Button></div>}
          {build.error && <ErrorState what={(build.error as ApiError).what} fix={(build.error as ApiError).fix} />}
        </div>
      </div>

      <div className="space-y-5">
        <div className="panel overflow-hidden"><header className="flex items-center justify-between border-b border-line px-5 py-4"><div><h2 className="font-semibold">最新能力图</h2><p className="text-xs text-ink-faint">每个图只描述最终输出和必要外部入参</p></div><Link to="/graphs" className="text-xs font-medium text-brand-strong">全部图谱 →</Link></header>{graphs.map((graph) => <Link to="/graphs" key={graph.id} className="block border-b border-line px-5 py-3 last:border-0 hover:bg-brand-tint"><div className="flex items-center gap-2"><span className={`h-2 w-2 rounded-full ${graph.status === 'ready' ? 'bg-pass' : graph.status === 'blocked' ? 'bg-block' : 'bg-warn'}`} /><span className="truncate text-sm font-medium">{graph.name}</span><span className="ml-auto shrink-0 text-[11px] text-ink-faint">{graph.nodes.length} 步</span></div><p className="mt-1 line-clamp-1 text-xs text-ink-muted">{graph.output_description}</p></Link>)}{!graphs.length && <p className="p-8 text-center text-sm text-ink-muted">导入 API 后将自动构建能力图。</p>}</div>
        <div className="panel overflow-hidden"><header className="border-b border-line px-5 py-4"><h2 className="font-semibold">最近编译的能力包</h2></header>{data.recent_packs.map((pack) => <Link key={pack.id} to={`/packs/${pack.id}`} className="flex items-center gap-3 border-b border-line px-5 py-3 last:border-0 hover:bg-brand-tint"><span className={`h-2 w-2 rounded-full ${pack.status === 'ready' ? 'bg-pass' : 'bg-block'}`} /><div className="min-w-0"><div className="truncate text-sm font-medium">{pack.name}</div><div className="text-[11px] text-ink-faint">{pack.capability_graphs?.length ?? 0} 图 · {pack.tools.length} 工具</div></div><span className="ml-auto shrink-0 text-[11px] text-ink-faint">{formatDate(pack.created_at)}</span></Link>)}{!data.recent_packs.length && <p className="p-8 text-center text-sm text-ink-muted">还没有能力包。</p>}</div>
      </div>
    </section>

    <section className="panel p-5"><h2 className="font-semibold">系统职责边界</h2><div className="mt-4 grid gap-4 md:grid-cols-4">{[['GateForge','API 池、能力图、测试与编译'],['Nacos','注册、版本、生命周期与发现'],['Higress','MCP 路由与运行数据面'],['智能体','语义规划与工具执行']].map(([role, detail], index) => <div key={role} className="flex gap-3"><span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-tint font-mono text-xs text-brand-strong">{index + 1}</span><div><div className="font-medium">{role}</div><div className="text-xs leading-5 text-ink-muted">{detail}</div></div></div>)}</div><p className="mt-4 border-t border-line pt-3 text-xs text-ink-faint">能力图当前是 GateForge 编译产物；注册到 Nacos 的仍是标准 MCP Tool。要把多步图对外暴露为一个 Tool，需要由独立的图执行器或 Agent 主机执行，GateForge 不进入调用数据面。</p></section>
  </div>
}
