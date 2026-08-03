import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { ErrorState } from '@/components/ui/ErrorState'
import { SkeletonTable } from '@/components/ui/SkeletonTable'
import { useToast } from '@/components/ui/Toast'
import { createAgent, fetchDepartments, type ApiError } from '@/lib/api'
import { useDepartment } from '@/state/department'

export function AgentNew() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const { deptId } = useDepartment()
  const departments = useQuery({ queryKey: ['departments'], queryFn: fetchDepartments })
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [departmentId, setDepartmentId] = useState(deptId === 'all' ? '' : deptId)
  const [description, setDescription] = useState('')
  const [forbiddenNotes, setForbiddenNotes] = useState('')

  useEffect(() => {
    const options = departments.data ?? []
    if (options.length === 0 || options.some((item) => item.id === departmentId)) return
    setDepartmentId(deptId !== 'all' && options.some((item) => item.id === deptId) ? deptId : options[0].id)
  }, [departmentId, departments.data, deptId])

  const mutation = useMutation({
    mutationFn: createAgent,
    onSuccess: async ({ id }) => {
      await queryClient.invalidateQueries({ queryKey: ['agents'] })
      await queryClient.invalidateQueries({ queryKey: ['audit-events'] })
      toast('数字员工已创建为草稿')
      navigate(`/agents/${id}`)
    },
  })

  if (departments.isLoading) return <SkeletonTable rows={5} cols={2} />

  if ((departments.data ?? []).length === 0) {
    return (
      <div className="max-w-2xl rounded border border-line bg-surface">
        <EmptyState
          message="创建数字员工前必须先有数字部门。部门会固化 consumer group，并作为员工能力、审批和审计的组织边界。"
          action={<Link to="/departments?create=1"><Button variant="primary">先创建数字部门</Button></Link>}
        />
      </div>
    )
  }

  const hasSelectedDepartment = (departments.data ?? []).some((department) => department.id === departmentId)
  const valid = name.trim() && slug.trim() && hasSelectedDepartment && description.trim()
  const error = mutation.error as ApiError | null

  return (
    <div className="flex max-w-3xl flex-col gap-5">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-lg font-semibold">新建数字员工</h1>
          <p className="mt-1 text-xs text-ink-muted">先登记稳定身份。创建后再从工具池选择能力、生成 Release、测试并由人工发布。</p>
        </div>
        <Button variant="ghost" onClick={() => navigate('/agents')}>取消</Button>
      </div>

      <div className="flex flex-col gap-4 rounded border border-line bg-surface p-5">
        <div className="grid grid-cols-2 gap-4">
          <Field label="名称">
            <input value={name} onChange={(event) => setName(event.target.value)} className="input" placeholder="例：售后客服专员" />
          </Field>
          <Field label="所属数字部门">
            <select value={departmentId} onChange={(event) => setDepartmentId(event.target.value)} className="input">
              {(departments.data ?? []).map((department) => (
                <option key={department.id} value={department.id}>{department.name}</option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="slug" hint="创建后不可变；MCP 服务名固定为 mcp-{department}-{slug}，发布、对账和回滚都依赖它。">
          <input
            value={slug}
            onChange={(event) => setSlug(event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))}
            className="input font-mono"
            placeholder="after-sales"
          />
        </Field>
        <Field label="职责描述" hint="写清楚它应完成的原子业务任务，后续意图拆解与工具匹配会读取这里。">
          <textarea
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            rows={7}
            className="input resize-y leading-relaxed"
            placeholder="例：你是售后客服专员，负责查询订单、解释物流状态并按审批规则发起退款。"
          />
        </Field>
        <Field label="明确禁止的事" hint="独立进入负向约束和后续评测，不与职责描述混写。">
          <textarea
            value={forbiddenNotes}
            onChange={(event) => setForbiddenNotes(event.target.value)}
            rows={3}
            className="input resize-y"
            placeholder="例：不得批量操作订单；不得绕过退款审批。"
          />
        </Field>

        {error && <ErrorState what={error.what} fix={error.fix} />}
        <div className="flex items-center gap-3 border-t border-line pt-4">
          <Button
            variant="primary"
            disabled={!valid || mutation.isPending}
            onClick={() => mutation.mutate({
              name: name.trim(), slug: slug.trim(), departmentId,
              description: description.trim(), forbiddenNotes: forbiddenNotes.trim(),
            })}
          >
            {mutation.isPending ? '创建中…' : '创建数字员工'}
          </Button>
          <span className="text-xs text-ink-faint">只创建 draft，不会自动发布或调用任何 MCP 数据面。</span>
        </div>
      </div>
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
