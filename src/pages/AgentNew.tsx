import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchApiSources, fetchTools } from '@/mock/api'
import { agentIntents, hitMap, initialCandidateToolIds } from '@/mock/data'
import { Button } from '@/components/ui/Button'
import { StepBar, type Step } from '@/components/ui/StepBar'
import { ProgressBar } from '@/components/ui/ProgressBar'
import { ErrorState } from '@/components/ui/ErrorState'
import { useToast } from '@/components/ui/Toast'
import { IntentToolMatrix } from '@/components/matrix/IntentToolMatrix'
import { cn, shortHash } from '@/lib/utils'
import type { Intent } from '@/types'

const STEPS = ['身份', '选 API 来源', '匹配审核', '测试与发布']

type TestPhase =
  | { kind: 'idle' }
  | { kind: 'running'; layer: string; percent: number; stepName: string }
  | { kind: 'env-missing' } // L2 需要评测环境
  | { kind: 'mock-done' } // L0+L1 用内置 mock 通过
  | { kind: 'full-done' }

export function AgentNew() {
  const navigate = useNavigate()
  const toast = useToast()
  const [step, setStep] = useState(0)

  // 步骤 1 身份
  const [name, setName] = useState('售后客服专员')
  const [slug, setSlug] = useState('aftersales-2')
  const [dept, setDept] = useState('d-cs')
  const [desc, setDesc] = useState('')
  const [forbidden, setForbidden] = useState('')

  // 步骤 2 API 来源
  const sources = useQuery({ queryKey: ['sources'], queryFn: fetchApiSources })
  const toolsQ = useQuery({ queryKey: ['tools'], queryFn: fetchTools })
  const [selectedSources, setSelectedSources] = useState<string[]>(['s-orders', 's-crm', 's-ticket', 's-pay'])

  // 步骤 3 矩阵（mock：用 AI 拆解结果初始化）
  const [intents, setIntents] = useState<Intent[]>(agentIntents['a-aftersales'])
  const [selectedToolIds, setSelectedToolIds] = useState<string[]>(initialCandidateToolIds)
  const pool = useMemo(
    () => (toolsQ.data ?? []).filter((t) => selectedSources.includes(t.apiSourceId)),
    [toolsQ.data, selectedSources],
  )

  // 步骤 4 测试与发布
  const [manifestHash] = useState('sha256:f31c88ab02d9e647')
  const [testPhase, setTestPhase] = useState<TestPhase>({ kind: 'idle' })
  const [l2Waiver, setL2Waiver] = useState<{ by: string; reason: string } | null>(null)
  const [waiverReason, setWaiverReason] = useState('')
  const [publishState, setPublishState] = useState<'idle' | 'confirm' | 'nacos' | 'higress' | 'done'>('idle')
  const timers = useRef<number[]>([])

  function runTests(mockOnly: boolean) {
    timers.current.forEach(clearTimeout)
    timers.current = []
    const seq: { layer: string; stepName: string; at: number }[] = [
      { layer: 'L0', stepName: 'L0 · schema 校验 get_order_detail', at: 0 },
      { layer: 'L0', stepName: 'L0 · schema 校验 create_refund', at: 500 },
      { layer: 'L1', stepName: 'L1 · 模板渲染 create_refund（mock 上游）', at: 1100 },
      { layer: 'L1', stepName: 'L1 · 模板渲染 update_shipping_address（mock 上游）', at: 1700 },
    ]
    const total = mockOnly ? 2300 : 3000
    for (const s of seq) {
      timers.current.push(window.setTimeout(
        () => setTestPhase({ kind: 'running', layer: s.layer, percent: (s.at / total) * 100, stepName: s.stepName }),
        s.at,
      ))
    }
    if (mockOnly) {
      timers.current.push(window.setTimeout(() => setTestPhase({ kind: 'mock-done' }), total))
    } else {
      timers.current.push(window.setTimeout(
        () => setTestPhase({ kind: 'running', layer: 'L2', percent: 80, stepName: 'L2 · 连接评测环境…' }),
        2300,
      ))
      timers.current.push(window.setTimeout(() => setTestPhase({ kind: 'env-missing' }), total))
    }
  }

  function publish() {
    setPublishState('nacos')
    window.setTimeout(() => setPublishState('higress'), 900)
    window.setTimeout(() => {
      setPublishState('done')
      toast('已发布')
    }, 1800)
  }

  const testsPassed = testPhase.kind === 'mock-done' || testPhase.kind === 'full-done'

  // 门禁判定（步骤 4）：测试完成后计算
  const gates = testsPassed
    ? [
        { id: 'schema-valid', name: '所有 inputSchema 通过校验', verdict: 'pass' as const, detail: '' },
        { id: 'closure', name: '闭包检查通过', verdict: 'pass' as const, detail: '' },
        { id: 'budget', name: '工具数 ≤ 20 且 schema ≤ 15k token', verdict: 'pass' as const, detail: `${selectedToolIds.length} 个工具` },
        { id: 'idempotency', name: '所有 write 工具声明幂等键', verdict: 'pass' as const, detail: '' },
        {
          id: 'l2-eval', name: 'L2 评测完成',
          verdict: (testPhase.kind === 'full-done' ? 'pass' : l2Waiver ? 'waived' : 'block') as 'pass' | 'waived' | 'block',
          detail: testPhase.kind === 'mock-done' ? 'L2 未运行（测试环境缺失）' : '',
        },
      ]
    : []
  const gateBlocked = gates.some((g) => g.verdict === 'block')

  const stepBar: Step[] = STEPS.map((label, i) => ({
    key: label, label,
    state: i < step ? 'done' : i === step ? 'current' : 'todo',
  }))

  const canNext =
    step === 0 ? name.trim() !== '' && slug.trim() !== '' && desc.trim() !== '' :
    step === 1 ? selectedSources.length > 0 :
    true

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">新建数字员工</h1>
        <Button variant="ghost" onClick={() => navigate('/agents')}>取消</Button>
      </div>
      <div className="rounded border border-line bg-surface px-4 py-3">
        <StepBar steps={stepBar} />
      </div>

      {/* ---------------- 步骤 1 身份 ---------------- */}
      {step === 0 && (
        <div className="flex max-w-2xl flex-col gap-4 rounded border border-line bg-surface p-4">
          <Field label="名称">
            <input value={name} onChange={(e) => setName(e.target.value)} className="input" />
          </Field>
          <Field label="slug" hint="创建后不可变：MCP 服务名 mcp-{部门}-{slug} 以它生成，发布、对账、回滚都靠这个名字。">
            <input
              value={slug}
              onChange={(e) => setSlug(e.target.value.replace(/[^a-z0-9-]/g, ''))}
              className="input font-mono"
            />
          </Field>
          <Field label="所属部门">
            <select value={dept} onChange={(e) => setDept(e.target.value)} className="input">
              <option value="d-cs">客服部</option>
              <option value="d-fin">财务部</option>
              <option value="d-ops">运维部</option>
              <option value="d-mkt">市场部</option>
            </select>
          </Field>
          <Field label="职责描述" hint="支持直接粘贴 system prompt，朱雀会从中拆出可审核的意图。">
            <textarea
              value={desc}
              onChange={(e) => setDesc(e.target.value)}
              rows={7}
              className="input resize-y leading-relaxed"
              placeholder="例：你是售后客服专员。用户报手机号后，帮他找到订单，处理退款、取消、改地址；投诉要开工单跟进…"
            />
          </Field>
          <Field label="明确禁止的事" hint="独立于职责描述：它在匹配时是负向约束，也会进入 L2 拒答评测。">
            <textarea
              value={forbidden}
              onChange={(e) => setForbidden(e.target.value)}
              rows={3}
              className="input resize-y"
              placeholder="例：不得主动向客户提供折扣承诺；不得批量操作订单。"
            />
          </Field>
        </div>
      )}

      {/* ---------------- 步骤 2 选 API 来源 ---------------- */}
      {step === 1 && (
        <div className="flex max-w-2xl flex-col gap-3">
          {(sources.data ?? []).map((s) => {
            const checked = selectedSources.includes(s.id)
            return (
              <label
                key={s.id}
                className={cn(
                  'flex cursor-pointer items-start gap-3 rounded border bg-surface p-4',
                  checked ? 'border-ink/50' : 'border-line',
                )}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  className="mt-1"
                  onChange={(e) =>
                    setSelectedSources((xs) => (e.target.checked ? [...xs, s.id] : xs.filter((x) => x !== s.id)))
                  }
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline gap-2">
                    <span className="font-medium">{s.name}</span>
                    <code className="font-mono text-[11px] text-ink-faint">{s.specUrl}</code>
                  </div>
                  <p className="mt-1 text-xs text-ink-muted">
                    {s.toolTotal} 个工具 · 富化 {s.toolTotal - s.rawCount}/{s.toolTotal}
                  </p>
                  {s.rawCount > 10 && (
                    <p className="mt-1.5 text-xs font-medium text-warn">
                      该来源有 {s.rawCount} 个工具未富化，匹配质量会下降。先到 工具池 批量富化，或继续但人工复核矩阵。
                    </p>
                  )}
                </div>
              </label>
            )
          })}
        </div>
      )}

      {/* ---------------- 步骤 3 匹配审核（签名界面） ---------------- */}
      {step === 2 && (
        <IntentToolMatrix
          intents={intents}
          setIntents={setIntents}
          selectedIds={selectedToolIds}
          setSelectedIds={setSelectedToolIds}
          pool={pool}
          hits={hitMap}
          onRematch={() => {
            setSelectedToolIds(initialCandidateToolIds)
            toast('已按当前意图重新匹配（mock）')
          }}
          onFreeze={() => {
            setStep(3)
            toast(`已冻结 Release 草稿，manifest_hash ${shortHash(manifestHash)}`)
          }}
        />
      )}

      {/* ---------------- 步骤 4 测试与发布 ---------------- */}
      {step === 3 && (
        <div className="flex max-w-2xl flex-col gap-4">
          <div className="rounded border border-line bg-surface p-4">
            <div className="flex items-center gap-3 text-sm">
              <span className="font-medium">Release 草稿</span>
              <code className="font-mono text-xs">v1</code>
              <code className="font-mono text-xs text-ink-muted">manifest_hash {shortHash(manifestHash)}</code>
              <span className="ml-auto text-xs text-ink-faint">冻结后 manifest 不可改；要改就回到上一步开新草稿</span>
            </div>
          </div>

          {/* 测试 */}
          <section className="rounded border border-line bg-surface p-4">
            <h2 className="text-sm font-semibold">测试</h2>
            {testPhase.kind === 'idle' && (
              <div className="mt-3">
                <Button variant="primary" onClick={() => runTests(false)}>运行测试（L0 + L1 + L2）</Button>
              </div>
            )}
            {testPhase.kind === 'running' && (
              <div className="mt-3">
                <ProgressBar percent={testPhase.percent} stepName={testPhase.stepName} />
              </div>
            )}
            {testPhase.kind === 'env-missing' && (
              <div className="mt-3 flex flex-col gap-3">
                <p className="text-sm">
                  <span className="font-medium text-pass">L0 通过（5 例）· L1 通过（3 例）</span>
                </p>
                <ErrorState
                  compact
                  what="L2 评测未运行：测试环境缺失"
                  fix="L2 需要评测模型与 staging 上游，当前都未配置。可以先用内置 mock 完成 L0+L1，L2 走门禁豁免；或到 设置 配置测试环境后重跑。"
                  action={
                    <div className="flex gap-2">
                      <Button size="sm" variant="primary" onClick={() => setTestPhase({ kind: 'mock-done' })}>
                        用内置 mock 跑 L0+L1
                      </Button>
                      <Button size="sm" onClick={() => navigate('/settings')}>去配置测试环境</Button>
                    </div>
                  }
                />
              </div>
            )}
            {testsPassed && (
              <div className="mt-3 text-sm">
                <p className="font-medium text-pass">L0 通过（5 例）· L1 通过（3 例，内置 mock 上游）</p>
                {testPhase.kind === 'mock-done' && (
                  <p className="mt-1 text-xs text-ink-muted">L2 未运行。发布前需在门禁中豁免，豁免记录会进入证据包。</p>
                )}
              </div>
            )}
          </section>

          {/* 门禁 */}
          {testsPassed && (
            <section className="rounded border border-line bg-surface p-4">
              <h2 className="text-sm font-semibold">门禁判定</h2>
              <ul className="mt-3 flex flex-col gap-2">
                {gates.map((g) => (
                  <li key={g.id} className="flex items-start gap-2 text-sm">
                    <span
                      className={cn(
                        'mt-0.5 font-mono text-xs font-medium',
                        g.verdict === 'pass' && 'text-pass',
                        g.verdict === 'block' && 'text-block',
                        g.verdict === 'waived' && 'text-warn',
                      )}
                    >
                      {g.verdict === 'pass' ? '通过' : g.verdict === 'block' ? '阻断' : '豁免'}
                    </span>
                    <div>
                      <span>{g.name}</span>
                      {g.detail && <span className="ml-2 text-xs text-ink-muted">{g.detail}</span>}
                      {g.id === 'l2-eval' && g.verdict === 'block' && (
                        <div className="mt-2 flex items-center gap-2">
                          <input
                            value={waiverReason}
                            onChange={(e) => setWaiverReason(e.target.value)}
                            placeholder="豁免理由（将写入审计）"
                            className="input h-7 w-72 text-xs"
                          />
                          <Button
                            size="sm"
                            disabled={waiverReason.trim().length < 4}
                            onClick={() => setL2Waiver({ by: '当前用户', reason: waiverReason.trim() })}
                          >
                            人工豁免
                          </Button>
                        </div>
                      )}
                      {g.id === 'l2-eval' && g.verdict === 'waived' && l2Waiver && (
                        <p className="mt-1 rounded bg-[var(--warn-tint)] px-2 py-1 text-xs">
                          豁免人：{l2Waiver.by} · 理由：{l2Waiver.reason}
                        </p>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* 发布：必须人点。双 target 事务，任一失败整体回滚。 */}
          {testsPassed && (
            <section className="rounded border border-line bg-surface p-4">
              <h2 className="text-sm font-semibold">发布</h2>
              {publishState === 'idle' && (
                <div className="mt-3 flex items-center gap-3">
                  <Button variant="primary" disabled={gateBlocked} onClick={() => setPublishState('confirm')}>
                    发布
                  </Button>
                  {gateBlocked && <span className="text-xs text-ink-muted">门禁未全部通过：处理上方「阻断」项后才能发布</span>}
                </div>
              )}
              {publishState === 'confirm' && (
                <div className="mt-3 rounded border border-line bg-canvas p-3">
                  <p className="text-sm">
                    即将发布 <code className="font-mono text-xs">mcp-cs-{slug}</code>，
                    manifest_hash <code className="font-mono text-xs">{shortHash(manifestHash)}</code>。
                    双 target 事务：写入 Nacos + 配置 Higress 鉴权，任一失败整体回滚。
                  </p>
                  <div className="mt-3 flex gap-2">
                    <Button variant="primary" onClick={publish}>确认发布</Button>
                    <Button variant="ghost" onClick={() => setPublishState('idle')}>再想想</Button>
                  </div>
                </div>
              )}
              {(publishState === 'nacos' || publishState === 'higress') && (
                <div className="mt-3">
                  <ProgressBar
                    percent={publishState === 'nacos' ? 40 : 80}
                    stepName={publishState === 'nacos' ? '写入 Nacos（mcp-server / mcp-cs-' + slug + '.json）' : '配置 Higress 入口鉴权（consumer group cg-cs）'}
                  />
                </div>
              )}
              {publishState === 'done' && (
                <div className="mt-3 text-sm">
                  <p className="font-medium text-pass">已发布</p>
                  <p className="mt-1.5 text-ink-muted">
                    MCP URL：<code className="select-all font-mono text-xs">https://gw.corp.example.com/mcp-cs-{slug}</code>
                  </p>
                  <p className="mt-2">
                    <Link to="/agents" className="text-sm underline underline-offset-2">返回数字员工列表</Link>
                  </p>
                </div>
              )}
            </section>
          )}
        </div>
      )}

      {/* 底部导航（步骤 3 的前进按钮在矩阵操作条里：确认并冻结 Release） */}
      {step < 3 && (
        <div className="flex items-center gap-2">
          {step > 0 && <Button onClick={() => setStep(step - 1)}>上一步</Button>}
          {step < 2 && (
            <Button variant="primary" disabled={!canNext} onClick={() => setStep(step + 1)}>
              下一步
            </Button>
          )}
          {step === 2 && <span className="text-xs text-ink-muted">审核完成后，用矩阵下方的「确认并冻结 Release」进入测试与发布</span>}
        </div>
      )}
    </div>
  )
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-ink-muted">{label}</span>
      {children}
      {hint && <span className="text-xs text-ink-faint">{hint}</span>}
    </label>
  )
}
