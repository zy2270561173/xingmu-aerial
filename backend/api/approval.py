from __future__ import annotations

import time

from fastapi import APIRouter, Depends, HTTPException, Request, status

from dependencies import get_current_user
from models import ApprovalRequest, DeviceApprovalDecisionRequest
from serializers import device_public, user_public


router = APIRouter(prefix="/api/device-approvals", tags=["device approvals"])
compat_router = APIRouter(tags=["approvals"])


@router.get("")
@compat_router.get("/api/approvals", include_in_schema=False)
async def list_pending(request: Request, user: dict = Depends(get_current_user)) -> list[dict]:
    query = """
        SELECT devices.*, pending_agents.requested_at
        FROM pending_agents JOIN devices ON devices.device_id = pending_agents.device_id
        WHERE pending_agents.state = 'pending'
    """
    parameters: tuple = ()
    if user["role"] != "admin":
        query += " AND devices.owner_user_id = ?"
        parameters = (user["id"],)
    rows = request.app.state.db.fetch_all(query + " ORDER BY pending_agents.requested_at ASC", parameters)
    result = []
    for row in rows:
        item = device_public(row, await request.app.state.hub.is_device_online(row["device_id"]))
        item["requested_at"] = row["requested_at"]
        result.append(item)
    return result


async def _review(
    payload: ApprovalRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    db = request.app.state.db
    device = db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (payload.device_id,))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "device not found")
    if user["role"] != "admin" and device["owner_user_id"] != user["id"]:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "device approval access denied")
    if payload.owner_user_id is not None and user["role"] != "admin":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "only an administrator can assign another owner")
    now = int(time.time())
    new_status = "approved" if payload.approve else "rejected"
    owner_id = device["owner_user_id"]
    if payload.approve:
        owner_id = payload.owner_user_id if user["role"] == "admin" else user["id"]
        owner_id = owner_id or user["id"]
        owner = db.fetch_one("SELECT id FROM users WHERE id = ? AND active = 1", (owner_id,))
        if owner is None:
            raise HTTPException(status.HTTP_409_CONFLICT, "device owner account is unavailable")
    else:
        owner_id = None
    name = payload.name or device["name"]
    with db.transaction() as connection:
        if owner_id:
            connection.execute(
                "DELETE FROM device_user_bindings WHERE device_pk = ? AND user_id = ?",
                (device["id"], owner_id),
            )
        elif not payload.approve:
            connection.execute(
                "DELETE FROM device_user_bindings WHERE device_pk = ?", (device["id"],)
            )
        connection.execute(
            "UPDATE devices SET status = ?, owner_user_id = ?, name = ?, updated_at = ? WHERE id = ?",
            (new_status, owner_id, name, now, device["id"]),
        )
        connection.execute(
            """
            UPDATE pending_agents SET state = ?, reviewed_at = ?, reviewed_by = ?
            WHERE device_id = ?
            """,
            (new_status, now, user["id"], payload.device_id),
        )
    updated = db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert updated is not None
    public = device_public(updated, await request.app.state.hub.is_device_online(payload.device_id))
    event = {"type": "approval_result", "device": public}
    await request.app.state.hub.send_device(payload.device_id, event)
    owner_profile = None
    if payload.approve and owner_id:
        owner_row = db.fetch_one("SELECT * FROM users WHERE id = ?", (owner_id,))
        if owner_row is not None:
            base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
            owner_profile = user_public(owner_row, base)
    await request.app.state.hub.send_device(
        payload.device_id, {"type": "binding_updated", "owner": owner_profile}
    )
    await request.app.state.hub.broadcast_control("*", event)
    return public


@router.post("/{device_id}/approve")
async def approve_device(
    device_id: str,
    payload: DeviceApprovalDecisionRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    return await _review(
        ApprovalRequest(
            device_id=device_id,
            approve=True,
            name=payload.name,
            owner_user_id=payload.owner_user_id,
        ),
        request,
        user,
    )


@router.post("/{device_id}/reject")
async def reject_device(
    device_id: str,
    payload: DeviceApprovalDecisionRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    return await _review(
        ApprovalRequest(device_id=device_id, approve=False, name=payload.name),
        request,
        user,
    )


@compat_router.post("/api/approvals", include_in_schema=False)
async def review_compat(
    payload: ApprovalRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    return await _review(payload, request, user)


@compat_router.post("/api/auth/approve")
async def approve_compat(
    payload: ApprovalRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    return await _review(payload, request, user)
