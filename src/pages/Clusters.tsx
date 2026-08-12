import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { buildPack, fetchClusters, ApiError } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { ErrorState } from '@/components/ui/ErrorState'
import { zh } from '@/lib/utils'

export function Clusters() {
  const query = useQuery({ queryKey: ['clusters'], queryFn: fetchClusters }); const client = useQueryClient(); const navigate = useNavigate()
  const [selected, setSelected] = useState<string[]>([]); const [open, setOpen] = useState(false); const [name, setName] = useState(''); const [runL1, setRunL1] = useState(false)
  const mutation = useMutation({ mutationFn: buildPack, onSuccess: async (pack) => { await client.invalidateQueries(); navigate(`/packs/${pack.id}`) } })
  const toggle = (key: string) => setSelected((v) => v.includes(key) ? v.filter((x) => x !== key) : [...v, key])
  return <div className="page-shell"><header className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5"><div><h1 className="page-title">意图聚类</h1><p className="page-description">聚类只是能力包推荐边界，不把“一组相似 API”强行等同于一个数字员工。</p></div><Button variant="primary" disabled={!selected.length} onClick={() => setOpen(true)}>构建所选聚类（{selected.length}）</Button></header><section className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{(query.data ?? []).map((cluster) => <button key={cluster.key} onClick={() => toggle(cluster.key)} className={`panel p-5 text-left transition ${selected.includes(cluster.key) ? 'border-brand ring-2 ring-brand-soft' : 'hover:border-brand'}`}><div className="flex items-start justify-between gap-3"><div><div className="font-semibold">{zh(cluster.domain)} · {zh(cluster.intent)}</div><code className="text-[11px] text-ink-faint">{cluster.key}</code></div><input type="checkbox" checked={selected.includes(cluster.key)} readOnly /></div><div className="mt-5 flex items-end justify-between"><div><div className="font-mono text-3xl font-semibold">{cluster.tool_count}</div><div className="text-xs text-ink-faint">{cluster.tool_count} 个工具 · {cluster.source_count} 个来源</div></div><div className="text-right"><div className="font-mono text-sm text-brand-strong">{Math.round(cluster.confidence * 100)}%</div><div className="text-[11px] text-ink-faint">置信度</div></div></div></button>)}</section>{!query.data?.length && <div className="panel p-12 text-center text-sm text-ink-muted">导入 API 后会出现语义聚类。</div>}
    <Drawer open={open} onClose={() => setOpen(false)} title="编译 MCP 能力包"><div className="space-y-4"><label className="flex flex-col gap-1.5"><span className="text-xs font-medium text-ink-muted">能力包名称</span><input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="订单查询能力包" /></label><label className="flex gap-2 text-sm"><input type="checkbox" checked={runL1} onChange={(e) => setRunL1(e.target.checked)} />运行 L1 后端连通性测试（受允许列表保护）</label><div className="rounded border border-line bg-canvas p-3 text-xs leading-5 text-ink-muted">编译会运行结构规范、参数边界、安全、权限、依赖闭包和语义区分测试。失败仍会生成被阻断的构建产物，但不能提交 Nacos。</div>{mutation.error && <ErrorState what={(mutation.error as ApiError).what} fix={(mutation.error as ApiError).fix} />}<Button variant="primary" disabled={!name.trim() || mutation.isPending} onClick={() => mutation.mutate({ name: name.trim(), cluster_keys: selected, tool_ids: [], run_l1: runL1, run_l2: true })}>{mutation.isPending ? '测试并编译中…' : '编译能力包'}</Button></div></Drawer>
  </div>
}
