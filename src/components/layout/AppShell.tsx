import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useMemo, useState } from 'react'
import { LogoLockup } from '@/components/Logo'
import { cn } from '@/lib/utils'

type Icon = 'overview' | 'intake' | 'catalog' | 'graphs' | 'packs' | 'registry' | 'settings'
const groups = [
  { label: '工程工作台', items: [
    { to: '/', label: '工程概览', icon: 'overview' as Icon, end: true },
    { to: '/intake', label: 'API 接入分析', icon: 'intake' as Icon },
    { to: '/catalog', label: '工具目录', icon: 'catalog' as Icon },
    { to: '/graphs', label: '能力图谱', icon: 'graphs' as Icon },
  ]},
  { label: '编译交付', items: [
    { to: '/packs', label: 'MCP 能力包', icon: 'packs' as Icon },
    { to: '/registry', label: 'Nacos 适配器', icon: 'registry' as Icon },
  ]},
  { label: '运行边界', items: [{ to: '/settings', label: '系统边界', icon: 'settings' as Icon }] },
]

export function AppShell() {
  const [open, setOpen] = useState(false)
  const location = useLocation()
  const active = useMemo(() => groups.flatMap((g) => g.items).find((item) => item.end ? location.pathname === '/' : location.pathname.startsWith(item.to)), [location.pathname])
  return (
    <div className="flex h-[100dvh] flex-col overflow-hidden bg-canvas">
      <header className="z-30 flex h-16 shrink-0 items-center border-b border-line bg-surface shadow-sm">
        <div className="flex h-full w-auto items-center border-r border-line lg:w-64">
          <button className="ml-3 h-9 w-9 rounded-md text-ink-muted lg:hidden" onClick={() => setOpen(!open)}>☰</button>
          <LogoLockup className="border-b-0 bg-transparent" />
        </div>
        <div className="flex min-w-0 flex-1 items-center px-5 md:px-7">
          <div><div className="text-sm font-semibold text-ink">{active?.label ?? 'GateForge'}</div><div className="text-[11px] text-ink-faint">API → 高质量 MCP 能力包工程化</div></div>
          <div className="ml-auto hidden items-center gap-2 rounded-full bg-brand-tint px-3 py-1.5 text-xs font-medium text-brand-strong sm:flex"><span className="h-2 w-2 rounded-full bg-brand" />Python 引擎 0.2 版</div>
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        {open && <button className="fixed inset-0 top-16 z-20 bg-ink/20 lg:hidden" onClick={() => setOpen(false)} />}
        <aside className={cn('fixed bottom-0 left-0 top-16 z-20 flex w-64 flex-col border-r border-line bg-surface transition-transform lg:static lg:translate-x-0', open ? 'translate-x-0' : '-translate-x-full')}>
          <nav className="min-h-0 flex-1 overflow-y-auto px-3 py-4">
            {groups.map((group, i) => <section key={group.label} className={cn(i && 'mt-5')}>
              <h2 className="mb-1.5 px-3 text-[11px] font-medium tracking-[.08em] text-ink-faint">{group.label}</h2>
              <div className="space-y-1">{group.items.map((item) => <NavLink key={item.to} to={item.to} end={item.end} onClick={() => setOpen(false)} className={({ isActive }) => cn('flex h-11 items-center gap-3 rounded-md px-3 text-sm transition-colors', isActive ? 'bg-brand-tint font-medium text-brand-strong' : 'text-ink-muted hover:bg-surface-subtle hover:text-ink')}><Glyph name={item.icon} /><span>{item.label}</span></NavLink>)}</div>
            </section>)}
          </nav>
          <div className="border-t border-line p-4"><div className="rounded-lg bg-surface-subtle p-3"><div className="text-xs font-medium text-ink">GateForge 0.2 版</div><p className="mt-1 text-[11px] leading-4 text-ink-faint">不实现注册中心、生命周期或 MCP 数据面</p></div></div>
        </aside>
        <main className="min-w-0 flex-1 overflow-y-auto p-4 md:p-6 xl:p-8"><Outlet /></main>
      </div>
    </div>
  )
}

function Glyph({ name }: { name: Icon }) {
  const paths: Record<Icon, string> = {
    overview: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
    intake: 'M12 3v12m0 0-4-4m4 4 4-4M4 18v2h16v-2',
    catalog: 'M5 4h14v16H5zM8 8h8M8 12h8M8 16h5',
    graphs: 'M5 4h5v5H5zM14 3h5v5h-5zM14 16h5v5h-5zM10 6.5h4m2.5 1.5v8M10 8l4 9',
    packs: 'm12 3 8 4-8 4-8-4 8-4zM4 12l8 4 8-4M4 17l8 4 8-4',
    registry: 'M4 6c0-2 16-2 16 0s-16 2-16 0zm0 0v12c0 2 16 2 16 0V6M4 12c0 2 16 2 16 0',
    settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm0-5v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4',
  }
  return <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>
}
