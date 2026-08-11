import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface Column<T> {
  key: string
  header: string
  width?: number | string
  align?: 'left' | 'right'
  render: (row: T) => ReactNode
}

export function DataTable<T>({ columns, rows, rowKey, onRowClick, empty }: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  onRowClick?: (row: T) => void
  empty?: ReactNode
}) {
  if (rows.length === 0 && empty) {
    return <div className="overflow-hidden rounded-lg border border-line bg-surface shadow-panel">{empty}</div>
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-panel">
      <table className="w-full border-collapse text-sm">
        <thead className="bg-[#f8fafc]">
          <tr className="border-b border-line">
            {columns.map((c) => (
              <th
                key={c.key}
                style={{ width: c.width }}
                className={cn(
                  'h-10 px-4 text-xs font-semibold text-ink-muted whitespace-nowrap',
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
                'border-b border-line last:border-b-0 transition-colors duration-100',
                onRowClick ? 'cursor-pointer hover:bg-brand-tint' : 'hover:bg-surface-subtle',
              )}
            >
              {columns.map((c) => (
                <td
                  key={c.key}
                  className={cn('h-11 px-4 whitespace-nowrap', c.align === 'right' ? 'text-right' : 'text-left')}
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
