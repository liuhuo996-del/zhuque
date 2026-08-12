from __future__ import annotations

from collections import defaultdict
from typing import Any

from gateforge.models import ClusterView


def clusters(tools: list[dict[str, Any]]) -> list[ClusterView]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for tool in tools:
        if tool["accepted"]:
            grouped[tool["cluster_key"]].append(tool)

    result = []
    for key, members in grouped.items():
        domains = [value for tool in members for value in tool["governance"]["domain"]]
        intents = [value for tool in members for value in tool["governance"]["intent"]]
        domain = max(set(domains), key=domains.count) if domains else "general"
        intent = max(set(intents), key=intents.count) if intents else "operate"
        agreement = (
            (domains.count(domain) / len(domains) if domains else 0.5)
            + (intents.count(intent) / len(intents) if intents else 0.5)
        ) / 2
        result.append(ClusterView(
            key=key,
            label=f"{domain} · {intent}",
            domain=domain,
            intent=intent,
            tool_ids=[tool["id"] for tool in members],
            tool_count=len(members),
            source_count=len({tool["source_id"] for tool in members}),
            confidence=round(min(0.98, 0.62 + agreement * 0.34), 3),
        ))
    return sorted(result, key=lambda item: (-item.tool_count, item.label))

