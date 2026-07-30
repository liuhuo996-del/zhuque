import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { ProgressBar } from '@/components/ui/ProgressBar'
import { useToast } from '@/components/ui/Toast'
import { cn } from '@/lib/utils'

interface CheckItem {
  name: string
  state: 'pending' | 'running' | 'ok' | 'fail'
  detail?: string
  fix?: string
}

const INITIAL_CHECKS: CheckItem[] = [
  { name: 'Nacos 版本 ≥ 3.0.1（Admin API）', state: 'pending' },
  { name: 'Higress 版本（支持同步 Nacos 原生 MCP Server）', state: 'pending' },
  { name: 'Redis 可达（Higress MCP 功能依赖）', state: 'pending' },
  { name: 'Higress MCP 功能已 enable', state: 'pending' },
]

export function Settings() {
  const toast = useToast()
  const [checks, setChecks] = useState<CheckItem[]>(INITIAL_CHECKS)
  const [checking, setChecking] = useState(false)
  const [rules, setRules] = useState([
    { id: 'schema-valid', name: '所有 inputSchema 通过校验', on: true, threshold: null as string | null },
    { id: 'closure', name: '闭包检查通过', on: true, threshold: null },
    { id: 'budget-tools', name: '工具数上限', on: true, threshold: '20' },
    { id: 'budget-tokens', name: 'schema token 上限', on: true, threshold: '15000' },
    { id: 'idempotency', name: '所有 write 工具声明幂等键', on: true, threshold: null },
    { id: 'sensitive-review', name: '命中敏感字段的工具需 reviewed', on: true, threshold: null },
    { id: 'delete-forbidden', name: 'delete 工具默认禁止（可豁免）', on: true, threshold: null },
  ])

  function runChecks() {
    setChecking(true)
    setChecks(INITIAL_CHECKS.map((c) => ({ ...c, state: 'pending' })))
    const results: CheckItem[] = [
      { name: INITIAL_CHECKS[0].name, state: 'ok', detail: '3.0.2 · Admin API 可用' },
      { name: INITIAL_CHECKS[1].name, state: 'ok', detail: '2.1.4' },
      { name: INITIAL_CHECKS[2].name, state: 'ok', detail: 'redis://higress-redis:6379 · 3ms' },
      {
        name: INITIAL_CHECKS[3].name, state: 'fail',
        detail: 'GET /api/mcp/status 返回 501',
        fix: 'Higress 未启用 MCP Server 能力。在 higress-config 中设置 mcpServer.enable: true 并重启网关，然后重新检查。',
      },
    ]
    results.forEach((r, i) => {
      window.setTimeout(() => {
        setChecks((xs) => xs.map((x, idx) => (idx === i ? r : idx === i + 1 ? { ...x, state: 'running' } : x)))
        if (i === results.length - 1) setChecking(false)
      }, (i + 1) * 600)
    })
    setChecks((xs) => xs.map((x, idx) => (idx === 0 ? { ...x, state: 'running' } : x)))
  }

  const doneCount = checks.filter((c) => c.state === 'ok' || c.state === 'fail').length

  return (
    <div className="flex max-w-2xl flex-col gap-5">
      <h1 className="text-lg font-semibold">设置</h1>

      {/* Nacos 连接 */}
      <section className="rounded border border-line bg-surface p-4">
        <h2 className="text-sm font-semibold">Nacos 连接</h2>
        <p className="mt-1 text-xs text-ink-muted">走 Admin API（client OpenAPI 发布不了配置）。密钥交由 Nacos 加密托管，朱雀只存引用。</p>
        <div className="mt-3 grid grid-cols-2 gap-3">
          <Field label="服务地址"><input className="input font-mono text-xs" defaultValue="http://nacos.internal:8848" /></Field>
          <Field label="命名空间"><input className="input font-mono text-xs" defaultValue="prod" /></Field>
          <Field label="用户名"><input className="input font-mono text-xs" defaultValue="zhuque-cp" /></Field>
          <Field label="密码"><input type="password" className="input font-mono text-xs" defaultValue="········" /></Field>
        </div>
        <Button size="sm" className="mt-3" onClick={() => toast('Nacos 连接正常：3.0.2（mock）')}>测试连接</Button>
      </section>

      {/* Higress 连接 */}
      <section className="rounded border border-line bg-surface p-4">
        <h2 className="text-sm font-semibold">Higress 连接</h2>
        <div className="mt-3 grid grid-cols-2 gap-3">
          <Field label="Console 地址"><input className="input font-mono text-xs" defaultValue="http://higress.internal:8001" /></Field>
          <Field label="网关入口"><input className="input font-mono text-xs" defaultValue="https://gw.corp.example.com" /></Field>
          <Field label="consumer group 前缀"><input className="input font-mono text-xs" defaultValue="cg-" /></Field>
        </div>
        <Button size="sm" className="mt-3" onClick={() => toast('Higress 连接正常：2.1.4（mock）')}>测试连接</Button>
      </section>

      {/* 门禁规则 */}
      <section className="rounded border border-line bg-surface p-4">
        <h2 className="text-sm font-semibold">门禁规则</h2>
        <p className="mt-1 text-xs text-ink-muted">硬规则决定 Release 能否发布。关闭或改阈值即刻生效于其后创建的 Release，已冻结的不受影响。</p>
        <ul className="mt-3 flex flex-col gap-2">
          {rules.map((r) => (
            <li key={r.id} className="flex h-9 items-center gap-3 rounded border border-line px-3">
              <input
                type="checkbox"
                checked={r.on}
                onChange={(e) => setRules((xs) => xs.map((x) => (x.id === r.id ? { ...x, on: e.target.checked } : x)))}
              />
              <span className={cn('text-sm', !r.on && 'text-ink-faint line-through')}>{r.name}</span>
              <code className="font-mono text-[10px] text-ink-faint">{r.id}</code>
              {r.threshold !== null && (
                <input
                  value={r.threshold}
                  onChange={(e) =>
                    setRules((xs) => xs.map((x) => (x.id === r.id ? { ...x, threshold: e.target.value.replace(/\D/g, '') } : x)))
                  }
                  className="input ml-auto h-6 w-20 text-right font-mono text-xs"
                />
              )}
            </li>
          ))}
        </ul>
      </section>

      {/* 环境前置检查 */}
      <section className="rounded border border-line bg-surface p-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold">环境前置检查</h2>
          <Button size="sm" variant="primary" disabled={checking} onClick={runChecks}>立即检查</Button>
        </div>
        {checking && (
          <div className="mt-3">
            <ProgressBar percent={(doneCount / checks.length) * 100} stepName={checks.find((c) => c.state === 'running')?.name ?? '准备检查…'} />
          </div>
        )}
        <ul className="mt-3 flex flex-col gap-2">
          {checks.map((c) => (
            <li key={c.name} className="rounded border border-line px-3 py-2">
              <div className="flex items-center gap-2 text-sm">
                <span className={cn(
                  'font-mono text-xs font-medium',
                  c.state === 'ok' && 'text-pass',
                  c.state === 'fail' && 'text-block',
                  (c.state === 'pending' || c.state === 'running') && 'text-ink-faint',
                )}>
                  {c.state === 'ok' ? '通过' : c.state === 'fail' ? '失败' : c.state === 'running' ? '检查中' : '待检查'}
                </span>
                <span>{c.name}</span>
                {c.detail && <code className="ml-auto font-mono text-[11px] text-ink-muted">{c.detail}</code>}
              </div>
              {c.fix && <p className="mt-1.5 text-xs text-block">{c.fix}</p>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-ink-muted">{label}</span>
      {children}
    </label>
  )
}
