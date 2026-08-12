from __future__ import annotations

import re
from typing import Any

from jsonschema import Draft202012Validator, SchemaError


MCP_TOOL_FIELDS = {
    "name", "title", "description", "inputSchema", "outputSchema", "annotations", "_meta"
}
MCP_ANNOTATION_FIELDS = {
    "title", "readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"
}


def validate_mcp_tool(tool: dict[str, Any]) -> list[str]:
    """校验 GateForge 输出的 MCP 2025-06-18 工具定义子集。"""
    errors: list[str] = []
    unknown = set(tool) - MCP_TOOL_FIELDS
    if unknown:
        errors.append("非 MCP Tool 字段: " + ", ".join(sorted(unknown)))
    name = tool.get("name")
    if not isinstance(name, str) or not name or len(name) > 128:
        errors.append("name 必须是 1..128 字符字符串")
    elif re.fullmatch(r"[A-Za-z0-9_.-]+", name) is None:
        errors.append("工具名含 MCP 规范不允许的字符")
    description = tool.get("description")
    if not isinstance(description, str) or not description.strip():
        errors.append("工具描述必须是非空字符串")
    schema = tool.get("inputSchema")
    if not isinstance(schema, dict) or schema.get("type") != "object":
        errors.append("输入结构的根类型必须为对象")
    else:
        try:
            Draft202012Validator.check_schema(schema)
        except SchemaError as error:
            errors.append("输入结构不合法：" + error.message)
    annotations = tool.get("annotations")
    if annotations is not None:
        if not isinstance(annotations, dict):
            errors.append("工具注解必须是对象")
        else:
            if set(annotations) - MCP_ANNOTATION_FIELDS:
                errors.append("工具注解包含非标准字段")
            for key, value in annotations.items():
                if key != "title" and not isinstance(value, bool):
                    errors.append(f"annotations.{key} 必须是 boolean")
    return errors
