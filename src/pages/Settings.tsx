import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/ErrorState'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { fetchPrecheck, type ApiError, type PrecheckItem } from '@/lib/api'
import { cn } from '@/lib/utils'

const BUILTIN_GATES = [
  { id: 'l0-completeness', name: 'inputSchema 静态完备性', detail: 'L0 静态检查必须通过' },
  { id: 'l1-contract', name: '正式上游契约测试', detail: '针对 test / staging 上游运行 L1' },
  { id: 'closure', name: '闭包检查', detail: '每个工具必填参数必须可得' },
  { id: 'idempotency', name: '写操作幂等性', detail: '写工具必须声明幂等键' },
  { id: 'sensitive-masking', name: '敏感字段处理', detail: '命中敏感字段的工具须完成人工复核' },
]

type CheckView = {
  name: string
  state: 'pending' | 'running' | 'ok' | 'fail'
  current: string
  fix?: string
}

export function Settings() {
  const precheck = useQuery({
    queryKey: ['deploy-precheck'],
    queryFn: fetchPrecheck,
    enabled: false,
    retry: false,
  })

  const checks = useMemo<CheckView[]>(() => {
    if (precheck.data) return precheck.data.map(checkView)
    return [
      { name: 'Nacos Admin API', state: precheck.isFetching ? 'running' : 'pending', current: '' },
    ]
  }, [precheck.data, precheck.isFetching])

  return (
    <div className="page-shell max-w-5xl">
      <div className="border-b border-line pb-5">
        <h1 className="page-title">部署与门禁</h1>
        <p className="page-description">连接凭据由部署环境安全注入，控制台只执行真实探测并展示结果。</p>
      </div>

      <section className="rounded border border-line bg-surface p-4">
        <h2 className="text-sm font-semibold">部署目标</h2>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <Target name="Nacos" detail="GateForge 通过 AI MCP Admin API 发布和回滚完整 MCP 快照。" />
          <Target name="Higress" detail="平台侧手工配置 Nacos3 source；自动发现路由，鉴权与流量策略独立维护。" />
        </div>
        <p className="mt-3 rounded border border-line bg-canvas p-3 text-xs leading-5 text-ink-muted">
          服务地址、命名空间和凭据必须通过后端部署配置设置。为防止未保存的表单值被误认为已生效，这些参数不在控制台中提供本地编辑。
        </p>
      </section>

      <section className="rounded border border-line bg-surface p-4">
        <h2 className="text-sm font-semibold">内置发布门禁</h2>
        <p className="mt-1 text-xs text-ink-muted">以下规则在每个 Release 的证据包中实际执行；规则变更需要走受控部署，不在浏览器中做未持久化的开关。</p>
        <ul className="mt-3 flex flex-col gap-2">
          {BUILTIN_GATES.map((rule) => (
            <li key={rule.id} className="flex items-center gap-3 rounded border border-line px-3 py-2.5">
              <span className="font-mono text-xs text-pass">启用</span>
              <div className="min-w-0">
                <span className="text-sm">{rule.name}</span>
                <p className="text-xs text-ink-muted">{rule.detail}</p>
              </div>
              <code className="ml-auto shrink-0 font-mono text-[10px] text-ink-faint">{rule.id}</code>
            </li>
          ))}
        </ul>
      </section>

      <section className="rounded border border-line bg-surface p-4">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold">正式环境前置检查</h2>
            <p className="mt-1 text-xs text-ink-muted">只探测 Nacos AI MCP Admin API；Higress 运行面不阻塞 GateForge 发布。</p>
          </div>
          <Button size="sm" variant="primary" disabled={precheck.isFetching} onClick={() => precheck.refetch()}>
            {precheck.isFetching ? '检查中…' : precheck.data ? '重新检查' : '执行检查'}
          </Button>
        </div>
        {precheck.isError && <div className="mt-3"><ErrorState compact what={(precheck.error as ApiError).what} fix={(precheck.error as ApiError).fix} /></div>}
        {precheck.isLoading && !precheck.data ? <div className="mt-3"><SkeletonTable rows={4} cols={2} /></div> : (
          <ul className="mt-3 flex flex-col gap-2">
            {checks.map((check) => (
              <li key={check.name} className="rounded border border-line px-3 py-2">
                <div className="flex items-center gap-2 text-sm">
                  <span className={cn(
                    'font-mono text-xs font-medium',
                    check.state === 'ok' && 'text-pass',
                    check.state === 'fail' && 'text-block',
                    (check.state === 'pending' || check.state === 'running') && 'text-ink-faint',
                  )}>
                    {check.state === 'ok' ? '通过' : check.state === 'fail' ? '失败' : check.state === 'running' ? '检查中' : '待检查'}
                  </span>
                  <span>{check.name}</span>
                  {check.current && <code className="ml-auto font-mono text-[11px] text-ink-muted">{check.current}</code>}
                </div>
                {check.fix && check.state === 'fail' && <p className="mt-1.5 text-xs text-block">{check.fix}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function Target({ name, detail }: { name: string; detail: string }) {
  return (
    <div className="rounded border border-line p-3">
      <h3 className="text-sm font-medium">{name}</h3>
      <p className="mt-1 text-xs leading-5 text-ink-muted">{detail}</p>
    </div>
  )
}

function checkView(item: PrecheckItem) {
  return {
    name: item.name,
    state: item.ok ? 'ok' as const : 'fail' as const,
    current: item.current,
    fix: item.fix,
  }
}
