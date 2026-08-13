from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GATEFORGE_", env_file=".env", extra="ignore")

    app_name: str = "GateForge"
    database_path: Path = Path("./data/gateforge.db")
    static_dir: Path = Path("./static")
    admin_token: str = ""
    settings_encryption_key: str = ""
    allowed_spec_hosts: str = ""
    # GateForge 的默认交付形态是客户网络内自托管，企业 RFC1918/ULA 地址必须开箱可用。
    # 公网多租户部署应显式关闭；回环、链路本地、保留和组播地址始终禁止。
    allow_private_spec_hosts: bool = True
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
        values = self.allowed_spec_hosts.replace("\n", ",")
        return {value.strip().lower().rstrip(".") for value in values.split(",") if value.strip()}

    def spec_host_is_allowed(self, host: str) -> bool:
        normalized = host.lower().rstrip(".")
        return any(
            normalized == pattern
            or (
                pattern.startswith("*.")
                and normalized.endswith(pattern[1:])
                and normalized != pattern[2:]
            )
            for pattern in self.spec_host_allowlist
        )

    @property
    def l1_origin_allowlist(self) -> set[str]:
        return {value.strip().rstrip("/") for value in self.l1_allow_origins.split(",") if value.strip()}


settings = Settings()
