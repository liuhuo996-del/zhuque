import type { ApiSource, CapabilityGraph, Dashboard, PackArtifact, PackRecommendation, Registration, RuntimeSettingsPayload, RuntimeSettingsView, SemanticCluster, Tool } from '@/types'

export class ApiError extends Error {
  constructor(public what: string, public fix: string, public status: number) { super(what) }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (error) {
    throw new ApiError('无法连接 GateForge Python 后端', error instanceof Error ? `网络连接失败：${error.message}` : '检查 8081 端口', 0)
  }
  const text = await response.text()
  const body = text ? safeJson(text) : null
  if (!response.ok) {
    const value = body && typeof body === 'object' ? body as Record<string, unknown> : {}
    throw new ApiError(String(value.what ?? `HTTP ${response.status}`), String(value.fix ?? text), response.status)
  }
  return body as T
}

const json = (method: string, body?: unknown): RequestInit => ({ method, body: body === undefined ? undefined : JSON.stringify(body) })
const safeJson = (value: string): unknown => { try { return JSON.parse(value) } catch { return value } }

export const fetchDashboard = () => request<Dashboard>('/api/dashboard')
export const fetchSources = () => request<ApiSource[]>('/api/sources')
export const fetchTools = (accepted?: boolean) => request<Tool[]>(`/api/tools${accepted === undefined ? '' : `?accepted=${accepted}`}`)
export const fetchClusters = () => request<SemanticCluster[]>('/api/clusters')
export const fetchGraphs = () => request<CapabilityGraph[]>('/api/graphs')
export const rebuildGraphs = () => request<{ graphs: number; ready: number; needsInput: number; ambiguous: number; blocked: number }>('/api/graphs/rebuild', json('POST'))
export const recommendPack = (description: string) => request<PackRecommendation>('/api/packs/recommend', json('POST', { description }))
export const fetchPacks = () => request<PackArtifact[]>('/api/packs')
export const fetchPack = (id: string) => request<PackArtifact>(`/api/packs/${id}`)
export const fetchRegistrations = () => request<Registration[]>('/api/registrations')
export const fetchSettings = () => request<RuntimeSettingsView>('/api/settings')
export const probeNacos = () => request<{ ok: boolean; version: string; namespace: string }>('/api/nacos/probe')

const adminJson = (method: string, body: unknown, adminToken: string): RequestInit => ({
  ...json(method, body),
  headers: { 'X-GateForge-Admin-Token': adminToken },
})

export const saveSettings = (input: RuntimeSettingsPayload, adminToken: string) =>
  request<RuntimeSettingsView>('/api/settings', adminJson('PUT', input, adminToken))

export const testNacosSettings = (input: RuntimeSettingsPayload, adminToken: string) =>
  request<{ ok: boolean; version: string; namespace: string }>('/api/settings/test/nacos', adminJson('POST', input, adminToken))

export const testAiSettings = (input: RuntimeSettingsPayload, adminToken: string) =>
  request<{ ok: boolean; model: string; responseReceived: boolean }>('/api/settings/test/ai', adminJson('POST', input, adminToken))

export function importSource(input: {
  name: string
  slug?: string
  spec_url?: string
  spec_text?: string
  base_url?: string
  environment: string
  owner: string
}) {
  return request<ApiSource>('/api/sources', json('POST', input))
}

export function buildPack(input: {
  name?: string
  slug?: string
  description?: string
  cluster_keys?: string[]
  graph_ids?: string[]
  tool_ids?: string[]
  run_l1?: boolean
  run_l2?: boolean
}) {
  return request<PackArtifact>('/api/packs/build', json('POST', input))
}

export const registerPack = (id: string) => request<Registration>(`/api/packs/${id}/register`, json('POST'))
