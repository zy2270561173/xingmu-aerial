"""屏幕镜像：把电视的截图变成一条连续的 MJPEG 流。

旧实现是"手机每隔一会儿要一张图"，每帧一次 HTTP + 一次 adb screencap，光往返就 300-600ms，
所以看起来一顿一顿。这里把两件事都改掉：

* 一条 HTTP 连接持续输出 multipart/x-mixed-replace，浏览器/手机用 <img> 直接渲染；
* 服务端循环执行 adb screencap，抓完就推，帧间只留 fps 间隔。

4.4 的 screencap -p 出来就是 JPEG（这台 M08 固件如此），所以不用解码重编码，直接原样推。
"""

from __future__ import annotations

import asyncio
import time
from typing import AsyncIterator

from fastapi import APIRouter, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, StreamingResponse

from adb_keys import adb_path
from dependencies import get_current_user, require_admin  # noqa: F401

router = APIRouter(prefix="/api/device", tags=["screen"])
page_router = APIRouter(tags=["screen"])

BOUNDARY = "xingmuframe"


def _serial(device: dict) -> str:
    host = str(device.get("lan_ip") or "").strip()
    port = int(device.get("adb_port") or 5555)
    if not host:
        raise HTTPException(status.HTTP_409_CONFLICT, "设备还没有上报局域网地址，无法截图")
    return f"{host}:{port}"


async def _grab(serial: str, timeout: float) -> bytes | None:
    """抓一帧。优先 exec-out；老固件（如这台 4.4）会在 exec-out 上直接 closed，
    退回"写到 sdcard 再 pull"的稳妥路径。"""
    adb = adb_path()
    if not adb:
        return None
    frame = await _grab_exec_out(adb, serial, timeout)
    if frame:
        return frame
    return await _grab_via_file(adb, serial, timeout)


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


async def _grab_exec_out(adb: str, serial: str, timeout: float) -> bytes | None:
    code, out = await _run([adb, "-s", serial, "exec-out", "screencap", "-p"], timeout)
    return out if code == 0 and _looks_like_image(out) else None


async def _grab_via_file(adb: str, serial: str, timeout: float) -> bytes | None:
    remote = "/sdcard/.xingmu_frame.jpg"
    import tempfile
    from pathlib import Path

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
        Path(local).unlink(missing_ok=True)
        await _run([adb, "-s", serial, "shell", "rm", "-f", remote], 3.0)


async def _frames(serial: str, fps: int) -> AsyncIterator[bytes]:
    interval = 1.0 / max(1, min(fps, 15))
    while True:
        started = time.monotonic()
        frame = await _grab(serial, timeout=5.0)
        if frame is None:
            await asyncio.sleep(0.5)
            continue
        content_type = "image/jpeg" if frame.startswith(b"\xff\xd8") else "image/png"
        yield (
            f"--{BOUNDARY}\r\n"
            f"Content-Type: {content_type}\r\n"
            f"Content-Length: {len(frame)}\r\n\r\n"
        ).encode() + frame + b"\r\n"
        spent = time.monotonic() - started
        if spent < interval:
            await asyncio.sleep(interval - spent)


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
    view.src = '/api/device/' + encodeURIComponent(deviceId) + '/screen.mjpeg?fps=8&token=' + encodeURIComponent(token);
    hint.textContent = '正在接收画面…';
  }}
  document.getElementById('go').addEventListener('click', start);
  if (input.value) start();
</script>"""
    )
