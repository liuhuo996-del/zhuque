import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAgents, fetchDepartments } from '@/mock/api'
import { useDepartment } from '@/state/department'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Button } from '@/components/ui/Button'
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
  const toast = useToast()
  const agents = useQuery({ queryKey: ['agents', deptId], queryFn: () => fetchAgents(deptId) })
  const departments = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })

  const deptName = (id: string) => departments.data?.find((d) => d.id === id)?.name ?? id

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
          <Button size="sm" variant="ghost" onClick={() => navigate(`/agents/${a.id}?tab=releases`)}>新建 Release</Button>
          <Button
            size="sm" variant="ghost"
            onClick={async () => { await copyText(a.mcpUrl); toast('已复制 MCP URL') }}
          >
            复制 MCP URL
          </Button>
        </span>
      ),
    },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">数字员工</h1>
        <Button variant="primary" onClick={() => navigate('/agents/new')}>新建数字员工</Button>
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
              message="还没有数字员工。创建第一个，朱雀会从工具池里帮你挑出它需要的工具。"
              action={<Button variant="primary" onClick={() => navigate('/agents/new')}>创建数字员工</Button>}
            />
          }
        />
      )}
    </div>
  )
}
