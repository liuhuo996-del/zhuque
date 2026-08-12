from __future__ import annotations

import json
from typing import Any

import httpx

from gateforge.settings import Settings
from gateforge.util import slugify


class ToolEnricher:
    """Optional OpenAI-compatible enrichment; deterministic analyzer remains the safe fallback."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    @property
    def mode(self) -> str:
        return "ai" if self.settings.ai_base_url and self.settings.ai_api_key and self.settings.ai_model else "deterministic"

    async def enrich(self, tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if self.mode != "ai" or not tools:
            return tools
        catalog = [{
            "id": tool["id"],
            "name": tool["standard"]["name"],
            "description": tool["standard"]["description"],
            "method": tool["method"],
            "path": tool["path"],
            "inputSchema": tool["standard"]["inputSchema"],
            "domain": tool["governance"]["domain"],
            "intent": tool["governance"]["intent"],
        } for tool in tools if tool["accepted"]]
        prompt = {
            "task": "在不虚构 API 行为的前提下，提高 MCP 工具的可发现性。",
            "requirements": [
                "只返回 JSON：{items:[...]}",
                "不得修改 id",
                "name 必须使用 snake_case 且不超过 80 个字符",
                "description 必须说明何时使用、何时不使用、前置条件和副作用，并使用中文",
                "parameterDescriptions 的键必须存在于 inputSchema.properties 中",
                "domain 和 intent 使用简短中文多标签",
            ],
            "tools": catalog,
        }
        payload = {
            "model": self.settings.ai_model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": "你是一名严谨保守的企业 MCP 工具目录工程师，所有自然语言内容必须使用中文。"},
                {"role": "user", "content": json.dumps(prompt, ensure_ascii=False)},
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.post(
                    self.settings.ai_base_url.rstrip("/") + "/chat/completions",
                    headers={"Authorization": f"Bearer {self.settings.ai_api_key}"},
                    json=payload,
                )
                response.raise_for_status()
                content = response.json()["choices"][0]["message"]["content"]
                items = {str(item["id"]): item for item in json.loads(content).get("items", [])}
        except (httpx.HTTPError, KeyError, TypeError, ValueError, json.JSONDecodeError):
            return tools

        names: set[str] = {tool["standard"]["name"] for tool in tools}
        for tool in tools:
            item = items.get(tool["id"])
            if not item:
                continue
            name = str(item.get("name", "")).strip()
            names.discard(tool["standard"]["name"])
            if name and len(name) <= 80 and name.replace("_", "").isalnum() and name.lower() == name:
                if name not in names:
                    tool["standard"]["name"] = name
            names.add(tool["standard"]["name"])
            description = str(item.get("description", "")).strip()
            if 40 <= len(description) <= 1200:
                tool["standard"]["description"] = description
            properties = tool["standard"]["inputSchema"].get("properties", {})
            for key, value in (item.get("parameterDescriptions") or {}).items():
                if key in properties and isinstance(value, str) and value.strip():
                    properties[key]["description"] = value.strip()[:500]
            for field in ("domain", "intent"):
                values = item.get(field)
                if isinstance(values, list) and values:
                    tool["governance"][field] = [str(value)[:80] for value in values[:5]]
            domain = tool["governance"]["domain"][0] if tool["governance"]["domain"] else "general"
            intent = tool["governance"]["intent"][0] if tool["governance"]["intent"] else "operate"
            tool["cluster_key"] = slugify(f"{domain}-{intent}", "general-operate")
            tool["governance"]["qualityScore"] = min(
                1.0, round(float(tool["governance"]["qualityScore"]) + 0.06, 3)
            )
        return tools
