from __future__ import annotations

import hashlib
import json
import re
import uuid
from datetime import datetime, timezone
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def new_id() -> str:
    return str(uuid.uuid4())


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_json(value).encode()).hexdigest()


def slugify(value: str, fallback: str = "item") -> str:
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value or "").lower()
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value[:63] or fallback


def tool_name(source_slug: str, operation_id: str, method: str, path: str) -> str:
    raw = operation_id or f"{method}_{path}"
    value = f"{source_slug}_{raw}"
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value).lower()
    value = re.sub(r"[^a-z0-9_]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_")
    return (value[:80].rstrip("_") or "api_tool")


def words(*values: str) -> set[str]:
    text = " ".join(value or "" for value in values).lower()
    return {part for part in re.findall(r"[a-z0-9_\u4e00-\u9fff]+", text) if len(part) > 1}

