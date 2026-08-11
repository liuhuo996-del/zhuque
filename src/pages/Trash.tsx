import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { ErrorState } from '@/components/ui/ErrorState'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { useToast } from '@/components/ui/Toast'
import {
  currentOperator, fetchAgents, fetchAuditEvents, fetchDepartments, fetchTrashedApiSources,
  purgeAgent, purgeApiSource, restoreAgent, restoreApiSource, type ApiError,
} from '@/lib/api'
import { formatDate, shortHash } from '@/lib/utils'
import type { Agent, AuditEvent, TrashedApiSource } from '@/types'

export function Trash() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [error, setError] = useState<ApiError | null>(null)
  const [confirm, setConfirm] = useState<{ type: 'agent' | 'api_source'; id: string; name: string } | null>(null)
  const agents = useQuery({ queryKey: ['agents', 'trash'], queryFn: () => fetchAgents('all', true) })
  const sources = useQuery({ queryKey: ['sources', 'trash'], queryFn: fetchTrashedApiSources })
  const departments = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const audits = useQuery({ queryKey: ['audit-events'], queryFn: () => fetchAuditEvents(30) })

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['agents'] }),
      queryClient.invalidateQueries({ queryKey: ['sources'] }),
      queryClient.invalidateQueries({ queryKey: ['tools'] }),
      queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
    ])
  }

  const action = useMutation({
    mutationFn: async ({ kind, type, id }: { kind: 'restore' | 'purge'; type: 'agent' | 'api_source'; id: string }) => {
      if (type === 'agent') return kind === 'restore' ? restoreAgent(id) : purgeAgent(id)
      return kind === 'restore' ? restoreApiSource(id) : purgeApiSource(id)
    },
    onSuccess: async (_, variables) => {
      setError(null)
      setConfirm(null)
      await refresh()
      toast(variables.kind === 'restore'
        ? variables.type === 'agent' ? '数字员工已恢复为可编辑草稿' : 'REST API 已恢复到工具池'
        : '资源本体已永久删除，已有审计事件仍保留')
    },
    onError: (value) => setError(value as ApiError),
  })

  const departmentName = (id: string) => departments.data?.find((department) => department.id === id)?.name ?? id
  const agentColumns: Column<Agent>[] = [
    { key: 'name', header: '数字员工', render: (agent) => <div><span className="font-medium">{agent.name}</span><code className="block font-mono text-[11px] text-ink-faint">{agent.slug}</code></div> },
    { key: 'department', header: '部门', render: (agent) => <span className="text-ink-muted">{departmentName(agent.departmentId)}</span> },
    { key: 'history', header: '历史', render: (agent) => <span className="text-xs text-ink-muted">永久删除前将核验 Release、审批、测试、门禁和部署证据</span> },
    { key: 'created', header: '创建时间', render: (agent) => <span className="text-xs text-ink-muted">{formatDate(agent.createdAt)}</span> },
    {
      key: 'actions', header: '', align: 'right', render: (agent) => (
        <span className="flex justify-end gap-1">
          <Button size="sm" onClick={() => action.mutate({ kind: 'restore', type: 'agent', id: agent.id })}>恢复</Button>
          <Button size="sm" variant="danger" onClick={() => setConfirm({ type: 'agent', id: agent.id, name: agent.name })}>永久删除</Button>
        </span>
      ),
    },
  ]
  const sourceColumns: Column<TrashedApiSource>[] = [
    { key: 'name', header: 'REST API', render: (source) => <div><span className="font-medium">{source.name}</span><code className="block max-w-72 truncate font-mono text-[11px] text-ink-faint">{source.specUrl ?? '手动上传'}</code></div> },
    { key: 'tools', header: '工具', render: (source) => <span className="font-mono text-xs">{source.toolTotal}</span> },
    { key: 'reason', header: '归档理由', render: (source) => <span className="block max-w-48 truncate text-xs text-ink-muted" title={source.trashReason}>{source.trashReason || '未填写'}</span> },
    { key: 'hash', header: 'spec_hash', render: (source) => <code className="font-mono text-xs text-ink-muted">{shortHash(source.specHash)}</code> },
    { key: 'trash', header: '移入时间', render: (source) => <span className="text-xs text-ink-muted">{formatDate(source.trashedAt)} · {source.trashedBy}</span> },
    {
      key: 'actions', header: '', align: 'right', render: (source) => (
        <span className="flex justify-end gap-1">
          <Button size="sm" onClick={() => action.mutate({ kind: 'restore', type: 'api_source', id: source.id })}>恢复</Button>
          <Button size="sm" variant="danger" onClick={() => setConfirm({ type: 'api_source', id: source.id, name: source.name })}>永久删除</Button>
        </span>
      ),
    },
  ]

  return (
    <div className="page-shell">
      <div className="border-b border-line pb-5">
        <h1 className="page-title">回收站</h1>
        <p className="page-description">
          移入回收站只停止使用并从普通列表隐藏。已有 Release、审批、测试、门禁、部署和 spec_hash 证据永久保留；只有从未进入证据链的纯草稿才能永久删除。
        </p>
      </div>

      {error && <ErrorState what={error.what} fix={error.fix} />}
      {confirm && (
        <section className="rounded border border-block/30 bg-[var(--block-tint)] p-4">
          <p className="text-sm font-medium">确认永久删除「{confirm.name}」？</p>
          <p className="mt-1 text-xs text-ink-muted">系统会再次检查引用和 Release 证据；有审计价值时会拒绝删除。</p>
          <div className="mt-3 flex gap-2">
            <Button variant="danger" disabled={action.isPending} onClick={() => action.mutate({ kind: 'purge', type: confirm.type, id: confirm.id })}>确认永久删除</Button>
            <Button variant="ghost" onClick={() => setConfirm(null)}>取消</Button>
          </div>
        </section>
      )}

      <section className="rounded border border-line bg-surface">
        <header className="border-b border-line px-4 py-3"><h2 className="text-sm font-semibold">退役数字员工</h2></header>
        {agents.isLoading ? <div className="p-4"><SkeletonTable rows={3} cols={5} /></div> : (
          <DataTable columns={agentColumns} rows={agents.data ?? []} rowKey={(agent) => agent.id}
            empty={<EmptyState message="回收站中没有退役数字员工。" />} />
        )}
      </section>

      <section className="rounded border border-line bg-surface">
        <header className="border-b border-line px-4 py-3"><h2 className="text-sm font-semibold">已归档 REST API</h2></header>
        {sources.isLoading ? <div className="p-4"><SkeletonTable rows={3} cols={6} /></div> : (
          <DataTable columns={sourceColumns} rows={sources.data ?? []} rowKey={(source) => source.id}
            empty={<EmptyState message="回收站中没有 REST API 来源。" />} />
        )}
      </section>

      <section className="rounded border border-line bg-surface">
        <header className="flex items-center justify-between border-b border-line px-4 py-3">
          <h2 className="text-sm font-semibold">最近操作审计</h2>
          <span className="text-xs text-ink-muted">本机操作标签：{currentOperator()}（生产由 SSO 注入）</span>
        </header>
        <div className="divide-y divide-line">
          {(audits.data ?? []).length === 0 && <p className="px-4 py-6 text-sm text-ink-muted">尚无用户操作记录。</p>}
          {(audits.data ?? []).map((event) => <AuditRow key={event.id} event={event} />)}
        </div>
      </section>
    </div>
  )
}

function AuditRow({ event }: { event: AuditEvent }) {
  return (
    <div className="flex items-start gap-3 px-4 py-2.5 text-xs">
      <code className="w-16 shrink-0 font-mono font-medium">{event.action}</code>
      <span className="w-24 shrink-0 text-ink-muted">{event.resourceType}</span>
      <code className="min-w-0 flex-1 truncate font-mono text-ink-faint">{event.resourceId}</code>
      <span>{event.actor}</span>
      <span className="text-ink-faint">{formatDate(event.occurredAt)}</span>
    </div>
  )
}
