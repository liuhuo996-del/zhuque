import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * 错误一律说明「发生了什么 + 怎么修」，不道歉不含糊。
 * what: 发生了什么；fix: 怎么修；action: 修复入口按钮。
 */
export function ErrorState({ what, fix, action, compact }: {
  what: string
  fix: string
  action?: ReactNode
  compact?: boolean
}) {
  return (
    <div className={cn(
      'rounded border border-block/30 bg-[var(--block-tint)]',
      compact ? 'p-3' : 'p-4',
    )}>
      <p className="text-sm font-medium text-block">{what}</p>
      <p className="mt-1 text-sm text-ink-muted">{fix}</p>
      {action && <div className="mt-3">{action}</div>}
    </div>
  )
}
