import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchAgent, fetchAgentIntents, fetchAgentKeys, fetchDriftEvents, fetchPacks, fetchReleases, fetchTools,
  rotateAgentKey, type ApiError,
} from '@/lib/api'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { EffectBadge } from '@/components/ui/EffectBadge'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/ErrorState'
import { useToast } from '@/components/ui/Toast'
import { cn, copyText, formatDate, shortHash } from '@/lib/utils'
import type { Release, Tool } from '@/types'

const TABS = [
  { key: 'overview', label: '概览' },
  { key: 'tools', label: '工具' },
  { key: 'releases', label: 'Release 历史' },
  { key: 'keys', label: 'Key' },
  { key: 'drift', label: '漂移' },
]

export function AgentDetail() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'overview'
  const toast = useToast()
  const queryClient = useQueryClient()
  const [issuedKey, setIssuedKey] = useState<{ keyRef: string; plaintextOnceOnly: string } | null>(null)

  const agent = useQuery({ queryKey: ['agent', id], queryFn: () => fetchAgent(id) })
  const intents = useQuery({ queryKey: ['intents', id], queryFn: () => fetchAgentIntents(id) })
  const releases = useQuery({ queryKey: ['releases', 'all'], queryFn: () => fetchReleases('all') })
  const keys = useQuery({ queryKey: ['keys', id], queryFn: () => fetchAgentKeys(id) })
  const drifts = useQuery({ queryKey: ['drifts'], queryFn: fetchDriftEvents })
  const packsQ = useQuery({ queryKey: ['packs', 'all'], queryFn: () => fetchPacks('all') })
  const toolsQ = useQuery({ queryKey: ['tools'], queryFn: fetchTools })
  const rotate = useMutation({
    mutationFn: () => rotateAgentKey(id),
    onSuccess: async (result) => {
      setIssuedKey(result)
      await queryClient.invalidateQueries({ queryKey: ['keys', id] })
      toast('密钥已轮换。请立即保存新密钥；关闭此提示后无法再次查看。')
    },
  })

  if (agent.isLoading) return <SkeletonTable rows={4} />
  if (!agent.data) return <p className="text-sm text-ink-muted">数字员工不存在。回到列表重新进入。</p>
  const a = agent.data

  const agentReleases = (releases.data ?? []).filter((r) => r.agentId === id)
  const agentDrifts = (drifts.data ?? []).filter((d) => d.agentId === id || d.scopeType === 'api_source')
  const pack = (packsQ.data ?? []).find((p) => p.usedByAgentIds.includes(id))
  const packTools = pack ? (toolsQ.data ?? []).filter((t) => pack.toolIds.includes(t.id)) : []

  const relCols: Column<Release>[] = [
    { key: 'v', header: '版本', render: (r) => <Link className="font-mono text-xs font-medium hover:underline" to={`/releases/${r.id}`}>{r.version}</Link> },
    { key: 's', header: '状态', render: (r) => <StatusBadge status={r.status} /> },
    { key: 'hash', header: 'manifest_hash', render: (r) => <code className="font-mono text-xs text-ink-muted">{shortHash(r.manifestHash)}</code> },
    { key: 'at', header: '创建时间', render: (r) => <span className="text-xs text-ink-muted">{formatDate(r.createdAt)}</span> },
    { key: 'appr', header: '审批人', render: (r) => <span className="text-xs">{r.approvals[0]?.approver ?? '—'}</span> },
  ]

  const toolCols: Column<Tool>[] = [
    { key: 'name', header: '名称', render: (t) => <code className="font-mono text-xs">{t.name}</code> },
    { key: 'ep', header: 'endpoint', render: (t) => <code className="font-mono text-[11px] text-ink-muted">{t.method} {t.path}</code> },
    { key: 'effect', header: 'effect', render: (t) => <EffectBadge effect={t.effect} /> },
    { key: 'token', header: 'token', align: 'right', render: (t) => <span className="font-mono text-xs tabular-nums">{t.tokenCost}</span> },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold">{a.name}</h1>
        <code className="font-mono text-xs text-ink-faint">{a.slug}</code>
        <StatusBadge status={a.status} kind="agent" />
      </div>

      <div className="flex gap-1 border-b border-line">
        {TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setParams({ tab: t.key })}
            className={cn(
              'h-9 px-3 text-sm',
              tab === t.key ? 'border-b-2 border-ink font-medium text-ink' : 'text-ink-muted hover:text-ink',
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'overview' && (
        <div className="flex max-w-3xl flex-col gap-4">
          <section className="rounded border border-line bg-surface p-4">
            <h2 className="text-xs font-semibold text-ink-muted">MCP URL</h2>
            <div className="mt-2 flex items-center gap-3">
              <code className="select-all font-mono text-base">{a.mcpUrl}</code>
              <Button size="sm" onClick={async () => { await copyText(a.mcpUrl); toast('已复制 MCP URL') }}>复制</Button>
            </div>
            <p className="mt-3 text-sm text-ink-muted">
              当前 Release：<code className="font-mono text-xs">{a.currentVersion ?? '尚未发布'}</code>
              {a.lastReleasedAt && <span className="ml-2 text-xs text-ink-faint">{formatDate(a.lastReleasedAt)}</span>}
            </p>
          </section>
          <section className="rounded border border-line bg-surface p-4">
            <h2 className="text-xs font-semibold text-ink-muted">职责与约束</h2>
            <p className="mt-2 text-sm">{a.description}</p>
            {a.forbiddenNotes && (
              <p className="mt-2 text-sm"><span className="font-medium">明确禁止：</span>{a.forbiddenNotes}</p>
            )}
          </section>
          <section className="rounded border border-line bg-surface p-4">
            <h2 className="text-xs font-semibold text-ink-muted">意图</h2>
            {intents.isLoading ? (
              <div className="skeleton mt-2 h-3 w-64" />
            ) : (intents.data ?? []).length === 0 ? (
              <p className="mt-2 text-sm text-ink-muted">还没有意图。在新建向导的匹配审核步骤里拆解职责后会出现在这里。</p>
            ) : (
              <ol className="mt-2 flex flex-col gap-1">
                {(intents.data ?? []).map((i) => (
                  <li key={i.id} className="flex items-center gap-2 text-sm">
                    <span className="w-5 text-right font-mono text-xs text-ink-faint">{i.orderNo}</span>
                    {i.text}
                    {i.source === 'ai' && <span className="rounded border border-line px-1 font-mono text-[9px] text-ink-faint">ai</span>}
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      )}

      {tab === 'tools' && (
        toolsQ.isLoading ? <SkeletonTable rows={5} cols={4} /> : (
          <div className="max-w-3xl">
            {pack && (
              <p className="mb-3 text-sm text-ink-muted">
                来自能力包 <Link to="/packs" className="font-medium underline underline-offset-2">{pack.name}</Link>
              </p>
            )}
            <DataTable columns={toolCols} rows={packTools} rowKey={(t) => t.id} />
          </div>
        )
      )}

      {tab === 'releases' && (
        releases.isLoading ? <SkeletonTable rows={4} cols={5} /> : (
          <div className="max-w-3xl">
            <DataTable columns={relCols} rows={agentReleases} rowKey={(r) => r.id} />
          </div>
        )
      )}

      {tab === 'keys' && (
        <div className="flex max-w-3xl flex-col gap-3">
          <div className="flex items-start justify-between gap-3">
            <p className="text-xs leading-5 text-ink-muted">只显示 key 引用与轮换时间。轮换会向已配置的密钥托管目标签发新 key，明文只在本次结果中显示一次。</p>
            <Button size="sm" className="shrink-0" disabled={a.status === 'retired' || keys.isLoading || rotate.isPending} onClick={() => rotate.mutate()}>
              {rotate.isPending ? '处理中…' : (keys.data ?? []).length ? '轮换 key' : '签发首个 key'}
            </Button>
          </div>
          {issuedKey && (
            <section className="rounded border border-warn/40 bg-[var(--warn-tint)] p-3">
              <p className="text-xs font-medium">新 key（仅本次显示）</p>
              <div className="mt-2 flex items-center gap-2">
                <code className="min-w-0 flex-1 break-all font-mono text-xs select-all">{issuedKey.plaintextOnceOnly}</code>
                <Button size="sm" onClick={async () => { await copyText(issuedKey.plaintextOnceOnly); toast('已复制新 key') }}>复制</Button>
                <Button size="sm" variant="ghost" onClick={() => setIssuedKey(null)}>关闭</Button>
              </div>
              <p className="mt-2 font-mono text-[11px] text-ink-muted">引用：{issuedKey.keyRef}</p>
            </section>
          )}
          {rotate.error && <ErrorState compact what={(rotate.error as ApiError).what} fix={(rotate.error as ApiError).fix} />}
          {(keys.data ?? []).map((k) => (
            <div key={k.id} className="flex items-center gap-3 rounded border border-line bg-surface px-4 py-3">
              <code className="font-mono text-xs">{k.keyRef}</code>
              <span className="text-xs text-ink-muted">轮换于 {formatDate(k.rotatedAt)}</span>
              {k.revokedAt ? (
                <span className="ml-auto text-xs text-ink-faint">已吊销 {formatDate(k.revokedAt)}</span>
              ) : (
                <span className="ml-auto text-xs text-pass">生效中</span>
              )}
            </div>
          ))}
        </div>
      )}

      {tab === 'drift' && (
        <div className="flex max-w-3xl flex-col gap-3">
          {agentDrifts.length === 0 && <p className="text-sm text-ink-muted">没有漂移记录。</p>}
          {agentDrifts.map((d) => (
            <div key={d.id} className="rounded border border-line bg-surface px-4 py-3">
              <div className="flex items-center gap-2">
                <span className={cn(
                  'rounded border px-1.5 font-mono text-[10px]',
                  d.status === 'open' ? 'border-warn/40 text-warn' : 'border-line text-ink-faint',
                )}>
                  {d.kind}
                </span>
                <span className="text-sm font-medium">{d.scopeName}</span>
                <span className="ml-auto text-xs text-ink-faint">{formatDate(d.detectedAt)} · {d.status === 'open' ? '未处理' : '已解决'}</span>
              </div>
              <p className="mt-1.5 text-xs text-ink-muted">{d.detail}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
