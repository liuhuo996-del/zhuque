import { useMemo, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { EffectBadge } from '@/components/ui/EffectBadge'
import { Drawer } from '@/components/ui/Drawer'
import { BudgetBar } from '@/components/ui/ProgressBar'
import { useToast } from '@/components/ui/Toast'
import { computeBudget, computeClosure, computeRisk, hitCountForTool, isIntentCovered } from '@/lib/matrix'
import { cn } from '@/lib/utils'
import type { HitMap, Intent, Tool } from '@/types'

/**
 * 意图 × 工具覆盖矩阵 —— GateForge 的签名治理界面。
 * 行=意图，列=候选工具，格子=命中。增删是列级操作，格子不可编辑。
 * 任何增删后闭包/预算/风险实时重算（纯派生状态，无手动刷新）。
 */
export function IntentToolMatrix({
  intents, setIntents, selectedIds, setSelectedIds, pool, hits, onFreeze, freezeLabel = '确认并冻结 Release', onRematch,
}: {
  intents: Intent[]
  setIntents: (next: Intent[]) => void
  selectedIds: string[]
  setSelectedIds: (next: string[]) => void
  pool: Tool[]
  hits: HitMap
  onFreeze?: () => void
  freezeLabel?: string
  onRematch?: () => void
}) {
  const toast = useToast()
  const selected = useMemo(() => new Set(selectedIds), [selectedIds])
  const selectedTools = useMemo(
    () => pool.filter((t) => selected.has(t.id)),
    [pool, selected],
  )

  const closure = useMemo(() => computeClosure(selected, pool), [selected, pool])
  const budget = useMemo(() => computeBudget(selected, pool), [selected, pool])
  const risk = useMemo(() => computeRisk(selected, pool), [selected, pool])

  const [addOpen, setAddOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [editingIntent, setEditingIntent] = useState<string | null>(null)
  const [schemaTool, setSchemaTool] = useState<Tool | null>(null)
  const dragFrom = useRef<number | null>(null)

  const freezeDisabledReason =
    closure.status !== 'closed'
      ? '闭包检查未通过：存在无法满足的必填参数'
      : budget.overTools || budget.overTokens
        ? '规模预算超限：考虑拆分为两个能力包'
        : null

  function removeTool(tool: Tool) {
    const next = selectedIds.filter((id) => id !== tool.id)
    const nextSet = new Set(next)
    // 若移除后某意图失去覆盖，立即变未覆盖态 + 可撤销 toast
    const lost = intents.filter(
      (i) => isIntentCovered(i, selected, hits) && !isIntentCovered(i, nextSet, hits),
    )
    setSelectedIds(next)
    if (lost.length > 0) {
      toast(
        `已移除 ${tool.name}，意图「${lost[0].text}」失去覆盖`,
        { label: '撤销', run: () => setSelectedIds([...next, tool.id]) },
      )
    } else {
      toast(`已移除 ${tool.name}`, { label: '撤销', run: () => setSelectedIds([...next, tool.id]) })
    }
  }

  function addTool(toolId: string) {
    if (!selected.has(toolId)) setSelectedIds([...selectedIds, toolId])
  }

  function updateIntentText(id: string, text: string) {
    setIntents(intents.map((i) => (i.id === id ? { ...i, text, source: 'human' } : i)))
  }
  function removeIntent(id: string) {
    setIntents(intents.filter((i) => i.id !== id))
  }
  function addIntent() {
    const id = `i-new-${Date.now()}`
    setIntents([...intents, { id, text: '', orderNo: intents.length + 1, source: 'human' }])
    setEditingIntent(id)
  }
  function reorderIntent(from: number, to: number) {
    if (from === to) return
    const next = [...intents]
    const [moved] = next.splice(from, 1)
    next.splice(to, 0, moved)
    setIntents(next.map((i, idx) => ({ ...i, orderNo: idx + 1 })))
  }

  function locateColumn(toolId: string) {
    const el = document.getElementById(`col-${toolId}`)
    if (el) {
      el.scrollIntoView({ block: 'nearest', inline: 'center' })
      el.classList.remove('col-flash')
      void el.offsetWidth
      el.classList.add('col-flash')
    }
  }

  const colTint = (t: Tool) =>
    t.effect === 'write' ? 'bg-[var(--warn-tint)]' : t.effect === 'delete' ? 'bg-[var(--block-tint)]' : ''

  const poolFiltered = pool.filter(
    (t) =>
      !selected.has(t.id) &&
      (t.name.includes(search) || t.path.includes(search) || t.description.includes(search)),
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-4 2xl:flex-row">
        {/* ---- 矩阵主区 ---- */}
        <div className="min-w-0 flex-1 overflow-x-auto rounded border border-line bg-surface">
          <table className="border-collapse">
            <thead>
              <tr>
                <th className="sticky left-0 z-10 min-w-[260px] border-b border-r border-line bg-surface p-2 text-left align-bottom">
                  <span className="text-xs font-medium text-ink-muted">意图（行）× 工具（列）</span>
                </th>
                {selectedTools.map((t) => (
                  <th
                    key={t.id}
                    id={`col-${t.id}`}
                    className={cn('group relative border-b border-line align-bottom', colTint(t))}
                  >
                    <button
                      tabIndex={0}
                      onKeyDown={(e) => {
                        if (e.key === 'Delete' || e.key === 'Backspace') {
                          e.preventDefault()
                          removeTool(t)
                        }
                      }}
                      className="flex h-[120px] w-9 items-end justify-center pb-1 focus-visible:outline-2"
                      aria-label={`工具 ${t.name}，Delete 键移除`}
                    >
                      <span
                        className="max-h-[112px] overflow-hidden font-mono text-[11px] text-ink"
                        style={{ writingMode: 'vertical-rl' }}
                      >
                        {t.name}
                      </span>
                    </button>
                    {/* hover 展开完整签名 + 列操作 */}
                    <div className="pointer-events-none absolute left-1/2 top-full z-20 hidden w-64 -translate-x-1/2 rounded border border-line bg-surface p-2.5 text-left shadow-lg group-hover:pointer-events-auto group-hover:block group-focus-within:pointer-events-auto group-focus-within:block">
                      <code className="block font-mono text-xs font-medium">{t.name}</code>
                      <code className="mt-0.5 block font-mono text-[11px] text-ink-muted">{t.method} {t.path}</code>
                      <p className="mt-1 text-xs text-ink-muted">{t.description}</p>
                      <div className="mt-2 flex gap-1.5">
                        <Button size="sm" variant="ghost" onClick={() => removeTool(t)}>移除</Button>
                        <Button size="sm" variant="ghost" onClick={() => setSchemaTool(t)}>完整 schema</Button>
                      </div>
                    </div>
                  </th>
                ))}
                <th className="w-full border-b border-line" />
              </tr>
              <tr>
                <th className="sticky left-0 z-10 border-b border-r border-line bg-surface p-2 text-left text-[11px] font-normal text-ink-faint">
                  effect
                </th>
                {selectedTools.map((t) => (
                  <th key={t.id} className={cn('border-b border-line pb-1.5 text-center', colTint(t))}>
                    <EffectBadge effect={t.effect} className="h-4 px-1 text-[9px]" />
                  </th>
                ))}
                <th className="border-b border-line" />
              </tr>
            </thead>
            <tbody>
              {intents.map((intent, rowIdx) => {
                const covered = isIntentCovered(intent, selected, hits)
                return (
                  <tr key={intent.id} className={cn(!covered && 'bg-[var(--warn-tint)]')}>
                    <td
                      className="sticky left-0 z-10 border-b border-r border-line bg-surface p-0"
                      draggable
                      onDragStart={() => (dragFrom.current = rowIdx)}
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={() => {
                        if (dragFrom.current !== null) reorderIntent(dragFrom.current, rowIdx)
                        dragFrom.current = null
                      }}
                    >
                      <div className={cn('flex h-9 items-center gap-1.5 px-2', !covered && 'bg-[var(--warn-tint)]')}>
                        <span className="cursor-grab text-ink-faint" title="拖动排序">⠿</span>
                        {intent.source === 'ai' && (
                          <span className="rounded border border-line px-1 font-mono text-[9px] text-ink-faint" title="AI 拆解的意图">ai</span>
                        )}
                        {editingIntent === intent.id ? (
                          <input
                            autoFocus
                            defaultValue={intent.text}
                            onBlur={(e) => {
                              updateIntentText(intent.id, e.target.value.trim() || intent.text)
                              setEditingIntent(null)
                            }}
                            onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
                            className="h-6 min-w-0 flex-1 rounded border border-line px-1 text-sm"
                          />
                        ) : (
                          <button
                            className="min-w-0 flex-1 truncate text-left text-sm hover:underline"
                            onClick={() => setEditingIntent(intent.id)}
                            title="点击就地编辑"
                          >
                            {intent.text || <span className="text-ink-faint">输入意图…</span>}
                          </button>
                        )}
                        {covered ? (
                          <span className="text-xs text-ink-faint" title="已覆盖">✓</span>
                        ) : (
                          <span className="whitespace-nowrap text-[11px] font-medium text-warn">无工具覆盖</span>
                        )}
                        <button
                          className="px-1 text-ink-faint hover:text-block"
                          aria-label={`删除意图 ${intent.text}`}
                          onClick={() => removeIntent(intent.id)}
                        >
                          ✕
                        </button>
                      </div>
                    </td>
                    {selectedTools.map((t) => {
                      const hit = hits[intent.id]?.[t.id]
                      return (
                        <td key={t.id} className={cn('group relative h-9 border-b border-line text-center', colTint(t))}>
                          {hit && (
                            <>
                              <span
                                className={cn(
                                  'inline-block h-3 w-3 transition-transform duration-100 group-hover:scale-125',
                                  hit.strength === 'strong' ? 'bg-ink' : 'border-[1.5px] border-ink bg-transparent',
                                )}
                              />
                              <div className="pointer-events-none absolute left-1/2 top-full z-20 hidden w-60 -translate-x-1/2 rounded border border-line bg-surface p-2.5 text-left shadow-lg group-hover:block">
                                <p className="text-xs">{hit.reason}</p>
                                <p className="mt-1.5 font-mono text-[11px] text-ink-muted">
                                  置信度 {hit.confidence.toFixed(2)} · {hit.strength === 'strong' ? '强命中' : '弱命中'}
                                </p>
                                <p className="mt-0.5 font-mono text-[11px] text-ink-faint">
                                  匹配字段：{hit.matchedFields.join(', ')}
                                </p>
                              </div>
                            </>
                          )}
                        </td>
                      )
                    })}
                    <td className="border-b border-line" />
                  </tr>
                )
              })}
              {/* 列尾：命中计数 */}
              <tr>
                <td className="sticky left-0 z-10 border-r border-line bg-surface p-2">
                  <button className="text-xs text-ink-muted hover:text-ink hover:underline" onClick={addIntent}>
                    + 添加意图
                  </button>
                </td>
                {selectedTools.map((t) => {
                  const count = hitCountForTool(t.id, intents, hits)
                  return (
                    <td key={t.id} className={cn('py-1.5 text-center align-top', colTint(t))}>
                      {count === 0 ? (
                        <button
                          className="mx-auto block font-mono text-[10px] font-medium leading-tight text-warn hover:underline"
                          title="未被任何意图命中，建议移除"
                          onClick={() => removeTool(t)}
                        >
                          0<br />移除
                        </button>
                      ) : (
                        <span className="font-mono text-[11px] text-ink-faint">{count}</span>
                      )}
                    </td>
                  )
                })}
                <td />
              </tr>
            </tbody>
          </table>
        </div>

        {/* ---- 右侧栏 ---- */}
        <aside className="grid w-full shrink-0 grid-cols-1 gap-3 md:grid-cols-3 2xl:flex 2xl:w-[300px] 2xl:flex-col">
          {/* 1) 闭包检查 */}
          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">闭包检查</h3>
            {closure.status === 'closed' && (
              <p className="mt-2 text-sm font-medium text-pass">闭合：每个必填参数都可由用户提供或上游工具产出</p>
            )}
            {closure.status !== 'closed' && (
              <div className="mt-2 flex flex-col gap-3">
                {closure.missing.map((m) => (
                  <div key={m.param}>
                    <p className="text-sm">
                      <code className="rounded bg-canvas px-1 font-mono text-[12px]">{m.param}</code>
                      <span className={cn('ml-1.5 font-medium', m.suggestions.length ? 'text-warn' : 'text-block')}>
                        当前无工具能产出
                      </span>
                    </p>
                    <p className="mt-0.5 font-mono text-[11px] text-ink-muted">
                      需要方：{m.neededBy.join(', ')}
                    </p>
                    {m.suggestions.length > 0 ? (
                      m.suggestions.map((s) => (
                        <div key={s.id} className="mt-2 rounded border border-line p-2.5">
                          <code className="font-mono text-xs font-medium">{s.name}</code>
                          <p className="mt-0.5 text-xs text-ink-muted">
                            输出 <code className="font-mono">{s.produces.join(', ')}</code> · {s.description}
                          </p>
                          <Button size="sm" className="mt-2" onClick={() => addTool(s.id)}>加入</Button>
                        </div>
                      ))
                    ) : (
                      <p className="mt-1.5 text-xs text-block">
                        工具池里没有任何工具能产出 <code className="font-mono">{m.param}</code>。
                        联系该系统的 API owner 补接口，或换一条意图路径。
                      </p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* 2) 规模预算 */}
          <section className="flex flex-col gap-3 rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">规模预算</h3>
            <BudgetBar label="工具数" used={budget.toolCount} max={budget.maxTools} />
            <BudgetBar label="schema token" used={budget.tokens} max={budget.maxTokens} />
            {(budget.overTools || budget.overTokens) && (
              <p className="text-xs font-medium text-warn">超限：考虑拆分为两个能力包</p>
            )}
          </section>

          {/* 3) 风险摘要 */}
          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">风险摘要</h3>
            <ul className="mt-2 flex flex-col gap-1.5 text-sm">
              <RiskRow label="写操作" tools={risk.write} color="text-effect-write" onLocate={locateColumn} />
              <RiskRow label="删除操作" tools={risk.del} color="text-effect-delete" onLocate={locateColumn} />
              <RiskRow label="命中敏感字段" tools={risk.sensitive} color="text-warn" onLocate={locateColumn} />
            </ul>
          </section>
        </aside>
      </div>

      {/* ---- 底部操作条 ---- */}
      <div className="flex items-center gap-3 rounded border border-line bg-surface px-4 py-3">
        <Button onClick={() => setAddOpen(true)}>从工具池添加工具</Button>
        {onRematch && <Button variant="ghost" onClick={onRematch}>重新匹配</Button>}
        <div className="ml-auto flex items-center gap-3">
          {freezeDisabledReason && (
            <span className="text-xs text-ink-muted">{freezeDisabledReason}</span>
          )}
          {onFreeze && (
            <Button variant="primary" disabled={!!freezeDisabledReason} onClick={onFreeze}>
              {freezeLabel}
            </Button>
          )}
        </div>
      </div>

      {/* 工具池搜索抽屉 */}
      <Drawer open={addOpen} onClose={() => setAddOpen(false)} title="从工具池添加工具">
        <input
          autoFocus
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="按名称 / 路径 / 描述搜索"
          className="mb-3 h-8 w-full rounded border border-line px-2.5 text-sm"
        />
        <div className="flex flex-col gap-2">
          {poolFiltered.map((t) => (
            <div key={t.id} className="flex items-start gap-2 rounded border border-line p-2.5">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <code className="truncate font-mono text-xs font-medium">{t.name}</code>
                  <EffectBadge effect={t.effect} className="h-4 px-1 text-[9px]" />
                </div>
                <code className="mt-0.5 block font-mono text-[11px] text-ink-muted">{t.method} {t.path}</code>
                <p className="mt-0.5 text-xs text-ink-muted">{t.description}</p>
              </div>
              <Button size="sm" onClick={() => { addTool(t.id); toast(`已加入 ${t.name}`) }}>加入</Button>
            </div>
          ))}
          {poolFiltered.length === 0 && (
            <p className="py-8 text-center text-sm text-ink-muted">没有匹配的工具。换个关键词，或先到 工具池 导入 OpenAPI。</p>
          )}
        </div>
      </Drawer>

      {/* 完整 schema 抽屉 */}
      <Drawer open={!!schemaTool} onClose={() => setSchemaTool(null)} title={schemaTool ? `schema · ${schemaTool.name}` : ''}>
        {schemaTool && (
          <pre className="overflow-x-auto rounded border border-line bg-canvas p-3 font-mono text-xs leading-5">
            {JSON.stringify(
              {
                name: schemaTool.name,
                endpoint: `${schemaTool.method} ${schemaTool.path}`,
                effect: schemaTool.effect,
                inputSchema: { type: 'object', required: schemaTool.requires },
                output_fields: schemaTool.produces,
                sensitivity_flags: schemaTool.sensitiveFields,
                token_cost: schemaTool.tokenCost,
              },
              null, 2,
            )}
          </pre>
        )}
      </Drawer>
    </div>
  )
}

function RiskRow({ label, tools, color, onLocate }: {
  label: string
  tools: Tool[]
  color: string
  onLocate: (toolId: string) => void
}) {
  return (
    <li className="flex items-baseline justify-between gap-2">
      <span className="text-ink-muted">{label}</span>
      {tools.length === 0 ? (
        <span className="font-mono text-xs text-ink-faint">0</span>
      ) : (
        <span className="flex flex-wrap justify-end gap-1">
          {tools.map((t) => (
            <button
              key={t.id}
              onClick={() => onLocate(t.id)}
              className={cn('font-mono text-[11px] underline-offset-2 hover:underline', color)}
              title="点击定位到列"
            >
              {t.name}
            </button>
          ))}
        </span>
      )}
    </li>
  )
}
