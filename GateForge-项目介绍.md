# GateForge：API → 高质量 MCP 能力包自动工程化平台

GateForge 面向企业存量 API，自动完成 API 分析、工具语义优化、意图聚类、治理元数据、
依赖图、自动测试与 MCP 能力包编译。通过检查的能力包由 Nacos 适配器提交到 Nacos，
再由 Higress 自动发现并成为智能体可调用的 MCP 服务。

## 产品定位

GateForge 不是 MCP 网关，也不是 Nacos 的替代品。它是 **MCP 工程加工、编译、质量与治理引擎**：
回答“怎样把一批原始企业 API 加工成高质量、可治理、可测试的 MCP 能力包”，而不是
“怎样在运行时代理一次工具调用”。

## 产物

一个能力包构建产物包含：

1. 标准 MCP 服务定义与工具定义。
2. `inputSchema` 与后端 API 映射。
3. 后端端点信息。
4. 独立的 GateForge 治理元数据。
5. 工具依赖有向无环图与五类闭包结论。
6. L0/L1/L2 测试报告和统一质量分。
7. 不可变构建清单与内容摘要。

标准 MCP 与治理元数据严格分开。治理附加信息用于可信智能体宿主或网关实现审批、权限、
风险和重试控制；不能依赖模型自己遵守，也不能污染工具输入结构。

Nacos 3.0.1 的注册投影只使用其官方 McpTool 字段；治理包以 namespaced JSON 字符串存放在
`toolsMeta[tool].invokeContext["com.gateforge/governance"]`，并绑定 Pack hash。Higress 2.2.3
不会自动执行该策略，实际授权仍由可信智能体宿主或运行时插件实施。

## 系统分工

```text
GateForge：加工、编译、质量和治理证据
Nacos：注册、版本、生命周期、服务发现
Higress：MCP/API 网关与运行时数据面
智能体/OpenClaw：规划与执行
```

GateForge 不进入智能体实际调用链，也不维护部门、数字员工、发布审批、密钥、回滚、漂移
或 Higress 控制台配置。

## 部署形态

GateForge 使用单一 Docker 镜像部署：构建阶段编译 React 前端，运行阶段只保留 Python
FastAPI 与静态资源。容器以非 root 用户运行，根文件系统只读，SQLite 持久化到 `/data`
命名卷。Nacos 和 Higress 不包含在 GateForge Compose 中，只通过配置连接外部实例。
