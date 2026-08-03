import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAgents, fetchReleases } from '@/lib/api'
import { useDepartment } from '@/state/department'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { formatDate } from '@/lib/utils'
import type { Release } from '@/types'

export function Releases() {
  const { deptId } = useDepartment()
  const navigate = useNavigate()
  const releases = useQuery({ queryKey: ['releases', deptId], queryFn: () => fetchReleases(deptId) })
  const agents = useQuery({ queryKey: ['agents', 'all'], queryFn: () => fetchAgents('all') })

  const agentName = (id: string) => agents.data?.find((a) => a.id === id)?.name ?? id

  // 门禁列：全部通过显示 n/n；有阻断则指出是哪条规则。
  function gateSummary(r: Release) {
    if (r.gates.length === 0) return <span className="text-xs text-ink-faint">尚未判定</span>
    const blocked = r.gates.find((g) => g.verdict === 'block')
    if (blocked) {
      return <span className="text-xs font-medium text-block">被 {blocked.ruleId} 规则阻断</span>
    }
    const waived = r.gates.filter((g) => g.verdict === 'waived').length
    return (
      <span className="text-xs text-pass">
        {r.gates.length}/{r.gates.length} 通过
        {waived > 0 && <span className="ml-1 text-warn">（{waived} 条豁免）</span>}
      </span>
    )
  }

  const columns: Column<Release>[] = [
    {
      key: 'version', header: '版本',
      render: (r) => <Link to={`/releases/${r.id}`} className="font-mono text-xs font-medium hover:underline">{r.version}</Link>,
    },
    { key: 'agent', header: '数字员工', render: (r) => <span>{agentName(r.agentId)}</span> },
    { key: 'status', header: '状态', render: (r) => <StatusBadge status={r.status} /> },
    { key: 'gates', header: '门禁', render: gateSummary },
    { key: 'at', header: '创建时间', render: (r) => <span className="text-xs text-ink-muted">{formatDate(r.createdAt)}</span> },
    { key: 'approver', header: '审批人', render: (r) => <span className="text-xs">{r.approvals[0]?.approver ?? '—'}</span> },
  ]

  return (
    <div className="flex max-w-4xl flex-col gap-4">
      <h1 className="text-lg font-semibold">发布</h1>
      {releases.isLoading ? (
        <SkeletonTable rows={6} cols={6} />
      ) : (
        <DataTable
          columns={columns}
          rows={releases.data ?? []}
          rowKey={(r) => r.id}
          onRowClick={(r) => navigate(`/releases/${r.id}`)}
          empty={
            <EmptyState message="当前部门还没有 Release。在数字员工的匹配审核完成后冻结，就会生成第一个 Release。" />
          }
        />
      )}
    </div>
  )
}
