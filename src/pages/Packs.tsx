import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchAgents, fetchDepartments, fetchPacks, fetchTools } from '@/lib/api'
import { useDepartment } from '@/state/department'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { EmptyState } from '@/components/ui/EmptyState'
import { Drawer } from '@/components/ui/Drawer'
import { EffectBadge } from '@/components/ui/EffectBadge'
import type { Pack } from '@/types'

// v1 能力包以只读为主，编辑入口在数字员工详情 / 新建向导的匹配审核里。
export function Packs() {
  const { deptId } = useDepartment()
  const packs = useQuery({ queryKey: ['packs', deptId], queryFn: () => fetchPacks(deptId) })
  const departments = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const agents = useQuery({ queryKey: ['agents', 'all'], queryFn: () => fetchAgents('all') })
  const tools = useQuery({ queryKey: ['tools'], queryFn: fetchTools })
  const [openPack, setOpenPack] = useState<Pack | null>(null)

  const deptName = (id: string) => departments.data?.find((d) => d.id === id)?.name ?? id
  const agentNames = (ids: string[]) =>
    ids.map((id) => agents.data?.find((a) => a.id === id)?.name ?? id).join('、') || '—'

  const columns: Column<Pack>[] = [
    { key: 'name', header: '名称', render: (p) => <span className="font-medium">{p.name}</span> },
    { key: 'dept', header: '部门', render: (p) => <span className="text-ink-muted">{deptName(p.departmentId)}</span> },
    { key: 'scope', header: 'scope', render: (p) => <code className="font-mono text-xs text-ink-muted">{p.scope}</code> },
    { key: 'count', header: '工具数', align: 'right', render: (p) => <span className="font-mono text-xs tabular-nums">{p.toolIds.length}</span> },
    { key: 'used', header: '使用它的数字员工', render: (p) => <span className="text-xs">{agentNames(p.usedByAgentIds)}</span> },
  ]

  return (
    <div className="page-shell">
      <div className="border-b border-line pb-5">
        <h1 className="page-title">能力包</h1>
        <p className="page-description">v1 中能力包由匹配审核生成。点击查看包内工具与使用它的数字员工。</p>
      </div>
      {packs.isLoading ? (
        <SkeletonTable rows={3} cols={5} />
      ) : (
        <DataTable
          columns={columns}
          rows={packs.data ?? []}
          rowKey={(p) => p.id}
          onRowClick={setOpenPack}
          empty={
            <EmptyState message="当前部门还没有能力包。到 数字员工 新建一个，匹配审核确认后会生成对应的能力包。" />
          }
        />
      )}

      <Drawer open={!!openPack} onClose={() => setOpenPack(null)} title={openPack?.name ?? ''}>
        <div className="flex flex-col gap-2">
          {openPack && (tools.data ?? [])
            .filter((t) => openPack.toolIds.includes(t.id))
            .map((t) => (
              <div key={t.id} className="flex items-center gap-2 rounded border border-line p-2.5">
                <code className="font-mono text-xs">{t.name}</code>
                <EffectBadge effect={t.effect} className="h-4 px-1 text-[9px]" />
                <code className="ml-auto font-mono text-[11px] text-ink-faint">{t.method} {t.path}</code>
              </div>
            ))}
        </div>
      </Drawer>
    </div>
  )
}
