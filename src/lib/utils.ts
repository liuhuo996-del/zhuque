import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDate(iso: string | null | undefined) {
  if (!iso) return '—'
  return iso.slice(0, 16).replace('T', ' ')
}

export function shortHash(hash: string) {
  const h = hash.replace(/^sha256:/, '')
  return `${h.slice(0, 10)}…`
}

export async function copyText(text: string) {
  await navigator.clipboard.writeText(text)
}

const labels: Record<string, string> = {
  ready: '已就绪', needs_input: '尚需入参', ambiguous: '存在歧义', blocked: '已阻断', pass: '通过', fail: '失败', warn: '警告', skip: '跳过',
  low: '低', medium: '中', high: '高', critical: '严重',
  readOnly: '只读', write: '写入', destructive: '破坏性',
  test: '测试', staging: '预发布', prod: '生产',
  search: '查询', read: '读取', create: '创建', update: '更新', delete: '删除', execute: '执行', operate: '操作',
  general: '通用', unassigned: '未分配', enabled: '已启用', disabled: '已停用',
  schema: '结构规范', 'mcp-contract': 'MCP 协议契约', 'parameter-boundary': '参数边界',
  security: '安全策略', permission: '权限策略', connectivity: '后端连通性',
  'response-schema': '响应结构', 'semantic-selection': '语义工具选择', 'dependency-closure': '依赖闭包',
  'graph-schema': '能力图结构', 'graph-edge': '字段绑定', 'graph-order': '执行顺序', 'graph-governance': '图治理传播',
  parameter: '参数闭包', type: '类型闭包', risk: '风险闭包', sideEffect: '副作用闭包',
  cycles: '循环依赖', reachability: '可达性',
  deprecated: '接口已废弃', 'operational-endpoint': '运行探针接口',
  'missing-description': '缺少描述', 'schema-too-large': '参数结构过大',
  'too-many-arguments': '参数过多', 'duplicate-semantic-operation': '语义重复接口',
  'higress-runtime-profile-incompatible': '与当前 Higress 运行配置不兼容',
}

export function zh(value: string | null | undefined) {
  if (!value) return '—'
  return labels[value] ?? value
}
