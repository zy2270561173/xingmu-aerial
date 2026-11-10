from __future__ import annotations

import asyncio
import time
from collections import defaultdict
from typing import Any

from fastapi import WebSocket, WebSocketDisconnect


SOCKET_CLOSED_ERRORS = (RuntimeError, WebSocketDisconnect)
PREVIEW_SEND_ERRORS = (asyncio.TimeoutError,) + SOCKET_CLOSED_ERRORS


class ConnectionHub:
    def __init__(self) -> None:
        self._devices: dict[str, WebSocket] = {}
        self._device_send_locks: dict[str, asyncio.Lock] = {}
        self._controls: dict[str, set[WebSocket]] = defaultdict(set)
        self._last_snapshot_requests: dict[str, float] = {}
        self._latest_snapshots: dict[str, tuple[float, dict[str, Any]]] = {}
        self._preview_requests: dict[str, dict[WebSocket, int]] = defaultdict(dict)
        self._lock = asyncio.Lock()

    async def set_device(self, device_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            previous = self._devices.get(device_id)
            self._devices[device_id] = websocket
            self._device_send_locks[device_id] = asyncio.Lock()
            self._last_snapshot_requests.pop(device_id, None)
        if previous is not None and previous is not websocket:
            try:
                await previous.close(code=4001, reason="replaced by a newer connection")
            except SOCKET_CLOSED_ERRORS:
                pass

    async def remove_device(self, device_id: str, websocket: WebSocket) -> bool:
        async with self._lock:
            if self._devices.get(device_id) is websocket:
                self._devices.pop(device_id, None)
                self._device_send_locks.pop(device_id, None)
                return True
        return False

    async def disconnect_device(self, device_id: str) -> None:
        async with self._lock:
            websocket = self._devices.pop(device_id, None)
            self._device_send_locks.pop(device_id, None)
            self._last_snapshot_requests.pop(device_id, None)
            self._latest_snapshots.pop(device_id, None)
        if websocket is not None:
            try:
                await websocket.close(code=4004, reason="device deleted")
            except SOCKET_CLOSED_ERRORS:
                pass

    async def add_control(self, scope: str, websocket: WebSocket) -> None:
        async with self._lock:
            self._controls[scope].add(websocket)

    async def remove_control(self, scope: str, websocket: WebSocket) -> None:
        async with self._lock:
            sockets = self._controls.get(scope)
            if sockets is not None:
                sockets.discard(websocket)
                if not sockets:
                    self._controls.pop(scope, None)

    async def start_preview(self, device_id: str, websocket: WebSocket, fps: int) -> bool:
        fps = min(15, max(1, fps))
        async with self._lock:
            self._preview_requests[device_id][websocket] = fps
            effective_fps = max(self._preview_requests[device_id].values())
        return await self.send_device(
            device_id, {"type": "preview_start", "fps": effective_fps, "width": 1280,
                        "height": 720, "quality": 55}
        )

    async def stop_preview(self, device_id: str, websocket: WebSocket) -> bool:
        async with self._lock:
            requests = self._preview_requests.get(device_id)
            if requests is None:
                return True
            requests.pop(websocket, None)
            if requests:
                effective_fps = max(requests.values())
                action = {"type": "preview_start", "fps": effective_fps, "width": 1280,
                          "height": 720, "quality": 55}
            else:
                self._preview_requests.pop(device_id, None)
                action = {"type": "preview_stop"}
        return await self.send_device(device_id, action)

    async def preview_fps(self, device_id: str) -> int:
        async with self._lock:
            requests = self._preview_requests.get(device_id, {})
            return max(requests.values(), default=0)

    async def disconnect_controls(
        self,
        scope: str,
        code: int = 4005,
        reason: str = "control access changed",
    ) -> None:
        async with self._lock:
            sockets = list(self._controls.pop(scope, set()))
        for websocket in sockets:
            try:
                await websocket.close(code=code, reason=reason)
            except SOCKET_CLOSED_ERRORS:
                pass

    async def send_device(self, device_id: str, payload: dict[str, Any]) -> bool:
        async with self._lock:
            websocket = self._devices.get(device_id)
            send_lock = self._device_send_locks.get(device_id)
        if websocket is None or send_lock is None:
            return False
        try:
            async with send_lock:
                async with self._lock:
                    if self._devices.get(device_id) is not websocket:
                        return False
                await websocket.send_json(payload)
            return True
        except SOCKET_CLOSED_ERRORS:
            await self.remove_device(device_id, websocket)
            return False

    async def request_snapshot(
        self, device_id: str, min_interval_seconds: float = 0.30
    ) -> tuple[bool, bool]:
        now = time.monotonic()
        async with self._lock:
            websocket = self._devices.get(device_id)
            if websocket is None:
                return False, False
            last_request = self._last_snapshot_requests.get(device_id, 0.0)
            if now - last_request < min_interval_seconds:
                return True, True
            self._last_snapshot_requests[device_id] = now
        sent = await self.send_device(device_id, {"type": "snapshot_request"})
        return sent, False

    async def set_latest_snapshot(
        self, device_id: str, payload: dict[str, Any]
    ) -> None:
        async with self._lock:
            self._latest_snapshots.pop(device_id, None)
            self._latest_snapshots[device_id] = (time.monotonic(), payload)
            while len(self._latest_snapshots) > 64:
                self._latest_snapshots.pop(next(iter(self._latest_snapshots)))

    async def get_latest_snapshot(
        self, device_id: str, max_age_seconds: float = 30.0
    ) -> dict[str, Any] | None:
        async with self._lock:
            cached = self._latest_snapshots.get(device_id)
            if cached is None:
                return None
            created_at, payload = cached
            if time.monotonic() - created_at > max_age_seconds:
                self._latest_snapshots.pop(device_id, None)
                return None
            return payload

    async def broadcast_control(self, device_id: str, payload: dict[str, Any]) -> None:
        async with self._lock:
            targets = set(self._controls.get("*", set()))
            targets.update(self._controls.get(device_id, set()))
        stale: list[WebSocket] = []
        for websocket in targets:
            try:
                await websocket.send_json(payload)
            except SOCKET_CLOSED_ERRORS:
                stale.append(websocket)
        if stale:
            async with self._lock:
                for sockets in self._controls.values():
                    sockets.difference_update(stale)

    async def broadcast_preview_bytes(self, device_id: str, jpeg: bytes) -> None:
        async with self._lock:
            targets = set(self._preview_requests.get(device_id, {}))
        stale: list[WebSocket] = []
        async def send(websocket: WebSocket) -> None:
            try:
                await asyncio.wait_for(websocket.send_bytes(jpeg), timeout=0.12)
            except PREVIEW_SEND_ERRORS:
                stale.append(websocket)

        await asyncio.gather(*(send(websocket) for websocket in targets))
        should_stop = False
        if stale:
            async with self._lock:
                requests = self._preview_requests.get(device_id)
                if requests is not None:
                    for websocket in stale:
                        requests.pop(websocket, None)
                    if not requests:
                        self._preview_requests.pop(device_id, None)
                        should_stop = True
        if should_stop:
            await self.send_device(device_id, {"type": "preview_stop"})

    async def is_device_online(self, device_id: str) -> bool:
        async with self._lock:
            return device_id in self._devices

    async def stats(self) -> dict[str, int]:
        async with self._lock:
            return {
                "connected_agents": len(self._devices),
                "connected_controls": sum(len(sockets) for sockets in self._controls.values()),
                "control_scopes": len(self._controls),
                "cached_snapshots": len(self._latest_snapshots),
                "preview_streams": sum(len(items) for items in self._preview_requests.values()),
            }
