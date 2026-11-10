from __future__ import annotations

import hashlib
import re
import time
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status

from dependencies import get_current_user


router = APIRouter(prefix="/api/upload", tags=["uploads"])
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


@router.post("/apk")
async def upload_apk(
    request: Request,
    file: UploadFile = File(...),
    user: dict = Depends(get_current_user),
) -> dict:
    original = Path(file.filename or "upload.apk").name
    if Path(original).suffix.lower() != ".apk":
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "only APK files are accepted")
    safe = SAFE_NAME.sub("_", original).strip("._") or "upload.apk"
    stored_name = f"{int(time.time())}-{uuid.uuid4().hex[:12]}-{safe}"
    destination = request.app.state.settings.apk_dir / stored_name
    digest = hashlib.sha256()
    size = 0
    try:
        with destination.open("xb") as output:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > request.app.state.settings.max_upload_bytes:
                    raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "APK is too large")
                digest.update(chunk)
                output.write(chunk)
    except Exception:
        destination.unlink(missing_ok=True)
        raise
    finally:
        await file.close()
    now = int(time.time())
    apk_id = request.app.state.db.execute(
        "INSERT INTO apks(filename, stored_name, sha256, size, uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        (original, stored_name, digest.hexdigest(), size, user["id"], now),
    )
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return {
        "id": apk_id,
        "filename": original,
        "size": size,
        "sha256": digest.hexdigest(),
        "url": f"{base}/static/apk/{stored_name}",
        "created_at": now,
    }


@router.get("/apk")
def list_apks(request: Request, _user: dict = Depends(get_current_user)) -> list[dict]:
    rows = request.app.state.db.fetch_all("SELECT * FROM apks ORDER BY created_at DESC LIMIT 100")
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return [
        {
            "id": row["id"],
            "filename": row["filename"],
            "size": row["size"],
            "sha256": row["sha256"],
            "url": f"{base}/static/apk/{row['stored_name']}",
            "created_at": row["created_at"],
        }
        for row in rows
    ]

