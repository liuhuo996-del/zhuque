import type { ApiSource, Dashboard, PackArtifact, Registration, SemanticCluster, Tool } from '@/types'

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
export const fetchPacks = () => request<PackArtifact[]>('/api/packs')
export const fetchPack = (id: string) => request<PackArtifact>(`/api/packs/${id}`)
export const fetchRegistrations = () => request<Registration[]>('/api/registrations')
export const fetchSettings = () => request<Record<string, unknown>>('/api/settings')
export const probeNacos = () => request<{ ok: boolean; version: string; namespace: string }>('/api/nacos/probe')

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
  cluster_keys: string[]
  tool_ids: string[]
  run_l1: boolean
  run_l2: boolean
}) {
  return request<PackArtifact>('/api/packs/build', json('POST', input))
}

export const registerPack = (id: string) => request<Registration>(`/api/packs/${id}/register`, json('POST'))
