from __future__ import annotations

import time
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status

from dependencies import require_admin
from models import ReleasePublishRequest


router = APIRouter(prefix="/api/releases", tags=["releases"])
Platform = Literal["tv", "mobile"]


def _release_public(request: Request, row: dict) -> dict:
    base = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return {
        "id": row["id"],
        "platform": row["platform"],
        "version_code": row["version_code"],
        "version_name": row["version_name"],
        "release_notes": row["release_notes"],
        "mandatory": bool(row["mandatory"]),
        "published_at": row["published_at"],
        "filename": row["filename"],
        "size": row["size"],
        "sha256": row["sha256"],
        "url": f"{base}/static/apk/{row['stored_name']}",
    }


def _release_query() -> str:
    return """
        SELECT releases.*, apks.filename, apks.stored_name, apks.sha256, apks.size
        FROM app_releases AS releases
        JOIN apks ON apks.id = releases.apk_id
    """


@router.get("/latest/{platform}")
def latest_release(
    platform: Platform,
    request: Request,
    current_version_code: int = Query(default=0, ge=0),
) -> dict:
    row = request.app.state.db.fetch_one(
        _release_query()
        + " WHERE releases.platform = ? AND releases.active = 1"
        + " ORDER BY releases.version_code DESC LIMIT 1",
        (platform,),
    )
    release = _release_public(request, row) if row is not None else None
    return {
        "update_available": bool(row and row["version_code"] > current_version_code),
        "release": release,
    }


@router.get("")
def list_releases(
    request: Request, _user: dict = Depends(require_admin)
) -> list[dict]:
    rows = request.app.state.db.fetch_all(
        _release_query()
        + " WHERE releases.active = 1"
        + " ORDER BY releases.platform, releases.version_code DESC"
    )
    return [_release_public(request, row) for row in rows]


@router.post("")
def publish_release(
    payload: ReleasePublishRequest,
    request: Request,
    user: dict = Depends(require_admin),
) -> dict:
    apk = request.app.state.db.fetch_one("SELECT * FROM apks WHERE id = ?", (payload.apk_id,))
    if apk is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "APK not found")
    if not (request.app.state.settings.apk_dir / apk["stored_name"]).is_file():
        raise HTTPException(status.HTTP_409_CONFLICT, "APK file is missing")
    now = int(time.time())
    with request.app.state.db.transaction() as connection:
        connection.execute(
            """
            INSERT INTO app_releases(
                platform, version_code, version_name, apk_id, release_notes,
                mandatory, active, published_at, published_by
            ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT(platform, version_code) DO UPDATE SET
                version_name = excluded.version_name,
                apk_id = excluded.apk_id,
                release_notes = excluded.release_notes,
                mandatory = excluded.mandatory,
                active = 1,
                published_at = excluded.published_at,
                published_by = excluded.published_by
            """,
            (
                payload.platform,
                payload.version_code,
                payload.version_name,
                payload.apk_id,
                payload.release_notes,
                int(payload.mandatory),
                now,
                user["id"],
            ),
        )
    row = request.app.state.db.fetch_one(
        _release_query()
        + " WHERE releases.platform = ? AND releases.version_code = ?",
        (payload.platform, payload.version_code),
    )
    assert row is not None
    return _release_public(request, row)
