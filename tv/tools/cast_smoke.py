#!/usr/bin/env python3
"""End-to-end smoke test for a TV running the merged Aerial Lite + 星投屏 agent.

It drives the real backend the TV is connected to and checks each capability through the same
paths a phone would use. Run it from a machine that can reach the backend:

    python3 tools/cast_smoke.py --backend http://127.0.0.1:18000 \
        --admin admin --password admin123 --tv-ip 192.168.1.20

Exit code 0 means every check passed. The TV-side effects it triggers: opens the Settings app,
presses BACK, takes one screenshot, and starts a P2P session (no key is sent over it).
"""

from __future__ import annotations

import argparse
import json
import sys
import time

import httpx


class Checker:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
        if not ok:
            self.failures.append(name)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default="http://127.0.0.1:18000")
    parser.add_argument("--admin", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--tv-ip", default="", help="TV LAN IP, for the DLNA check")
    args = parser.parse_args()

    check = Checker()
    http = httpx.Client(base_url=args.backend, timeout=15)

    login = http.post("/api/auth/login", json={"username": args.admin, "password": args.password})
    if login.status_code != 200:
        print("login failed:", login.status_code, login.text[:200])
        return 2
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    devices = http.get("/api/devices", headers=headers).json()
    online = [item for item in devices if item.get("online")]
    if not online:
        print("no online device — is the TV running the merged app?")
        return 2
    device = online[0]
    device_id = device["device_id"]
    print(f"device: {device_id}  model: {device.get('model')}  app: {device.get('app_version')}")

    def run_command(kind: str, payload: dict, timeout: float = 15.0) -> dict:
        created = http.post(
            f"/api/device/{device_id}/commands", headers=headers,
            json={"type": kind, "payload": payload},
        ).json()
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(1.5)
            rows = http.get(f"/api/device/{device_id}/commands", headers=headers).json()
            for row in rows:
                if row["id"] == created["id"]:
                    if row["status"] in {"completed", "failed"}:
                        return row
        return created

    opened = run_command("app", {"action": "launch", "package_name": "com.android.settings"})
    check.check("open an app", opened["status"] == "completed", opened["status"])

    back = run_command("key", {"key": "back"})
    check.check("remote BACK key", back["status"] == "completed", back["status"])

    snapshot = run_command("snapshot", {}, timeout=20.0)
    check.check("screenshot round trip", snapshot["status"] == "completed", snapshot["status"])

    session = http.post(
        f"/api/device/{device_id}/p2p/session", headers=headers, json={"mode": "p2p"}
    ).json()
    check.check(
        "P2P session negotiates UDP",
        session.get("selected_mode") == "udp_jpeg",
        f"{session.get('selected_mode')} / {session.get('selection_reason')}",
    )

    if args.tv_ip:
        try:
            dlna = httpx.get(f"http://{args.tv_ip}:8766/description.xml", timeout=8)
            body = dlna.text
            check.check(
                "DLNA description",
                dlna.status_code == 200 and "MediaRenderer" in body,
                f"HTTP {dlna.status_code}",
            )
        except Exception as error:  # noqa: BLE001 - report, do not crash
            check.check("DLNA description", False, type(error).__name__)

    health = http.get("/api/health").json()
    check.check("device online", health.get("connected_agents", 0) >= 1,
                f"agents={health.get('connected_agents')}")

    print()
    if check.failures:
        print("FAILED:", ", ".join(check.failures))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
