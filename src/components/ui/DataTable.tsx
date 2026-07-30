import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface Column<T> {
  key: string
  header: string
  width?: number | string
  align?: 'left' | 'right'
  render: (row: T) => ReactNode
}

// 高密度控制台表格：行高 36px，表头灰阶，无斑马纹。
export function DataTable<T>({ columns, rows, rowKey, onRowClick, empty }: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  onRowClick?: (row: T) => void
  empty?: ReactNode
}) {
  if (rows.length === 0 && empty) {
    return <div className="rounded border border-line bg-surface">{empty}</div>
  }
  return (
    <div className="overflow-x-auto rounded border border-line bg-surface">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-line">
            {columns.map((c) => (
              <th
                key={c.key}
                style={{ width: c.width }}
                className={cn(
                  'h-9 px-3 text-xs font-medium text-ink-muted whitespace-nowrap',
                  c.align === 'right' ? 'text-right' : 'text-left',
                )}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={cn(
                'border-b border-line last:border-b-0',
                onRowClick && 'cursor-pointer hover:bg-canvas',
              )}
            >
              {columns.map((c) => (
                <td
                  key={c.key}
                  className={cn('h-9 px-3 whitespace-nowrap', c.align === 'right' ? 'text-right' : 'text-left')}
                >
                  {c.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
