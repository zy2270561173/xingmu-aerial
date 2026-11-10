from __future__ import annotations

import json
import re
import time

from fastapi import APIRouter, Depends, HTTPException, Request, status

from auth import create_device_token, hash_device_token, verify_device_token
from dependencies import get_current_user, require_admin
from models import DeviceBindingCreateRequest, DeviceRegisterRequest, DeviceUpdateRequest
from pairing_service import issue_pending_pairing
from serializers import device_public, parse_json, user_public
from services import ensure_user_device_capacity, get_device_for_user


router = APIRouter(tags=["devices"])


def _binding_public(row: dict, role: str, bound_at: int) -> dict:
    return {
        **user_public(row),
        "binding_role": role,
        "bound_at": bound_at,
    }


def _device_bindings(db, device: dict) -> list[dict]:
    result: list[dict] = []
    if device.get("owner_user_id"):
        owner = db.fetch_one("SELECT * FROM users WHERE id = ?", (device["owner_user_id"],))
        if owner is not None:
            result.append(_binding_public(owner, "owner", int(device["updated_at"])))
    members = db.fetch_all(
        """
        SELECT users.*, device_user_bindings.created_at AS bound_at
        FROM device_user_bindings
        JOIN users ON users.id = device_user_bindings.user_id
        WHERE device_user_bindings.device_pk = ?
        ORDER BY device_user_bindings.created_at, device_user_bindings.id
        """,
        (device["id"],),
    )
    result.extend(
        _binding_public(member, "member", int(member["bound_at"])) for member in members
    )
    return result


def _ensure_primary_or_admin(device: dict, user: dict) -> None:
    if user["role"] != "admin" and device.get("owner_user_id") != user["id"]:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only the primary account can manage the device")


def _version_tuple(value: str) -> tuple[int, ...] | None:
    match = re.match(r"^\D*(\d+)(?:\.(\d+))?(?:\.(\d+))?", value.strip())
    if match is None:
        return None
    return tuple(int(part or 0) for part in match.groups())


async def _can_recover_credentials(existing: dict, payload: DeviceRegisterRequest, request: Request) -> bool:
    old_version = _version_tuple(existing["app_version"])
    new_version = _version_tuple(payload.app_version)
    source_host = request.client.host if request.client is not None else ""
    return (
        existing["status"] in {"pending", "approved"}
        and not await request.app.state.hub.is_device_online(payload.device_id)
        and bool(existing["lan_ip"])
        and payload.lan_ip == existing["lan_ip"] == source_host
        and payload.platform == existing["platform"]
        and bool(existing["model"])
        and payload.model == existing["model"]
        and old_version is not None
        and new_version is not None
        and new_version > old_version
    )


async def _replace_stale_duplicates(
    current: dict, payload: DeviceRegisterRequest, request: Request, now: int
) -> list[str]:
    if not payload.lan_ip or request.client is None or request.client.host != payload.lan_ip:
        return []
    current_version = _version_tuple(payload.app_version)
    if current_version is None:
        return []
    candidates = request.app.state.db.fetch_all(
        """
        SELECT * FROM devices
        WHERE device_id != ? AND platform = ? AND model = ? AND lan_ip = ?
        ORDER BY updated_at DESC
        """,
        (payload.device_id, payload.platform, payload.model, payload.lan_ip),
    )
    stale: list[dict] = []
    for candidate in candidates:
        old_version = _version_tuple(candidate["app_version"])
        if (
            old_version is not None
            and current_version > old_version
            and not await request.app.state.hub.is_device_online(candidate["device_id"])
        ):
            stale.append(candidate)
    if not stale:
        return []

    with request.app.state.db.transaction() as connection:
        connection.execute(
            """
            INSERT INTO device_events(device_pk, type, data_json, created_at)
            VALUES (?, 'stale_device_replaced', ?, ?)
            """,
            (
                current["id"],
                json.dumps(
                    {
                        "replaced_device_ids": [item["device_id"] for item in stale],
                        "replaced_app_versions": [item["app_version"] for item in stale],
                        "new_app_version": payload.app_version,
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                now,
            ),
        )
        connection.executemany(
            "DELETE FROM devices WHERE id = ?", [(item["id"],) for item in stale]
        )
    for item in stale:
        await request.app.state.hub.disconnect_device(item["device_id"])
        await request.app.state.hub.broadcast_control(
            item["device_id"], {"type": "device_deleted", "device_id": item["device_id"]}
        )
    return [item["device_id"] for item in stale]


@router.post("/api/device/register")
async def register_device(payload: DeviceRegisterRequest, request: Request) -> dict:
    db = request.app.state.db
    now = int(time.time())
    requested_owner = None
    if payload.owner_username or payload.owner_phone:
        requested_owner = db.fetch_one(
            "SELECT id, username, phone FROM users WHERE active = 1 AND "
            "((? <> '' AND username = ? COLLATE NOCASE) OR (? <> '' AND phone = ?))",
            (
                payload.owner_username,
                payload.owner_username,
                payload.owner_phone,
                payload.owner_phone,
            ),
        )
        if requested_owner is None:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "binding account does not exist or is disabled")
    existing = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (payload.device_id,))
    issued_token: str | None = None
    credentials_recovered = False
    stale_registration_replaced = False
    if existing is None:
        issued_token = create_device_token()
        with db.transaction() as connection:
            connection.execute(
                """
                INSERT INTO devices(
                    device_id, name, owner_user_id, token_hash, platform, model, app_version,
                    lan_ip, public_key, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    payload.device_id,
                    payload.name,
                    requested_owner["id"] if requested_owner else None,
                    hash_device_token(issued_token),
                    payload.platform,
                    payload.model,
                    payload.app_version,
                    payload.lan_ip,
                    payload.public_key,
                    now,
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO pending_agents(device_id, requested_at, state) VALUES (?, ?, 'pending')",
                (payload.device_id, now),
            )
    else:
        if not payload.token or not verify_device_token(payload.token, existing["token_hash"]):
            if not await _can_recover_credentials(existing, payload, request):
                raise HTTPException(status.HTTP_409_CONFLICT, "device already registered")
            issued_token = create_device_token()
            credentials_recovered = True
            stale_registration_replaced = True
            with db.transaction() as connection:
                connection.execute("DELETE FROM devices WHERE id = ?", (existing["id"],))
                cursor = connection.execute(
                    """
                    INSERT INTO devices(
                        device_id, name, owner_user_id, token_hash, platform, model, app_version,
                        lan_ip, public_key, created_at, updated_at
                    ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        payload.device_id,
                        payload.name,
                        hash_device_token(issued_token),
                        payload.platform,
                        payload.model,
                        payload.app_version,
                        payload.lan_ip,
                        payload.public_key,
                        now,
                        now,
                    ),
                )
                connection.execute(
                    """
                    INSERT INTO pending_agents(device_id, requested_at, state)
                    VALUES (?, ?, 'pending')
                    """,
                    (payload.device_id, now),
                )
                connection.execute(
                    """
                    INSERT INTO device_events(device_pk, type, data_json, created_at)
                    VALUES (?, 'stale_registration_replaced', ?, ?)
                    """,
                    (
                        cursor.lastrowid,
                        json.dumps(
                            {
                                "old_app_version": existing["app_version"],
                                "new_app_version": payload.app_version,
                                "source_lan_ip": payload.lan_ip,
                            },
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                        now,
                    ),
                )
        else:
            issued_token = payload.token
            db.execute(
                """
                UPDATE devices SET name = ?, platform = ?, model = ?, app_version = ?,
                    lan_ip = ?, public_key = ?, owner_user_id = CASE
                        WHEN status = 'pending' AND ? IS NOT NULL THEN ? ELSE owner_user_id END,
                    updated_at = ? WHERE device_id = ?
                """,
                (
                    payload.name,
                    payload.platform,
                    payload.model,
                    payload.app_version,
                    payload.lan_ip,
                    payload.public_key,
                    requested_owner["id"] if requested_owner else None,
                    requested_owner["id"] if requested_owner else None,
                    now,
                    payload.device_id,
                ),
            )
    device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (payload.device_id,))
    assert device is not None
    replaced_device_ids = await _replace_stale_duplicates(device, payload, request, now)
    if replaced_device_ids:
        device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (payload.device_id,))
        assert device is not None
    online = await request.app.state.hub.is_device_online(payload.device_id)
    response = device_public(device, online)
    owner = None
    if device.get("owner_user_id"):
        owner_row = db.fetch_one(
            "SELECT username, nickname, phone, vip_expires_at, vip_tag_style, vip_plan_name, "
            "vip_device_limit, vip_benefits_json, vip_entitlements_json, vip_badge_text, vip_accent_color "
            "FROM users WHERE id = ?", (device["owner_user_id"],)
        )
        if owner_row is not None:
            owner = {
                "username": owner_row["username"],
                "nickname": owner_row.get("nickname") or "",
                "phone": owner_row.get("phone") or "",
                "vip_active": int(owner_row.get("vip_expires_at") or 0) > int(time.time()),
                "vip_expires_at": int(owner_row.get("vip_expires_at") or 0),
                "vip_tag_style": owner_row.get("vip_tag_style") or "sky",
                "vip_plan_name": owner_row.get("vip_plan_name") or "",
                "vip_device_limit": int(owner_row.get("vip_device_limit") or 1),
                "vip_benefits": parse_json(owner_row.get("vip_benefits_json"), []) or [],
                "vip_entitlements": parse_json(owner_row.get("vip_entitlements_json"), {}) or {},
                "vip_badge_text": owner_row.get("vip_badge_text") or "",
                "vip_accent_color": owner_row.get("vip_accent_color") or "#2F80ED",
            }
    response["owner"] = owner
    response["device_token"] = issued_token
    response["credentials_recovered"] = credentials_recovered
    response["stale_registration_replaced"] = stale_registration_replaced
    response["replaced_device_ids"] = replaced_device_ids
    response["pairing"] = issue_pending_pairing(request, device)
    activated = device["status"] == "approved" and bool(device["enabled"])
    response["agent_activation"] = {
        "activated": activated,
        "status": device["status"],
        "requires_approval": device["status"] == "pending",
        "local_cast_allowed": True,
        "remote_control_allowed": activated,
        "remote_screen_allowed": activated,
    }
    if device["status"] == "pending":
        await request.app.state.hub.broadcast_control(
            "*", {"type": "approval_requested", "device": response | {"device_token": None}}
        )
    return response


@router.get("/api/devices")
async def list_devices(request: Request, user: dict = Depends(get_current_user)) -> list[dict]:
    if user["role"] == "admin":
        rows = request.app.state.db.fetch_all("SELECT * FROM devices ORDER BY updated_at DESC")
    else:
        rows = request.app.state.db.fetch_all(
            """
            SELECT * FROM devices
            WHERE owner_user_id = ? OR EXISTS (
              SELECT 1 FROM device_user_bindings
              WHERE device_user_bindings.device_pk = devices.id
                AND device_user_bindings.user_id = ?
            )
            ORDER BY updated_at DESC
            """,
            (user["id"], user["id"]),
        )
    return [
        device_public(row, await request.app.state.hub.is_device_online(row["device_id"]))
        for row in rows
    ]


@router.get("/api/device/{device_id}")
async def get_device(device_id: str, request: Request, user: dict = Depends(get_current_user)) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    return device_public(device, await request.app.state.hub.is_device_online(device_id))


@router.patch("/api/device/{device_id}")
async def update_device(
    device_id: str,
    payload: DeviceUpdateRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    _ensure_primary_or_admin(device, user)
    name = payload.name if payload.name is not None else device["name"]
    enabled = int(payload.enabled) if payload.enabled is not None else device["enabled"]
    owner_user_id = device["owner_user_id"]
    if payload.owner_user_id is not None:
        if user["role"] != "admin":
            raise HTTPException(status.HTTP_403_FORBIDDEN, "only an administrator can assign a device owner")
        owner = request.app.state.db.fetch_one(
            "SELECT * FROM users WHERE id = ? AND active = 1",
            (payload.owner_user_id,),
        )
        if owner is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "device owner account is unavailable")
        ensure_user_device_capacity(request.app.state.db, owner, int(device["id"]))
        owner_user_id = owner["id"]
    owner_changed = owner_user_id != device["owner_user_id"]
    with request.app.state.db.transaction() as connection:
        connection.execute(
            "UPDATE devices SET name = ?, enabled = ?, owner_user_id = ?, updated_at = ? WHERE id = ?",
            (name, enabled, owner_user_id, int(time.time()), device["id"]),
        )
        if owner_changed and owner_user_id:
            connection.execute(
                "DELETE FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
                (device["id"], owner_user_id),
            )
    updated = request.app.state.db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert updated is not None
    public = device_public(updated, await request.app.state.hub.is_device_online(device_id))
    if owner_changed:
        await request.app.state.hub.disconnect_controls(
            device_id,
            reason="device ownership changed",
        )
        owner_row = request.app.state.db.fetch_one(
            "SELECT * FROM users WHERE id = ?", (owner_user_id,)
        ) if owner_user_id else None
        base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
        await request.app.state.hub.send_device(
            device_id,
            {"type": "binding_updated", "owner": user_public(owner_row, base) if owner_row else None},
        )
        await request.app.state.hub.broadcast_control(
            "*", {"type": "device_updated", "device": public}
        )
    else:
        await request.app.state.hub.broadcast_control(
            device_id, {"type": "device_updated", "device": public}
        )
    return public


@router.get("/api/device/{device_id}/bindings")
def list_device_bindings(
    device_id: str,
    request: Request,
    _admin: dict = Depends(require_admin),
) -> list[dict]:
    device = request.app.state.db.fetch_one(
        "SELECT * FROM devices WHERE device_id = ?", (device_id,)
    )
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device not found")
    return _device_bindings(request.app.state.db, device)


@router.post("/api/device/{device_id}/bindings", status_code=status.HTTP_201_CREATED)
async def add_device_binding(
    device_id: str,
    payload: DeviceBindingCreateRequest,
    request: Request,
    admin: dict = Depends(require_admin),
) -> list[dict]:
    db = request.app.state.db
    device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (device_id,))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device not found")
    if device["status"] != "approved" or not device.get("owner_user_id"):
        raise HTTPException(status.HTTP_409_CONFLICT, "device needs an approved primary account")
    user = db.fetch_one("SELECT * FROM users WHERE id = ? AND active = 1", (payload.user_id,))
    if user is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "binding account is unavailable")
    if payload.user_id == device["owner_user_id"]:
        raise HTTPException(status.HTTP_409_CONFLICT, "account is already the primary account")
    existing = db.fetch_one(
        "SELECT id FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
        (device["id"], payload.user_id),
    )
    if existing is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "account is already bound to the device")
    ensure_user_device_capacity(db, user, int(device["id"]))
    db.execute(
        "INSERT INTO device_user_bindings(device_pk, user_id, created_by, created_at) VALUES (?, ?, ?, ?)",
        (device["id"], payload.user_id, admin["id"], int(time.time())),
    )
    await request.app.state.hub.broadcast_control(
        "*", {"type": "device_bindings_updated", "device_id": device_id}
    )
    return _device_bindings(db, device)


@router.put("/api/device/{device_id}/bindings/{user_id}/primary")
async def set_primary_device_binding(
    device_id: str,
    user_id: int,
    request: Request,
    admin: dict = Depends(require_admin),
) -> list[dict]:
    db = request.app.state.db
    device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (device_id,))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device not found")
    if user_id == device.get("owner_user_id"):
        return _device_bindings(db, device)
    user = db.fetch_one("SELECT * FROM users WHERE id = ? AND active = 1", (user_id,))
    member = db.fetch_one(
        "SELECT id FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
        (device["id"], user_id),
    )
    if user is None or member is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "bound account not found")
    now = int(time.time())
    old_owner_id = device.get("owner_user_id")
    with db.transaction() as connection:
        if old_owner_id:
            connection.execute(
                "INSERT OR IGNORE INTO device_user_bindings(device_pk, user_id, created_by, created_at) "
                "VALUES (?, ?, ?, ?)",
                (device["id"], old_owner_id, admin["id"], now),
            )
        connection.execute(
            "DELETE FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
            (device["id"], user_id),
        )
        connection.execute(
            "UPDATE devices SET owner_user_id = ?, updated_at = ? WHERE id = ?",
            (user_id, now, device["id"]),
        )
    await request.app.state.hub.disconnect_controls(device_id, reason="primary account changed")
    await request.app.state.hub.send_device(
        device_id,
        {"type": "binding_updated", "owner": user_public(user, request.app.state.settings.public_base_url or str(request.base_url).rstrip("/"))},
    )
    await request.app.state.hub.broadcast_control(
        "*", {"type": "device_bindings_updated", "device_id": device_id}
    )
    updated = db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert updated is not None
    return _device_bindings(db, updated)


@router.delete("/api/device/{device_id}/bindings/{user_id}")
async def remove_device_binding(
    device_id: str,
    user_id: int,
    request: Request,
    _admin: dict = Depends(require_admin),
) -> list[dict]:
    db = request.app.state.db
    device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (device_id,))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device not found")
    if user_id == device.get("owner_user_id"):
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "primary account cannot be removed; set another account as primary or unbind all accounts",
        )
    with db.transaction() as connection:
        deleted = connection.execute(
            "DELETE FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
            (device["id"], user_id),
        )
        if deleted.rowcount != 1:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "bound account not found")
    await request.app.state.hub.disconnect_controls(device_id, reason="device access changed")
    await request.app.state.hub.broadcast_control(
        "*", {"type": "device_bindings_updated", "device_id": device_id}
    )
    return _device_bindings(db, device)


@router.delete("/api/device/{device_id}")
async def delete_device(
    device_id: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    _ensure_primary_or_admin(device, user)
    request.app.state.db.execute("DELETE FROM devices WHERE id = ?", (device["id"],))
    await request.app.state.hub.disconnect_device(device_id)
    await request.app.state.hub.broadcast_control(
        device_id, {"type": "device_deleted", "device_id": device_id}
    )
    return {"deleted": True, "device_id": device_id}


@router.delete("/api/device/{device_id}/binding")
async def unbind_device(
    device_id: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    _ensure_primary_or_admin(device, user)
    now = int(time.time())
    with request.app.state.db.transaction() as connection:
        connection.execute(
            "DELETE FROM device_user_bindings WHERE device_pk = ?", (device["id"],)
        )
        connection.execute(
            "UPDATE devices SET owner_user_id = NULL, status = 'pending', updated_at = ? WHERE id = ?",
            (now, device["id"]),
        )
        connection.execute(
            "INSERT INTO pending_agents(device_id, requested_at, state) VALUES (?, ?, 'pending') "
            "ON CONFLICT(device_id) DO UPDATE SET state = 'pending', requested_at = excluded.requested_at, "
            "reviewed_at = NULL, reviewed_by = NULL",
            (device_id, now),
        )
        connection.execute(
            "DELETE FROM pairing_sessions WHERE device_pk = ? AND claimed_at IS NULL", (device["id"],)
        )
    await request.app.state.hub.disconnect_controls(device_id, reason="device was unbound")
    await request.app.state.hub.send_device(device_id, {"type": "binding_updated", "owner": None})
    updated = request.app.state.db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert updated is not None
    public = device_public(updated, await request.app.state.hub.is_device_online(device_id))
    await request.app.state.hub.broadcast_control("*", {"type": "device_updated", "device": public})
    return public


@router.get("/api/device/{device_id}/events")
def list_device_events(
    device_id: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> list[dict]:
    device = get_device_for_user(request.app.state.db, device_id, user)
    rows = request.app.state.db.fetch_all(
        """
        SELECT id, type, data_json, created_at FROM device_events
        WHERE device_pk = ? AND type != 'snapshot'
        ORDER BY created_at DESC, id DESC LIMIT 100
        """,
        (device["id"],),
    )
    return [
        {
            "id": row["id"],
            "type": row["type"],
            "data": json.loads(row["data_json"]),
            "created_at": row["created_at"],
        }
        for row in rows
    ]


@router.get("/api/device/{device_id}/apps")
def list_device_apps(
    device_id: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> list[dict]:
    device = get_device_for_user(request.app.state.db, device_id, user)
    rows = request.app.state.db.fetch_all(
        """
        SELECT package_name, label, version_name, version_code, system, enabled,
            launchable, first_install_time, last_update_time, icon_mime,
            icon_base64, collected_at
        FROM device_apps
        WHERE device_pk = ?
        ORDER BY label COLLATE NOCASE, package_name COLLATE NOCASE
        """,
        (device["id"],),
    )
    return [
        {
            **row,
            "icon_data_uri": (
                f"data:{row['icon_mime']};base64,{row['icon_base64']}"
                if row["icon_mime"] and row["icon_base64"]
                else ""
            ),
            "system": bool(row["system"]),
            "enabled": bool(row["enabled"]),
            "launchable": bool(row["launchable"]),
        }
        for row in rows
    ]
