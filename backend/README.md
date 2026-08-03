# 朱雀后端骨架

Java 17 + Spring Boot 3 + PostgreSQL 15。全量契约见项目根 `CLAUDE.md`。

**当前状态：控制面实现已接入 PostgreSQL 迁移与 HTTP API。**
数据库由 Flyway 从 `db/migration` 初始化；迁移不写入演示数据。**新建空数据库**首次启动为空库；已有数据库绝不会被应用自动清空，历史测试数据如需清理应先备份并走受控运维流程。

## 模块地图（包名 = P2 模块号）

| 包 | 模块 | 核心类 |
|---|---|---|
| `m1_toolpool` | 工具池与富化 | OpenApiParser → ToolDraftGenerator → OutputFieldExtractor → EnrichmentService → StaticAnnotator；SpecSyncService（条目级 diff，绝不全表重建） |
| `m2_agent` | 数字员工与意图拆解 | AgentService（slug/mcp_url 不可变）、IntentDecomposer（8~12 条，forbidden 单独） |
| `m3_matching` | 意图匹配 | IntentMatcher（长上下文直灌，不上向量检索）、RetrievalPrefilter（>2000 条的扩展点，v1 Noop） |
| `m4_closure` | 闭包检查 | ClosureChecker（可达性不动点迭代，500 工具 200ms）、FieldNormalizer（归一化+同义词+置信度） |
| `m5_release` | Release 状态机 | ReleaseStateMachine（迁移表唯一裁决）、ManifestCompiler（冻结五件事）、VersionSuggester、ReleaseService |
| `m6_testing` | 三层测试 | L0StaticChecker（零依赖）、L1ContractTester（真实测试/预发上游）、L2AgentEvaluator（只测选工具准确率，model_meta 必填）、TestCaseService |
| `m7_gate` | 门禁 | GateRule 接口 + BuiltinRules 清单 + GateEngine（逐条落库、豁免、规则集版本化） |
| `m8_deploy` | 双 target 发布 | DeployTarget 接口、NacosTarget（Admin API）、**HigressAuthTarget（网关知识只许在这一个类）**、DualTargetPublisher（快照→先鉴权后工具→失败逆向恢复）、DeployPrecheck |
| `m9_drift` | 漂移检测 | SpecDriftDetector（复用 M1 diff）、ConfigDriftDetector（读回比对 + 重放修复） |
| `m10_org` | 组织与凭证 | DepartmentService（同步建 consumer group）、AgentLifecycleService、KeyService（明文永不落库） |
| `metrics` | 北极星指标 | NorthStarMetrics（首次推荐精确率，按部门） |
| `common` | 公共 | CanonicalJson（规范化+SHA-256，M5/M8/M9 共用同一实现）、JobProgress |

## P3 里程碑 → 模块对照

- **M1 验收**（导入 OpenAPI 出静态检查表）：m1 全部 + m6.L0StaticChecker
- **M2 验收**（生成并审核候选工具）：m2 + m3（前端矩阵已就绪）
- **M3 验收**（冻结出带证据的快照并被规则挡住）：m4 + m5 + m7
- **M4 验收**（真正发布、拿到 URL、回滚）：m8 + m10.KeyService + m9

## 实现顺序建议

严格按里程碑走。跨模块的三个共享点先定死：
1. `CanonicalJson`——hash 稳定性是审批/幂等/漂移三件事的地基，最先写并配测试
2. `tool.output_fields` 的路径格式（M1 产出、M4 消费），两边用同一常量库
3. `ReleaseStateMachine.ALLOWED`——所有状态变更唯一入口

## 数据保留与回收站

首次部署只执行建表迁移，不插入部门、数字员工、REST API 或 Release 示例数据。用户开始使用后，系统保留能够重建控制面决策链的记录：部门与员工身份、REST API 的 `spec_hash`、冻结 Release 全量快照、审批哈希、L0/L1/L2 测试报告、门禁判定、部署记录、漂移事件以及操作审计。每次测试重跑都会新增不可变 `test_run`，报告按 run 关联；门禁判定和豁免也只追加历史，不覆盖旧结论。迁移前无法确定归属的旧报告会明确标记为 legacy/unbound，而不会被伪装成某次新运行的证据；它们可查但不能用于进入 `tested/approved`，须重新跑出 bound run。L1 仅保留方法、目标 origin、HTTP 状态、耗时、响应大小与 SHA-256；不会把请求 URL/参数、请求体或响应体写入审计库。

删除操作分两步：普通删除只会将数字员工或 REST API 移入回收站并记录操作人和理由；历史 Release 及相关证据不改写。只有从未进入冻结 Release、且没有能力包等活动引用的纯草稿，才可由用户在回收站再次确认后物理删除。即使物理删除成功，`audit_event` 不设资源外键，仍会保留谁在何时执行了清除操作的记录。

正式 L1 只会调用标为 `test` 或 `staging` 的上游，但环境标签本身不是网络授权：只有人工复核的 `effect=read` 工具、冻结模板中带有 `x-zhuque-l1={testSafe:true,fixture:"…"}` 标记、且其精确 `scheme://host:port` origin 位于服务端 `zhuque.testing.allowed-origins` 中，才会真正出网。该配置默认空白（deny-all）；开发/部署时需显式设置 `ZHUQUE_TESTING_ALLOWED_ORIGINS=https://staging-api.example.com,http://localhost:19080`。写/删接口在没有专门的 fixture、清理与回读断言前会明确失败，不会用重复 HTTP 成功伪造幂等性或自动写入业务数据。

生产部署还必须把控制台放在 SSO/受信任网关后，由网关提供已验证的用户主体；浏览器提交的操作人字段只能作为本地开发标签，不能单独视为防篡改身份认证。
