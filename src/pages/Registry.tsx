import { useMutation, useQuery } from '@tanstack/react-query'
import { fetchRegistrations, probeNacos, ApiError } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/ErrorState'
import { formatDate } from '@/lib/utils'
import { zh } from '@/lib/utils'

export function Registry() {
  const rows = useQuery({ queryKey: ['registrations'], queryFn: fetchRegistrations })
  const probe = useMutation({ mutationFn: probeNacos })
  return <div className="page-shell"><header className="flex items-end justify-between border-b border-line pb-5"><div><h1 className="page-title">Nacos 适配器</h1><p className="page-description">只调用 Nacos 官方 AI/MCP 管理接口；版本、服务编号与状态均在注册后回读。</p></div><Button onClick={() => probe.mutate()}>{probe.isPending ? '探测中…' : '探测 Nacos'}</Button></header>{probe.data && <div className="rounded-lg border border-pass/30 bg-[var(--pass-tint)] p-4 text-sm text-pass">Nacos {probe.data.version} · 命名空间 {probe.data.namespace}</div>}{probe.error && <ErrorState what={(probe.error as ApiError).what} fix={(probe.error as ApiError).fix} />}<div className="panel overflow-hidden"><table className="w-full text-sm"><thead className="bg-[#f8fafc] text-left text-xs text-ink-muted"><tr><th className="px-5 py-3">MCP 名称</th><th>服务编号</th><th>Nacos 版本</th><th>状态</th><th>注册时间</th></tr></thead><tbody>{(rows.data ?? []).map((r) => <tr key={r.pack_id} className="border-t border-line"><td className="px-5 py-4 font-mono text-xs">{r.mcp_name}</td><td className="font-mono text-xs text-ink-muted">{r.nacos_server_id}</td><td>{r.nacos_version}</td><td className="text-pass">{zh(r.status)}</td><td className="text-xs text-ink-faint">{formatDate(r.registered_at)}</td></tr>)}</tbody></table>{!rows.data?.length && <p className="p-10 text-center text-sm text-ink-muted">还没有提交到 Nacos 的能力包。</p>}</div></div>
}
