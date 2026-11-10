from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status

from access import require_entitlement, same_lan
from dependencies import get_current_user
from models import CommandCreateRequest
from serializers import command_public
from services import create_command, dispatch_command, ensure_controllable, get_device_for_user


router = APIRouter(prefix="/api/device/{device_id}/commands", tags=["commands"])
compat_router = APIRouter(tags=["commands"])


def ensure_command_entitlement(
    payload: CommandCreateRequest, source_ip: str, user: dict, device: dict
) -> None:
    entitlement = ""
    action = str(payload.payload.get("action") or "")
    if payload.type == "media" and action in {"play_url", "play_hosted", "show_image", "start_slideshow"}:
        entitlement = "remote_cast"
    elif payload.type == "install":
        entitlement = "app_install"
    elif payload.type in {"key", "switch", "settings", "app", "power"} or payload.type == "media":
        entitlement = "remote_control"
    elif payload.type in {"snapshot", "p2p"}:
        entitlement = "screen_view"
    if entitlement:
        local_allowed = entitlement in {"remote_cast", "app_install", "remote_control"}
        require_entitlement(
            user, entitlement, source_ip=source_ip,
            device_lan_ip=str(device.get("lan_ip") or ""),
            local_allowed=local_allowed,
        )
        if not local_allowed or not same_lan(source_ip, str(device.get("lan_ip") or "")):
            require_entitlement(user, "relay_access", local_allowed=False)


@router.get("")
def list_commands(
    device_id: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> list[dict]:
    device = get_device_for_user(request.app.state.db, device_id, user)
    rows = request.app.state.db.fetch_all(
        """
        SELECT commands.*, devices.device_id
        FROM commands JOIN devices ON devices.id = commands.device_pk
        WHERE commands.device_pk = ? ORDER BY commands.created_at DESC, commands.id DESC LIMIT 100
        """,
        (device["id"],),
    )
    return [command_public(row) for row in rows]


@router.post("")
async def send_command(
    device_id: str,
    payload: CommandCreateRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    device = get_device_for_user(request.app.state.db, device_id, user)
    ensure_controllable(device)
    ensure_command_entitlement(
        payload, request.client.host if request.client else "", user, device
    )
    command = create_command(
        request.app.state.db, device, payload.type, payload.payload, user["id"]
    )
    await dispatch_command(request.app, command)
    # D-pad keys cannot be injected by the TV app on 4.x (INJECT_EVENTS is system-only and adbd
    # refuses app connections). The server already has an authorised adb link, so press it here.
    if payload.type == "key":
        try:
            from adb_keys import inject_key

            key_name = str(payload.payload.get("key") or "")
            serial = f"{device.get('lan_ip') or ''}:{device.get('adb_port') or 5555}"
            ok, detail = inject_key(serial, key_name)
            if ok:
                # The key is already pressed, so the command is done: report it before the device
                # answers (its own injection attempt fails on 4.x and would show up as an error).
                import time as _time

                request.app.state.db.execute(
                    "UPDATE commands SET status = 'completed', result_message = ?, completed_at = ?"
                    " WHERE id = ? AND status IN ('pending', 'delivered')",
                    (detail, int(_time.time()), command["id"]),
                )
        except Exception:  # never break command delivery because the shortcut failed
            pass
    return command


@compat_router.post("/api/device/{device_id}/command")
async def send_command_compat(
    device_id: str,
    payload: CommandCreateRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    return await send_command(device_id, payload, request, user)
