import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { fetchGraphs, rebuildGraphs } from '@/lib/api'
import { zh } from '@/lib/utils'

const statusTone = {
  ready: 'bg-[var(--pass-tint)] text-pass',
  needs_input: 'bg-brand-tint text-brand-strong',
  ambiguous: 'bg-[var(--warn-tint)] text-warn',
  blocked: 'bg-[var(--block-tint)] text-block',
}

export function Graphs() {
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['graphs'], queryFn: fetchGraphs })
  const rebuild = useMutation({
    mutationFn: rebuildGraphs,
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['graphs'] }),
        client.invalidateQueries({ queryKey: ['dashboard'] }),
      ])
    },
  })
  const graphs = query.data ?? []
  return <div className="page-shell">
    <header className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5">
      <div><h1 className="page-title">API 能力图谱</h1><p className="page-description">从终点工具的必填入参反向查找可信输出，递归补齐前置工具和字段绑定。</p></div>
      <Button onClick={() => rebuild.mutate()} disabled={rebuild.isPending}>{rebuild.isPending ? '构图中…' : '重建全部能力图'}</Button>
    </header>
    <section className="rounded-lg border border-brand/20 bg-brand-tint/60 p-4 text-sm leading-6 text-ink-muted">
      <strong className="text-ink">构图规则：</strong> 输出字段倒排索引 → 语义、类型、基数和同源检查 → 消除循环与多 Provider 歧义 → 生成拓扑顺序 → 传播风险与审批要求。数组输出不会自动喂给单值入参。
    </section>
    <section className="grid gap-4 xl:grid-cols-2">
      {graphs.map((graph) => {
        const required = Array.isArray(graph.input_schema.required) ? graph.input_schema.required as string[] : []
        return <article key={graph.id} className="panel overflow-hidden">
          <header className="border-b border-line p-5">
            <div className="flex items-start justify-between gap-4"><div><h2 className="font-semibold leading-6">{graph.name}</h2><p className="mt-1 text-xs leading-5 text-ink-muted">{graph.output_description}</p></div><span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${statusTone[graph.status]}`}>{zh(graph.status)}</span></div>
            <div className="mt-3 flex flex-wrap gap-2 text-[11px]"><span className="rounded bg-surface-subtle px-2 py-1">{graph.nodes.length} 个工具</span><span className="rounded bg-surface-subtle px-2 py-1">{graph.edges.length} 条字段边</span><span className="rounded bg-surface-subtle px-2 py-1">置信度 {Math.round(graph.confidence * 100)}%</span>{graph.zero_input && <span className="rounded bg-[var(--pass-tint)] px-2 py-1 text-pass">零外部入参</span>}</div>
          </header>
          <div className="space-y-4 p-5">
            <div><div className="text-[11px] font-medium tracking-wide text-ink-faint">确定性执行顺序</div><div className="mt-2 flex flex-wrap items-center gap-2">{graph.nodes.map((node, index) => <span key={node.tool_id} className="contents"><code className={`rounded border px-2 py-1 text-[11px] ${node.role === 'terminal' ? 'border-brand bg-brand-tint text-brand-strong' : 'border-line bg-surface-subtle'}`}>{node.tool_name}</code>{index < graph.nodes.length - 1 && <span className="text-ink-faint">→</span>}</span>)}</div></div>
            <div><div className="text-[11px] font-medium tracking-wide text-ink-faint">字段绑定</div><div className="mt-2 space-y-1.5">{graph.edges.map((edge) => <div key={`${edge.provider_tool_id}-${edge.consumer_tool_id}-${edge.input_path}`} className="rounded bg-surface-subtle px-3 py-2 font-mono text-[11px] text-ink-muted">{edge.output_path} <span className="text-brand-strong">→</span> {edge.input_path} <span className="font-sans text-ink-faint">· {Math.round(edge.confidence * 100)}%</span></div>)}</div></div>
            <div className="grid grid-cols-2 gap-3 text-xs"><div className="rounded border border-line p-3"><div className="text-ink-faint">尚需外部输入</div><div className="mt-1 font-medium">{required.length ? required.join('、') : '无'}</div></div><div className="rounded border border-line p-3"><div className="text-ink-faint">图测试</div><div className="mt-1 font-medium">{Math.round(graph.test_report.pass_rate * 100)}% 通过 · {graph.test_report.blocking_failures} 阻断</div></div></div>
            <p className="text-xs leading-5 text-ink-muted">{graph.description}</p>
          </div>
        </article>
      })}
    </section>
    {!query.isLoading && !graphs.length && <div className="panel p-12 text-center text-sm text-ink-muted">当前 API 池还没有可以安全连接的工具。导入包含响应 Schema 和字段描述的 OpenAPI 后再重建。</div>}
  </div>
}
