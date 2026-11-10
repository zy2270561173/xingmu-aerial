"""Regression tests for the high-severity findings in reports/backend-audit.md.

Each test pins one behaviour that was exploitable before: a stale pairing code taking over an
approved TV, the pairing page reflecting the code into an inline script, and the P2P signal
relay skipping the paid-transport gate that /p2p/session enforces.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from test_workflow import DEVICE, create_regular_user, register


def _approve(client: TestClient, auth_headers: dict[str, str], owner_id: int | None = None) -> None:
    payload: dict[str, object] = {"device_id": DEVICE["device_id"], "approve": True}
    if owner_id is not None:
        payload["owner_user_id"] = owner_id
    response = client.post("/api/approvals", headers=auth_headers, json=payload)
    assert response.status_code == 200


def test_stale_pairing_code_cannot_take_over_approved_device(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    device = register(client)
    issued = client.post(
        "/api/pairing/device",
        json={"device_id": DEVICE["device_id"], "device_token": device["device_token"]},
    )
    assert issued.status_code == 200
    code = issued.json()["app_pairing"]["code"]

    owner, _owner_headers = create_regular_user(client, auth_headers, "owner_a")
    _intruder, intruder_headers = create_regular_user(client, auth_headers, "intruder_b")
    _approve(client, auth_headers, owner["id"])

    claim = client.post(f"/api/pairing/{code}/claim", headers=intruder_headers)
    assert claim.status_code == 409
    assert "already paired" in claim.json()["detail"]

    # The real owner is untouched: the takeover attempt did not change the device row.
    devices = client.get("/api/devices", headers=intruder_headers)
    assert devices.status_code == 200
    assert devices.json() == []


def test_pairing_page_does_not_reflect_code_into_script(client: TestClient) -> None:
    # Called directly: an encoded "%2F" is decoded by Starlette before routing, so the HTTP form
    # of this path never reaches the handler with a slash inside the code.
    from api.pairing import pairing_page

    payload = "</script><script>alert(1)//"
    html = pairing_page(payload).body.decode("utf-8")
    # The literal sequence would break out of the inline script and run attacker markup.
    assert "</script><script>alert(1)" not in html
    # Only '<' needs escaping to keep the payload inside the string literal.
    assert "\\u003c/script>" in html


def test_p2p_signal_requires_paid_transport(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    user, user_headers = create_regular_user(client, auth_headers, "signal_user")
    _approve(client, auth_headers, user["id"])

    signal = client.post(
        f"/api/device/{DEVICE['device_id']}/p2p/signal",
        headers=user_headers,
        json={"session_id": "session-abcdefgh", "kind": "ice", "payload": {"candidate": "x"}},
    )
    assert signal.status_code == 403

    # An administrator is still allowed through, so the gate is on entitlement, not on the route.
    admin_signal = client.post(
        f"/api/device/{DEVICE['device_id']}/p2p/signal",
        headers=auth_headers,
        json={"session_id": "session-abcdefgh", "kind": "ice", "payload": {"candidate": "x"}},
    )
    assert admin_signal.status_code == 200
