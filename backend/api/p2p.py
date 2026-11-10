from __future__ import annotations

import json
import time

from fastapi import APIRouter, Depends, Request

from access import require_entitlement
from dependencies import get_current_user
from models import P2PSessionRequest, P2PSignalRequest
from p2p_rendezvous import SESSION_TTL_SECONDS, public_rendezvous
from serializers import parse_json
from services import ensure_controllable, get_device_for_user


router = APIRouter(prefix="/api/device/{device_id}/p2p", tags=["p2p"])


@router.post("/session")
async def create_session(
    device_id: str,
    payload: P2PSessionRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    ensure_controllable(device)
    source_ip = request.client.host if request.client else ""
    require_entitlement(
        user, "screen_view", source_ip=source_ip,
        device_lan_ip=str(device.get("lan_ip") or ""),
        local_allowed=False,
    )
    require_entitlement(
        user, "remote_control", source_ip=source_ip,
        device_lan_ip=str(device.get("lan_ip") or ""),
        local_allowed=False,
    )
    settings_rows = request.app.state.db.fetch_all("SELECT key, value_json FROM settings")
    settings = {row["key"]: json.loads(row["value_json"]) for row in settings_rows}
    requested = payload.mode
    metadata = parse_json(device.get("metadata_json"), {}) or {}
    direct_capable = metadata.get("udp_p2p_transport") == "ready"
    p2p_enabled = bool(settings.get("p2p_enabled"))
    direct_only = requested in {"p2p", "webrtc"}
    if requested == "relay":
        selected = "relay"
    elif p2p_enabled and direct_capable:
        selected = "udp_jpeg"
    else:
        selected = "unavailable" if direct_only else "relay"
    if selected == "relay" and not settings.get("relay_enabled", True):
        selected = "unavailable"
    if selected == "udp_jpeg" or direct_only:
        require_entitlement(user, "p2p_access", local_allowed=False)
    elif selected == "relay":
        require_entitlement(user, "relay_access", local_allowed=False)
    if requested == "relay":
        reason = "relay_requested"
    elif not p2p_enabled:
        reason = "p2p_disabled"
    elif not direct_capable:
        reason = "device_udp_transport_unavailable"
    else:
        reason = "direct_transport_ready"
    session, control_token, device_token = request.app.state.p2p_sessions.create(device_id)
    host, port = public_rendezvous(request.app.state.settings)
    if selected == "udp_jpeg" and (not host or port <= 0):
        selected = "unavailable" if direct_only else "relay"
        reason = "rendezvous_public_endpoint_missing"
        if selected == "relay":
            require_entitlement(user, "relay_access", local_allowed=False)
    delivered = False
    if selected == "udp_jpeg":
        delivered = await request.app.state.hub.send_device(device_id, {
            "type": "p2p_start", "session_id": session.session_id,
            "role": "device", "token": device_token, "channel_key": session.channel_key,
            "rendezvous_host": host, "rendezvous_port": port,
            "lan_stream_token": session.channel_key, "lan_stream_port": 8080,
            "expires_at": session.expires_at,
        })
        if not delivered:
            selected = "unavailable" if direct_only else "relay"
            reason = "device_signaling_unavailable"
            if selected == "relay":
                require_entitlement(user, "relay_access", local_allowed=False)
    return {
        "session_id": session.session_id,
        "requested_mode": requested,
        "selected_mode": selected,
        "direct_capable": direct_capable,
        "selection_reason": reason,
        "relay_fallback": bool(settings.get("relay_enabled", True)) and not direct_only,
        "ttl": SESSION_TTL_SECONDS,
        "transport": {
            "rendezvous_host": host, "rendezvous_port": port,
            "role": "control", "token": control_token,
            "channel_key": session.channel_key, "expires_at": session.expires_at,
            "ttl": SESSION_TTL_SECONDS,
            "lan_stream_host": device.get("lan_ip") or "", "lan_stream_port": 8080,
            "lan_stream_token": session.channel_key,
        } if selected == "udp_jpeg" else None,
        "device_notified": delivered,
    }


@router.get("/session/{session_id}")
async def session_status(
    device_id: str,
    session_id: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    """Diagnostics for one rendezvous session: who registered, and when it dies.

    Both sides refresh it with a UDP keepalive, so "expires_at" moving forward while a control
    page is open is the signal that hole punching is actually alive.
    """
    device = get_device_for_user(request.app.state.db, device_id, user)
    ensure_controllable(device)
    session = request.app.state.p2p_sessions.lookup(session_id)
    if session is None or session.device_id != device_id:
        return {"session_id": session_id, "found": False}
    return {
        "session_id": session.session_id,
        "found": True,
        "device_registered": "device" in session.endpoints,
        "control_registered": "control" in session.endpoints,
        "device_endpoint": list(session.endpoints["device"]) if "device" in session.endpoints else None,
        "control_endpoint": list(session.endpoints["control"]) if "control" in session.endpoints else None,
        "expires_at": session.expires_at,
        "last_seen_at": session.last_seen_at,
        "ttl": SESSION_TTL_SECONDS,
    }


@router.post("/signal")
async def send_signal(
    device_id: str,
    payload: P2PSignalRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    ensure_controllable(device)
    source_ip = request.client.host if request.client else ""
    # The signal channel moves keys and snapshots for a session that /p2p/session gated behind
    # p2p_access/relay_access. Without the same gate here the paid transport check was bypassable.
    session = request.app.state.p2p_sessions.lookup(payload.session_id)
    require_entitlement(
        user,
        "p2p_access" if session is not None else "relay_access",
        source_ip=source_ip,
        device_lan_ip=str(device.get("lan_ip") or ""),
        local_allowed=False,
    )
    now = int(time.time())
    request.app.state.db.execute(
        """
        INSERT INTO p2p_signals(session_id, device_pk, direction, kind, payload_json, created_at)
        VALUES (?, ?, 'control_to_device', ?, ?, ?)
        """,
        (
            payload.session_id,
            device["id"],
            payload.kind,
            json.dumps(payload.payload, ensure_ascii=False, separators=(",", ":")),
            now,
        ),
    )
    delivered = await request.app.state.hub.send_device(
        device_id,
        {
            "type": "p2p_signal",
            "session_id": payload.session_id,
            "kind": payload.kind,
            "payload": payload.payload,
        },
    )
    return {"accepted": True, "delivered": delivered, "relay_fallback": True}
