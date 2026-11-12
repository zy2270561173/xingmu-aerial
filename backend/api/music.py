"""内置点歌台：第三方曲库代理、按设备的点歌队列和手机点歌页。

The TV and the phone never talk to the music CDN directly: every search, quality list, lyric and
playback URL comes through here, and the TV plays the ``/stream`` URL so a short-lived CDN token
never has to survive on the TV.  The upstream service needs no key, so there is no secret to keep;
what this module adds is a stable, same-origin API, a queue per device and the page a phone scans.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from functools import lru_cache
from pathlib import Path
from typing import Any, AsyncIterator
from urllib.parse import quote, urlparse

import httpx
import jwt
from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, Response, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, Field

from auth import decode_access_token
from config import Settings
from dependencies import bearer, require_admin

# Overridable for a local mirror; the public service is what the phone page was checked against.
UPSTREAM = os.getenv("TVCAST_MUSIC_BASE", "https://music.muysky.cn").rstrip("/")
UPSTREAM_TIMEOUT = 10.0

# The qualities every song has, used when the upstream list marks nothing available (some songs
# only expose the fallback 320k stream).  Keys are the upstream's own.
FALLBACK_QUALITIES = (
    {"key": "MP3_320", "label": "HQ 高品质 MP3 320k", "available": True, "ext": "mp3"},
    {"key": "ACC_192", "label": "HQ 高品质 AAC 192k", "available": True, "ext": "m4a"},
    {"key": "MP3_128", "label": "标准音质 MP3 128k", "available": True, "ext": "mp3"},
)
DEFAULT_QUALITY = "MP3_320"
MAX_QUEUE = 60
# The phone page's link lives a shift, not forever: a screenshot of the QR stops working the next day.
PAGE_TOKEN_TTL_SECONDS = 12 * 60 * 60

router = APIRouter(prefix="/api/music", tags=["music"])
page_router = APIRouter(tags=["music"])


def _page_token_signature(settings: Settings, payload: str) -> str:
    """urlsafe base64 HMAC-SHA256 of ``device_id.exp``; the secret is the backend's JWT secret."""
    secret = str(settings.jwt_secret).encode("utf-8")
    digest = hmac.new(secret, payload.encode("utf-8"), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def music_sign(settings: Settings, device_id: str) -> str:
    """A short-lived phone-page token: ``{exp}.{sig}``, valid for [PAGE_TOKEN_TTL_SECONDS]."""
    expires = int(time.time()) + PAGE_TOKEN_TTL_SECONDS
    payload = f"{device_id}.{expires}"
    return f"{expires}.{_page_token_signature(settings, payload)}"


def music_verify(settings: Settings, device_id: str, token: str) -> bool:
    """True when [token] was signed by this backend for [device_id] and has not expired."""
    text = str(token or "").strip()
    if not text or "." not in text:
        return False
    expires_text, signature = text.split(".", 1)
    try:
        expires = int(expires_text)
    except ValueError:
        return False
    if expires < int(time.time()):
        return False
    expected = _page_token_signature(settings, f"{device_id}.{expires}")
    return hmac.compare_digest(expected, signature)


def _console_user(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> dict[str, Any] | None:
    """The console's admin JWT, when one was sent; an invalid token is a 401, not a silent fallback."""
    if credentials is None:
        return None
    try:
        claims = decode_access_token(request.app.state.settings, credentials.credentials)
    except jwt.PyJWTError as error:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid or expired token") from error
    user = request.app.state.db.fetch_one(
        "SELECT * FROM users WHERE id = ?", (int(claims["sub"]),)
    )
    if user is None or not user["active"]:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "user is disabled")
    # A regular account is not a console exemption; a valid phone-page token may still get in.
    return user if user["role"] == "admin" else None


def _require_device_access(
    request: Request, device_id: str, token: str, user: dict[str, Any] | None
) -> None:
    """Device control is either the console (JWT) or a valid phone-page token for that device."""
    if user is not None:
        return
    if music_verify(request.app.state.settings, device_id.strip(), token):
        return
    raise HTTPException(
        status.HTTP_403_FORBIDDEN, "点歌链接已失效，请在电视上重新扫码"
    )


class SongBody(BaseModel):
    """One song, as the phone page (and the TV's command payload) describes it."""
    device_id: str | None = None
    mid: str = Field(min_length=1, max_length=128)
    name: str = Field(default="", max_length=200)
    singer: str = Field(default="", max_length=200)
    album: str = Field(default="", max_length=200)
    cover: str = Field(default="", max_length=1000)
    interval: int = Field(default=0, ge=0, le=24 * 60 * 60)
    quality: str = Field(default=DEFAULT_QUALITY, max_length=32)
    url: str | None = Field(default=None, max_length=4000)
    # The phone may also carry its signed token in the body instead of ``?t=``.
    t: str | None = Field(default=None)


class QueueOrderBody(BaseModel):
    """The upcoming ids in the order the phone dragged them into."""

    order: list[str] = Field(default_factory=list)
    t: str | None = Field(default=None)


# In-memory by design: a queue is a living room's "已点" list, not history worth keeping.
_queues: dict[str, list[dict[str, Any]]] = {}
# The TV's latest music_state, so the phone mirrors the TV rather than guessing.
_state: dict[str, dict[str, Any]] = {}
_sequence = 0


async def _upstream(path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    """GET one upstream API method, turning every failure into a short 502 for the caller."""
    try:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT, follow_redirects=True) as client:
            response = await client.get(
                UPSTREAM + path,
                params=params,
                headers={"User-Agent": "Xingmu-Aerial-Music/1.0", "Accept": "application/json"},
            )
    except httpx.TimeoutException:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "音乐服务超时")
    except httpx.HTTPError as error:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"音乐服务不可达：{type(error).__name__}")
    if response.status_code != 200:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"音乐服务返回 HTTP {response.status_code}")
    try:
        payload = response.json()
    except ValueError:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "音乐服务返回了非 JSON 数据")
    if not isinstance(payload, dict):
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "音乐服务返回格式异常")
    if int(payload.get("code") or 0) != 0:
        reason = str(payload.get("message") or payload.get("msg") or "音乐服务返回错误")[:120]
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, reason)
    return payload


def _song(raw: dict[str, Any]) -> dict[str, Any] | None:
    """Normalises one upstream search result; both ``singer`` and ``singerText`` are kept."""
    mid = str(raw.get("mid") or "").strip()
    if not mid:
        return None
    name = str(raw.get("name") or raw.get("title") or "").strip()
    singer = str(raw.get("singerText") or "").strip()
    if not singer:
        singers = raw.get("singers")
        if isinstance(singers, list):
            singer = " / ".join(str(item) for item in singers if item)
    interval = raw.get("interval") or 0
    try:
        interval = int(interval)
    except (TypeError, ValueError):
        interval = 0
    return {
        "mid": mid,
        "name": name,
        "title": str(raw.get("title") or name),
        "singer": singer,
        "singerText": singer,
        "album": str(raw.get("album") or ""),
        "cover": str(raw.get("cover") or ""),
        "interval": max(0, interval),
    }


async def _resolve(mid: str, quality: str) -> dict[str, Any]:
    """The playable URL, plus the same stream through this backend (what the TV actually plays)."""
    payload = await _upstream(f"/api/songs/{quote(mid, safe='')}/url", {"quality": quality})
    data = payload.get("data") or {}
    direct = str(data.get("url") or "").strip()
    if not direct:
        purl = str(data.get("purl") or "").strip()
        if purl:
            direct = "https://isure.stream.qqmusic.qq.com/" + purl.lstrip("/")
    if not direct:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "音乐服务没有返回播放地址")
    return {
        "mid": mid,
        "quality": str(data.get("quality") or quality),
        "requested": str(data.get("requested") or quality),
        "downgraded": bool(data.get("downgraded")),
        "url": direct,
    }


def _base_url(request: Request) -> str:
    settings = getattr(request.app.state, "settings", None)
    configured = str(getattr(settings, "public_base_url", "") or "").rstrip("/")
    return configured or str(request.base_url).rstrip("/")


def _stream_url(request: Request, mid: str, quality: str) -> str:
    return (
        f"{_base_url(request)}/api/music/stream/{quote(mid, safe='')}"
        f"?quality={quote(quality, safe='')}"
    )


@router.get("/search")
async def search(q: str = "", limit: int = 20) -> dict[str, Any]:
    keyword = q.strip()
    limit = max(1, min(int(limit or 20), 30))
    if not keyword:
        return {"code": 0, "data": {"keyword": "", "list": [], "total": 0}}
    payload = await _upstream("/api/search", {"keyword": keyword, "limit": limit})
    raw_list = (payload.get("data") or {}).get("list") or []
    songs = [song for song in (_song(item) for item in raw_list if isinstance(item, dict)) if song]
    # The upstream ignores our limit (it always answers 20); the TV asks for a small page.
    songs = songs[:limit]
    return {"code": 0, "data": {"keyword": keyword, "list": songs, "total": len(songs)}}


@router.get("/qualities/{mid}")
async def qualities(mid: str) -> dict[str, Any]:
    payload = await _upstream(f"/api/songs/{quote(mid, safe='')}/qualities")
    data = payload.get("data") or {}
    entries: list[dict[str, Any]] = []
    for item in data.get("list") or []:
        if not isinstance(item, dict):
            continue
        key = str(item.get("key") or "").strip()
        if not key:
            continue
        entries.append(
            {
                "key": key,
                "label": str(item.get("label") or key),
                "available": bool(item.get("available")),
                "ext": str(item.get("ext") or ""),
                "sizeBytes": int(item.get("sizeBytes") or 0),
            }
        )
    if not any(entry["available"] for entry in entries):
        entries = [dict(entry) for entry in FALLBACK_QUALITIES]
    return {
        "code": 0,
        "data": {
            "mid": mid,
            "default": str(data.get("default") or DEFAULT_QUALITY),
            "list": entries,
            "availableCount": sum(1 for entry in entries if entry["available"]),
        },
    }


@router.get("/url/{mid}")
async def song_url(mid: str, request: Request, quality: str = DEFAULT_QUALITY) -> dict[str, Any]:
    resolved = await _resolve(mid, quality)
    resolved["stream_url"] = _stream_url(request, mid, resolved["quality"])
    return {"code": 0, "data": resolved}


@router.get("/lyric/{mid}")
async def lyric(mid: str) -> dict[str, Any]:
    payload = await _upstream(f"/api/songs/{quote(mid, safe='')}/lyric")
    data = payload.get("data") or {}
    lines: list[dict[str, Any]] = []
    for item in data.get("lines") or []:
        if not isinstance(item, dict):
            continue
        try:
            at = float(item.get("time") or 0)
        except (TypeError, ValueError):
            continue
        text = str(item.get("text") or "").strip()
        if not text:
            continue
        lines.append({"time": round(at, 2), "text": text})
    lines.sort(key=lambda entry: entry["time"])
    return {
        "code": 0,
        "data": {
            "mid": mid,
            "lines": lines,
            "lineCount": len(lines),
            "hasLyric": bool(lines),
        },
    }


@router.get("/cover")
async def cover(url: str) -> Response:
    """Relays the album art: Android 4.4's OkHttp cannot negotiate the CDN's TLS settings."""
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    allowed = host == "y.gtimg.cn" or host.endswith(".gtimg.cn") or host.endswith(".qq.com")
    if parsed.scheme not in {"http", "https"} or not allowed:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "不支持的封面地址")
    try:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT, follow_redirects=True) as client:
            response = await client.get(
                url, headers={"User-Agent": "Xingmu-Aerial-Music/1.0", "Accept": "image/*"}
            )
    except httpx.TimeoutException:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "封面服务超时")
    except httpx.HTTPError as error:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"封面不可达：{type(error).__name__}")
    if response.status_code != 200:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"封面返回 HTTP {response.status_code}")
    if not response.content or len(response.content) > 3 * 1024 * 1024:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "封面大小异常")
    return Response(
        response.content,
        media_type=response.headers.get("content-type") or "image/jpeg",
        headers={"Cache-Control": "public, max-age=86400"},
    )


@router.get("/stream/{mid}")
async def stream(mid: str, request: Request, quality: str = DEFAULT_QUALITY) -> StreamingResponse:
    """Relays the CDN bytes, Range included, so the TV never holds a URL that expires."""
    resolved = await _resolve(mid, quality)
    outgoing: dict[str, str] = {}
    incoming_range = request.headers.get("range")
    if incoming_range:
        outgoing["Range"] = incoming_range
    client = httpx.AsyncClient(
        timeout=httpx.Timeout(10.0, read=60.0),
        follow_redirects=True,
        headers={"User-Agent": "Xingmu-Aerial-Music/1.0"},
    )
    try:
        upstream_request = client.build_request("GET", resolved["url"], headers=outgoing)
        upstream = await client.send(upstream_request, stream=True)
    except httpx.HTTPError as error:
        await client.aclose()
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"音频源不可达：{type(error).__name__}")
    if upstream.status_code >= 400:
        await upstream.aclose()
        await client.aclose()
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"音频源返回 HTTP {upstream.status_code}")

    response_headers: dict[str, str] = {}
    for name in ("content-type", "content-length", "content-range", "accept-ranges"):
        value = upstream.headers.get(name)
        if value:
            response_headers[name] = value

    async def body() -> AsyncIterator[bytes]:
        try:
            async for chunk in upstream.aiter_raw():
                yield chunk
        finally:
            await upstream.aclose()
            await client.aclose()

    return StreamingResponse(
        body(),
        status_code=upstream.status_code,
        media_type=upstream.headers.get("content-type") or "audio/mpeg",
        headers=response_headers,
    )


def _queue_for(device_id: str) -> list[dict[str, Any]]:
    return _queues.setdefault(device_id, [])


def _queue_item(raw: Any) -> dict[str, Any] | None:
    """A queue row as the TV and the phone both understand it (no stream URL needed)."""
    if not isinstance(raw, dict):
        return None
    mid = str(raw.get("mid") or "").strip()
    if not mid:
        return None
    try:
        interval = int(raw.get("interval") or 0)
    except (TypeError, ValueError):
        interval = 0
    return {
        "id": str(raw.get("id") or ""),
        "mid": mid,
        "name": str(raw.get("name") or mid),
        "singer": str(raw.get("singer") or ""),
        "album": str(raw.get("album") or ""),
        "cover": str(raw.get("cover") or ""),
        "interval": max(0, interval),
        "quality": str(raw.get("quality") or DEFAULT_QUALITY),
    }


def _clean_queue(raw: Any) -> list[dict[str, Any]]:
    items = raw if isinstance(raw, list) else []
    return [item for item in (_queue_item(entry) for entry in items) if item]


def record_state(device_id: str, data: dict[str, Any]) -> None:
    """The TV's own view of the queue, so the phone sees exactly what is playing and next."""
    queue = _clean_queue(data.get("queue"))
    try:
        index = int(data.get("index", -1))
    except (TypeError, ValueError):
        index = -1
    if queue:
        index = max(-1, min(index, len(queue) - 1))
    else:
        index = -1
    _queues[device_id] = queue
    _state[device_id] = {
        "queue": queue,
        "index": index,
        "playing": bool(data.get("playing")),
        "prepared": bool(data.get("prepared")),
        "position_ms": max(0, int(data.get("position_ms") or 0)),
        "duration_ms": max(0, int(data.get("duration_ms") or 0)),
        "quality": str(data.get("quality") or ""),
        "updated_at": int(time.time()),
    }


def _current_state(device_id: str) -> dict[str, Any]:
    state = _state.get(device_id)
    if state is None:
        queue = list(_queue_for(device_id))
        return {
            "queue": queue,
            "index": -1,
            "playing": False,
            "prepared": False,
            "position_ms": 0,
            "duration_ms": 0,
            "quality": "",
            "source": "server",
        }
    queue = list(state["queue"])
    index = int(state["index"])
    view = dict(state)
    view["queue"] = queue
    view["index"] = index
    view["current"] = queue[index] if 0 <= index < len(queue) else None
    view["source"] = "tv"
    return view


async def _push_queue(request: Request, device_id: str, payload: dict[str, Any]) -> bool:
    return await request.app.state.hub.send_device(
        device_id, {"type": "command", "command": {"type": "music_queue", "payload": payload}}
    )


async def _enqueue(request: Request, device_id: str, body: SongBody) -> dict[str, Any]:
    """Adds a song and tells the TV over the agent socket, in the envelope CastAgent parses."""
    global _sequence
    device_id = device_id.strip()
    if not device_id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "缺少 device_id")
    quality = (body.quality or DEFAULT_QUALITY).strip() or DEFAULT_QUALITY
    direct = (body.url or "").strip()
    resolve_error = ""
    if not direct:
        try:
            direct = (await _resolve(body.mid, quality))["url"]
        except HTTPException as error:
            # Queueing still succeeds: the TV has its own /api/music/url call and can retry.
            resolve_error = str(error.detail)

    _sequence += 1
    entry = {
        "id": f"{int(time.time() * 1000)}-{_sequence}",
        "mid": body.mid,
        "name": body.name or body.mid,
        "singer": body.singer,
        "album": body.album,
        "cover": body.cover,
        "interval": int(body.interval or 0),
        "quality": quality,
        "url": direct,
        "queued_at": int(time.time()),
    }
    queue = _queue_for(device_id)
    queue.append(entry)
    # A living-room queue only grows; drop the oldest rather than let a stuck session grow forever.
    if len(queue) > MAX_QUEUE:
        del queue[: len(queue) - MAX_QUEUE]
    # Show the request on the phone at once; the TV's next music_state makes the order canonical.
    # record_state() aliases _queues[device_id] with state["queue"], so appending there again would
    # duplicate every request — only fix up the index for the optimistic view.
    state = _state.get(device_id)
    if state is not None and state.get("queue") is not queue:
        state["queue"].append({key: entry[key] for key in (
            "id", "mid", "name", "singer", "album", "cover", "interval", "quality"
        )})
        if len(state["queue"]) > MAX_QUEUE:
            del state["queue"][: len(state["queue"]) - MAX_QUEUE]
            state["index"] = max(-1, int(state.get("index", -1)) - 1)

    payload = {
        "id": entry["id"],
        "mid": entry["mid"],
        "name": entry["name"],
        "singer": entry["singer"],
        "album": entry["album"],
        "cover": entry["cover"],
        "interval": entry["interval"],
        "quality": entry["quality"],
    }
    if entry["url"]:
        payload["url"] = entry["url"]
    delivered = await request.app.state.hub.send_device(
        device_id, {"type": "command", "command": {"type": "music", "payload": payload}}
    )
    return {
        "ok": True,
        "delivered": bool(delivered),
        "queued": entry,
        "queue": list(queue),
        "count": len(queue),
        "resolve_error": resolve_error,
    }


@router.post("/queue/{device_id}")
async def enqueue_for_device(
    device_id: str,
    body: SongBody,
    request: Request,
    t: str = Query(default=""),
    user: dict[str, Any] | None = Depends(_console_user),
) -> dict[str, Any]:
    _require_device_access(request, device_id, t or (body.t or ""), user)
    return await _enqueue(request, device_id, body)


@router.get("/queue/{device_id}")
async def read_queue(
    device_id: str,
    request: Request,
    t: str = Query(default=""),
    user: dict[str, Any] | None = Depends(_console_user),
) -> dict[str, Any]:
    _require_device_access(request, device_id, t, user)
    state = _state.get(device_id.strip())
    queue = list(state["queue"] if state is not None else _queue_for(device_id.strip()))
    return {
        "device_id": device_id,
        "count": len(queue),
        "queue": queue,
        "index": int(state["index"]) if state is not None else -1,
        "next": queue[1] if len(queue) > 1 else None,
    }


@router.get("/state/{device_id}")
async def music_state(
    device_id: str,
    request: Request,
    t: str = Query(default=""),
    user: dict[str, Any] | None = Depends(_console_user),
) -> dict[str, Any]:
    """What the TV reported: the phone's 已点歌单 and the playing row come from here."""
    _require_device_access(request, device_id, t, user)
    device_id = device_id.strip()
    view = _current_state(device_id)
    view["device_id"] = device_id
    view["online"] = await request.app.state.hub.is_device_online(device_id)
    view["updated_at"] = int(_state.get(device_id, {}).get("updated_at", 0))
    return view


@router.put("/queue/{device_id}")
async def reorder_queue(
    device_id: str,
    body: QueueOrderBody,
    request: Request,
    t: str = Query(default=""),
    user: dict[str, Any] | None = Depends(_console_user),
) -> dict[str, Any]:
    """Drag and drop on the phone: reorder the upcoming songs and tell the TV the same order."""
    _require_device_access(request, device_id, t or (body.t or ""), user)
    device_id = device_id.strip()
    state = _state.get(device_id)
    if state is not None:
        queue = state["queue"]
        index = int(state["index"])
    else:
        queue = _queue_for(device_id)
        index = -1
    current_id = queue[index]["id"] if 0 <= index < len(queue) else ""
    prefix = list(queue[: index + 1]) if index >= 0 else []
    upcoming = list(queue[index + 1:]) if index >= 0 else list(queue)
    by_id = {item["id"]: item for item in upcoming if item["id"]}
    ordered: list[dict[str, Any]] = []
    for item_id in body.order:
        item = by_id.pop(item_id, None)
        if item is not None:
            ordered.append(item)
    ordered.extend(item for item in upcoming if item["id"] in by_id)
    ordered.extend(item for item in upcoming if not item["id"])
    queue = prefix + ordered
    _queues[device_id] = queue
    if state is not None:
        state["queue"] = queue
        state["index"] = index if index < len(queue) else (len(queue) - 1)
    delivered = await _push_queue(
        request, device_id, {"order": [item["id"] for item in ordered], "current": current_id}
    )
    return {
        "ok": True,
        "delivered": bool(delivered),
        "queue": queue,
        "index": index if index < len(queue) else (len(queue) - 1),
    }


@router.delete("/queue/{device_id}/{item_id}")
async def remove_queue_item(
    device_id: str,
    item_id: str,
    request: Request,
    t: str = Query(default=""),
    user: dict[str, Any] | None = Depends(_console_user),
) -> dict[str, Any]:
    """The phone's delete: remove the row and tell the TV; removing the current row skips it."""
    _require_device_access(request, device_id, t, user)
    device_id = device_id.strip()
    state = _state.get(device_id)
    if state is not None:
        queue = state["queue"]
        index = int(state["index"])
    else:
        queue = _queue_for(device_id)
        index = -1
    at = next((i for i, item in enumerate(queue) if item["id"] == item_id), -1)
    if at < 0:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "歌曲不在队列中")
    queue.pop(at)
    if not queue:
        index = -1
    elif at < index:
        index -= 1
    elif at == index and index >= len(queue):
        index = len(queue) - 1
    _queues[device_id] = queue
    if state is not None:
        state["queue"] = queue
        state["index"] = index
    delivered = await _push_queue(request, device_id, {"remove": item_id})
    return {"ok": True, "delivered": bool(delivered), "queue": queue, "index": index}


@router.post("/request")
async def request_song(
    body: SongBody,
    request: Request,
    t: str = Query(default=""),
    user: dict[str, Any] | None = Depends(_console_user),
) -> dict[str, Any]:
    """The phone page's one call: queue the song and push it to the TV."""
    _require_device_access(request, body.device_id or "", t or (body.t or ""), user)
    return await _enqueue(request, body.device_id or "", body)


_PAGE_FILE = Path(__file__).with_name("music_page.html")

_PAGE_DENIED = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>点歌链接已失效</title>
<style>
  html, body { height: 100%; margin: 0; }
  body { display: flex; flex-direction: column; align-items: center; justify-content: center;
         gap: 14px; padding: 32px; box-sizing: border-box; background: #081016; color: #eaf3f2;
         font: 16px/1.6 -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
  h1 { margin: 0; font-size: 20px; font-weight: 600; }
  p { margin: 0; max-width: 320px; text-align: center; color: #9fb3b0; }
</style>
</head>
<body>
  <h1>点歌链接已失效</h1>
  <p>这个点歌二维码只在一段时间内有效，请在电视的点歌台重新扫码。</p>
</body>
</html>
"""


@lru_cache(maxsize=1)
def _page_source() -> str:
    return _PAGE_FILE.read_text(encoding="utf-8")


@router.get("/devices")
async def music_devices(
    request: Request, _admin: dict[str, Any] = Depends(require_admin)
) -> list[dict]:
    """点歌页的设备下拉：只暴露已批准设备的 id 与名称，方便多台电视切换。"""
    rows = request.app.state.db.fetch_all(
        "SELECT device_id, name FROM devices WHERE status = 'approved' AND enabled = 1 ORDER BY id"
    )
    devices = []
    for row in rows:
        devices.append(
            {
                "device_id": row["device_id"],
                "name": row["name"] or row["device_id"],
                "online": await request.app.state.hub.is_device_online(row["device_id"]),
            }
        )
    return devices


@page_router.get("/music/{device_id}", response_class=HTMLResponse, include_in_schema=False)
async def music_page(
    device_id: str,
    request: Request,
    t: str = Query(default=""),
) -> HTMLResponse:
    """The phone page: one self-contained HTML file, behind a short-lived signed link."""
    if not music_verify(request.app.state.settings, device_id, t):
        return HTMLResponse(
            _PAGE_DENIED,
            status_code=status.HTTP_403_FORBIDDEN,
            headers={"Cache-Control": "no-store"},
        )
    safe_id = json.dumps(device_id).replace("</", "<\\/")
    return HTMLResponse(
        _page_source().replace("__DEVICE_ID__", safe_id),
        headers={"Cache-Control": "no-store"},
    )
