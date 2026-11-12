"""把电视本机的 adbd 反向映射到后端的一个 127.0.0.1 端口。

公网机房里的后端和家庭内网里的电视之间没有可达地址，服务器上的 ``adb connect
192.168.31.245:5555`` 永远连不上，于是 ``screen.mjpeg`` 一帧都没有、``snapshot`` 也
等不到结果。这里让电视主动连出来：每台设备在 ``/ws/tunnel`` 上保持若干条空闲的
WebSocket；后端为它在 ``127.0.0.1`` 上开一个监听端口，任何本地 TCP 连接（也就是
adb 自己的连接）都会被接到其中一条空闲隧道上，之后两端只转发二进制字节。

控制帧是文本 JSON，数据帧是二进制，二者在同一个 WebSocket 上：

    C→S  {"type":"auth","device_id":"lite-xxxx","token":"..."}
    S→C  {"type":"tunnel_ready","device_id":"lite-xxxx","port":15000,"max_tunnels":2}
    S→C  {"type":"attach","id":7}       这条隧道被一条 adb TCP 连接占用了
    C→S  {"type":"attached","id":7,"local_port":5555}   电视已经连上本机 adbd（诊断用）
    C→S / S→C  二进制帧 = 原始 adb 字节流
    C→S  {"type":"attach_failed","id":7,"reason":"..."} 电视连不上 127.0.0.1:5555
    C→S  {"type":"detach","id":7}       一端结束（随后连接会被关闭）

端口不够时后端不走这条 WebSocket，而是从设备控制通道
``app.state.hub.send_device(device_id, {"type":"tunnel_open"})`` 要求电视再开一条，
和现有的 ``preview_start`` / ``snapshot_request`` 是同一条路。

设备表里写 ``lan_ip='127.0.0.1'``、``adb_port=<分配端口>``，于是
``api/screen.py::_serial``、``api/commands.py``、``adb_keys.py`` 不用改就能拿到
``127.0.0.1:<port>``；最后一条隧道断开时恢复原来的 ``lan_ip``（后端重启时也会把
残留的 127.0.0.1 清掉，因为进程一重启隧道必然已经不存在）。
"""

from __future__ import annotations

import asyncio
import contextlib
import itertools
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from adb_keys import adb_path
from auth import verify_device_token


router = APIRouter()
# Uvicorn only installs handlers on its own loggers; without this a plain module logger would be
# silent at INFO and the "tunnel is up" line the operator looks for would never appear.
logger = logging.getLogger("adb-tunnel")
if not logger.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("[adb-tunnel] %(levelname)s %(message)s"))
    logger.addHandler(_handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False

TUNNEL_PORT_START = 15000
TUNNEL_PORT_ATTEMPTS = 1000
# 电视端维持两条空闲隧道：adb 通常只占用一条，另一条留给下一次连接/重连。
TUNNELS_PER_DEVICE = 2
# 隧道全被占用时，给 ``tunnel_open`` 留一点时间让电视补上，再放弃这次 adb 连接。
TUNNEL_WAIT_SECONDS = 2.0
# 设备断开后端口不立刻关：电视重连通常只要几秒，端口留着可以少一次 adb connect。
TUNNEL_CLOSE_GRACE_SECONDS = 3.0
TUNNEL_OPEN_MIN_INTERVAL_SECONDS = 2.0
# 后端自己跑 adb connect：公网部署时没有人在服务器上手工敲这条命令。
ADB_CONNECT_MIN_INTERVAL_SECONDS = 5.0
ADB_COMMAND_TIMEOUT_SECONDS = 8.0
# 一台设备最多同时连这么多条；正常只需要两条，留一点余量给 adb 的短暂并发。
MAX_TUNNELS_PER_DEVICE = 8
TCP_READ_BYTES = 64 * 1024

SOCKET_ERRORS = (RuntimeError, WebSocketDisconnect, ConnectionError, OSError)


@dataclass(eq=False)
class Tunnel:
    """一条电视连上来的 WebSocket，同一时刻最多服务一个 adb TCP 连接。"""

    device_id: str
    websocket: WebSocket
    connected_at: float = field(default_factory=time.time)
    stream: "Stream | None" = None


@dataclass
class TunnelDevice:
    """一台设备对应的本地监听端口和它的隧道集合。"""

    device_id: str
    device_pk: int
    port: int = 0
    original_lan_ip: str = ""
    server: asyncio.AbstractServer | None = None
    tunnels: set[Tunnel] = field(default_factory=set)
    idle: list[Tunnel] = field(default_factory=list)
    last_open_request: float = 0.0
    last_connect_attempt: float = 0.0
    close_task: asyncio.Task | None = None


class Stream:
    """一条 adb TCP 连接；二进制帧与 socket 之间的桥。"""

    __slots__ = ("id", "tunnel", "reader", "writer", "closed")

    def __init__(
        self,
        stream_id: int,
        tunnel: Tunnel,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        self.id = stream_id
        self.tunnel = tunnel
        self.reader = reader
        self.writer = writer
        self.closed = False


class AdbTunnelManager:
    """进程内的 device_id → 本地端口映射。单进程有效（多 worker 部署见 README 的说明）。"""

    def __init__(self, app: Any) -> None:
        self._app = app
        self._devices: dict[str, TunnelDevice] = {}
        self._lock = asyncio.Lock()
        self._stream_ids = itertools.count(1)

    # ------------------------------------------------------------------ 查询 / 健康检查
    def mapping(self) -> dict[str, int]:
        """给 /api/health 用：只暴露正在监听且属于已连接设备的端口。"""
        return {
            device_id: item.port
            for device_id, item in self._devices.items()
            if item.server is not None and item.port
        }

    def port_for(self, device_id: str) -> int:
        item = self._devices.get(device_id)
        return item.port if item is not None and item.server is not None else 0

    def idle_count(self, device_id: str) -> int:
        item = self._devices.get(device_id)
        return len(item.idle) if item is not None else 0

    # ------------------------------------------------------------------ 启动 / 停止
    async def clear_stale(self) -> None:
        """进程启动时没有任何隧道，表里残留的 127.0.0.1 一定是上次没来得及恢复的。"""
        try:
            self._db().execute(
                "UPDATE devices SET lan_ip = '', adb_port = 5555, updated_at = ? "
                "WHERE lan_ip = '127.0.0.1'",
                (int(time.time()),),
            )
        except Exception:  # 数据库还没准备好时不要让后端起不来
            logger.exception("adb tunnel: stale mapping cleanup failed")

    async def shutdown(self) -> None:
        devices = list(self._devices.values())
        self._devices.clear()
        for item in devices:
            if item.close_task is not None:
                item.close_task.cancel()
            for tunnel in list(item.tunnels):
                with contextlib.suppress(Exception):
                    await tunnel.websocket.close(code=1001, reason="server shutdown")
            if item.server is not None:
                item.server.close()
                with contextlib.suppress(Exception):
                    await item.server.wait_closed()
            self._restore_device_mapping(item)
            await self._drop_adb_transport(item.port)

    # ------------------------------------------------------------------ WebSocket 生命周期
    async def register(
        self, device: dict[str, Any], websocket: WebSocket
    ) -> tuple[TunnelDevice, Tunnel]:
        device_id = str(device["device_id"])
        async with self._lock:
            item = self._devices.get(device_id)
            if item is None:
                item = TunnelDevice(
                    device_id=device_id,
                    device_pk=int(device["id"]),
                )
                await self._open_listener(item)
                self._devices[device_id] = item
            if item.close_task is not None:
                item.close_task.cancel()
                item.close_task = None
            if len(item.tunnels) >= MAX_TUNNELS_PER_DEVICE:
                raise RuntimeError(f"too many adb tunnels for {device_id}")
            tunnel = Tunnel(device_id=device_id, websocket=websocket)
            item.tunnels.add(tunnel)
            item.idle.append(tunnel)
        logger.info(
            "adb tunnel: device %s connected (port=%s, idle=%s)",
            device_id,
            item.port,
            len(item.idle),
        )
        return item, tunnel

    async def unregister(self, tunnel: Tunnel) -> None:
        async with self._lock:
            item = self._devices.get(tunnel.device_id)
            if item is None:
                return
            item.tunnels.discard(tunnel)
            if tunnel in item.idle:
                item.idle.remove(tunnel)
            if item.tunnels:
                return
            if item.close_task is not None:
                item.close_task.cancel()
            item.close_task = asyncio.create_task(self._close_after_grace(item))
        # 还有隧道时如果一条空闲的都没有，说明 adb 把他们都占了，让电视再开一条。
        if item.tunnels and not item.idle:
            await self._request_more(item)

    async def _close_after_grace(self, item: TunnelDevice) -> None:
        try:
            await asyncio.sleep(TUNNEL_CLOSE_GRACE_SECONDS)
            # 端口回收和恢复设备表要在同一把锁里做完：电视可能刚好在这几秒里重连并
            # 建了新的监听端口，旧的清理任务不能把新写进去的 adb_port 改回 5555。
            async with self._lock:
                if item.tunnels:
                    return
                current = self._devices.get(item.device_id)
                if current is not None and current is not item:
                    return
                if current is item:
                    self._devices.pop(item.device_id, None)
                self._restore_device_mapping(item)
            if item.server is not None:
                item.server.close()
                with contextlib.suppress(Exception):
                    await item.server.wait_closed()
            await self._drop_adb_transport(item.port)
            logger.info("adb tunnel: device %s disconnected, port %s closed", item.device_id, item.port)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("adb tunnel: close device %s failed", item.device_id)

    # ------------------------------------------------------------------ 监听端口
    async def _open_listener(self, item: TunnelDevice) -> None:
        row = self._db().fetch_one(
            "SELECT id, lan_ip FROM devices WHERE device_id = ?", (item.device_id,)
        )
        original = str(row["lan_ip"] or "") if row is not None else ""
        if original == "127.0.0.1":
            original = ""
        item.original_lan_ip = original
        item.port, item.server = await self._bind_listener(item)
        now = int(time.time())
        self._db().execute(
            "UPDATE devices SET lan_ip = '127.0.0.1', adb_port = ?, updated_at = ? "
            "WHERE device_id = ?",
            (item.port, now, item.device_id),
        )
        logger.info(
            "adb tunnel: device %s listening on 127.0.0.1:%s (was %s)",
            item.device_id,
            item.port,
            original or "-",
        )

    async def _bind_listener(
        self, item: TunnelDevice
    ) -> tuple[int, asyncio.AbstractServer]:
        """从 15000 往上找一个没人用的端口；每台设备一个，互不复用。"""
        taken = {other.port for other in self._devices.values() if other.server is not None}
        last_error: OSError | None = None
        for offset in range(TUNNEL_PORT_ATTEMPTS):
            port = TUNNEL_PORT_START + offset
            if port in taken:
                continue
            try:
                server = await asyncio.start_server(
                    lambda reader, writer: self._on_tcp_client(item, reader, writer),
                    host="127.0.0.1",
                    port=port,
                    reuse_address=True,
                )
            except OSError as error:
                last_error = error
                continue
            return port, server
        raise OSError(
            f"no free adb tunnel port in {TUNNEL_PORT_START}.."
            f"{TUNNEL_PORT_START + TUNNEL_PORT_ATTEMPTS - 1}"
        ) from last_error

    def _restore_device_mapping(self, item: TunnelDevice) -> None:
        try:
            # 只有当前还是隧道写进去的 127.0.0.1 时才恢复，避免覆盖设备刚上报的地址。
            self._db().execute(
                "UPDATE devices SET lan_ip = ?, adb_port = 5555, updated_at = ? "
                "WHERE device_id = ? AND lan_ip = '127.0.0.1'",
                (item.original_lan_ip, int(time.time()), item.device_id),
            )
        except Exception:
            logger.exception("adb tunnel: restore mapping for %s failed", item.device_id)

    # ------------------------------------------------------------------ 本地 adb 连接
    async def _on_tcp_client(
        self, item: TunnelDevice, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        """adb 连上了本地端口：取一条空闲隧道，把 TCP 字节流与 WS 二进制帧对接。"""
        tunnel = await self._take_idle(item)
        if tunnel is None:
            logger.warning(
                "adb tunnel: device %s has no idle tunnel for a new adb connection", item.device_id
            )
            with contextlib.suppress(Exception):
                writer.close()
            return

        stream = Stream(next(self._stream_ids), tunnel, reader, writer)
        tunnel.stream = stream
        logger.info("adb tunnel: stream %s attached to device %s", stream.id, item.device_id)
        try:
            await tunnel.websocket.send_json({"type": "attach", "id": stream.id})
            while not stream.closed:
                data = await reader.read(TCP_READ_BYTES)
                if not data:
                    break
                await tunnel.websocket.send_bytes(data)
        except asyncio.CancelledError:
            raise
        except SOCKET_ERRORS:
            pass
        except Exception:
            logger.exception("adb tunnel: stream %s forward failed", stream.id)
        finally:
            await self._close_stream(stream)
            logger.info("adb tunnel: stream %s closed (device %s)", stream.id, item.device_id)

    async def _take_idle(self, item: TunnelDevice) -> Tunnel | None:
        deadline = time.monotonic() + TUNNEL_WAIT_SECONDS
        while True:
            async with self._lock:
                if item.tunnels and not item.idle:
                    pass  # 让电视补隧道，下面等下一轮
                elif item.idle:
                    return item.idle.pop(0)
            if time.monotonic() >= deadline:
                return None
            await self._request_more(item)
            await asyncio.sleep(0.2)

    async def _request_more(self, item: TunnelDevice) -> None:
        now = time.monotonic()
        if now - item.last_open_request < TUNNEL_OPEN_MIN_INTERVAL_SECONDS:
            return
        item.last_open_request = now
        try:
            await self._app.state.hub.send_device(item.device_id, {"type": "tunnel_open"})
        except Exception:
            logger.exception("adb tunnel: tunnel_open request for %s failed", item.device_id)

    async def ensure_adb_transport(self, item: TunnelDevice) -> None:
        """让本机 adb 连上隧道端口；没有这一步，``adb -s 127.0.0.1:<port>`` 会报 device not found。"""
        now = time.monotonic()
        if now - item.last_connect_attempt < ADB_CONNECT_MIN_INTERVAL_SECONDS:
            return
        item.last_connect_attempt = now
        adb = adb_path()
        if not adb:
            logger.warning("adb tunnel: adb not found on the server; tunnel up but adb cannot use it")
            return
        serial = f"127.0.0.1:{item.port}"
        await self._run_adb(adb, ["connect", serial], f"connect {serial}")

    async def _drop_adb_transport(self, port: int) -> None:
        adb = adb_path()
        if not adb or not port:
            return
        await self._run_adb(adb, ["disconnect", f"127.0.0.1:{port}"], f"disconnect 127.0.0.1:{port}")

    async def _run_adb(self, adb: str, arguments: list[str], label: str) -> None:
        try:
            process = await asyncio.create_subprocess_exec(
                adb,
                *arguments,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
            )
            output, _ = await asyncio.wait_for(
                process.communicate(), timeout=ADB_COMMAND_TIMEOUT_SECONDS
            )
            detail = (output or b"").decode("utf-8", "replace").strip().replace("\r", " ")
            logger.info("adb tunnel: adb %s -> %s", label, detail or f"exit {process.returncode}")
        except Exception as error:
            logger.warning("adb tunnel: adb %s failed: %s", label, error)

    async def _close_stream(self, stream: Stream) -> None:
        if stream.closed:
            return
        stream.closed = True
        with contextlib.suppress(Exception):
            stream.writer.close()
            await stream.writer.wait_closed()
        # 一条隧道只服务一条 adb 连接；关掉它，电视会按需补一条新的空闲隧道。
        with contextlib.suppress(Exception):
            await stream.tunnel.websocket.close(code=1000, reason="tunnel stream closed")
        stream.tunnel.stream = None
        await self.unregister(stream.tunnel)

    async def close_stream(self, tunnel: Tunnel) -> None:
        stream = tunnel.stream
        if stream is not None:
            await self._close_stream(stream)
        else:
            await self.unregister(tunnel)

    def _db(self) -> Any:
        return self._app.state.db


async def _device_from_auth(websocket: WebSocket) -> dict[str, Any] | None:
    """和 /ws/device 一样的校验：先收 auth 帧，再核对 token / 审核状态。"""
    try:
        auth_message = await asyncio.wait_for(websocket.receive_json(), timeout=10)
    except (asyncio.TimeoutError, json.JSONDecodeError, WebSocketDisconnect):
        return None
    if not isinstance(auth_message, dict) or auth_message.get("type") != "auth":
        return None
    device_id = str(auth_message.get("device_id", ""))
    token = str(auth_message.get("token", ""))
    if not device_id or not token:
        return None
    device = websocket.app.state.db.fetch_one(
        "SELECT * FROM devices WHERE device_id = ?", (device_id,)
    )
    if device is None or not verify_device_token(token, device["token_hash"]):
        return None
    if device["status"] != "approved" or not device["enabled"]:
        return None
    return device


@router.websocket("/ws/tunnel")
async def tunnel_websocket(websocket: WebSocket) -> None:
    await websocket.accept()
    manager: AdbTunnelManager = websocket.app.state.adb_tunnels
    device = await _device_from_auth(websocket)
    if device is None:
        with contextlib.suppress(Exception):
            await websocket.close(code=4401, reason="invalid device credentials")
        return

    device_id = str(device["device_id"])
    tunnel: Tunnel | None = None
    try:
        item, tunnel = await manager.register(device, websocket)
        await websocket.send_json(
            {
                "type": "tunnel_ready",
                "device_id": device_id,
                "port": item.port,
                "max_tunnels": TUNNELS_PER_DEVICE,
            }
        )
        # 隧道已经可用，让服务器上的 adb 立刻连上它；否则后续 adb -s 127.0.0.1:<port> 找不到设备。
        asyncio.create_task(manager.ensure_adb_transport(item))
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                raise WebSocketDisconnect(message.get("code", 1000))
            frame = message.get("bytes")
            if frame is not None:
                stream = tunnel.stream
                if stream is None or stream.closed:
                    # 空闲隧道上不该有数据；忽略，等 attach。
                    continue
                stream.writer.write(frame)
                await stream.writer.drain()
                continue
            text = message.get("text")
            if not isinstance(text, str):
                continue
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                continue
            kind = str(payload.get("type", ""))
            if kind == "attached":
                logger.info(
                    "adb tunnel: device %s attached stream %s (local_port=%s)",
                    device_id,
                    payload.get("id"),
                    payload.get("local_port"),
                )
            elif kind in {"attach_failed", "detach"}:
                logger.info("adb tunnel: device %s sent %s for stream %s", device_id, kind, payload.get("id"))
                if kind == "attach_failed":
                    await manager.close_stream(tunnel)
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    except SOCKET_ERRORS:
        pass
    except Exception:
        logger.exception("adb tunnel: device %s tunnel loop failed", device_id)
    finally:
        if tunnel is not None:
            await manager.close_stream(tunnel)
