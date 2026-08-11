import { useEffect, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

// 列表内的轻量查看用 Drawer；Release 详情与新建向导用整页。
export function Drawer({ open, onClose, title, children, width = 480 }: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
  width?: number
}) {
  const closeButton = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    window.setTimeout(() => closeButton.current?.focus(), 0)
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
    }
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-40">
      <div className="absolute inset-0 bg-ink/25 backdrop-blur-[1px]" onClick={onClose} />
      <div
        className={cn('absolute right-0 top-0 h-full overflow-y-auto rounded-l-lg border-l border-line bg-surface shadow-2xl')}
        style={{ width: `min(${width}px, calc(100vw - 16px))` }}
        role="dialog"
        aria-label={title}
        aria-modal="true"
      >
        <div className="sticky top-0 z-10 flex h-14 items-center justify-between border-b border-line bg-surface/95 px-5 backdrop-blur">
          <h2 className="text-base font-semibold">{title}</h2>
          <button ref={closeButton} onClick={onClose} aria-label="关闭" className="flex h-8 w-8 items-center justify-center rounded-md text-lg text-ink-muted hover:bg-brand-tint hover:text-brand-strong">
×
          </button>
        </div>
        <div className="p-5">{children}</div>
      </div>
    </div>
  )
}
