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
        assert built.json()["schema_version"] == "gateforge.mcp-pack/v1"

        dashboard = (await client.get("/api/dashboard")).json()
        assert dashboard["sources"] == 1
        assert dashboard["operations"] == 4
        assert dashboard["packs"] == 1


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
