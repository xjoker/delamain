"""
delamain Gateway Configuration Loader

Loads TOML configuration files for the single-machine, single-instance,
single-user (multi-equivalent-token) gateway.
"""

import logging
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

# Python 3.11+ has tomllib built-in, older versions need tomli package
if sys.version_info >= (3, 11):
    import tomllib
else:
    try:
        import tomli as tomllib
    except ImportError:
        raise ImportError("Please install 'tomli' package for Python < 3.11: pip install tomli")

logger = logging.getLogger(__name__)


@dataclass
class JadxBackendConfig:
    """Configuration for the single JADX backend this gateway proxies to."""
    host: str = "127.0.0.1"
    port: int = 8650
    token: str = ""


@dataclass
class ServerConfig:
    """MCP Server configuration"""
    host: str = "0.0.0.0"
    port: int = 8651
    transfer_public_url: str = ""  # External base URL of *this gateway* (e.g.,
    # http://192.168.1.100:8651), which proxies PUT /transfer/upload and GET
    # /transfer/status through to the Java backend on localhost:8650. Used for
    # create_transfer_token's upload_url when the gateway's own bind address (e.g.
    # 0.0.0.0) is not itself a reachable hostname/IP from the uploading client.
    allowed_tokens: List[str] = field(default_factory=list)  # MCP client token whitelist


@dataclass
class DefaultsConfig:
    """Default token settings"""
    jadx_token: str = ""  # Default JADX plugin token


@dataclass
class AppConfig:
    """Complete application configuration"""
    server: ServerConfig = field(default_factory=ServerConfig)
    defaults: DefaultsConfig = field(default_factory=DefaultsConfig)
    jadx: JadxBackendConfig = field(default_factory=JadxBackendConfig)

    @classmethod
    def from_dict(cls, data: dict) -> "AppConfig":
        """Create AppConfig from a parsed TOML dictionary"""
        server_data = data.get("server", {})
        defaults_data = data.get("defaults", {})
        jadx_data = data.get("jadx", {})

        server = ServerConfig(
            host=server_data.get("host", "0.0.0.0"),
            port=server_data.get("port", 8651),
            transfer_public_url=server_data.get("transfer_public_url", ""),
            allowed_tokens=list(server_data.get("allowed_tokens", [])),
        )

        defaults = DefaultsConfig(
            jadx_token=defaults_data.get("jadx_token", ""),
        )

        jadx = JadxBackendConfig(
            host=jadx_data.get("host", "127.0.0.1"),
            port=jadx_data.get("port", 8650),
            token=jadx_data.get("token", ""),
        )

        return cls(server=server, defaults=defaults, jadx=jadx)


class ConfigLoader:
    """Loads the gateway TOML config file (no hot-reload — single static load at startup)."""

    def __init__(self, config_path: Path):
        self.config_path = config_path
        self._config: Optional[AppConfig] = None

    def load(self) -> AppConfig:
        """
        Load configuration from TOML file.

        Returns:
            AppConfig object

        Raises:
            FileNotFoundError: if config file doesn't exist
            tomllib.TOMLDecodeError: if config file is invalid
        """
        if not self.config_path.exists():
            logger.warning(f"Config file not found: {self.config_path}, using defaults")
            self._config = AppConfig()
            return self._config

        with open(self.config_path, "rb") as f:
            data = tomllib.load(f)

        self._config = AppConfig.from_dict(data)

        logger.info(f"Loaded configuration from {self.config_path}")
        logger.info(f"  Server: {self._config.server.host}:{self._config.server.port}")
        logger.info(f"  JADX backend: {self._config.jadx.host}:{self._config.jadx.port}")

        return self._config

    @property
    def config(self) -> Optional[AppConfig]:
        """Get current configuration (None if not loaded)"""
        return self._config


# Global config loader instance
_config_loader: Optional[ConfigLoader] = None


def get_config_loader() -> Optional[ConfigLoader]:
    """Get global config loader instance"""
    return _config_loader


def set_config_loader(loader: ConfigLoader) -> None:
    """Set global config loader instance"""
    global _config_loader
    _config_loader = loader
