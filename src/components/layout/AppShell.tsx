import { NavLink, Outlet } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { LogoLockup } from '@/components/Logo'
import { useDepartment } from '@/state/department'
import { fetchDepartments } from '@/mock/api'
import { cn } from '@/lib/utils'

const nav = [
  { to: '/', label: '概览', end: true },
  { to: '/agents', label: '数字员工' },
  { to: '/tools', label: '工具池' },
  { to: '/packs', label: '能力包' },
  { to: '/releases', label: '发布' },
  { to: '/settings', label: '设置' },
]

export function AppShell() {
  const { deptId, setDeptId } = useDepartment()
  const { data: departments } = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })

  return (
    <div className="flex h-screen">
      <aside className="flex w-[196px] shrink-0 flex-col border-r border-line bg-surface">
        <LogoLockup />
        <nav className="flex-1 py-2">
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'relative mx-2 flex h-8 items-center rounded px-3 text-sm',
                  isActive
                    ? 'bg-canvas font-medium text-ink before:absolute before:left-0 before:top-1.5 before:h-5 before:w-0.5 before:rounded before:bg-ink'
                    : 'text-ink-muted hover:bg-canvas hover:text-ink',
                )
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-line px-4 py-3 text-[11px] text-ink-faint">
          <code className="font-mono">zhuque v0.1 · mock</code>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-surface px-5">
          <label className="text-xs text-ink-muted" htmlFor="dept-switch">数字部门</label>
          <select
            id="dept-switch"
            value={deptId}
            onChange={(e) => setDeptId(e.target.value)}
            className="h-8 rounded border border-line bg-surface px-2 text-sm"
          >
            <option value="all">全部部门</option>
            {(departments ?? []).map((d) => (
              <option key={d.id} value={d.id}>{d.name}</option>
            ))}
          </select>
          <div className="ml-auto flex items-center gap-2 text-xs text-ink-muted">
            环境 <code className="rounded border border-line px-1.5 py-0.5 font-mono">prod</code>
          </div>
        </header>
        <main className="min-h-0 flex-1 overflow-y-auto p-5">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
