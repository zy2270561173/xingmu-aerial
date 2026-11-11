from __future__ import annotations

import glob
import hashlib
import os
import re
import shutil
import subprocess
import time
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status

from dependencies import get_current_user


router = APIRouter(prefix="/api/upload", tags=["uploads"])
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


def _aapt_path() -> str:
    """Locate aapt: PATH first, then ANDROID_HOME/build-tools (highest version wins)."""
    found = shutil.which("aapt") or shutil.which("aapt.exe")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    if not sdk:
        return ""
    candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt.exe")))
    if not candidates:
        candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt")))
    return candidates[-1] if candidates else ""


def apk_metadata(path: Path) -> dict:
    """Package name and minSdk from `aapt dump badging`; empty when the SDK tools are absent."""
    aapt = _aapt_path()
    if not aapt:
        return {"package_name": "", "min_sdk": 0}
    try:
        completed = subprocess.run(
            [aapt, "dump", "badging", str(path)],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return {"package_name": "", "min_sdk": 0}
    output = completed.stdout or ""
    package = re.search(r"package: name='([^']+)'", output)
    sdk_version = re.search(r"sdkVersion:'(\d+)'", output)
    return {
        "package_name": package.group(1) if package else "",
        "min_sdk": int(sdk_version.group(1)) if sdk_version else 0,
    }


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
    metadata = apk_metadata(destination)
    apk_id = request.app.state.db.execute(
        "INSERT INTO apks(filename, stored_name, sha256, size, package_name, min_sdk, uploaded_by, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            original,
            stored_name,
            digest.hexdigest(),
            size,
            metadata["package_name"],
            metadata["min_sdk"],
            user["id"],
            now,
        ),
    )
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return {
        "id": apk_id,
        "filename": original,
        "size": size,
        "sha256": digest.hexdigest(),
        "package_name": metadata["package_name"],
        "min_sdk": metadata["min_sdk"],
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
            "package_name": row.get("package_name") or "",
            "min_sdk": int(row.get("min_sdk") or 0),
            "url": f"{base}/static/apk/{row['stored_name']}",
            "created_at": row["created_at"],
        }
        for row in rows
    ]

