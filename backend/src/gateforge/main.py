from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from gateforge import __version__
from gateforge.capability_graph import CapabilityGraphBuilder
from gateforge.api import router
from gateforge.errors import GateForgeError
from gateforge.runtime_settings import RuntimeSettings
from gateforge.settings import Settings, settings
from gateforge.store import Store


def create_app(config: Settings | None = None) -> FastAPI:
    config = config or settings
    store = Store(config.database_path)
    if store.tools(accepted=True) and not store.capability_graphs():
        store.replace_capability_graphs(
            graph.model_dump() for graph in CapabilityGraphBuilder(store.tools(accepted=True)).build()
        )
    runtime_settings = RuntimeSettings(config, store)
    app = FastAPI(
        title="GateForge API",
        version=__version__,
        description="面向企业存量 API 的高质量 MCP 能力包工程化引擎",
    )
    app.state.settings = config
    app.state.store = store
    app.state.runtime_settings = runtime_settings
    app.include_router(router(store, config, runtime_settings))

    @app.exception_handler(GateForgeError)
    async def gateforge_error(_: Request, error: GateForgeError) -> JSONResponse:
        return JSONResponse(
            status_code=error.status_code,
            content={"what": error.what, "fix": error.fix},
        )

    _mount_frontend(app, config.static_dir)

    return app


def _mount_frontend(app: FastAPI, static_dir: Path) -> None:
    """在生产镜像中托管前端，并为前端路由提供单页应用回退。"""
    root = static_dir.resolve()
    index = root / "index.html"
    if not index.is_file():
        return
    assets = root / "assets"
    if assets.is_dir():
        app.mount("/assets", StaticFiles(directory=assets), name="前端静态资源")

    @app.get("/", include_in_schema=False)
    async def frontend_index() -> FileResponse:
        return FileResponse(index)

    @app.get("/{path:path}", include_in_schema=False)
    async def frontend_route(path: str) -> FileResponse:
        if path == "api" or path.startswith("api/"):
            raise HTTPException(status_code=404, detail="接口不存在")
        candidate = (root / path).resolve()
        if candidate.is_relative_to(root) and candidate.is_file():
            return FileResponse(candidate)
        return FileResponse(index)


app = create_app()


def run() -> None:
    import uvicorn

    uvicorn.run("gateforge.main:app", host="0.0.0.0", port=8081, reload=False)
