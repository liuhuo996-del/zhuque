from __future__ import annotations

import json
from typing import Any

import httpx

from gateforge.errors import GateForgeError, QualityGateError
from gateforge.models import PackArtifact, RegistrationResult
from gateforge.settings import Settings
from gateforge.util import digest, now_iso


class NacosAdapter:
    """只对接 Nacos 3 官方 AI/MCP 管理接口的轻量适配器。"""

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.settings = settings
        self.transport = transport
        self._access_token: str | None = None

    async def register(self, pack: PackArtifact) -> RegistrationResult:
        if pack.status != "ready":
            raise QualityGateError(
                "只有通过检查的 MCP 能力包才能提交 Nacos",
                "修复构建中的阻断测试并重新编译能力包",
            )
        await self._require_minimum_version()
        existing = await self._detail(pack.mcp_server["name"])
        service = self._nacos_service(pack)
        fields = {
            "namespaceId": self.settings.nacos_namespace,
            "mcpName": pack.mcp_server["name"],
            "serverSpecification": json.dumps(service["serverSpecification"], ensure_ascii=False),
            "toolSpecification": json.dumps(service["toolSpecification"], ensure_ascii=False),
            "endpointSpecification": json.dumps(service["endpointSpecification"], ensure_ascii=False),
        }
        method = "POST"
        if existing:
            method = "PUT"
            server = service["serverSpecification"]
            server["id"] = existing["id"]
            fields["serverSpecification"] = json.dumps(server, ensure_ascii=False)
            fields["latest"] = "true"
            fields["overrideExisting"] = "true"
        response = await self._request(method, "/v3/admin/ai/mcp", data=fields)
        self._require_success(response, "注册 MCP Pack")
        expected_version = str(service["serverSpecification"]["versionDetail"]["version"])
        detail = None
        for delay in (0, 0.1, 0.25, 0.5, 1.0, 2.0):
            if delay:
                await __import__("asyncio").sleep(delay)
            detail = await self._detail(pack.mcp_server["name"])
            visible_version = str((detail or {}).get("versionDetail", {}).get("version", ""))
            if detail and visible_version == expected_version and self._sidecars_match(detail, pack):
                break
        if not detail or str((detail.get("versionDetail") or {}).get("version", "")) != expected_version:
            raise GateForgeError(
                "Nacos 接受请求但新版本尚未可见",
                f"期望 {expected_version}；稍后重试并检查 Nacos 状态",
                503,
            )
        if not self._sidecars_match(detail, pack):
            raise GateForgeError(
                "Nacos 未完整保存 GateForge 治理附加信息",
                "确认 Nacos >= 3.0.1 并支持 toolsMeta.invokeContext 后重试",
                503,
            )
        version_detail = detail.get("versionDetail") or {}
        return RegistrationResult(
            pack_id=pack.id,
            nacos_server_id=str(detail.get("id", "")),
            nacos_version=str(version_detail.get("version", "")),
            status="enabled" if detail.get("enabled") else "disabled",
            mcp_name=pack.mcp_server["name"],
            registered_at=now_iso(),
            raw={
                "id": detail.get("id"),
                "name": detail.get("name"),
                "versionDetail": version_detail,
                "enabled": detail.get("enabled"),
                "capabilities": detail.get("capabilities"),
            },
        )

    async def probe(self) -> dict[str, Any]:
        response = await self._request("GET", "/v3/admin/core/state")
        self._require_success(response, "探测 Nacos")
        body = response.json()
        data = body.get("data", body)
        return {"ok": True, "version": str(data.get("version", "")), "namespace": self.settings.nacos_namespace}

    async def _require_minimum_version(self) -> None:
        probe = await self.probe()
        current = self._version_tuple(probe["version"])
        required = self._version_tuple(self.settings.nacos_min_version)
        if current < required:
            raise GateForgeError(
                f"Nacos {probe['version']} 不支持所需 AI/MCP Admin API",
                f"升级到 Nacos >= {self.settings.nacos_min_version}",
                503,
            )

    async def _detail(self, mcp_name: str) -> dict[str, Any] | None:
        response = await self._request(
            "GET", "/v3/admin/ai/mcp",
            params={"namespaceId": self.settings.nacos_namespace, "mcpName": mcp_name},
        )
        if response.status_code == 404:
            try:
                if response.json().get("code") == 20004:
                    return None
            except ValueError:
                pass
        self._require_success(response, "读取 Nacos MCP detail")
        return response.json().get("data")

    def _nacos_service(self, pack: PackArtifact) -> dict[str, Any]:
        endpoint = pack.endpoints[0]
        tools_meta = {}
        registry_tools = []
        for tool in pack.tools:
            name = tool["name"]
            policy = pack.governance["tools"][name]
            locations = pack.backend_mappings[name]["argumentLocations"]
            policy_envelope = {"digest": digest(policy), "policy": policy}
            registry_tools.append({
                "name": name,
                "description": tool["description"],
                "inputSchema": tool["inputSchema"],
            })
            tools_meta[name] = {
                "enabled": True,
                # Nacos 3.0.1 persists McpToolMeta's official invokeContext map,
                # while unknown sibling fields are discarded during deserialization.
                "invokeContext": {
                    "com.gateforge/governance": json.dumps(
                        policy_envelope, ensure_ascii=False, separators=(",", ":"), sort_keys=True
                    ),
                    "com.gateforge/packHash": pack.artifact_hash,
                },
                "templates": {
                    "json-go-template": {
                        "requestTemplate": pack.backend_mappings[name]["requestTemplate"],
                        "argsPosition": {
                            argument: encoded.partition(":")[0]
                            for argument, encoded in locations.items()
                        },
                        "responseTemplate": {},
                    }
                },
            }
        version = "1.0.0-" + pack.artifact_hash.removeprefix("sha256:")[:12]
        return {
            "serverSpecification": {
                "name": pack.mcp_server["name"],
                "protocol": endpoint["protocol"],
                "frontProtocol": pack.mcp_server["frontProtocol"],
                "description": pack.description,
                "versionDetail": {"version": version},
                "remoteServerConfig": {"exportPath": ""},
                "capabilities": ["TOOL"],
                "enabled": True,
            },
            "toolSpecification": {"tools": registry_tools, "toolsMeta": tools_meta},
            "endpointSpecification": {
                "type": "DIRECT",
                "data": {"address": endpoint["address"], "port": endpoint["port"]},
            },
        }

    @staticmethod
    def _sidecars_match(detail: dict[str, Any], pack: PackArtifact) -> bool:
        tools_meta = ((detail.get("toolSpec") or {}).get("toolsMeta") or {})
        for tool in pack.tools:
            context = ((tools_meta.get(tool["name"]) or {}).get("invokeContext") or {})
            if context.get("com.gateforge/packHash") != pack.artifact_hash:
                return False
            try:
                envelope = json.loads(str(context.get("com.gateforge/governance", "")))
            except json.JSONDecodeError:
                return False
            policy = pack.governance["tools"][tool["name"]]
            if envelope.get("digest") != digest(policy) or envelope.get("policy") != policy:
                return False
        return True

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        base = self.settings.nacos_server_url.rstrip("/") + "/" + self.settings.nacos_context_path.strip("/")
        headers = dict(kwargs.pop("headers", {}))
        if self._access_token:
            headers["Authorization"] = f"Bearer {self._access_token}"
        async with httpx.AsyncClient(
            base_url=base, timeout=self.settings.request_timeout_seconds,
            transport=self.transport, trust_env=False,
        ) as client:
            response = await client.request(method, path, headers=headers, **kwargs)
            if response.status_code in {401, 403} and self.settings.nacos_username:
                login = await client.post("/v3/auth/user/login", data={
                    "username": self.settings.nacos_username,
                    "password": self.settings.nacos_password,
                })
                self._require_success(login, "登录 Nacos")
                self._access_token = str(login.json().get("accessToken") or "")
                if not self._access_token:
                    raise GateForgeError("Nacos 登录未返回 accessToken", "检查 Nacos 鉴权配置", 503)
                headers["Authorization"] = f"Bearer {self._access_token}"
                response = await client.request(method, path, headers=headers, **kwargs)
            return response

    @staticmethod
    def _require_success(response: httpx.Response, action: str) -> None:
        try:
            body = response.json()
        except ValueError as error:
            raise GateForgeError(f"{action}返回非 JSON", f"HTTP {response.status_code}", 503) from error
        if not response.is_success or body.get("code") not in {0, 200, None}:
            raise GateForgeError(
                f"{action}失败：HTTP {response.status_code} / code={body.get('code')}",
                str(body.get("message") or body)[:500],
                503,
            )

    @staticmethod
    def _version_tuple(value: str) -> tuple[int, ...]:
        result = []
        for part in value.split("."):
            digits = "".join(character for character in part if character.isdigit())
            result.append(int(digits or 0))
        return tuple(result)
