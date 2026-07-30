import { cn } from '@/lib/utils'
import type { Effect } from '@/types'

// effect 是机器判定，因此着色：read 蓝 / write 同 warn / delete 同 block。
const map: Record<Effect, string> = {
  read: 'text-effect-read border-effect-read/40',
  write: 'text-effect-write border-effect-write/40',
  delete: 'text-effect-delete border-effect-delete/40',
  unknown: 'text-ink-faint border-line',
}

export function EffectBadge({ effect, className }: { effect: Effect; className?: string }) {
  return (
    <span className={cn('inline-flex h-5 items-center rounded border px-1.5 font-mono text-[11px]', map[effect], className)}>
      {effect}
    </span>
  )
}
