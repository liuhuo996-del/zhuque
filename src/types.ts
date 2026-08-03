// 与 CLAUDE.md 数据契约对应的前端类型（字段不得擅自增删）

export type Effect = 'read' | 'write' | 'delete' | 'unknown'
export type Enrichment = 'raw' | 'enriched' | 'reviewed'
export type AgentStatus = 'draft' | 'active' | 'suspended' | 'retired'
export type ReleaseStatus =
  | 'draft'
  | 'candidate'
  | 'tested'
  | 'approved'
  | 'released'
  | 'superseded'
  | 'rolled_back'
export type Health = 'ok' | 'drift' | 'failed' | 'none'

export interface Department {
  id: string
  name: string
  slug: string
  consumerGroupRef: string
}

export interface ApiSource {
  id: string
  name: string
  specUrl: string | null
  specHash: string
  lastFetchedAt: string | null
  envProfile: string
  toolTotal: number
  rawCount: number
}

export interface Tool {
  id: string
  apiSourceId: string
  name: string
  description: string
  method: string
  path: string
  effect: Effect
  enrichmentStatus: Enrichment
  tokenCost: number
  /** 必填参数（inputSchema.required 的抽象） */
  requires: string[]
  /** 该工具输出中可作为其他工具入参的字段（闭包检查用） */
  produces: string[]
  sensitiveFields: string[]
  refCount: number
}

export interface Intent {
  id: string
  text: string
  orderNo: number
  source: 'ai' | 'human'
}

export interface Agent {
  id: string
  departmentId: string
  name: string
  slug: string
  description: string
  forbiddenNotes: string
  status: AgentStatus
  mcpUrl: string
  health: Health
  currentVersion: string | null
  toolCount: number
  lastReleasedAt: string | null
  createdAt: string
}

export interface Hit {
  strength: 'strong' | 'weak'
  reason: string
  confidence: number
  matchedFields: string[]
}

/** intentId -> toolId -> Hit */
export type HitMap = Record<string, Record<string, Hit>>

export interface GateDecision {
  ruleId: string
  ruleName: string
  verdict: 'pass' | 'block' | 'waived'
  detail?: string
  waivedBy?: string
  waiverReason?: string
}

export interface TestCase {
  layer: 'L0' | 'L1' | 'L2'
  caseId: string
  result: 'pass' | 'fail' | 'skip' | 'warn'
  detail: string
}

export interface ModelMeta {
  model: string
  version: string
  temperature: number
  promptTemplate: string
}

export interface DeployRecord {
  target: 'nacos' | 'higress_auth'
  payloadHash: string
  appliedAt: string
  result: 'ok' | 'failed'
  error?: string
}

export interface Approval {
  approver: string
  decidedAt: string
  decision: 'approved' | 'rejected'
  manifestHash: string
}

export interface TimelineStep {
  status: ReleaseStatus
  at: string | null
  by: string | null
}

export interface Release {
  id: string
  agentId: string
  version: string
  status: ReleaseStatus
  manifestHash: string
  createdAt: string
  nacosPayload: unknown
  higressAuthPayload: unknown
  targetConstraints: { name: string; required: string; current: string; ok: boolean }[]
  sourceSpecHashes: { source: string; hash: string }[]
  gates: GateDecision[]
  tests: TestCase[]
  modelMeta: ModelMeta | null
  approvals: Approval[]
  deploys: DeployRecord[]
  closureSummary: string
  timeline: TimelineStep[]
}

export interface DriftEvent {
  id: string
  scopeType: 'api_source' | 'agent'
  scopeName: string
  agentId?: string
  kind: 'spec' | 'config'
  detail: string
  detectedAt: string
  status: 'open' | 'resolved'
}

export interface AgentKey {
  id: string
  agentId: string
  keyRef: string
  rotatedAt: string
  revokedAt: string | null
}

export interface Pack {
  id: string
  departmentId: string
  name: string
  scope: 'company' | 'department'
  toolIds: string[]
  usedByAgentIds: string[]
  createdAt: string
}

/** 核心模型之外的控制面辅助视图；不改变 CLAUDE.md 的共享实体字段。 */
export interface TrashMetadata {
  trashedAt: string
  trashedBy: string
  trashReason: string
}

export type TrashedApiSource = ApiSource & TrashMetadata

export interface AuditEvent {
  id: string
  actor: string
  action: 'create' | 'import' | 'trash' | 'restore' | 'purge' | string
  resourceType: string
  resourceId: string
  detail: Record<string, unknown>
  occurredAt: string
}

export interface JobProgress {
  jobId: string
  total: number
  done: number
  currentStep: string
  state: 'running' | 'done' | 'failed'
  error: string | null
}
