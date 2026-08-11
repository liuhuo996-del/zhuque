import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  approveRelease, fetchAgents, fetchRelease, fetchReleases, publishRelease, rollbackRelease, type ApiError,
} from '@/lib/api'
import { StepBar, type Step } from '@/components/ui/StepBar'
import { JsonBlock } from '@/components/ui/JsonBlock'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/ErrorState'
import { useToast } from '@/components/ui/Toast'
import { cn, copyText, formatDate, shortHash } from '@/lib/utils'
import type { Release, ReleaseStatus, TestCase } from '@/types'

const FLOW: ReleaseStatus[] = ['draft', 'candidate', 'tested', 'approved', 'released']
const statusLabel: Record<ReleaseStatus, string> = {
  draft: '草稿', candidate: '候选', tested: '已测试', approved: '已审批',
  released: '已发布', superseded: '已被取代', rolled_back: '已回滚',
}

export function ReleaseDetail() {
  const { id = '' } = useParams()
  const toast = useToast()
  const queryClient = useQueryClient()
  const release = useQuery({ queryKey: ['release', id], queryFn: () => fetchRelease(id) })
  const all = useQuery({ queryKey: ['releases', 'all'], queryFn: () => fetchReleases('all') })
  const agents = useQuery({ queryKey: ['agents', 'all'], queryFn: () => fetchAgents('all') })
  const [confirmAction, setConfirmAction] = useState<'approve' | 'publish' | 'rollback' | null>(null)
  const [publishedKey, setPublishedKey] = useState<{ key: string; mcpUrl: string } | null>(null)
  const action = useMutation({
    mutationFn: async (kind: 'approve' | 'publish' | 'rollback') => {
      if (kind === 'approve') {
        await approveRelease(id, release.data?.manifestHash ?? '')
        return null
      }
      if (kind === 'publish') return publishRelease(id)
      await rollbackRelease(id)
      return null
    },
    onSuccess: async (result, kind) => {
      if (kind === 'publish' && result?.plaintextKeyOnceOnly) {
        setPublishedKey({ key: result.plaintextKeyOnceOnly, mcpUrl: result.mcpUrl })
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['release', id] }),
        queryClient.invalidateQueries({ queryKey: ['releases'] }),
        queryClient.invalidateQueries({ queryKey: ['agents'] }),
        queryClient.invalidateQueries({ queryKey: ['audit-events'] }),
      ])
      setConfirmAction(null)
      toast(kind === 'approve' ? 'Release 已批准' : kind === 'publish' ? 'Release 已发布' : '已提交回滚')
    },
  })

  const r = release.data
  // 上一版：同 agent、创建时间早于本版的最近一版（diff 开关用）
  const prev = useMemo(() => {
    if (!r || !all.data) return undefined
    return all.data
      .filter((x) => x.agentId === r.agentId && x.createdAt < r.createdAt)
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0]
  }, [r, all.data])

  if (release.isLoading || !r) return <SkeletonTable rows={6} />

  const agent = agents.data?.find((a) => a.id === r.agentId)
  const currentlyReleased = all.data?.find((x) => x.agentId === r.agentId && x.status === 'released')

  const steps = buildSteps(r)

  return (
    <div className="page-shell">
      {/* 顶部：状态机 + 标识 */}
      <div className="panel p-4 md:p-5">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="page-title">{agent?.name ?? r.agentId}</h1>
          <code className="font-mono text-sm font-medium">{r.version}</code>
          <button
            className="group flex items-center gap-1 font-mono text-xs text-ink-muted hover:text-ink"
            onClick={async () => { await copyText(r.manifestHash); toast('已复制 manifest_hash') }}
            title={r.manifestHash}
          >
            manifest_hash {shortHash(r.manifestHash)}
            <span className="opacity-0 group-hover:opacity-100">⧉</span>
          </button>
          <span className="ml-auto text-xs text-ink-faint">创建于 {formatDate(r.createdAt)}</span>
        </div>
        <div className="mt-4 overflow-x-auto">
          <StepBar steps={steps} />
        </div>
        {/* 操作：审批 / 发布 / 回滚（弹窗二次确认，明示 manifest_hash） */}
        <div className="mt-4 flex items-center gap-2 border-t border-line pt-3">
          {r.status === 'tested' && (
            <Button variant="primary" onClick={() => setConfirmAction('approve')}>批准</Button>
          )}
          {r.status === 'approved' && (
            <Button variant="primary" onClick={() => setConfirmAction('publish')}>发布</Button>
          )}
          {(r.status === 'superseded' || r.status === 'rolled_back') && currentlyReleased && (
            <Button onClick={() => setConfirmAction('rollback')}>回滚到此版本</Button>
          )}
          {r.status === 'candidate' && r.gates.some((g) => g.verdict === 'block') && (
            <span className="text-xs text-ink-muted">门禁存在阻断项，处理后才能进入测试与审批</span>
          )}
          {r.status === 'released' && <span className="text-xs text-pass">当前线上版本</span>}
        </div>
        {action.error && <div className="mt-3"><ErrorState compact what={(action.error as ApiError).what} fix={(action.error as ApiError).fix} /></div>}
        {confirmAction && (
          <div className="mt-3 rounded border border-line bg-canvas p-3 text-sm">
            {confirmAction === 'approve' && (
              <p>
                正在批准的是 manifest_hash <code className="font-mono text-xs font-medium">{r.manifestHash}</code>。
                审批签的是内容哈希：manifest 一变，本次审批自动失效。
              </p>
            )}
            {confirmAction === 'publish' && (
              <p>
                即将发布 <code className="font-mono text-xs">{r.version}</code>（manifest_hash{' '}
                <code className="font-mono text-xs">{shortHash(r.manifestHash)}</code>）。
                GateForge 将全量 MCP 快照写入 Nacos；Higress 由已配置的 Nacos3 source 自动发现。
              </p>
            )}
            {confirmAction === 'rollback' && (
              <p>
                回滚 = 将 <code className="font-mono text-xs">{r.version}</code> 的全量快照原样重放到
                Nacos MCP Registry，替换当前线上版本 <code className="font-mono text-xs">{currentlyReleased?.version}</code>。
              </p>
            )}
            <div className="mt-3 flex gap-2">
              <Button
                variant="primary" size="sm"
                disabled={action.isPending}
                onClick={() => action.mutate(confirmAction)}
              >
                {action.isPending ? '提交中…' : '确认'}
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setConfirmAction(null)}>取消</Button>
            </div>
          </div>
        )}
        {publishedKey && (
          <section className="mt-3 rounded border border-warn/40 bg-[var(--warn-tint)] p-3">
            <p className="text-xs font-medium">初始访问密钥（仅本次显示）</p>
            <p className="mt-1 text-xs text-ink-muted">将它交给调用方安全保存；GateForge 不会再次返回该明文。</p>
            <div className="mt-2 flex items-center gap-2">
              <code className="min-w-0 flex-1 break-all font-mono text-xs select-all">{publishedKey.key}</code>
              <Button size="sm" onClick={async () => { await copyText(publishedKey.key); toast('已复制初始访问密钥') }}>复制</Button>
              <Button size="sm" variant="ghost" onClick={() => setPublishedKey(null)}>关闭</Button>
            </div>
            <p className="mt-2 font-mono text-[11px] text-ink-muted">MCP URL：{publishedKey.mcpUrl}</p>
          </section>
        )}
      </div>

      {/* 主体：配置(左) × 证据(右) 并置。这个并置是产品主张，不许折叠进二级页。 */}
      <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-2">
        {/* 左栏 · 配置产物 */}
        <div className="flex flex-col gap-3">
          <h2 className="text-sm font-semibold">配置产物 <span className="ml-1 text-xs font-normal text-ink-muted">参与部署</span></h2>
          <JsonBlock title="nacos_payload" data={r.nacosPayload} diffWith={prev?.nacosPayload} />
          {hasLegacyPayload(r.higressAuthPayload) && (
            <JsonBlock title="higress_auth_payload（历史兼容，不参与发布）" data={r.higressAuthPayload}
              diffWith={prev?.higressAuthPayload} defaultOpen={false} />
          )}

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">target_constraints</h3>
            <ul className="mt-2 flex flex-col gap-1.5">
              {r.targetConstraints.map((c) => (
                <li key={c.name} className="flex items-center gap-2 text-sm">
                  <span className={cn('font-mono text-xs font-medium', c.ok ? 'text-pass' : 'text-block')}>
                    {c.ok ? '满足' : '不满足'}
                  </span>
                  <span>{c.name}</span>
                  <code className="ml-auto font-mono text-xs text-ink-muted">{c.required} · 当前 {c.current}</code>
                </li>
              ))}
            </ul>
          </section>

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">部署记录</h3>
            {r.deploys.length === 0 ? (
              <p className="mt-2 text-sm text-ink-muted">尚未部署。</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-2">
                {r.deploys.map((d, i) => (
                  <li key={i} className="text-sm">
                    <div className="flex items-center gap-2">
                      <code className="rounded border border-line px-1 font-mono text-[10px] text-ink-muted">{d.target}</code>
                      <span className={cn('font-mono text-xs font-medium', d.result === 'ok' ? 'text-pass' : 'text-block')}>
                        {d.result === 'ok' ? '成功' : '失败'}
                      </span>
                      <code className="font-mono text-[11px] text-ink-faint">{shortHash(d.payloadHash)}</code>
                      <span className="ml-auto text-xs text-ink-faint">{formatDate(d.appliedAt)}</span>
                    </div>
                    {d.error && <p className="mt-1 text-xs text-block">{d.error}</p>}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>

        {/* 右栏 · 证据包 */}
        <div className="flex flex-col gap-3">
          <h2 className="text-sm font-semibold">证据包 <span className="ml-1 text-xs font-normal text-ink-muted">不参与部署，但决定能否部署</span></h2>

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">门禁判定</h3>
            <ul className="mt-2 flex flex-col gap-2">
              {r.gates.map((g) => (
                <li key={g.ruleId} className="text-sm">
                  <div className="flex items-start gap-2">
                    <span className={cn(
                      'mt-0.5 shrink-0 font-mono text-xs font-medium',
                      g.verdict === 'pass' && 'text-pass',
                      g.verdict === 'block' && 'text-block',
                      g.verdict === 'waived' && 'text-warn',
                    )}>
                      {g.verdict === 'pass' ? '通过' : g.verdict === 'block' ? '阻断' : '豁免'}
                    </span>
                    <div className="min-w-0">
                      <span>{g.ruleName}</span>
                      <code className="ml-2 font-mono text-[10px] text-ink-faint">{g.ruleId}</code>
                      {g.detail && <p className="mt-0.5 text-xs text-ink-muted">{g.detail}</p>}
                      {/* 被豁免的规则显著标出人和理由——审计最常追问的地方 */}
                      {g.verdict === 'waived' && (
                        <p className="mt-1 rounded bg-[var(--warn-tint)] px-2 py-1 text-xs">
                          <span className="font-medium">豁免人：{g.waivedBy}</span> · 理由:{g.waiverReason}
                        </p>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          </section>

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">测试报告</h3>
            {(['L0', 'L1', 'L2'] as const).map((layer) => {
              const cases = r.tests.filter((t) => t.layer === layer)
              return (
                <details key={layer} className="mt-2 rounded border border-line" open={cases.some((c) => c.result === 'fail')}>
                  <summary className="flex h-8 cursor-pointer select-none items-center gap-2 px-2.5 text-xs">
                    <code className="font-mono font-medium">{layer}</code>
                    {cases.length === 0 ? (
                      <span className="text-ink-faint">未运行</span>
                    ) : (
                      <LayerSummary cases={cases} />
                    )}
                  </summary>
                  {layer === 'L2' && cases.length > 0 && r.modelMeta && (
                    <p className="border-t border-line bg-canvas px-2.5 py-1.5 font-mono text-[11px] text-ink-muted">
                      评测模型 {r.modelMeta.model} · 版本 {r.modelMeta.version} · temperature {r.modelMeta.temperature} · prompt 模板 {r.modelMeta.promptTemplate}
                    </p>
                  )}
                  {cases.map((c) => (
                    <div key={c.caseId} className="border-t border-line px-2.5 py-1.5">
                      <div className="flex items-center gap-2">
                        <span className={cn(
                          'font-mono text-[11px] font-medium',
                          c.result === 'pass' ? 'text-pass' : c.result === 'fail' ? 'text-block' : 'text-ink-faint',
                        )}>
                          {c.result}
                        </span>
                        <code className="font-mono text-[11px]">{c.caseId}</code>
                      </div>
                      <p className="mt-0.5 text-xs text-ink-muted">{c.detail}</p>
                    </div>
                  ))}
                </details>
              )
            })}
          </section>

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">闭包检查</h3>
            <p className="mt-2 text-sm text-pass">{r.closureSummary}</p>
          </section>

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">审批链</h3>
            {r.approvals.length === 0 ? (
              <p className="mt-2 text-sm text-ink-muted">尚无审批。审批签的是 manifest_hash，内容一变即失效。</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-1.5">
                {r.approvals.map((a, i) => (
                  <li key={i} className="text-sm">
                    <span className="font-medium">{a.approver}</span>
                    <span className="ml-2 text-xs text-ink-muted">{formatDate(a.decidedAt)} {a.decision === 'approved' ? '批准' : '驳回'}</span>
                    <code className="ml-2 font-mono text-[11px] text-ink-faint">{shortHash(a.manifestHash)}</code>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="rounded border border-line bg-surface p-4">
            <h3 className="text-xs font-semibold text-ink-muted">来源 spec hash</h3>
            <ul className="mt-2 flex flex-col gap-1">
              {r.sourceSpecHashes.map((s) => (
                <li key={s.source} className="flex items-center gap-2 text-sm">
                  <span>{s.source}</span>
                  <code className="ml-auto font-mono text-[11px] text-ink-muted">{s.hash}</code>
                </li>
              ))}
            </ul>
          </section>
        </div>
      </div>
    </div>
  )
}

function hasLegacyPayload(value: unknown) {
  return !!value && typeof value === 'object' && Object.keys(value as Record<string, unknown>).length > 0
}

function LayerSummary({ cases }: { cases: TestCase[] }) {
  const pass = cases.filter((c) => c.result === 'pass').length
  const fail = cases.filter((c) => c.result === 'fail').length
  return (
    <span>
      <span className="text-pass">{pass} 通过</span>
      {fail > 0 && <span className="ml-1.5 text-block">{fail} 失败</span>}
    </span>
  )
}

function buildSteps(r: Release): Step[] {
  const at = (s: ReleaseStatus) => r.timeline.find((t) => t.status === s)
  const rolled = r.status === 'rolled_back'
  const idxOf = (s: ReleaseStatus) => FLOW.indexOf(s)
  const reachedIdx = Math.max(...r.timeline.filter((t) => FLOW.includes(t.status)).map((t) => idxOf(t.status)), 0)

  const steps: Step[] = FLOW.map((s, i) => {
    const t = at(s)
    return {
      key: s,
      label: statusLabel[s],
      sub: t?.at ? `${formatDate(t.at).slice(5)} ${t.by ?? ''}` : undefined,
      state: t ? (i === reachedIdx && r.status === s ? 'current' : 'done') : 'todo',
    }
  })
  // 已回滚：末端追加 rolled_back 分支（block 色）
  if (rolled) {
    const t = at('rolled_back')
    steps.push({
      key: 'rolled_back', label: statusLabel.rolled_back,
      sub: t?.at ? `${formatDate(t.at).slice(5)} ${t.by ?? ''}` : undefined,
      state: 'blocked',
    })
  }
  if (r.status === 'superseded') {
    const t = r.timeline.find((x) => x.status === 'superseded')
    steps.push({
      key: 'superseded', label: statusLabel.superseded,
      sub: t?.at ? formatDate(t.at).slice(5) : undefined,
      state: 'done',
    })
  }
  return steps
}
