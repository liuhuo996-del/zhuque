from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlparse

import httpx
from jsonschema import Draft202012Validator, SchemaError

from gateforge.models import DependencyGraph, TestCaseResult, TestReport
from gateforge.mcp_contract import validate_mcp_tool
from gateforge.settings import Settings
from gateforge.util import now_iso


class TestPipeline:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    async def run(
        self,
        tools: list[dict[str, Any]],
        graph: DependencyGraph,
        *,
        run_l1: bool,
        run_l2: bool,
    ) -> TestReport:
        cases: list[TestCaseResult] = []
        for tool in tools:
            cases.extend(self._l0(tool))
            cases.extend(await self._l1(tool, run_l1))
        cases.extend(self._closure_cases(graph))
        cases.extend(await self._l2(tools, run_l2))

        score_cases = [case for case in cases if case.status != "skip"]
        pass_rate = (
            sum(1 for case in score_cases if case.status in {"pass", "warn"}) / len(score_cases)
            if score_cases else 0.0
        )
        quality_score = (
            sum(case.score for case in score_cases) / len(score_cases) if score_cases else 0.0
        )
        blocking = sum(1 for case in cases if case.status == "fail")
        return TestReport(
            cases=cases,
            pass_rate=round(pass_rate, 3),
            quality_score=round(quality_score, 3),
            blocking_failures=blocking,
            generated_at=now_iso(),
        )

    def _l0(self, tool: dict[str, Any]) -> list[TestCaseResult]:
        name = tool["standard"]["name"]
        schema = tool["standard"]["inputSchema"]
        results = []
        contract_errors = validate_mcp_tool(tool["standard"])
        results.append(self._case(
            "L0", "mcp-contract", f"{name}:mcp-tool-contract",
            "pass" if not contract_errors else "fail", 1.0 if not contract_errors else 0.0,
            "符合 MCP 2025-06-18 Tool 定义" if not contract_errors else "; ".join(contract_errors),
        ))
        try:
            Draft202012Validator.check_schema(schema)
            schema_status = "pass"
            detail = "inputSchema 通过 JSON Schema 2020-12 元 schema 检查"
        except SchemaError as error:
            schema_status = "fail"
            detail = error.message
        results.append(self._case("L0", "schema", f"{name}:input-schema", schema_status,
                                  1.0 if schema_status == "pass" else 0.0, detail))

        required = schema.get("required", [])
        properties = schema.get("properties", {})
        boundary_ok = all(item in properties for item in required) and schema.get("type") == "object"
        results.append(self._case(
            "L0", "parameter-boundary", f"{name}:parameter-boundary",
            "pass" if boundary_ok else "fail", 1.0 if boundary_ok else 0.0,
            "必填参数均有定义且根类型为对象" if boundary_ok else "必填参数或根类型不合法",
        ))

        governance = tool["governance"]
        policy_ok = not (
            governance["riskLevel"] in {"high", "critical"}
            and not governance["approvalRequired"]
        )
        declared_auth = bool(tool["backend_mapping"].get("security"))
        auth_ok = not governance["write"] or declared_auth
        security_ok = policy_ok and auth_ok
        results.append(self._case(
            "L0", "security", f"{name}:risk-policy",
            "pass" if security_ok else "fail", 1.0 if security_ok else 0.0,
            "风险策略与后端认证声明完整" if security_ok else (
                "状态变更 API 未声明认证方式" if not auth_ok
                else "高风险工具未声明需要审批"
            ),
        ))
        permission_ok = not governance["approvalRequired"] or bool(governance.get("approvalRole"))
        results.append(self._case(
            "L0", "permission", f"{name}:approval-role",
            "pass" if permission_ok else "fail", 1.0 if permission_ok else 0.0,
            "审批角色完整" if permission_ok else "已声明需要审批，但审批角色为空",
        ))
        return results

    async def _l1(self, tool: dict[str, Any], enabled: bool) -> list[TestCaseResult]:
        name = tool["standard"]["name"]
        if not enabled:
            return [self._case("L1", "connectivity", f"{name}:connectivity", "skip", 0, "本次未启用 L1")]
        mapping = tool["backend_mapping"]
        method = mapping["method"]
        endpoint = tool["endpoint"]
        origin = f"{endpoint['protocol']}://{endpoint['address']}:{endpoint['port']}"
        if origin.rstrip("/") not in self.settings.l1_origin_allowlist:
            return [self._case(
                "L1", "connectivity", f"{name}:connectivity", "skip", 0,
                "后端地址不在 GATEFORGE_L1_ALLOW_ORIGINS 允许列表中，禁止自动出网",
            )]
        if method not in {"GET", "HEAD"} and not self.settings.l1_allow_unsafe_methods:
            return [self._case(
                "L1", "connectivity", f"{name}:connectivity", "skip", 0,
                "写操作默认禁止自动 L1；需显式开启并提供测试环境",
            )]
        path = mapping["requestTemplate"]["url"]
        if "{{" in path:
            return [self._case(
                "L1", "connectivity", f"{name}:connectivity", "skip", 0,
                "存在必填模板参数，尚未提供安全 fixture",
            )]
        url = origin + endpoint.get("basePath", "") + path
        try:
            async with httpx.AsyncClient(
                timeout=tool["governance"]["timeoutMs"] / 1000, trust_env=False
            ) as client:
                response = await client.request(method, url)
            ok = 200 <= response.status_code < 500
            connectivity = self._case(
                "L1", "connectivity", f"{name}:connectivity", "pass" if ok else "fail",
                1.0 if ok else 0.0, f"HTTP {response.status_code}", {"status": response.status_code},
            )
            response_case = self._response_case(name, tool, response)
            return [connectivity, response_case]
        except httpx.HTTPError as error:
            return [self._case(
                "L1", "connectivity", f"{name}:connectivity", "fail", 0, str(error)
            )]

    @staticmethod
    def _response_case(name: str, tool: dict[str, Any], response: httpx.Response) -> TestCaseResult:
        expected = tool["backend_mapping"].get("responseSchema") or {}
        try:
            payload = response.json()
        except ValueError:
            status = "warn" if not expected else "fail"
            return TestPipeline._case(
                "L1", "response-schema", f"{name}:response-schema", status,
                0.6 if status == "warn" else 0.0, "响应不是 JSON",
            )
        if not expected:
            return TestPipeline._case(
                "L1", "response-schema", f"{name}:response-schema", "warn", 0.75,
                "后端返回 JSON，但 OpenAPI 未提供响应 schema",
            )
        errors = list(Draft202012Validator(expected).iter_errors(payload))
        return TestPipeline._case(
            "L1", "response-schema", f"{name}:response-schema", "pass" if not errors else "fail",
            1.0 if not errors else 0.0, "响应符合结构规范" if not errors else errors[0].message,
        )

    async def _l2(self, tools: list[dict[str, Any]], enabled: bool) -> list[TestCaseResult]:
        if not enabled:
            return [self._case("L2", "semantic-selection", "pack:selection", "skip", 0, "本次未启用 L2")]
        if self.settings.ai_base_url and self.settings.ai_api_key and self.settings.ai_model:
            result = await self._ai_selection(tools)
            if result is not None:
                return [result]
        return [self._deterministic_selection(tools)]

    @staticmethod
    def _deterministic_selection(tools: list[dict[str, Any]]) -> TestCaseResult:
        names = [tool["standard"]["name"] for tool in tools]
        descriptions = [tool["standard"]["description"] for tool in tools]
        unique = len(names) == len(set(names))
        distinctive = True
        for index, current in enumerate(descriptions):
            current_words = set(re.findall(r"[a-z0-9_\u4e00-\u9fff]+", current.lower()))
            for other in descriptions[index + 1:]:
                other_words = set(re.findall(r"[a-z0-9_\u4e00-\u9fff]+", other.lower()))
                union = current_words | other_words
                similarity = len(current_words & other_words) / len(union) if union else 1
                if similarity > 0.82:
                    distinctive = False
        ok = unique and distinctive
        return TestPipeline._case(
            "L2", "semantic-selection", "pack:selection", "warn" if ok else "fail",
            0.75 if ok else 0.0,
            "未配置 AI，确定性检查显示工具名唯一且描述可区分" if ok
            else "存在重名工具或描述高度相似，模型可能选错工具",
            {"mode": "deterministic-fallback", "toolCount": len(tools)},
        )

    async def _ai_selection(self, tools: list[dict[str, Any]]) -> TestCaseResult | None:
        cases = []
        catalog = []
        for index, tool in enumerate(tools):
            name = tool["standard"]["name"]
            intent = "、".join(tool["governance"]["intent"])
            domain = "、".join(tool["governance"]["domain"])
            cases.append({
                "caseId": str(index),
                "prompt": f"我需要在{domain}领域执行{intent}任务：{tool['standard']['description']}",
                "expectedTool": name,
            })
            catalog.append(tool["standard"])
        payload = {
            "model": self.settings.ai_model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": "请为每个用例准确选择一个 MCP 工具，只返回 JSON。"},
                {"role": "user", "content": __import__("json").dumps({
                    "tools": catalog,
                    "cases": [{"caseId": case["caseId"], "prompt": case["prompt"]} for case in cases],
                    "output": {"selections": [{"caseId": "0", "toolName": "exact_name"}]},
                }, ensure_ascii=False)},
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.post(
                    self.settings.ai_base_url.rstrip("/") + "/chat/completions",
                    headers={"Authorization": f"Bearer {self.settings.ai_api_key}"},
                    json=payload,
                )
                response.raise_for_status()
                content = response.json()["choices"][0]["message"]["content"]
                selections = {
                    str(item["caseId"]): str(item["toolName"])
                    for item in __import__("json").loads(content).get("selections", [])
                }
        except (httpx.HTTPError, KeyError, TypeError, ValueError):
            return None
        correct = sum(selections.get(case["caseId"]) == case["expectedTool"] for case in cases)
        rate = correct / len(cases) if cases else 0.0
        return self._case(
            "L2", "semantic-selection", "pack:selection", "pass" if rate >= 0.9 else "fail",
            round(rate, 3), f"AI 工具选择准确率 {correct}/{len(cases)}",
            {"mode": "ai-zero-temperature", "correct": correct, "total": len(cases)},
        )

    @staticmethod
    def _closure_cases(graph: DependencyGraph) -> list[TestCaseResult]:
        closure = graph.closure
        checks = {
            "参数": closure.parameter_closed,
            "类型": closure.type_closed,
            "权限": closure.permission_closed,
            "风险": closure.risk_closed,
            "副作用": closure.side_effect_closed,
            "循环依赖": not closure.cycles,
            "可达性": not closure.unreachable_tools,
        }
        return [TestPipeline._case(
            "L0", "dependency-closure", f"pack:closure:{name}", "pass" if ok else "fail",
            1.0 if ok else 0.0, f"{name}闭包{'完整' if ok else '不完整'}",
        ) for name, ok in checks.items()]

    @staticmethod
    def _case(
        layer: str,
        category: str,
        case_id: str,
        status: str,
        score: float,
        detail: str,
        evidence: dict[str, Any] | None = None,
    ) -> TestCaseResult:
        return TestCaseResult(
            layer=layer, category=category, case_id=case_id,
            status=status, score=score, detail=detail, evidence=evidence or {},
        )
