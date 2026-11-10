from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
import time

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from api import approval, auth_api, commands, devices, pairing, p2p, player, releases, settings_api, upload, users, vip
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
        transport = await start_rendezvous(app)
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
        device_ws.router,
        control_ws.router,
    ):
        app.include_router(router)

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

    return app


app = create_app()
