from __future__ import annotations

import ipaddress
import json
import socket
from collections import Counter
from copy import deepcopy
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx
import yaml

from gateforge.errors import GateForgeError
from gateforge.models import GovernanceMetadata, Sensitivity, SourceImport
from gateforge.settings import Settings
from gateforge.util import digest, new_id, now_iso, slugify, tool_name


HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options", "trace")
READ_METHODS = {"GET", "HEAD"}
WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
SENSITIVE_NAMES = {
    "password", "passwd", "secret", "token", "authorization", "api_key", "apikey",
    "access_token", "refresh_token", "credential", "ssn", "身份证", "手机号", "phone",
    "email", "bank", "card", "account", "地址", "address",
}
HEALTH_PATHS = {"/health", "/healthz", "/metrics", "/ready", "/readiness", "/live", "/liveness"}


class OpenApiIntake:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    async def import_source(self, request: SourceImport) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        spec_text = request.spec_text or await self._fetch_spec(str(request.spec_url))
        document = self._parse_document(spec_text)
        version = str(document.get("openapi") or document.get("swagger") or "")
        if not (version.startswith("3.") or version.startswith("2.")):
            raise GateForgeError("仅支持 OpenAPI 3.x 或 Swagger 2.0", "转换规范后重新导入", 422)

        source_slug = request.slug or slugify(request.name, "api")
        base_url = self._base_url(document, str(request.base_url or ""))
        source = {
            "id": new_id(),
            "name": request.name.strip(),
            "slug": source_slug,
            "spec_url": str(request.spec_url) if request.spec_url else None,
            "base_url": base_url,
            "environment": request.environment,
            "owner": request.owner.strip() or "unassigned",
            "spec_hash": digest(document),
            "spec_text": spec_text,
            "imported_at": now_iso(),
        }

        security_schemes = self._security_schemes(document)
        raw_tools: list[dict[str, Any]] = []
        fingerprints: Counter[str] = Counter()
        for path, path_item in (document.get("paths") or {}).items():
            if not isinstance(path_item, dict):
                continue
            inherited = path_item.get("parameters") or []
            for method in HTTP_METHODS:
                operation = path_item.get(method)
                if not isinstance(operation, dict):
                    continue
                tool = self._analyze_operation(
                    source, document, security_schemes, method.upper(), path, operation, inherited
                )
                fingerprints[tool["fingerprint"]] += 1
                raw_tools.append(tool)

        for tool in raw_tools:
            if fingerprints[tool["fingerprint"]] > 1:
                tool["accepted"] = False
                tool["rejection_reasons"].append("duplicate-semantic-operation")
        return source, raw_tools

    async def _fetch_spec(self, url: str) -> str:
        await self._assert_safe_url(url)
        async with httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds, follow_redirects=False, trust_env=False
        ) as client:
            current = url
            for _ in range(4):
                response = await client.get(current, headers={"Accept": "application/json, application/yaml, text/yaml"})
                if response.is_redirect:
                    target = urljoin(current, response.headers.get("location", ""))
                    await self._assert_safe_url(target)
                    current = target
                    continue
                response.raise_for_status()
                if len(response.content) > 10 * 1024 * 1024:
                    raise GateForgeError("OpenAPI 文档超过 10MB", "拆分来源后重新导入", 413)
                return response.text
        raise GateForgeError("OpenAPI 重定向次数过多", "检查 spec URL", 422)

    async def _assert_safe_url(self, url: str) -> None:
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise GateForgeError("spec URL 必须是 HTTP(S)", "使用可访问的 OpenAPI URL", 422)
        host = parsed.hostname.lower()
        if self.settings.spec_host_allowlist and host not in self.settings.spec_host_allowlist:
            raise GateForgeError("spec host 不在允许列表", "配置 GATEFORGE_ALLOWED_SPEC_HOSTS", 403)
        if self.settings.allow_private_spec_hosts:
            return
        try:
            addresses = await __import__("asyncio").get_running_loop().run_in_executor(
                None, lambda: socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80))
            )
        except socket.gaierror as error:
            raise GateForgeError("无法解析 spec host", str(error), 422) from error
        for address in {item[4][0] for item in addresses}:
            ip = ipaddress.ip_address(address)
            if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
                raise GateForgeError("spec URL 指向私有或保留地址", "使用 allowlist 或显式开启私网导入", 403)

    @staticmethod
    def _parse_document(spec_text: str) -> dict[str, Any]:
        try:
            value = json.loads(spec_text)
        except json.JSONDecodeError:
            try:
                value = yaml.safe_load(spec_text)
            except yaml.YAMLError as error:
                raise GateForgeError("OpenAPI JSON/YAML 解析失败", str(error), 422) from error
        if not isinstance(value, dict):
            raise GateForgeError("OpenAPI 根节点必须是对象", "修复文档格式", 422)
        return value

    @staticmethod
    def _base_url(document: dict[str, Any], fallback: str) -> str:
        if fallback:
            return fallback.rstrip("/")
        servers = document.get("servers") or []
        if servers and isinstance(servers[0], dict) and servers[0].get("url"):
            url = str(servers[0]["url"])
            for key, value in (servers[0].get("variables") or {}).items():
                default = value.get("default", "") if isinstance(value, dict) else ""
                url = url.replace("{" + key + "}", str(default))
            return url.rstrip("/")
        if str(document.get("swagger", "")).startswith("2."):
            schemes = document.get("schemes") or ["https"]
            host = document.get("host")
            if host:
                return f"{schemes[0]}://{host}{document.get('basePath', '')}".rstrip("/")
        raise GateForgeError("OpenAPI 没有可用的 servers/baseUrl", "导入时提供 base_url", 422)

    @staticmethod
    def _security_schemes(document: dict[str, Any]) -> dict[str, Any]:
        return (
            ((document.get("components") or {}).get("securitySchemes") or {})
            if str(document.get("openapi", "")).startswith("3.")
            else (document.get("securityDefinitions") or {})
        )

    def _analyze_operation(
        self,
        source: dict[str, Any],
        document: dict[str, Any],
        security_schemes: dict[str, Any],
        method: str,
        path: str,
        operation: dict[str, Any],
        inherited_parameters: list[Any],
    ) -> dict[str, Any]:
        operation_id = str(operation.get("operationId") or "")
        name = tool_name(source["slug"], operation_id, method, path)
        tags = [str(tag) for tag in operation.get("tags") or []]
        summary = str(operation.get("summary") or operation.get("description") or "").strip()
        domain = self._domain(tags, path)
        intent = self._intent(method, name, summary, path)
        parameters = list(inherited_parameters) + list(operation.get("parameters") or [])
        input_schema, locations = self._input_schema(document, parameters, operation.get("requestBody"))
        output_schema = self._output_schema(document, operation.get("responses") or {})
        input_sensitive = self._sensitive_paths(input_schema)
        output_sensitive = self._sensitive_paths(output_schema)
        description = self._description(summary, intent, method, path, input_schema)
        effect = self._effect(method, path, summary)
        governance = self._governance(
            source, operation, method, effect, domain, intent, input_schema, output_schema,
            input_sensitive, output_sensitive, description,
        )
        security = operation.get("security", document.get("security", []))
        mapping = {
            "method": method,
            "path": path,
            "requestTemplate": self._request_template(source["base_url"], method, path, locations),
            "argumentLocations": locations,
            "responseSchema": output_schema,
            "security": self._security_requirements(security, security_schemes),
        }
        endpoint = self._endpoint(source["base_url"])
        standard: dict[str, Any] = {
            "name": name,
            "description": description,
            "inputSchema": input_schema,
            "annotations": {
                "readOnlyHint": governance.read_only,
                "destructiveHint": governance.destructive,
                "idempotentHint": governance.idempotent is True,
                "openWorldHint": True,
            },
        }

        rejection_reasons = self._rejection_reasons(method, path, operation, standard)
        fingerprint = digest({
            "method": method,
            "path": path.lower(),
            "schema": input_schema,
            "intent": intent.lower(),
        })
        cluster_key = slugify(f"{domain}-{intent}", "general-api")
        return {
            "id": new_id(),
            "source_id": source["id"],
            "source_spec_hash": source["spec_hash"],
            "accepted": len(rejection_reasons) == 0,
            "rejection_reasons": rejection_reasons,
            "method": method,
            "path": path,
            "operation_id": operation_id,
            "standard": standard,
            "backend_mapping": mapping,
            "endpoint": endpoint,
            "governance": governance.model_dump(by_alias=True),
            "cluster_key": cluster_key,
            "fingerprint": fingerprint,
        }

    def _input_schema(
        self, document: dict[str, Any], parameters: list[Any], request_body: Any
    ) -> tuple[dict[str, Any], dict[str, str]]:
        properties: dict[str, Any] = {}
        required: list[str] = []
        locations: dict[str, str] = {}
        for raw in parameters:
            parameter = self._resolve(document, raw)
            if not isinstance(parameter, dict) or not parameter.get("name"):
                continue
            name = str(parameter["name"])
            location = str(parameter.get("in", "query"))
            public_name = self._unique_argument(name, location, properties)
            schema = self._normalize_schema(document, parameter.get("schema") or {"type": "string"})
            location_label = {"path": "路径参数", "query": "查询参数", "header": "请求头参数", "cookie": "Cookie 参数"}.get(location, location)
            schema.setdefault("description", str(parameter.get("description") or f"{name}（{location_label}）"))
            properties[public_name] = schema
            locations[public_name] = location + (":" + name if public_name != name else "")
            if parameter.get("required") is True:
                required.append(public_name)

        body = self._resolve(document, request_body)
        if isinstance(body, dict):
            content = body.get("content") or {}
            media = content.get("application/json") or next(iter(content.values()), {})
            body_schema = self._normalize_schema(document, media.get("schema") or {})
            if body_schema.get("type") == "object" and isinstance(body_schema.get("properties"), dict):
                for name, schema in body_schema["properties"].items():
                    public_name = self._unique_argument(name, "body", properties)
                    properties[public_name] = schema
                    locations[public_name] = "body" + (":" + name if public_name != name else "")
                    if name in body_schema.get("required", []):
                        required.append(public_name)
            elif body_schema:
                public_name = self._unique_argument("body", "body", properties)
                properties[public_name] = body_schema
                locations[public_name] = "body"
                if body.get("required") is True:
                    required.append(public_name)

        schema: dict[str, Any] = {"type": "object", "properties": properties, "additionalProperties": False}
        if required:
            schema["required"] = list(dict.fromkeys(required))
        return schema, locations

    def _output_schema(self, document: dict[str, Any], responses: dict[str, Any]) -> dict[str, Any]:
        selected = next((value for key, value in responses.items() if str(key).startswith("2")), None)
        response = self._resolve(document, selected)
        if not isinstance(response, dict):
            return {}
        content = response.get("content") or {}
        media = content.get("application/json") or next(iter(content.values()), {})
        return self._normalize_schema(document, media.get("schema") or {})

    def _normalize_schema(self, document: dict[str, Any], value: Any, depth: int = 0) -> dict[str, Any]:
        if depth > 16:
            raise GateForgeError("JSON Schema 嵌套超过 16 层", "简化接口参数模型", 422)
        schema = self._resolve(document, value)
        if not isinstance(schema, dict):
            return {}
        result: dict[str, Any] = {}
        allowed = {
            "$id", "$schema", "$defs", "type", "title", "description", "format", "default",
            "examples", "enum", "const", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern", "minItems",
            "maxItems", "uniqueItems", "minProperties", "maxProperties", "additionalProperties",
            "required", "readOnly", "writeOnly",
        }
        for key, item in schema.items():
            if key in allowed:
                result[key] = deepcopy(item)
        if "example" in schema and "examples" not in result:
            result["examples"] = [deepcopy(schema["example"])]
        if isinstance(schema.get("properties"), dict):
            result["properties"] = {
                str(key): self._normalize_schema(document, child, depth + 1)
                for key, child in schema["properties"].items()
            }
            result.setdefault("type", "object")
        if "items" in schema:
            result["items"] = self._normalize_schema(document, schema["items"], depth + 1)
            result.setdefault("type", "array")
        for keyword in ("allOf", "oneOf", "anyOf"):
            if isinstance(schema.get(keyword), list):
                result[keyword] = [self._normalize_schema(document, item, depth + 1) for item in schema[keyword]]
        if isinstance(schema.get("not"), dict):
            result["not"] = self._normalize_schema(document, schema["not"], depth + 1)
        if schema.get("nullable") is True:
            current_type = result.get("type")
            if isinstance(current_type, str):
                result["type"] = [current_type, "null"]
            else:
                non_null = dict(result)
                result = {"anyOf": [non_null, {"type": "null"}]}
        return result

    def _resolve(self, document: dict[str, Any], value: Any) -> Any:
        if not isinstance(value, dict) or "$ref" not in value:
            return value
        ref = str(value["$ref"])
        if not ref.startswith("#/"):
            raise GateForgeError("暂不支持跨文件 $ref", "将引用内联到同一 OpenAPI 文档", 422)
        current: Any = document
        for part in ref[2:].split("/"):
            current = current[part.replace("~1", "/").replace("~0", "~")]
        resolved = deepcopy(current)
        resolved.update({key: item for key, item in value.items() if key != "$ref"})
        return resolved

    @staticmethod
    def _unique_argument(name: str, location: str, properties: dict[str, Any]) -> str:
        if name not in properties:
            return name
        candidate = f"{location}_{name}"
        index = 2
        while candidate in properties:
            candidate = f"{location}_{name}_{index}"
            index += 1
        return candidate

    @staticmethod
    def _domain(tags: list[str], path: str) -> str:
        if tags:
            return tags[0]
        segments = [part for part in path.strip("/").split("/") if part and not part.startswith("{")]
        return segments[0] if segments else "general"

    @staticmethod
    def _intent(method: str, name: str, summary: str, path: str) -> str:
        text = f"{name} {summary} {path}".lower()
        verbs = (
            ("search", ("search", "list", "query", "find", "查询", "搜索", "列表")),
            ("create", ("create", "add", "submit", "创建", "新增", "提交")),
            ("update", ("update", "edit", "change", "修改", "更新")),
            ("delete", ("delete", "remove", "cancel", "删除", "取消", "注销")),
            ("execute", ("trigger", "execute", "run", "refund", "执行", "触发", "退款")),
        )
        for intent, candidates in verbs:
            if any(candidate in text for candidate in candidates):
                return intent
        return "read" if method in READ_METHODS else "operate"

    @staticmethod
    def _effect(method: str, path: str, summary: str) -> str:
        text = f"{path} {summary}".lower()
        if method == "DELETE" or any(value in text for value in ("delete", "remove", "cancel", "删除", "取消")):
            return "destructive"
        if method in READ_METHODS:
            return "read"
        return "write"

    @staticmethod
    def _description(summary: str, intent: str, method: str, path: str, schema: dict[str, Any]) -> str:
        intent_label = {
            "search": "查询", "create": "创建", "update": "更新", "delete": "删除",
            "execute": "执行", "read": "读取", "operate": "操作",
        }.get(intent, intent)
        base = summary.rstrip("。.") if summary else f"对 {path} 执行{intent_label}操作"
        required = schema.get("required") or []
        condition = f"调用前必须提供：{', '.join(required)}。" if required else "无需必填参数。"
        caution = "该操作会修改外部系统状态。" if method in WRITE_METHODS else "该操作仅读取数据。"
        return f"何时使用：{base}。{condition}{caution}不要用于与 {path} 无关的任务。"

    def _governance(
        self,
        source: dict[str, Any],
        operation: dict[str, Any],
        method: str,
        effect: str,
        domain: str,
        intent: str,
        input_schema: dict[str, Any],
        output_schema: dict[str, Any],
        input_sensitive: list[str],
        output_sensitive: list[str],
        description: str,
    ) -> GovernanceMetadata:
        extension = operation.get("x-gateforge-governance") or {}
        destructive = effect == "destructive"
        write = effect in {"write", "destructive"}
        risk = "critical" if destructive and (input_sensitive or output_sensitive) else (
            "high" if destructive or (write and input_sensitive) else "medium" if write else "low"
        )
        required = list(input_schema.get("required") or [])
        provides = self._leaf_fields(output_schema)
        description_score = min(1.0, 0.35 + len(description) / 240 + (0.15 if "何时使用" in description else 0))
        properties = input_schema.get("properties") or {}
        described = sum(1 for item in properties.values() if isinstance(item, dict) and item.get("description"))
        schema_score = 1.0 if not properties else min(1.0, 0.55 + 0.45 * described / len(properties))
        quality = round(0.55 * description_score + 0.45 * schema_score, 3)
        return GovernanceMetadata.model_validate({
            "intent": [str(value) for value in extension.get("intent", [intent])],
            "domain": [str(value) for value in extension.get("domain", [domain])],
            "readOnly": effect == "read",
            "write": write,
            "destructive": destructive,
            "idempotent": extension.get("idempotent", method in {"GET", "HEAD", "PUT", "DELETE"}),
            "sideEffect": extension.get("sideEffect", "none" if effect == "read" else "external"),
            "riskLevel": extension.get("riskLevel", risk),
            "approvalRequired": extension.get("approvalRequired", risk in {"high", "critical"}),
            "approvalRole": extension.get("approvalRole", "business-owner" if risk in {"high", "critical"} else None),
            "sensitivity": {"input": input_sensitive, "output": output_sensitive},
            # requires/provides describe inter-tool dependencies, not arguments supplied by the user.
            # Treating every required input as a provider dependency would falsely block ordinary tools.
            "requires": extension.get("requires", []),
            "provides": extension.get("provides", provides),
            "retryable": extension.get("retryable", method in READ_METHODS or method in {"PUT", "DELETE"}),
            "timeoutMs": extension.get("timeoutMs", 10_000),
            "owner": extension.get("owner", source["owner"]),
            "qualityScore": quality,
            "descriptionScore": round(description_score, 3),
            "schemaScore": round(schema_score, 3),
            "testPassRate": 0.0,
        })

    @staticmethod
    def _request_template(base_url: str, method: str, path: str, locations: dict[str, str]) -> dict[str, Any]:
        url = path
        query = []
        headers = []
        body_fields = []
        for public, encoded in locations.items():
            location, _, original = encoded.partition(":")
            original = original or public
            expression = "{{.args." + public + "}}"
            if location == "path":
                url = url.replace("{" + original + "}", expression)
            elif location == "query":
                query.append(f"{original}={expression}")
            elif location == "header":
                headers.append({"key": original, "value": expression})
            elif location == "body":
                body_fields.append((original, public))
        if query:
            url += ("&" if "?" in url else "?") + "&".join(query)
        template: dict[str, Any] = {"method": method, "url": url, "headers": headers}
        if body_fields:
            template["body"] = "{" + ",".join(
                f'"{original}":{{{{ toJson .args.{public} }}}}' for original, public in body_fields
            ) + "}"
            template["headers"] = headers + [{"key": "Content-Type", "value": "application/json"}]
        return template

    @staticmethod
    def _endpoint(base_url: str) -> dict[str, Any]:
        parsed = urlparse(base_url)
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        return {
            "type": "DIRECT",
            "protocol": parsed.scheme,
            "address": parsed.hostname,
            "port": str(port),
            "basePath": parsed.path.rstrip("/"),
        }

    @staticmethod
    def _security_requirements(security: Any, schemes: dict[str, Any]) -> list[dict[str, Any]]:
        result = []
        for requirement in security or []:
            if not isinstance(requirement, dict):
                continue
            for name, scopes in requirement.items():
                scheme = schemes.get(name, {})
                result.append({
                    "name": name,
                    "type": scheme.get("type", "unknown"),
                    "scheme": scheme.get("scheme"),
                    "in": scheme.get("in"),
                    "parameter": scheme.get("name"),
                    "scopes": scopes or [],
                })
        return result

    @staticmethod
    def _sensitive_paths(schema: dict[str, Any]) -> list[str]:
        result: list[str] = []

        def visit(value: Any, path: str) -> None:
            if not isinstance(value, dict):
                return
            for name, child in (value.get("properties") or {}).items():
                child_path = f"{path}/{name}"
                normalized = name.lower().replace("-", "_")
                if any(token in normalized for token in SENSITIVE_NAMES):
                    result.append(child_path)
                visit(child, child_path)
            if "items" in value:
                visit(value["items"], path + "/*")
            for keyword in ("allOf", "oneOf", "anyOf"):
                for child in value.get(keyword) or []:
                    visit(child, path)

        visit(schema, "")
        return sorted(set(result))

    @staticmethod
    def _leaf_fields(schema: dict[str, Any]) -> list[str]:
        fields: list[str] = []

        def visit(value: Any, prefix: str) -> None:
            if not isinstance(value, dict):
                return
            properties = value.get("properties")
            if isinstance(properties, dict) and properties:
                for key, child in properties.items():
                    visit(child, f"{prefix}.{key}" if prefix else key)
            elif prefix:
                fields.append(prefix)
            for keyword in ("allOf", "oneOf", "anyOf"):
                for child in value.get(keyword) or []:
                    visit(child, prefix)

        visit(schema, "")
        return sorted(set(fields))[:200]

    @staticmethod
    def _rejection_reasons(
        method: str, path: str, operation: dict[str, Any], standard: dict[str, Any]
    ) -> list[str]:
        reasons = []
        if operation.get("deprecated") is True:
            reasons.append("deprecated")
        if path.lower().rstrip("/") in HEALTH_PATHS or method in {"TRACE", "OPTIONS"}:
            reasons.append("operational-endpoint")
        if not standard["description"].strip():
            reasons.append("missing-description")
        schema_size = len(json.dumps(standard["inputSchema"], ensure_ascii=False))
        if schema_size > 64_000:
            reasons.append("schema-too-large")
        if len(standard["inputSchema"].get("properties", {})) > 80:
            reasons.append("too-many-arguments")
        if not OpenApiIntake._higress_input_compatible(standard["inputSchema"]):
            reasons.append("higress-runtime-profile-incompatible")
        return reasons

    @staticmethod
    def _higress_input_compatible(schema: Any, *, root: bool = True) -> bool:
        """固定版本 Nacos→Higress 转换器支持的保守输入结构范围。

        原始 JSON Schema 仍遵守标准 MCP；不支持的操作会被明确拒绝，避免注册到
        Higress 时被静默弱化。
        """
        if not isinstance(schema, dict):
            return False
        if any(keyword in schema for keyword in ("allOf", "oneOf", "anyOf", "not")):
            return False
        schema_type = schema.get("type")
        if isinstance(schema_type, list):
            return False
        if root and schema_type != "object":
            return False
        if schema_type not in {None, "object", "array", "string", "integer", "number", "boolean"}:
            return False
        properties = schema.get("properties")
        if properties is not None:
            if not isinstance(properties, dict):
                return False
            if not all(OpenApiIntake._higress_input_compatible(value, root=False) for value in properties.values()):
                return False
        if "items" in schema and not OpenApiIntake._higress_input_compatible(schema["items"], root=False):
            return False
        return True
