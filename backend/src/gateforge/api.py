from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, Query

from gateforge.clustering import clusters
from gateforge.compiler import PackCompiler, pack_tools_from_clusters
from gateforge.enrichment import ToolEnricher
from gateforge.errors import QualityGateError
from gateforge.intake import OpenApiIntake
from gateforge.models import (
    ApiSourceView,
    ClusterView,
    DashboardView,
    PackArtifact,
    PackBuildRequest,
    RegistrationResult,
    SourceImport,
    ToolView,
)
from gateforge.nacos import NacosAdapter
from gateforge.settings import Settings
from gateforge.store import Store
from gateforge.util import slugify


def router(store: Store, settings: Settings) -> APIRouter:
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
        accepted = [tool for tool in tool_rows if tool["accepted"]]
        quality = [float(tool["governance"]["qualityScore"]) for tool in accepted]
        return DashboardView(
            sources=len(source_rows),
            operations=len(tool_rows),
            accepted_tools=len(accepted),
            rejected_operations=len(tool_rows) - len(accepted),
            clusters=len(clusters(tool_rows)),
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

    @api.post("/packs/build", response_model=PackArtifact, status_code=201)
    async def build_pack(request: PackBuildRequest) -> PackArtifact:
        selected = pack_tools_from_clusters(store.tools(), request.cluster_keys, request.tool_ids)
        artifact = await compiler.compile(request, selected)
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
        return {
            "enrichmentMode": enricher.mode,
            "qualityThreshold": settings.quality_threshold,
            "nacos": {
                "serverUrl": settings.nacos_server_url,
                "namespace": settings.nacos_namespace,
                "minVersion": settings.nacos_min_version,
            },
            "boundaries": {
                "registry": "Nacos AI/MCP 注册中心",
                "runtime": "Higress",
                "dataPlane": False,
                "ownsLifecycle": False,
            },
        }

    return api


def _available_slug(preferred: str, existing: set[str]) -> str:
    candidate = slugify(preferred, "api")
    index = 2
    while candidate in existing:
        candidate = f"{slugify(preferred, 'api')}-{index}"
        index += 1
    return candidate
