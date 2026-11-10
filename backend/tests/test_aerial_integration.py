"""The console's Aerial entry: it asks the TV agent to hand the HOME role to Aerial.

The agent side (AgentService.executeApp "set_home") is Android code and cannot run here; this pins
the API contract the console relies on, so a payload-shape regression fails fast.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from test_workflow import DEVICE, register


def _approve(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = client.post(
        "/api/approvals", headers=auth_headers, json={"device_id": DEVICE["device_id"], "approve": True}
    )
    assert response.status_code == 200


def test_console_can_ask_the_tv_to_become_aerial_home(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    _approve(client, auth_headers)

    payload = {
        "action": "set_home",
        "package_name": "app.orchard.tv.lite",
        "activity_name": ".HomeActivity",
    }
    response = client.post(
        f"/api/device/{DEVICE['device_id']}/commands",
        headers=auth_headers,
        json={"type": "app", "payload": payload},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["type"] == "app"
    assert body["payload"]["action"] == "set_home"
    assert body["payload"]["package_name"] == "app.orchard.tv.lite"
