"""内置点歌台：第三方曲库代理、按设备的点歌队列和手机点歌页。

The TV and the phone never talk to the music CDN directly: every search, quality list, lyric and
playback URL comes through here, and the TV plays the ``/stream`` URL so a short-lived CDN token
never has to survive on the TV.  The upstream service needs no key, so there is no secret to keep;
what this module adds is a stable, same-origin API, a queue per device and the page a phone scans.
"""

from __future__ import annotations

import json
import os
import time
from typing import Any, AsyncIterator
from urllib.parse import quote, urlparse

import httpx
from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import HTMLResponse, Response, StreamingResponse
from pydantic import BaseModel, Field

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

router = APIRouter(prefix="/api/music", tags=["music"])
page_router = APIRouter(tags=["music"])

# In-memory by design: a queue is a living room's "已点" list, not history worth keeping.
_queues: dict[str, list[dict[str, Any]]] = {}
_sequence = 0


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

    payload = {
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
async def enqueue_for_device(device_id: str, body: SongBody, request: Request) -> dict[str, Any]:
    return await _enqueue(request, device_id, body)


@router.get("/queue/{device_id}")
async def read_queue(device_id: str) -> dict[str, Any]:
    queue = list(_queue_for(device_id.strip()))
    return {
        "device_id": device_id,
        "count": len(queue),
        "queue": queue,
        "next": queue[1] if len(queue) > 1 else None,
    }


@router.post("/request")
async def request_song(body: SongBody, request: Request) -> dict[str, Any]:
    """The phone page's one call: queue the song and push it to the TV."""
    return await _enqueue(request, body.device_id or "", body)


_PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b0b10">
<title>星幕点歌台</title>
<style>
  :root { color-scheme: dark; --ink:#f5f5f7; --dim:rgba(235,235,245,.62); --faint:rgba(235,235,245,.34);
          --glass:rgba(255,255,255,.07); --line:rgba(255,255,255,.10); --accent:#7d6bff; }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  html,body { margin:0; min-height:100%; }
  body {
    font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif;
    color:var(--ink); background:#08080c; padding:22px 16px 40px;
    background-image:
      radial-gradient(120% 70% at 8% -10%, rgba(125,107,255,.42), transparent 60%),
      radial-gradient(90% 60% at 100% 0%, rgba(255,94,138,.22), transparent 55%),
      radial-gradient(90% 60% at 50% 110%, rgba(51,199,255,.14), transparent 60%);
    background-attachment:fixed;
  }
  .wrap { max-width:680px; margin:0 auto; }
  header { display:flex; align-items:center; gap:12px; margin:4px 2px 18px; }
  .logo { width:42px; height:42px; border-radius:13px; flex:0 0 auto;
    background:linear-gradient(145deg,#8f7cff,#4b3ad6); box-shadow:0 8px 24px rgba(90,70,255,.35);
    display:grid; place-items:center; font-size:22px; }
  h1 { font-size:20px; margin:0; letter-spacing:.5px; font-weight:650; }
  .sub { margin:2px 0 0; font-size:12px; color:var(--dim); }
  .card { background:var(--glass); border:1px solid var(--line); border-radius:20px;
    padding:14px; backdrop-filter:blur(18px); -webkit-backdrop-filter:blur(18px);
    box-shadow:0 12px 40px rgba(0,0,0,.28); }
  .search { display:flex; gap:9px; }
  input[type=search] { flex:1; min-width:0; height:46px; border-radius:14px; border:1px solid var(--line);
    background:rgba(0,0,0,.28); color:var(--ink); padding:0 14px; font-size:16px; outline:none; }
  input[type=search]:focus { border-color:rgba(125,107,255,.7); box-shadow:0 0 0 3px rgba(125,107,255,.16); }
  button { font-family:inherit; cursor:pointer; }
  .go { height:46px; padding:0 20px; border:0; border-radius:14px; font-size:15px; font-weight:650;
    color:#fff; background:linear-gradient(145deg,#8f7cff,#5b48e8); box-shadow:0 8px 20px rgba(90,70,255,.35); }
  .go:active { transform:scale(.97); }
  .row { display:flex; gap:10px; align-items:center; padding:10px 4px; border-bottom:1px solid rgba(255,255,255,.06); }
  .row:last-child { border-bottom:0; }
  .cover { width:50px; height:50px; border-radius:12px; object-fit:cover; background:rgba(255,255,255,.08); flex:0 0 auto; }
  .meta { flex:1; min-width:0; }
  .name { font-size:15px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .singer { font-size:12px; color:var(--dim); margin-top:3px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .album { font-size:11px; color:var(--faint); margin-top:2px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .pick { border:1px solid rgba(125,107,255,.5); background:rgba(125,107,255,.16); color:#cfc7ff;
    border-radius:12px; height:36px; padding:0 15px; font-size:13px; font-weight:600; flex:0 0 auto; }
  .pick:active { transform:scale(.95); }
  .pick[disabled] { opacity:.45; }
  .quality { display:flex; gap:7px; margin:12px 2px 0; flex-wrap:wrap; }
  .chip { border:1px solid var(--line); background:rgba(255,255,255,.04); color:var(--dim);
    border-radius:99px; padding:7px 13px; font-size:12px; }
  .chip.on { color:#fff; border-color:rgba(125,107,255,.65); background:rgba(125,107,255,.22); }
  .status { min-height:20px; margin:10px 4px 0; font-size:12.5px; color:var(--dim); }
  .status.bad { color:#ff8a80; }
  .status.good { color:#8ef0a8; }
  h2 { font-size:13px; color:var(--dim); font-weight:600; margin:22px 4px 8px; letter-spacing:.4px; }
  #results { margin-top:10px; }
  .empty { color:var(--faint); font-size:13px; text-align:center; padding:26px 0; }
  #queue .q { display:flex; gap:8px; align-items:baseline; padding:9px 4px; border-bottom:1px solid rgba(255,255,255,.06); }
  #queue .q:last-child { border-bottom:0; }
  .qn { font-size:14px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .qs { font-size:12px; color:var(--dim); white-space:nowrap; flex:0 0 auto; }
  .idx { font-size:11px; color:var(--faint); width:16px; flex:0 0 auto; }
  footer { text-align:center; color:var(--faint); font-size:11px; margin-top:22px; }
  .spin { display:inline-block; width:14px; height:14px; border:2px solid rgba(255,255,255,.25);
    border-top-color:#fff; border-radius:50%; animation:sp .8s linear infinite; vertical-align:-2px; margin-right:6px; }
  @keyframes sp { to { transform:rotate(360deg); } }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">♪</div>
    <div>
      <h1>星幕点歌台</h1>
      <p class="sub">手机扫码点歌 · 电视马上播放</p>
    </div>
  </header>

  <section class="card">
    <div class="search">
      <input id="q" type="search" placeholder="搜索歌名 / 歌手" enterkeyhint="search" autocomplete="off">
      <button id="searchBtn" class="go">搜索</button>
    </div>
    <div class="quality" id="quality">
      <button class="chip on" data-q="MP3_320">高品质 320K</button>
      <button class="chip" data-q="ACC_192">AAC 192K</button>
      <button class="chip" data-q="MP3_128">标准 128K</button>
    </div>
    <div class="status" id="status"></div>
    <div id="results"><div class="empty">输入歌名或歌手，开始点歌</div></div>
  </section>

  <h2>已点歌单</h2>
  <section class="card" id="queue"><div class="empty">还没有点歌</div></section>
  <footer>星幕 · 客厅电视点歌台</footer>
</div>
<script>
  const DEVICE_ID = __DEVICE_ID__;
  const API = "/api/music";
  let quality = "MP3_320";
  let results = [];
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

  function say(text, kind) {
    const el = $("status");
    el.textContent = text;
    el.className = "status" + (kind ? " " + kind : "");
  }

  document.querySelectorAll(".chip").forEach((chip) => {
    chip.addEventListener("click", () => {
      quality = chip.dataset.q;
      document.querySelectorAll(".chip").forEach((c) => c.classList.toggle("on", c === chip));
    });
  });

  function render(list) {
    results = list;
    const box = $("results");
    if (!list.length) { box.innerHTML = '<div class="empty">没有找到，换个关键词试试</div>'; return; }
    box.innerHTML = list.map((song, i) =>
      '<div class="row">' +
        '<img class="cover" loading="lazy" referrerpolicy="no-referrer" src="' + esc(song.cover) + '" onerror="this.style.visibility=\\'hidden\\'">' +
        '<div class="meta"><div class="name">' + esc(song.name) + '</div>' +
        '<div class="singer">' + esc(song.singer) + '</div>' +
        '<div class="album">' + esc(song.album || "") + (song.interval ? " · " + Math.floor(song.interval / 60) + ":" + String(song.interval % 60).padStart(2, "0") : "") + '</div></div>' +
        '<button class="pick" data-i="' + i + '">点歌</button>' +
      '</div>'
    ).join("");
    box.querySelectorAll(".pick").forEach((button) => {
      button.addEventListener("click", () => pick(parseInt(button.dataset.i, 10), button));
    });
  }

  async function search() {
    const keyword = $("q").value.trim();
    if (!keyword) { say("先输入歌名或歌手", "bad"); return; }
    say('<span class="spin"></span>正在搜索…');
    try {
      const response = await fetch(API + "/search?q=" + encodeURIComponent(keyword) + "&limit=20");
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail || ("HTTP " + response.status));
      render(data.data.list || []);
      say("找到 " + (data.data.list || []).length + " 首，点「点歌」推送到电视");
    } catch (error) {
      say("搜索失败：" + error.message, "bad");
    }
  }

  async function pick(index, button) {
    const song = results[index];
    if (!song) return;
    button.disabled = true;
    say('<span class="spin"></span>正在点歌…');
    try {
      const response = await fetch(API + "/request", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          device_id: DEVICE_ID, mid: song.mid, name: song.name, singer: song.singer,
          album: song.album || "", cover: song.cover, interval: song.interval, quality: quality
        })
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail || ("HTTP " + response.status));
      say(data.delivered ? ("已点：" + song.name + "，电视开始播放") : ("已加入队列：" + song.name + "（电视当前不在线）"),
          data.delivered ? "good" : "");
      loadQueue();
    } catch (error) {
      say("点歌失败：" + error.message, "bad");
    } finally {
      button.disabled = false;
    }
  }

  async function loadQueue() {
    try {
      const response = await fetch(API + "/queue/" + encodeURIComponent(DEVICE_ID));
      const data = await response.json();
      const queue = data.queue || [];
      const box = $("queue");
      if (!queue.length) { box.innerHTML = '<div class="empty">还没有点歌</div>'; return; }
      box.innerHTML = queue.map((song, i) =>
        '<div class="q"><span class="idx">' + (i + 1) + '</span>' +
        '<span class="qn">' + esc(song.name) + '</span>' +
        '<span class="qs">' + esc(song.singer) + '</span></div>'
      ).join("");
    } catch (error) { /* the queue is a convenience; keep the page usable */ }
  }

  $("searchBtn").addEventListener("click", search);
  $("q").addEventListener("keydown", (event) => { if (event.key === "Enter") search(); });
  loadQueue();
  setInterval(loadQueue, 15000);
</script>
</body>
</html>
"""


@page_router.get("/music/{device_id}", response_class=HTMLResponse, include_in_schema=False)
async def music_page(device_id: str) -> HTMLResponse:
    """The phone page: one self-contained file, no build step and no login."""
    safe_id = json.dumps(device_id).replace("</", "<\\/")
    return HTMLResponse(_PAGE.replace("__DEVICE_ID__", safe_id))
