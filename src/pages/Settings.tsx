import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  fetchSettings,
  saveSettings,
  testAiSettings,
  testNacosSettings,
} from '@/lib/api'
import type { RuntimeSettingsPayload, RuntimeSettingsView } from '@/types'
import { Button } from '@/components/ui/Button'
import { ErrorState } from '@/components/ui/ErrorState'
import { useToast } from '@/components/ui/Toast'

interface FormState {
  adminToken: string
  nacosServerUrl: string
  nacosContextPath: string
  nacosNamespace: string
  nacosUsername: string
  nacosPassword: string
  clearNacosPassword: boolean
  aiBaseUrl: string
  aiModel: string
  aiApiKey: string
  clearAiApiKey: boolean
  allowedSpecHosts: string
  allowPrivateSpecHosts: boolean
  l1AllowOrigins: string
  l1AllowUnsafeMethods: boolean
}

const EMPTY: FormState = {
  adminToken: '',
  nacosServerUrl: '',
  nacosContextPath: '/nacos',
  nacosNamespace: 'public',
  nacosUsername: '',
  nacosPassword: '',
  clearNacosPassword: false,
  aiBaseUrl: '',
  aiModel: '',
  aiApiKey: '',
  clearAiApiKey: false,
  allowedSpecHosts: '',
  allowPrivateSpecHosts: true,
  l1AllowOrigins: '',
  l1AllowUnsafeMethods: false,
}

export function Settings() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const query = useQuery({ queryKey: ['settings'], queryFn: fetchSettings })
  const [form, setForm] = useState<FormState>(EMPTY)
  const [result, setResult] = useState<string>('')

  useEffect(() => {
    if (!query.data) return
    setForm((current) => ({ ...fromSettings(query.data), adminToken: current.adminToken }))
  }, [query.data])

  const save = useMutation({
    mutationFn: () => saveSettings(payload(form), form.adminToken),
    onSuccess: (data) => {
      queryClient.setQueryData(['settings'], data)
      setForm((current) => ({
        ...fromSettings(data),
        adminToken: current.adminToken,
        nacosPassword: '',
        aiApiKey: '',
      }))
      setResult('配置已保存并立即生效；敏感值已经加密写入数据卷。')
      toast('运行配置已保存')
    },
  })
  const nacosTest = useMutation({
    mutationFn: () => testNacosSettings(payload(form), form.adminToken),
    onSuccess: (data) => setResult(`Nacos 连接成功：版本 ${data.version}，命名空间 ${data.namespace}`),
  })
  const aiTest = useMutation({
    mutationFn: () => testAiSettings(payload(form), form.adminToken),
    onSuccess: (data) => setResult(`大模型连接成功：模型 ${data.model} 已返回兼容响应。`),
  })
  const error = save.error ?? nacosTest.error ?? aiTest.error
  const busy = save.isPending || nacosTest.isPending || aiTest.isPending

  if (query.error) {
    const value = query.error as ApiError
    return <ErrorState what={value.what} fix={value.fix} />
  }

  return (
    <div className="page-shell">
      <header className="border-b border-line pb-5">
        <h1 className="page-title">连接与安全设置</h1>
        <p className="page-description">配置 Nacos、大模型和企业内网 OpenAPI 访问策略。保存后立即生效，不需要重启容器。</p>
      </header>

      {!query.data?.adminWriteEnabled && (
        <div className="rounded-lg border border-warn/30 bg-[var(--warn-tint)] p-4 text-sm leading-6 text-ink-muted">
          <strong className="text-warn">配置写入尚未启用。</strong> 请先在 Docker 环境变量中设置
          <code className="mx-1 rounded bg-white px-1.5 py-0.5 text-xs">GATEFORGE_ADMIN_TOKEN</code>
          并重启容器。读取配置不需要令牌。
        </div>
      )}

      <Section title="管理授权" description="令牌仅保存在当前页面内存中，不写入浏览器存储，也不会提交给 Nacos 或大模型。">
        <Field label="GateForge 管理令牌" hint="使用 Docker 部署时配置的 GATEFORGE_ADMIN_TOKEN。">
          <input className="input w-full" type="password" autoComplete="off" value={form.adminToken} onChange={(event) => set(form, setForm, 'adminToken', event.target.value)} placeholder="输入管理令牌后才能测试或保存" />
        </Field>
      </Section>

      <Section title="Nacos 连接" description={`仅调用 Nacos 官方 AI/MCP 管理接口；最低支持版本 ${query.data?.nacos.minVersion ?? '3.0.1'}。`}>
        <div className="grid gap-4 lg:grid-cols-2">
          <Field label="服务地址" hint="Docker 访问宿主机可使用 http://host.docker.internal:8848。">
            <input className="input w-full" value={form.nacosServerUrl} onChange={(event) => set(form, setForm, 'nacosServerUrl', event.target.value)} placeholder="http://nacos.example.corp:8848" />
          </Field>
          <Field label="上下文路径" hint="官方默认值通常为 /nacos；若网关已移除前缀可留空。">
            <input className="input w-full" value={form.nacosContextPath} onChange={(event) => set(form, setForm, 'nacosContextPath', event.target.value)} placeholder="/nacos" />
          </Field>
          <Field label="命名空间" hint="填写命名空间 ID，不是显示名称。">
            <input className="input w-full" value={form.nacosNamespace} onChange={(event) => set(form, setForm, 'nacosNamespace', event.target.value)} placeholder="public" />
          </Field>
          <Field label="用户名" hint="Nacos 未启用鉴权时可以留空。">
            <input className="input w-full" autoComplete="username" value={form.nacosUsername} onChange={(event) => set(form, setForm, 'nacosUsername', event.target.value)} />
          </Field>
          <Field label="密码" hint={secretHint(query.data?.nacos.passwordConfigured, query.data?.nacos.passwordSaved)}>
            <input className="input w-full" type="password" autoComplete="new-password" value={form.nacosPassword} disabled={form.clearNacosPassword} onChange={(event) => set(form, setForm, 'nacosPassword', event.target.value)} placeholder="留空表示保持现有密码" />
          </Field>
          <Checkbox checked={form.clearNacosPassword} onChange={(checked) => set(form, setForm, 'clearNacosPassword', checked)} label="清除已保存的 Nacos 密码" />
        </div>
        <Button type="button" disabled={busy || !form.adminToken} onClick={() => nacosTest.mutate()}>{nacosTest.isPending ? '测试中…' : '测试 Nacos 连接'}</Button>
      </Section>

      <Section title="大模型增强" description="使用 OpenAI 兼容的聊天补全接口改善工具名称、说明和参数描述；未配置时自动使用确定性规则。">
        <div className="grid gap-4 lg:grid-cols-2">
          <Field label="接口地址" hint="填写到 /v1，不要包含 /chat/completions。">
            <input className="input w-full" value={form.aiBaseUrl} onChange={(event) => set(form, setForm, 'aiBaseUrl', event.target.value)} placeholder="https://llm.example.corp/v1" />
          </Field>
          <Field label="模型名称" hint="必须是服务端实际接受的模型标识。">
            <input className="input w-full" value={form.aiModel} onChange={(event) => set(form, setForm, 'aiModel', event.target.value)} placeholder="企业模型名称" />
          </Field>
          <Field label="API Key" hint={secretHint(query.data?.ai.apiKeyConfigured, query.data?.ai.apiKeySaved)}>
            <input className="input w-full" type="password" autoComplete="new-password" value={form.aiApiKey} disabled={form.clearAiApiKey} onChange={(event) => set(form, setForm, 'aiApiKey', event.target.value)} placeholder="留空表示保持现有 API Key" />
          </Field>
          <Checkbox checked={form.clearAiApiKey} onChange={(checked) => set(form, setForm, 'clearAiApiKey', checked)} label="清除已保存的 API Key" />
        </div>
        <Button type="button" disabled={busy || !form.adminToken} onClick={() => aiTest.mutate()}>{aiTest.isPending ? '测试中…' : '测试大模型连接'}</Button>
      </Section>

      <Section title="OpenAPI 导入安全" description="自托管默认允许企业私网 API；回环、链路本地、云元数据、组播和保留地址始终拒绝。公网多人部署建议关闭企业私网访问并配置可信域名。">
        <div className="grid gap-4 lg:grid-cols-2">
          <Field label="可信 OpenAPI 域名" hint="每行一个域名；支持 *.example.corp。名单内域名允许解析到内网 IP。">
            <textarea className="input min-h-32 w-full font-mono text-xs" value={form.allowedSpecHosts} onChange={(event) => set(form, setForm, 'allowedSpecHosts', event.target.value)} placeholder={'openapi.example.corp\n*.api.example.corp'} />
          </Field>
          <Field label="L1 允许访问的业务源站" hint="每行一个完整源站，包含协议和可选端口，不要填写路径。">
            <textarea className="input min-h-32 w-full font-mono text-xs" value={form.l1AllowOrigins} onChange={(event) => set(form, setForm, 'l1AllowOrigins', event.target.value)} placeholder={'https://api.example.corp\nhttp://service.internal:8080'} />
          </Field>
          <Checkbox checked={form.allowPrivateSpecHosts} onChange={(checked) => set(form, setForm, 'allowPrivateSpecHosts', checked)} label="允许 RFC1918 / IPv6 ULA 企业私网地址（推荐用于客户网络内自托管；不包含 localhost 和云元数据地址）" />
          <Checkbox checked={form.l1AllowUnsafeMethods} onChange={(checked) => set(form, setForm, 'l1AllowUnsafeMethods', checked)} tone="warning" label="允许 L1 连通性测试调用写入/删除类接口（可能产生真实业务副作用）" />
        </div>
      </Section>

      {result && <div className="rounded-lg border border-pass/30 bg-[var(--pass-tint)] p-4 text-sm text-pass">{result}</div>}
      {error && <ErrorState what={(error as ApiError).what} fix={(error as ApiError).fix} />}

      <div className="sticky bottom-4 flex items-center justify-end gap-3 rounded-lg border border-line bg-white/95 p-3 shadow-lg backdrop-blur">
        <span className="mr-auto text-xs text-ink-faint">密码和 API Key 永不回显；新值将使用 Fernet 加密后保存。</span>
        <Button type="button" disabled={busy || !form.adminToken || !query.data?.adminWriteEnabled} variant="primary" onClick={() => save.mutate()}>{save.isPending ? '保存中…' : '保存并立即生效'}</Button>
      </div>
    </div>
  )
}

function Section({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <section className="panel space-y-5 p-5"><div><h2 className="text-base font-semibold">{title}</h2><p className="mt-1 text-sm leading-6 text-ink-muted">{description}</p></div>{children}</section>
}

function Field({ label, hint, children }: { label: string; hint: string; children: ReactNode }) {
  return <label className="flex flex-col gap-1.5"><span className="text-sm font-medium">{label}</span>{children}<span className="text-xs leading-5 text-ink-faint">{hint}</span></label>
}

function Checkbox({ checked, onChange, label, tone = 'normal' }: { checked: boolean; onChange: (checked: boolean) => void; label: string; tone?: 'normal' | 'warning' }) {
  return <label className={`flex min-h-16 cursor-pointer items-start gap-3 rounded-lg border p-3 text-sm leading-5 ${tone === 'warning' ? 'border-warn/30 bg-[var(--warn-tint)]' : 'border-line bg-surface-subtle'}`}><input className="mt-1 size-4 accent-[var(--brand)]" type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} /><span>{label}</span></label>
}

function fromSettings(data: RuntimeSettingsView): FormState {
  return {
    ...EMPTY,
    nacosServerUrl: data.nacos.serverUrl,
    nacosContextPath: data.nacos.contextPath,
    nacosNamespace: data.nacos.namespace,
    nacosUsername: data.nacos.username,
    aiBaseUrl: data.ai.baseUrl,
    aiModel: data.ai.model,
    allowedSpecHosts: data.intake.allowedSpecHosts.join('\n'),
    allowPrivateSpecHosts: data.intake.allowPrivateSpecHosts,
    l1AllowOrigins: data.intake.l1AllowOrigins.join('\n'),
    l1AllowUnsafeMethods: data.intake.l1AllowUnsafeMethods,
  }
}

function payload(form: FormState): RuntimeSettingsPayload {
  return {
    nacos: {
      serverUrl: form.nacosServerUrl.trim(),
      contextPath: form.nacosContextPath.trim(),
      namespace: form.nacosNamespace.trim(),
      username: form.nacosUsername.trim(),
      password: form.nacosPassword || undefined,
      clearPassword: form.clearNacosPassword,
    },
    ai: {
      baseUrl: form.aiBaseUrl.trim(),
      model: form.aiModel.trim(),
      apiKey: form.aiApiKey || undefined,
      clearApiKey: form.clearAiApiKey,
    },
    intake: {
      allowedSpecHosts: lines(form.allowedSpecHosts),
      allowPrivateSpecHosts: form.allowPrivateSpecHosts,
      l1AllowOrigins: lines(form.l1AllowOrigins),
      l1AllowUnsafeMethods: form.l1AllowUnsafeMethods,
    },
  }
}

function lines(value: string): string[] {
  return [...new Set(value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean))]
}

function set<K extends keyof FormState>(current: FormState, update: (next: FormState) => void, key: K, value: FormState[K]) {
  update({ ...current, [key]: value })
}

function secretHint(configured?: boolean, saved?: boolean): string {
  if (!configured) return '当前未配置。填写后只保存密文。'
  return saved ? '已在 GateForge 数据卷中加密保存；留空保持不变。' : '已由 Docker 环境变量提供；留空保持不变。'
}
