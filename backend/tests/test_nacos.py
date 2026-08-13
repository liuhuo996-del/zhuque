from __future__ import annotations

import json
from pathlib import Path
from urllib.parse import parse_qs

import httpx
import pytest

from gateforge.compiler import PackCompiler
from gateforge.intake import OpenApiIntake
from gateforge.models import PackBuildRequest, SourceImport
from gateforge.nacos import NacosAdapter
from gateforge.settings import Settings


FIXTURE = Path(__file__).parent / "fixtures" / "orders-openapi.yaml"


@pytest.mark.asyncio
async def test_nacos_adapter_uses_official_ai_mcp_admin_api() -> None:
    detail_reads = 0
    captured: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal detail_reads
        if request.url.path.endswith("/v3/admin/core/state"):
            return httpx.Response(200, json={"code": 0, "data": {"version": "3.0.1"}})
        if request.url.path.endswith("/v3/admin/ai/mcp") and request.method == "GET":
            detail_reads += 1
            if detail_reads == 1:
                return httpx.Response(404, json={"code": 20004, "message": "not found"})
            expected = "1.0.0-abc"
            tool_spec: dict[str, object] = {"toolsMeta": {}}
            if "serverSpecification" in captured:
                expected = json.loads(str(captured["serverSpecification"]))["versionDetail"]["version"]
                tool_spec = json.loads(str(captured["toolSpecification"]))
            return httpx.Response(200, json={"code": 0, "data": {
                "id": "nacos-server-id",
                "name": "mcp-order-search",
                "enabled": True,
                "capabilities": ["TOOL"],
                "versionDetail": {"version": expected},
                "toolSpec": tool_spec,
            }})
        if request.url.path.endswith("/v3/admin/ai/mcp") and request.method == "POST":
            captured.update({key: values[0] for key, values in parse_qs(request.content.decode()).items()})
            return httpx.Response(200, json={"code": 0, "message": "success"})
        return httpx.Response(500, json={"code": 500, "message": "unexpected"})

    settings = Settings(database_path=Path(":memory:"), quality_threshold=0.5)
    _, tools = await OpenApiIntake(settings).import_source(SourceImport(
        name="Orders", spec_text=FIXTURE.read_text(), owner="commerce-platform"
    ))
    selected = [tool for tool in tools if tool["accepted"] and tool["governance"]["intent"] == ["search"]]
    artifact = await PackCompiler(settings).compile(
        PackBuildRequest(name="Order Search", slug="order-search", tool_ids=[tool["id"] for tool in selected]),
        selected,
    )
    adapter = NacosAdapter(settings, httpx.MockTransport(handler))
    result = await adapter.register(artifact)

    assert result.nacos_server_id == "nacos-server-id"
    assert captured["namespaceId"] == "public"
    assert captured["mcpName"] == "mcp-order-search"
    server = json.loads(str(captured["serverSpecification"]))
    tool_spec = json.loads(str(captured["toolSpecification"]))
    assert server["frontProtocol"] == "mcp-sse"
    assert server["capabilities"] == ["TOOL"]
    tool = tool_spec["tools"][0]
    assert set(tool) == {"name", "description", "inputSchema"}
    assert "riskLevel" not in tool
    meta = tool_spec["toolsMeta"][tool["name"]]
    sidecar = json.loads(meta["invokeContext"]["com.gateforge/governance"])
    assert sidecar["digest"].startswith("sha256:")
    assert sidecar["policy"]["schemaVersion"] == "gateforge.governance/v1"
    assert meta["invokeContext"]["com.gateforge/packHash"] == artifact.artifact_hash
    assert json.loads(meta["invokeContext"]["com.gateforge/capabilityGraphs"]) == []
    assert set(meta["templates"]["json-go-template"]["argsPosition"].values()) <= {
        "path", "query", "header", "body"
    }
    assert "json-go-template" in meta["templates"]
