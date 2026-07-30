# 朱雀后端骨架

Java 17 + Spring Boot 3 + PostgreSQL 15。全量契约见项目根 `CLAUDE.md`。

**当前状态：框架 + 功能注释。所有方法体是 `TODO`，按注释填实现即可。**
持久层未定型：schema 在 `db/migration/V1__init.sql`（与契约逐字段对应），
service 里按注释自行选 JdbcTemplate / MyBatis / JPA。

## 模块地图（包名 = P2 模块号）

| 包 | 模块 | 核心类 |
|---|---|---|
| `m1_toolpool` | 工具池与富化 | OpenApiParser → ToolDraftGenerator → OutputFieldExtractor → EnrichmentService → StaticAnnotator；SpecSyncService（条目级 diff，绝不全表重建） |
| `m2_agent` | 数字员工与意图拆解 | AgentService（slug/mcp_url 不可变）、IntentDecomposer（8~12 条，forbidden 单独） |
| `m3_matching` | 意图匹配 | IntentMatcher（长上下文直灌，不上向量检索）、RetrievalPrefilter（>2000 条的扩展点，v1 Noop） |
| `m4_closure` | 闭包检查 | ClosureChecker（可达性不动点迭代，500 工具 200ms）、FieldNormalizer（归一化+同义词+置信度） |
| `m5_release` | Release 状态机 | ReleaseStateMachine（迁移表唯一裁决）、ManifestCompiler（冻结五件事）、VersionSuggester、ReleaseService |
| `m6_testing` | 三层测试 | L0StaticChecker（零依赖）、MockServerFactory + L1ContractTester（mock 优先）、L2AgentEvaluator（只测选工具准确率，model_meta 必填）、TestCaseService |
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
