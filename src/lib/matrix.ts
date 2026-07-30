// 意图 × 工具矩阵的纯计算引擎：覆盖、闭包、预算、风险。
// 任何增删后由 React 状态驱动全部重算，不需要手动刷新。
import { USER_SUPPLIED } from '@/mock/data'
import type { HitMap, Intent, Tool } from '@/types'

export const BUDGET = { maxTools: 20, maxTokens: 15000 }

export interface MissingParam {
  param: string
  neededBy: string[] // tool names
  suggestions: Tool[] // 池内能产出该参数、且未入选的工具
}

export interface ClosureResult {
  status: 'closed' | 'fixable' | 'broken'
  missing: MissingParam[]
}

export function isIntentCovered(intent: Intent, selected: Set<string>, hits: HitMap) {
  const row = hits[intent.id] ?? {}
  return Object.keys(row).some((toolId) => selected.has(toolId))
}

export function computeClosure(selectedIds: Set<string>, pool: Tool[]): ClosureResult {
  const selected = pool.filter((t) => selectedIds.has(t.id))
  const produced = new Set<string>(USER_SUPPLIED)
  for (const t of selected) for (const p of t.produces) produced.add(p)

  const missingMap = new Map<string, Set<string>>()
  for (const t of selected) {
    for (const param of t.requires) {
      if (!produced.has(param)) {
        if (!missingMap.has(param)) missingMap.set(param, new Set())
        missingMap.get(param)!.add(t.name)
      }
    }
  }

  const missing: MissingParam[] = [...missingMap.entries()].map(([param, neededBy]) => ({
    param,
    neededBy: [...neededBy],
    suggestions: pool.filter((t) => !selectedIds.has(t.id) && t.produces.includes(param)),
  }))

  if (missing.length === 0) return { status: 'closed', missing }
  return {
    status: missing.every((m) => m.suggestions.length > 0) ? 'fixable' : 'broken',
    missing,
  }
}

export function computeBudget(selectedIds: Set<string>, pool: Tool[]) {
  const selected = pool.filter((t) => selectedIds.has(t.id))
  const tokens = selected.reduce((s, t) => s + t.tokenCost, 0)
  return {
    toolCount: selected.length,
    maxTools: BUDGET.maxTools,
    tokens,
    maxTokens: BUDGET.maxTokens,
    overTools: selected.length > BUDGET.maxTools,
    overTokens: tokens > BUDGET.maxTokens,
  }
}

export function computeRisk(selectedIds: Set<string>, pool: Tool[]) {
  const selected = pool.filter((t) => selectedIds.has(t.id))
  return {
    write: selected.filter((t) => t.effect === 'write'),
    del: selected.filter((t) => t.effect === 'delete'),
    sensitive: selected.filter((t) => t.sensitiveFields.length > 0),
  }
}

/** 某列被几个意图命中（决定「未被任何意图命中，建议移除」） */
export function hitCountForTool(toolId: string, intents: Intent[], hits: HitMap) {
  return intents.filter((i) => hits[i.id]?.[toolId]).length
}
