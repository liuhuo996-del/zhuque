from __future__ import annotations

import re
from collections import defaultdict
from copy import deepcopy
from typing import Any

from jsonschema import Draft202012Validator, SchemaError

from gateforge.models import (
    CapabilityGraph,
    CapabilityGraphEdge,
    CapabilityGraphIssue,
    CapabilityGraphNode,
    FieldPort,
    GraphTestReport,
    TestCaseResult,
)
from gateforge.util import digest


ALIASES = {
    "id": "identifier", "identifier": "identifier", "uuid": "identifier",
    "编号": "identifier", "标识": "identifier", "唯一": "identifier",
    "order": "order", "订单": "order",
    "customer": "customer", "client": "customer", "客户": "customer",
    "user": "user", "用户": "user",
    "payment": "payment", "支付": "payment",
    "refund": "refund", "退款": "refund",
    "cancel": "cancellation", "cancellation": "cancellation", "取消": "cancellation",
    "status": "status", "state": "status", "状态": "status",
    "token": "token", "令牌": "token",
    "tenant": "tenant", "租户": "tenant",
}

CHINESE_STOPWORDS = {
    "什么", "如何", "这个", "那个", "进行", "通过", "需要", "用于", "返回",
    "接口", "工具", "能力", "自动", "可以", "一个", "结果", "信息",
}


class CapabilityGraphBuilder:
    """从 API 池的输入/输出字段端口反向构建最小能力图。"""

    def __init__(self, tools: list[dict[str, Any]]) -> None:
        self.tools = [tool for tool in tools if tool.get("accepted")]
        self.by_id = {tool["id"]: tool for tool in self.tools}
        self.inputs = {tool["id"]: input_ports(tool) for tool in self.tools}
        self.outputs = {tool["id"]: output_ports(tool) for tool in self.tools}
        self.output_index: dict[str, list[tuple[str, FieldPort]]] = defaultdict(list)
        for tool in self.tools:
            for port in self.outputs[tool["id"]]:
                for token in concept_tokens(port.name, port.description):
                    self.output_index[token].append((tool["id"], port))

    def build(self) -> list[CapabilityGraph]:
        graphs = [graph for tool in self.tools if (graph := self._build_for(tool)) is not None]
        graph_by_terminal = {graph.terminal_tool_id: graph for graph in graphs}
        result = []
        for graph in graphs:
            subgraphs = []
            graph_nodes = {node.tool_id for node in graph.nodes}
            for node in graph.nodes:
                candidate = graph_by_terminal.get(node.tool_id)
                if candidate and candidate.id != graph.id:
                    candidate_nodes = {item.tool_id for item in candidate.nodes}
                    if candidate_nodes < graph_nodes:
                        subgraphs.append(candidate.id)
            result.append(graph.model_copy(update={"subgraph_ids": sorted(set(subgraphs))}))
        return sorted(result, key=lambda item: (-len(item.nodes), item.name))

    def _build_for(self, terminal: dict[str, Any]) -> CapabilityGraph | None:
        nodes: set[str] = {terminal["id"]}
        edges: list[CapabilityGraphEdge] = []
        issues: list[CapabilityGraphIssue] = []
        external: list[tuple[str, FieldPort]] = []

        def resolve(tool_id: str, stack: tuple[str, ...], depth: int) -> None:
            tool = self.by_id[tool_id]
            for port in self.inputs[tool_id]:
                ranked = self._rank_providers(tool, port, set(stack))
                chosen = ranked[0] if ranked else None
                ambiguous = len(ranked) > 1 and ranked[0][2] - ranked[1][2] < 0.08
                if chosen and chosen[2] >= 0.72 and not ambiguous and depth < 6 and len(nodes) < 12:
                    provider_id, output, score, evidence = chosen
                    nodes.add(provider_id)
                    edges.append(CapabilityGraphEdge(
                        provider_tool_id=provider_id,
                        consumer_tool_id=tool_id,
                        output_path=output.path,
                        input_path=port.path,
                        concept=port.concept,
                        confidence=score,
                        evidence=evidence,
                    ))
                    resolve(provider_id, stack + (provider_id,), depth + 1)
                else:
                    external.append((tool_id, port))
                    if ambiguous:
                        issues.append(CapabilityGraphIssue(
                            level="warning", code="ambiguous-provider", tool_id=tool_id,
                            input_path=port.path,
                            detail=f"{port.name} 存在多个分数接近的输出来源，保留为能力图外部输入",
                        ))

        resolve(terminal["id"], (terminal["id"],), 0)
        if not edges and not issues:
            return None

        input_schema = external_input_schema(external, self.by_id)
        output_schema = deepcopy(terminal["backend_mapping"].get("responseSchema") or {})
        confidence = round(sum(edge.confidence for edge in edges) / len(edges), 3) if edges else 0.0
        graph_id = "graph-" + digest({
            "terminal": terminal["fingerprint"],
            "edges": [edge.model_dump() for edge in edges],
        }).split(":", 1)[1][:20]
        name = graph_name(terminal, len(nodes))
        output_description = describe_output(terminal, self.outputs[terminal["id"]])
        execution_order = topological_order(nodes, edges, self.by_id)
        description = graph_description(
            terminal, execution_order, input_schema, output_description, self.by_id
        )
        governance = graph_governance([self.by_id[node] for node in nodes])
        test_report = graph_tests(
            graph_id, edges, execution_order, input_schema, output_schema, governance, issues
        )
        status = "blocked" if test_report.blocking_failures else (
            "ambiguous" if any(issue.code == "ambiguous-provider" for issue in issues)
            else "ready" if not input_schema.get("required") else "needs_input"
        )
        return CapabilityGraph(
            id=graph_id,
            name=name,
            description=description,
            output_description=output_description,
            terminal_tool_id=terminal["id"],
            terminal_tool_name=terminal["standard"]["name"],
            nodes=[CapabilityGraphNode(
                tool_id=node_id,
                tool_name=self.by_id[node_id]["standard"]["name"],
                role="terminal" if node_id == terminal["id"] else "provider",
            ) for node_id in execution_order],
            edges=edges,
            execution_order=execution_order,
            input_schema=input_schema,
            output_schema=output_schema,
            zero_input=not bool(input_schema.get("required")),
            confidence=confidence,
            status=status,
            governance=governance,
            issues=issues,
            test_report=test_report,
        )

    def _rank_providers(
        self,
        consumer: dict[str, Any],
        required: FieldPort,
        stack: set[str],
    ) -> list[tuple[str, FieldPort, float, list[str]]]:
        candidate_pairs: dict[tuple[str, str], tuple[str, FieldPort]] = {}
        required_tokens = concept_tokens(required.name, required.description)
        for token in required_tokens:
            for provider_id, output in self.output_index.get(token, []):
                candidate_pairs[(provider_id, output.path)] = (provider_id, output)

        ranked = []
        for provider_id, output in candidate_pairs.values():
            if provider_id in stack or provider_id == consumer["id"]:
                continue
            provider = self.by_id[provider_id]
            if origin_key(provider) != origin_key(consumer):
                continue
            if provider["governance"]["write"] or provider["governance"]["destructive"]:
                continue
            if output.cardinality != "one" or not schema_compatible(output.schema_def, required.schema_def):
                continue
            provider_inputs = self.inputs[provider_id]
            if any(
                concept_similarity(item.name, item.description, required.name, required.description) >= 0.85
                for item in provider_inputs
            ):
                continue
            semantic = concept_similarity(output.name, output.description, required.name, required.description)
            if semantic <= 0:
                continue
            same_domain = bool(
                set(provider["governance"]["domain"]) & set(consumer["governance"]["domain"])
            )
            exact = normalize_name(output.name) == normalize_name(required.name)
            score = min(1.0, 0.45 * semantic + 0.25 + (0.12 if same_domain else 0) + (0.22 if exact else 0))
            evidence = ["JSON Schema 类型兼容", "输出为单值"]
            if same_domain:
                evidence.append("业务领域一致")
            evidence.append("字段名完全一致" if exact else "字段语义相似")
            ranked.append((provider_id, output, round(score, 3), evidence))
        return sorted(ranked, key=lambda item: (-item[2], item[0], item[1].path))[:8]


def input_ports(tool: dict[str, Any]) -> list[FieldPort]:
    schema = tool["standard"].get("inputSchema") or {}
    required = set(schema.get("required") or [])
    result = []
    for name in required:
        value = (schema.get("properties") or {}).get(name) or {}
        description = str(value.get("description") or name)
        result.append(FieldPort(
            path="/" + pointer_escape(name), name=name, description=description,
            schema=deepcopy(value), concept=concept_name(name, description), required=True,
        ))
    return result


def output_ports(tool: dict[str, Any]) -> list[FieldPort]:
    schema = tool["backend_mapping"].get("responseSchema") or {}
    result: list[FieldPort] = []

    def visit(value: Any, path: str, inherited_description: str, many: bool) -> None:
        if not isinstance(value, dict):
            return
        description = str(value.get("description") or inherited_description)
        if value.get("type") == "array" and isinstance(value.get("items"), dict):
            visit(value["items"], path + "/*", description, True)
            return
        properties = value.get("properties")
        if isinstance(properties, dict) and properties:
            for name, child in properties.items():
                visit(child, path + "/" + pointer_escape(str(name)), str((child or {}).get("description") or name), many)
            return
        if path:
            name = path.rsplit("/", 1)[-1].replace("~1", "/").replace("~0", "~")
            result.append(FieldPort(
                path=path, name=name, description=description or name,
                schema=deepcopy(value), concept=concept_name(name, description),
                cardinality="many" if many else "one",
            ))

    visit(schema, "", "", False)
    return result[:300]


def external_input_schema(
    external: list[tuple[str, FieldPort]], tools: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    properties: dict[str, Any] = {}
    required: list[str] = []
    for tool_id, port in external:
        name = port.name
        if name in properties and properties[name] != port.schema_def:
            name = tools[tool_id]["standard"]["name"] + "__" + name
        properties[name] = deepcopy(port.schema_def)
        properties[name].setdefault("description", port.description)
        required.append(name)
    schema: dict[str, Any] = {
        "type": "object", "properties": properties, "additionalProperties": False,
    }
    if required:
        schema["required"] = list(dict.fromkeys(required))
    return schema


def schema_compatible(output: dict[str, Any], required: dict[str, Any]) -> bool:
    source = output.get("type")
    target = required.get("type")
    if isinstance(source, list):
        source = next((item for item in source if item != "null"), None)
    if isinstance(target, list):
        target = next((item for item in target if item != "null"), None)
    if not source or not target:
        return True
    if source == target or (source == "integer" and target == "number"):
        source_format, target_format = output.get("format"), required.get("format")
        return not source_format or not target_format or source_format == target_format
    return False


def concept_tokens(name: str, description: str) -> set[str]:
    text = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", f"{name} {description}").lower()
    raw = set(re.findall(r"[a-z0-9]+", text))
    for phrase in re.findall(r"[\u4e00-\u9fff]+", text):
        if phrase not in CHINESE_STOPWORDS:
            raw.add(phrase)
        for size in (2, 3):
            raw.update(
                phrase[index:index + size]
                for index in range(max(0, len(phrase) - size + 1))
                if phrase[index:index + size] not in CHINESE_STOPWORDS
            )
    for word, canonical in ALIASES.items():
        if word in text:
            raw.add(canonical)
    return {ALIASES.get(token, token) for token in raw if len(token) > 1 or token.isdigit()}


def concept_name(name: str, description: str) -> str:
    tokens = sorted(concept_tokens(name, description))
    return ".".join(tokens[:8]) or normalize_name(name)


def concept_similarity(a_name: str, a_desc: str, b_name: str, b_desc: str) -> float:
    a, b = concept_tokens(a_name, a_desc), concept_tokens(b_name, b_desc)
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def normalize_name(value: str) -> str:
    return re.sub(r"[^a-z0-9\u4e00-\u9fff]", "", value.lower())


def origin_key(tool: dict[str, Any]) -> tuple[str, str, str]:
    endpoint = tool["endpoint"]
    return endpoint["protocol"], endpoint["address"], str(endpoint["port"])


def graph_name(terminal: dict[str, Any], count: int) -> str:
    summary = terminal["standard"]["description"].split("。", 1)[0].removeprefix("何时使用：")
    return f"{summary}（{count} 步自动能力图）"


def describe_output(terminal: dict[str, Any], ports: list[FieldPort]) -> str:
    fields = "、".join(port.description or port.name for port in ports[:8])
    return f"最终返回{fields}" if fields else f"返回 {terminal['standard']['name']} 的执行结果"


def graph_description(
    terminal: dict[str, Any], execution_order: list[str],
    input_schema: dict[str, Any], output_description: str, tools: dict[str, dict[str, Any]],
) -> str:
    ordered = [tools[tool_id]["standard"]["name"] for tool_id in execution_order]
    inputs = input_schema.get("required") or []
    input_text = "无需外部参数" if not inputs else "仍需提供：" + "、".join(inputs)
    return f"自动执行 {' → '.join(dict.fromkeys(ordered))}；{input_text}；{output_description}。"


def topological_order(
    nodes: set[str], edges: list[CapabilityGraphEdge], tools: dict[str, dict[str, Any]],
) -> list[str]:
    downstream: dict[str, set[str]] = defaultdict(set)
    indegree = {node: 0 for node in nodes}
    for edge in edges:
        if edge.consumer_tool_id not in downstream[edge.provider_tool_id]:
            downstream[edge.provider_tool_id].add(edge.consumer_tool_id)
            indegree[edge.consumer_tool_id] += 1
    ready = sorted(
        (node for node, degree in indegree.items() if degree == 0),
        key=lambda node: tools[node]["standard"]["name"],
    )
    ordered: list[str] = []
    while ready:
        node = ready.pop(0)
        ordered.append(node)
        for consumer in sorted(
            downstream[node], key=lambda item: tools[item]["standard"]["name"]
        ):
            indegree[consumer] -= 1
            if indegree[consumer] == 0:
                ready.append(consumer)
                ready.sort(key=lambda item: tools[item]["standard"]["name"])
    return ordered if len(ordered) == len(nodes) else sorted(nodes)


def graph_governance(tools: list[dict[str, Any]]) -> dict[str, Any]:
    order = {"low": 0, "medium": 1, "high": 2, "critical": 3}
    highest = max(tools, key=lambda item: order[item["governance"]["riskLevel"]])["governance"]
    return {
        "riskLevel": highest["riskLevel"],
        "approvalRequired": any(tool["governance"]["approvalRequired"] for tool in tools),
        "approvalRoles": sorted({
            tool["governance"]["approvalRole"] for tool in tools
            if tool["governance"].get("approvalRole")
        }),
        "sideEffect": highest["sideEffect"],
        "timeoutMs": sum(int(tool["governance"]["timeoutMs"]) for tool in tools),
    }


def graph_tests(
    graph_id: str, edges: list[CapabilityGraphEdge], execution_order: list[str], input_schema: dict[str, Any],
    output_schema: dict[str, Any], governance: dict[str, Any],
    issues: list[CapabilityGraphIssue],
) -> GraphTestReport:
    cases: list[TestCaseResult] = []
    for label, schema in (("输入", input_schema), ("输出", output_schema)):
        try:
            Draft202012Validator.check_schema(schema)
            status, detail = "pass", f"能力图{label} Schema 合法"
        except SchemaError as error:
            status, detail = "fail", error.message
        cases.append(TestCaseResult(
            layer="L0", category="graph-schema", case_id=f"{graph_id}:{label}",
            status=status, score=1 if status == "pass" else 0, detail=detail,
        ))
    has_ambiguity = any(issue.code == "ambiguous-provider" for issue in issues)
    edge_status = "pass" if edges else "warn" if has_ambiguity else "fail"
    cases.append(TestCaseResult(
        layer="L0", category="graph-edge", case_id=f"{graph_id}:edges",
        status=edge_status, score=1 if edges else 0,
        detail=(f"{len(edges)} 条边均通过类型、数量关系与语义置信度检查"
                if edges else "Provider 存在歧义，未自动生成字段边"),
    ))
    positions = {tool_id: index for index, tool_id in enumerate(execution_order)}
    ordered = bool(execution_order) and all(
        positions.get(edge.provider_tool_id, -1) < positions.get(edge.consumer_tool_id, -1)
        for edge in edges
    )
    order_status = "pass" if ordered else "warn" if has_ambiguity else "fail"
    cases.append(TestCaseResult(
        layer="L0", category="graph-order", case_id=f"{graph_id}:order",
        status=order_status, score=1 if ordered else 0,
        detail=("已生成无环的确定性执行顺序" if ordered else
                "Provider 歧义解决后才能生成执行顺序" if has_ambiguity else
                "执行顺序中存在环或逆向依赖"),
    ))
    permission_ok = not governance["approvalRequired"] or bool(governance["approvalRoles"])
    cases.append(TestCaseResult(
        layer="L0", category="graph-governance", case_id=f"{graph_id}:governance",
        status="pass" if permission_ok else "fail", score=1 if permission_ok else 0,
        detail="风险与审批策略已沿图传播" if permission_ok else "图需要审批但缺少审批角色",
    ))
    blocking = sum(case.status == "fail" for case in cases) + sum(
        issue.level == "blocking" for issue in issues
    )
    return GraphTestReport(
        cases=cases,
        pass_rate=round(sum(case.status == "pass" for case in cases) / len(cases), 3),
        blocking_failures=blocking,
    )


def pointer_escape(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")
