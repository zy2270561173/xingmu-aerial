from __future__ import annotations

from typing import Any

import jwt
from fastapi import Depends, Header, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from auth import decode_access_token, verify_device_token


bearer = HTTPBearer(auto_error=False)


def get_current_user(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> dict[str, Any]:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing bearer token")
    try:
        claims = decode_access_token(request.app.state.settings, credentials.credentials)
    except jwt.PyJWTError as error:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or expired token") from error
    user = request.app.state.db.fetch_one(
        "SELECT * FROM users WHERE id = ?",
        (int(claims["sub"]),),
    )
    if user is None or not user["active"]:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "user is disabled")
    return user


def require_admin(user: dict[str, Any] = Depends(get_current_user)) -> dict[str, Any]:
    if user["role"] != "admin":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "administrator access required")
    return user


def get_authenticated_device(
    request: Request,
    device_id: str = Header(alias="X-Device-ID"),
    device_token: str = Header(alias="X-Device-Token"),
) -> dict[str, Any]:
    device = request.app.state.db.fetch_one(
        "SELECT * FROM devices WHERE device_id = ?", (device_id,)
    )
    if device is None or not verify_device_token(device_token, device["token_hash"]):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid device credentials")
    if not device["enabled"]:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "device is disabled")
    return device
