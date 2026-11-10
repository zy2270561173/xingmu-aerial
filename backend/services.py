from __future__ import annotations

import json
import time
from typing import Any

from fastapi import HTTPException, status

from serializers import command_public


def user_has_device_access(db: Any, device: dict[str, Any], user: dict[str, Any]) -> bool:
    if user["role"] == "admin" or device.get("owner_user_id") == user["id"]:
        return True
    binding = db.fetch_one(
        "SELECT 1 FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
        (device["id"], user["id"]),
    )
    return binding is not None


def user_device_count(db: Any, user_id: int, exclude_device_pk: int | None = None) -> int:
    parameters: list[Any] = [user_id, user_id]
    excluded = ""
    if exclude_device_pk is not None:
        excluded = " AND devices.id <> ?"
        parameters.append(exclude_device_pk)
    row = db.fetch_one(
        """
        SELECT COUNT(*) AS total FROM devices
        WHERE (devices.owner_user_id = ? OR EXISTS (
          SELECT 1 FROM device_user_bindings
          WHERE device_user_bindings.device_pk = devices.id
            AND device_user_bindings.user_id = ?
        ))
        """ + excluded,
        tuple(parameters),
    )
    return int(row["total"] if row else 0)


def ensure_user_device_capacity(
    db: Any, user: dict[str, Any], exclude_device_pk: int | None = None
) -> None:
    if user.get("role") == "admin":
        return
    active = int(user.get("vip_expires_at") or 0) > int(time.time())
    limit = int(user.get("vip_device_limit") or 1) if active else 1
    if user_device_count(db, int(user["id"]), exclude_device_pk) >= limit:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            f"当前账号最多绑定 {limit} 台电视，请先解绑旧设备或升级套餐",
        )


def get_device_for_user(db: Any, device_id: str, user: dict[str, Any]) -> dict[str, Any]:
    device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (device_id,))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device not found")
    if not user_has_device_access(db, device, user):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "device access denied")
    return device


def ensure_controllable(device: dict[str, Any]) -> None:
    if device["status"] != "approved":
        raise HTTPException(status.HTTP_409_CONFLICT, "device is not approved")
    if not device["enabled"]:
        raise HTTPException(status.HTTP_409_CONFLICT, "device is disabled")


def create_command(
    db: Any,
    device: dict[str, Any],
    command_type: str,
    payload: dict[str, Any],
    created_by: int | None,
) -> dict[str, Any]:
    now = int(time.time())
    command_id = db.execute(
        """
        INSERT INTO commands(device_pk, type, payload_json, created_by, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (
            device["id"],
            command_type,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            created_by,
            now,
        ),
    )
    row = db.fetch_one(
        """
        SELECT commands.*, devices.device_id
        FROM commands JOIN devices ON devices.id = commands.device_pk
        WHERE commands.id = ?
        """,
        (command_id,),
    )
    assert row is not None
    return command_public(row)


async def dispatch_command(app: Any, command: dict[str, Any]) -> bool:
    delivered = await app.state.hub.send_device(
        command["device_id"], {"type": "command", "command": command}
    )
    if delivered:
        delivered_at = int(time.time())
        app.state.db.execute(
            "UPDATE commands SET status = 'delivered', delivered_at = ? WHERE id = ? AND status = 'pending'",
            (delivered_at, command["id"]),
        )
        command["status"] = "delivered"
        command["delivered_at"] = delivered_at
    await app.state.hub.broadcast_control(
        command["device_id"], {"type": "command_update", "command": command}
    )
    return delivered
