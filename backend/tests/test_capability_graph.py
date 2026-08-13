from __future__ import annotations

from typing import Any

from gateforge.capability_graph import CapabilityGraphBuilder


def tool(
    tool_id: str,
    name: str,
    *,
    inputs: dict[str, dict[str, Any]] | None = None,
    output: dict[str, Any] | None = None,
) -> dict[str, Any]:
    inputs = inputs or {}
    return {
        "id": tool_id,
        "accepted": True,
        "fingerprint": f"sha256:{tool_id}",
        "standard": {
            "name": name,
            "description": name.replace("_", " "),
            "inputSchema": {
                "type": "object",
                "properties": inputs,
                "required": list(inputs),
                "additionalProperties": False,
            },
        },
        "backend_mapping": {"responseSchema": output or {"type": "object"}},
        "endpoint": {
            "protocol": "http",
            "address": "orders.internal",
            "port": "8080",
        },
        "governance": {
            "domain": ["orders"],
            "write": False,
            "destructive": False,
            "riskLevel": "low",
            "approvalRequired": False,
            "approvalRole": None,
            "sideEffect": "none",
            "timeoutMs": 3000,
        },
    }


def object_output(name: str, description: str) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {name: {"type": "string", "description": description}},
    }


def test_recursive_backfill_builds_topological_graph_and_subgraph() -> None:
    tools = [
        tool("user", "get_current_user", output=object_output("userId", "用户唯一标识")),
        tool(
            "order", "get_latest_order",
            inputs={"userId": {"type": "string", "description": "用户唯一标识"}},
            output=object_output("orderId", "订单唯一标识"),
        ),
        tool(
            "invoice", "get_order_invoice",
            inputs={"orderId": {"type": "string", "description": "订单唯一标识"}},
            output=object_output("invoiceId", "发票唯一标识"),
        ),
    ]

    graphs = CapabilityGraphBuilder(tools).build()
    invoice = next(graph for graph in graphs if graph.terminal_tool_id == "invoice")
    order = next(graph for graph in graphs if graph.terminal_tool_id == "order")

    assert invoice.execution_order == ["user", "order", "invoice"]
    assert invoice.zero_input is True
    assert [(edge.output_path, edge.input_path) for edge in invoice.edges] == [
        ("/orderId", "/orderId"),
        ("/userId", "/userId"),
    ]
    assert order.id in invoice.subgraph_ids
    assert invoice.test_report.blocking_failures == 0


def test_array_output_is_not_implicitly_bound_to_scalar_input() -> None:
    provider = tool(
        "orders", "list_orders",
        output={
            "type": "array",
            "items": object_output("orderId", "订单唯一标识"),
        },
    )
    consumer = tool(
        "cancel", "cancel_order",
        inputs={"orderId": {"type": "string", "description": "订单唯一标识"}},
        output=object_output("cancellationId", "取消记录唯一标识"),
    )

    assert not CapabilityGraphBuilder([provider, consumer]).build()


def test_close_provider_scores_are_reported_as_ambiguity_not_auto_selected() -> None:
    providers = [
        tool("recent", "get_recent_order", output=object_output("orderId", "订单唯一标识")),
        tool("draft", "get_draft_order", output=object_output("orderId", "订单唯一标识")),
    ]
    consumer = tool(
        "cancel", "cancel_order",
        inputs={"orderId": {"type": "string", "description": "订单唯一标识"}},
        output=object_output("cancellationId", "取消记录唯一标识"),
    )

    graph = next(
        graph for graph in CapabilityGraphBuilder([*providers, consumer]).build()
        if graph.terminal_tool_id == "cancel"
    )
    assert graph.status == "ambiguous"
    assert graph.edges == []
    assert graph.input_schema["required"] == ["orderId"]
    assert graph.test_report.blocking_failures == 0
    assert graph.issues[0].code == "ambiguous-provider"
