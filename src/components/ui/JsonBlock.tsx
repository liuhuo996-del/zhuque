import { useMemo, useState } from 'react'
import { cn } from '@/lib/utils'

/**
 * 可折叠 JSON 查看器（等宽）。diffWith 打开后：与上一版逐行对比，
 * 仅出现在当前版的行高亮（简化 diff，mock 用足够）。
 */
export function JsonBlock({ title, data, diffWith, defaultOpen = true }: {
  title: string
  data: unknown
  diffWith?: unknown
  defaultOpen?: boolean
}) {
  const [showDiff, setShowDiff] = useState(false)

  const lines = useMemo(() => JSON.stringify(data, null, 2).split('\n'), [data])
  const prevSet = useMemo(
    () => (diffWith !== undefined ? new Set(JSON.stringify(diffWith, null, 2).split('\n')) : null),
    [diffWith],
  )

  return (
    <details open={defaultOpen} className="rounded border border-line bg-surface">
      <summary className="flex h-9 cursor-pointer select-none items-center justify-between px-3 text-xs font-medium text-ink-muted">
        <code className="font-mono">{title}</code>
        {diffWith !== undefined && (
          <label className="flex items-center gap-1.5 font-normal" onClick={(e) => e.stopPropagation()}>
            <input type="checkbox" checked={showDiff} onChange={(e) => setShowDiff(e.target.checked)} />
            与上一版 diff
          </label>
        )}
      </summary>
      <pre className="overflow-x-auto border-t border-line p-3 text-xs leading-5">
        {lines.map((l, i) => (
          <div
            key={i}
            className={cn(showDiff && prevSet && !prevSet.has(l) && 'bg-[var(--warn-tint)]')}
          >
            {l || ' '}
          </div>
        ))}
      </pre>
    </details>
  )
}
