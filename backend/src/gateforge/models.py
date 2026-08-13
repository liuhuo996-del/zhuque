from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, field_validator, model_validator
from urllib.parse import urlparse


JsonObject = dict[str, Any]


class SourceImport(BaseModel):
    name: str = Field(min_length=1, max_length=160)
    slug: str | None = Field(default=None, pattern=r"^[a-z0-9][a-z0-9-]*$")
    spec_url: HttpUrl | None = None
    spec_text: str | None = None
    base_url: HttpUrl | None = None
    environment: Literal["test", "staging", "prod"] = "test"
    owner: str = "unassigned"

    @model_validator(mode="after")
    def exactly_one_spec(self) -> "SourceImport":
        if bool(self.spec_url) == bool(self.spec_text and self.spec_text.strip()):
            raise ValueError("spec_url 与 spec_text 必须且只能提供一个")
        return self


class ApiSourceView(BaseModel):
    id: str
    name: str
    slug: str
    spec_url: str | None
    base_url: str
    environment: str
    owner: str
    spec_hash: str
    imported_at: str
    operation_count: int
    accepted_count: int
    rejected_count: int


class Sensitivity(BaseModel):
    input: list[str] = Field(default_factory=list)
    output: list[str] = Field(default_factory=list)


class GovernanceMetadata(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    schema_version: Literal["gateforge.governance/v1"] = Field(
        default="gateforge.governance/v1", alias="schemaVersion"
    )
    intent: list[str] = Field(default_factory=list)
    domain: list[str] = Field(default_factory=list)
    read_only: bool = Field(alias="readOnly")
    write: bool
    destructive: bool
    idempotent: bool | None = None
    side_effect: Literal["none", "local", "external", "unknown"] = Field(alias="sideEffect")
    risk_level: Literal["low", "medium", "high", "critical"] = Field(alias="riskLevel")
    approval_required: bool = Field(alias="approvalRequired")
    approval_role: str | None = Field(default=None, alias="approvalRole")
    sensitivity: Sensitivity
    requires: list[str] = Field(default_factory=list)
    provides: list[str] = Field(default_factory=list)
    retryable: bool
    timeout_ms: int = Field(ge=100, le=120_000, alias="timeoutMs")
    owner: str
    quality_score: float = Field(ge=0, le=1, alias="qualityScore")
    description_score: float = Field(ge=0, le=1, alias="descriptionScore")
    schema_score: float = Field(ge=0, le=1, alias="schemaScore")
    test_pass_rate: float = Field(ge=0, le=1, alias="testPassRate")


class ToolView(BaseModel):
    id: str
    source_id: str
    source_name: str
    source_spec_hash: str
    accepted: bool
    rejection_reasons: list[str]
    method: str
    path: str
    operation_id: str
    standard: JsonObject
    backend_mapping: JsonObject
    endpoint: JsonObject
    governance: GovernanceMetadata
    cluster_key: str
    fingerprint: str


class ClusterView(BaseModel):
    key: str
    label: str
    domain: str
    intent: str
    tool_ids: list[str]
    tool_count: int
    source_count: int
    confidence: float


class PackBuildRequest(BaseModel):
    name: str | None = None
    slug: str | None = Field(default=None, pattern=r"^[a-z0-9][a-z0-9-]*$")
    description: str | None = None
    cluster_keys: list[str] = Field(default_factory=list)
    graph_ids: list[str] = Field(default_factory=list)
    tool_ids: list[str] = Field(default_factory=list)
    run_l1: bool = False
    run_l2: bool = True

    @model_validator(mode="after")
    def choose_tools(self) -> "PackBuildRequest":
        if not self.description and not self.cluster_keys and not self.graph_ids and not self.tool_ids:
            raise ValueError("填写能力包目标描述，或至少选择一个能力图/原子工具")
        return self

    @field_validator("name")
    @classmethod
    def name_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip()
        return value or None


class TestCaseResult(BaseModel):
    layer: Literal["L0", "L1", "L2"]
    category: str
    case_id: str
    status: Literal["pass", "fail", "warn", "skip"]
    score: float = Field(ge=0, le=1)
    detail: str
    evidence: JsonObject = Field(default_factory=dict)


class TestReport(BaseModel):
    schema_version: Literal["gateforge.test-report/v1"] = "gateforge.test-report/v1"
    cases: list[TestCaseResult]
    pass_rate: float
    quality_score: float
    blocking_failures: int
    generated_at: str


class DependencyNode(BaseModel):
    tool_id: str
    requires: list[str]
    provides: list[str]


class DependencyEdge(BaseModel):
    provider: str
    consumer: str
    fields: list[str]


class ClosureReport(BaseModel):
    parameter_closed: bool
    type_closed: bool
    permission_closed: bool
    risk_closed: bool
    side_effect_closed: bool
    cycles: list[list[str]] = Field(default_factory=list)
    missing_providers: JsonObject = Field(default_factory=dict)
    ambiguous_providers: JsonObject = Field(default_factory=dict)
    unreachable_tools: list[str] = Field(default_factory=list)


class DependencyGraph(BaseModel):
    schema_version: Literal["gateforge.dependency-graph/v1"] = "gateforge.dependency-graph/v1"
    nodes: list[DependencyNode]
    edges: list[DependencyEdge]
    closure: ClosureReport


class FieldPort(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    path: str
    name: str
    description: str
    schema_def: JsonObject = Field(alias="schema")
    concept: str
    cardinality: Literal["one", "many"] = "one"
    required: bool = False


class CapabilityGraphNode(BaseModel):
    tool_id: str
    tool_name: str
    role: Literal["provider", "terminal"]


class CapabilityGraphEdge(BaseModel):
    provider_tool_id: str
    consumer_tool_id: str
    output_path: str
    input_path: str
    concept: str
    confidence: float = Field(ge=0, le=1)
    evidence: list[str] = Field(default_factory=list)


class CapabilityGraphIssue(BaseModel):
    level: Literal["warning", "blocking"]
    code: str
    tool_id: str
    input_path: str | None = None
    detail: str


class GraphTestReport(BaseModel):
    schema_version: Literal["gateforge.graph-test/v1"] = "gateforge.graph-test/v1"
    cases: list[TestCaseResult]
    pass_rate: float
    blocking_failures: int


class CapabilityGraph(BaseModel):
    schema_version: Literal["gateforge.capability-graph/v1"] = "gateforge.capability-graph/v1"
    id: str
    name: str
    description: str
    output_description: str
    terminal_tool_id: str
    terminal_tool_name: str
    nodes: list[CapabilityGraphNode]
    edges: list[CapabilityGraphEdge]
    execution_order: list[str]
    subgraph_ids: list[str] = Field(default_factory=list)
    input_schema: JsonObject
    output_schema: JsonObject
    zero_input: bool
    confidence: float = Field(ge=0, le=1)
    status: Literal["ready", "needs_input", "ambiguous", "blocked"]
    governance: JsonObject
    issues: list[CapabilityGraphIssue] = Field(default_factory=list)
    test_report: GraphTestReport


class PackArtifact(BaseModel):
    schema_version: Literal["gateforge.mcp-pack/v1", "gateforge.mcp-pack/v2"] = "gateforge.mcp-pack/v2"
    id: str
    name: str
    slug: str
    description: str
    created_at: str
    status: Literal["ready", "blocked"]
    mcp_server: JsonObject
    tools: list[JsonObject]
    capability_graphs: list[CapabilityGraph] = Field(default_factory=list)
    backend_mappings: JsonObject
    endpoints: list[JsonObject]
    governance: JsonObject
    dependency_graph: DependencyGraph
    test_report: TestReport
    build_manifest: JsonObject
    artifact_hash: str


class RegistrationResult(BaseModel):
    pack_id: str
    nacos_server_id: str
    nacos_version: str
    status: str
    mcp_name: str
    registered_at: str
    raw: JsonObject = Field(default_factory=dict)


class DashboardView(BaseModel):
    sources: int
    operations: int
    accepted_tools: int
    rejected_operations: int
    clusters: int
    capability_graphs: int
    zero_input_graphs: int
    graph_coverage: float
    packs: int
    ready_packs: int
    registered_packs: int
    average_quality: float
    recent_packs: list[PackArtifact]


class PackRecommendationRequest(BaseModel):
    description: str = Field(min_length=4, max_length=2000)
    max_items: int = Field(default=6, ge=1, le=20)


class PackRecommendationItem(BaseModel):
    kind: Literal["graph", "tool"]
    id: str
    name: str
    description: str
    score: float = Field(ge=0, le=1)
    reason: str


class PackRecommendation(BaseModel):
    description: str
    items: list[PackRecommendationItem]
    graph_ids: list[str]
    tool_ids: list[str]


def _http_url(value: str, *, allow_empty: bool = False) -> str:
    value = value.strip().rstrip("/")
    if allow_empty and not value:
        return ""
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("必须填写有效的 HTTP(S) 地址")
    if parsed.username or parsed.password:
        raise ValueError("地址中不能包含用户名或密码")
    return value


class NacosSettingsInput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    server_url: str = Field(alias="serverUrl", min_length=1, max_length=2048)
    context_path: str = Field(default="/nacos", alias="contextPath", max_length=200)
    namespace: str = Field(default="public", min_length=1, max_length=255)
    username: str = Field(default="", max_length=255)
    password: str | None = Field(default=None, max_length=4096)
    clear_password: bool = Field(default=False, alias="clearPassword")

    @field_validator("server_url")
    @classmethod
    def validate_server_url(cls, value: str) -> str:
        return _http_url(value)

    @field_validator("context_path")
    @classmethod
    def validate_context_path(cls, value: str) -> str:
        value = value.strip()
        if not value:
            return ""
        if not value.startswith("/") or "?" in value or "#" in value:
            raise ValueError("上下文路径必须以 / 开头，且不能包含查询参数")
        return "/" + value.strip("/")

    @model_validator(mode="after")
    def secret_action_is_unambiguous(self) -> "NacosSettingsInput":
        if self.password and self.clear_password:
            raise ValueError("不能同时填写新密码并清除密码")
        return self


class AiSettingsInput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    base_url: str = Field(default="", alias="baseUrl", max_length=2048)
    model: str = Field(default="", max_length=255)
    api_key: str | None = Field(default=None, alias="apiKey", max_length=8192)
    clear_api_key: bool = Field(default=False, alias="clearApiKey")

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        return _http_url(value, allow_empty=True)

    @model_validator(mode="after")
    def secret_action_is_unambiguous(self) -> "AiSettingsInput":
        if self.api_key and self.clear_api_key:
            raise ValueError("不能同时填写新 API Key 并清除密钥")
        return self


class IntakeSecuritySettingsInput(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    allowed_spec_hosts: list[str] = Field(default_factory=list, alias="allowedSpecHosts", max_length=500)
    allow_private_spec_hosts: bool = Field(default=True, alias="allowPrivateSpecHosts")
    l1_allow_origins: list[str] = Field(default_factory=list, alias="l1AllowOrigins", max_length=500)
    l1_allow_unsafe_methods: bool = Field(default=False, alias="l1AllowUnsafeMethods")

    @field_validator("allowed_spec_hosts")
    @classmethod
    def validate_hosts(cls, values: list[str]) -> list[str]:
        result: list[str] = []
        for raw in values:
            value = raw.strip().lower().rstrip(".")
            candidate = value[2:] if value.startswith("*.") else value
            if not candidate or "://" in candidate or "/" in candidate or " " in candidate:
                raise ValueError(f"不是有效的域名模式：{raw}")
            if value not in result:
                result.append(value)
        return result

    @field_validator("l1_allow_origins")
    @classmethod
    def validate_origins(cls, values: list[str]) -> list[str]:
        return list(dict.fromkeys(_http_url(value) for value in values if value.strip()))


class RuntimeSettingsUpdate(BaseModel):
    nacos: NacosSettingsInput
    ai: AiSettingsInput
    intake: IntakeSecuritySettingsInput
