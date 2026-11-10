from __future__ import annotations

import hashlib
import re
import time

import httpx
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect


DEVICE = {
    "device_id": "tv_test_001",
    "name": "客厅电视",
    "model": "Android TV",
    "app_version": "0.2.1",
    "lan_ip": "192.168.1.25",
}


def register(client: TestClient) -> dict:
    response = client.post("/api/device/register", json=DEVICE)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "pending"
    assert body["device_token"]
    assert body["agent_activation"] == {
        "activated": False,
        "status": "pending",
        "requires_approval": True,
        "local_cast_allowed": True,
        "remote_control_allowed": False,
        "remote_screen_allowed": False,
    }
    return body


def create_regular_user(
    client: TestClient,
    auth_headers: dict[str, str],
    username: str = "family_user",
) -> tuple[dict, dict[str, str]]:
    created = client.post(
        "/api/users",
        headers=auth_headers,
        json={"username": username, "password": "family-password"},
    )
    assert created.status_code == 201
    login = client.post(
        "/api/auth/login",
        json={"username": username, "password": "family-password"},
    )
    assert login.status_code == 200
    return created.json(), {"Authorization": f"Bearer {login.json()['access_token']}"}


def test_admin_creates_accounts_and_users_only_see_their_devices(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    first, first_headers = create_regular_user(client, auth_headers, "family_one")
    _second, second_headers = create_regular_user(client, auth_headers, "family_two")
    listed = client.get("/api/users", headers=auth_headers)
    assert listed.status_code == 200
    assert {item["username"] for item in listed.json()} >= {"admin", "family_one", "family_two"}
    assert client.get("/api/users", headers=first_headers).status_code == 403

    registered = client.post(
        "/api/device/register", json={**DEVICE, "owner_username": "family_one"}
    )
    assert registered.status_code == 200
    assert registered.json()["owner_user_id"] == first["id"]
    assert len(client.get("/api/approvals", headers=first_headers).json()) == 1
    assert client.get("/api/approvals", headers=second_headers).json() == []

    approved = client.post(
        "/api/approvals",
        headers=first_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    assert approved.status_code == 200
    assert len(client.get("/api/devices", headers=first_headers).json()) == 1
    assert client.get("/api/devices", headers=second_headers).json() == []
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=second_headers
    ).status_code == 403


def test_admin_can_approve_a_device_for_another_user(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    owner, owner_headers = create_regular_user(client, auth_headers, "parent")
    registered = register(client)
    approved = client.post(
        "/api/approvals",
        headers=auth_headers,
        json={
            "device_id": registered["device_id"],
            "approve": True,
            "owner_user_id": owner["id"],
        },
    )
    assert approved.status_code == 200
    assert approved.json()["owner_user_id"] == owner["id"]
    assert [item["device_id"] for item in client.get("/api/devices", headers=owner_headers).json()] == [DEVICE["device_id"]]


def test_admin_can_reassign_an_approved_device(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    first, first_headers = create_regular_user(client, auth_headers, "first_owner")
    second, second_headers = create_regular_user(client, auth_headers, "second_owner")
    registered = client.post(
        "/api/device/register",
        json={**DEVICE, "owner_username": "first_owner"},
    ).json()
    approved = client.post(
        "/api/approvals",
        headers=first_headers,
        json={"device_id": registered["device_id"], "approve": True},
    )
    assert approved.status_code == 200

    reassigned = client.patch(
        f"/api/device/{DEVICE['device_id']}",
        headers=auth_headers,
        json={"owner_user_id": second["id"]},
    )
    assert reassigned.status_code == 200
    assert reassigned.json()["owner_user_id"] == second["id"]
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=first_headers
    ).status_code == 403
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=second_headers
    ).status_code == 200
    forbidden = client.patch(
        f"/api/device/{DEVICE['device_id']}",
        headers=second_headers,
        json={"owner_user_id": first["id"]},
    )
    assert forbidden.status_code == 403


def test_admin_manages_multiple_accounts_for_one_device(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    primary, primary_headers = create_regular_user(client, auth_headers, "multi_primary")
    member, member_headers = create_regular_user(client, auth_headers, "multi_member")
    _outsider, outsider_headers = create_regular_user(client, auth_headers, "multi_outsider")
    registered = client.post(
        "/api/device/register",
        json={**DEVICE, "owner_username": primary["username"]},
    ).json()
    assert client.post(
        "/api/approvals",
        headers=primary_headers,
        json={"device_id": registered["device_id"], "approve": True},
    ).status_code == 200

    assert client.get(
        f"/api/device/{DEVICE['device_id']}/bindings", headers=member_headers
    ).status_code == 403
    added = client.post(
        f"/api/device/{DEVICE['device_id']}/bindings",
        headers=auth_headers,
        json={"user_id": member["id"]},
    )
    assert added.status_code == 201
    assert [(item["username"], item["binding_role"]) for item in added.json()] == [
        ("multi_primary", "owner"),
        ("multi_member", "member"),
    ]
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=member_headers
    ).status_code == 200
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=outsider_headers
    ).status_code == 403
    assert client.delete(
        f"/api/device/{DEVICE['device_id']}/binding", headers=member_headers
    ).status_code == 403
    assert client.delete(
        f"/api/device/{DEVICE['device_id']}", headers=member_headers
    ).status_code == 403
    assert [item["device_id"] for item in client.get(
        "/api/devices", headers=member_headers
    ).json()] == [DEVICE["device_id"]]
    member_token = member_headers["Authorization"].removeprefix("Bearer ")
    with client.websocket_connect(f"/ws/control/{DEVICE['device_id']}") as websocket:
        websocket.send_json({"type": "auth", "token": member_token})
        assert websocket.receive_json()["type"] == "authenticated"

    counts = {item["username"]: item["device_count"] for item in client.get(
        "/api/users", headers=auth_headers
    ).json()}
    assert counts["multi_primary"] == 1
    assert counts["multi_member"] == 1

    promoted = client.put(
        f"/api/device/{DEVICE['device_id']}/bindings/{member['id']}/primary",
        headers=auth_headers,
    )
    assert promoted.status_code == 200
    assert [(item["username"], item["binding_role"]) for item in promoted.json()] == [
        ("multi_member", "owner"),
        ("multi_primary", "member"),
    ]
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=primary_headers
    ).status_code == 200
    assert client.delete(
        f"/api/device/{DEVICE['device_id']}/bindings/{member['id']}",
        headers=auth_headers,
    ).status_code == 409

    removed = client.delete(
        f"/api/device/{DEVICE['device_id']}/bindings/{primary['id']}",
        headers=auth_headers,
    )
    assert removed.status_code == 200
    assert [item["username"] for item in removed.json()] == ["multi_member"]
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=primary_headers
    ).status_code == 403


def test_deleting_primary_account_promotes_a_shared_member(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    primary, _primary_headers = create_regular_user(client, auth_headers, "delete_primary")
    member, member_headers = create_regular_user(client, auth_headers, "promoted_member")
    registered = client.post(
        "/api/device/register",
        json={**DEVICE, "owner_username": primary["username"]},
    ).json()
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": registered["device_id"], "approve": True, "owner_user_id": primary["id"]},
    )
    client.post(
        f"/api/device/{DEVICE['device_id']}/bindings",
        headers=auth_headers,
        json={"user_id": member["id"]},
    )

    deleted = client.delete(f"/api/users/{primary['id']}", headers=auth_headers)
    assert deleted.status_code == 200
    assert deleted.json()["unbound_device_ids"] == []
    assert deleted.json()["promoted_device_ids"] == [DEVICE["device_id"]]
    device = client.get(
        f"/api/device/{DEVICE['device_id']}", headers=member_headers
    ).json()
    assert device["owner_user_id"] == member["id"]
    assert device["status"] == "approved"


def test_registration_rejects_unknown_binding_account(client: TestClient) -> None:
    response = client.post(
        "/api/device/register", json={**DEVICE, "owner_username": "missing_user"}
    )
    assert response.status_code == 400


def test_newer_agent_replaces_stale_registration_and_requires_fresh_approval(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    original = register(client)
    approved = client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    assert approved.status_code == 200

    upgraded = client.post(
        "/api/device/register",
        json={**DEVICE, "app_version": "0.6.2-debug", "token": "stale-token"},
    )
    assert upgraded.status_code == 200
    body = upgraded.json()
    assert body["device_id"] == DEVICE["device_id"]
    assert body["status"] == "pending"
    assert body["owner_user_id"] is None
    assert body["credentials_recovered"] is True
    assert body["stale_registration_replaced"] is True
    assert body["device_token"] != original["device_token"]
    assert body["pairing"]["code"]
    assert body["agent_activation"]["requires_approval"] is True

    devices = client.get("/api/devices", headers=auth_headers)
    assert devices.status_code == 200
    assert len(devices.json()) == 1
    assert devices.json()[0]["power_state"] == "reconnecting"
    assert devices.json()[0]["missed_heartbeats"] == 0
    events = client.get(
        f"/api/device/{DEVICE['device_id']}/events", headers=auth_headers
    )
    assert events.json()[0]["type"] == "stale_registration_replaced"


def test_credential_recovery_rejects_unmatched_or_non_newer_agent(client: TestClient) -> None:
    register(client)
    same_version = client.post(
        "/api/device/register", json={**DEVICE, "token": "stale-token"}
    )
    assert same_version.status_code == 409
    wrong_model = client.post(
        "/api/device/register",
        json={**DEVICE, "model": "Other TV", "app_version": "9.0.0", "token": "stale-token"},
    )
    assert wrong_model.status_code == 409


def test_delete_device_removes_it_and_its_history(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    deleted = client.delete(f"/api/device/{DEVICE['device_id']}", headers=auth_headers)
    assert deleted.status_code == 200
    assert deleted.json() == {"deleted": True, "device_id": DEVICE["device_id"]}
    assert client.get("/api/devices", headers=auth_headers).json() == []
    assert client.get(
        f"/api/device/{DEVICE['device_id']}", headers=auth_headers
    ).status_code == 404


def test_rotated_newer_identity_removes_stale_device_without_inheriting_account(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    replacement_id = "tv_replacement_002"
    replacement = client.post(
        "/api/device/register",
        json={**DEVICE, "device_id": replacement_id, "app_version": "0.7.0-debug"},
    )
    assert replacement.status_code == 200
    body = replacement.json()
    assert body["device_id"] == replacement_id
    assert body["status"] == "pending"
    assert body["owner_user_id"] is None
    assert body["replaced_device_ids"] == [DEVICE["device_id"]]
    assert body["agent_activation"]["requires_approval"] is True
    devices = client.get("/api/devices", headers=auth_headers).json()
    assert [item["device_id"] for item in devices] == [replacement_id]


def test_health_and_admin_system_status(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    health = client.get("/api/health")
    assert health.status_code == 200
    assert health.json()["version"] == "0.14.11"
    assert health.json()["connected_agents"] == 0

    register(client)
    system = client.get("/api/system/status", headers=auth_headers)
    assert system.status_code == 200
    assert system.json()["registered_devices"] == 1
    assert system.json()["pending_approvals"] == 1
    assert system.json()["database_size_bytes"] > 0


def test_device_is_assumed_off_after_five_missed_heartbeats(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    client.app.state.db.execute(
        "UPDATE devices SET last_seen_at = ? WHERE device_id = ?",
        (int(time.time()) - 25, DEVICE["device_id"]),
    )
    device = client.get("/api/devices", headers=auth_headers).json()[0]
    assert device["missed_heartbeats"] >= 5
    assert device["power_state"] == "assumed_off"


def test_register_approve_agent_and_command_flow(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        try:
            agent.receive_json()
            raise AssertionError("pending agent websocket must be rejected")
        except WebSocketDisconnect as error:
            assert error.code == 4403

    pending = client.get("/api/approvals", headers=auth_headers)
    assert pending.status_code == 200
    assert len(pending.json()) == 1

    approved = client.post(
        "/api/auth/approve",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    assert approved.status_code == 200
    assert approved.json()["status"] == "approved"

    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        authenticated = agent.receive_json()
        assert authenticated["type"] == "authenticated"
        assert authenticated["status"] == "approved"
        command = client.post(
            f"/api/device/{DEVICE['device_id']}/command",
            headers=auth_headers,
            json={"type": "key", "payload": {"key": "MEDIA_PLAY_PAUSE"}},
        )
        assert command.status_code == 200
        assert command.json()["status"] == "delivered"
        delivered = agent.receive_json()
        assert delivered["type"] == "command"
        command_id = delivered["command"]["id"]

        agent.send_json(
            {
                "type": "command_result",
                "device_id": DEVICE["device_id"],
                "data": {"command_id": command_id, "success": True, "message": "done"},
            }
        )
        assert agent.receive_json()["type"] == "ack"

    commands = client.get(
        f"/api/device/{DEVICE['device_id']}/commands", headers=auth_headers
    )
    assert commands.status_code == 200
    assert commands.json()[0]["status"] == "completed"


def test_control_websocket_uses_first_frame_jwt(
    client: TestClient, admin_token: str, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect(f"/ws/control/{DEVICE['device_id']}") as control:
        control.send_json({"type": "auth", "token": admin_token})
        assert control.receive_json()["type"] == "authenticated"
        control.send_json({"type": "ping"})
        assert control.receive_json()["type"] == "pong"
    assert registered["device_token"] not in f"/ws/control/{DEVICE['device_id']}"


def test_control_websocket_forwards_transient_snapshot_request(
    client: TestClient, admin_token: str, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        assert agent.receive_json()["type"] == "authenticated"
        with client.websocket_connect(f"/ws/control/{DEVICE['device_id']}") as control:
            control.send_json({"type": "auth", "token": admin_token})
            assert control.receive_json()["type"] == "authenticated"
            control.send_json({"type": "snapshot_request"})
            assert agent.receive_json() == {"type": "snapshot_request"}
            acknowledged = control.receive_json()
            assert acknowledged["type"] == "snapshot_ack"
            assert acknowledged["delivered"] is True
            assert acknowledged["throttled"] is False

            control.send_json({"type": "snapshot_request"})
            coalesced = control.receive_json()
            assert coalesced["type"] == "snapshot_ack"
            assert coalesced["delivered"] is True
            assert coalesced["throttled"] is True


def test_snapshot_is_broadcast_but_not_stored_in_event_history(
    client: TestClient, admin_token: str, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        assert agent.receive_json()["type"] == "authenticated"
        with client.websocket_connect(f"/ws/control/{DEVICE['device_id']}") as control:
            control.send_json({"type": "auth", "token": admin_token})
            assert control.receive_json()["type"] == "authenticated"
            agent.send_json(
                {
                    "type": "snapshot",
                    "device_id": DEVICE["device_id"],
                    "data": {
                        "screen_base64": "data:image/jpeg;base64,dGVzdA==",
                        "current_activity": "PlayerActivity",
                    },
                }
            )
            event = control.receive_json()
            assert event["type"] == "device_event"
            assert event["event"] == "snapshot"
            assert agent.receive_json()["type"] == "ack"

    history = client.get(
        f"/api/device/{DEVICE['device_id']}/events", headers=auth_headers
    )
    assert history.status_code == 200
    assert all(item["type"] != "snapshot" for item in history.json())


def test_binary_jpeg_preview_is_subscribed_and_forwarded(
    client: TestClient, admin_token: str, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    jpeg = b"\xff\xd8continuous-preview\xff\xd9"
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {"type": "auth", "device_id": DEVICE["device_id"],
             "token": registered["device_token"]}
        )
        assert agent.receive_json()["type"] == "authenticated"
        with client.websocket_connect(f"/ws/control/{DEVICE['device_id']}") as control:
            control.send_json({"type": "auth", "token": admin_token})
            assert control.receive_json()["type"] == "authenticated"
            control.send_json({"type": "preview_start", "fps": 99})
            start = agent.receive_json()
            assert start == {
                "type": "preview_start", "fps": 15, "width": 1280,
                "height": 720, "quality": 55,
            }
            acknowledgement = control.receive_json()
            assert acknowledgement["type"] == "preview_ack"
            assert acknowledgement["active"] is True
            agent.send_bytes(jpeg)
            assert control.receive_bytes() == jpeg
            agent.send_json(
                {"type": "heartbeat", "device_id": DEVICE["device_id"], "data": {}}
            )
            assert agent.receive_json()["type"] == "ack"
            assert client.get("/api/health").json()["connected_agents"] == 1
        assert agent.receive_json()["type"] == "preview_stop"


def test_snapshot_does_not_erase_latest_system_metadata(
    client: TestClient, admin_token: str, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        assert agent.receive_json()["type"] == "authenticated"
        agent.send_json(
            {
                "type": "heartbeat",
                "device_id": DEVICE["device_id"],
                "data": {
                    "metadata": {
                        "android_api": 30,
                        "control_mode": "accessibility",
                        "capture_mode": "accessibility",
                    }
                },
            }
        )
        assert agent.receive_json()["type"] == "ack"
        agent.send_json(
            {
                "type": "snapshot",
                "device_id": DEVICE["device_id"],
                "data": {"screen_base64": "data:image/jpeg;base64,dGVzdA=="},
            }
        )
        assert agent.receive_json()["type"] == "ack"

    loaded = client.get(
        f"/api/device/{DEVICE['device_id']}", headers=auth_headers
    ).json()
    assert loaded["metadata"]["control_mode"] == "accessibility"
    assert loaded["metadata"]["capture_mode"] == "accessibility"


def test_agent_inventory_can_be_queried_and_apps_can_be_launched(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        assert agent.receive_json()["type"] == "authenticated"
        agent.send_json(
            {
                "type": "app_inventory",
                "device_id": DEVICE["device_id"],
                "data": {
                    "total": 2,
                    "collected_at": 123456,
                    "apps": [
                        {
                            "package_name": "com.example.video",
                            "label": "视频",
                            "version_name": "2.1",
                            "version_code": 21,
                            "system": False,
                            "enabled": True,
                            "launchable": True,
                            "first_install_time": 100,
                            "last_update_time": 200,
                            "icon_mime": "image/png",
                            "icon_base64": "iVBORw0KGgo=",
                        },
                        {
                            "package_name": "com.android.settings",
                            "label": "设置",
                            "system": True,
                            "enabled": True,
                            "launchable": True,
                        },
                    ],
                },
            }
        )
        assert agent.receive_json()["type"] == "ack"

        apps = client.get(
            f"/api/device/{DEVICE['device_id']}/apps", headers=auth_headers
        )
        assert apps.status_code == 200
        assert {item["package_name"] for item in apps.json()} == {
            "com.example.video",
            "com.android.settings",
        }
        assert next(
            item for item in apps.json() if item["package_name"] == "com.example.video"
        )["icon_data_uri"] == "data:image/png;base64,iVBORw0KGgo="

        command = client.post(
            f"/api/device/{DEVICE['device_id']}/commands",
            headers=auth_headers,
            json={
                "type": "app",
                "payload": {"action": "launch", "package_name": "com.example.video"},
            },
        )
        assert command.status_code == 200
        delivered = agent.receive_json()
        assert delivered["type"] == "command"
        assert delivered["command"]["type"] == "app"
        assert delivered["command"]["payload"]["package_name"] == "com.example.video"


def test_deleting_device_cascades_app_inventory(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {"type": "auth", "device_id": DEVICE["device_id"], "token": registered["device_token"]}
        )
        assert agent.receive_json()["type"] == "authenticated"
        agent.send_json(
            {
                "type": "app_inventory",
                "device_id": DEVICE["device_id"],
                "data": {"apps": [{"package_name": "com.example.app", "label": "Example"}]},
            }
        )
        assert agent.receive_json()["type"] == "ack"
    assert client.delete(
        f"/api/device/{DEVICE['device_id']}", headers=auth_headers
    ).status_code == 200
    assert client.app.state.db.fetch_all("SELECT * FROM device_apps") == []


def test_apk_upload_and_p2p_relay_fallback(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    apk_content = b"PK\x03\x04fake-apk-for-api-test"
    uploaded = client.post(
        "/api/upload/apk",
        headers=auth_headers,
        files={"file": ("receiver.apk", apk_content, "application/vnd.android.package-archive")},
    )
    assert uploaded.status_code == 200
    assert uploaded.json()["sha256"] == hashlib.sha256(apk_content).hexdigest()

    session = client.post(
        f"/api/device/{DEVICE['device_id']}/p2p/session",
        headers=auth_headers,
        json={"mode": "auto"},
    )
    assert session.status_code == 200
    assert session.json()["selected_mode"] == "relay"
    assert session.json()["relay_fallback"] is True
    assert session.json()["direct_capable"] is False


def test_p2p_requires_server_switch_and_explicit_device_capability(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            "ai_enabled": False,
            "ai_api_url": "",
            "ai_api_model": "",
            "p2p_enabled": True,
            "relay_enabled": True,
        },
    )
    unsupported = client.post(
        f"/api/device/{DEVICE['device_id']}/p2p/session",
        headers=auth_headers,
        json={"mode": "auto"},
    )
    assert unsupported.status_code == 200
    assert unsupported.json()["selected_mode"] == "relay"
    assert unsupported.json()["selection_reason"] == "device_udp_transport_unavailable"

    direct_only = client.post(
        f"/api/device/{DEVICE['device_id']}/p2p/session",
        headers=auth_headers,
        json={"mode": "p2p"},
    )
    assert direct_only.status_code == 200
    assert direct_only.json()["selected_mode"] == "unavailable"
    assert direct_only.json()["relay_fallback"] is False

    client.app.state.db.execute(
        "UPDATE devices SET metadata_json = ? WHERE device_id = ?",
        ('{"udp_p2p_transport":"ready"}', DEVICE["device_id"]),
    )
    supported = client.post(
        f"/api/device/{DEVICE['device_id']}/p2p/session",
        headers=auth_headers,
        json={"mode": "auto"},
    )
    assert supported.status_code == 200
    assert supported.json()["selected_mode"] == "relay"
    assert supported.json()["direct_capable"] is True
    assert supported.json()["selection_reason"] == "device_signaling_unavailable"


def test_admin_can_publish_platform_updates_and_clients_compare_version_codes(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    uploaded = client.post(
        "/api/upload/apk",
        headers=auth_headers,
        files={"file": ("tv-0.9.0.apk", b"PK\x03\x04release", "application/vnd.android.package-archive")},
    )
    assert uploaded.status_code == 200
    published = client.post(
        "/api/releases",
        headers=auth_headers,
        json={
            "platform": "tv",
            "apk_id": uploaded.json()["id"],
            "version_code": 14,
            "version_name": "0.9.0",
            "release_notes": "应用图标与自动更新",
            "mandatory": False,
        },
    )
    assert published.status_code == 200
    assert published.json()["sha256"] == uploaded.json()["sha256"]

    available = client.get("/api/releases/latest/tv?current_version_code=13")
    assert available.status_code == 200
    assert available.json()["update_available"] is True
    assert available.json()["release"]["version_code"] == 14
    current = client.get("/api/releases/latest/tv?current_version_code=14")
    assert current.json()["update_available"] is False
    assert client.get("/api/releases", headers=auth_headers).json()[0]["platform"] == "tv"


def test_unapproved_device_cannot_receive_commands(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    register(client)
    response = client.post(
        f"/api/device/{DEVICE['device_id']}/commands",
        headers=auth_headers,
        json={"type": "key", "payload": {"key": "HOME"}},
    )
    assert response.status_code == 409


def test_disconnect_requeues_only_commands_that_never_started(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    client.post(
        "/api/approvals",
        headers=auth_headers,
        json={"device_id": DEVICE["device_id"], "approve": True},
    )
    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        assert agent.receive_json()["type"] == "authenticated"
        response = client.post(
            f"/api/device/{DEVICE['device_id']}/commands",
            headers=auth_headers,
            json={"type": "key", "payload": {"key": "HOME"}},
        )
        command_id = response.json()["id"]
        assert agent.receive_json()["command"]["id"] == command_id

    queued = client.get(
        f"/api/device/{DEVICE['device_id']}/commands", headers=auth_headers
    ).json()[0]
    assert queued["status"] == "pending"

    with client.websocket_connect("/ws/device") as agent:
        agent.send_json(
            {
                "type": "auth",
                "device_id": DEVICE["device_id"],
                "token": registered["device_token"],
            }
        )
        assert agent.receive_json()["type"] == "authenticated"
        assert agent.receive_json()["command"]["id"] == command_id
        agent.send_json(
            {
                "type": "command_result",
                "device_id": DEVICE["device_id"],
                "data": {
                    "command_id": command_id,
                    "status": "started",
                    "success": True,
                    "message": "running",
                },
            }
        )
        assert agent.receive_json()["type"] == "ack"

    started = client.get(
        f"/api/device/{DEVICE['device_id']}/commands", headers=auth_headers
    ).json()[0]
    assert started["status"] == "started"


def test_ai_settings_hide_and_preserve_api_key(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    saved = client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            "ai_enabled": True,
            "ai_api_url": "https://api.example.com/v1/",
            "ai_api_key": "secret-test-key",
            "ai_api_model": "test-model",
            "clear_ai_api_key": False,
            "p2p_enabled": True,
            "relay_enabled": True,
        },
    )
    assert saved.status_code == 200
    assert saved.json()["ai_api_url"] == "https://api.example.com/v1"
    assert saved.json()["ai_api_key_set"] is True
    assert "ai_api_key" not in saved.json()

    preserved = client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            "ai_enabled": True,
            "ai_api_url": "https://api.example.com/v1",
            "ai_api_key": "",
            "ai_api_model": "new-model",
            "clear_ai_api_key": False,
            "p2p_enabled": False,
            "relay_enabled": True,
        },
    )
    assert preserved.status_code == 200
    assert preserved.json()["ai_api_key_set"] is True

    loaded = client.get("/api/settings", headers=auth_headers)
    assert loaded.status_code == 200
    assert loaded.json()["ai_api_model"] == "new-model"
    assert loaded.json()["ai_api_key_set"] is True
    assert "ai_api_key" not in loaded.json()
    assert "secret-test-key" not in loaded.text


def test_ai_settings_can_clear_api_key_and_reject_unsafe_url(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    cleared = client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            "ai_enabled": False,
            "ai_api_url": "http://192.168.1.10:11434/v1",
            "ai_api_model": "local-model",
            "clear_ai_api_key": True,
            "p2p_enabled": False,
            "relay_enabled": True,
        },
    )
    assert cleared.status_code == 200
    assert cleared.json()["ai_api_key_set"] is False

    unsafe = client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            "ai_enabled": True,
            "ai_api_url": "https://api.example.com/v1?api_key=leaked",
            "ai_api_model": "test-model",
            "p2p_enabled": False,
            "relay_enabled": True,
        },
    )
    assert unsafe.status_code == 422


def test_ai_connection_uses_stored_key_without_exposing_it(
    client: TestClient, auth_headers: dict[str, str], monkeypatch
) -> None:
    client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            "ai_enabled": False,
            "ai_api_url": "https://api.example.com/v1",
            "ai_api_key": "stored-secret-key",
            "ai_api_model": "test-model",
            "p2p_enabled": False,
            "relay_enabled": True,
        },
    )
    captured: dict = {}

    async def fake_post(self, url, **kwargs):
        captured["url"] = url
        captured["headers"] = kwargs["headers"]
        captured["json"] = kwargs["json"]
        return httpx.Response(
            200,
            json={"model": "test-model", "choices": [{"message": {"content": "OK"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    tested = client.post(
        "/api/settings/ai/test",
        headers=auth_headers,
        json={
            "ai_api_url": "https://api.example.com/v1",
            "ai_api_model": "test-model",
            "use_stored_api_key": True,
        },
    )

    assert tested.status_code == 200
    assert tested.json()["ok"] is True
    assert tested.json()["latency_ms"] >= 1
    assert captured["url"] == "https://api.example.com/v1/chat/completions"
    assert captured["headers"]["Authorization"] == "Bearer stored-secret-key"
    assert captured["json"]["model"] == "test-model"
    assert "stored-secret-key" not in tested.text


def test_ai_connection_reports_upstream_error_and_redacts_key(
    client: TestClient, auth_headers: dict[str, str], monkeypatch
) -> None:
    async def fake_post(self, url, **kwargs):
        return httpx.Response(
            401,
            json={"error": {"message": "bad key test-secret-key"}},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    tested = client.post(
        "/api/settings/ai/test",
        headers=auth_headers,
        json={
            "ai_api_url": "https://api.example.com/v1",
            "ai_api_key": "test-secret-key",
            "ai_api_model": "test-model",
            "use_stored_api_key": False,
        },
    )

    assert tested.status_code == 200
    assert tested.json()["ok"] is False
    assert tested.json()["message"] == "Upstream HTTP 401: bad key ***"
    assert "test-secret-key" not in tested.text


def test_hosted_player_uses_authenticated_short_lived_ticket(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    source_url = "https://media.example.com/live/channel.m3u8?token=private-value"
    created = client.post(
        "/api/player/sessions",
        headers=auth_headers,
        json={
            "url": source_url,
            "media_type": "application/vnd.apple.mpegurl",
            "title": "</script><script>alert(1)</script>",
        },
    )

    assert created.status_code == 200
    data = created.json()
    assert data["engine"] == "dplayer"
    assert data["transcode_available"] is False
    assert data["player_path"] == f"/player/{data['ticket']}"
    assert source_url not in created.text
    assert "private-value" not in data["player_url"]

    page = client.get(data["player_url"])
    assert page.status_code == 200
    assert "DPlayer-1.25.1.min.js" in page.text
    assert "hls.light-0.14.17.min.js" in page.text
    assert source_url not in page.text
    assert "private-value" not in page.text
    assert f"/player/{data['ticket']}/media" in page.text
    assert "</script><script>alert(1)</script>" not in page.text
    assert page.headers["cache-control"] == "no-store"


def test_hosted_player_supports_palyer_url_compatibility_route(
    client: TestClient,
) -> None:
    unauthenticated = client.post(
        "/api/player/sessions",
        json={"url": "https://media.example.com/video.mp4"},
    )
    assert unauthenticated.status_code == 401

    raw_query = client.get(
        "/palyer",
        params={"url": "https://93.184.216.34/video.mp4?token=secret"},
        follow_redirects=False,
    )
    assert raw_query.status_code == 307
    assert raw_query.headers["location"].startswith("/player/")
    assert "secret" not in raw_query.headers["location"]


def test_public_registration_and_one_time_device_pairing(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    registered_device = register(client)
    pair_data = registered_device["pairing"]
    app_code = pair_data["code"]
    assert "starlight-tvcast://pair" in pair_data["uri"]
    assert f"/pair/{app_code}" in pair_data["web_url"]
    repeated_registration = client.post("/api/device/register", json={**DEVICE, "token": registered_device["device_token"]})
    assert repeated_registration.status_code == 200
    assert repeated_registration.json()["pairing"]["code"] == app_code

    legacy_pairing = client.post(
        "/api/pairing/device",
        json={
            "device_id": registered_device["device_id"],
            "device_token": registered_device["device_token"],
        },
    )
    assert legacy_pairing.status_code == 200
    assert legacy_pairing.json()["deprecated"] is True
    assert legacy_pairing.json()["pairing"]["code"] == app_code

    page = client.get(f"/pair/{app_code}")
    assert page.status_code == 200
    assert "注册并绑定" in page.text
    assert "手机号（注册时填写）" in page.text
    assert "上传 APK 到电视安装" in page.text
    assert registered_device["device_token"] not in page.text

    created_user = client.post(
        "/api/auth/register",
        json={
            "username": "new_family",
            "password": "new-family-password",
            "nickname": "家人账号",
            "phone": "13600136000",
        },
    )
    assert created_user.status_code == 201
    user_data = created_user.json()
    assert user_data["user"]["role"] == "user"
    user_headers = {"Authorization": f"Bearer {user_data['access_token']}"}

    summary = client.get(f"/api/pairing/{app_code}")
    assert summary.status_code == 200
    assert summary.json()["purpose"] == "app"
    assert summary.json()["claimed"] is False
    claimed = client.post(f"/api/pairing/{app_code}/claim", headers=user_headers)
    assert claimed.status_code == 200
    assert claimed.json()["owner_user_id"] == user_data["user"]["id"]
    assert claimed.json()["status"] == "pending"
    assert claimed.json()["approval_required"] is True
    assert claimed.json()["next_step"] == "open_device_approvals"
    assert claimed.json()["approval_api"] == "/api/device-approvals"
    claimed_summary = client.get(f"/api/pairing/{app_code}")
    assert claimed_summary.json()["approval_status"] == "pending"
    assert claimed_summary.json()["owner"] is None
    assert claimed_summary.json()["close_pairing"] is False
    assert claimed_summary.json()["message"] == ""
    pending = client.get("/api/device-approvals", headers=user_headers)
    assert pending.status_code == 200
    assert pending.json()[0]["device_id"] == DEVICE["device_id"]
    approved = client.post(
        f"/api/device-approvals/{DEVICE['device_id']}/approve",
        headers=user_headers,
        json={},
    )
    assert approved.status_code == 200
    assert approved.json()["status"] == "approved"
    claimed_summary = client.get(f"/api/pairing/{app_code}")
    assert claimed_summary.json()["approval_status"] == "approved"
    assert claimed_summary.json()["owner"]["phone"] == "13600136000"
    assert claimed_summary.json()["close_pairing"] is True
    assert claimed_summary.json()["message"] == "登录成功 · App 审批已批准！"
    assert client.get("/api/devices", headers=user_headers).json()[0]["device_id"] == DEVICE["device_id"]
    assert client.post(f"/api/pairing/{app_code}/claim", headers=user_headers).status_code == 409
    approved_registration = client.post(
        "/api/device/register", json={**DEVICE, "token": registered_device["device_token"]}
    )
    assert approved_registration.status_code == 200
    assert approved_registration.json()["pairing"] is None
    assert approved_registration.json()["agent_activation"] == {
        "activated": True,
        "status": "approved",
        "requires_approval": False,
        "local_cast_allowed": True,
        "remote_control_allowed": True,
        "remote_screen_allowed": True,
    }

    admin_device = client.get(
        f"/api/device/{DEVICE['device_id']}", headers=auth_headers
    )
    assert admin_device.status_code == 200
    assert admin_device.json()["owner_user_id"] == user_data["user"]["id"]


def test_pairing_rejects_invalid_device_token(client: TestClient) -> None:
    registered_device = register(client)
    denied = client.post(
        "/api/pairing/device",
        json={"device_id": registered_device["device_id"], "device_token": "x" * 32},
    )
    assert denied.status_code == 401


def test_tv_account_password_login_and_logout(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    registered = register(client)
    created, user_headers = create_regular_user(client, auth_headers, "tv_login_user")
    denied = client.post(
        "/api/pairing/device/login",
        json={"device_id": DEVICE["device_id"], "device_token": registered["device_token"],
              "username": "tv_login_user", "password": "wrong-password"},
    )
    assert denied.status_code == 401
    logged_in = client.post(
        "/api/pairing/device/login",
        json={"device_id": DEVICE["device_id"], "device_token": registered["device_token"],
              "username": "tv_login_user", "password": "family-password"},
    )
    assert logged_in.status_code == 200
    assert logged_in.json()["owner"]["username"] == "tv_login_user"
    assert logged_in.json()["device"]["owner_user_id"] == created["id"]
    assert client.get("/api/devices", headers=user_headers).json()[0]["status"] == "approved"

    logged_out = client.post(
        "/api/pairing/device/logout",
        json={"device_id": DEVICE["device_id"], "device_token": registered["device_token"]},
    )
    assert logged_out.status_code == 200
    assert logged_out.json()["logged_out"] is True
    assert logged_out.json()["device"]["owner_user_id"] is None
    assert logged_out.json()["device"]["status"] == "pending"
    assert client.get("/api/devices", headers=user_headers).json() == []


def test_user_profile_password_avatar_admin_crud_and_device_unbind(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    created = client.post(
        "/api/users",
        headers=auth_headers,
        json={
            "username": "family_profile",
            "password": "first-password",
            "nickname": "家里电视",
            "phone": "13800138000",
        },
    )
    assert created.status_code == 201
    user = created.json()
    assert user["nickname"] == "家里电视"
    assert user["phone"] == "13800138000"

    login = client.post(
        "/api/auth/login", json={"username": "13800138000", "password": "first-password"}
    )
    assert login.status_code == 200
    user_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    profile = client.patch(
        "/api/auth/profile",
        headers=user_headers,
        json={"nickname": "爸爸", "phone": "13900139000"},
    )
    assert profile.status_code == 200
    assert profile.json()["nickname"] == "爸爸"
    assert profile.json()["phone"] == "13900139000"
    cleared_phone = client.patch(
        "/api/auth/profile",
        headers=user_headers,
        json={"nickname": "爸爸", "phone": ""},
    )
    assert cleared_phone.status_code == 200
    assert cleared_phone.json()["phone"] == ""
    restored_phone = client.patch(
        "/api/auth/profile",
        headers=user_headers,
        json={"nickname": "爸爸", "phone": "13900139000"},
    )
    assert restored_phone.status_code == 200

    wrong = client.post(
        "/api/auth/password",
        headers=user_headers,
        json={
            "old_password": "wrong-password",
            "new_password": "second-password",
            "new_password_confirm": "second-password",
        },
    )
    assert wrong.status_code == 400
    changed = client.post(
        "/api/auth/password",
        headers=user_headers,
        json={
            "old_password": "first-password",
            "new_password": "second-password",
            "new_password_confirm": "second-password",
        },
    )
    assert changed.status_code == 200
    assert client.post(
        "/api/auth/login", json={"username": "family_profile", "password": "second-password"}
    ).status_code == 200

    avatar = client.post(
        "/api/auth/avatar",
        headers=user_headers,
        files={"file": ("avatar.png", b"\x89PNG\r\n\x1a\nprofile", "image/png")},
    )
    assert avatar.status_code == 200
    assert f"/api/users/{user['id']}/avatar?v=" in avatar.json()["avatar_url"]
    image = client.get(f"/api/users/{user['id']}/avatar")
    assert image.status_code == 200
    assert image.headers["content-type"] == "image/png"

    registered = client.post(
        "/api/device/register",
        json={
            **DEVICE,
            "device_id": "tv_profile_001",
            "name": "客厅电视",
            "owner_phone": "13900139000",
        },
    )
    assert registered.status_code == 200
    assert registered.json()["owner_user_id"] == user["id"]
    unbound = client.delete("/api/device/tv_profile_001/binding", headers=user_headers)
    assert unbound.status_code == 200
    assert unbound.json()["owner_user_id"] is None
    assert unbound.json()["status"] == "pending"

    updated = client.patch(
        f"/api/users/{user['id']}",
        headers=auth_headers,
        json={"nickname": "家人", "phone": "13700137000", "active": True},
    )
    assert updated.status_code == 200
    assert updated.json()["nickname"] == "家人"
    deleted = client.delete(f"/api/users/{user['id']}", headers=auth_headers)
    assert deleted.status_code == 200
    assert deleted.json()["deleted"] is True
    assert all(item["id"] != user["id"] for item in client.get("/api/users", headers=auth_headers).json())


def test_hosted_player_rewrites_hls_playlists_through_same_origin_relay(
    client: TestClient,
    auth_headers: dict[str, str],
    monkeypatch,
) -> None:
    import api.player as player_api

    async def allow_test_host(_value: str) -> None:
        return None

    async def fake_send(self, request, stream=False):
        path = request.url.path
        if path.endswith("channel.m3u8"):
            content = (
                '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
                '#EXT-X-STREAM-INF:BANDWIDTH=800000\nvariant/stream.m3u8\n'
            )
            content_type = "application/vnd.apple.mpegurl"
        elif path.endswith("stream.m3u8"):
            content = "#EXTM3U\n#EXTINF:2,\nsegment-1.ts\n"
            content_type = "application/vnd.apple.mpegurl"
        elif path.endswith("key.bin"):
            content = b"0123456789abcdef"
            content_type = "application/octet-stream"
        else:
            content = b"mpeg-ts-segment"
            content_type = "video/mp2t"
        return httpx.Response(
            200,
            stream=httpx.ByteStream(content.encode("utf-8") if isinstance(content, str) else content),
            headers={"content-type": content_type},
            request=request,
        )

    monkeypatch.setattr(player_api, "_ensure_public_http_url", allow_test_host)
    monkeypatch.setattr(httpx.AsyncClient, "send", fake_send)
    created = client.post(
        "/api/player/sessions",
        headers=auth_headers,
        json={
            "url": "https://media.example.com/live/channel.m3u8?signature=secret",
            "media_type": "application/vnd.apple.mpegurl",
        },
    ).json()
    media_path = f"/player/{created['ticket']}/media"
    playlist = client.get(media_path)

    assert playlist.status_code == 200
    assert playlist.headers["content-type"].startswith("application/vnd.apple.mpegurl")
    assert "media.example.com" not in playlist.text
    assert "signature=secret" not in playlist.text
    resource_paths = re.findall(
        rf"/player/{re.escape(created['ticket'])}/resource/[A-Za-z0-9_-]+",
        playlist.text,
    )
    assert len(set(resource_paths)) == 2

    nested_responses = [client.get(path) for path in set(resource_paths)]
    nested_playlist = next(
        response for response in nested_responses if response.text.startswith("#EXTM3U")
    )
    assert "segment-1.ts" not in nested_playlist.text
    assert f"/player/{created['ticket']}/resource/" in nested_playlist.text
