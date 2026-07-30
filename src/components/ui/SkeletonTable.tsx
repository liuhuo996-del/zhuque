// 表格骨架屏：与真实表格同为 36px 行高，避免加载完成时跳动。
export function SkeletonTable({ rows = 6, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div className="rounded border border-line bg-surface">
      <div className="flex h-9 items-center gap-4 border-b border-line px-4">
        {Array.from({ length: cols }).map((_, i) => (
          <div key={i} className="skeleton h-3 flex-1" style={{ maxWidth: i === 0 ? 180 : 110 }} />
        ))}
      </div>
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="flex h-9 items-center gap-4 border-b border-line px-4 last:border-b-0">
          {Array.from({ length: cols }).map((_, c) => (
            <div key={c} className="skeleton h-3 flex-1" style={{ maxWidth: c === 0 ? 180 : 110, animationDelay: `${r * 80}ms` }} />
          ))}
        </div>
      ))}
    </div>
  )
}
