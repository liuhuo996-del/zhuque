import { useEffect, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

// 列表内的轻量查看用 Drawer；Release 详情与新建向导用整页。
export function Drawer({ open, onClose, title, children, width = 480 }: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
  width?: number
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-40">
      <div className="absolute inset-0 bg-ink/20" onClick={onClose} />
      <div
        className={cn('absolute right-0 top-0 h-full overflow-y-auto border-l border-line bg-surface shadow-xl')}
        style={{ width }}
        role="dialog"
        aria-label={title}
      >
        <div className="sticky top-0 flex h-12 items-center justify-between border-b border-line bg-surface px-4">
          <h2 className="text-sm font-semibold">{title}</h2>
          <button onClick={onClose} aria-label="关闭" className="rounded px-2 py-1 text-ink-muted hover:bg-canvas">
            ✕
          </button>
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  )
}
