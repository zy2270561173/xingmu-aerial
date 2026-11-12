"""纯 Python 的 ADB 客户端，给没有 adb 二进制的服务器兜底。

后端的按键、安装和截图一直依赖服务器上的 ``adb`` 命令。公网 Docker 镜像里没有 adb，
只靠反向隧道也跑不了 ``adb connect``。这里用一份纯 Python 的 ADB 协议实现
（``vendor/adb_shell``，Apache-2.0，见该目录的 LICENSE）直接说 ADB 协议：

* 隧道模式下连 ``127.0.0.1:<adb_port>``，和 adb server 连的是同一个端口；
* 局域网模式下连电视上报的 ``lan_ip:5555``，行为和 adb 命令一致；
* 不需要 adb 二进制、不需要 adb server，也不占用 5037 端口。

只实现后端真正用到的四件事：shell、截屏、按键、安装。连接按序列号复用；
adb 连接是阻塞的，调用方在异步代码里要用 ``asyncio.to_thread`` 包一层。
"""

from __future__ import annotations

import atexit
import logging
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

# 仓库里自带一份 adb_shell（0.4.4），不走 pip，避免 Docker 的离线 wheelhouse 里
# 还要为它准备 sdist/构建依赖。rsa + pyasn1 是纯 Python 小包，requirements 里单独列。
_VENDOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")
if _VENDOR not in sys.path:
    sys.path.insert(0, _VENDOR)

try:  # pragma: no cover - import availability is the feature switch
    from adb_shell.adb_device import AdbDeviceTcp
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner
except Exception:  # noqa: BLE001 - a missing optional dependency must not break the backend
    AdbDeviceTcp = None  # type: ignore[assignment]
    PythonRSASigner = None  # type: ignore[assignment]

logger = logging.getLogger("pyadb")

_CONNECT_TIMEOUT_SECONDS = 6.0
_AUTH_TIMEOUT_SECONDS = 8.0
_READ_TIMEOUT_SECONDS = 15.0
_MAX_CACHED_DEVICES = 4
_REMOTE_SCREENSHOT = "/data/local/tmp/.xingmu_py_frame.jpg"
_REMOTE_APK = "/data/local/tmp/.xingmu_py_install.apk"

_map_lock = threading.RLock()
_signer_lock = threading.Lock()
_entries: dict[str, "_Entry"] = {}
_signer = None
_signer_error = ""


class _Entry:
    __slots__ = ("device", "lock", "last_used")

    def __init__(self) -> None:
        self.device = None
        self.lock = threading.Lock()
        self.last_used = 0.0


def available() -> bool:
    """True when the vendored ADB client can be imported (dependency-wise)."""
    return AdbDeviceTcp is not None and PythonRSASigner is not None


def adb_key_path() -> str:
    configured = os.environ.get("TVCAST_ADBKEY", "").strip()
    if configured:
        return configured
    return str(Path.home() / ".android" / "adbkey")


def _load_signer():
    """Loads ~/.android/adbkey once; same key file the adb command line would use."""
    global _signer, _signer_error
    if _signer is not None:
        return _signer
    with _signer_lock:
        if _signer is not None:
            return _signer
        path = adb_key_path()
        if not os.path.isfile(path):
            _signer_error = (
                f"adb key not found: {path} (copy ~/.android/adbkey and adbkey.pub, "
                "or set TVCAST_ADBKEY)"
            )
            raise RuntimeError(_signer_error)
        if not os.path.isfile(path + ".pub"):
            _signer_error = f"adb public key not found: {path}.pub"
            raise RuntimeError(_signer_error)
        _signer = PythonRSASigner.FromRSAKeyPath(path)
        return _signer


def _split_serial(serial: str) -> tuple[str, int]:
    host, _, port_text = serial.rpartition(":")
    if not host:
        return serial, 5555
    try:
        port = int(port_text)
    except ValueError:
        return serial, 5555
    return host, port


def _connect(serial: str):
    if not available():
        raise RuntimeError("adb_shell is not importable")
    signer = _load_signer()
    host, port = _split_serial(serial)
    device = AdbDeviceTcp(
        host, port, default_transport_timeout_s=_CONNECT_TIMEOUT_SECONDS
    )
    device.connect(
        rsa_keys=[signer],
        auth_timeout_s=_AUTH_TIMEOUT_SECONDS,
        read_timeout_s=_READ_TIMEOUT_SECONDS,
    )
    logger.info("pyadb: connected to %s", serial)
    return device


def _entry(serial: str) -> _Entry:
    with _map_lock:
        entry = _entries.get(serial)
        if entry is not None:
            return entry
        if len(_entries) >= _MAX_CACHED_DEVICES:
            # 端口会随电视重连变化，旧序列号不能无限留着；优先清掉没在用的连接。
            for key, candidate in list(_entries.items()):
                if not candidate.lock.acquire(blocking=False):
                    continue
                try:
                    _entries.pop(key, None)
                    _close(candidate)
                finally:
                    candidate.lock.release()
                break
        entry = _Entry()
        _entries[serial] = entry
        return entry


def _close(entry: _Entry) -> None:
    device = entry.device
    entry.device = None
    if device is not None:
        try:
            device.close()
        except Exception:  # noqa: BLE001 - closing a dead socket is best effort
            pass


def _run(serial: str, operation, timeout: float | None = None):
    entry = _entry(serial)
    with entry.lock:
        try:
            if entry.device is None:
                entry.device = _connect(serial)
            result = operation(entry.device)
            entry.last_used = time.monotonic()
            return result
        except Exception as error:
            logger.warning("pyadb: %s operation failed: %s: %s", serial, type(error).__name__, error)
            _close(entry)
            raise


def shell(serial: str, command: str, timeout: float = 10.0) -> str:
    """Runs one shell command and returns its decoded stdout."""
    def operation(device):
        return device.shell(
            command,
            timeout_s=timeout,
            read_timeout_s=max(_READ_TIMEOUT_SECONDS, timeout),
            decode=True,
        )

    output = _run(serial, operation)
    return output or ""


def keyevent(serial: str, code: int, timeout: float = 5.0) -> tuple[bool, str]:
    try:
        shell(serial, f"input keyevent {int(code)}", timeout)
    except Exception as error:  # noqa: BLE001 - report the reason to the command result
        return False, f"pyadb: {type(error).__name__}: {error}"[:200]
    return True, f"injected ({int(code)})"


def screencap(serial: str, timeout: float = 20.0) -> bytes:
    """`screencap -p` + `pull` through one persistent ADB connection.

    The 4.x adbd (this M08 included) does not support exec-out, so the file path is the
    reliable one. The screen command itself takes ~0.25 s on the M08, comparable to the
    adb CLI fallback, and keeping the connection open removes the per-frame handshake.
    """
    def operation(device):
        device.shell(
            f"screencap -p {_REMOTE_SCREENSHOT}",
            timeout_s=timeout,
            read_timeout_s=max(_READ_TIMEOUT_SECONDS, timeout),
        )
        descriptor, local = tempfile.mkstemp(suffix=".img")
        os.close(descriptor)
        try:
            device.pull(
                _REMOTE_SCREENSHOT,
                local,
                transport_timeout_s=_CONNECT_TIMEOUT_SECONDS,
                read_timeout_s=max(_READ_TIMEOUT_SECONDS, timeout),
            )
            return Path(local).read_bytes()
        finally:
            try:
                os.unlink(local)
            except OSError:
                pass
            try:
                device.shell(f"rm -f {_REMOTE_SCREENSHOT}", timeout_s=3.0)
            except Exception:  # noqa: BLE001 - cleanup must not hide the screenshot
                pass

    return _run(serial, operation)


def install_apk(
    serial: str, apk_path: str, timeout: float = 180.0
) -> tuple[bool, str]:
    """Pushes an APK and runs `pm install -r` (the adb CLI's install without -g)."""
    def operation(device):
        device.push(
            apk_path,
            _REMOTE_APK,
            transport_timeout_s=_CONNECT_TIMEOUT_SECONDS,
            read_timeout_s=max(_READ_TIMEOUT_SECONDS, timeout),
        )
        try:
            output = device.shell(
                f"pm install -r {_REMOTE_APK}",
                timeout_s=timeout,
                read_timeout_s=max(_READ_TIMEOUT_SECONDS, timeout),
                decode=True,
            )
        finally:
            try:
                device.shell(f"rm -f {_REMOTE_APK}", timeout_s=5.0)
            except Exception:  # noqa: BLE001 - cleanup must not hide the install result
                pass
        text = str(output or "").strip()
        ok = "Success" in text or "success" in text
        return ok, text[-200:] or ("installed" if ok else "pm install failed")

    return _run(serial, operation)


def close_all() -> None:
    with _map_lock:
        entries = list(_entries.values())
        _entries.clear()
    for entry in entries:
        with entry.lock:
            _close(entry)


atexit.register(close_all)
