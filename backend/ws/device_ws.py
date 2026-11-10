from __future__ import annotations

import asyncio
import base64
import binascii
import json
import time
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from auth import verify_device_token
from models import AgentEnvelope
from serializers import command_public, device_public, sanitize_media_url


router = APIRouter()
MAX_APP_ICON_BYTES = 48 * 1024
MAX_INVENTORY_ICON_BYTES = 8 * 1024 * 1024
HEARTBEAT_INTERVAL_SECONDS = 5
OFFLINE_TIMEOUT_SECONDS = HEARTBEAT_INTERVAL_SECONDS * 5 + 2


def _safe_int(value: Any, minimum: int = 0) -> int:
    try:
        return min(9_223_372_036_854_775_807, max(minimum, int(value or 0)))
    except (TypeError, ValueError, OverflowError):
        return minimum


def _replace_app_inventory(
    websocket: WebSocket, device: dict[str, Any], data: dict[str, Any], now: int
) -> dict[str, Any]:
    raw_apps = data.get("apps")
    if not isinstance(raw_apps, list) or len(raw_apps) > 1500:
        raise ValueError("invalid app inventory")
    collected_at = _safe_int(data.get("collected_at"), now)
    normalized: dict[str, tuple[Any, ...]] = {}
    icon_bytes_total = 0
    for raw in raw_apps:
        if not isinstance(raw, dict):
            continue
        package_name = str(raw.get("package_name", "")).strip()[:255]
        if not package_name or any(
            character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-"
            for character in package_name
        ):
            continue
        icon_mime = str(raw.get("icon_mime", "")).strip().lower()
        icon_base64 = str(raw.get("icon_base64", "")).strip()
        if icon_mime not in {"image/png", "image/webp"} or not icon_base64:
            icon_mime = ""
            icon_base64 = ""
        else:
            try:
                decoded_icon = base64.b64decode(icon_base64, validate=True)
                if (
                    not decoded_icon
                    or len(decoded_icon) > MAX_APP_ICON_BYTES
                    or icon_bytes_total + len(decoded_icon) > MAX_INVENTORY_ICON_BYTES
                ):
                    icon_mime = ""
                    icon_base64 = ""
                else:
                    icon_bytes_total += len(decoded_icon)
            except (binascii.Error, ValueError):
                icon_mime = ""
                icon_base64 = ""
        normalized[package_name] = (
            device["id"],
            package_name,
            str(raw.get("label", "")).strip()[:128],
            str(raw.get("version_name", "")).strip()[:128],
            _safe_int(raw.get("version_code")),
            int(bool(raw.get("system"))),
            int(bool(raw.get("enabled", True))),
            int(bool(raw.get("launchable"))),
            _safe_int(raw.get("first_install_time")),
            _safe_int(raw.get("last_update_time")),
            icon_mime,
            icon_base64,
            collected_at,
        )
    with websocket.app.state.db.transaction() as connection:
        connection.execute("DELETE FROM device_apps WHERE device_pk = ?", (device["id"],))
        connection.executemany(
            """
            INSERT INTO device_apps(
                device_pk, package_name, label, version_name, version_code,
                system, enabled, launchable, first_install_time, last_update_time,
                icon_mime, icon_base64, collected_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            list(normalized.values()),
        )
    return {
        "total": len(normalized),
        "reported_total": _safe_int(data.get("total"), len(raw_apps)),
        "collected_at": collected_at,
    }


@router.websocket("/ws/device")
async def device_websocket(websocket: WebSocket) -> None:
    await websocket.accept()
    device_id = ""
    try:
        auth_message = await asyncio.wait_for(websocket.receive_json(), timeout=10)
        if not isinstance(auth_message, dict) or auth_message.get("type") != "auth":
            await websocket.close(code=4401, reason="authentication required")
            return
        device_id = str(auth_message.get("device_id", ""))
        token = str(auth_message.get("token", ""))
        device = websocket.app.state.db.fetch_one(
            "SELECT * FROM devices WHERE device_id = ?", (device_id,)
        )
        if device is None or not verify_device_token(token, device["token_hash"]):
            await websocket.close(code=4401, reason="invalid device credentials")
            return
        if device["status"] != "approved" or not device["enabled"]:
            await websocket.close(code=4403, reason="device is not activated")
            return

        await websocket.app.state.hub.set_device(device_id, websocket)
        await _touch_device(websocket, device, {})
        await websocket.app.state.hub.send_device(
            device_id,
            {
                "type": "authenticated",
                "device_id": device_id,
                "status": device["status"],
                "heartbeat_interval": HEARTBEAT_INTERVAL_SECONDS,
            }
        )
        await _broadcast_presence(websocket, device_id, True)
        if device["status"] == "approved" and device["enabled"]:
            await _deliver_pending(websocket, device)
        preview_fps = await websocket.app.state.hub.preview_fps(device_id)
        if preview_fps:
            await websocket.app.state.hub.send_device(
                device_id,
                {"type": "preview_start", "fps": preview_fps, "width": 1280,
                 "height": 720, "quality": 55}
            )

        while True:
            message = await asyncio.wait_for(websocket.receive(), timeout=OFFLINE_TIMEOUT_SECONDS)
            if message.get("type") == "websocket.disconnect":
                raise WebSocketDisconnect(message.get("code", 1000))
            frame = message.get("bytes")
            if frame is not None:
                if (
                    not isinstance(frame, bytes)
                    or len(frame) > websocket.app.state.settings.max_snapshot_bytes
                    or len(frame) < 4
                    or frame[:2] != b"\xff\xd8"
                    or frame[-2:] != b"\xff\xd9"
                ):
                    await websocket.app.state.hub.send_device(
                        device_id, {"type": "error", "code": "INVALID_PREVIEW_FRAME"}
                    )
                    continue
                await websocket.app.state.hub.broadcast_preview_bytes(device_id, frame)
                continue
            text = message.get("text")
            try:
                raw = json.loads(text) if isinstance(text, str) else None
            except json.JSONDecodeError:
                raw = None
            try:
                envelope = AgentEnvelope.model_validate(raw)
            except ValidationError:
                await websocket.app.state.hub.send_device(
                    device_id, {"type": "error", "code": "INVALID_EVENT"}
                )
                continue
            if envelope.device_id != device_id:
                await websocket.close(code=4403, reason="device id mismatch")
                return
            await _handle_event(websocket, device, envelope)
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        if device_id:
            removed = await websocket.app.state.hub.remove_device(device_id, websocket)
            if removed:
                websocket.app.state.db.execute(
                    """
                    UPDATE commands SET status = 'pending', delivered_at = NULL
                    WHERE device_pk = (SELECT id FROM devices WHERE device_id = ?)
                      AND status = 'delivered'
                    """,
                    (device_id,),
                )
                await _broadcast_presence(websocket, device_id, False)


async def _touch_device(
    websocket: WebSocket, device: dict[str, Any], data: dict[str, Any]
) -> None:
    now = int(time.time())
    current = websocket.app.state.db.fetch_one(
        "SELECT * FROM devices WHERE id = ?", (device["id"],)
    ) or device
    metadata = data.get("metadata")
    metadata_json = (
        json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))
        if isinstance(metadata, dict)
        else current.get("metadata_json", "{}")
    )
    websocket.app.state.db.execute(
        """
        UPDATE devices SET last_seen_at = ?, lan_ip = ?, wan_ip = ?, nat_type = ?,
            current_activity = ?, playing_url = ?, position_ms = ?, battery_whitelist = ?,
            model = ?, app_version = ?, metadata_json = ?, updated_at = ? WHERE id = ?
        """,
        (
            now,
            str(data.get("lan_ip", current["lan_ip"]))[:64],
            str(data.get("wan_ip", current["wan_ip"]))[:64],
            str(data.get("nat_type", current["nat_type"]))[:64],
            str(data.get("current_activity", current["current_activity"]))[:256],
            sanitize_media_url(data.get("playing_url", current["playing_url"])),
            max(0, int(data.get("position_ms", current["position_ms"]) or 0)),
            int(bool(data.get("battery_whitelist", current["battery_whitelist"]))),
            str(data.get("model", current["model"]))[:128],
            str(data.get("app_version", current["app_version"]))[:64],
            metadata_json,
            now,
            device["id"],
        ),
    )


async def _handle_event(
    websocket: WebSocket, device: dict[str, Any], envelope: AgentEnvelope
) -> None:
    data = dict(envelope.data)
    if envelope.type == "snapshot":
        snapshot = data.get("screen_base64", "")
        if not isinstance(snapshot, str) or len(snapshot.encode("utf-8")) > websocket.app.state.settings.max_snapshot_bytes:
            await websocket.app.state.hub.send_device(
                device["device_id"], {"type": "error", "code": "SNAPSHOT_TOO_LARGE"}
            )
            return
    await _touch_device(websocket, device, data)
    now = int(time.time())

    event_data = data
    if envelope.type == "app_inventory":
        try:
            event_data = _replace_app_inventory(websocket, device, data, now)
        except ValueError:
            await websocket.app.state.hub.send_device(
                device["device_id"], {"type": "error", "code": "INVALID_APP_INVENTORY"}
            )
            return

    if envelope.type not in {"heartbeat", "snapshot"}:
        websocket.app.state.db.execute(
            "INSERT INTO device_events(device_pk, type, data_json, created_at) VALUES (?, ?, ?, ?)",
            (
                device["id"],
                envelope.type,
                json.dumps(event_data, ensure_ascii=False, separators=(",", ":")),
                now,
            ),
        )

    if envelope.type in {"command_result", "install_result"}:
        await _complete_command(websocket, device, data)
    elif envelope.type == "p2p_signal":
        await _forward_p2p_signal(websocket, device, data, now)

    current = websocket.app.state.db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert current is not None
    event_payload = {
        "type": "device_event",
        "event": envelope.type,
        "ts": envelope.ts or now,
        "data": event_data,
        "device": device_public(current, True),
    }
    if envelope.type == "snapshot":
        await websocket.app.state.hub.set_latest_snapshot(device["device_id"], event_payload)
    await websocket.app.state.hub.broadcast_control(device["device_id"], event_payload)
    await websocket.app.state.hub.send_device(
        device["device_id"], {"type": "ack", "event": envelope.type, "ts": now}
    )


async def _complete_command(
    websocket: WebSocket, device: dict[str, Any], data: dict[str, Any]
) -> None:
    try:
        command_id = int(data.get("command_id", 0))
    except (TypeError, ValueError):
        return
    if command_id <= 0:
        return
    requested_status = str(data.get("status", ""))
    if requested_status == "started":
        status_value = "started"
    else:
        status_value = "completed" if bool(data.get("success")) else "failed"
    completed_at = int(time.time())
    websocket.app.state.db.execute(
        """
        UPDATE commands SET status = ?, result_json = ?, completed_at = ?
        WHERE id = ? AND device_pk = ?
        """,
        (
            status_value,
            json.dumps(data, ensure_ascii=False, separators=(",", ":")),
            None if status_value == "started" else completed_at,
            command_id,
            device["id"],
        ),
    )
    row = websocket.app.state.db.fetch_one(
        """
        SELECT commands.*, devices.device_id FROM commands
        JOIN devices ON devices.id = commands.device_pk WHERE commands.id = ?
        """,
        (command_id,),
    )
    if row is not None:
        await websocket.app.state.hub.broadcast_control(
            device["device_id"],
            {"type": "command_update", "command": command_public(row)},
        )


async def _forward_p2p_signal(
    websocket: WebSocket,
    device: dict[str, Any],
    data: dict[str, Any],
    now: int,
) -> None:
    session_id = str(data.get("session_id", ""))[:128]
    kind = str(data.get("kind", ""))[:32]
    payload = data.get("payload", {})
    if len(session_id) < 8 or kind not in {"offer", "answer", "ice", "ready", "failed"}:
        return
    websocket.app.state.db.execute(
        """
        INSERT INTO p2p_signals(session_id, device_pk, direction, kind, payload_json, created_at)
        VALUES (?, ?, 'device_to_control', ?, ?, ?)
        """,
        (session_id, device["id"], kind, json.dumps(payload, ensure_ascii=False), now),
    )
    await websocket.app.state.hub.broadcast_control(
        device["device_id"],
        {"type": "p2p_signal", "session_id": session_id, "kind": kind, "payload": payload},
    )


async def _deliver_pending(websocket: WebSocket, device: dict[str, Any]) -> None:
    rows = websocket.app.state.db.fetch_all(
        """
        SELECT commands.*, devices.device_id FROM commands
        JOIN devices ON devices.id = commands.device_pk
        WHERE commands.device_pk = ? AND commands.status = 'pending'
        ORDER BY commands.created_at ASC, commands.id ASC LIMIT 100
        """,
        (device["id"],),
    )
    for row in rows:
        command = command_public(row)
        sent = await websocket.app.state.hub.send_device(
            device["device_id"], {"type": "command", "command": command}
        )
        if not sent:
            return
        delivered_at = int(time.time())
        websocket.app.state.db.execute(
            "UPDATE commands SET status = 'delivered', delivered_at = ? WHERE id = ?",
            (delivered_at, row["id"]),
        )


async def _broadcast_presence(websocket: WebSocket, device_id: str, online: bool) -> None:
    await websocket.app.state.hub.broadcast_control(
        device_id,
        {"type": "presence", "device_id": device_id, "online": online, "ts": int(time.time())},
    )
