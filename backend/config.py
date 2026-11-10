from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent


def _csv(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.split(",") if item.strip())


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    database_path: Path
    apk_dir: Path
    jwt_secret: str
    jwt_issuer: str = "starlight-tvcast"
    access_token_minutes: int = 720
    admin_username: str = "admin"
    admin_password: str = "admin123"
    cors_origins: tuple[str, ...] = (
        "http://localhost:15173",
        "http://127.0.0.1:15173",
    )
    public_base_url: str = ""
    max_upload_bytes: int = 200 * 1024 * 1024
    max_snapshot_bytes: int = 1536 * 1024
    p2p_udp_host: str = "0.0.0.0"
    p2p_udp_port: int = 0
    p2p_public_host: str = ""
    p2p_public_port: int = 0

    @classmethod
    def from_env(cls) -> "Settings":
        data_dir = Path(os.getenv("TVCAST_DATA_DIR", BASE_DIR / "data")).resolve()
        data_dir.mkdir(parents=True, exist_ok=True)
        apk_dir = data_dir / "apk"
        apk_dir.mkdir(parents=True, exist_ok=True)
        secret = os.getenv("TVCAST_JWT_SECRET", "").strip() or _persistent_secret(data_dir)
        return cls(
            data_dir=data_dir,
            database_path=Path(
                os.getenv("TVCAST_DATABASE_PATH", data_dir / "tvcast.db")
            ).resolve(),
            apk_dir=apk_dir,
            jwt_secret=secret,
            access_token_minutes=int(os.getenv("TVCAST_ACCESS_TOKEN_MINUTES", "720")),
            admin_username=os.getenv("TVCAST_ADMIN_USERNAME", "admin").strip() or "admin",
            admin_password=os.getenv("TVCAST_ADMIN_PASSWORD", "admin123"),
            cors_origins=_csv(
                os.getenv(
                    "TVCAST_CORS_ORIGINS",
                    "http://localhost:15173,http://127.0.0.1:15173",
                )
            ),
            public_base_url=os.getenv("TVCAST_PUBLIC_BASE_URL", "").rstrip("/"),
            p2p_udp_host=os.getenv("TVCAST_P2P_UDP_HOST", "0.0.0.0").strip() or "0.0.0.0",
            p2p_udp_port=int(os.getenv("TVCAST_P2P_UDP_PORT", "18001")),
            p2p_public_host=os.getenv("TVCAST_P2P_PUBLIC_HOST", "").strip(),
            p2p_public_port=int(os.getenv("TVCAST_P2P_PUBLIC_PORT", os.getenv("TVCAST_P2P_UDP_PORT", "18001"))),
        )


def _persistent_secret(data_dir: Path) -> str:
    secret_path = data_dir / ".jwt-secret"
    if secret_path.exists():
        value = secret_path.read_text(encoding="ascii").strip()
        if value:
            return value
    value = secrets.token_urlsafe(48)
    secret_path.write_text(value, encoding="ascii")
    return value
