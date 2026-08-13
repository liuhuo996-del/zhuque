from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet, InvalidToken

from gateforge.errors import GateForgeError
from gateforge.settings import Settings
from gateforge.store import Store
from gateforge.util import now_iso


EDITABLE_FIELDS = {
    "nacos_server_url",
    "nacos_context_path",
    "nacos_namespace",
    "nacos_username",
    "ai_base_url",
    "ai_model",
    "allowed_spec_hosts",
    "allow_private_spec_hosts",
    "l1_allow_origins",
    "l1_allow_unsafe_methods",
}
SECRET_FIELDS = {"nacos_password", "ai_api_key"}


class RuntimeSettings:
    """将页面配置持久化并立即应用到当前进程，敏感值只保存密文。"""

    def __init__(self, settings: Settings, store: Store) -> None:
        self.settings = settings
        self.store = store
        self._cipher: Fernet | None = None
        self.load()

    def load(self) -> None:
        for row in self.store.system_settings():
            key = str(row["key"])
            if key not in EDITABLE_FIELDS | SECRET_FIELDS:
                continue
            raw = str(row["value"])
            if bool(row["secret"]):
                raw = self._decrypt(raw)
            try:
                value = json.loads(raw)
            except json.JSONDecodeError as error:
                raise RuntimeError(f"运行配置 {key} 已损坏") from error
            setattr(self.settings, key, value)

    def update(self, values: dict[str, Any], *, clear_secrets: set[str]) -> None:
        unknown = set(values) - EDITABLE_FIELDS - SECRET_FIELDS
        if unknown:
            raise GateForgeError("包含不可修改的系统配置", ", ".join(sorted(unknown)), 422)

        timestamp = now_iso()
        encoded: dict[str, tuple[str, bool, str]] = {}
        for key, value in values.items():
            if key in SECRET_FIELDS:
                if value is None or value == "":
                    continue
                stored = self._encrypt(json.dumps(value, ensure_ascii=False))
                encoded[key] = (stored, True, timestamp)
            else:
                encoded[key] = (json.dumps(value, ensure_ascii=False), False, timestamp)

        for key in clear_secrets:
            if key in SECRET_FIELDS:
                encoded[key] = (self._encrypt(json.dumps("")), True, timestamp)
        self.store.save_system_settings(encoded)
        for key, value in values.items():
            if key not in SECRET_FIELDS or (value is not None and value != ""):
                setattr(self.settings, key, value)
        for key in clear_secrets:
            if key in SECRET_FIELDS:
                setattr(self.settings, key, "")

    def has_saved_secret(self, key: str) -> bool:
        return bool(getattr(self.settings, key, "")) and any(
            row["key"] == key and bool(row["secret"])
            for row in self.store.system_settings()
        )

    def _encrypt(self, value: str) -> str:
        return self._get_cipher(create=True).encrypt(value.encode()).decode()

    def _decrypt(self, value: str) -> str:
        try:
            return self._get_cipher(create=False).decrypt(value.encode()).decode()
        except InvalidToken as error:
            raise RuntimeError(
                "无法解密已保存的连接密钥；请恢复 GATEFORGE_SETTINGS_ENCRYPTION_KEY 或密钥文件"
            ) from error

    def _get_cipher(self, *, create: bool) -> Fernet:
        if self._cipher is not None:
            return self._cipher
        configured = self.settings.settings_encryption_key.strip()
        if configured:
            try:
                self._cipher = Fernet(configured.encode())
            except ValueError as error:
                raise RuntimeError("GATEFORGE_SETTINGS_ENCRYPTION_KEY 不是有效的 Fernet 密钥") from error
            return self._cipher

        key_path = self._key_path()
        if key_path.is_file():
            key = key_path.read_bytes().strip()
        elif create:
            key_path.parent.mkdir(parents=True, exist_ok=True)
            key = Fernet.generate_key()
            try:
                descriptor = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            except FileExistsError:
                key = key_path.read_bytes().strip()
            else:
                with os.fdopen(descriptor, "wb") as stream:
                    stream.write(key + b"\n")
        else:
            raise RuntimeError(
                f"缺少设置加密密钥文件 {key_path}，无法读取已保存的敏感配置"
            )
        try:
            self._cipher = Fernet(key)
        except ValueError as error:
            raise RuntimeError(f"设置加密密钥文件 {key_path} 无效") from error
        return self._cipher

    def _key_path(self) -> Path:
        database = self.settings.database_path
        return database.parent / ".gateforge-settings.key"
