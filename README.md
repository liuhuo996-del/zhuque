# GateForge

GateForge 是面向企业存量 API 的 API → 高质量 MCP 能力包自动工程化平台。

它只负责 API 接入分析、AI 富化、能力图构建、治理附加信息、依赖闭包、自动测试与能力包
编译；通过 Nacos 3 官方 AI/MCP 管理接口注册通过检查的能力包。Nacos 是唯一注册中心与
生命周期控制面，Higress 是 MCP 数据面，GateForge 不代理工具调用。

## Docker 部署（推荐）

GateForge 镜像同时包含中文前端和 Python API，只需暴露一个端口。Nacos 与 Higress 不会
被打包进 Compose，仍作为外部基础设施独立部署。

```bash
cp .env.example .env
# 编辑 .env，至少设置 GATEFORGE_ADMIN_TOKEN
docker compose up -d --build
docker compose ps
```

浏览器访问 `http://127.0.0.1:8081`。SQLite 数据保存在命名卷 `gateforge-data` 中；更新镜像
不会丢失。停止服务使用 `docker compose down`，不要添加 `-v`，除非明确要删除全部数据。

容器启动后可在“设置”页维护 Nacos、大模型与内网 OpenAPI 访问策略。配置写入和连接测试
需要 `.env` 中的管理令牌。密码及 API Key 不会回显，页面提交的新密钥使用 Fernet 加密后
写入命名卷；删除卷也会删除自动生成的设置加密密钥。

GateForge 默认按企业自托管模式允许 RFC1918 与 IPv6 ULA 私网 API，因此每位部署者都不需要
填写本机 IP。`localhost`、回环、链路本地/云元数据、组播和保留地址仍始终拒绝。公网多人
部署应在设置页关闭企业私网访问，并通过精确域名或 `*.example.corp` 可信名单收紧范围。

如果 Nacos 与 GateForge 位于同一个 Docker 网络，将
`GATEFORGE_NACOS_SERVER_URL` 改成类似 `http://nacos:8848`；如果 Nacos 在宿主机，默认的
`http://host.docker.internal:8848` 可直接使用。

## 新的工程流程

```text
API 池
→ 输入/输出字段端口与倒排索引
→ 从终点工具反向回溯 Provider
→ 类型、基数、同源、歧义和循环检查
→ 能力图/子图与确定性执行顺序
→ 图 Schema、治理传播与 L0/L1/L2 测试
→ 根据能力包目标描述匹配图和原子工具
→ MCP 能力包 → Nacos → Higress
```

能力图不是自定义 MCP 协议：注册给 Nacos 的仍然是标准 MCP Tool 定义和官方
API 映射。图是 GateForge Pack 的独立编译元数据。如果未来要把多步图暴露为一个可调用
Tool，需要另外的图执行器或智能体主机执行，GateForge 不会进入运行时数据面。

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
