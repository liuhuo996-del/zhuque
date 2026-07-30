// mock API：加一点延迟，让骨架屏可见。全部只读，写操作在页面内用本地状态模拟。
import * as db from './data'

const delay = (ms = 450) => new Promise((r) => setTimeout(r, ms))

export async function fetchDepartments() {
  await delay(200)
  return db.departments
}
export async function fetchAgents(departmentId?: string) {
  await delay()
  return departmentId && departmentId !== 'all'
    ? db.agents.filter((a) => a.departmentId === departmentId)
    : db.agents
}
export async function fetchAgent(id: string) {
  await delay(300)
  const a = db.agents.find((x) => x.id === id)
  if (!a) throw new Error(`agent ${id} 不存在`)
  return a
}
export async function fetchApiSources() {
  await delay()
  return db.apiSources
}
export async function fetchTools() {
  await delay()
  return db.tools
}
export async function fetchPacks(departmentId?: string) {
  await delay()
  return departmentId && departmentId !== 'all'
    ? db.packs.filter((p) => p.departmentId === departmentId)
    : db.packs
}
export async function fetchReleases(departmentId?: string) {
  await delay()
  if (!departmentId || departmentId === 'all') return db.releases
  const agentIds = new Set(db.agents.filter((a) => a.departmentId === departmentId).map((a) => a.id))
  return db.releases.filter((r) => agentIds.has(r.agentId))
}
export async function fetchRelease(id: string) {
  await delay(300)
  const r = db.releases.find((x) => x.id === id)
  if (!r) throw new Error(`release ${id} 不存在`)
  return r
}
export async function fetchDriftEvents() {
  await delay()
  return db.driftEvents
}
export async function fetchAgentKeys(agentId: string) {
  await delay(250)
  return db.agentKeys.filter((k) => k.agentId === agentId)
}
export async function fetchAgentIntents(agentId: string) {
  await delay(250)
  return db.agentIntents[agentId] ?? []
}
