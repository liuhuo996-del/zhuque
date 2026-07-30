import { Fragment } from 'react'
import { cn } from '@/lib/utils'

export interface Step {
  key: string
  label: string
  sub?: string // 时间 / 操作人
  state: 'done' | 'current' | 'todo' | 'blocked'
}

// Release 状态推进 / 向导步骤共用。blocked（如 rolled_back）用 block 色。
export function StepBar({ steps }: { steps: Step[] }) {
  return (
    <ol className="flex items-start">
      {steps.map((s, i) => (
        <Fragment key={s.key}>
          {i > 0 && (
            <div
              className={cn(
                'mt-[11px] h-px flex-1 min-w-6',
                s.state === 'todo' ? 'bg-line' : s.state === 'blocked' ? 'bg-block/50' : 'bg-ink/50',
              )}
            />
          )}
          <li className="flex flex-col items-center gap-1 px-2">
            <span
              className={cn(
                'flex h-[22px] w-[22px] items-center justify-center rounded-full border text-[11px] font-medium transition-colors duration-300',
                s.state === 'done' && 'border-ink bg-ink text-white',
                s.state === 'current' && 'border-ink text-ink',
                s.state === 'todo' && 'border-line text-ink-faint',
                s.state === 'blocked' && 'border-block bg-block text-white',
              )}
            >
              {s.state === 'done' ? '✓' : s.state === 'blocked' ? '✕' : i + 1}
            </span>
            <span
              className={cn(
                'text-xs whitespace-nowrap',
                s.state === 'current' && 'font-semibold text-ink',
                s.state === 'done' && 'text-ink',
                s.state === 'todo' && 'text-ink-faint',
                s.state === 'blocked' && 'font-semibold text-block',
              )}
            >
              {s.label}
            </span>
            {s.sub && <span className="text-[10px] text-ink-faint whitespace-nowrap">{s.sub}</span>}
          </li>
        </Fragment>
      ))}
    </ol>
  )
}
