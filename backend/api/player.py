from __future__ import annotations

import json
import asyncio
import ipaddress
import re
import shutil
import socket
from urllib.parse import quote, urljoin, urlsplit

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from starlette.background import BackgroundTask
from starlette.responses import StreamingResponse

from dependencies import get_current_user
from access import require_entitlement
from models import PlayerSessionRequest


router = APIRouter(tags=["hosted-player"])


@router.post("/api/player/sessions")
async def create_player_session(
    payload: PlayerSessionRequest,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    require_entitlement(user, "remote_cast", local_allowed=False)
    require_entitlement(user, "relay_access", local_allowed=False)
    ticket, session = request.app.state.player_sessions.create(
        source_url=str(payload.url),
        media_type=payload.media_type,
        title=payload.title,
        owner_user_id=user["id"],
    )
    base_url = request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")
    return {
        "ticket": ticket,
        "player_path": f"/player/{quote(ticket, safe='')}",
        "player_url": f"{base_url}/player/{quote(ticket, safe='')}",
        "engine": "dplayer",
        "expires_at": session.expires_at,
        "transcode_available": False,
        "ffmpeg_detected": shutil.which("ffmpeg") is not None,
        "note": "当前为电视端直接解码；云端 FFmpeg 转码尚未启用",
    }


@router.get("/player/{ticket}", response_class=HTMLResponse, include_in_schema=False)
async def hosted_player(ticket: str, request: Request) -> HTMLResponse:
    session = request.app.state.player_sessions.get(ticket)
    if session is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "player ticket is invalid or expired")
    return _render_player(
        f"/player/{quote(ticket, safe='')}/media",
        session.media_type,
        session.title,
    )


@router.get("/player/{ticket}/media", include_in_schema=False)
async def hosted_player_media(ticket: str, request: Request) -> Response:
    session = request.app.state.player_sessions.get(ticket)
    if session is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "player ticket is invalid or expired")
    return await _relay_media(
        ticket,
        session.source_url,
        request,
        hinted_media_type=session.media_type,
    )


@router.get("/player/{ticket}/resource/{resource_token}", include_in_schema=False)
async def hosted_player_resource(
    ticket: str,
    resource_token: str,
    request: Request,
) -> Response:
    target_url = request.app.state.player_sessions.get_resource(ticket, resource_token)
    if target_url is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "player resource is invalid or expired")
    return await _relay_media(ticket, target_url, request)


async def _relay_media(
    ticket: str,
    source_url: str,
    request: Request,
    hinted_media_type: str = "",
) -> Response:
    range_header = request.headers.get("range", "")
    if range_header and not re.fullmatch(r"bytes=\d*-\d*", range_header):
        raise HTTPException(status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE, "invalid range")
    playlist_hint = _looks_like_hls(source_url, hinted_media_type)

    client = httpx.AsyncClient(
        follow_redirects=False,
        timeout=httpx.Timeout(connect=10, read=30, write=10, pool=10),
        trust_env=False,
    )
    upstream = None
    target_url = source_url
    try:
        for _ in range(5):
            await _ensure_public_http_url(target_url)
            headers = {
                "Accept": "*/*",
                "Accept-Encoding": "identity",
                "User-Agent": "StarlightTvCast-MediaRelay/0.7",
            }
            if range_header and not playlist_hint:
                headers["Range"] = range_header
            upstream = await client.send(
                client.build_request("GET", target_url, headers=headers),
                stream=True,
            )
            if upstream.status_code not in {301, 302, 303, 307, 308}:
                break
            location = upstream.headers.get("location")
            await upstream.aclose()
            upstream = None
            if not location:
                raise HTTPException(status.HTTP_502_BAD_GATEWAY, "upstream redirect has no location")
            target_url = urljoin(target_url, location)
        else:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, "too many upstream redirects")
    except Exception as exc:
        if upstream is not None:
            await upstream.aclose()
        await client.aclose()
        if isinstance(exc, HTTPException):
            raise
        if isinstance(exc, httpx.HTTPError):
            raise HTTPException(
                status.HTTP_502_BAD_GATEWAY,
                "upstream media connection failed",
            ) from exc
        raise

    if upstream.status_code >= 400:
        code = upstream.status_code
        await upstream.aclose()
        await client.aclose()
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"upstream media HTTP {code}")

    content_type = upstream.headers.get("content-type") or hinted_media_type or "application/octet-stream"
    if playlist_hint or "mpegurl" in content_type.lower():
        content_length = int(upstream.headers.get("content-length", "0") or 0)
        if content_length > 2 * 1024 * 1024:
            await upstream.aclose()
            await client.aclose()
            raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "HLS playlist is too large")
        body = await upstream.aread()
        await upstream.aclose()
        await client.aclose()
        if len(body) > 2 * 1024 * 1024:
            raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "HLS playlist is too large")
        try:
            playlist = body.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, "upstream HLS playlist is not UTF-8") from exc
        if playlist.lstrip().startswith("#EXTM3U"):
            playlist = _rewrite_hls_playlist(
                playlist,
                target_url,
                ticket,
                request.app.state.player_sessions,
            )
            return Response(
                content=playlist.encode("utf-8"),
                media_type="application/vnd.apple.mpegurl",
                headers={
                    "Cache-Control": "no-store",
                    "X-Content-Type-Options": "nosniff",
                },
            )
        return Response(
            content=body,
            media_type=content_type,
            headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
        )

    response_headers = {
        "Cache-Control": "no-store",
        "Accept-Ranges": upstream.headers.get("accept-ranges", "bytes"),
        "X-Content-Type-Options": "nosniff",
    }
    for name in ("content-length", "content-range", "etag", "last-modified"):
        if value := upstream.headers.get(name):
            response_headers[name] = value

    async def close_upstream() -> None:
        await upstream.aclose()
        await client.aclose()

    return StreamingResponse(
        upstream.aiter_raw(),
        status_code=upstream.status_code,
        media_type=content_type,
        headers=response_headers,
        background=BackgroundTask(close_upstream),
    )


@router.get("/player", include_in_schema=False)
@router.get("/palyer", include_in_schema=False)
async def hosted_player_query(
    request: Request,
    ticket: str | None = Query(default=None, min_length=32, max_length=128),
    url: str | None = Query(default=None, min_length=8, max_length=8192),
    media_type: str = Query(default="", max_length=128),
) -> RedirectResponse:
    if ticket:
        return RedirectResponse(f"/player/{quote(ticket, safe='')}", status_code=307)
    if not url:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "ticket or url is required")
    payload = PlayerSessionRequest(url=url, media_type=media_type, title="星投屏")
    await _ensure_public_http_url(str(payload.url))
    created_ticket, _session = request.app.state.player_sessions.create(
        source_url=str(payload.url),
        media_type=payload.media_type,
        title=payload.title,
        owner_user_id=0,
    )
    return RedirectResponse(f"/player/{quote(created_ticket, safe='')}", status_code=307)


_HLS_URI_ATTRIBUTE = re.compile(r'URI="([^"]+)"')


def _looks_like_hls(url: str, media_type: str) -> bool:
    path = urlsplit(url).path.lower()
    return "mpegurl" in media_type.lower() or path.endswith(".m3u8") or path.endswith(".m3u")


def _rewrite_hls_playlist(playlist: str, base_url: str, ticket: str, store) -> str:
    def proxy_url(value: str) -> str:
        absolute = urljoin(base_url, value.strip())
        try:
            parsed = urlsplit(absolute)
        except ValueError:
            return value
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return value
        token = store.register_resource(ticket, absolute)
        if token is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "player ticket is invalid or expired")
        return f"/player/{quote(ticket, safe='')}/resource/{quote(token, safe='')}"

    rewritten: list[str] = []
    for line in playlist.splitlines():
        stripped = line.strip()
        if not stripped:
            rewritten.append(line)
        elif stripped.startswith("#"):
            rewritten.append(
                _HLS_URI_ATTRIBUTE.sub(lambda match: f'URI="{proxy_url(match.group(1))}"', line)
            )
        else:
            rewritten.append(line.replace(stripped, proxy_url(stripped), 1))
    suffix = "\n" if playlist.endswith(("\n", "\r")) else ""
    return "\n".join(rewritten) + suffix


def _render_player(media_url: str, media_type: str, title: str) -> HTMLResponse:
    config_json = json.dumps(
        {"url": media_url, "mediaType": media_type, "title": title or "星投屏"},
        ensure_ascii=False,
        separators=(",", ":"),
    ).replace("</", "<\\/")
    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
  <title>星投屏 DPlayer</title>
  <style>
    html,body,#player{{width:100%;height:100%;margin:0;background:#000;overflow:hidden}}
    #status{{position:fixed;left:20px;right:20px;bottom:20px;z-index:20;padding:12px 16px;
      color:#fff;background:rgba(20,25,23,.88);font:16px sans-serif;display:block}}
    #status.error{{background:rgba(145,35,35,.92)}}
  </style>
</head>
<body>
  <div id="player"></div><div id="status">正在连接后端视频流...</div>
  <script src="/static/player/hls.light-0.14.17.min.js"></script>
  <script src="/static/player/DPlayer-1.25.1.min.js"></script>
  <script>
  (function(){{
    'use strict';
    var config={config_json};
    var hls=null;
    var started=false;
    var status=document.getElementById('status');
    function report(kind,message){{
      if(kind==='error'){{status.className='error';status.textContent=message||'播放失败';status.style.display='block';}}
      try{{if(window.StarlightNative&&window.StarlightNative.onPlayerEvent){{
        window.StarlightNative.onPlayerEvent(kind,String(message||''));
      }}}}catch(ignore){{}}
    }}
    var cleanUrl=config.url.split('?')[0].toLowerCase();
    var isHls=config.mediaType.indexOf('mpegurl')>=0 || /\\.m3u8$/.test(cleanUrl);
    var video={{url:config.url,type:isHls?'customHls':'auto'}};
    if(isHls){{
      video.customType={{customHls:function(media){{
        if(window.Hls&&window.Hls.isSupported()){{
          hls=new window.Hls({{enableWorker:true,lowLatencyMode:false,backBufferLength:30}});
          hls.loadSource(media.src);hls.attachMedia(media);
          hls.on(window.Hls.Events.ERROR,function(_,data){{
            if(data&&data.fatal) report('error','HLS 播放失败：'+String(data.details||data.type||''));
          }});
        }}else{{media.src=config.url;}}
      }}}};
    }}
    var player=new window.DPlayer({{
      container:document.getElementById('player'),autoplay:true,theme:'#46c7a5',
      lang:'zh-cn',hotkey:true,preload:'auto',video:video
    }});
    player.on('loadstart',function(){{if(!started){{status.className='';status.textContent='正在读取视频数据...';status.style.display='block';}}}});
    player.on('waiting',function(){{status.className='';status.textContent='视频缓冲中...';status.style.display='block';}});
    player.on('playing',function(){{started=true;status.style.display='none';report('playing','');}});
    player.on('pause',function(){{report('pause','');}});
    player.on('error',function(){{report('error','视频源无法解码，可切换电视端原生播放内核');}});
    window.StarlightPlayer={{
      play:function(){{player.play();}},pause:function(){{player.pause();}},
      stop:function(){{player.pause();player.seek(0);}},
      seek:function(ms){{player.seek(Math.max(0,Number(ms)||0)/1000);}},
      destroy:function(){{if(hls)hls.destroy();player.destroy();}}
    }};
    window.setInterval(function(){{
      try{{if(window.StarlightNative&&window.StarlightNative.onPlayerProgress){{
        var media=player.video;
        window.StarlightNative.onPlayerProgress(Number(media.currentTime||0),Number(media.duration||0),Boolean(media.paused));
      }}}}catch(ignore){{}}
    }},1000);
    document.addEventListener('visibilitychange',function(){{if(document.hidden)player.pause();}});
    window.setTimeout(function(){{if(!started){{status.className='error';status.textContent='视频加载超时，请检查源地址或切换电视播放器';status.style.display='block';}}}},20000);
    if(player.fullScreen&&player.fullScreen.request){{try{{player.fullScreen.request('web');}}catch(ignore){{}}}}
  }})();
  </script>
</body>
</html>"""
    return HTMLResponse(
        html,
        headers={
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": (
                "default-src 'none'; script-src 'self' 'unsafe-inline'; "
                "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: android-webview:; "
                "media-src 'self' blob:; connect-src 'self';"
            ),
        },
    )


async def _ensure_public_http_url(value: str) -> None:
    try:
        parsed = urlsplit(value)
    except ValueError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "invalid upstream media URL") from exc
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "invalid upstream media URL")
    if parsed.username or parsed.password:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "upstream media URL cannot contain credentials")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        addresses = await asyncio.get_running_loop().getaddrinfo(
            parsed.hostname,
            port,
            type=socket.SOCK_STREAM,
        )
    except OSError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "upstream media host cannot be resolved") from exc
    if not addresses:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "upstream media host cannot be resolved")
    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if not ip.is_global:
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST,
                "private or local upstream media addresses are not allowed",
            )
