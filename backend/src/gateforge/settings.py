from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GATEFORGE_", env_file=".env", extra="ignore")

    app_name: str = "GateForge"
    database_path: Path = Path("./data/gateforge.db")
    static_dir: Path = Path("./static")
    allowed_spec_hosts: str = ""
    allow_private_spec_hosts: bool = False
    request_timeout_seconds: float = 10.0

    ai_base_url: str = ""
    ai_api_key: str = ""
    ai_model: str = ""

    nacos_server_url: str = "http://127.0.0.1:8848"
    nacos_context_path: str = "/nacos"
    nacos_namespace: str = "public"
    nacos_username: str = ""
    nacos_password: str = ""
    nacos_min_version: str = "3.0.1"

    l1_allow_origins: str = ""
    l1_allow_unsafe_methods: bool = False
    quality_threshold: float = 0.78

    @property
    def spec_host_allowlist(self) -> set[str]:
        return {value.strip().lower() for value in self.allowed_spec_hosts.split(",") if value.strip()}

    @property
    def l1_origin_allowlist(self) -> set[str]:
        return {value.strip().rstrip("/") for value in self.l1_allow_origins.split(",") if value.strip()}


settings = Settings()
