import { cn } from '@/lib/utils'

// 长任务用带百分比和当前步骤名的进度条，不用转圈。
export function ProgressBar({ percent, stepName, warn }: {
  percent: number
  stepName?: string
  warn?: boolean
}) {
  const p = Math.max(0, Math.min(100, percent))
  return (
    <div>
      <div className="flex items-center justify-between text-xs text-ink-muted">
        {stepName && <span className="truncate font-mono">{stepName}</span>}
        <span className="ml-auto tabular-nums">{Math.round(p)}%</span>
      </div>
      <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-line">
        <div
          className={cn('h-full rounded-full', warn ? 'bg-warn' : 'bg-ink')}
          style={{ width: `${p}%` }}
        />
      </div>
    </div>
  )
}

// 预算条：占用/上限，超限变 warn。
export function BudgetBar({ label, used, max, unit }: {
  label: string
  used: number
  max: number
  unit?: string
}) {
  const over = used > max
  const p = Math.min(100, (used / max) * 100)
  return (
    <div>
      <div className="flex items-baseline justify-between text-xs">
        <span className="text-ink-muted">{label}</span>
        <span className={cn('font-mono tabular-nums', over ? 'font-medium text-warn' : 'text-ink')}>
          {used.toLocaleString()}{unit ? ` ${unit}` : ''} / {max.toLocaleString()}{unit ? ` ${unit}` : ''}
        </span>
      </div>
      <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-line">
        <div className={cn('h-full rounded-full', over ? 'bg-warn' : 'bg-ink/70')} style={{ width: `${p}%` }} />
      </div>
    </div>
  )
}
