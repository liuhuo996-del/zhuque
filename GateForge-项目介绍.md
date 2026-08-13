# GateForge：API → 高质量 MCP 能力包自动工程化平台

GateForge 面向企业存量 API，自动完成 API 分析、工具语义优化、输入/输出字段索引、
能力图回溯构建、治理元数据、自动测试与 MCP 能力包编译。通过检查的能力包由 Nacos 适配器提交到 Nacos，
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
5. 能力图/子图、字段绑定、确定性执行顺序与图 Schema。
6. 工具依赖闭包与图级风险/审批传播结论。
7. L0/L1/L2 测试报告和统一质量分。
8. 不可变构建清单与内容摘要。

标准 MCP 与治理元数据严格分开。治理附加信息用于可信智能体宿主或网关实现审批、权限、
风险和重试控制；不能依赖模型自己遵守，也不能污染工具输入结构。

Nacos 3.0.1 的注册投影只使用其官方 McpTool 字段；治理包以 namespaced JSON 字符串存放在
`toolsMeta[tool].invokeContext["com.gateforge/governance"]`，并绑定 Pack hash。Higress 2.2.3
不会自动执行该策略，实际授权仍由可信智能体宿主或运行时插件实施。
Tool 所属的能力图摘要另存在
`toolsMeta[tool].invokeContext["com.gateforge/capabilityGraphs"]`；这些是命名空间附加信息，
不会改写 MCP Tool 的 `name` / `description` / `inputSchema` 标准字段。

## 能力图构建逻辑

GateForge 不对所有工具做全量两两比较。导入后先把响应 Schema 拆成输出字段端口，建立
语义词片 → 输出端口的倒排索引。对每个终点工具的必填入参，只召回可能的 Provider，
然后通过 JSON Schema 类型、单值/列表基数、同一 API origin、业务语义、读写风险与循环检查。
高置信且无歧义的 Provider 才会成为边，再递归回溯 Provider 自身的必填入参。无法安全补齐的
字段保留为图的外部入参；数组输出不会隐式转换为单值；分数接近的多 Provider 会标记歧义。

图最终生成拓扑执行顺序、字段 JSON Pointer 绑定、外部 `inputSchema`、终点 `outputSchema`、
输出描述、子图引用、治理传播和图测试报告。用户写能力包目标描述时，GateForge 优先匹配已完成
闭包检查的图，再用同一后端来源的原子工具补齐。

能力图是 GateForge Pack v2 的附加编译元数据，不是修改后的 MCP 协议。Nacos 仍然只注册标准 MCP
Tool 和 API 映射。多步图如果要成为单一可调用 Tool，必须由独立图执行器或智能体主机执行，
GateForge 不进入实际 Tool Call 数据面。

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
