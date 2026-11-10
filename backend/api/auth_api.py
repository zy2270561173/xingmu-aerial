from __future__ import annotations

import sqlite3
import time

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status

from auth import create_access_token, hash_password, verify_password
from dependencies import get_current_user
from models import LoginRequest, PasswordChangeRequest, ProfileUpdateRequest, RegisterRequest
from profile_media import read_avatar
from serializers import user_public


router = APIRouter(prefix="/api/auth", tags=["auth"])


def _session_response(request: Request, user: dict) -> dict:
    token = create_access_token(request.app.state.settings, user)
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return {
        "access_token": token,
        "token_type": "bearer",
        "expires_in": request.app.state.settings.access_token_minutes * 60,
        "user": user_public(user, base),
    }


@router.post("/login")
def login(payload: LoginRequest, request: Request) -> dict:
    user = request.app.state.db.fetch_one(
        "SELECT * FROM users WHERE username = ? COLLATE NOCASE",
        (payload.username.strip(),),
    )
    if user is None:
        user = request.app.state.db.fetch_one(
            "SELECT * FROM users WHERE phone <> '' AND phone = ?", (payload.username.strip(),)
        )
    if user is None or not user["active"] or not verify_password(payload.password, user["password_hash"]):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid username or password")
    return _session_response(request, user)


@router.post("/register", status_code=status.HTTP_201_CREATED)
def register(payload: RegisterRequest, request: Request) -> dict:
    username = payload.username.strip()
    conflict = request.app.state.db.fetch_one(
        "SELECT id FROM users WHERE username = ? COLLATE NOCASE OR phone = ? "
        "OR (? <> '' AND (username = ? COLLATE NOCASE OR phone = ?))",
        (username, username, payload.phone, payload.phone, payload.phone),
    )
    if conflict is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "username or phone already exists")
    try:
        user_id = request.app.state.db.execute(
            "INSERT INTO users(username, password_hash, nickname, phone, role, active, created_at) "
            "VALUES (?, ?, ?, ?, 'user', 1, ?)",
            (
                username,
                hash_password(payload.password),
                payload.nickname,
                payload.phone,
                int(time.time()),
            ),
        )
    except sqlite3.IntegrityError as error:
        raise HTTPException(status.HTTP_409_CONFLICT, "username or phone already exists") from error
    user = request.app.state.db.fetch_one(
        "SELECT * FROM users WHERE id = ?", (user_id,)
    )
    assert user is not None
    return _session_response(request, user)


@router.get("/me")
def me(request: Request, user: dict = Depends(get_current_user)) -> dict:
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return user_public(user, base)


@router.patch("/profile")
async def update_profile(
    payload: ProfileUpdateRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    conflict = request.app.state.db.fetch_one(
        "SELECT id FROM users WHERE id <> ? AND ? <> '' "
        "AND (phone = ? OR username = ? COLLATE NOCASE)",
        (user["id"], payload.phone, payload.phone, payload.phone),
    )
    if conflict is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "phone number is already in use")
    try:
        request.app.state.db.execute(
            "UPDATE users SET nickname = ?, phone = ? WHERE id = ?",
            (payload.nickname, payload.phone, user["id"]),
        )
    except sqlite3.IntegrityError as error:
        raise HTTPException(status.HTTP_409_CONFLICT, "phone number is already in use") from error
    updated = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user["id"],))
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
        "SELECT device_id FROM devices WHERE owner_user_id = ?", (user["id"],)
    )
    for device in devices:
        await request.app.state.hub.send_device(
            device["device_id"], {"type": "binding_updated", "owner": owner}
        )
    await request.app.state.hub.broadcast_control(
        "*", {"type": "owner_profile_updated", "owner_user_id": user["id"]}
    )
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return user_public(updated, base)


@router.post("/password")
def change_password(
    payload: PasswordChangeRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    if not verify_password(payload.old_password, user["password_hash"]):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "old password is incorrect")
    request.app.state.db.execute(
        "UPDATE users SET password_hash = ? WHERE id = ?",
        (hash_password(payload.new_password), user["id"]),
    )
    return {"changed": True}


@router.post("/avatar")
async def update_avatar(
    request: Request,
    file: UploadFile = File(...),
    user: dict = Depends(get_current_user),
) -> dict:
    mime, data = await read_avatar(file)
    now = int(time.time())
    request.app.state.db.execute(
        "UPDATE users SET avatar_mime = ?, avatar_blob = ?, avatar_updated_at = ? WHERE id = ?",
        (mime, data, now, user["id"]),
    )
    updated = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user["id"],))
    assert updated is not None
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return user_public(updated, base)


@router.delete("/avatar")
def delete_avatar(request: Request, user: dict = Depends(get_current_user)) -> dict:
    request.app.state.db.execute(
        "UPDATE users SET avatar_mime = '', avatar_blob = NULL, avatar_updated_at = 0 WHERE id = ?",
        (user["id"],),
    )
    return {"deleted": True}
