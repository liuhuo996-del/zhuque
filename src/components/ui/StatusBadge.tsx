import { cn } from '@/lib/utils'
import type { AgentStatus, ReleaseStatus } from '@/types'

// 状态徽章：released / rolled_back 是系统判定的终点，允许着色；其余灰阶。
const releaseMap: Record<ReleaseStatus, { label: string; cls: string }> = {
  draft: { label: '草稿', cls: 'text-ink-muted border-line' },
  candidate: { label: '候选', cls: 'text-ink-muted border-line' },
  tested: { label: '已测试', cls: 'text-ink border-line' },
  approved: { label: '已审批', cls: 'text-ink border-ink/40' },
  released: { label: '已发布', cls: 'text-pass border-pass/40 bg-[var(--pass-tint)]' },
  superseded: { label: '已被取代', cls: 'text-ink-faint border-line' },
  rolled_back: { label: '已回滚', cls: 'text-block border-block/40 bg-[var(--block-tint)]' },
}

const agentMap: Record<AgentStatus, { label: string; cls: string }> = {
  draft: { label: '草稿', cls: 'text-ink-muted border-line' },
  active: { label: '运行中', cls: 'text-ink border-line' },
  suspended: { label: '已暂停', cls: 'text-warn border-warn/40' },
  retired: { label: '已退役', cls: 'text-ink-faint border-line' },
}

export function StatusBadge({ status, kind = 'release' }: {
  status: ReleaseStatus | AgentStatus
  kind?: 'release' | 'agent'
}) {
  const m = kind === 'release'
    ? releaseMap[status as ReleaseStatus]
    : agentMap[status as AgentStatus]
  return (
    <span className={cn('inline-flex items-center h-5 px-1.5 rounded border text-xs font-medium', m.cls)}>
      {m.label}
      <code className="ml-1 font-mono text-[10px] opacity-70">{status}</code>
    </span>
  )
}
