from __future__ import annotations

from typing import Any

import secrets

from fastapi import APIRouter, Body, Header, Query

from gateforge.clustering import clusters
from gateforge.capability_graph import CapabilityGraphBuilder, concept_tokens
from gateforge.compiler import PackCompiler, pack_tools_from_clusters
from gateforge.enrichment import ToolEnricher
from gateforge.errors import GateForgeError, QualityGateError
from gateforge.intake import OpenApiIntake
from gateforge.models import (
    ApiSourceView,
    ClusterView,
    DashboardView,
    PackArtifact,
    PackBuildRequest,
    PackRecommendation,
    PackRecommendationItem,
    PackRecommendationRequest,
    RegistrationResult,
    RuntimeSettingsUpdate,
    SourceImport,
    ToolView,
)
from gateforge.nacos import NacosAdapter
from gateforge.runtime_settings import RuntimeSettings
from gateforge.settings import Settings
from gateforge.store import Store
from gateforge.util import slugify


def router(store: Store, settings: Settings, runtime: RuntimeSettings) -> APIRouter:
    api = APIRouter(prefix="/api")
    intake = OpenApiIntake(settings)
    enricher = ToolEnricher(settings)
    compiler = PackCompiler(settings)
    nacos = NacosAdapter(settings)

    @api.get("/health")
    async def health() -> dict[str, Any]:
        return {"status": "ok", "service": "gateforge", "version": "0.2.0"}

    @api.get("/dashboard", response_model=DashboardView)
    async def dashboard() -> DashboardView:
        source_rows = store.sources()
        tool_rows = store.tools()
        pack_rows = [PackArtifact.model_validate(item) for item in store.packs()]
        registrations = store.registrations()
        graph_rows = _load_graphs(store)
        accepted = [tool for tool in tool_rows if tool["accepted"]]
        quality = [float(tool["governance"]["qualityScore"]) for tool in accepted]
        return DashboardView(
            sources=len(source_rows),
            operations=len(tool_rows),
            accepted_tools=len(accepted),
            rejected_operations=len(tool_rows) - len(accepted),
            clusters=len(clusters(tool_rows)),
            capability_graphs=len(graph_rows),
            zero_input_graphs=sum(graph.zero_input for graph in graph_rows),
            graph_coverage=round(
                len({node.tool_id for graph in graph_rows for node in graph.nodes}) / len(accepted), 3
            ) if accepted else 0,
            packs=len(pack_rows),
            ready_packs=sum(pack.status == "ready" for pack in pack_rows),
            registered_packs=len(registrations),
            average_quality=round(sum(quality) / len(quality), 3) if quality else 0,
            recent_packs=pack_rows[:5],
        )

    @api.get("/sources", response_model=list[ApiSourceView])
    async def sources() -> list[ApiSourceView]:
        return [ApiSourceView.model_validate(row) for row in store.sources()]

    @api.post("/sources", response_model=ApiSourceView, status_code=201)
    async def import_source(request: SourceImport) -> ApiSourceView:
        preferred = request.slug or slugify(request.name, "api")
        available = _available_slug(preferred, {row["slug"] for row in store.sources()})
        request = request.model_copy(update={"slug": available})
        source, tools = await intake.import_source(request)
        tools = await enricher.enrich(tools)
        store.save_source(source, tools)
        _refresh_graphs(store)
        return next(ApiSourceView.model_validate(row) for row in store.sources() if row["id"] == source["id"])

    @api.post("/sources/batch", response_model=list[ApiSourceView], status_code=201)
    async def import_batch(requests: list[SourceImport] = Body(min_length=1, max_length=50)) -> list[ApiSourceView]:
        imported = []
        for request in requests:
            imported.append(await import_source(request))
        return imported

    @api.get("/tools", response_model=list[ToolView])
    async def tools(accepted: bool | None = Query(default=None)) -> list[ToolView]:
        return [ToolView.model_validate(row) for row in store.tools(accepted=accepted)]

    @api.get("/clusters", response_model=list[ClusterView])
    async def semantic_clusters() -> list[ClusterView]:
        return clusters(store.tools(accepted=True))

    @api.get("/graphs")
    async def capability_graphs() -> list[dict[str, Any]]:
        return [graph.model_dump() for graph in _load_graphs(store)]

    @api.post("/graphs/rebuild")
    async def rebuild_capability_graphs() -> dict[str, Any]:
        graphs = _refresh_graphs(store)
        return {
            "graphs": len(graphs),
            "ready": sum(graph.status == "ready" for graph in graphs),
            "needsInput": sum(graph.status == "needs_input" for graph in graphs),
            "ambiguous": sum(graph.status == "ambiguous" for graph in graphs),
            "blocked": sum(graph.status == "blocked" for graph in graphs),
        }

    @api.post("/packs/recommend", response_model=PackRecommendation)
    async def recommend_pack(request: PackRecommendationRequest) -> PackRecommendation:
        return _recommend(request, store.tools(accepted=True), _load_graphs(store))

    @api.post("/packs/build", response_model=PackArtifact, status_code=201)
    async def build_pack(request: PackBuildRequest) -> PackArtifact:
        all_tools = store.tools()
        all_graphs = _load_graphs(store)
        graph_ids = list(request.graph_ids)
        tool_ids = list(request.tool_ids)
        if request.description and not graph_ids and not tool_ids and not request.cluster_keys:
            recommendation = _recommend(
                PackRecommendationRequest(description=request.description), all_tools, all_graphs
            )
            graph_ids = recommendation.graph_ids
            tool_ids = recommendation.tool_ids
        selected_graphs = [graph for graph in all_graphs if graph.id in set(graph_ids)]
        if len(selected_graphs) != len(set(graph_ids)):
            raise QualityGateError("能力图不存在或已因 API 池变化失效", "重新构建能力图并再次选择")
        invalid_graphs = [graph.name for graph in selected_graphs if graph.status in {"blocked", "ambiguous"}]
        if invalid_graphs:
            raise QualityGateError(
                "能力包包含尚未通过检查的能力图：" + "、".join(invalid_graphs),
                "解决 Provider 歧义或阻断测试后重新构图",
            )
        graph_tool_ids = {node.tool_id for graph in selected_graphs for node in graph.nodes}
        selected = pack_tools_from_clusters(
            all_tools, request.cluster_keys, list(set(tool_ids) | graph_tool_ids)
        )
        artifact = await compiler.compile(request, selected, selected_graphs)
        store.save_pack(artifact.model_dump())
        return artifact

    @api.get("/packs", response_model=list[PackArtifact])
    async def packs() -> list[PackArtifact]:
        return [PackArtifact.model_validate(item) for item in store.packs()]

    @api.get("/packs/{pack_id}", response_model=PackArtifact)
    async def pack(pack_id: str) -> PackArtifact:
        return PackArtifact.model_validate(store.pack(pack_id))

    @api.post("/packs/{pack_id}/register", response_model=RegistrationResult)
    async def register_pack(pack_id: str) -> RegistrationResult:
        artifact = PackArtifact.model_validate(store.pack(pack_id))
        result = await nacos.register(artifact)
        store.save_registration(result.model_dump())
        return result

    @api.get("/registrations", response_model=list[RegistrationResult])
    async def registrations() -> list[RegistrationResult]:
        return [RegistrationResult.model_validate(item) for item in store.registrations()]

    @api.get("/nacos/probe")
    async def nacos_probe() -> dict[str, Any]:
        return await nacos.probe()

    @api.get("/settings")
    async def public_settings() -> dict[str, Any]:
        return _settings_view(settings, runtime, enricher)

    @api.put("/settings")
    async def update_settings(
        request: RuntimeSettingsUpdate,
        x_gateforge_admin_token: str = Header(default=""),
    ) -> dict[str, Any]:
        _require_admin(settings, x_gateforge_admin_token)
        runtime.update(
            {
                "nacos_server_url": request.nacos.server_url,
                "nacos_context_path": request.nacos.context_path,
                "nacos_namespace": request.nacos.namespace,
                "nacos_username": request.nacos.username,
                "nacos_password": request.nacos.password,
                "ai_base_url": request.ai.base_url,
                "ai_model": request.ai.model,
                "ai_api_key": request.ai.api_key,
                "allowed_spec_hosts": ",".join(request.intake.allowed_spec_hosts),
                "allow_private_spec_hosts": request.intake.allow_private_spec_hosts,
                "l1_allow_origins": ",".join(request.intake.l1_allow_origins),
                "l1_allow_unsafe_methods": request.intake.l1_allow_unsafe_methods,
            },
            clear_secrets={
                key for key, clear in (
                    ("nacos_password", request.nacos.clear_password),
                    ("ai_api_key", request.ai.clear_api_key),
                ) if clear
            },
        )
        return _settings_view(settings, runtime, enricher)

    @api.post("/settings/test/nacos")
    async def test_nacos_connection(
        request: RuntimeSettingsUpdate,
        x_gateforge_admin_token: str = Header(default=""),
    ) -> dict[str, Any]:
        _require_admin(settings, x_gateforge_admin_token)
        candidate = settings.model_copy(deep=True)
        candidate.nacos_server_url = request.nacos.server_url
        candidate.nacos_context_path = request.nacos.context_path
        candidate.nacos_namespace = request.nacos.namespace
        candidate.nacos_username = request.nacos.username
        if request.nacos.password:
            candidate.nacos_password = request.nacos.password
        elif request.nacos.clear_password:
            candidate.nacos_password = ""
        return await NacosAdapter(candidate).probe()

    @api.post("/settings/test/ai")
    async def test_ai_connection(
        request: RuntimeSettingsUpdate,
        x_gateforge_admin_token: str = Header(default=""),
    ) -> dict[str, Any]:
        _require_admin(settings, x_gateforge_admin_token)
        candidate = settings.model_copy(deep=True)
        candidate.ai_base_url = request.ai.base_url
        candidate.ai_model = request.ai.model
        if request.ai.api_key:
            candidate.ai_api_key = request.ai.api_key
        elif request.ai.clear_api_key:
            candidate.ai_api_key = ""
        return await ToolEnricher(candidate).probe()

    return api


def _settings_view(
    settings: Settings,
    runtime: RuntimeSettings,
    enricher: ToolEnricher,
) -> dict[str, Any]:
    return {
        "nacos": {
            "serverUrl": settings.nacos_server_url,
            "contextPath": settings.nacos_context_path,
            "namespace": settings.nacos_namespace,
            "username": settings.nacos_username,
            "passwordConfigured": bool(settings.nacos_password),
            "passwordSaved": runtime.has_saved_secret("nacos_password"),
            "minVersion": settings.nacos_min_version,
        },
        "ai": {
            "baseUrl": settings.ai_base_url,
            "model": settings.ai_model,
            "apiKeyConfigured": bool(settings.ai_api_key),
            "apiKeySaved": runtime.has_saved_secret("ai_api_key"),
            "mode": enricher.mode,
        },
        "intake": {
            "allowedSpecHosts": sorted(settings.spec_host_allowlist),
            "allowPrivateSpecHosts": settings.allow_private_spec_hosts,
            "l1AllowOrigins": sorted(settings.l1_origin_allowlist),
            "l1AllowUnsafeMethods": settings.l1_allow_unsafe_methods,
        },
        "qualityThreshold": settings.quality_threshold,
        "adminWriteEnabled": bool(settings.admin_token),
        "boundaries": {
            "registry": "Nacos AI/MCP 注册中心",
            "runtime": "Higress",
            "dataPlane": False,
            "ownsLifecycle": False,
        },
    }


def _require_admin(settings: Settings, supplied: str) -> None:
    if not settings.admin_token:
        raise GateForgeError(
            "前端配置写入尚未启用",
            "在 Docker 环境变量中设置 GATEFORGE_ADMIN_TOKEN 后重启 GateForge",
            503,
        )
    if not supplied or not secrets.compare_digest(supplied, settings.admin_token):
        raise GateForgeError("管理令牌不正确", "填写部署时配置的 GateForge 管理令牌", 401)


def _available_slug(preferred: str, existing: set[str]) -> str:
    candidate = slugify(preferred, "api")
    index = 2
    while candidate in existing:
        candidate = f"{slugify(preferred, 'api')}-{index}"
        index += 1
    return candidate


def _refresh_graphs(store: Store) -> list[Any]:
    from gateforge.models import CapabilityGraph

    graphs = CapabilityGraphBuilder(store.tools(accepted=True)).build()
    store.replace_capability_graphs(graph.model_dump() for graph in graphs)
    return [CapabilityGraph.model_validate(row) for row in store.capability_graphs()]


def _load_graphs(store: Store) -> list[Any]:
    from gateforge.models import CapabilityGraph

    return [CapabilityGraph.model_validate(row) for row in store.capability_graphs()]


def _recommend(
    request: PackRecommendationRequest,
    tools: list[dict[str, Any]],
    graphs: list[Any],
) -> PackRecommendation:
    query_tokens = concept_tokens("", request.description)
    candidates: list[tuple[PackRecommendationItem, tuple[str, str, str]]] = []
    tool_by_id = {tool["id"]: tool for tool in tools}
    graph_node_ids: set[str] = set()
    for graph in graphs:
        if graph.status not in {"ready", "needs_input"}:
            continue
        tokens = concept_tokens(graph.name, graph.description + " " + graph.output_description)
        score = _semantic_score(query_tokens, tokens)
        if score > 0:
            terminal = tool_by_id.get(graph.terminal_tool_id)
            if terminal is None:
                continue
            candidates.append((PackRecommendationItem(
                kind="graph", id=graph.id, name=graph.name, description=graph.description,
                score=min(1.0, round(score + (0.08 if graph.zero_input else 0), 3)),
                reason=("零外部参数能力图；" if graph.zero_input else "已回溯补齐前置工具；")
                + graph.output_description,
            ), _origin(terminal)))
            graph_node_ids.update(node.tool_id for node in graph.nodes)
    for tool in tools:
        if not tool.get("accepted") or tool["id"] in graph_node_ids:
            continue
        standard = tool["standard"]
        tokens = concept_tokens(standard["name"], standard["description"])
        score = _semantic_score(query_tokens, tokens)
        if score > 0:
            candidates.append((PackRecommendationItem(
                kind="tool", id=tool["id"], name=standard["name"],
                description=standard["description"], score=round(score, 3),
                reason="原子工具可直接覆盖能力包目标描述",
            ), _origin(tool)))
    graph_candidates = {
        item.id: item for item, _ in candidates if item.kind == "graph"
    }
    redundant_subgraphs: set[str] = set()
    for graph in graphs:
        parent = graph_candidates.get(graph.id)
        if parent is None:
            continue
        for subgraph_id in graph.subgraph_ids:
            child = graph_candidates.get(subgraph_id)
            if child is not None and parent.score + 0.12 >= child.score:
                redundant_subgraphs.add(subgraph_id)
    candidates = [
        pair for pair in candidates
        if pair[0].kind != "graph" or pair[0].id not in redundant_subgraphs
    ]
    candidates.sort(key=lambda pair: (-pair[0].score, pair[0].kind, pair[0].name))
    if candidates:
        selected_origin = candidates[0][1]
        ranked = [item for item, origin in candidates if origin == selected_origin][:request.max_items]
    else:
        ranked = []
    if not ranked:
        fallback_tools = sorted(
            [item for item in tools if item.get("accepted")],
            key=lambda item: -float(item["governance"]["qualityScore"]),
        )
        fallback_origin = _origin(fallback_tools[0]) if fallback_tools else None
        ranked = [PackRecommendationItem(
            kind="tool", id=tool["id"], name=tool["standard"]["name"],
            description=tool["standard"]["description"], score=0.2,
            reason="没有高置信语义结果，提供质量最高的原子工具候选",
        ) for tool in fallback_tools if _origin(tool) == fallback_origin][:request.max_items]
    return PackRecommendation(
        description=request.description,
        items=ranked,
        graph_ids=[item.id for item in ranked if item.kind == "graph"],
        tool_ids=[item.id for item in ranked if item.kind == "tool"],
    )


def _origin(tool: dict[str, Any]) -> tuple[str, str, str]:
    endpoint = tool["endpoint"]
    return endpoint["protocol"], endpoint["address"], str(endpoint["port"])


def _semantic_score(query: set[str], candidate: set[str]) -> float:
    if not query or not candidate:
        return 0.0
    overlap = len(query & candidate)
    if not overlap:
        return 0.0
    return min(1.0, 0.7 * overlap / len(query) + 0.3 * overlap / len(candidate))
