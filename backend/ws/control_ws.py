from __future__ import annotations

import asyncio
import time

import jwt
from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from auth import decode_access_token
from access import require_entitlement
from api.commands import ensure_command_entitlement
from models import CommandCreateRequest, P2PSignalRequest
from services import create_command, dispatch_command, ensure_controllable, user_has_device_access


router = APIRouter()


@router.websocket("/ws/control/{device_id}")
async def control_websocket(websocket: WebSocket, device_id: str) -> None:
    await websocket.accept()
    try:
        auth_message = await asyncio.wait_for(websocket.receive_json(), timeout=10)
        token = auth_message.get("token", "") if isinstance(auth_message, dict) else ""
        if not isinstance(auth_message, dict) or auth_message.get("type") != "auth":
            await websocket.close(code=4401, reason="authentication required")
            return
        try:
            claims = decode_access_token(websocket.app.state.settings, str(token))
        except jwt.PyJWTError:
            await websocket.close(code=4401, reason="invalid or expired token")
            return
        user = websocket.app.state.db.fetch_one(
            "SELECT * FROM users WHERE id = ?", (int(claims["sub"]),)
        )
        if user is None or not user["active"]:
            await websocket.close(code=4401, reason="user is disabled")
            return
        device = None
        if device_id == "*":
            if user["role"] != "admin":
                await websocket.close(code=4403, reason="administrator access required")
                return
        else:
            device = websocket.app.state.db.fetch_one(
                "SELECT * FROM devices WHERE device_id = ?", (device_id,)
            )
            if device is None:
                await websocket.close(code=4404, reason="device not found")
                return
            if not user_has_device_access(websocket.app.state.db, device, user):
                await websocket.close(code=4403, reason="device access denied")
                return
        await websocket.app.state.hub.add_control(device_id, websocket)
        await websocket.send_json(
            {"type": "authenticated", "scope": device_id, "server_time": int(time.time())}
        )
        if device is not None:
            latest_snapshot = await websocket.app.state.hub.get_latest_snapshot(device_id)
            if latest_snapshot is not None:
                await websocket.send_json(latest_snapshot)
        while True:
            message = await websocket.receive_json()
            message_type = message.get("type") if isinstance(message, dict) else None
            if message_type == "ping":
                await websocket.send_json({"type": "pong", "ts": int(time.time())})
            elif message_type == "snapshot_request" and device is not None:
                current = websocket.app.state.db.fetch_one(
                    "SELECT * FROM devices WHERE device_id = ?", (device_id,)
                )
                if current is None or not user_has_device_access(
                    websocket.app.state.db, current, user
                ):
                    await websocket.close(code=4403, reason="device access changed")
                    return
                try:
                    ensure_controllable(current)
                    require_entitlement(
                        user, "screen_view",
                        source_ip=websocket.client.host if websocket.client else "",
                        device_lan_ip=str(current.get("lan_ip") or ""),
                        local_allowed=False,
                    )
                    require_entitlement(user, "relay_access", local_allowed=False)
                except HTTPException as error:
                    await websocket.send_json(
                        {"type": "error", "code": "DEVICE_UNAVAILABLE", "detail": error.detail}
                    )
                    continue
                delivered, throttled = await websocket.app.state.hub.request_snapshot(device_id)
                await websocket.send_json(
                    {
                        "type": "snapshot_ack",
                        "delivered": delivered,
                        "throttled": throttled,
                        "ts": int(time.time()),
                    }
                )
            elif message_type in {"preview_start", "preview_stop"} and device is not None:
                current = websocket.app.state.db.fetch_one(
                    "SELECT * FROM devices WHERE device_id = ?", (device_id,)
                )
                if current is None or not user_has_device_access(
                    websocket.app.state.db, current, user
                ):
                    await websocket.close(code=4403, reason="device access changed")
                    return
                try:
                    ensure_controllable(current)
                    require_entitlement(
                        user, "screen_view",
                        source_ip=websocket.client.host if websocket.client else "",
                        device_lan_ip=str(current.get("lan_ip") or ""),
                        local_allowed=False,
                    )
                    require_entitlement(user, "relay_access", local_allowed=False)
                except HTTPException as error:
                    await websocket.send_json(
                        {"type": "error", "code": "DEVICE_UNAVAILABLE", "detail": error.detail}
                    )
                    continue
                if message_type == "preview_start":
                    try:
                        fps = min(15, max(1, int(message.get("fps", 10))))
                    except (TypeError, ValueError):
                        fps = 10
                    delivered = await websocket.app.state.hub.start_preview(device_id, websocket, fps)
                else:
                    delivered = await websocket.app.state.hub.stop_preview(device_id, websocket)
                await websocket.send_json(
                    {"type": "preview_ack", "active": message_type == "preview_start",
                     "delivered": delivered}
                )
            elif message_type == "command" and device is not None:
                try:
                    payload = CommandCreateRequest.model_validate(message.get("command", {}))
                except ValidationError:
                    await websocket.send_json({"type": "error", "code": "INVALID_COMMAND"})
                    continue
                current = websocket.app.state.db.fetch_one(
                    "SELECT * FROM devices WHERE device_id = ?", (device_id,)
                )
                if current is None or not user_has_device_access(
                    websocket.app.state.db, current, user
                ):
                    await websocket.close(code=4403, reason="device access changed")
                    return
                try:
                    ensure_controllable(current)
                    ensure_command_entitlement(
                        payload, websocket.client.host if websocket.client else "", user, current
                    )
                except HTTPException as error:
                    await websocket.send_json(
                        {"type": "error", "code": "DEVICE_UNAVAILABLE", "detail": error.detail}
                    )
                    continue
                command = create_command(
                    websocket.app.state.db, current, payload.type, payload.payload, user["id"]
                )
                await dispatch_command(websocket.app, command)
            elif message_type == "p2p_signal" and device is not None:
                try:
                    payload = P2PSignalRequest.model_validate(message)
                except ValidationError:
                    await websocket.send_json({"type": "error", "code": "INVALID_SIGNAL"})
                    continue
                current = websocket.app.state.db.fetch_one(
                    "SELECT * FROM devices WHERE device_id = ?", (device_id,)
                )
                if current is None or not user_has_device_access(
                    websocket.app.state.db, current, user
                ):
                    await websocket.close(code=4403, reason="device access changed")
                    return
                try:
                    ensure_controllable(current)
                except HTTPException as error:
                    await websocket.send_json(
                        {"type": "error", "code": "DEVICE_UNAVAILABLE", "detail": error.detail}
                    )
                    continue
                try:
                    session = websocket.app.state.p2p_sessions.lookup(payload.session_id)
                    require_entitlement(
                        user,
                        "p2p_access" if session is not None else "relay_access",
                        source_ip=websocket.client.host if websocket.client else "",
                        device_lan_ip=str(current.get("lan_ip") or ""),
                        local_allowed=False,
                    )
                except HTTPException as error:
                    await websocket.send_json(
                        {"type": "error", "code": "ENTITLEMENT_REQUIRED", "detail": error.detail}
                    )
                    continue
                delivered = await websocket.app.state.hub.send_device(
                    device_id,
                    {
                        "type": "p2p_signal",
                        "session_id": payload.session_id,
                        "kind": payload.kind,
                        "payload": payload.payload,
                    },
                )
                await websocket.send_json({"type": "signal_ack", "delivered": delivered})
            else:
                await websocket.send_json({"type": "error", "code": "UNSUPPORTED_MESSAGE"})
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    finally:
        if device_id != "*":
            await websocket.app.state.hub.stop_preview(device_id, websocket)
        await websocket.app.state.hub.remove_control(device_id, websocket)
