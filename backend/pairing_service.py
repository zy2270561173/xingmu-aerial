from __future__ import annotations

import hashlib
import secrets
import time
from typing import Any
from urllib.parse import quote


PAIRING_TTL_SECONDS = 10 * 60


def pairing_digest(code: str) -> str:
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


def public_base(request: Any) -> str:
    return request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")


def issue_pending_pairing(request: Any, device: dict[str, Any]) -> dict[str, Any] | None:
    if device.get("status") != "pending" or device.get("owner_user_id") is not None:
        return None
    now = int(time.time())
    existing = request.app.state.db.fetch_one(
        """
        SELECT code_value, expires_at FROM pairing_sessions
        WHERE device_pk = ? AND purpose = 'app' AND claimed_at IS NULL
          AND expires_at > ? AND code_value <> ''
        ORDER BY expires_at DESC LIMIT 1
        """,
        (device["id"], now),
    )
    if existing is None:
        code = secrets.token_urlsafe(24)
        expires_at = now + PAIRING_TTL_SECONDS
        with request.app.state.db.transaction() as connection:
            connection.execute(
                "DELETE FROM pairing_sessions WHERE device_pk = ? AND claimed_at IS NULL",
                (device["id"],),
            )
            connection.execute(
                """
                INSERT INTO pairing_sessions(
                    code_hash, code_value, purpose, device_pk, created_at, expires_at
                ) VALUES (?, ?, 'app', ?, ?, ?)
                """,
                (pairing_digest(code), code, device["id"], now, expires_at),
            )
    else:
        code = str(existing["code_value"])
        expires_at = int(existing["expires_at"])
    base = public_base(request)
    uri = (
        "starlight-tvcast://pair?server="
        + quote(base, safe="")
        + "&code="
        + quote(code, safe="")
    )
    return {
        "code": code,
        "uri": uri,
        "web_url": f"{base}/pair/{quote(code, safe='')}",
        "expires_at": expires_at,
        "status_url": f"{base}/api/pairing/{quote(code, safe='')}",
        "next_step": "scan_then_approve",
        "approval_path": "/approvals",
        "approval_api": "/api/device-approvals",
    }
