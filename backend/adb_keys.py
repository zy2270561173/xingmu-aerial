"""Server-side key injection over adb.

Why this exists: on the 4.x TVs the app itself cannot inject D-pad keys. `input keyevent` needs the
system-only INJECT_EVENTS permission, and adbd on these boxes only accepts hosts whose key is already
in /data/misc/adb/adb_keys (no pairing dialog for TCP, and writing that file needs root).

The server, however, already has an authorised adb connection to the TV, so the key is injected from
here and the TV app only reports the result. Nothing privileged runs on the TV.
"""

from __future__ import annotations

import os
import shutil
import subprocess

# Same key map the TV agent uses; kept here so the server can work without the agent answering.
KEYCODES = {
    "up": 19,
    "down": 20,
    "left": 21,
    "right": 22,
    "ok": 23,
    "center": 23,
    "enter": 23,
    "select": 23,
    "back": 4,
    "home": 3,
    "menu": 82,
    "recents": 187,
    "app_switch": 187,
    "volume_up": 24,
    "volume_down": 25,
    "volume_mute": 164,
    "mute": 164,
    "power": 26,
    "play_pause": 85,
    "media_play_pause": 85,
    "media_play": 126,
    "media_pause": 127,
    "media_stop": 86,
    "media_next": 87,
    "media_previous": 88,
    "media_rewind": 89,
    "media_fast_forward": 90,
}


def key_code(name: str) -> int:
    """Accepts the phone's names (DPAD_CENTER, KEYCODE_HOME) as well as the short ones."""
    key = (name or "").strip().lower()
    if key.startswith("keycode_"):
        key = key[len("keycode_") :]
    if key.startswith("dpad_"):
        key = key[len("dpad_") :]
    return KEYCODES.get(key, 0)


def adb_path() -> str:
    """adb 的位置：PATH 优先，其次 ANDROID_HOME/platform-tools（面板启动的环境常缺 PATH）。"""
    found = shutil.which("adb") or shutil.which("adb.exe")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    if sdk:
        for candidate in ("adb.exe", "adb"):
            path = os.path.join(sdk, "platform-tools", candidate)
            if os.path.isfile(path):
                return path
    return ""


def _python_adb():
    """没有 adb 二进制时用仓库自带的纯 Python ADB 客户端；不可用则返回 None。"""
    try:
        import pyadb_client

        return pyadb_client if pyadb_client.available() else None
    except Exception:
        return None


def inject_key(serial: str, name: str, timeout: float = 5.0) -> tuple[bool, str]:
    """Runs `adb -s <serial> shell input keyevent <code>`; returns (ok, detail)."""
    code = key_code(name)
    if code == 0:
        return False, f"unknown key: {name}"
    adb = adb_path()
    if not adb:
        client = _python_adb()
        if client is None:
            return False, "adb not found on the server (and pure-Python adb unavailable)"
        try:
            return client.keyevent(serial, code, timeout)
        except Exception as error:  # noqa: BLE001 - report instead of failing the command
            return False, f"pyadb: {type(error).__name__}: {error}"[:200]
    try:
        result = subprocess.run(
            [adb, "-s", serial, "shell", "input", "keyevent", str(code)],
            capture_output=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, f"{type(error).__name__}"
    output = (result.stdout or b"") + (result.stderr or b"")
    text = output.decode("utf-8", "replace").strip()
    if result.returncode != 0 or "Error" in text or "Exception" in text:
        return False, text[:200] or f"exit {result.returncode}"
    return True, f"injected {name} ({code})"


def capture_png(serial: str, timeout: float = 20.0) -> tuple[bool, bytes | str]:
    """Grabs one screenshot as PNG/JPEG over the same adb link; returns bytes on success."""
    adb = adb_path()
    if not adb:
        client = _python_adb()
        if client is None:
            return False, "adb not found on the server (and pure-Python adb unavailable)"
        try:
            data = client.screencap(serial, timeout)
        except Exception as error:  # noqa: BLE001 - report instead of failing the command
            return False, f"pyadb: {type(error).__name__}: {error}"[:200]
        return _image_or_error(data)
    try:
        result = subprocess.run(
            [adb, "-s", serial, "exec-out", "screencap", "-p"],
            capture_output=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, f"{type(error).__name__}"
    data = result.stdout or b""
    if result.returncode != 0 or not (
        data.startswith(b"\x89PNG") or data.startswith(b"\xff\xd8")
    ):
        text = (result.stderr or b"").decode("utf-8", "replace").strip()
        return False, text[:200] or f"exit {result.returncode}"
    return True, data


def _image_or_error(data: bytes) -> tuple[bool, bytes | str]:
    if len(data) < 512 or not (
        data.startswith(b"\x89PNG") or data.startswith(b"\xff\xd8")
    ):
        return False, "screencap returned no image"
    return True, data


def install_apk(serial: str, apk_path: str, timeout: float = 180.0) -> tuple[bool, str]:
    """Installs an APK the server already stores, using the same adb link the keys use.

    The TV cannot install through its own adbd (the app has no shell access to itself), and a
    system-installer intent would need a tap on the remote. The server's adb session has neither
    problem, so installation happens here and the TV only shows the result.
    """
    adb = adb_path()
    if not adb:
        client = _python_adb()
        if client is None:
            return False, "adb not found on the server (and pure-Python adb unavailable)"
        try:
            return client.install_apk(serial, apk_path, timeout)
        except Exception as error:  # noqa: BLE001 - report instead of failing the command
            return False, f"pyadb: {type(error).__name__}: {error}"[:200]
    try:
        result = subprocess.run(
            [adb, "-s", serial, "install", "-r", "-g", apk_path],
            capture_output=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, f"{type(error).__name__}"
    output = (result.stdout or b"") + (result.stderr or b"")
    text = output.decode("utf-8", "replace").strip()
    if result.returncode != 0 or "Failure" in text or "Error" in text:
        return False, text[-200:] or f"exit {result.returncode}"
    return True, text.splitlines()[-1][:120] if text else "installed"
