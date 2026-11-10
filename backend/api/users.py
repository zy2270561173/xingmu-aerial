from __future__ import annotations

import sqlite3
import time

from fastapi import APIRouter, Depends, File, HTTPException, Request, Response, UploadFile, status

from auth import hash_password
from dependencies import require_admin
from models import UserCreateRequest, UserUpdateRequest
from profile_media import read_avatar
from serializers import user_public


router = APIRouter(prefix="/api/users", tags=["users"])


def _base(request: Request) -> str:
    return request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")


@router.get("")
def list_users(request: Request, _admin: dict = Depends(require_admin)) -> list[dict]:
    rows = request.app.state.db.fetch_all(
        """
        SELECT users.*,
          (SELECT COUNT(*) FROM devices
            WHERE devices.owner_user_id = users.id OR EXISTS (
              SELECT 1 FROM device_user_bindings
              WHERE device_user_bindings.device_pk = devices.id
                AND device_user_bindings.user_id = users.id
            )) AS device_count,
          (SELECT COUNT(*) FROM devices
             JOIN pending_agents ON pending_agents.device_id = devices.device_id
            WHERE pending_agents.state = 'pending' AND (
              devices.owner_user_id = users.id OR EXISTS (
                SELECT 1 FROM device_user_bindings
                WHERE device_user_bindings.device_pk = devices.id
                  AND device_user_bindings.user_id = users.id
              )
            )) AS pending_count
        FROM users ORDER BY users.role DESC, users.created_at ASC
        """
    )
    return [user_public(row, _base(request)) for row in rows]


@router.post("", status_code=status.HTTP_201_CREATED)
def create_user(
    payload: UserCreateRequest,
    request: Request,
    _admin: dict = Depends(require_admin),
) -> dict:
    existing = request.app.state.db.fetch_one(
        "SELECT id FROM users WHERE username = ? COLLATE NOCASE OR phone = ? "
        "OR (? <> '' AND (username = ? COLLATE NOCASE OR phone = ?))",
        (payload.username, payload.username, payload.phone, payload.phone, payload.phone),
    )
    if existing is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "username already exists")
    try:
        user_id = request.app.state.db.execute(
            "INSERT INTO users(username, password_hash, nickname, phone, role, active, created_at) "
            "VALUES (?, ?, ?, ?, 'user', 1, ?)",
            (
                payload.username,
                hash_password(payload.password),
                payload.nickname,
                payload.phone,
                int(time.time()),
            ),
        )
    except sqlite3.IntegrityError as error:
        raise HTTPException(status.HTTP_409_CONFLICT, "username or phone already exists") from error
    row = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user_id,))
    assert row is not None
    return user_public(row, _base(request))


@router.patch("/{user_id}")
async def update_user(
    user_id: int,
    payload: UserUpdateRequest,
    request: Request,
    admin: dict = Depends(require_admin),
) -> dict:
    row = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user not found")
    if row["role"] == "admin" and payload.active is False:
        raise HTTPException(status.HTTP_409_CONFLICT, "super administrator cannot be disabled")
    active = int(payload.active) if payload.active is not None else row["active"]
    password_hash = hash_password(payload.password) if payload.password else row["password_hash"]
    nickname = payload.nickname if payload.nickname is not None else row["nickname"]
    phone = payload.phone if payload.phone is not None else row["phone"]
    vip_expires_at = payload.vip_expires_at if payload.vip_expires_at is not None else row["vip_expires_at"]
    vip_tag_style = payload.vip_tag_style if payload.vip_tag_style is not None else row["vip_tag_style"]
    vip_plan_name = payload.vip_plan_name if payload.vip_plan_name is not None else row["vip_plan_name"]
    if vip_expires_at <= int(time.time()):
        vip_plan_name = ""
    conflict = request.app.state.db.fetch_one(
        "SELECT id FROM users WHERE id <> ? AND ? <> '' "
        "AND (phone = ? OR username = ? COLLATE NOCASE)",
        (user_id, phone, phone, phone),
    )
    if conflict is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "phone number is already in use")
    try:
        request.app.state.db.execute(
            "UPDATE users SET password_hash = ?, active = ?, nickname = ?, phone = ?, "
            "vip_expires_at = ?, vip_tag_style = ?, vip_plan_name = ? WHERE id = ?",
            (password_hash, active, nickname, phone, vip_expires_at, vip_tag_style, vip_plan_name, user_id),
        )
    except sqlite3.IntegrityError as error:
        raise HTTPException(status.HTTP_409_CONFLICT, "phone number is already in use") from error
    updated = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user_id,))
    assert updated is not None
    owner = {
        "username": updated["username"],
        "nickname": updated.get("nickname") or "",
        "phone": updated.get("phone") or "",
        "vip_active": int(updated.get("vip_expires_at") or 0) > int(time.time()),
        "vip_expires_at": int(updated.get("vip_expires_at") or 0),
        "vip_tag_style": updated.get("vip_tag_style") or "sky",
        "vip_plan_name": updated.get("vip_plan_name") or "",
    }
    devices = request.app.state.db.fetch_all(
        "SELECT device_id FROM devices WHERE owner_user_id = ?", (user_id,)
    )
    for device in devices:
        await request.app.state.hub.send_device(
            device["device_id"], {"type": "binding_updated", "owner": owner}
        )
    await request.app.state.hub.broadcast_control(
        "*", {"type": "owner_profile_updated", "owner_user_id": user_id}
    )
    return user_public(updated, _base(request))


@router.get("/{user_id}/avatar", include_in_schema=False)
def get_avatar(user_id: int, request: Request) -> Response:
    row = request.app.state.db.fetch_one(
        "SELECT avatar_mime, avatar_blob, avatar_updated_at FROM users WHERE id = ?", (user_id,)
    )
    if row is None or not row["avatar_blob"] or not row["avatar_mime"]:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "avatar not found")
    return Response(
        content=row["avatar_blob"],
        media_type=row["avatar_mime"],
        headers={"Cache-Control": "public, max-age=86400", "ETag": str(row["avatar_updated_at"])},
    )


@router.post("/{user_id}/avatar")
async def admin_update_avatar(
    user_id: int,
    request: Request,
    file: UploadFile = File(...),
    _admin: dict = Depends(require_admin),
) -> dict:
    row = request.app.state.db.fetch_one("SELECT id FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user not found")
    mime, data = await read_avatar(file)
    now = int(time.time())
    request.app.state.db.execute(
        "UPDATE users SET avatar_mime = ?, avatar_blob = ?, avatar_updated_at = ? WHERE id = ?",
        (mime, data, now, user_id),
    )
    updated = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user_id,))
    assert updated is not None
    return user_public(updated, _base(request))


@router.delete("/{user_id}/avatar")
def admin_delete_avatar(
    user_id: int, request: Request, _admin: dict = Depends(require_admin)
) -> dict:
    request.app.state.db.execute(
        "UPDATE users SET avatar_mime = '', avatar_blob = NULL, avatar_updated_at = 0 WHERE id = ?",
        (user_id,),
    )
    return {"deleted": True}


@router.delete("/{user_id}")
async def delete_user(
    user_id: int,
    request: Request,
    admin: dict = Depends(require_admin),
) -> dict:
    row = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user not found")
    if row["role"] == "admin" or user_id == admin["id"]:
        raise HTTPException(status.HTTP_409_CONFLICT, "super administrator cannot be deleted")
    now = int(time.time())
    unbound_device_ids: list[str] = []
    promotions: list[dict] = []
    with request.app.state.db.transaction() as connection:
        primary_devices = connection.execute(
            "SELECT id, device_id FROM devices WHERE owner_user_id = ?", (user_id,)
        ).fetchall()
        for device in primary_devices:
            replacement = connection.execute(
                """
                SELECT users.* FROM device_user_bindings
                JOIN users ON users.id = device_user_bindings.user_id
                WHERE device_user_bindings.device_pk = ? AND users.active = 1
                ORDER BY device_user_bindings.created_at, device_user_bindings.id LIMIT 1
                """,
                (device["id"],),
            ).fetchone()
            if replacement is not None:
                connection.execute(
                    "UPDATE devices SET owner_user_id = ?, updated_at = ? WHERE id = ?",
                    (replacement["id"], now, device["id"]),
                )
                connection.execute(
                    "DELETE FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
                    (device["id"], replacement["id"]),
                )
                promotions.append({"device_id": device["device_id"], "owner": dict(replacement)})
            else:
                connection.execute(
                    "UPDATE devices SET owner_user_id = NULL, status = 'pending', updated_at = ? WHERE id = ?",
                    (now, device["id"]),
                )
                connection.execute(
                    "INSERT INTO pending_agents(device_id, requested_at, state) VALUES (?, ?, 'pending') "
                    "ON CONFLICT(device_id) DO UPDATE SET state = 'pending', requested_at = excluded.requested_at, "
                    "reviewed_at = NULL, reviewed_by = NULL",
                    (device["device_id"], now),
                )
                unbound_device_ids.append(device["device_id"])
        for table, column in (
            ("commands", "created_by"),
            ("apks", "uploaded_by"),
            ("app_releases", "published_by"),
            ("settings", "updated_by"),
            ("pairing_sessions", "claimed_by"),
            ("vip_orders", "created_by"),
            ("vip_orders", "reviewed_by"),
            ("vip_codes", "created_by"),
            ("vip_codes", "redeemed_by"),
        ):
            connection.execute(f"UPDATE {table} SET {column} = NULL WHERE {column} = ?", (user_id,))
        connection.execute("UPDATE pending_agents SET reviewed_by = NULL WHERE reviewed_by = ?", (user_id,))
        connection.execute("DELETE FROM users WHERE id = ?", (user_id,))
    base = _base(request)
    for affected in [
        *({"device_id": device_id, "owner": None} for device_id in unbound_device_ids),
        *promotions,
    ]:
        device_id = affected["device_id"]
        # Existing user sessions are detached immediately; the Agent itself stays connected.
        if affected["owner"] is None:
            request.app.state.db.execute(
                "DELETE FROM pairing_sessions WHERE device_pk = (SELECT id FROM devices WHERE device_id = ?) "
                "AND claimed_at IS NULL",
                (device_id,),
            )
        await request.app.state.hub.disconnect_controls(
            device_id, reason="device owner account was deleted"
        )
        await request.app.state.hub.send_device(
            device_id,
            {
                "type": "binding_updated",
                "owner": user_public(affected["owner"], base) if affected["owner"] else None,
            },
        )
        await request.app.state.hub.broadcast_control(
            "*", {"type": "device_updated", "device_id": device_id}
        )
    return {
        "deleted": True,
        "user_id": user_id,
        "unbound_device_ids": unbound_device_ids,
        "promoted_device_ids": [item["device_id"] for item in promotions],
    }
