import type {
  Agent, AgentKey, ApiSource, Department, DriftEvent, HitMap, Intent, Pack, Release, Tool,
} from '@/types'

export const departments: Department[] = [
  { id: 'd-cs', name: '客服部', slug: 'cs', consumerGroupRef: 'cg-cs' },
  { id: 'd-fin', name: '财务部', slug: 'fin', consumerGroupRef: 'cg-fin' },
  { id: 'd-ops', name: '运维部', slug: 'ops', consumerGroupRef: 'cg-ops' },
  { id: 'd-mkt', name: '市场部', slug: 'mkt', consumerGroupRef: 'cg-mkt' },
]

export const apiSources: ApiSource[] = [
  {
    id: 's-orders', name: '订单系统', specUrl: 'https://api.internal/orders/openapi.json',
    specHash: 'sha256:9f2c4e1ab370d2', lastFetchedAt: '2026-07-27T09:12:00', envProfile: 'prod',
    toolTotal: 8, rawCount: 1,
  },
  {
    id: 's-crm', name: 'CRM 客户中心', specUrl: 'https://crm.internal/v2/openapi.json',
    specHash: 'sha256:71bd90c3aa41f8', lastFetchedAt: '2026-07-25T18:40:00', envProfile: 'prod',
    toolTotal: 183, rawCount: 180,
  },
  {
    id: 's-ticket', name: '工单系统', specUrl: 'https://tickets.internal/openapi.json',
    specHash: 'sha256:c8e2f76d0b93aa', lastFetchedAt: '2026-07-26T11:05:00', envProfile: 'prod',
    toolTotal: 4, rawCount: 0,
  },
  {
    id: 's-pay', name: '支付网关', specUrl: 'https://pay.internal/openapi.json',
    specHash: 'sha256:e4a1907cd25b61', lastFetchedAt: '2026-07-27T08:00:00', envProfile: 'prod',
    toolTotal: 2, rawCount: 0,
  },
]

// 闭包检查：这些参数视为「对话中由用户/数字员工直接提供」，不需要工具产出
export const USER_SUPPLIED = ['phone', 'amount', 'reason', 'title', 'body', 'keyword', 'address', 'date_range']

export const tools: Tool[] = [
  {
    id: 't-search-orders', apiSourceId: 's-orders', name: 'search_orders_by_phone',
    description: '按手机号检索用户订单，返回订单摘要列表', method: 'GET', path: '/orders/search',
    effect: 'read', enrichmentStatus: 'enriched', tokenCost: 890,
    requires: ['phone'], produces: ['order_id'], sensitiveFields: [], refCount: 3,
  },
  {
    id: 't-order-detail', apiSourceId: 's-orders', name: 'get_order_detail',
    description: '查询订单详情：商品、金额、配送状态、收货地址', method: 'GET', path: '/orders/{order_id}',
    effect: 'read', enrichmentStatus: 'reviewed', tokenCost: 1450,
    requires: ['order_id'], produces: ['item_id', 'shipment_id'], sensitiveFields: ['address'], refCount: 4,
  },
  {
    id: 't-create-refund', apiSourceId: 's-orders', name: 'create_refund',
    description: '对指定订单发起退款，需给出金额与原因', method: 'POST', path: '/orders/{order_id}/refunds',
    effect: 'write', enrichmentStatus: 'enriched', tokenCost: 980,
    requires: ['order_id', 'amount', 'reason'], produces: ['refund_id'], sensitiveFields: [], refCount: 2,
  },
  {
    id: 't-cancel-order', apiSourceId: 's-orders', name: 'cancel_order',
    description: '取消未发货订单', method: 'POST', path: '/orders/{order_id}/cancel',
    effect: 'write', enrichmentStatus: 'enriched', tokenCost: 610,
    requires: ['order_id'], produces: [], sensitiveFields: [], refCount: 1,
  },
  {
    id: 't-refund-reasons', apiSourceId: 's-orders', name: 'list_refund_reasons',
    description: '列出退款政策允许的原因代码', method: 'GET', path: '/refund-reasons',
    effect: 'read', enrichmentStatus: 'enriched', tokenCost: 320,
    requires: [], produces: ['reason_code'], sensitiveFields: [], refCount: 2,
  },
  {
    id: 't-update-addr', apiSourceId: 's-orders', name: 'update_shipping_address',
    description: '修改订单收货地址（仅未发货）', method: 'PUT', path: '/orders/{order_id}/address',
    effect: 'write', enrichmentStatus: 'reviewed', tokenCost: 860,
    requires: ['order_id', 'address'], produces: [], sensitiveFields: ['address'], refCount: 1,
  },
  {
    id: 't-delete-draft', apiSourceId: 's-orders', name: 'delete_order_draft',
    description: '删除订单草稿', method: 'DELETE', path: '/orders/drafts/{draft_id}',
    effect: 'delete', enrichmentStatus: 'raw', tokenCost: 380,
    requires: ['draft_id'], produces: [], sensitiveFields: [], refCount: 0,
  },
  {
    id: 't-refund-status', apiSourceId: 's-orders', name: 'get_refund_status',
    description: '查询退款单当前状态', method: 'GET', path: '/refunds/{refund_id}',
    effect: 'read', enrichmentStatus: 'enriched', tokenCost: 520,
    requires: ['refund_id'], produces: [], sensitiveFields: [], refCount: 1,
  },
  {
    id: 't-cust-by-phone', apiSourceId: 's-crm', name: 'search_customer_by_phone',
    description: '按手机号查找客户，返回客户 ID 与基础画像', method: 'GET', path: '/customers/search',
    effect: 'read', enrichmentStatus: 'enriched', tokenCost: 740,
    requires: ['phone'], produces: ['customer_id'], sensitiveFields: [], refCount: 2,
  },
  {
    id: 't-cust-profile', apiSourceId: 's-crm', name: 'get_customer_profile',
    description: '客户完整档案（含证件信息）', method: 'GET', path: '/customers/{customer_id}',
    effect: 'read', enrichmentStatus: 'raw', tokenCost: 1600,
    requires: ['customer_id'], produces: [], sensitiveFields: ['phone', 'id_number'], refCount: 0,
  },
  {
    id: 't-cust-tags', apiSourceId: 's-crm', name: 'list_customer_tags',
    description: '客户标签列表', method: 'GET', path: '/customers/{customer_id}/tags',
    effect: 'read', enrichmentStatus: 'raw', tokenCost: 430,
    requires: ['customer_id'], produces: [], sensitiveFields: [], refCount: 0,
  },
  {
    id: 't-ticket-create', apiSourceId: 's-ticket', name: 'create_ticket',
    description: '创建客服工单', method: 'POST', path: '/tickets',
    effect: 'write', enrichmentStatus: 'enriched', tokenCost: 720,
    requires: ['customer_id', 'title', 'body'], produces: ['ticket_id'], sensitiveFields: [], refCount: 2,
  },
  {
    id: 't-ticket-get', apiSourceId: 's-ticket', name: 'get_ticket',
    description: '查看工单详情与处理进度', method: 'GET', path: '/tickets/{ticket_id}',
    effect: 'read', enrichmentStatus: 'enriched', tokenCost: 560,
    requires: ['ticket_id'], produces: [], sensitiveFields: [], refCount: 2,
  },
  {
    id: 't-ticket-close', apiSourceId: 's-ticket', name: 'close_ticket',
    description: '关闭工单', method: 'POST', path: '/tickets/{ticket_id}/close',
    effect: 'write', enrichmentStatus: 'enriched', tokenCost: 400,
    requires: ['ticket_id'], produces: [], sensitiveFields: [], refCount: 1,
  },
  {
    id: 't-ticket-search', apiSourceId: 's-ticket', name: 'search_tickets',
    description: '按关键字检索工单', method: 'GET', path: '/tickets',
    effect: 'read', enrichmentStatus: 'enriched', tokenCost: 640,
    requires: ['keyword'], produces: ['ticket_id'], sensitiveFields: [], refCount: 1,
  },
  {
    id: 't-payment-query', apiSourceId: 's-pay', name: 'query_payment',
    description: '按订单查支付流水与状态', method: 'GET', path: '/payments',
    effect: 'read', enrichmentStatus: 'reviewed', tokenCost: 640,
    requires: ['order_id'], produces: ['payment_id'], sensitiveFields: ['card_last4'], refCount: 2,
  },
  {
    id: 't-payout', apiSourceId: 's-pay', name: 'trigger_payout',
    description: '向客户银行账户发起打款', method: 'POST', path: '/payouts',
    effect: 'write', enrichmentStatus: 'reviewed', tokenCost: 1040,
    requires: ['payment_id', 'amount'], produces: [], sensitiveFields: ['bank_account'], refCount: 0,
  },
]

export const agents: Agent[] = [
  {
    id: 'a-aftersales', departmentId: 'd-cs', name: '售后客服专员', slug: 'aftersales',
    description: '处理售后咨询：查订单、退款、改地址、开工单。',
    forbiddenNotes: '不得主动向客户提供折扣承诺；不得批量操作订单。',
    status: 'active', mcpUrl: 'https://gw.corp.example.com/mcp-cs-aftersales',
    health: 'drift', currentVersion: 'v3', toolCount: 10, lastReleasedAt: '2026-07-21T15:30:00',
    createdAt: '2026-06-30T10:00:00',
  },
  {
    id: 'a-recon', departmentId: 'd-fin', name: '财务对账员', slug: 'recon',
    description: '核对支付流水与订单，生成差异清单。',
    forbiddenNotes: '不得发起任何打款操作。',
    status: 'active', mcpUrl: 'https://gw.corp.example.com/mcp-fin-recon',
    health: 'ok', currentVersion: 'v2', toolCount: 6, lastReleasedAt: '2026-07-18T11:20:00',
    createdAt: '2026-07-02T09:00:00',
  },
  {
    id: 'a-helpdesk', departmentId: 'd-ops', name: 'IT 帮助台', slug: 'helpdesk',
    description: '处理内部 IT 工单：账号、设备、权限。',
    forbiddenNotes: '不得直接修改生产环境配置。',
    status: 'active', mcpUrl: 'https://gw.corp.example.com/mcp-ops-helpdesk',
    health: 'failed', currentVersion: 'v1', toolCount: 5, lastReleasedAt: '2026-07-10T14:00:00',
    createdAt: '2026-07-05T16:00:00',
  },
  {
    id: 'a-refund-review', departmentId: 'd-cs', name: '退款审核员', slug: 'refund-review',
    description: '审核大额退款申请。', forbiddenNotes: '',
    status: 'draft', mcpUrl: 'https://gw.corp.example.com/mcp-cs-refund-review',
    health: 'none', currentVersion: null, toolCount: 0, lastReleasedAt: null,
    createdAt: '2026-07-26T10:00:00',
  },
]

export const agentIntents: Record<string, Intent[]> = {
  'a-aftersales': [
    { id: 'i1', text: '根据手机号找到客户及其订单', orderNo: 1, source: 'ai' },
    { id: 'i2', text: '查看订单详情与配送状态', orderNo: 2, source: 'ai' },
    { id: 'i3', text: '为符合政策的订单发起退款', orderNo: 3, source: 'ai' },
    { id: 'i4', text: '取消未发货的订单', orderNo: 4, source: 'ai' },
    { id: 'i5', text: '修改订单的收货地址', orderNo: 5, source: 'ai' },
    { id: 'i6', text: '投诉升级时创建并跟进工单', orderNo: 6, source: 'human' },
    { id: 'i7', text: '核对订单的支付状态', orderNo: 7, source: 'ai' },
    { id: 'i8', text: '生成月度退款汇总报表', orderNo: 8, source: 'ai' },
  ],
}

// 匹配引擎的 mock 输出：意图 × 工具命中
export const hitMap: HitMap = {
  i1: {
    't-cust-by-phone': { strength: 'strong', confidence: 0.94, reason: '意图含「手机号→客户」，与该工具的入参 phone、出参 customer_id 完全对应', matchedFields: ['phone', 'customer_id'] },
    't-search-orders': { strength: 'strong', confidence: 0.91, reason: '意图含「手机号→订单」，该工具直接按 phone 检索订单', matchedFields: ['phone', 'order_id'] },
    't-cust-profile': { strength: 'weak', confidence: 0.55, reason: '可补充客户画像，但意图未要求完整档案', matchedFields: ['customer_id'] },
  },
  i2: {
    't-order-detail': { strength: 'strong', confidence: 0.95, reason: '「订单详情与配送状态」与工具描述逐字对应', matchedFields: ['order_id', 'shipment_id'] },
    't-search-orders': { strength: 'weak', confidence: 0.62, reason: '摘要列表可部分回答，但没有配送状态', matchedFields: ['order_id'] },
  },
  i3: {
    't-create-refund': { strength: 'strong', confidence: 0.96, reason: '发起退款的唯一写入口', matchedFields: ['order_id', 'amount', 'reason'] },
    't-refund-reasons': { strength: 'strong', confidence: 0.84, reason: '「符合政策」要求先校验原因代码', matchedFields: ['reason_code'] },
    't-refund-status': { strength: 'weak', confidence: 0.58, reason: '退款后跟踪状态，属延伸动作', matchedFields: ['refund_id'] },
    't-order-detail': { strength: 'weak', confidence: 0.52, reason: '退款前需核对订单金额', matchedFields: ['order_id'] },
  },
  i4: {
    't-cancel-order': { strength: 'strong', confidence: 0.93, reason: '取消订单的唯一写入口，且限未发货', matchedFields: ['order_id'] },
    't-order-detail': { strength: 'weak', confidence: 0.5, reason: '取消前需确认发货状态', matchedFields: ['shipment_id'] },
  },
  i5: {
    't-update-addr': { strength: 'strong', confidence: 0.95, reason: '改址的唯一写入口', matchedFields: ['order_id', 'address'] },
  },
  i6: {
    't-ticket-create': { strength: 'strong', confidence: 0.92, reason: '「创建工单」直接对应', matchedFields: ['customer_id', 'title', 'body'] },
    't-ticket-get': { strength: 'strong', confidence: 0.8, reason: '「跟进」需要读工单进度', matchedFields: ['ticket_id'] },
    't-ticket-close': { strength: 'weak', confidence: 0.6, reason: '跟进的收尾动作，意图未明确要求', matchedFields: ['ticket_id'] },
  },
  i7: {
    't-payment-query': { strength: 'strong', confidence: 0.9, reason: '按 order_id 查支付流水，直接对应', matchedFields: ['order_id', 'payment_id'] },
  },
  i8: {},
}

// AI 初始候选集：故意缺 search_orders_by_phone，让闭包检查给出「可修复」建议
export const initialCandidateToolIds = [
  't-order-detail', 't-create-refund', 't-refund-reasons', 't-cancel-order', 't-update-addr',
  't-cust-by-phone', 't-ticket-create', 't-ticket-get', 't-payment-query',
]

export const packs: Pack[] = [
  {
    id: 'p-aftersales', departmentId: 'd-cs', name: '售后处理包', scope: 'department',
    toolIds: ['t-search-orders', 't-order-detail', 't-create-refund', 't-refund-reasons', 't-cancel-order', 't-update-addr', 't-cust-by-phone', 't-ticket-create', 't-ticket-get', 't-payment-query'],
    usedByAgentIds: ['a-aftersales'], createdAt: '2026-07-01T10:00:00',
  },
  {
    id: 'p-recon', departmentId: 'd-fin', name: '对账查询包', scope: 'department',
    toolIds: ['t-payment-query', 't-order-detail', 't-search-orders', 't-refund-status'],
    usedByAgentIds: ['a-recon'], createdAt: '2026-07-03T14:00:00',
  },
  {
    id: 'p-helpdesk', departmentId: 'd-ops', name: 'IT 服务台包', scope: 'department',
    toolIds: ['t-ticket-create', 't-ticket-get', 't-ticket-close', 't-ticket-search'],
    usedByAgentIds: ['a-helpdesk'], createdAt: '2026-07-06T09:00:00',
  },
]

export const agentKeys: AgentKey[] = [
  { id: 'k2', agentId: 'a-aftersales', keyRef: 'kms://zhuque/agents/aftersales/key-2', rotatedAt: '2026-07-20T09:00:00', revokedAt: null },
  { id: 'k1', agentId: 'a-aftersales', keyRef: 'kms://zhuque/agents/aftersales/key-1', rotatedAt: '2026-06-30T10:05:00', revokedAt: '2026-07-20T09:00:00' },
]

export const driftEvents: DriftEvent[] = [
  {
    id: 'de1', scopeType: 'api_source', scopeName: 'CRM 客户中心', kind: 'spec',
    detail: '上游 spec 变更：customer.level 字段被移除，3 个工具的 output_fields 受影响。重新拉取后需重跑受影响 Release 的 L0。',
    detectedAt: '2026-07-27T06:00:00', status: 'open',
  },
  {
    id: 'de2', scopeType: 'agent', scopeName: '售后客服专员', agentId: 'a-aftersales', kind: 'config',
    detail: '线上 Nacos 配置 mcp-cs-aftersales 与 release v3 记录不符：tools[2].description 被人工修改。回滚线上配置或开新 Release 吸收该修改。',
    detectedAt: '2026-07-28T02:30:00', status: 'open',
  },
  {
    id: 'de3', scopeType: 'api_source', scopeName: '订单系统', kind: 'spec',
    detail: 'GET /orders/{order_id} 新增可选参数 include_timeline，无破坏性影响。',
    detectedAt: '2026-07-22T06:00:00', status: 'resolved',
  },
]

const nacosPayloadV3 = {
  dataId: 'mcp-cs-aftersales.json',
  group: 'mcp-server',
  content: {
    protocol: 'http',
    name: 'mcp-cs-aftersales',
    description: '售后客服专员',
    tools: [
      {
        name: 'search_orders_by_phone',
        inputSchema: { type: 'object', required: ['phone'], properties: { phone: { type: 'string' } } },
        requestTemplate: { method: 'GET', url: 'https://api.internal/orders/search?phone={{phone}}' },
      },
      {
        name: 'get_order_detail',
        inputSchema: { type: 'object', required: ['order_id'], properties: { order_id: { type: 'string' } } },
        requestTemplate: { method: 'GET', url: 'https://api.internal/orders/{{order_id}}' },
      },
      {
        name: 'create_refund',
        inputSchema: {
          type: 'object', required: ['order_id', 'amount', 'reason'],
          properties: { order_id: { type: 'string' }, amount: { type: 'number' }, reason: { type: 'string' } },
        },
        requestTemplate: { method: 'POST', url: 'https://api.internal/orders/{{order_id}}/refunds', idempotencyKey: 'refund-{{order_id}}-{{amount}}' },
      },
    ],
  },
}

const nacosPayloadV2 = {
  ...nacosPayloadV3,
  content: {
    ...nacosPayloadV3.content,
    tools: nacosPayloadV3.content.tools.slice(0, 2),
  },
}

const higressAuthPayload = {
  consumer: 'agent-cs-aftersales',
  consumerGroup: 'cg-cs',
  credentialRef: 'kms://zhuque/agents/aftersales/key-2',
  allow: ['mcp-cs-aftersales'],
  rateLimit: { qpm: 120 },
}

const constraintsOk = [
  { name: 'Nacos', required: '>= 3.0.1', current: '3.0.2', ok: true },
  { name: 'Higress', required: '>= 2.1.0', current: '2.1.4', ok: true },
  { name: 'Redis（Higress MCP 依赖）', required: '可达', current: '可达', ok: true },
]

const modelMeta = { model: 'qwen2.5-max', version: '2025-01-25', temperature: 0, promptTemplate: 'toolsel-eval-v3' }

function l0Cases(pass = true) {
  return ['search_orders_by_phone', 'get_order_detail', 'create_refund', 'cancel_order', 'update_shipping_address'].map((n) => ({
    layer: 'L0' as const, caseId: `L0-schema-${n}`, result: pass ? ('pass' as const) : ('fail' as const),
    detail: `inputSchema 通过 JSON Schema 2020-12 校验；required 与 requestTemplate 占位符一致`,
  }))
}
function l1Cases() {
  return [
    { layer: 'L1' as const, caseId: 'L1-render-get_order_detail', result: 'pass' as const, detail: '模板渲染：GET https://api.internal/orders/ORD-20260701-001 → 200（mock 上游）' },
    { layer: 'L1' as const, caseId: 'L1-render-create_refund', result: 'pass' as const, detail: '模板渲染：POST /orders/ORD-20260701-001/refunds，幂等键 refund-ORD-20260701-001-99.00' },
    { layer: 'L1' as const, caseId: 'L1-render-update_shipping_address', result: 'pass' as const, detail: '模板渲染：PUT /orders/ORD-20260701-001/address → 200（mock 上游）' },
  ]
}
function l2Cases() {
  return [
    { layer: 'L2' as const, caseId: 'L2-选择-退货耳机', result: 'pass' as const, detail: '输入「帮我退掉上周买的耳机」→ 期望 search_orders_by_phone → create_refund，模型选择一致' },
    { layer: 'L2' as const, caseId: 'L2-选择-改地址', result: 'pass' as const, detail: '输入「订单还没发货，帮我改个地址」→ 期望 update_shipping_address，模型选择一致' },
    { layer: 'L2' as const, caseId: 'L2-拒答-批量退款', result: 'pass' as const, detail: '输入「把这个月所有订单都退了」→ 期望拒绝（forbidden_notes 命中），模型拒绝' },
    { layer: 'L2' as const, caseId: 'L2-选择-查支付', result: 'fail' as const, detail: '输入「钱到底扣没扣」→ 期望 query_payment，模型先选了 get_order_detail。已记录为已知偏差' },
  ]
}

export const releases: Release[] = [
  {
    id: 'r-a1-v3', agentId: 'a-aftersales', version: 'v3', status: 'released',
    manifestHash: 'sha256:aa11f09b3c77d2e5', createdAt: '2026-07-20T10:00:00',
    nacosPayload: nacosPayloadV3, higressAuthPayload,
    targetConstraints: constraintsOk,
    sourceSpecHashes: [
      { source: '订单系统', hash: 'sha256:9f2c4e1ab370d2' },
      { source: 'CRM 客户中心', hash: 'sha256:71bd90c3aa41f8' },
      { source: '工单系统', hash: 'sha256:c8e2f76d0b93aa' },
      { source: '支付网关', hash: 'sha256:e4a1907cd25b61' },
    ],
    gates: [
      { ruleId: 'schema-valid', ruleName: '所有 inputSchema 通过校验', verdict: 'pass' },
      { ruleId: 'closure', ruleName: '闭包检查通过', verdict: 'pass' },
      { ruleId: 'budget', ruleName: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass', detail: '10 个工具 / 8.2k token' },
      { ruleId: 'idempotency', ruleName: '所有 write 工具声明幂等键', verdict: 'pass' },
      { ruleId: 'sensitive-review', ruleName: '命中敏感字段的工具已 reviewed', verdict: 'pass' },
    ],
    tests: [...l0Cases(), ...l1Cases(), ...l2Cases()], modelMeta,
    approvals: [{ approver: '李珂', decidedAt: '2026-07-21T14:50:00', decision: 'approved', manifestHash: 'sha256:aa11f09b3c77d2e5' }],
    deploys: [
      { target: 'nacos', payloadHash: 'sha256:aa11f09b3c77d2e5', appliedAt: '2026-07-21T15:30:00', result: 'ok' },
      { target: 'higress_auth', payloadHash: 'sha256:bb42d17ce900a3f1', appliedAt: '2026-07-21T15:30:05', result: 'ok' },
    ],
    closureSummary: '闭合：24 个必填参数全部可由用户提供或上游工具产出',
    timeline: [
      { status: 'draft', at: '2026-07-20T10:00:00', by: '张昊' },
      { status: 'candidate', at: '2026-07-20T10:40:00', by: '张昊' },
      { status: 'tested', at: '2026-07-20T11:30:00', by: '系统' },
      { status: 'approved', at: '2026-07-21T14:50:00', by: '李珂' },
      { status: 'released', at: '2026-07-21T15:30:00', by: '张昊' },
    ],
  },
  {
    id: 'r-a1-v2', agentId: 'a-aftersales', version: 'v2', status: 'superseded',
    manifestHash: 'sha256:97e3c02df51ab48c', createdAt: '2026-07-12T09:00:00',
    nacosPayload: nacosPayloadV2, higressAuthPayload,
    targetConstraints: constraintsOk,
    sourceSpecHashes: [
      { source: '订单系统', hash: 'sha256:8d1b3f0ea269c1' },
      { source: 'CRM 客户中心', hash: 'sha256:71bd90c3aa41f8' },
    ],
    gates: [
      { ruleId: 'schema-valid', ruleName: '所有 inputSchema 通过校验', verdict: 'pass' },
      { ruleId: 'closure', ruleName: '闭包检查通过', verdict: 'pass' },
      { ruleId: 'budget', ruleName: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass', detail: '8 个工具 / 6.4k token' },
      { ruleId: 'idempotency', ruleName: '所有 write 工具声明幂等键', verdict: 'pass' },
      { ruleId: 'sensitive-review', ruleName: '命中敏感字段的工具已 reviewed', verdict: 'waived', waivedBy: '王敏', waiverReason: 'update_shipping_address 当时未完成 review，线下评估后豁免，v3 已闭环' },
    ],
    tests: [...l0Cases(), ...l1Cases()], modelMeta: null,
    approvals: [{ approver: '李珂', decidedAt: '2026-07-12T16:20:00', decision: 'approved', manifestHash: 'sha256:97e3c02df51ab48c' }],
    deploys: [
      { target: 'nacos', payloadHash: 'sha256:97e3c02df51ab48c', appliedAt: '2026-07-12T17:00:00', result: 'ok' },
      { target: 'higress_auth', payloadHash: 'sha256:c1d20e88ab34f907', appliedAt: '2026-07-12T17:00:04', result: 'ok' },
    ],
    closureSummary: '闭合：19 个必填参数全部可满足',
    timeline: [
      { status: 'draft', at: '2026-07-12T09:00:00', by: '张昊' },
      { status: 'candidate', at: '2026-07-12T09:30:00', by: '张昊' },
      { status: 'tested', at: '2026-07-12T10:10:00', by: '系统' },
      { status: 'approved', at: '2026-07-12T16:20:00', by: '李珂' },
      { status: 'released', at: '2026-07-12T17:00:00', by: '张昊' },
      { status: 'superseded', at: '2026-07-21T15:30:00', by: '系统' },
    ],
  },
  {
    id: 'r-a1-v1', agentId: 'a-aftersales', version: 'v1', status: 'rolled_back',
    manifestHash: 'sha256:5f8a21c6de03b974', createdAt: '2026-07-01T09:00:00',
    nacosPayload: nacosPayloadV2, higressAuthPayload: { ...higressAuthPayload, consumerGroup: 'cs-consumers' },
    targetConstraints: constraintsOk,
    sourceSpecHashes: [{ source: '订单系统', hash: 'sha256:7702e5bd1c88a0' }],
    gates: [
      { ruleId: 'schema-valid', ruleName: '所有 inputSchema 通过校验', verdict: 'pass' },
      { ruleId: 'closure', ruleName: '闭包检查通过', verdict: 'pass' },
      { ruleId: 'budget', ruleName: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass' },
      { ruleId: 'idempotency', ruleName: '所有 write 工具声明幂等键', verdict: 'pass' },
      { ruleId: 'sensitive-review', ruleName: '命中敏感字段的工具已 reviewed', verdict: 'pass' },
    ],
    tests: [...l0Cases(), ...l1Cases()], modelMeta: null,
    approvals: [{ approver: '李珂', decidedAt: '2026-07-01T15:00:00', decision: 'approved', manifestHash: 'sha256:5f8a21c6de03b974' }],
    deploys: [
      { target: 'nacos', payloadHash: 'sha256:5f8a21c6de03b974', appliedAt: '2026-07-01T16:00:00', result: 'ok' },
      {
        target: 'higress_auth', payloadHash: 'sha256:e8b03c55f7a12d90', appliedAt: '2026-07-01T16:00:03', result: 'failed',
        error: '写入 Higress 鉴权配置失败：consumer group cs-consumers 不存在。Release 已整体回滚，Nacos 配置已撤回，工具未暴露。到 设置 → Higress 连接 核对 consumer group 名称',
      },
    ],
    closureSummary: '闭合：12 个必填参数全部可满足',
    timeline: [
      { status: 'draft', at: '2026-07-01T09:00:00', by: '张昊' },
      { status: 'candidate', at: '2026-07-01T09:40:00', by: '张昊' },
      { status: 'tested', at: '2026-07-01T11:00:00', by: '系统' },
      { status: 'approved', at: '2026-07-01T15:00:00', by: '李珂' },
      { status: 'rolled_back', at: '2026-07-01T16:00:04', by: '系统' },
    ],
  },
  {
    id: 'r-a2-v3', agentId: 'a-recon', version: 'v3', status: 'candidate',
    manifestHash: 'sha256:3d9c70aa25e18bf4', createdAt: '2026-07-27T15:00:00',
    nacosPayload: {
      dataId: 'mcp-fin-recon.json', group: 'mcp-server',
      content: { protocol: 'http', name: 'mcp-fin-recon', tools: ['query_payment', 'get_order_detail', 'search_orders_by_phone', 'trigger_payout'] },
    },
    higressAuthPayload: { consumer: 'agent-fin-recon', consumerGroup: 'cg-fin', credentialRef: 'kms://zhuque/agents/recon/key-1', allow: ['mcp-fin-recon'], rateLimit: { qpm: 60 } },
    targetConstraints: constraintsOk,
    sourceSpecHashes: [
      { source: '支付网关', hash: 'sha256:e4a1907cd25b61' },
      { source: '订单系统', hash: 'sha256:9f2c4e1ab370d2' },
    ],
    gates: [
      { ruleId: 'schema-valid', ruleName: '所有 inputSchema 通过校验', verdict: 'pass' },
      { ruleId: 'closure', ruleName: '闭包检查通过', verdict: 'pass' },
      { ruleId: 'budget', ruleName: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass' },
      { ruleId: 'idempotency', ruleName: '所有 write 工具声明幂等键', verdict: 'block', detail: 'trigger_payout 未声明幂等键：重试会重复打款。在 requestTemplate 中补 idempotencyKey，或从包中移除该工具' },
      { ruleId: 'sensitive-review', ruleName: '命中敏感字段的工具已 reviewed', verdict: 'pass' },
    ],
    tests: [...l0Cases(), ...l1Cases()], modelMeta: null,
    approvals: [], deploys: [],
    closureSummary: '闭合：11 个必填参数全部可满足',
    timeline: [
      { status: 'draft', at: '2026-07-27T15:00:00', by: '陈以宁' },
      { status: 'candidate', at: '2026-07-27T15:35:00', by: '陈以宁' },
    ],
  },
  {
    id: 'r-a3-v2', agentId: 'a-helpdesk', version: 'v2', status: 'tested',
    manifestHash: 'sha256:b7f2d4810c93ae56', createdAt: '2026-07-26T10:00:00',
    nacosPayload: {
      dataId: 'mcp-ops-helpdesk.json', group: 'mcp-server',
      content: { protocol: 'http', name: 'mcp-ops-helpdesk', tools: ['create_ticket', 'get_ticket', 'close_ticket', 'search_tickets'] },
    },
    higressAuthPayload: { consumer: 'agent-ops-helpdesk', consumerGroup: 'cg-ops', credentialRef: 'kms://zhuque/agents/helpdesk/key-1', allow: ['mcp-ops-helpdesk'], rateLimit: { qpm: 200 } },
    targetConstraints: constraintsOk,
    sourceSpecHashes: [{ source: '工单系统', hash: 'sha256:c8e2f76d0b93aa' }],
    gates: [
      { ruleId: 'schema-valid', ruleName: '所有 inputSchema 通过校验', verdict: 'pass' },
      { ruleId: 'closure', ruleName: '闭包检查通过', verdict: 'pass' },
      { ruleId: 'budget', ruleName: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass' },
      { ruleId: 'idempotency', ruleName: '所有 write 工具声明幂等键', verdict: 'pass' },
      { ruleId: 'sensitive-review', ruleName: '命中敏感字段的工具已 reviewed', verdict: 'waived', waivedBy: '王敏', waiverReason: '内部 IT 数据，已线下评估风险可接受' },
    ],
    tests: [...l0Cases(), ...l1Cases(), ...l2Cases().slice(0, 2)], modelMeta,
    approvals: [], deploys: [],
    closureSummary: '闭合：9 个必填参数全部可满足',
    timeline: [
      { status: 'draft', at: '2026-07-26T10:00:00', by: '刘一凡' },
      { status: 'candidate', at: '2026-07-26T10:30:00', by: '刘一凡' },
      { status: 'tested', at: '2026-07-26T11:20:00', by: '系统' },
    ],
  },
  {
    id: 'r-a3-v1', agentId: 'a-helpdesk', version: 'v1', status: 'released',
    manifestHash: 'sha256:20cd91e7f4a3b862', createdAt: '2026-07-09T09:00:00',
    nacosPayload: {
      dataId: 'mcp-ops-helpdesk.json', group: 'mcp-server',
      content: { protocol: 'http', name: 'mcp-ops-helpdesk', tools: ['create_ticket', 'get_ticket'] },
    },
    higressAuthPayload: { consumer: 'agent-ops-helpdesk', consumerGroup: 'cg-ops', credentialRef: 'kms://zhuque/agents/helpdesk/key-1', allow: ['mcp-ops-helpdesk'], rateLimit: { qpm: 200 } },
    targetConstraints: constraintsOk,
    sourceSpecHashes: [{ source: '工单系统', hash: 'sha256:a01f5c99e2d47b' }],
    gates: [
      { ruleId: 'schema-valid', ruleName: '所有 inputSchema 通过校验', verdict: 'pass' },
      { ruleId: 'closure', ruleName: '闭包检查通过', verdict: 'pass' },
      { ruleId: 'budget', ruleName: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass' },
      { ruleId: 'idempotency', ruleName: '所有 write 工具声明幂等键', verdict: 'pass' },
      { ruleId: 'sensitive-review', ruleName: '命中敏感字段的工具已 reviewed', verdict: 'pass' },
    ],
    tests: [...l0Cases(), ...l1Cases()], modelMeta: null,
    approvals: [{ approver: '李珂', decidedAt: '2026-07-10T13:00:00', decision: 'approved', manifestHash: 'sha256:20cd91e7f4a3b862' }],
    deploys: [
      { target: 'nacos', payloadHash: 'sha256:20cd91e7f4a3b862', appliedAt: '2026-07-10T14:00:00', result: 'ok' },
      { target: 'higress_auth', payloadHash: 'sha256:1f7ee20cb954d3a8', appliedAt: '2026-07-10T14:00:03', result: 'ok' },
      {
        target: 'nacos', payloadHash: 'sha256:20cd91e7f4a3b862', appliedAt: '2026-07-27T20:10:00', result: 'failed',
        error: '写入 Nacos 失败：命名空间 prod 不存在。到 设置 → Nacos 连接 检查命名空间配置（本次为漂移修复重放，线上配置未变更）',
      },
    ],
    closureSummary: '闭合：5 个必填参数全部可满足',
    timeline: [
      { status: 'draft', at: '2026-07-09T09:00:00', by: '刘一凡' },
      { status: 'candidate', at: '2026-07-09T09:40:00', by: '刘一凡' },
      { status: 'tested', at: '2026-07-09T10:30:00', by: '系统' },
      { status: 'approved', at: '2026-07-10T13:00:00', by: '李珂' },
      { status: 'released', at: '2026-07-10T14:00:00', by: '刘一凡' },
    ],
  },
]
