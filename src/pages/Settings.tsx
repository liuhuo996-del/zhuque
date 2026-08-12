import { useQuery } from '@tanstack/react-query'
import { fetchSettings } from '@/lib/api'
import { JsonBlock } from '@/components/ui/JsonBlock'

export function Settings() {
  const query = useQuery({ queryKey: ['settings'], queryFn: fetchSettings })
  return <div className="page-shell"><header className="border-b border-line pb-5"><h1 className="page-title">系统边界与配置</h1><p className="page-description">这些边界是架构约束，不是可以在页面里随意切换的产品开关。</p></header><div className="grid gap-4 lg:grid-cols-2">{[['GateForge','MCP 工程加工、编译、质量与治理引擎','只保存 API 加工产物、测试证据和构建产物。'],['Nacos','注册、版本、生命周期与服务发现控制面','GateForge 只通过官方 AI/MCP 管理接口提交和回读。'],['Higress','MCP/API 网关与运行数据面','路由、鉴权、限流和 MCP↔API 调用不经过 GateForge。'],['智能体 / OpenClaw','规划与执行','根据标准 MCP 工具选择能力，并由可信运行时执行治理策略。']].map(([name, role, note]) => <section key={name} className="panel p-5"><h2 className="font-semibold">{name}</h2><p className="mt-2 font-mono text-xs text-brand-strong">{role}</p><p className="mt-3 text-sm leading-6 text-ink-muted">{note}</p></section>)}</div>{query.data && <JsonBlock title="公开运行配置" data={query.data} />}<section className="panel p-5"><h2 className="font-semibold">明确删除的旧能力</h2><p className="mt-3 text-sm leading-7 text-ink-muted">数字部门、数字员工生命周期、人工版本审批、GateForge 密钥托管、配置漂移、回滚状态机、Higress 控制台写入、双目标发布均不再属于 GateForge。Nacos 是唯一注册中心和生命周期控制面。</p></section></div>
}
