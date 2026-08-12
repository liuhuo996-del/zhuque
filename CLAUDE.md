# GateForge

GateForge 是面向企业存量 API 的 **API → 高质量 MCP 能力包自动工程化平台**。

## 不可突破的系统边界

```text
GateForge = MCP 工程加工 / 编译 / 质量与治理引擎
Nacos     = 注册 / 版本 / 生命周期 / 服务发现控制面
Higress   = MCP/API 网关与运行数据面
智能体    = 规划与执行
```

GateForge 不实现注册中心、配置版本中心、发布生命周期、服务发现、MCP 服务、
API 网关、智能体生命周期、运行时鉴权、业务请求代理或工具调用数据面。

GateForge 只能通过 Nacos 官方 AI/MCP 管理接口交互。Nacos 是唯一注册中心和
生命周期控制面；Higress 从 Nacos 发现 MCP 并承担最终数据面。

## 固定加工链路

```text
OpenAPI / REST API
→ 接入与分析
→ AI 富化
→ 意图聚类
→ MCP 能力包构建
→ 治理元数据
→ 依赖图
→ 自动测试
→ 闭包检查
→ MCP 能力包构建产物
→ Nacos 适配器
→ Nacos
→ Higress
→ 智能体 / OpenClaw
```

## 数据边界

GateForge 只保存以下工程数据：

- `api_source`：输入规范、摘要、基础地址、负责人、环境。
- `tool`：标准 MCP 工具、后端 API 映射、端点、治理附加信息、分析结果。
- `pack`：不可变 MCP 能力包构建产物和测试证据。
- `nacos_registration`：Nacos 官方接口回读的服务编号、版本和状态。

禁止重新加入部门、智能体、发布状态机、审批、智能体密钥、门禁决策、部署目标、
配置漂移或 Higress 控制台管理表。

## MCP 与治理字段

标准 MCP 工具与 GateForge 治理元数据必须分离：

- `toolSpecification.tools[]`：只包含标准 MCP 工具字段。
- `toolsMeta[tool].templates`：Nacos/Higress API 转换配置。
- `toolsMeta[tool].invokeContext["com.gateforge/governance"]`：Nacos 3.0.1 可持久化的
  带命名空间的 JSON 治理附加信息；不能使用会被服务端丢弃的未知 `toolsMeta` 同级字段。
- 治理字段不能塞进 `inputSchema`，也不能伪装成标准 ToolAnnotations。

当前运行配置是 `MCP 2025-06-18 + Higress 2.2.3`。后续升级必须增加真实
`tools/list` / `tools/call` 合约测试，不能只修改版本字符串。

## 技术栈

- 后端：Python 3.10+、FastAPI、Pydantic v2、HTTPX、SQLite、JSON Schema 2020-12。
- 前端：React 18、TypeScript、Vite、TanStack Query、Tailwind。
- 注册中心：Nacos >= 3.0.1 官方 `/nacos/v3/admin/ai/mcp`。
- 部署：多阶段 Docker 镜像；FastAPI 同时托管前端静态资源，SQLite 只写入 `/data` 持久卷。

## 质量规则

- 分析器可自动拒绝已废弃、运行探针、重复和输入结构过大的 API。
- L0 必须覆盖结构规范、参数边界、安全、权限和五类闭包。
- L1 只能访问显式允许列表中的测试来源地址；写操作默认禁止。
- L2 负责工具语义区分；没有真实模型时必须标记确定性降级模式。
- 被阻断的能力包可保存用于修复，但不能提交 Nacos。
- 构建产物不等于发布版本；版本与生命周期由 Nacos 管理。
