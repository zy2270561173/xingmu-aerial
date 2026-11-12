"""屏幕镜像：把电视的截图变成一条连续的 MJPEG 流。

旧实现是"手机每隔一会儿要一张图"，每帧一次 HTTP + 一次 adb screencap，光往返就 300-600ms，
所以看起来一顿一顿。这里把三件事都改掉：

* 一条 HTTP 连接持续输出 multipart/x-mixed-replace，浏览器/手机用 <img> 直接渲染；
* 4.4 上不再"每帧起一个 adb 进程 + pull 文件"，而是在电视里跑一条持久管线
  `screencap -p 文件 && cat 文件`，通过 Unix socket + `adb forward` 直接送给后端。
  这样省掉了每帧的 pull/rm 往返，M08（Android 4.4）实测从 1.6fps 提到约 4-6fps；
* 仍保留老的 file/pull 路径作为兜底：没有 nc、没有 localfilesystem forward 的电视照常工作。

4.4 的 screencap -p 出来就是 JPEG（这台 M08 固件如此），所以不用解码重编码，直接原样推。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import socket
import subprocess
import tempfile
import time
from pathlib import Path
from typing import AsyncIterator

from fastapi import APIRouter, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, StreamingResponse

from adb_keys import adb_path
from dependencies import get_current_user, require_admin  # noqa: F401

router = APIRouter(prefix="/api/device", tags=["screen"])
page_router = APIRouter(tags=["screen"])

logger = logging.getLogger(__name__)

BOUNDARY = "xingmuframe"
# 一条持久管线已经能把 M08 的 CPU 吃到 25% 左右；多个观众同时开 fast path 会把电视拖到卡顿。
# 只给一个 fast slot，其余观众走老的慢速兜底，保证每个人至少能看。
_FAST_SLOTS = asyncio.Semaphore(1)
# 最近一次拿不到画面的原因；503 时带在 detail 里，省得只看一句“访问不到 adb”。
_last_adb_error = ""


def _serial(device: dict) -> str:
    host = str(device.get("lan_ip") or "").strip()
    port = int(device.get("adb_port") or 5555)
    if not host:
        raise HTTPException(status.HTTP_409_CONFLICT, "设备还没有上报局域网地址，无法截图")
    return f"{host}:{port}"


async def _run(cmd: list[str], timeout: float) -> tuple[int, bytes]:
    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        out, _ = await asyncio.wait_for(process.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        process.kill()
        return -1, b""
    return process.returncode or 0, out or b""


def _looks_like_image(data: bytes) -> bool:
    return len(data) > 512 and (data.startswith(b"\xff\xd8") or data.startswith(b"\x89PNG"))


def _part(frame: bytes) -> bytes:
    content_type = "image/jpeg" if frame.startswith(b"\xff\xd8") else "image/png"
    return (
        f"--{BOUNDARY}\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(frame)}\r\n\r\n"
    ).encode() + frame + b"\r\n"


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _extract_frame(buffer: bytearray) -> tuple[bytes, int] | None:
    """从流缓冲里切出一帧。M08 的 screencap 输出 JPEG，别的固件可能是 PNG，两种都认。"""
    if len(buffer) < 8:
        return None
    jpeg = buffer.find(b"\xff\xd8\xff")
    png = buffer.find(b"\x89PNG\r\n\x1a\n")
    if jpeg < 0 and png < 0:
        if len(buffer) > 1_000_000:
            del buffer[: len(buffer) - 8]
        return None

    if jpeg >= 0 and (png < 0 or jpeg < png):
        if jpeg:
            del buffer[:jpeg]
        end = buffer.find(b"\xff\xd9", 2)
        if end < 0:
            # 一帧 JPEG 不应该超过几 MB；超过还找不到 EOI 说明流里是垃圾，保留尾部重新同步。
            if len(buffer) > 8_000_000:
                del buffer[: len(buffer) - 8]
            return None
        size = end + 2
        return bytes(buffer[:size]), size

    if png:
        del buffer[:png]
    pos = 8
    while pos + 12 <= len(buffer):
        length = int.from_bytes(buffer[pos:pos + 4], "big")
        chunk_type = bytes(buffer[pos + 4:pos + 8])
        end = pos + 12 + length
        if end > len(buffer):
            return None
        pos = end
        if chunk_type == b"IEND":
            return bytes(buffer[:pos]), pos
    if len(buffer) > 8_000_000:
        del buffer[: len(buffer) - 8]
    return None


async def _fast_frames(serial: str, adb: str) -> AsyncIterator[bytes]:
    """4.4 上最快的一条路：电视侧持久 screencap 循环 → Unix socket → adb forward → 后端。

    电视只负责按自己的速度抓屏（M08 约 4-6fps），后端不参与 pull；这比每帧起 adb 进程快 2-3 倍。
    """
    port = _free_port()
    sock_path = f"/data/local/tmp/xingmu-{port}.sock"
    frame_path = f"/data/local/tmp/xingmu-{port}.jpg"
    shell_cmd = (
        'NC=$(command -v nc 2>/dev/null); [ -x "$NC" ] || NC=/system/xbin/nc; '
        '[ -x "$NC" ] || exit 3; '
        f"rm -f {frame_path} {sock_path}; "
        f"while true; do screencap -p {frame_path} >/dev/null 2>&1 && cat {frame_path} || break; done "
        f'| "$NC" -lU {sock_path}'
    )
    process: asyncio.subprocess.Process | None = None
    writer: asyncio.StreamWriter | None = None
    try:
        code, _ = await _run(
            [adb, "-s", serial, "forward", f"tcp:{port}", f"localfilesystem:{sock_path}"],
            5.0,
        )
        if code != 0:
            return
        process = await asyncio.create_subprocess_exec(
            adb, "-s", serial, "shell", shell_cmd,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        # 注意：adb forward 会在电视侧 nc 还没 bind 时就接受 TCP 连接，然后立刻 EOF。
        # 所以不能只重试 connect，必须探到第一段数据才算真正连上。
        reader: asyncio.StreamReader | None = None
        initial = b""
        for _ in range(15):
            candidate = candidate_writer = None
            try:
                candidate, candidate_writer = await asyncio.wait_for(
                    asyncio.open_connection("127.0.0.1", port), timeout=0.4
                )
            except (OSError, asyncio.TimeoutError):
                await asyncio.sleep(0.15)
                continue
            try:
                probe = await asyncio.wait_for(candidate.read(4096), timeout=0.6)
            except asyncio.TimeoutError:
                # 连接已经建立、只是首帧还没到；交给主读取循环等，不要误判成失败。
                reader, writer, initial = candidate, candidate_writer, b""
                break
            except OSError:
                with contextlib.suppress(Exception):
                    candidate_writer.close()
                await asyncio.sleep(0.15)
                continue
            if probe:
                reader, writer, initial = candidate, candidate_writer, probe
                break
            with contextlib.suppress(Exception):
                candidate_writer.close()
            await asyncio.sleep(0.15)
        if reader is None:
            return

        buffer = bytearray(initial)
        while True:
            try:
                chunk = await asyncio.wait_for(reader.read(65536), timeout=10.0)
            except asyncio.TimeoutError:
                return
            if not chunk:
                return
            buffer.extend(chunk)
            while True:
                parsed = _extract_frame(buffer)
                if parsed is None:
                    break
                frame, size = parsed
                del buffer[:size]
                yield frame
    finally:
        if writer is not None:
            with contextlib.suppress(Exception):
                writer.close()
        if process is not None:
            with contextlib.suppress(Exception):
                process.kill()
        # 这里用同步清理：FastAPI 在手机断开时会 cancel 这个生成器，异步 await 可能被立刻取消，
        # 导致电视侧的 screencap/nc 进程残留。同步调用能保证清理一定执行。
        with contextlib.suppress(Exception):
            subprocess.run(
                [adb, "-s", serial, "forward", "--remove", f"tcp:{port}"],
                timeout=3, capture_output=True,
            )
        with contextlib.suppress(Exception):
            subprocess.run(
                [adb, "-s", serial, "shell", "rm", "-f", frame_path, sock_path],
                timeout=3, capture_output=True,
            )


async def _grab(serial: str, timeout: float) -> bytes | None:
    """慢速兜底：优先 exec-out；老固件（如这台 4.4）会在 exec-out 上直接 closed，
    退回"写到 data/local/tmp 再 pull"的稳妥路径。没有 adb 二进制时走纯 Python 客户端。"""
    global _last_adb_error
    adb = adb_path()
    if not adb:
        return await _grab_python(serial, timeout)
    frame = await _grab_exec_out(adb, serial, timeout)
    if frame:
        _last_adb_error = ""
        return frame
    frame = await _grab_via_file(adb, serial, timeout)
    if frame:
        _last_adb_error = ""
        return frame
    _last_adb_error = f"adb 命令拿不到画面（serial={serial}）"
    return None


async def _grab_python(serial: str, timeout: float) -> bytes | None:
    """无 adb 命令时的截图：纯 Python ADB 客户端，连接按序列号复用。"""
    global _last_adb_error
    try:
        import pyadb_client
    except Exception as error:  # noqa: BLE001 - report why the fallback is unavailable
        _last_adb_error = f"pyadb_client 无法导入：{type(error).__name__}: {error}"
        return None
    if not pyadb_client.available():
        _last_adb_error = "pyadb 不可用（vendor/adb_shell、rsa、pyasn1 缺失？）"
        return None
    try:
        data = await asyncio.to_thread(pyadb_client.screencap, serial, timeout)
    except Exception as error:  # 无 adb 的服务器上这里失败就是 503 的原因，记一条就够
        _last_adb_error = f"pyadb: {type(error).__name__}: {error}"
        logger.debug("pyadb screencap failed for %s: %s", serial, error)
        return None
    if not _looks_like_image(data):
        _last_adb_error = "pyadb 拿到的不是图片"
        return None
    _last_adb_error = ""
    return data


async def _grab_exec_out(adb: str, serial: str, timeout: float) -> bytes | None:
    code, out = await _run([adb, "-s", serial, "exec-out", "screencap", "-p"], timeout)
    return out if code == 0 and _looks_like_image(out) else None


async def _grab_via_file(adb: str, serial: str, timeout: float) -> bytes | None:
    # /data/local/tmp 比 /sdcard（FUSE）pull 快很多；M08 上实测 pull 0.8s → 0.24s。
    remote = "/data/local/tmp/.xingmu_frame.jpg"

    code, _ = await _run([adb, "-s", serial, "shell", "screencap", "-p", remote], timeout)
    if code != 0:
        return None
    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as handle:
        local = handle.name
    try:
        code, _ = await _run([adb, "-s", serial, "pull", remote, local], timeout)
        data = Path(local).read_bytes() if code == 0 else b""
        return data if _looks_like_image(data) else None
    finally:
        # Windows 上 adb pull 的句柄可能还没释放，unlink 会抛 WinError 32；
        # 清理失败不该把整个流打断，忽略即可。
        try:
            Path(local).unlink(missing_ok=True)
        except OSError:
            pass
        await _run([adb, "-s", serial, "shell", "rm", "-f", remote], 3.0)


async def _slow_frames(serial: str, fps: int) -> AsyncIterator[bytes]:
    interval = 1.0 / max(1, min(fps, 15))
    while True:
        started = time.monotonic()
        frame = await _grab(serial, timeout=5.0)
        if frame is None:
            await asyncio.sleep(0.5)
            continue
        yield _part(frame)
        spent = time.monotonic() - started
        if spent < interval:
            await asyncio.sleep(interval - spent)


async def _frames(serial: str, fps: int) -> AsyncIterator[bytes]:
    interval = 1.0 / max(1, min(fps, 15))
    adb = adb_path()
    fast_acquired = False
    if adb:
        try:
            await asyncio.wait_for(_FAST_SLOTS.acquire(), timeout=0.2)
            fast_acquired = True
        except asyncio.TimeoutError:
            fast_acquired = False

    if fast_acquired:
        try:
            async for frame in _fast_frames(serial, adb):
                started = time.monotonic()
                yield _part(frame)
                spent = time.monotonic() - started
                if spent < interval:
                    await asyncio.sleep(interval - spent)
        except Exception as error:  # 电视侧管线异常时退回老路径，不把整条流打断
            logger.debug("screen fast path ended: %s", error)
        finally:
            _FAST_SLOTS.release()

    async for part in _slow_frames(serial, fps):
        yield part


@router.get("/{device_id}/screen.mjpeg")
async def screen_mjpeg(
    device_id: str,
    request: Request,
    fps: int = Query(default=8, ge=1, le=15),
    token: str = Query(default=""),
) -> StreamingResponse:
    """浏览器里 <img src=".../screen.mjpeg?token=JWT"> 就能直接看画面。"""
    if not token:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "缺少 token")
    # 复用后台的 JWT：token 从?token= 传，因为 <img> 不能带 Authorization 头。
    request.scope.setdefault("headers", [])
    user = await _user_from_token(request, token)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "token 无效或已过期")
    device = request.app.state.db.fetch_one("SELECT * FROM devices WHERE device_id = ?", (device_id,))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "设备不存在")
    serial = _serial(device)
    # 先试一帧：服务器连不到电视 adb（公网机房与家庭内网）时，直接给 503 + 明确原因，
    # 而不是开一条永远没有帧的流让前端以为"画面中断"。
    first = await _grab(serial, timeout=4.0)
    if first is None:
        detail = "服务器无法访问电视的 adb（通常是后端与电视不在同一网络，需内网隧道或端口映射）"
        if _last_adb_error:
            detail += f"；最近一次失败：{_last_adb_error[:200]}"
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail)
    return StreamingResponse(
        _frames(serial, fps),
        media_type=f"multipart/x-mixed-replace; boundary={BOUNDARY}",
        headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
    )


async def _user_from_token(request: Request, token: str) -> dict | None:
    """与 /api/auth/me 同一套 JWT 校验，避免这里另写一份规则。"""
    from auth import decode_access_token  # 延迟导入，保持模块加载轻

    try:
        payload = decode_access_token(request.app.state.settings, token)
    except Exception:
        return None
    user_id = payload.get("sub")
    if not user_id:
        return None
    row = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (int(user_id),))
    return dict(row) if row else None


@page_router.get("/screen/{device_id}", response_class=HTMLResponse, include_in_schema=False)
async def screen_page(device_id: str) -> HTMLResponse:
    """一个最小的观看页：登录后把 JWT 塞进 localStorage，再用它拼 MJPEG 地址。"""
    return HTMLResponse(
        f"""<!doctype html><meta charset="utf-8"><title>星幕 · 屏幕镜像</title>
<style>
  html,body{{margin:0;height:100%;background:#0b0d0c;color:#eaf3f2;
    font:14px/1.6 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif}}
  .bar{{display:flex;gap:12px;align-items:center;padding:12px 16px;
    background:rgba(8,16,22,.82);border-bottom:1px solid rgba(255,255,255,.12)}}
  img{{display:block;max-width:100%;margin:0 auto;background:#000}}
  input,button{{padding:8px 10px;border-radius:10px;border:1px solid rgba(255,255,255,.16);
    background:rgba(255,255,255,.08);color:#eaf3f2;font-size:14px}}
  button{{background:linear-gradient(135deg,#3fd0c9,#2f80ed);color:#04231f;font-weight:700;border:0}}
</style>
<div class="bar">
  <strong>星幕</strong><span id="hint">先填入后台管理员 token</span>
  <input id="tk" placeholder="JWT（后台 localStorage: starlight_console_token）" style="flex:1;min-width:0">
  <button id="go">开始</button>
</div>
<img id="v" alt="等待画面">
<script>
  const deviceId = {device_id!r}.replace(/^['"]|['"]$/g, '');
  const input = document.getElementById('tk');
  const view = document.getElementById('v');
  const hint = document.getElementById('hint');
  function rawToken(value) {{
    try {{ const parsed = JSON.parse(value); return typeof parsed === 'string' ? parsed : (parsed.token || parsed.access_token || ''); }}
    catch {{ return value; }}
  }}
  const saved = localStorage.getItem('starlight_console_token');
  if (saved) input.value = rawToken(saved);
  function start() {{
    const token = rawToken(input.value.trim());
    if (!token) {{ hint.textContent = '需要 token'; return; }}
    localStorage.setItem('starlight_console_token', JSON.stringify(token));
    view.src = '/api/device/' + encodeURIComponent(deviceId) + '/screen.mjpeg?fps=15&token=' + encodeURIComponent(token);
    hint.textContent = '正在接收画面…';
  }}
  document.getElementById('go').addEventListener('click', start);
  if (input.value) start();
</script>"""
    )
