# GateForge Python 引擎

GateForge 把 OpenAPI/Swagger 加工为带治理、依赖图与自动测试证据的 MCP 能力包，
然后通过 Nacos 官方 AI/MCP 管理接口提交。它不实现注册中心、生命周期或 MCP 数据面。

生产部署统一使用仓库根目录的 `Dockerfile` 与 `compose.yaml`。镜像会把 Vite 前端编译后
交给 FastAPI 托管，前后端共用 8081 端口；数据库固定写入 `/data/gateforge.db` 持久卷。

## 本地运行

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -q
.venv/bin/uvicorn gateforge.main:app --host 0.0.0.0 --port 8081
```

前端：

```bash
npm install
npm run dev
```

## 核心 API

- `POST /api/sources` / `POST /api/sources/batch`：导入、分析、过滤与富化 API。
- `GET /api/tools`：标准 MCP 工具 + 独立治理元数据。
- `GET /api/clusters`：领域/意图聚类建议。
- `POST /api/packs/build`：依赖图、L0/L1/L2、闭包与能力包编译。
- `POST /api/packs/{id}/register`：通过 Nacos 官方接口注册通过检查的能力包。
- `GET /api/registrations`：读取 GateForge 保存的 Nacos 回读结果。

## 环境变量

变量前缀为 `GATEFORGE_`：

- `DATABASE_PATH`：SQLite 路径，默认 `./data/gateforge.db`。
- `ALLOWED_SPEC_HOSTS`：远程 OpenAPI 主机允许列表。
- `ALLOW_PRIVATE_SPEC_HOSTS`：允许导入私网规范；默认关闭。
- `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL`：可选 OpenAI 兼容富化器。
- `NACOS_SERVER_URL` / `NACOS_CONTEXT_PATH` / `NACOS_NAMESPACE`。
- `NACOS_USERNAME` / `NACOS_PASSWORD`。
- `L1_ALLOW_ORIGINS`：L1 可访问的精确来源地址允许列表。
- `L1_ALLOW_UNSAFE_METHODS`：允许 L1 写操作；默认关闭。
- `QUALITY_THRESHOLD`：可注册质量阈值，默认 0.78。

生产环境必须把数据库和 Nacos 凭据放在密钥管理系统中，并通过身份网关保护 GateForge API。
