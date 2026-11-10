from __future__ import annotations

from fastapi import HTTPException, UploadFile, status


MAX_AVATAR_BYTES = 2 * 1024 * 1024


async def read_avatar(file: UploadFile) -> tuple[str, bytes]:
    data = await file.read(MAX_AVATAR_BYTES + 1)
    await file.close()
    if not data:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "avatar image is empty")
    if len(data) > MAX_AVATAR_BYTES:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "avatar image is too large")
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        mime = "image/png"
    elif data.startswith(b"\xff\xd8\xff"):
        mime = "image/jpeg"
    elif len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        mime = "image/webp"
    else:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "avatar must be PNG, JPEG, or WebP")
    return mime, data
