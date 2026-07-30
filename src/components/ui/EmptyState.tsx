import type { ReactNode } from 'react'

// 空态是行动邀请：具体文案 + 一个主行动按钮。禁止插画和「暂无数据」。
export function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-16 px-6 text-center">
      <p className="max-w-md text-sm text-ink-muted">{message}</p>
      {action}
    </div>
  )
}
