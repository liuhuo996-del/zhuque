export type JsonObject = Record<string, unknown>

export interface ApiSource {
  id: string
  name: string
  slug: string
  spec_url: string | null
  base_url: string
  environment: 'test' | 'staging' | 'prod'
  owner: string
  spec_hash: string
  imported_at: string
  operation_count: number
  accepted_count: number
  rejected_count: number
}

export interface GovernanceMetadata {
  schemaVersion: 'gateforge.governance/v1'
  intent: string[]
  domain: string[]
  readOnly: boolean
  write: boolean
  destructive: boolean
  idempotent: boolean | null
  sideEffect: 'none' | 'local' | 'external' | 'unknown'
  riskLevel: 'low' | 'medium' | 'high' | 'critical'
  approvalRequired: boolean
  approvalRole: string | null
  sensitivity: { input: string[]; output: string[] }
  requires: string[]
  provides: string[]
  retryable: boolean
  timeoutMs: number
  owner: string
  qualityScore: number
  descriptionScore: number
  schemaScore: number
  testPassRate: number
}

export interface Tool {
  id: string
  source_id: string
  source_name: string
  source_spec_hash: string
  accepted: boolean
  rejection_reasons: string[]
  method: string
  path: string
  operation_id: string
  standard: {
    name: string
    description: string
    inputSchema: JsonObject
    annotations: JsonObject
  }
  backend_mapping: JsonObject
  endpoint: JsonObject
  governance: GovernanceMetadata
  cluster_key: string
  fingerprint: string
}

export interface SemanticCluster {
  key: string
  label: string
  domain: string
  intent: string
  tool_ids: string[]
  tool_count: number
  source_count: number
  confidence: number
}

export interface TestCaseResult {
  layer: 'L0' | 'L1' | 'L2'
  category: string
  case_id: string
  status: 'pass' | 'fail' | 'warn' | 'skip'
  score: number
  detail: string
  evidence: JsonObject
}

export interface PackArtifact {
  schema_version: 'gateforge.mcp-pack/v1'
  id: string
  name: string
  slug: string
  description: string
  created_at: string
  status: 'ready' | 'blocked'
  mcp_server: JsonObject
  tools: Array<{ name: string; description: string; inputSchema: JsonObject; annotations?: JsonObject }>
  backend_mappings: JsonObject
  endpoints: JsonObject[]
  governance: { schemaVersion: string; digest: string; tools: Record<string, GovernanceMetadata> }
  dependency_graph: {
    schema_version: string
    nodes: Array<{ tool_id: string; requires: string[]; provides: string[] }>
    edges: Array<{ provider: string; consumer: string; fields: string[] }>
    closure: {
      parameter_closed: boolean
      type_closed: boolean
      permission_closed: boolean
      risk_closed: boolean
      side_effect_closed: boolean
      cycles: string[][]
      missing_providers: JsonObject
      ambiguous_providers: JsonObject
      unreachable_tools: string[]
    }
  }
  test_report: {
    schema_version: string
    cases: TestCaseResult[]
    pass_rate: number
    quality_score: number
    blocking_failures: number
    generated_at: string
  }
  build_manifest: JsonObject
  artifact_hash: string
}

export interface Registration {
  pack_id: string
  nacos_server_id: string
  nacos_version: string
  status: string
  mcp_name: string
  registered_at: string
  raw: JsonObject
}

export interface Dashboard {
  sources: number
  operations: number
  accepted_tools: number
  rejected_operations: number
  clusters: number
  packs: number
  ready_packs: number
  registered_packs: number
  average_quality: number
  recent_packs: PackArtifact[]
}
