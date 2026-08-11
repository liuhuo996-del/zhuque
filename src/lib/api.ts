import type {
  Agent, AgentKey, ApiSource, Approval, AuditEvent, Department, DeployRecord, DriftEvent,
  GateDecision, JobProgress, ModelMeta, Pack, Release, ReleaseStatus, TestCase, TimelineStep,
  Tool, TrashedApiSource,
} from '@/types'

export class ApiError extends Error {
  constructor(
    public readonly what: string,
    public readonly fix: string,
    public readonly status: number,
  ) {
    super(what)
  }
}

const OPERATOR_KEY = 'gateforge.operator'
const LEGACY_OPERATOR_KEY = 'zhuque.operator'

export function currentOperator() {
  if (typeof window === 'undefined') return 'console-user'
  const current = window.localStorage.getItem(OPERATOR_KEY)?.trim()
  if (current) return current
  const legacy = window.localStorage.getItem(LEGACY_OPERATOR_KEY)?.trim()
  if (legacy) window.localStorage.setItem(OPERATOR_KEY, legacy)
  return legacy || 'console-user'
}

export function setCurrentOperator(value: string) {
  const normalized = value.trim() || 'console-user'
  window.localStorage.setItem(OPERATOR_KEY, normalized)
  return normalized
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  headers.set('Accept', 'application/json')
  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (error) {
    throw new ApiError('无法连接 GateForge 后端', error instanceof Error ? error.message : '检查后端服务与网络', 0)
  }
  const text = await response.text()
  let body: unknown = null
  if (text) {
    try { body = JSON.parse(text) } catch { body = text }
  }
  if (!response.ok) {
    const value = isRecord(body) ? body : {}
    throw new ApiError(
      String(value.what ?? `请求失败（HTTP ${response.status}）`),
      String(value.fix ?? (typeof body === 'string' ? body : '修复后重试')),
      response.status,
    )
  }
  return body as T
}

function json(method: string, body?: unknown): RequestInit {
  return { method, body: body === undefined ? undefined : JSON.stringify(body) }
}

function query(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  })
  const value = search.toString()
  return value ? `?${value}` : ''
}

export async function fetchDepartments(): Promise<Department[]> {
  return request<Department[]>('/api/departments')
}

export async function createDepartment(input: { name: string; slug: string }) {
  return request<{ id: string }>('/api/departments', json('POST', { ...input, operator: currentOperator() }))
}

export async function fetchAgents(departmentId?: string, trash = false): Promise<Agent[]> {
  return request<Agent[]>('/api/agents' + query({
    department: departmentId && departmentId !== 'all' ? departmentId : undefined,
    trash,
  }))
}

export async function createAgent(input: {
  departmentId: string
  name: string
  slug: string
  description: string
  forbiddenNotes: string
}) {
  return request<{ id: string }>('/api/agents', json('POST', { ...input, operator: currentOperator() }))
}

type RawAgentDetail = { agent: Agent; intents: RawIntent[]; currentRelease: RawRelease | null; keys: AgentKey[] }
type RawIntent = { id: string; agentId: string; text: string; orderNo: number; source: 'ai' | 'human' }

async function fetchAgentDetail(id: string) {
  return request<RawAgentDetail>(`/api/agents/${id}`)
}

export async function fetchAgent(id: string) {
  return (await fetchAgentDetail(id)).agent
}

export async function fetchAgentIntents(id: string) {
  return (await fetchAgentDetail(id)).intents
}

export async function fetchAgentKeys(id: string) {
  return (await fetchAgentDetail(id)).keys
}

export async function trashAgent(id: string, reason: string) {
  return request<void>(`/api/agents/${id}`, json('DELETE', { operator: currentOperator(), reason }))
}

export async function restoreAgent(id: string) {
  return request<void>(`/api/agents/${id}/restore`, json('POST', { operator: currentOperator() }))
}

export async function purgeAgent(id: string) {
  return request<void>(`/api/agents/${id}/purge`, json('DELETE', { operator: currentOperator() }))
}

export async function rotateAgentKey(id: string) {
  return request<{ keyRef: string; plaintextOnceOnly: string }>(`/api/agents/${id}/keys/rotate`, json('POST'))
}

type RawSource = ApiSource & {
  trashedAt?: string | null
  trashedBy?: string | null
  trashReason?: string | null
}

export async function fetchApiSources(trash = false): Promise<ApiSource[]> {
  return request<RawSource[]>('/api/sources' + query({ trash })).then((rows) => rows.map(sourceView))
}

export async function fetchTrashedApiSources(): Promise<TrashedApiSource[]> {
  const rows = await request<RawSource[]>('/api/sources?trash=true')
  return rows.map((row) => ({
    ...sourceView(row),
    trashedAt: row.trashedAt ?? '',
    trashedBy: row.trashedBy ?? '',
    trashReason: row.trashReason ?? '',
  }))
}

function sourceView(row: RawSource): ApiSource {
  return {
    id: row.id,
    name: row.name,
    specUrl: row.specUrl,
    specHash: row.specHash,
    lastFetchedAt: row.lastFetchedAt,
    envProfile: row.envProfile,
    toolTotal: row.toolTotal,
    rawCount: Number(row.rawCount),
  }
}

export async function importApiSource(input: {
  name: string
  slug?: string
  specUrl?: string
  specText?: string
  baseUrl?: string
  envProfile: string
}) {
  return request<{ sourceId: string; importedTools: number; parseErrors: Array<Record<string, string>> }>(
    '/api/sources', json('POST', { ...input, operator: currentOperator() }),
  )
}

export async function refetchApiSource(id: string, apply = false) {
  return request<{ unchanged: boolean; applied: boolean; diff: unknown; parseErrors: unknown[] }>(
    `/api/sources/${id}/refetch${query({ apply })}`, json('POST'),
  )
}

export async function trashApiSource(id: string, reason: string) {
  return request<void>(`/api/sources/${id}`, json('DELETE', { operator: currentOperator(), reason }))
}

export async function restoreApiSource(id: string) {
  return request<void>(`/api/sources/${id}/restore`, json('POST', { operator: currentOperator() }))
}

export async function purgeApiSource(id: string) {
  return request<void>(`/api/sources/${id}/purge`, json('DELETE', { operator: currentOperator() }))
}

type RawTool = {
  id: string
  apiSourceId: string
  name: string
  description: string
  method: string
  path: string
  effect: Tool['effect']
  enrichmentStatus: Tool['enrichmentStatus']
  inputSchema: { required?: string[] }
  requestTemplate: Record<string, unknown>
  outputFields: string[]
  sensitivityFlags: string[]
  tokenCost: number
  refCount: number
}

export async function fetchTools(): Promise<Tool[]> {
  const rows = await request<RawTool[]>('/api/tools')
  return rows.map((row) => ({
    id: row.id,
    apiSourceId: row.apiSourceId,
    name: row.name,
    description: row.description,
    method: row.method,
    path: row.path,
    effect: row.effect,
    enrichmentStatus: row.enrichmentStatus,
    tokenCost: row.tokenCost,
    requires: row.inputSchema?.required ?? [],
    produces: row.outputFields ?? [],
    sensitiveFields: row.sensitivityFlags ?? [],
    refCount: row.refCount,
  }))
}

export async function enrichTools(toolIds: string[]) {
  return request<{ jobId: string }>('/api/tools/enrich', json('POST', { toolIds }))
}

export async function reviewTool(id: string) {
  return request<void>(`/api/tools/${id}/review`, json('POST', { reviewer: currentOperator() }))
}

export async function fetchJob(jobId: string): Promise<JobProgress> {
  return request<JobProgress>(`/api/jobs/${jobId}/progress`)
}

type RawPackView = {
  pack: { id: string; departmentId: string; name: string; scope: Pack['scope']; createdAt: string }
  toolIds: string[]
  usedByAgentIds: string[]
}

export async function fetchPacks(departmentId?: string): Promise<Pack[]> {
  const rows = await request<RawPackView[]>('/api/packs' + query({
    department: departmentId && departmentId !== 'all' ? departmentId : undefined,
  }))
  return rows.map(({ pack, toolIds, usedByAgentIds }) => ({ ...pack, toolIds, usedByAgentIds }))
}

type RawRelease = {
  id: string
  agentId: string
  version: string
  status: ReleaseStatus
  manifest: Record<string, unknown>
  manifestHash: string | null
  nacosPayload: unknown
  higressAuthPayload: unknown
  sourceSpecHashes: unknown
  targetConstraints: unknown
  createdAt: string
}

type RawTest = {
  layer: TestCase['layer']
  caseId: string
  result: TestCase['result']
  detail: Record<string, unknown>
  modelMeta: Record<string, unknown> | null
}
type RawGate = {
  ruleId: string
  verdict: string
  waivedBy: string | null
  waiverReason: string | null
  detail?: Record<string, unknown> | null
}
type RawApproval = Approval & { id: string; releaseId: string }
type RawDeploy = { target: DeployRecord['target']; payloadHash: string; appliedAt: string; result: string }
type RawReleaseDetail = {
  release: RawRelease
  tests: RawTest[]
  gates: RawGate[]
  approvals: RawApproval[]
  deploys: RawDeploy[]
}

export async function fetchReleases(departmentId?: string): Promise<Release[]> {
  const scoped = departmentId && departmentId !== 'all'
  const [rows, activeAgents, retiredAgents] = await Promise.all([
    request<RawRelease[]>('/api/releases'),
    scoped ? fetchAgents(departmentId) : Promise.resolve<Agent[]>([]),
    scoped ? fetchAgents(departmentId, true) : Promise.resolve<Agent[]>([]),
  ])
  const agentIds = new Set([...activeAgents, ...retiredAgents].map((agent) => agent.id))
  const visibleRows = rows.filter((row) => !scoped || agentIds.has(row.agentId))
  // 列表接口只返回不可变快照本体；门禁、测试、审批与部署记录属于证据包，
  // 必须逐条取详情，不能再用前端虚构的汇总值代替。
  return Promise.all(visibleRows.map((row) => fetchRelease(row.id)))
}

export async function fetchRelease(id: string): Promise<Release> {
  const detail = await request<RawReleaseDetail>(`/api/releases/${id}`)
  return releaseView(detail.release, detail.tests, detail.gates, detail.approvals, detail.deploys)
}

function releaseView(
  row: RawRelease,
  tests: RawTest[],
  gates: RawGate[],
  approvals: RawApproval[],
  deploys: RawDeploy[],
): Release {
  const manifest = isRecord(row.manifest) ? row.manifest : {}
  const closure = isRecord(manifest.closure) ? manifest.closure : {}
  const modelMetaRaw = tests.find((test) => test.layer === 'L2' && test.modelMeta)?.modelMeta
  const modelMeta: ModelMeta | null = modelMetaRaw ? {
    model: String(modelMetaRaw.model ?? ''),
    version: String(modelMetaRaw.modelVersion ?? modelMetaRaw.version ?? ''),
    temperature: Number(modelMetaRaw.temperature ?? 0),
    promptTemplate: String(modelMetaRaw.promptTemplateVersion ?? modelMetaRaw.promptTemplate ?? ''),
  } : null
  return {
    id: row.id,
    agentId: row.agentId,
    version: row.version,
    status: row.status,
    manifestHash: row.manifestHash ?? '',
    createdAt: row.createdAt,
    nacosPayload: row.nacosPayload,
    higressAuthPayload: row.higressAuthPayload,
    targetConstraints: constraintViews(row.targetConstraints),
    sourceSpecHashes: sourceHashViews(row.sourceSpecHashes),
    gates: gates.map(gateView),
    tests: tests.map((test) => ({
      layer: test.layer,
      caseId: test.caseId,
      result: test.result,
      detail: String(test.detail?.message ?? JSON.stringify(test.detail)),
    })),
    modelMeta,
    approvals: approvals.map(({ approver, decidedAt, decision, manifestHash }) => ({
      approver, decidedAt, decision, manifestHash,
    })),
    deploys: deploys.map((deploy) => ({
      target: deploy.target,
      payloadHash: deploy.payloadHash,
      appliedAt: deploy.appliedAt,
      result: deploy.result === 'success' ? 'ok' : 'failed',
      error: deploy.result === 'success' ? undefined : deploy.result,
    })),
    closureSummary: String(closure.conclusion ?? '尚未冻结'),
    timeline: timeline(row),
  }
}

function gateView(raw: RawGate): GateDecision {
  return {
    ruleId: raw.ruleId,
    ruleName: RULE_NAMES[raw.ruleId] ?? raw.ruleId,
    verdict: raw.verdict === 'fail' ? 'block' : raw.verdict as GateDecision['verdict'],
    detail: raw.detail ? String(raw.detail.message ?? '') || undefined : undefined,
    waivedBy: raw.waivedBy ?? undefined,
    waiverReason: raw.waiverReason ?? undefined,
  }
}

const RULE_NAMES: Record<string, string> = {
  'l1-contract': '正式上游契约测试通过',
  idempotency: '写操作幂等性通过',
  'l0-completeness': '静态 schema 完备',
  closure: '闭包检查通过',
  'sensitive-masking': '敏感字段已脱敏',
  'l2-accuracy': '选工具准确率达标',
  budget: '工具规模预算',
  latency: '上游延迟达标',
}

function timeline(row: RawRelease): TimelineStep[] {
  const flow: ReleaseStatus[] = ['draft', 'candidate', 'tested', 'approved', 'released']
  const index = flow.indexOf(row.status)
  const reached = index < 0 ? flow.length - 1 : index
  const result = flow.slice(0, reached + 1).map((status) => ({ status, at: row.createdAt, by: null }))
  if (row.status === 'superseded' || row.status === 'rolled_back') {
    result.push({ status: row.status, at: row.createdAt, by: null })
  }
  return result
}

function constraintViews(value: unknown): Release['targetConstraints'] {
  if (!isRecord(value)) return []
  return Object.entries(value).map(([name, required]) => ({ name, required: String(required), current: '发布时检查', ok: true }))
}

function sourceHashViews(value: unknown): Release['sourceSpecHashes'] {
  if (!isRecord(value)) return []
  return Object.entries(value).map(([source, info]) => ({
    source: isRecord(info) ? String(info.name ?? source) : source,
    hash: isRecord(info) ? String(info.specHash ?? '') : String(info),
  }))
}

export async function approveRelease(id: string, manifestHash: string) {
  return request<void>(`/api/releases/${id}/approve`, json('POST', {
    approver: currentOperator(), manifestHash,
  }))
}

export async function publishRelease(id: string) {
  return request<{ mcpUrl: string; plaintextKeyOnceOnly: string | null }>(
    `/api/releases/${id}/publish`, json('POST', { operator: currentOperator() }),
  )
}

export async function rollbackRelease(id: string) {
  return request<void>(`/api/releases/${id}/rollback`, json('POST', { operator: currentOperator() }))
}

export async function runReleaseL0(id: string) {
  return request<unknown[]>(`/api/releases/${id}/tests/l0`, json('POST'))
}

export async function runReleaseL1(id: string) {
  return request<{ jobId: string }>(`/api/releases/${id}/tests/l1?target=live`, json('POST'))
}

export async function fetchDriftEvents(): Promise<DriftEvent[]> {
  const [rows, agents, activeSources, trashedSources] = await Promise.all([
    request<Array<{
      id: string; scopeType: 'api_source' | 'agent'; scopeId: string; kind: 'spec' | 'config'
      detail: Record<string, unknown>; detectedAt: string; status: 'open' | 'resolved'
    }>>('/api/drifts'),
    fetchAgents('all'),
    fetchApiSources(),
    fetchTrashedApiSources(),
  ])
  const names = new Map<string, string>([
    ...agents.map((agent) => [agent.id, agent.name] as const),
    ...activeSources.map((source) => [source.id, source.name] as const),
    ...trashedSources.map((source) => [source.id, source.name] as const),
  ])
  return rows.map((row) => ({
    id: row.id,
    scopeType: row.scopeType,
    scopeName: names.get(row.scopeId) ?? row.scopeId,
    agentId: row.scopeType === 'agent' ? row.scopeId : undefined,
    kind: row.kind,
    detail: String(row.detail.message ?? row.detail.action ?? JSON.stringify(row.detail)),
    detectedAt: row.detectedAt,
    status: row.status,
  }))
}

export async function fetchAuditEvents(limit = 100): Promise<AuditEvent[]> {
  return request<AuditEvent[]>('/api/audit-events' + query({ limit }))
}

export interface PrecheckItem {
  name: string
  ok: boolean
  current: string
  fix: string
}

export async function fetchPrecheck() {
  return request<PrecheckItem[]>('/api/deploy/precheck')
}

function isRecord(value: unknown): value is Record<string, any> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}
