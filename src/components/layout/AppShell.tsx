import { useEffect, useMemo, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { LogoLockup } from '@/components/Logo'
import { useDepartment } from '@/state/department'
import { currentOperator, fetchDepartments } from '@/lib/api'
import { cn } from '@/lib/utils'

type IconName = 'overview' | 'agents' | 'tools' | 'packs' | 'releases' | 'trash' | 'departments' | 'settings'

const navGroups: Array<{ label: string; items: Array<{ to: string; label: string; icon: IconName; end?: boolean }> }> = [
  { label: '工作台', items: [{ to: '/', label: '概览', icon: 'overview', end: true }] },
  {
    label: '能力资产',
    items: [
      { to: '/agents', label: '数字员工', icon: 'agents' },
      { to: '/tools', label: '工具池', icon: 'tools' },
      { to: '/packs', label: '能力包', icon: 'packs' },
    ],
  },
  {
    label: '发布治理',
    items: [
      { to: '/releases', label: 'Release 发布', icon: 'releases' },
      { to: '/trash', label: '回收站', icon: 'trash' },
    ],
  },
  {
    label: '系统管理',
    items: [
      { to: '/departments', label: '部门管理', icon: 'departments' },
      { to: '/settings', label: '部署与门禁', icon: 'settings' },
    ],
  },
]

export function AppShell() {
  const { deptId, setDeptId } = useDepartment()
  const { data: departments } = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const navigate = useNavigate()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const portalDepartments = departments ?? []
  const currentDepartment = portalDepartments.find((department) => department.id === deptId)
  const activeLabel = useMemo(() => {
    const item = navGroups.flatMap((group) => group.items)
      .find((candidate) => candidate.end ? location.pathname === candidate.to : location.pathname.startsWith(candidate.to))
    return item?.label ?? '控制面'
  }, [location.pathname])

  useEffect(() => {
    if (deptId !== 'all' && departments && !currentDepartment) setDeptId('all')
  }, [currentDepartment, departments, deptId, setDeptId])

  useEffect(() => setMenuOpen(false), [location.pathname])

  return (
    <div className="flex h-screen h-[100dvh] flex-col overflow-hidden bg-canvas">
      <header className="z-30 flex h-16 shrink-0 items-center border-b border-line bg-surface shadow-[0_1px_2px_rgba(15,23,42,0.03)]">
        <div className="flex h-full w-auto shrink-0 items-center border-r border-line lg:w-64">
          <button
            type="button"
            className="ml-3 flex h-9 w-9 items-center justify-center rounded-md text-ink-muted hover:bg-surface-subtle lg:hidden"
            aria-label="打开导航"
            onClick={() => setMenuOpen((value) => !value)}
          >
            <span className="flex w-4 flex-col gap-1"><i className="h-px bg-current" /><i className="h-px bg-current" /><i className="h-px bg-current" /></span>
          </button>
          <LogoLockup className="border-b-0 bg-transparent" />
        </div>

        <div className="flex min-w-0 flex-1 items-center gap-3 px-4 md:px-6">
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold text-ink">{activeLabel}</div>
            <div className="hidden text-[11px] text-ink-faint sm:block">AI 能力治理与发布控制面</div>
          </div>

          <div className="ml-auto flex items-center gap-2">
            <label className="hidden items-center gap-2 rounded-md border border-line bg-surface px-2.5 shadow-sm sm:flex">
              <NavGlyph name="departments" className="h-4 w-4 text-ink-faint" />
              <select
                aria-label="当前数字部门"
                value={deptId}
                onChange={(event) => setDeptId(event.target.value)}
                className="h-8 max-w-44 bg-transparent pr-2 text-xs font-medium text-ink outline-none"
              >
                <option value="all">全部数字部门</option>
                {portalDepartments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}
              </select>
            </label>
            <button
              type="button"
              className="hidden h-9 items-center rounded-md px-2.5 text-xs font-medium text-ink-muted hover:bg-surface-subtle hover:text-ink md:flex"
              onClick={() => navigate('/departments?create=1')}
            >
              + 新建部门
            </button>
            <div className="mx-1 hidden h-5 w-px bg-line md:block" />
            <div className="flex items-center gap-2" title={`当前操作人：${currentOperator()}`}>
              <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-tint text-[11px] font-semibold text-brand-strong">GF</span>
              <span className="hidden max-w-28 truncate text-xs text-ink-muted xl:block">{currentOperator()}</span>
            </div>
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {menuOpen && <button type="button" className="fixed inset-0 top-16 z-20 bg-ink/20 lg:hidden" aria-label="关闭导航" onClick={() => setMenuOpen(false)} />}
        <aside className={cn(
          'fixed bottom-0 left-0 top-16 z-20 flex w-64 flex-col border-r border-line bg-surface transition-transform duration-200 lg:static lg:z-auto lg:translate-x-0',
          menuOpen ? 'translate-x-0 shadow-xl' : '-translate-x-full',
        )}>
          <div className="border-b border-line p-3 sm:hidden">
            <label className="block text-[11px] font-medium text-ink-faint">当前部门</label>
            <select className="input mt-1 w-full" value={deptId} onChange={(event) => setDeptId(event.target.value)}>
              <option value="all">全部数字部门</option>
              {portalDepartments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}
            </select>
          </div>

          <nav className="min-h-0 flex-1 overflow-y-auto px-3 py-4" aria-label="GateForge 控制面导航">
            {navGroups.map((group, groupIndex) => (
              <section key={group.label} className={cn(groupIndex > 0 && 'mt-5')}>
                <h2 className="mb-1.5 px-3 text-[11px] font-medium tracking-[0.08em] text-ink-faint">{group.label}</h2>
                <div className="space-y-1">
                  {group.items.map((item) => (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      end={item.end}
                      className={({ isActive }) => cn(
                        'group flex h-11 items-center gap-3 rounded-md px-3 text-sm transition-colors duration-150',
                        isActive
                          ? 'bg-brand-tint font-medium text-brand-strong'
                          : 'text-ink-muted hover:bg-surface-subtle hover:text-ink',
                      )}
                    >
                      {({ isActive }) => <><NavGlyph name={item.icon} className={cn('h-[18px] w-[18px]', isActive ? 'text-brand' : 'text-ink-faint group-hover:text-ink-muted')} /><span>{item.label}</span></>}
                    </NavLink>
                  ))}
                </div>
              </section>
            ))}
          </nav>

          <div className="border-t border-line p-4">
            <div className="rounded-lg bg-surface-subtle px-3 py-2.5">
              <div className="flex items-center gap-2 text-xs font-medium text-ink">
                <span className="h-2 w-2 rounded-full bg-pass" />
                Control plane online
              </div>
              <code className="mt-1 block font-mono text-[10px] text-ink-faint">GateForge v1.0</code>
            </div>
          </div>
        </aside>

        <main className="min-w-0 flex-1 overflow-y-auto p-4 md:p-6 xl:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function NavGlyph({ name, className }: { name: IconName; className?: string }) {
  const common = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  return (
    <svg viewBox="0 0 24 24" aria-hidden className={className} {...common}>
      {name === 'overview' && <><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><path d="M14 17.5h7M17.5 14v7" /></>}
      {name === 'agents' && <><circle cx="9" cy="8" r="3" /><path d="M3.5 19c.6-3.2 2.4-4.8 5.5-4.8s4.9 1.6 5.5 4.8" /><circle cx="17.5" cy="9.5" r="2.3" /><path d="M15.7 14.6c2.9-.7 4.6.6 5 3.5" /></>}
      {name === 'tools' && <><path d="m14.2 5.2 4.6 4.6M13 7l4-4 4 4-4 4" /><path d="m14.5 9.5-8.8 8.8a1.4 1.4 0 0 1-2-2l8.8-8.8" /><circle cx="6.5" cy="15.5" r=".7" fill="currentColor" stroke="none" /></>}
      {name === 'packs' && <><path d="m12 3 8 4.2-8 4.2-8-4.2L12 3Z" /><path d="m4 11.5 8 4.2 8-4.2M4 15.8l8 4.2 8-4.2" /></>}
      {name === 'releases' && <><path d="M12 15V3m0 0L7.5 7.5M12 3l4.5 4.5" /><path d="M5 12.5v6A2.5 2.5 0 0 0 7.5 21h9a2.5 2.5 0 0 0 2.5-2.5v-6" /></>}
      {name === 'trash' && <><path d="M4 7h16M9 7V4h6v3M6.5 7l.8 14h9.4l.8-14M10 11v6M14 11v6" /></>}
      {name === 'departments' && <><path d="M4 21V6l8-3 8 3v15M2 21h20" /><path d="M8 8h2m4 0h2M8 12h2m4 0h2M8 16h2m4 0h2" /></>}
      {name === 'settings' && <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3v-.2h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z" /></>}
    </svg>
  )
}
