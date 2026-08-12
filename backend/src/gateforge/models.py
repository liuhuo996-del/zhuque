from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, model_validator


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
    tool_ids: list[str] = Field(default_factory=list)
    run_l1: bool = False
    run_l2: bool = True

    @model_validator(mode="after")
    def choose_tools(self) -> "PackBuildRequest":
        if not self.cluster_keys and not self.tool_ids:
            raise ValueError("至少选择一个 cluster 或 tool")
        return self


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


class PackArtifact(BaseModel):
    schema_version: Literal["gateforge.mcp-pack/v1"] = "gateforge.mcp-pack/v1"
    id: str
    name: str
    slug: str
    description: str
    created_at: str
    status: Literal["ready", "blocked"]
    mcp_server: JsonObject
    tools: list[JsonObject]
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
    packs: int
    ready_packs: int
    registered_packs: int
    average_quality: float
    recent_packs: list[PackArtifact]
