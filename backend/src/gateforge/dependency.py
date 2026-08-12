from __future__ import annotations

from collections import defaultdict, deque
from typing import Any

from gateforge.models import ClosureReport, DependencyEdge, DependencyGraph, DependencyNode


CONTEXT_FIELDS = {"tenant_id", "current_user_id", "current_time", "request_id"}


def build_dependency_graph(tools: list[dict[str, Any]]) -> DependencyGraph:
    nodes: list[DependencyNode] = []
    providers: dict[str, list[str]] = defaultdict(list)
    for tool in tools:
        governance = tool["governance"]
        node = DependencyNode(
            tool_id=tool["id"],
            requires=list(dict.fromkeys(governance.get("requires", []))),
            provides=list(dict.fromkeys(governance.get("provides", []))),
        )
        nodes.append(node)
        for field in node.provides:
            providers[field].append(node.tool_id)

    edges: list[DependencyEdge] = []
    missing: dict[str, list[str]] = {}
    ambiguous: dict[str, dict[str, list[str]]] = {}
    adjacency: dict[str, set[str]] = defaultdict(set)
    indegree = {node.tool_id: 0 for node in nodes}
    for node in nodes:
        per_edge: dict[str, list[str]] = defaultdict(list)
        for requirement in node.requires:
            matches = providers.get(requirement, [])
            if not matches and requirement not in CONTEXT_FIELDS:
                missing.setdefault(node.tool_id, []).append(requirement)
            elif len(matches) > 1:
                ambiguous.setdefault(node.tool_id, {})[requirement] = matches
            elif len(matches) == 1 and matches[0] != node.tool_id:
                per_edge[matches[0]].append(requirement)
        for provider, fields in per_edge.items():
            edges.append(DependencyEdge(provider=provider, consumer=node.tool_id, fields=fields))
            adjacency[provider].add(node.tool_id)
            indegree[node.tool_id] += 1

    cycles = _cycles(adjacency, list(indegree))
    reachable = _reachable(adjacency, indegree)
    unreachable = sorted(set(indegree) - reachable)
    high_risk = [tool for tool in tools if tool["governance"]["riskLevel"] in {"high", "critical"}]
    permission_closed = all(
        (not tool["governance"]["approvalRequired"]) or bool(tool["governance"].get("approvalRole"))
        for tool in high_risk
    )
    risk_closed = all(tool["governance"]["approvalRequired"] for tool in high_risk)
    side_effect_closed = all(
        tool["governance"]["sideEffect"] != "unknown" for tool in tools
    )
    closure = ClosureReport(
        parameter_closed=not missing,
        type_closed=not ambiguous,
        permission_closed=permission_closed,
        risk_closed=risk_closed,
        side_effect_closed=side_effect_closed,
        cycles=cycles,
        missing_providers=missing,
        ambiguous_providers=ambiguous,
        unreachable_tools=unreachable,
    )
    return DependencyGraph(nodes=nodes, edges=edges, closure=closure)


def _reachable(adjacency: dict[str, set[str]], indegree: dict[str, int]) -> set[str]:
    queue = deque(node for node, degree in indegree.items() if degree == 0)
    seen = set(queue)
    while queue:
        current = queue.popleft()
        for target in adjacency.get(current, set()):
            if target not in seen:
                seen.add(target)
                queue.append(target)
    return seen


def _cycles(adjacency: dict[str, set[str]], nodes: list[str]) -> list[list[str]]:
    state: dict[str, int] = {}
    stack: list[str] = []
    result: list[list[str]] = []

    def visit(node: str) -> None:
        state[node] = 1
        stack.append(node)
        for target in adjacency.get(node, set()):
            if state.get(target, 0) == 0:
                visit(target)
            elif state.get(target) == 1:
                index = stack.index(target)
                cycle = stack[index:] + [target]
                if cycle not in result:
                    result.append(cycle)
        stack.pop()
        state[node] = 2

    for node in nodes:
        if state.get(node, 0) == 0:
            visit(node)
    return result

