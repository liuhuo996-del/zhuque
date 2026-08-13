from __future__ import annotations

from pathlib import Path

import pytest

from gateforge.clustering import clusters
from gateforge.compiler import PackCompiler, pack_tools_from_clusters
from gateforge.intake import OpenApiIntake
from gateforge.models import PackBuildRequest, SourceImport
from gateforge.settings import Settings


FIXTURE = Path(__file__).parent / "fixtures" / "orders-openapi.yaml"


@pytest.mark.asyncio
async def test_intake_analyzes_filters_and_separates_governance() -> None:
    intake = OpenApiIntake(Settings(database_path=Path(":memory:")))
    source, tools = await intake.import_source(SourceImport(
        name="Orders",
        spec_text=FIXTURE.read_text(),
        environment="test",
        owner="commerce-platform",
    ))

    assert source["slug"] == "orders"
    assert len(tools) == 4
    accepted = [tool for tool in tools if tool["accepted"]]
    rejected = [tool for tool in tools if not tool["accepted"]]
    assert len(accepted) == 3
    assert rejected[0]["rejection_reasons"] == ["operational-endpoint"]

    cancel = next(tool for tool in accepted if tool["standard"]["name"] == "orders_cancel_order")
    assert set(cancel["standard"]) == {"name", "description", "inputSchema", "annotations"}
    assert "riskLevel" not in cancel["standard"]
    assert cancel["governance"]["riskLevel"] == "high"
    assert cancel["governance"]["approvalRequired"] is True
    assert cancel["governance"]["approvalRole"] == "order-manager"
    assert cancel["backend_mapping"]["requestTemplate"]["url"] == "/orders/{{.args.orderId}}/cancel"


@pytest.mark.asyncio
async def test_cluster_and_compile_produce_governed_mcp_pack() -> None:
    settings = Settings(database_path=Path(":memory:"), quality_threshold=0.5)
    source, tools = await OpenApiIntake(settings).import_source(SourceImport(
        name="Orders",
        spec_text=FIXTURE.read_text(),
        owner="commerce-platform",
    ))
    groups = clusters(tools)
    search_cluster = next(group for group in groups if group.intent == "search")
    selected = pack_tools_from_clusters(tools, [search_cluster.key], [])
    artifact = await PackCompiler(settings).compile(
        PackBuildRequest(cluster_keys=[search_cluster.key], name="Order Search", run_l2=True),
        selected,
    )

    assert artifact.schema_version == "gateforge.mcp-pack/v2"
    assert artifact.status == "ready"
    assert artifact.tools
    assert artifact.mcp_server["frontProtocol"] == "mcp-sse"
    tool_name = artifact.tools[0]["name"]
    assert artifact.backend_mappings[tool_name]["requestTemplate"]["url"].startswith("/v1/")
    assert artifact.governance["schemaVersion"] == "gateforge.governance-bundle/v1"
    assert artifact.governance["digest"].startswith("sha256:")
    assert artifact.build_manifest["sourceHashes"] == [source["spec_hash"]]
    assert artifact.dependency_graph.schema_version == "gateforge.dependency-graph/v1"
    assert artifact.test_report.schema_version == "gateforge.test-report/v1"
    assert artifact.artifact_hash.startswith("sha256:")


@pytest.mark.asyncio
async def test_runtime_profile_rejects_schema_that_higress_would_silently_weaken() -> None:
    specification = """
openapi: 3.0.3
info: {title: Payment, version: 1.0.0}
servers: [{url: https://payments.example.com}]
paths:
  /quote:
    post:
      operationId: createQuote
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                value:
                  oneOf: [{type: string}, {type: integer}]
      responses: {'200': {description: ok}}
"""
    _, tools = await OpenApiIntake(Settings(database_path=Path(":memory:"))).import_source(
        SourceImport(name="Payment", spec_text=specification)
    )
    assert tools[0]["accepted"] is False
    assert "higress-runtime-profile-incompatible" in tools[0]["rejection_reasons"]
