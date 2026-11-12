from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
import time

from fastapi import Depends, FastAPI, HTTPException, Request, Response, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from api import approval, auth_api, commands, devices, music, pairing, p2p, player, releases, screen, settings_api, upload, users, vip
from config import BASE_DIR, Settings
from db import Database
from dependencies import get_current_user
from hub import ConnectionHub
from player_sessions import PlayerSessionStore
from p2p_rendezvous import P2PSessionStore, start_rendezvous
from ws import control_ws, device_ws


def create_app(settings: Settings | None = None) -> FastAPI:
    current_settings = settings or Settings.from_env()
    database = Database(current_settings.database_path)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        database.initialize(
            current_settings.admin_username, current_settings.admin_password
        )
        # P2P 的 UDP 会合是可选能力：端口被占用时不能让整个后端起不来（注册、心跳、遥控、
        # 会员、支付都与它无关）。绑定失败就降级，客户端会自动回落到后端转发。
        try:
            transport = await start_rendezvous(app)
        except OSError as error:
            transport = None
            print(
                f"[p2p] rendezvous disabled: {error} "
                f"(port {current_settings.p2p_udp_port}); clients fall back to relay",
                flush=True,
            )
        app.state.rendezvous_transport = transport
        payment_reconciliation = asyncio.create_task(vip.payment_reconciliation_worker(app))
        try:
            yield
        finally:
            payment_reconciliation.cancel()
            with suppress(asyncio.CancelledError):
                await payment_reconciliation
            if transport is not None:
                transport.close()

    app = FastAPI(
        title="星投屏控制中心",
        version="0.14.11",
        description="设备认证、Agent 上报、控制信令、P2P 协商与安全托管播放页。",
        lifespan=lifespan,
    )
    app.state.settings = current_settings
    app.state.db = database
    app.state.hub = ConnectionHub()
    app.state.player_sessions = PlayerSessionStore()
    app.state.p2p_sessions = P2PSessionStore()
    app.state.started_at = time.monotonic()
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(current_settings.cors_origins),
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Device-ID", "X-Device-Token"],
    )
    app.mount("/static/apk", StaticFiles(directory=current_settings.apk_dir), name="apks")
    app.mount(
        "/static/player",
        StaticFiles(directory=BASE_DIR / "static" / "player"),
        name="player-assets",
    )
    for router in (
        auth_api.router,
        devices.router,
        approval.router,
        approval.compat_router,
        commands.router,
        commands.compat_router,
        upload.router,
        releases.router,
        settings_api.router,
        users.router,
        vip.router,
        pairing.router,
        p2p.router,
        player.router,
        music.router,
        music.page_router,
        screen.router,
        screen.page_router,
        device_ws.router,
        control_ws.router,
    ):
        app.include_router(router)

    @app.get("/api/wallpaper/bing", include_in_schema=False)
    async def bing_wallpaper() -> Response:
        """必应每日一图：服务端下载并缓存，浏览器直接引用同源 URL，避免 CORS。

        拿不到图片时返回 1x1 透明 PNG，前端保持纯色背景，不影响使用。
        """
        import datetime as _dt
        from pathlib import Path as _Path

        cache_dir = _Path(current_settings.data_dir) / "wallpaper"
        cache_dir.mkdir(parents=True, exist_ok=True)
        today = _dt.date.today().strftime("%Y%m%d")
        cached = cache_dir / f"{today}.jpg"
        if cached.is_file() and cached.stat().st_size > 1024:
            return Response(
                cached.read_bytes(),
                media_type="image/jpeg",
                headers={"Cache-Control": "public, max-age=3600"},
            )
        try:
            import httpx

            async with httpx.AsyncClient(timeout=8.0, follow_redirects=True) as client:
                meta = await client.get(
                    "https://cn.bing.com/HPImageArchive.aspx",
                    params={"format": "js", "idx": "0", "n": "1", "mkt": "zh-CN"},
                )
                meta.raise_for_status()
                urlbase = (meta.json().get("images") or [{}])[0].get("urlbase") or ""
                if not urlbase:
                    raise ValueError("no urlbase")
                image = await client.get(f"https://cn.bing.com{urlbase}_1920x1080.jpg")
                image.raise_for_status()
                payload = image.content
                if len(payload) < 1024:
                    raise ValueError("image too small")
                cached.write_bytes(payload)
        except Exception:
            return Response(
                bytes.fromhex(
                    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                    "1f15c4890000000a49444154789c6300010000050001"
                    "0d0a2db40000000049454e44ae426082"
                ),
                media_type="image/png",
                headers={"Cache-Control": "no-store"},
            )
        return Response(
            payload,
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=3600"},
        )

    @app.get("/api/health", tags=["system"])
    async def health(request: Request) -> dict:
        connections = await request.app.state.hub.stats()
        rendezvous_up = getattr(request.app.state, "rendezvous_transport", None) is not None
        return {
            "status": "ok",
            "version": request.app.version,
            "uptime_seconds": int(time.monotonic() - request.app.state.started_at),
            "media_relay": True,
            "media_relay_scope": "hosted_player_only",
            "hosted_player": "dplayer",
            "cloud_transcode": False,
            # Report the listener we actually own: saying "udp" while the rendezvous never bound
            # sent people chasing a P2P problem that was really a missing UDP port.
            "p2p": "udp_hole_punch_with_websocket_fallback" if rendezvous_up else "websocket_relay_only",
            "p2p_sessions": request.app.state.p2p_sessions.stats(),
            **connections,
        }

    @app.get("/api/system/status", tags=["system"])
    async def system_status(
        request: Request, user: dict = Depends(get_current_user)
    ) -> dict:
        if user["role"] != "admin":
            raise HTTPException(status.HTTP_403_FORBIDDEN, "administrator access required")
        counts = database.fetch_one(
            """
            SELECT
              (SELECT COUNT(*) FROM devices) AS registered_devices,
              (SELECT COUNT(*) FROM devices WHERE status = 'approved' AND enabled = 1) AS enabled_devices,
              (SELECT COUNT(*) FROM pending_agents WHERE state = 'pending') AS pending_approvals,
              (SELECT COUNT(*) FROM commands WHERE status IN ('pending', 'delivered', 'started')) AS active_commands,
              (SELECT COUNT(*) FROM commands WHERE status = 'failed') AS failed_commands,
              (SELECT COUNT(*) FROM apks) AS stored_apks
            """
        ) or {}
        db_size = database.path.stat().st_size if database.path.exists() else 0
        return {
            "status": "ok",
            "version": request.app.version,
            "uptime_seconds": int(time.monotonic() - request.app.state.started_at),
            "database_size_bytes": db_size,
            "media_relay": True,
            "media_relay_scope": "hosted_player_only",
            "cloud_transcode": False,
            "p2p_mode": "udp_hole_punch_with_websocket_fallback",
            **counts,
            **(await request.app.state.hub.stats()),
        }

    # 前端构建产物随后端一起发布：同源，免去单独配站点与跨域
    webui_dir = BASE_DIR / "webui"
    if (webui_dir / "index.html").is_file():
        app.mount("/admin/assets", StaticFiles(directory=webui_dir / "assets"), name="admin-assets")

        @app.get("/admin", include_in_schema=False)
        @app.get("/admin/{path:path}", include_in_schema=False)
        async def admin_spa(path: str = "") -> FileResponse:
            candidate = (webui_dir / path).resolve()
            if path and webui_dir.resolve() in candidate.parents and candidate.is_file():
                return FileResponse(candidate)
            return FileResponse(webui_dir / "index.html")

    # 根路径是落地页（下载页），控制台在 /console
    landing_page = BASE_DIR / "landing" / "index.html"
    if landing_page.is_file():

        @app.get("/", include_in_schema=False)
        @app.get("/index", include_in_schema=False)
        async def landing() -> FileResponse:
            return FileResponse(landing_page)

    return app


app = create_app()

if __name__ == "__main__":
    # 让「python main.py」也能直接跑起来：宝塔等面板常用这种启动方式。
    import os

    import uvicorn

    uvicorn.run(
        "main:app",
        host=os.getenv("TVCAST_HOST", "0.0.0.0"),
        port=int(os.getenv("TVCAST_PORT", "18000")),
        proxy_headers=True,
        forwarded_allow_ips="*",
    )