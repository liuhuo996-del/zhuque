from __future__ import annotations

from pathlib import Path

import httpx
import pytest

from gateforge.main import create_app
from gateforge.settings import Settings


FIXTURE = Path(__file__).parent / "fixtures" / "orders-openapi.yaml"


@pytest.mark.asyncio
async def test_api_intake_to_pack_workflow(tmp_path: Path) -> None:
    app = create_app(Settings(database_path=tmp_path / "gateforge.db", quality_threshold=0.5))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        imported = await client.post("/api/sources", json={
            "name": "Orders",
            "spec_text": FIXTURE.read_text(),
            "owner": "commerce-platform",
        })
        assert imported.status_code == 201, imported.text
        assert imported.json()["accepted_count"] == 3

        cluster_rows = (await client.get("/api/clusters")).json()
        assert cluster_rows
        built = await client.post("/api/packs/build", json={
            "name": "Order Query Pack",
            "cluster_keys": [cluster_rows[0]["key"]],
            "run_l2": True,
        })
        assert built.status_code == 201, built.text
        assert built.json()["schema_version"] == "gateforge.mcp-pack/v2"

        dashboard = (await client.get("/api/dashboard")).json()
        assert dashboard["sources"] == 1
        assert dashboard["operations"] == 4
        assert dashboard["packs"] == 1


@pytest.mark.asyncio
async def test_api_pool_builds_graphs_and_recommends_pack_by_description(tmp_path: Path) -> None:
    app = create_app(Settings(database_path=tmp_path / "gateforge.db", quality_threshold=0.5))
    graph_spec = """
openapi: 3.0.3
info: {title: Orders, version: 1.0.0}
servers: [{url: https://orders.example.com/v1}]
security: [{internalApiKey: []}]
components:
  securitySchemes:
    internalApiKey: {type: apiKey, in: header, name: x-api-key}
paths:
  /orders/latest-cancelable:
    get:
      operationId: getLatestCancelableOrder
      tags: [Orders]
      summary: 查询当前用户最新可取消订单
      responses:
        '200':
          description: 最新可取消订单
          content:
            application/json:
              schema:
                type: object
                properties:
                  orderId: {type: string, description: 可取消订单唯一标识}
  /orders/{orderId}/cancel:
    post:
      operationId: cancelOrder
      tags: [Orders]
      summary: 取消指定订单
      parameters:
        - name: orderId
          in: path
          required: true
          description: 需要取消的订单唯一标识
          schema: {type: string}
      responses:
        '200':
          description: 取消结果
          content:
            application/json:
              schema:
                type: object
                properties:
                  cancellationId: {type: string, description: 取消记录唯一标识}
"""
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        imported = await client.post("/api/sources", json={
            "name": "Orders",
            "spec_text": graph_spec,
            "owner": "commerce-platform",
        })
        assert imported.status_code == 201, imported.text
        graphs = (await client.get("/api/graphs")).json()
        cancel_graph = next(graph for graph in graphs if graph["terminal_tool_name"] == "orders_cancel_order")
        assert len(cancel_graph["nodes"]) == 2
        assert cancel_graph["edges"][0]["concept"]
        assert cancel_graph["test_report"]["blocking_failures"] == 0
        assert cancel_graph["status"] in {"ready", "needs_input"}

        recommendation = await client.post(
            "/api/packs/recommend",
            json={"description": "查询订单后取消尚未履约的订单"},
        )
        assert recommendation.status_code == 200, recommendation.text
        assert cancel_graph["id"] in recommendation.json()["graph_ids"]

        built = await client.post("/api/packs/build", json={
            "name": "订单取消能力包",
            "description": "查询订单后取消尚未履约的订单",
            "graph_ids": [cancel_graph["id"]],
            "run_l2": True,
        })
        assert built.status_code == 201, built.text
        body = built.json()
        assert body["schema_version"] == "gateforge.mcp-pack/v2"
        assert body["slug"] != "mcp-pack"
        assert body["capability_graphs"][0]["id"] == cancel_graph["id"]
        assert "graph-order" in {case["category"] for case in body["test_report"]["cases"]}
        assert len(body["dependency_graph"]["edges"]) == 1
        assert {tool["name"] for tool in body["tools"]} == {
            "orders_get_latest_cancelable_order", "orders_cancel_order",
        }


@pytest.mark.asyncio
async def test_production_static_frontend_and_spa_fallback(tmp_path: Path) -> None:
    static = tmp_path / "static"
    (static / "assets").mkdir(parents=True)
    (static / "index.html").write_text("<html><body>GateForge 中文前端</body></html>")
    (static / "assets" / "app.js").write_text("console.log('gateforge')")
    app = create_app(Settings(
        database_path=tmp_path / "gateforge.db",
        static_dir=static,
    ))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        assert "GateForge 中文前端" in (await client.get("/")).text
        assert "GateForge 中文前端" in (await client.get("/packs/example")).text
        assert "gateforge" in (await client.get("/assets/app.js")).text
        assert (await client.get("/api/not-found")).status_code == 404


@pytest.mark.asyncio
async def test_runtime_settings_are_protected_encrypted_and_reloaded(tmp_path: Path) -> None:
    database = tmp_path / "gateforge.db"
    config = Settings(
        database_path=database,
        admin_token="correct-admin-token",
        nacos_server_url="http://environment-nacos:8848",
    )
    app = create_app(config)
    request = {
        "nacos": {
            "serverUrl": "http://nacos.internal:8848",
            "contextPath": "/nacos",
            "namespace": "enterprise",
            "username": "gateforge",
            "password": "nacos-secret",
            "clearPassword": False,
        },
        "ai": {
            "baseUrl": "https://llm.example.corp/v1",
            "model": "enterprise-model",
            "apiKey": "llm-secret",
            "clearApiKey": False,
        },
        "intake": {
            "allowedSpecHosts": ["openapi.example.corp", "*.api.example.corp"],
            "allowPrivateSpecHosts": False,
            "l1AllowOrigins": ["https://api.example.corp"],
            "l1AllowUnsafeMethods": False,
        },
    }
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.put("/api/settings", json=request)).status_code == 401
        saved = await client.put(
            "/api/settings",
            json=request,
            headers={"X-GateForge-Admin-Token": "correct-admin-token"},
        )
        assert saved.status_code == 200, saved.text
        assert saved.json()["nacos"]["passwordConfigured"] is True
        assert saved.json()["ai"]["apiKeyConfigured"] is True
        assert "nacos-secret" not in saved.text
        assert "llm-secret" not in saved.text

    raw_database = database.read_bytes()
    assert b"nacos-secret" not in raw_database
    assert b"llm-secret" not in raw_database
    assert (tmp_path / ".gateforge-settings.key").stat().st_mode & 0o777 == 0o600

    reloaded = Settings(database_path=database, admin_token="correct-admin-token")
    second_app = create_app(reloaded)
    assert reloaded.nacos_server_url == "http://nacos.internal:8848"
    assert reloaded.nacos_password == "nacos-secret"
    assert reloaded.ai_api_key == "llm-secret"
    assert reloaded.spec_host_is_allowed("service.api.example.corp") is True
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=second_app), base_url="http://test"
    ) as client:
        visible = (await client.get("/api/settings")).json()
        assert visible["nacos"]["passwordSaved"] is True
        assert "nacos-secret" not in str(visible)


@pytest.mark.asyncio
async def test_self_hosted_default_and_allowlist_handle_private_resolution(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from gateforge.intake import OpenApiIntake

    monkeypatch.setattr(
        "socket.getaddrinfo",
        lambda *_: [(2, 1, 6, "", ("10.20.30.40", 443))],
    )
    await OpenApiIntake(Settings())._assert_safe_url("https://openapi.example.corp/openapi.json")
    await OpenApiIntake(Settings(
        allow_private_spec_hosts=False,
        allowed_spec_hosts="openapi.example.corp",
    ))._assert_safe_url("https://openapi.example.corp/openapi.json")


@pytest.mark.asyncio
async def test_loopback_is_always_rejected_even_when_allowlisted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from gateforge.errors import GateForgeError
    from gateforge.intake import OpenApiIntake

    monkeypatch.setattr(
        "socket.getaddrinfo",
        lambda *_: [(2, 1, 6, "", ("127.0.0.1", 443))],
    )
    with pytest.raises(GateForgeError, match="禁止访问"):
        await OpenApiIntake(Settings(
            allowed_spec_hosts="localhost",
            allow_private_spec_hosts=True,
        ))._assert_safe_url("https://localhost/openapi.json")
