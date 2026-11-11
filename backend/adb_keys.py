"""Server-side key injection over adb.

Why this exists: on the 4.x TVs the app itself cannot inject D-pad keys. `input keyevent` needs the
system-only INJECT_EVENTS permission, and adbd on these boxes only accepts hosts whose key is already
in /data/misc/adb/adb_keys (no pairing dialog for TCP, and writing that file needs root).

The server, however, already has an authorised adb connection to the TV, so the key is injected from
here and the TV app only reports the result. Nothing privileged runs on the TV.
"""

from __future__ import annotations

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
    return shutil.which("adb") or ""


def inject_key(serial: str, name: str, timeout: float = 5.0) -> tuple[bool, str]:
    """Runs `adb -s <serial> shell input keyevent <code>`; returns (ok, detail)."""
    code = key_code(name)
    if code == 0:
        return False, f"unknown key: {name}"
    adb = adb_path()
    if not adb:
        return False, "adb not found on the server"
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
    """Grabs one screenshot as PNG over the same adb link; returns bytes on success."""
    adb = adb_path()
    if not adb:
        return False, "adb not found on the server"
    try:
        result = subprocess.run(
            [adb, "-s", serial, "exec-out", "screencap", "-p"],
            capture_output=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return False, f"{type(error).__name__}"
    data = result.stdout or b""
    if result.returncode != 0 or len(data) < 8 or not data.startswith(b"\x89PNG"):
        text = (result.stderr or b"").decode("utf-8", "replace").strip()
        return False, text[:200] or f"exit {result.returncode}"
    return True, data


def install_apk(serial: str, apk_path: str, timeout: float = 180.0) -> tuple[bool, str]:
    """Installs an APK the server already stores, using the same adb link the keys use.

    The TV cannot install through its own adbd (the app has no shell access to itself), and a
    system-installer intent would need a tap on the remote. The server's adb session has neither
    problem, so installation happens here and the TV only shows the result.
    """
    adb = adb_path()
    if not adb:
        return False, "adb not found on the server"
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
