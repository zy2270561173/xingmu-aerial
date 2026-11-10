from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from config import Settings
from main import create_app


@pytest.fixture()
def client(tmp_path: Path):
    settings = Settings(
        data_dir=tmp_path,
        database_path=tmp_path / "test.db",
        apk_dir=tmp_path / "apk",
        jwt_secret="test-secret-that-is-long-enough-for-tests",
        admin_username="admin",
        admin_password="test-password",
        cors_origins=("http://localhost:15173",),
        p2p_public_host="203.0.113.10",
        p2p_public_port=18001,
    )
    settings.apk_dir.mkdir(parents=True)
    with TestClient(create_app(settings), client=("192.168.1.25", 50000)) as test_client:
        yield test_client


@pytest.fixture()
def admin_token(client: TestClient) -> str:
    response = client.post(
        "/api/auth/login", json={"username": "admin", "password": "test-password"}
    )
    assert response.status_code == 200
    return response.json()["access_token"]


@pytest.fixture()
def auth_headers(admin_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {admin_token}"}
