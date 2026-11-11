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
                    "UPDATE commands SET status = 'completed', result_json = ?, completed_at = ?"
                    " WHERE id = ? AND status IN ('pending', 'delivered')",
                    (__import__("json").dumps({"success": True, "message": detail}), int(_time.time()), command["id"]),
                )
        except Exception:  # never break command delivery because the shortcut failed
            pass
    # Same reasoning for installs: the TV cannot read the APK through its own adbd and a system
    # installer would need a remote tap, while the server already holds the file and the adb link.
    if payload.type == "install":
        try:
            import time as _time
            from pathlib import Path as _Path

            from adb_keys import install_apk

            url = str(payload.payload.get("url") or "")
            stored = url.rsplit("/", 1)[-1]
            directory = _Path(request.app.state.settings.apk_dir)
            local = directory / stored
            if stored and not local.is_file():
                candidates = sorted(directory.glob("*" + stored))
                local = candidates[0] if candidates else local
            if stored and local.is_file():
                serial = f"{device.get('lan_ip') or ''}:{device.get('adb_port') or 5555}"
                ok, detail = install_apk(serial, str(local))
                status = "completed" if ok else "failed"
                request.app.state.db.execute(
                    "UPDATE commands SET status = ?, result_json = ?, completed_at = ?"
                    " WHERE id = ? AND status IN ('pending', 'delivered')",
                    (status, __import__("json").dumps({"success": ok, "message": detail}), int(_time.time()), command["id"]),
                )
        except Exception as error:  # a failed shortcut must not break command delivery
            try:
                request.app.state.db.execute(
                    "UPDATE commands SET result_json = ? WHERE id = ?",
                    (__import__("json").dumps({"success": False, "message": f"server install error: {type(error).__name__}: {error}"[:200]}), command["id"]),
                )
            except Exception:
                pass
    # Snapshots: the TV app cannot screencap itself (shell-only permission), so grab it here and
    # hand back a URL the phone can render directly.
    if payload.type == "snapshot":
        try:
            import json as _json
            import time as _time
            from pathlib import Path as _Path

            from adb_keys import capture_png

            serial = f"{device.get('lan_ip') or ''}:{device.get('adb_port') or 5555}"
            ok, data = capture_png(serial)
            if ok:
                directory = _Path(request.app.state.settings.apk_dir)
                name = f"snap-{device_id.replace(':', '_')}-{int(_time.time())}.png"
                (directory / name).write_bytes(data)  # type: ignore[arg-type]
                base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
                detail = {"success": True, "message": f"{len(data)} bytes", "url": f"{base}/static/apk/{name}"}
                status_value = "completed"
            else:
                detail = {"success": False, "message": str(data)[:200]}
                status_value = "failed"
            request.app.state.db.execute(
                "UPDATE commands SET status = ?, result_json = ?, completed_at = ?"
                " WHERE id = ? AND status IN ('pending', 'delivered')",
                (status_value, _json.dumps(detail, ensure_ascii=False), int(_time.time()), command["id"]),
            )
        except Exception as error:  # a failed shortcut must not break command delivery
            try:
                request.app.state.db.execute(
                    "UPDATE commands SET result_json = ? WHERE id = ?",
                    (f'{{"success": false, "message": "snapshot error: {type(error).__name__}"}}', command["id"]),
                )
            except Exception:
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
