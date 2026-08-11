import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchAgents, fetchDepartments, trashAgent, type ApiError } from '@/lib/api'
import { useDepartment } from '@/state/department'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { ErrorState } from '@/components/ui/ErrorState'
import { useToast } from '@/components/ui/Toast'
import { copyText, formatDate } from '@/lib/utils'
import type { Agent, Health } from '@/types'

// 健康列是本页唯一的彩色列：绿=正常，黄=有漂移告警，红=最近一次发布失败。
function HealthDot({ health }: { health: Health }) {
  if (health === 'none') return <span className="text-xs text-ink-faint">—</span>
  const map = {
    ok: ['bg-pass', '正常'],
    drift: ['bg-warn', '漂移'],
    failed: ['bg-block', '发布失败'],
  } as const
  const [cls, label] = map[health]
  return (
    <span className="inline-flex items-center gap-1.5 text-xs">
      <span className={`h-2 w-2 rounded-full ${cls}`} />
      {label}
    </span>
  )
}

export function AgentsList() {
  const { deptId } = useDepartment()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const [retiring, setRetiring] = useState<Agent | null>(null)
  const [reason, setReason] = useState('')
  const agents = useQuery({ queryKey: ['agents', deptId], queryFn: () => fetchAgents(deptId) })
  const departments = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const retire = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => trashAgent(id, reason),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['agents'] })
      await queryClient.invalidateQueries({ queryKey: ['audit-events'] })
      setRetiring(null)
      setReason('')
      toast('数字员工已退役并移入回收站')
    },
  })

  const deptName = (id: string) => departments.data?.find((d) => d.id === id)?.name ?? id
  const departmentsReady = departments.data !== undefined
  const hasDepartments = (departments.data ?? []).length > 0
  const beginCreate = () => {
    if (!departmentsReady) return
    navigate(hasDepartments ? '/agents/new' : '/departments?create=1')
  }

  const columns: Column<Agent>[] = [
    {
      key: 'name', header: '名称', width: 220,
      render: (a) => (
        <div className="leading-tight">
          <Link to={`/agents/${a.id}`} className="font-medium hover:underline">{a.name}</Link>
          <code className="block font-mono text-[11px] text-ink-faint">{a.slug}</code>
        </div>
      ),
    },
    { key: 'dept', header: '部门', render: (a) => <span className="text-ink-muted">{deptName(a.departmentId)}</span> },
    { key: 'status', header: '状态', render: (a) => <StatusBadge status={a.status} kind="agent" /> },
    { key: 'version', header: '当前版本', render: (a) => <code className="font-mono text-xs">{a.currentVersion ?? '—'}</code> },
    { key: 'tools', header: '工具数', align: 'right', render: (a) => <span className="font-mono text-xs tabular-nums">{a.toolCount || '—'}</span> },
    { key: 'health', header: '健康', render: (a) => <HealthDot health={a.health} /> },
    { key: 'last', header: '最后发布', render: (a) => <span className="text-xs text-ink-muted">{formatDate(a.lastReleasedAt)}</span> },
    {
      key: 'actions', header: '', align: 'right',
      render: (a) => (
        <span className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>
          <Button size="sm" variant="ghost" onClick={() => navigate(`/agents/${a.id}`)}>查看</Button>
          <Button size="sm" variant="ghost" onClick={() => navigate(`/agents/${a.id}?tab=releases`)}>Release 历史</Button>
          <Button
            size="sm" variant="ghost"
            onClick={async () => { await copyText(a.mcpUrl); toast('已复制 MCP URL') }}
          >
            复制 MCP URL
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setRetiring(a)}>退役</Button>
        </span>
      ),
    },
  ]

  return (
    <div className="page-shell">
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5">
        <div>
          <h1 className="page-title">数字员工</h1>
          <p className="page-description">管理每个数字员工的职责、工具能力和发布状态。</p>
        </div>
        <Button
          variant="primary"
          disabled={!departmentsReady}
          onClick={beginCreate}
        >
          {!departmentsReady ? '读取数字部门…' : hasDepartments ? '新建数字员工' : '先创建数字部门'}
        </Button>
      </div>
      {agents.isLoading ? (
        <SkeletonTable rows={5} cols={7} />
      ) : (
        <DataTable
          columns={columns}
          rows={agents.data ?? []}
          rowKey={(a) => a.id}
          onRowClick={(a) => navigate(`/agents/${a.id}`)}
          empty={
            <EmptyState
              message="还没有数字员工。创建第一个，GateForge 会从工具池中组装它需要的受治理能力。"
              action={<Button variant="primary" disabled={!departmentsReady} onClick={beginCreate}>
                {!departmentsReady ? '读取数字部门…' : hasDepartments ? '创建数字员工' : '先创建数字部门'}
              </Button>}
            />
          }
        />
      )}

      <Drawer open={!!retiring} onClose={() => setRetiring(null)} title="退役数字员工">
        {retiring && (
          <div className="flex flex-col gap-4">
            <p className="text-sm leading-6">
              「{retiring.name}」将停止使用并进入回收站。已发布能力会先安全摘除，Release、审批、测试、门禁和部署证据不会删除。
            </p>
            <label className="flex flex-col gap-1.5">
              <span className="text-xs font-medium text-ink-muted">退役理由</span>
              <textarea className="input" rows={3} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="填写可审计的业务理由" />
            </label>
            {retire.error && <ErrorState compact what={(retire.error as ApiError).what} fix={(retire.error as ApiError).fix} />}
            <div className="flex gap-2">
              <Button variant="danger" disabled={reason.trim().length < 2 || retire.isPending} onClick={() => retire.mutate({ id: retiring.id, reason: reason.trim() })}>
                {retire.isPending ? '处理中…' : '确认退役'}
              </Button>
              <Button variant="ghost" onClick={() => setRetiring(null)}>取消</Button>
            </div>
          </div>
        )}
      </Drawer>
    </div>
  )
}
