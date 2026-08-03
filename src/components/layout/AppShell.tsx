import { useEffect } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { LogoLockup } from '@/components/Logo'
import { useDepartment } from '@/state/department'
import { fetchDepartments } from '@/lib/api'
import { cn } from '@/lib/utils'

const nav = [
  { to: '/', label: '概览', end: true },
  { to: '/agents', label: '数字员工' },
  { to: '/tools', label: '工具池' },
  { to: '/packs', label: '能力包' },
  { to: '/releases', label: '发布' },
  { to: '/trash', label: '回收站' },
  { to: '/departments', label: '部门管理' },
  { to: '/settings', label: '设置' },
]

export function AppShell() {
  const { deptId, setDeptId } = useDepartment()
  const { data: departments } = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const navigate = useNavigate()
  const portalDepartments = departments ?? []
  const currentDepartment = portalDepartments.find((department) => department.id === deptId)

  // 数据库清空或部门被外部删除后，浏览器可能还保留旧的门户选择。
  // 此时回到“全部部门”，避免把用户困在一个不存在的筛选条件里。
  useEffect(() => {
    if (deptId !== 'all' && departments && !currentDepartment) setDeptId('all')
  }, [currentDepartment, departments, deptId, setDeptId])

  return (
    <div className="flex h-screen">
      <aside className="flex w-[232px] shrink-0 flex-col border-r border-line bg-surface">
        <LogoLockup />
        <section className="border-b border-line px-3 py-3" aria-label="数字部门门户">
          <div className="mb-2 flex items-center justify-between px-1">
            <span className="text-[11px] font-medium tracking-[0.08em] text-ink-muted">数字部门门户</span>
            <button
              type="button"
              className="rounded px-1.5 py-0.5 text-xs font-medium text-ink-muted hover:bg-canvas hover:text-ink"
              onClick={() => navigate('/departments?create=1')}
            >
              + 新建
            </button>
          </div>
          <div className="flex max-h-52 flex-col gap-0.5 overflow-y-auto">
            <button
              type="button"
              aria-pressed={deptId === 'all'}
              onClick={() => setDeptId('all')}
              className={cn(
                'flex min-h-8 w-full items-center rounded px-2.5 text-left text-sm',
                deptId === 'all' ? 'bg-canvas font-medium text-ink' : 'text-ink-muted hover:bg-canvas hover:text-ink',
              )}
            >
              全部部门
              <span className="ml-auto font-mono text-[10px] text-ink-faint">{portalDepartments.length}</span>
            </button>
            {departments === undefined ? (
              <div className="space-y-1 px-2.5 py-1.5" aria-label="正在加载数字部门">
                <div className="skeleton h-3 w-24" />
                <div className="skeleton h-3 w-16" />
              </div>
            ) : portalDepartments.length === 0 ? (
              <button
                type="button"
                onClick={() => navigate('/departments?create=1')}
                className="rounded px-2.5 py-2 text-left text-xs leading-5 text-ink-muted hover:bg-canvas hover:text-ink"
              >
                尚无数字部门<br />先创建第一个部门
              </button>
            ) : (
              portalDepartments.map((department) => (
                <button
                  key={department.id}
                  type="button"
                  aria-pressed={deptId === department.id}
                  title={`进入 ${department.name} 门户`}
                  onClick={() => setDeptId(department.id)}
                  className={cn(
                    'flex w-full flex-col items-start rounded px-2.5 py-1.5 text-left',
                    deptId === department.id ? 'bg-canvas text-ink' : 'text-ink-muted hover:bg-canvas hover:text-ink',
                  )}
                >
                  <span className={cn('max-w-full truncate text-sm', deptId === department.id && 'font-medium')}>
                    {department.name}
                  </span>
                  <code className="max-w-full truncate font-mono text-[10px] text-ink-faint">{department.slug}</code>
                </button>
              ))
            )}
          </div>
          <button
            type="button"
            onClick={() => navigate('/departments')}
            className="mt-2 w-full rounded border border-line px-2.5 py-1.5 text-left text-xs text-ink-muted hover:bg-canvas hover:text-ink"
          >
            管理数字部门
          </button>
        </section>

        <nav className="flex-1 py-2" aria-label="控制面导航">
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
          <code className="font-mono">zhuque v1 · control plane</code>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-surface px-5">
          <span className="text-xs text-ink-muted">当前门户</span>
          <span className="text-sm font-medium">{currentDepartment?.name ?? '全部部门'}</span>
          {currentDepartment && (
            <code className="font-mono text-[11px] text-ink-faint">{currentDepartment.consumerGroupRef}</code>
          )}
          <div className="ml-auto flex items-center gap-2 text-xs text-ink-muted">
            控制面 <code className="rounded border border-line px-1.5 py-0.5 font-mono">v1</code>
          </div>
        </header>
        <main className="min-h-0 flex-1 overflow-y-auto p-5">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
