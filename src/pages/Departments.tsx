import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/Button'
import { Drawer } from '@/components/ui/Drawer'
import { EmptyState } from '@/components/ui/EmptyState'
import { ErrorState } from '@/components/ui/ErrorState'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { useToast } from '@/components/ui/Toast'
import { createDepartment, fetchAgents, fetchDepartments, fetchPacks, type ApiError } from '@/lib/api'
import { useDepartment } from '@/state/department'

export function Departments() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const toast = useToast()
  const { setDeptId } = useDepartment()
  const [params, setParams] = useSearchParams()
  const [open, setOpen] = useState(params.get('create') === '1')
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')

  useEffect(() => {
    if (params.get('create') === '1') setOpen(true)
  }, [params])

  const departments = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const agents = useQuery({ queryKey: ['agents', 'all'], queryFn: () => fetchAgents('all') })
  const packs = useQuery({ queryKey: ['packs', 'all'], queryFn: () => fetchPacks('all') })
  const mutation = useMutation({
    mutationFn: createDepartment,
    onSuccess: async ({ id }) => {
      await queryClient.invalidateQueries({ queryKey: ['departments'] })
      setDeptId(id)
      setOpen(false)
      setParams({}, { replace: true })
      setName('')
      setSlug('')
      toast('数字部门已创建')
    },
  })

  const counts = useMemo(() => {
    const result = new Map<string, { agents: number; packs: number }>()
    for (const department of departments.data ?? []) result.set(department.id, { agents: 0, packs: 0 })
    for (const agent of agents.data ?? []) {
      const count = result.get(agent.departmentId)
      if (count) count.agents += 1
    }
    for (const pack of packs.data ?? []) {
      const count = result.get(pack.departmentId)
      if (count) count.packs += 1
    }
    return result
  }, [agents.data, departments.data, packs.data])

  const submit = () => mutation.mutate({ name: name.trim(), slug: slug.trim() })
  const error = mutation.error as ApiError | null

  return (
    <div className="page-shell">
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-line pb-5">
        <div>
          <h1 className="page-title">数字部门</h1>
          <p className="page-description max-w-2xl">
            数字部门是企业能力门户的组织入口。每个部门固化一个 consumer group 引用，部门下的数字员工、能力包和发布记录都可独立追查。
          </p>
        </div>
        <Button variant="primary" onClick={() => setOpen(true)}>创建数字部门</Button>
      </div>

      {departments.isLoading ? (
        <SkeletonTable rows={3} cols={3} />
      ) : (departments.data ?? []).length === 0 ? (
        <div className="rounded border border-line bg-surface">
          <EmptyState
            message="还没有数字部门。先创建企业的第一个数字部门，之后才能创建数字员工。"
            action={<Button variant="primary" onClick={() => setOpen(true)}>创建第一个数字部门</Button>}
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {(departments.data ?? []).map((department) => {
            const count = counts.get(department.id) ?? { agents: 0, packs: 0 }
            return (
              <button
                key={department.id}
                onClick={() => {
                  setDeptId(department.id)
                  navigate('/agents')
                }}
                className="panel group p-4 text-left transition hover:-translate-y-0.5 hover:border-brand hover:shadow-md"
              >
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h2 className="font-medium group-hover:underline">{department.name}</h2>
                    <code className="mt-1 block font-mono text-[11px] text-ink-faint">{department.slug}</code>
                  </div>
                  <span className="rounded border border-line px-2 py-1 font-mono text-[10px] text-ink-muted">
                    {department.consumerGroupRef}
                  </span>
                </div>
                <div className="mt-6 grid grid-cols-2 border-t border-line pt-3 text-xs">
                  <div>
                    <span className="block font-mono text-base font-medium">{count.agents}</span>
                    <span className="text-ink-muted">数字员工</span>
                  </div>
                  <div>
                    <span className="block font-mono text-base font-medium">{count.packs}</span>
                    <span className="text-ink-muted">能力包</span>
                  </div>
                </div>
              </button>
            )
          })}
        </div>
      )}

      <section className="rounded border border-line bg-surface p-4 text-xs leading-5 text-ink-muted">
        <h2 className="font-semibold text-ink">数据保留原则</h2>
        <p className="mt-1">
          系统不预置任何示例部门或业务数据。用户创建后，部门、数字员工生命周期、Release 全量快照、审批哈希、测试报告、门禁、部署记录和操作审计会按控制面证据长期保留。
        </p>
      </section>

      <Drawer open={open} onClose={() => { setOpen(false); setParams({}, { replace: true }) }} title="创建数字部门">
        <div className="flex flex-col gap-4">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-ink-muted">部门名称</span>
            <input className="input" value={name} onChange={(event) => setName(event.target.value)} placeholder="例：客户服务部" />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-ink-muted">slug</span>
            <input
              className="input font-mono"
              value={slug}
              onChange={(event) => setSlug(event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))}
              placeholder="customer-service"
            />
            <span className="text-xs text-ink-faint">创建后不可变，用于生成 consumer group 和 MCP 服务名。</span>
          </label>
          {error && <ErrorState compact what={error.what} fix={error.fix} />}
          <div className="flex gap-2">
            <Button variant="primary" disabled={!name.trim() || !slug.trim() || mutation.isPending} onClick={submit}>
              {mutation.isPending ? '创建中…' : '创建部门'}
            </Button>
            <Button variant="ghost" onClick={() => setOpen(false)}>取消</Button>
          </div>
        </div>
      </Drawer>
    </div>
  )
}
