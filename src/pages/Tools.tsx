import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DataTable, type Column } from '@/components/ui/DataTable'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { EffectBadge } from '@/components/ui/EffectBadge'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { ErrorState } from '@/components/ui/ErrorState'
import { ProgressBar } from '@/components/ui/ProgressBar'
import { useToast } from '@/components/ui/Toast'
import {
  enrichTools, fetchApiSources, fetchJob, fetchTools, importApiSource, refetchApiSource,
  reviewTool, trashApiSource, ApiError,
} from '@/lib/api'
import { cn, formatDate, shortHash } from '@/lib/utils'
import type { ApiSource, JobProgress, Tool } from '@/types'

const enrichLabel = { raw: '未富化', enriched: '已富化', reviewed: '已复核' } as const

export function Tools() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const sources = useQuery({ queryKey: ['sources'], queryFn: () => fetchApiSources() })
  const tools = useQuery({ queryKey: ['tools'], queryFn: fetchTools })
  const [activeSource, setActiveSource] = useState<string | 'all'>('all')
  const [effectFilter, setEffectFilter] = useState('all')
  const [enrichFilter, setEnrichFilter] = useState('all')
  const [sensitiveOnly, setSensitiveOnly] = useState(false)
  const [referencedOnly, setReferencedOnly] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [retiring, setRetiring] = useState<ApiSource | null>(null)
  const [trashReason, setTrashReason] = useState('')
  const [operationError, setOperationError] = useState<ApiError | null>(null)
  const [job, setJob] = useState<JobProgress | null>(null)

  const rows = useMemo(() => {
    let values = tools.data ?? []
    if (activeSource !== 'all') values = values.filter((tool) => tool.apiSourceId === activeSource)
    if (effectFilter !== 'all') values = values.filter((tool) => tool.effect === effectFilter)
    if (enrichFilter !== 'all') values = values.filter((tool) => tool.enrichmentStatus === enrichFilter)
    if (sensitiveOnly) values = values.filter((tool) => tool.sensitiveFields.length > 0)
    if (referencedOnly) values = values.filter((tool) => tool.refCount > 0)
    return values
  }, [tools.data, activeSource, effectFilter, enrichFilter, sensitiveOnly, referencedOnly])

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['sources'] }),
      queryClient.invalidateQueries({ queryKey: ['tools'] }),
      queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
    ])
  }

  const enrich = useMutation({
    mutationFn: async () => {
      const ids = rows.filter((tool) => tool.enrichmentStatus === 'raw').map((tool) => tool.id)
      if (ids.length === 0) throw new ApiError('没有待富化工具', '切换来源或筛选“未富化”工具', 400)
      const started = await enrichTools(ids)
      for (;;) {
        const progress = await fetchJob(started.jobId)
        setJob(progress)
        if (progress.state !== 'running') return progress
        await new Promise((resolve) => window.setTimeout(resolve, 500))
      }
    },
    onSuccess: async (progress) => {
      if (progress.state === 'failed') {
        setOperationError(new ApiError('工具富化失败', progress.error ?? '检查模型配置后重试', 500))
        return
      }
      await refresh()
      toast('工具富化完成，结果已写入正式工具池')
    },
    onError: (error) => setOperationError(error as ApiError),
  })

  const refetch = useMutation({
    mutationFn: (id: string) => refetchApiSource(id, true),
    onSuccess: async (result) => {
      setOperationError(null)
      await refresh()
      toast(result.unchanged ? 'spec_hash 未变化' : result.applied ? '已应用 endpoint 级 spec 变更' : '已生成 spec 变更候选')
    },
    onError: (error) => setOperationError(error as ApiError),
  })

  const retire = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => trashApiSource(id, reason),
    onSuccess: async () => {
      setRetiring(null)
      setTrashReason('')
      setActiveSource('all')
      setOperationError(null)
      await refresh()
      toast('REST API 已移入回收站')
    },
    onError: (error) => setOperationError(error as ApiError),
  })

  const review = useMutation({
    mutationFn: reviewTool,
    onSuccess: async () => {
      await refresh()
      toast('工具已人工复核')
    },
    onError: (error) => setOperationError(error as ApiError),
  })

  const columns: Column<Tool>[] = [
    {
      key: 'name', header: '名称', width: 240,
      render: (tool) => (
        <div className="leading-tight">
          <code className="font-mono text-xs font-medium">{tool.name}</code>
          <span className="block max-w-[260px] truncate text-[11px] text-ink-faint">{tool.description}</span>
        </div>
      ),
    },
    { key: 'ep', header: 'method + path', render: (tool) => <code className="font-mono text-[11px] text-ink-muted">{tool.method} {tool.path}</code> },
    { key: 'effect', header: 'effect', render: (tool) => <EffectBadge effect={tool.effect} /> },
    {
      key: 'enrich', header: '富化', render: (tool) => (
        <span className={cn('text-xs', tool.enrichmentStatus === 'raw' ? 'font-medium text-warn' : 'text-ink-muted')}>
          {enrichLabel[tool.enrichmentStatus]}
        </span>
      ),
    },
    { key: 'token', header: 'token', align: 'right', render: (tool) => <span className="font-mono text-xs tabular-nums">{tool.tokenCost}</span> },
    { key: 'ref', header: '被引用', align: 'right', render: (tool) => <span className="font-mono text-xs tabular-nums">{tool.refCount} 个包</span> },
    {
      key: 'actions', header: '', align: 'right', render: (tool) => tool.enrichmentStatus === 'enriched'
        ? <Button size="sm" variant="ghost" onClick={() => review.mutate(tool.id)}>确认复核</Button>
        : null,
    },
  ]

  const selectedSource = activeSource === 'all' ? null : sources.data?.find((source) => source.id === activeSource) ?? null

  return (
    <div className="page-shell">
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-5">
        <div>
          <h1 className="page-title">工具池</h1>
          <p className="page-description">导入、富化并复核企业 REST API，形成可发布的工具资产。</p>
        </div>
        <div className="flex gap-2">
          <Button onClick={() => { setImportOpen(true); setOperationError(null) }}>导入 OpenAPI</Button>
          <Button onClick={() => enrich.mutate()} disabled={enrich.isPending}>批量富化当前结果</Button>
        </div>
      </div>

      {job && (enrich.isPending || job.state === 'failed') && (
        <div className="max-w-xl rounded border border-line bg-surface p-3">
          <ProgressBar percent={job.total ? (job.done / job.total) * 100 : 0} stepName={job.currentStep} />
        </div>
      )}
      {operationError && <ErrorState what={operationError.what} fix={operationError.fix} />}

      <div className="flex flex-col gap-4 xl:flex-row">
        <aside className="w-full shrink-0 xl:w-[250px]">
          <div className="rounded border border-line bg-surface">
            <button onClick={() => setActiveSource('all')} className={cn('flex h-9 w-full items-center px-3 text-sm', activeSource === 'all' ? 'bg-canvas font-medium' : 'hover:bg-canvas')}>
              全部来源
            </button>
            {(sources.data ?? []).map((source) => (
              <button key={source.id} onClick={() => setActiveSource(source.id)} className={cn(
                'flex w-full flex-col items-start border-t border-line px-3 py-2 text-left text-sm',
                activeSource === source.id ? 'bg-canvas' : 'hover:bg-canvas',
              )}>
                <span className={cn(activeSource === source.id && 'font-medium')}>{source.name}</span>
                <span className="text-[11px] text-ink-faint">{source.toolTotal} 工具 · {source.rawCount > 0 ? `${source.rawCount} 未富化` : '全部已富化'}</span>
              </button>
            ))}
            {(sources.data ?? []).length === 0 && <p className="border-t border-line px-3 py-5 text-xs leading-5 text-ink-muted">还没有 REST API 来源。使用上方按钮导入 URL 或 OpenAPI 文件。</p>}
          </div>

          {selectedSource && (
            <div className="mt-3 rounded border border-line bg-surface p-3 text-xs">
              <code className="block break-all font-mono text-[11px] text-ink-muted">{selectedSource.specUrl ?? '手动上传的 OpenAPI'}</code>
              <p className="mt-1.5 text-ink-faint">hash <code className="font-mono">{shortHash(selectedSource.specHash)}</code> · 拉取于 {formatDate(selectedSource.lastFetchedAt)}</p>
              <p className="mt-1 text-ink-faint">测试环境：<code className="font-mono">{selectedSource.envProfile}</code></p>
              <div className="mt-2 flex flex-wrap gap-1">
                {selectedSource.specUrl && <Button size="sm" onClick={() => refetch.mutate(selectedSource.id)} disabled={refetch.isPending}>重新拉取并应用</Button>}
                <Button size="sm" variant="ghost" onClick={() => { setRetiring(selectedSource); setOperationError(null) }}>移入回收站</Button>
              </div>
            </div>
          )}
        </aside>

        <div className="min-w-0 flex-1">
          <div className="mb-3 flex flex-wrap items-center gap-3 text-xs">
            <select value={effectFilter} onChange={(event) => setEffectFilter(event.target.value)} className="input h-7 text-xs">
              <option value="all">effect：全部</option><option value="read">read</option><option value="write">write</option><option value="delete">delete</option><option value="unknown">unknown</option>
            </select>
            <select value={enrichFilter} onChange={(event) => setEnrichFilter(event.target.value)} className="input h-7 text-xs">
              <option value="all">富化：全部</option><option value="raw">未富化</option><option value="enriched">已富化</option><option value="reviewed">已复核</option>
            </select>
            <label className="flex items-center gap-1.5 text-ink-muted"><input type="checkbox" checked={sensitiveOnly} onChange={(event) => setSensitiveOnly(event.target.checked)} />仅敏感字段</label>
            <label className="flex items-center gap-1.5 text-ink-muted"><input type="checkbox" checked={referencedOnly} onChange={(event) => setReferencedOnly(event.target.checked)} />仅被引用</label>
            <span className="ml-auto font-mono text-ink-faint">{rows.length} 个工具</span>
          </div>
          {tools.isLoading ? <SkeletonTable rows={8} cols={7} /> : (
            <DataTable columns={columns} rows={rows} rowKey={(tool) => tool.id} empty={<div className="px-6 py-12 text-center text-sm text-ink-muted">没有工具。导入 OpenAPI 后，endpoint 会以草稿形式出现在这里。</div>} />
          )}
        </div>
      </div>

      <ImportDrawer open={importOpen} onClose={() => setImportOpen(false)} onImported={refresh} />
      <Drawer open={!!retiring} onClose={() => setRetiring(null)} title="归档 REST API">
        {retiring && <div className="flex flex-col gap-4">
          <p className="text-sm leading-6">「{retiring.name}」及其工具会从普通工具池隐藏并移入回收站。已有能力包、Release 与审计证据会保留；如仍有能力包引用，新的 Release 必须先替换或移除这些工具。只有回收站中且不再被引用、未进入 Release 的草稿来源才能永久删除。</p>
          <label className="flex flex-col gap-1.5"><span className="text-xs font-medium text-ink-muted">归档理由</span><textarea className="input" rows={3} value={trashReason} onChange={(event) => setTrashReason(event.target.value)} /></label>
          {retire.error && <ErrorState compact what={(retire.error as ApiError).what} fix={(retire.error as ApiError).fix} />}
          <div className="flex gap-2"><Button variant="danger" disabled={trashReason.trim().length < 2 || retire.isPending} onClick={() => retire.mutate({ id: retiring.id, reason: trashReason.trim() })}>确认移入回收站</Button><Button variant="ghost" onClick={() => setRetiring(null)}>取消</Button></div>
        </div>}
      </Drawer>
    </div>
  )
}

function ImportDrawer({ open, onClose, onImported }: { open: boolean; onClose: () => void; onImported: () => Promise<void> }) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [specUrl, setSpecUrl] = useState('')
  const [specText, setSpecText] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [envProfile, setEnvProfile] = useState('test')
  const mutation = useMutation({
    mutationFn: importApiSource,
    onSuccess: async (result) => {
      await onImported()
      onClose()
      setName(''); setSlug(''); setSpecUrl(''); setSpecText(''); setBaseUrl('')
      toast(`已正式导入 ${result.importedTools} 个工具${result.parseErrors.length ? `，另有 ${result.parseErrors.length} 条解析告警` : ''}`)
    },
  })
  const error = mutation.error as ApiError | null
  const hasSpec = specUrl.trim() || specText.trim()

  return (
    <Drawer open={open} onClose={onClose} title="导入 OpenAPI" width={560}>
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="来源名称"><input className="input" value={name} onChange={(event) => setName(event.target.value)} placeholder="订单系统" /></Field>
          <Field label="来源 slug（可选）"><input className="input font-mono" value={slug} onChange={(event) => setSlug(event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))} placeholder="orders" /></Field>
        </div>
        <Field label="OpenAPI URL（与上传文件二选一）"><input className="input font-mono text-xs" value={specUrl} onChange={(event) => {
          const value = event.target.value
          setSpecUrl(value)
          if (value.trim()) setSpecText('')
        }} placeholder="https://api.example.com/openapi.json" /></Field>
        <div className="flex items-center gap-3 text-xs text-ink-faint"><span className="h-px flex-1 bg-line" />或上传文件<span className="h-px flex-1 bg-line" /></div>
        <Field label="OpenAPI JSON / YAML 文件">
          <input type="file" accept=".json,.yaml,.yml,application/json,text/yaml" className="text-xs" onChange={async (event) => {
            const file = event.target.files?.[0]
            if (file) {
              setSpecText(await file.text())
              setSpecUrl('')
            }
          }} />
          {specText && <span className="text-xs text-pass">已读取 {specText.length.toLocaleString()} 字符</span>}
        </Field>
        <Field label="REST baseUrl（spec 未声明 servers 时必填）"><input className="input font-mono text-xs" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="https://api.example.com" /></Field>
        <Field label="正式测试环境">
          <select className="input" value={envProfile} onChange={(event) => setEnvProfile(event.target.value)}>
            <option value="test">test（允许正式契约测试）</option>
            <option value="staging">staging（允许正式契约测试）</option>
            <option value="prod">prod（仅登记与追溯，禁止 L1）</option>
          </select>
        </Field>
        <p className="rounded border border-line bg-canvas p-3 text-xs leading-5 text-ink-muted">导入会真实拉取并解析 OpenAPI，保留 spec_hash，并将每个 endpoint 写成 raw 工具。不会创建 MCP 服务或自动发布。若某个只读 endpoint 可由系统自动执行正式 L1，请在该 operation 标注 <code>x-zhuque-l1: &#123; testSafe: true, fixture: "稳定 fixture 标识" &#125;</code>；只有这两个有效字段会被导入。</p>
        {error && <ErrorState what={error.what} fix={error.fix} />}
        <div className="flex gap-2">
          <Button variant="primary" disabled={!name.trim() || !hasSpec || mutation.isPending} onClick={() => mutation.mutate({ name: name.trim(), slug: slug.trim() || undefined, specUrl: specUrl.trim() || undefined, specText: specText || undefined, baseUrl: baseUrl.trim() || undefined, envProfile })}>{mutation.isPending ? '拉取并解析中…' : '解析并导入'}</Button>
          <Button variant="ghost" onClick={onClose}>取消</Button>
        </div>
      </div>
    </Drawer>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="flex flex-col gap-1.5"><span className="text-xs font-medium text-ink-muted">{label}</span>{children}</label>
}
