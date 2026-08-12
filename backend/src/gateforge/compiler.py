from __future__ import annotations

from collections import defaultdict
from typing import Any

from gateforge.dependency import build_dependency_graph
from gateforge.errors import QualityGateError
from gateforge.models import PackArtifact, PackBuildRequest
from gateforge.settings import Settings
from gateforge.testing import TestPipeline
from gateforge.util import digest, new_id, now_iso, slugify


class PackCompiler:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.tests = TestPipeline(settings)

    async def compile(self, request: PackBuildRequest, tools: list[dict[str, Any]]) -> PackArtifact:
        if not tools:
            raise QualityGateError("能力包没有可编译的工具", "选择至少一个已通过分析器检查的工具")
        rejected = [tool["standard"]["name"] for tool in tools if not tool["accepted"]]
        if rejected:
            raise QualityGateError(
                "能力包包含已被分析器拒绝的 API：" + ", ".join(rejected[:8]),
                "从能力包中移除，或先修复 API 定义后重新导入",
            )
        origins = {
            (tool["endpoint"]["protocol"], tool["endpoint"]["address"], tool["endpoint"]["port"])
            for tool in tools
        }
        if len(origins) != 1:
            raise QualityGateError(
                "一个 Nacos API→MCP Server 目前只能绑定一个后端 origin",
                "按后端服务边界拆成多个 MCP Pack，或先通过 API Gateway 统一 origin",
            )

        graph = build_dependency_graph(tools)
        report = await self.tests.run(tools, graph, run_l1=request.run_l1, run_l2=request.run_l2)
        for tool in tools:
            tool["governance"]["testPassRate"] = report.pass_rate
            prior = float(tool["governance"]["qualityScore"])
            tool["governance"]["qualityScore"] = round(prior * 0.65 + report.quality_score * 0.35, 3)

        first = tools[0]
        cluster_keys = list(dict.fromkeys(tool["cluster_key"] for tool in tools))
        default_name = " + ".join(self._cluster_name(tool) for tool in tools[:2]) + " MCP 能力包"
        name = request.name or default_name
        slug = request.slug or slugify(name, "mcp-pack")
        description = request.description or self._description(tools)
        endpoint = first["endpoint"]
        mcp_server = {
            "name": f"mcp-{slug}",
            "description": description,
            "protocol": endpoint["protocol"],
            "frontProtocol": "mcp-sse",
            "capabilities": ["TOOL"],
        }
        standard_tools = [tool["standard"] for tool in tools]
        mappings = {tool["standard"]["name"]: self._runtime_mapping(tool) for tool in tools}
        governance = {
            "schemaVersion": "gateforge.governance-bundle/v1",
            "digest": digest({
                tool["standard"]["name"]: tool["governance"] for tool in tools
            }),
            "tools": {tool["standard"]["name"]: tool["governance"] for tool in tools},
        }
        generated_at = now_iso()
        manifest = {
            "schemaVersion": "gateforge.build-manifest/v1",
            "compiler": {"name": "GateForge", "version": "0.2.0"},
            "mcpProtocolProfile": "2025-06-18+higress-2.2.3",
            "sourceHashes": sorted({tool["source_spec_hash"] for tool in tools}),
            "clusterKeys": cluster_keys,
            "toolFingerprints": {
                tool["standard"]["name"]: tool["fingerprint"] for tool in tools
            },
            "generatedAt": generated_at,
        }
        status = "ready" if (
            report.blocking_failures == 0
            and report.quality_score >= self.settings.quality_threshold
        ) else "blocked"
        pack_id = new_id()
        content = {
            "schema_version": "gateforge.mcp-pack/v1",
            "id": pack_id,
            "name": name,
            "slug": slug,
            "description": description,
            "created_at": generated_at,
            "status": status,
            "mcp_server": mcp_server,
            "tools": standard_tools,
            "backend_mappings": mappings,
            "endpoints": [endpoint],
            "governance": governance,
            "dependency_graph": graph.model_dump(),
            "test_report": report.model_dump(),
            "build_manifest": manifest,
        }
        content["artifact_hash"] = digest(content)
        return PackArtifact.model_validate(content)

    @staticmethod
    def _description(tools: list[dict[str, Any]]) -> str:
        domains = []
        intents = []
        for tool in tools:
            domains.extend(tool["governance"]["domain"])
            intents.extend(tool["governance"]["intent"])
        domain_text = "、".join(list(dict.fromkeys(domains))[:3]) or "通用业务"
        intent_text = "、".join(list(dict.fromkeys(intents))[:5]) or "API 操作"
        return f"面向 {domain_text} 领域的 MCP 能力包，提供 {intent_text} 等经过治理与测试的工具。"

    @staticmethod
    def _cluster_name(tool: dict[str, Any]) -> str:
        domain = (tool["governance"].get("domain") or ["通用"])[0]
        intent = (tool["governance"].get("intent") or ["操作"])[0]
        intent = {
            "search": "查询", "read": "读取", "create": "创建", "update": "更新",
            "delete": "删除", "execute": "执行", "operate": "操作",
        }.get(intent, intent)
        return f"{domain}·{intent}"

    @staticmethod
    def _runtime_mapping(tool: dict[str, Any]) -> dict[str, Any]:
        mapping = dict(tool["backend_mapping"])
        mapping.pop("responseSchema", None)
        base_path = str(tool["endpoint"].get("basePath", "")).rstrip("/")
        template = dict(mapping["requestTemplate"])
        template["url"] = base_path + str(template["url"])
        mapping["requestTemplate"] = template
        return mapping


def pack_tools_from_clusters(
    all_tools: list[dict[str, Any]], cluster_keys: list[str], tool_ids: list[str]
) -> list[dict[str, Any]]:
    selected_ids = set(tool_ids)
    selected_clusters = set(cluster_keys)
    return [
        tool for tool in all_tools
        if tool["id"] in selected_ids or tool["cluster_key"] in selected_clusters
    ]
