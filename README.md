# GateForge

GateForge 是面向企业存量 API 的 API → 高质量 MCP 能力包自动工程化平台。

它只负责 API 接入分析、AI 富化、意图聚类、治理附加信息、依赖闭包、自动测试与能力包
编译；通过 Nacos 3 官方 AI/MCP 管理接口注册通过检查的能力包。Nacos 是唯一注册中心与
生命周期控制面，Higress 是 MCP 数据面，GateForge 不代理工具调用。

## Docker 部署（推荐）

GateForge 镜像同时包含中文前端和 Python API，只需暴露一个端口。Nacos 与 Higress 不会
被打包进 Compose，仍作为外部基础设施独立部署。

```bash
cp .env.example .env
# 编辑 .env，至少确认 GATEFORGE_NACOS_SERVER_URL 和 Nacos 凭据
docker compose up -d --build
docker compose ps
```

浏览器访问 `http://127.0.0.1:8081`。SQLite 数据保存在命名卷 `gateforge-data` 中；更新镜像
不会丢失。停止服务使用 `docker compose down`，不要添加 `-v`，除非明确要删除全部数据。

如果 Nacos 与 GateForge 位于同一个 Docker 网络，将
`GATEFORGE_NACOS_SERVER_URL` 改成类似 `http://nacos:8848`；如果 Nacos 在宿主机，默认的
`http://host.docker.internal:8848` 可直接使用。

## 本地开发

```bash
cd backend
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/uvicorn gateforge.main:app --host 0.0.0.0 --port 8081
```

另一个终端：

```bash
npm install
npm run dev
```

开发模式浏览器访问 `http://127.0.0.1:5173`。架构、接口与环境变量详见
[`backend/README.md`](backend/README.md) 和 [`GateForge-项目介绍.md`](GateForge-项目介绍.md)。
