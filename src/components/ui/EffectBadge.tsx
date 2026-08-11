import { cn } from '@/lib/utils'
import type { Effect } from '@/types'

// effect 是机器判定，因此着色：read 蓝 / write 同 warn / delete 同 block。
const map: Record<Effect, string> = {
  read: 'bg-blue-50 text-effect-read border-blue-200',
  write: 'bg-amber-50 text-effect-write border-amber-200',
  delete: 'bg-red-50 text-effect-delete border-red-200',
  unknown: 'bg-surface-subtle text-ink-faint border-line',
}

export function EffectBadge({ effect, className }: { effect: Effect; className?: string }) {
  return (
    <span className={cn('inline-flex h-5 items-center rounded-full border px-2 font-mono text-[11px]', map[effect], className)}>
      {effect}
    </span>
  )
}
