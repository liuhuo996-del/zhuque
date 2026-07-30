import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchApiSources, fetchTools } from '@/mock/api'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { EffectBadge } from '@/components/ui/EffectBadge'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { ErrorState } from '@/components/ui/ErrorState'
import { ProgressBar } from '@/components/ui/ProgressBar'
import { useToast } from '@/components/ui/Toast'
import { cn, formatDate, shortHash } from '@/lib/utils'
import type { Tool } from '@/types'

const enrichLabel = { raw: '未富化', enriched: '已富化', reviewed: '已复核' } as const

export function Tools() {
  const toast = useToast()
  const sources = useQuery({ queryKey: ['sources'], queryFn: fetchApiSources })
  const tools = useQuery({ queryKey: ['tools'], queryFn: fetchTools })

  const [activeSource, setActiveSource] = useState<string | 'all'>('all')
  const [effectFilter, setEffectFilter] = useState('all')
  const [enrichFilter, setEnrichFilter] = useState('all')
  const [sensitiveOnly, setSensitiveOnly] = useState(false)
  const [referencedOnly, setReferencedOnly] = useState(false)

  const [importOpen, setImportOpen] = useState(false)
  const [importUrl, setImportUrl] = useState('')
  const [importError, setImportError] = useState(false)
  const [refetchError, setRefetchError] = useState<string | null>(null)
  const [enriching, setEnriching] = useState<{ percent: number; step: string } | null>(null)

  const rows = useMemo(() => {
    let xs = tools.data ?? []
    if (activeSource !== 'all') xs = xs.filter((t) => t.apiSourceId === activeSource)
    if (effectFilter !== 'all') xs = xs.filter((t) => t.effect === effectFilter)
    if (enrichFilter !== 'all') xs = xs.filter((t) => t.enrichmentStatus === enrichFilter)
    if (sensitiveOnly) xs = xs.filter((t) => t.sensitiveFields.length > 0)
    if (referencedOnly) xs = xs.filter((t) => t.refCount > 0)
    return xs
  }, [tools.data, activeSource, effectFilter, enrichFilter, sensitiveOnly, referencedOnly])

  function runEnrich() {
    const steps = [
      'get_customer_profile (12/183)', 'list_customer_tags (58/183)',
      'merge_customer_accounts (117/183)', 'export_customer_csv (176/183)',
    ]
    steps.forEach((s, i) =>
      window.setTimeout(() => setEnriching({ percent: ((i + 1) / (steps.length + 1)) * 100, step: `正在富化 ${s}` }), i * 700),
    )
    window.setTimeout(() => {
      setEnriching(null)
      toast('批量富化完成：183 个工具（mock）')
    }, steps.length * 700 + 500)
  }

  function refetchSpec(sourceId: string) {
    if (sourceId === 's-ticket') {
      setRefetchError(
        '拉取 spec 失败：GET https://tickets.internal/openapi.json 返回 404。检查 spec_url 是否变更，或改为手动上传。',
      )
    } else {
      setRefetchError(null)
      toast('spec 已重新拉取，hash 无变化（mock）')
    }
  }

  const columns: Column<Tool>[] = [
    {
      key: 'name', header: '名称', width: 240,
      render: (t) => (
        <div className="leading-tight">
          <code className="font-mono text-xs font-medium">{t.name}</code>
          <span className="block truncate text-[11px] text-ink-faint" style={{ maxWidth: 260 }}>{t.description}</span>
        </div>
      ),
    },
    { key: 'ep', header: 'method + path', render: (t) => <code className="font-mono text-[11px] text-ink-muted">{t.method} {t.path}</code> },
    { key: 'effect', header: 'effect', render: (t) => <EffectBadge effect={t.effect} /> },
    {
      key: 'enrich', header: '富化',
      render: (t) => (
        <span className={cn('text-xs', t.enrichmentStatus === 'raw' ? 'font-medium text-warn' : 'text-ink-muted')}>
          {enrichLabel[t.enrichmentStatus]}
        </span>
      ),
    },
    { key: 'token', header: 'token', align: 'right', render: (t) => <span className="font-mono text-xs tabular-nums">{t.tokenCost}</span> },
    { key: 'ref', header: '被引用', align: 'right', render: (t) => <span className="font-mono text-xs tabular-nums">{t.refCount} 个包</span> },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">工具池</h1>
        <div className="flex gap-2">
          <Button onClick={() => { setImportOpen(true); setImportError(false); setImportUrl('') }}>导入 OpenAPI</Button>
          <Button onClick={runEnrich} disabled={!!enriching}>批量富化</Button>
        </div>
      </div>

      {enriching && (
        <div className="max-w-xl rounded border border-line bg-surface p-3">
          <ProgressBar percent={enriching.percent} stepName={enriching.step} />
        </div>
      )}

      <div className="flex gap-4">
        {/* 左侧：api_source 列表 */}
        <aside className="w-[240px] shrink-0">
          <div className="rounded border border-line bg-surface">
            <button
              onClick={() => setActiveSource('all')}
              className={cn('flex h-9 w-full items-center px-3 text-sm', activeSource === 'all' ? 'bg-canvas font-medium' : 'hover:bg-canvas')}
            >
              全部来源
            </button>
            {(sources.data ?? []).map((s) => (
              <button
                key={s.id}
                onClick={() => setActiveSource(s.id)}
                className={cn(
                  'flex w-full flex-col items-start border-t border-line px-3 py-2 text-left text-sm',
                  activeSource === s.id ? 'bg-canvas' : 'hover:bg-canvas',
                )}
              >
                <span className={cn(activeSource === s.id && 'font-medium')}>{s.name}</span>
                <span className="text-[11px] text-ink-faint">
                  {s.toolTotal} 工具 · {s.rawCount > 0 ? `${s.rawCount} 未富化` : '全部已富化'}
                </span>
              </button>
            ))}
          </div>

          {activeSource !== 'all' && sources.data && (
            <div className="mt-3 rounded border border-line bg-surface p-3 text-xs">
              {(() => {
                const s = sources.data.find((x) => x.id === activeSource)!
                return (
                  <>
                    <code className="block break-all font-mono text-[11px] text-ink-muted">{s.specUrl}</code>
                    <p className="mt-1.5 text-ink-faint">
                      hash <code className="font-mono">{shortHash(s.specHash)}</code> · 拉取于 {formatDate(s.lastFetchedAt)}
                    </p>
                    <Button size="sm" className="mt-2" onClick={() => refetchSpec(s.id)}>重新拉取 spec</Button>
                    {refetchError && activeSource === 's-ticket' && (
                      <div className="mt-2">
                        <ErrorState
                          compact
                          what="拉取 spec 失败：返回 404"
                          fix={refetchError.replace('拉取 spec 失败：', '')}
                        />
                      </div>
                    )}
                  </>
                )
              })()}
            </div>
          )}
        </aside>

        {/* 右侧：Tool 表格 */}
        <div className="min-w-0 flex-1">
          <div className="mb-3 flex items-center gap-3 text-xs">
            <select value={effectFilter} onChange={(e) => setEffectFilter(e.target.value)} className="input h-7 text-xs">
              <option value="all">effect：全部</option>
              <option value="read">read</option>
              <option value="write">write</option>
              <option value="delete">delete</option>
              <option value="unknown">unknown</option>
            </select>
            <select value={enrichFilter} onChange={(e) => setEnrichFilter(e.target.value)} className="input h-7 text-xs">
              <option value="all">富化：全部</option>
              <option value="raw">未富化</option>
              <option value="enriched">已富化</option>
              <option value="reviewed">已复核</option>
            </select>
            <label className="flex items-center gap-1.5 text-ink-muted">
              <input type="checkbox" checked={sensitiveOnly} onChange={(e) => setSensitiveOnly(e.target.checked)} />
              仅敏感字段
            </label>
            <label className="flex items-center gap-1.5 text-ink-muted">
              <input type="checkbox" checked={referencedOnly} onChange={(e) => setReferencedOnly(e.target.checked)} />
              仅被引用
            </label>
            <span className="ml-auto font-mono text-ink-faint">{rows.length} 个工具</span>
          </div>

          {tools.isLoading ? (
            <SkeletonTable rows={8} cols={6} />
          ) : (
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(t) => t.id}
              empty={
                <div className="px-6 py-12 text-center text-sm text-ink-muted">
                  没有满足当前筛选条件的工具。放宽筛选，或导入新的 OpenAPI 来源。
                </div>
              }
            />
          )}
        </div>
      </div>

      {/* 导入 OpenAPI 抽屉 */}
      <Drawer open={importOpen} onClose={() => setImportOpen(false)} title="导入 OpenAPI">
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-ink-muted">spec URL</span>
            <input
              value={importUrl}
              onChange={(e) => setImportUrl(e.target.value)}
              placeholder="https://…/openapi.json"
              className="input font-mono text-xs"
            />
            <span className="text-xs text-ink-faint">也可以手动上传文件（v1 后端接入后开放）。试试包含 bad 的 URL 看解析失败的提示。</span>
          </label>
          {importError && (
            <ErrorState
              what="OpenAPI 解析失败"
              fix="第 214 行 $ref '#/components/schemas/OrderItemX' 无法解析。检查 components.schemas 是否缺少 OrderItemX，或上传修正后的文件。"
            />
          )}
          <div>
            <Button
              variant="primary"
              disabled={!importUrl.trim()}
              onClick={() => {
                if (importUrl.includes('bad')) {
                  setImportError(true)
                } else {
                  setImportError(false)
                  setImportOpen(false)
                  toast('已导入，解析出 14 个工具，全部标记为未富化（mock）')
                }
              }}
            >
              解析并导入
            </Button>
          </div>
        </div>
      </Drawer>
    </div>
  )
}
