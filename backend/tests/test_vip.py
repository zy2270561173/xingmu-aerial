from __future__ import annotations

import asyncio

import httpx
from fastapi.testclient import TestClient

from epay import query_order, sign_params, test_merchant as query_merchant
from p2p_rendezvous import P2PSessionStore


def test_epay_merchant_query_uses_documented_endpoint(monkeypatch) -> None:
    async def fake_get(_client, url: str, *, params: dict):
        assert url == "https://pay.id0.cn/api.php"
        assert params == {"act": "query", "pid": "1001", "key": "merchant-secret"}
        return httpx.Response(
            200,
            request=httpx.Request("GET", url),
            json={
                "code": 1,
                "pid": 1001,
                "active": 1,
                "money": "66.80",
                "type": 2,
                "account": "wx-account",
                "username": "测试商户",
                "orders": 20,
                "order_today": 3,
                "order_lastday": 5,
            },
        )

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    merchant = asyncio.run(query_merchant("https://pay.id0.cn", "1001", "merchant-secret"))
    assert merchant["merchant_name"] == "测试商户"
    assert merchant["settlement_type"] == 2
    assert merchant["orders_today"] == 3


def test_epay_order_query_uses_documented_endpoint(monkeypatch) -> None:
    async def fake_get(_client, url: str, *, params: dict):
        assert url == "https://pay.id0.cn/api.php"
        assert params == {
            "act": "order", "pid": "1001", "key": "merchant-secret",
            "out_trade_no": "VIP202609060001",
        }
        return httpx.Response(
            200,
            request=httpx.Request("GET", url),
            json={
                "code": 1, "pid": 1001, "trade_no": "EPAY001",
                "out_trade_no": "VIP202609060001", "api_trade_no": "WX001",
                "type": "wxpay", "name": "家庭套餐", "money": "28.80",
                "status": 1, "param": "tv_living_room",
            },
        )

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    order = asyncio.run(
        query_order("https://pay.id0.cn", "1001", "merchant-secret", "VIP202609060001")
    )
    assert order is not None
    assert order["status"] == 1
    assert order["trade_no"] == "EPAY001"
    assert order["param"] == "tv_living_room"


def _user(client: TestClient, admin_headers: dict[str, str]) -> tuple[dict, dict[str, str]]:
    created = client.post(
        "/api/users", headers=admin_headers,
        json={"username": "vip_family", "password": "vip-family-password", "nickname": "家人"},
    )
    assert created.status_code == 201
    login = client.post("/api/auth/login", json={"username": "vip_family", "password": "vip-family-password"})
    return created.json(), {"Authorization": f"Bearer {login.json()['access_token']}"}


def test_sales_switch_orders_review_and_redemption_codes(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    user, user_headers = _user(client, auth_headers)
    plans = client.get("/api/vip/plans", headers=user_headers).json()
    assert len(plans) >= 3
    plan = plans[0]
    assert client.post("/api/vip/orders", headers=user_headers, json={"plan_id": plan["id"]}).status_code == 403

    current = client.get("/api/settings", headers=auth_headers).json()
    enabled = client.put(
        "/api/settings", headers=auth_headers,
        json={**current, "ai_api_key": "", "clear_ai_api_key": False, "sales_enabled": True},
    )
    assert enabled.status_code == 200
    order = client.post("/api/vip/orders", headers=user_headers, json={"plan_id": plan["id"]})
    assert order.status_code == 201
    reviewed = client.post(
        f"/api/vip/orders/{order.json()['id']}/review", headers=auth_headers, json={"status": "paid"},
    )
    assert reviewed.status_code == 200
    assert client.get("/api/auth/me", headers=user_headers).json()["vip_active"] is True
    assert client.post(
        f"/api/vip/orders/{order.json()['id']}/review", headers=auth_headers, json={"status": "paid"},
    ).status_code == 409

    created_codes = client.post(
        "/api/vip/codes", headers=auth_headers, json={"plan_id": plan["id"], "count": 2},
    )
    assert created_codes.status_code == 201
    raw_code = created_codes.json()[0]["code"]
    assert raw_code.startswith("STAR-")
    assert "starlight-tvcast://vip" in created_codes.json()[0]["redeem_uri"]
    assert raw_code not in str(client.get("/api/vip/codes", headers=auth_headers).json())
    redeemed = client.post("/api/vip/redeem", headers=user_headers, json={"code": raw_code.lower()})
    assert redeemed.status_code == 200
    assert redeemed.json()["user"]["vip_active"] is True
    assert client.post("/api/vip/redeem", headers=user_headers, json={"code": raw_code}).status_code == 409
    assert client.get("/api/vip/orders", headers=user_headers).json()[0]["user_id"] == user["id"]


def test_p2p_session_tokens_are_role_scoped_and_expiring() -> None:
    store = P2PSessionStore()
    session, control_token, device_token = store.create("tv_123456")
    registered, peer = store.register(session.session_id, "control", control_token, ("198.51.100.2", 41000))
    assert registered is session and peer is None
    assert store.register(session.session_id, "device", control_token, ("203.0.113.4", 42000)) == (None, None)
    registered, peer = store.register(session.session_id, "device", device_token, ("203.0.113.4", 42000))
    assert registered is session and peer == ("198.51.100.2", 41000)


def test_custom_plan_and_epay_callback_are_snapshot_based_and_idempotent(
    client: TestClient, auth_headers: dict[str, str], monkeypatch
) -> None:
    user, user_headers = _user(client, auth_headers)
    plan_response = client.post(
        "/api/vip/plans",
        headers=auth_headers,
        json={
            "name": "家庭自由套餐",
            "days": 90,
            "price_cents": 2880,
            "tag_style": "mint",
            "description": "按家庭需要配置",
            "device_limit": 6,
            "benefits": ["公网远程投屏", "最多 6 台电视", "专属绿色角标"],
            "entitlements": {
                "remote_cast": True,
                "remote_control": True,
                "screen_view": True,
                "app_install": False,
            },
            "badge_text": "家庭版",
            "accent_color": "#12AB78",
            "recommended": True,
            "active": True,
            "sort_order": 7,
        },
    )
    assert plan_response.status_code == 201
    plan = plan_response.json()
    assert plan["device_limit"] == 6
    assert plan["benefits"][1] == "最多 6 台电视"
    assert plan["entitlements"]["app_install"] is False

    current = client.get("/api/settings", headers=auth_headers).json()
    async def fake_test_merchant(base_url: str, pid: str, merchant_key: str):
        assert base_url == "https://pay.id0.cn"
        assert pid == "1001"
        assert merchant_key == "merchant-secret"
        return {
            "ok": True,
            "merchant_pid": "1001",
            "merchant_name": "测试结算人",
            "merchant_active": True,
            "merchant_balance": "88.60",
            "settlement_type": 1,
            "settlement_account": "merchant@example.com",
            "orders": 30,
            "orders_today": 15,
            "orders_lastday": 12,
        }

    monkeypatch.setattr("api.settings_api.test_merchant", fake_test_merchant)
    tested = client.post(
        "/api/settings/payment/test",
        headers=auth_headers,
        json={
            "epay_base_url": "https://pay.id0.cn",
            "epay_pid": "1001",
            "epay_key": "merchant-secret",
            "use_stored_epay_key": False,
        },
    )
    assert tested.status_code == 200
    assert tested.json()["merchant_name"] == "测试结算人"
    assert tested.json()["settlement_account"] == "merchant@example.com"
    assert tested.json()["merchant_active"] is True

    configured = client.put(
        "/api/settings",
        headers=auth_headers,
        json={
            **current,
            "ai_api_key": "",
            "clear_ai_api_key": False,
            "sales_enabled": True,
            "payment_enabled": True,
            "epay_payment_methods": ["alipay"],
            "epay_pid": "1001",
            "epay_key": "merchant-secret",
            "clear_epay_key": False,
        },
    )
    assert configured.status_code == 200
    assert configured.json()["epay_key_set"] is True
    assert configured.json()["epay_payment_methods"] == ["alipay"]
    assert configured.json()["epay_merchant_name"] == "测试结算人"
    assert configured.json()["epay_merchant_account"] == "merchant@example.com"
    assert "epay_key" not in configured.json()

    async def fake_create_payment(**kwargs):
        assert kwargs["payment_method"] == "alipay"
        assert kwargs["amount_cents"] == 2880
        return {
            "trade_no": "EPAY123",
            "payload_type": "qrcode",
            "payment_target": "https://pay.example/qr/EPAY123",
            "provider_response": {"code": 1, "trade_no": "EPAY123", "qrcode": "https://pay.example/qr/EPAY123"},
        }

    monkeypatch.setattr("api.vip.create_payment", fake_create_payment)
    disabled_method = client.post(
        "/api/vip/payments",
        headers=user_headers,
        json={"plan_id": plan["id"], "payment_method": "wxpay"},
    )
    assert disabled_method.status_code == 403
    created = client.post(
        "/api/vip/payments",
        headers=user_headers,
        json={"plan_id": plan["id"], "payment_method": "alipay"},
    )
    assert created.status_code == 201
    order = created.json()
    assert order["username"] == user["username"]
    assert order["payment_status"] == "unpaid"
    assert order["payment_payload_type"] == "qrcode"
    assert order["device_limit"] == 6

    callback = {
        "pid": "1001",
        "trade_no": "EPAY123",
        "out_trade_no": order["order_no"],
        "type": "alipay",
        "name": "家庭自由套餐",
        "money": "28.80",
        "trade_status": "TRADE_SUCCESS",
        "sign_type": "MD5",
    }
    callback["sign"] = sign_params(callback, "merchant-secret")
    bad_callback = {**callback, "money": "0.01"}
    bad_callback["sign"] = sign_params(bad_callback, "merchant-secret")
    assert client.get("/api/vip/payments/epay/notify", params=bad_callback).status_code == 400

    paid = client.get("/api/vip/payments/epay/notify", params=callback)
    assert paid.status_code == 200 and paid.text == "success"
    profile = client.get("/api/auth/me", headers=user_headers).json()
    first_expiry = profile["vip_expires_at"]
    assert profile["vip_device_limit"] == 6
    assert profile["vip_entitlements"]["remote_cast"] is True
    assert profile["vip_entitlements"]["app_install"] is False

    duplicate = client.post("/api/vip/payments/epay/notify", data=callback)
    assert duplicate.status_code == 200 and duplicate.text == "success"
    assert client.get("/api/auth/me", headers=user_headers).json()["vip_expires_at"] == first_expiry

    missed_callback_order = client.post(
        "/api/vip/payments", headers=user_headers,
        json={"plan_id": plan["id"], "payment_method": "alipay"},
    ).json()

    async def fake_query_order(base_url: str, pid: str, merchant_key: str, order_no: str):
        assert base_url == "https://pay.id0.cn"
        assert pid == "1001" and merchant_key == "merchant-secret"
        assert order_no == missed_callback_order["order_no"]
        return {
            "pid": "1001", "trade_no": "EPAY123",
            "out_trade_no": order_no, "api_trade_no": "ALIPAY456",
            "type": "alipay", "name": "家庭自由套餐", "money": "28.80",
            "status": 1, "param": "",
        }

    monkeypatch.setattr("api.vip.query_order", fake_query_order)
    client.app.state.db.execute(
        "UPDATE vip_orders SET updated_at = 0 WHERE order_no = ?",
        (missed_callback_order["order_no"],),
    )
    reconciled = client.get(
        f"/api/vip/payments/{missed_callback_order['order_no']}", headers=user_headers,
    )
    assert reconciled.status_code == 200
    assert reconciled.json()["status"] == "paid"
    assert reconciled.json()["payment_status"] == "paid"
    assert client.get("/api/auth/me", headers=user_headers).json()["vip_expires_at"] == first_expiry + 90 * 86400


def test_admin_package_grants_stack_and_can_be_revoked_individually(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    user, user_headers = _user(client, auth_headers)
    first = client.post(
        "/api/vip/plans", headers=auth_headers,
        json={
            "name": "远程控制包", "days": 30, "price_cents": 990,
            "device_limit": 2, "benefits": ["公网遥控"],
            "entitlements": {"remote_control": True},
        },
    ).json()
    second = client.post(
        "/api/vip/plans", headers=auth_headers,
        json={
            "name": "家庭投屏包", "days": 90, "price_cents": 2990,
            "device_limit": 6, "benefits": ["公网投屏", "多设备"],
            "entitlements": {"remote_cast": True, "screen_view": True},
        },
    ).json()

    first_order = client.post(
        "/api/vip/orders", headers=auth_headers,
        json={"plan_id": first["id"], "user_id": user["id"]},
    ).json()
    assert client.post(
        f"/api/vip/orders/{first_order['id']}/review", headers=auth_headers,
        json={"status": "paid"},
    ).status_code == 200
    first_profile = client.get("/api/auth/me", headers=user_headers).json()

    second_order = client.post(
        "/api/vip/orders", headers=auth_headers,
        json={"plan_id": second["id"], "user_id": user["id"]},
    ).json()
    assert client.post(
        f"/api/vip/orders/{second_order['id']}/review", headers=auth_headers,
        json={"status": "paid"},
    ).status_code == 200
    combined = client.get("/api/auth/me", headers=user_headers).json()
    assert combined["vip_active_plans"] == ["远程控制包", "家庭投屏包"]
    assert combined["vip_device_limit"] == 6
    assert combined["vip_entitlements"]["remote_control"] is True
    assert combined["vip_entitlements"]["remote_cast"] is True
    assert combined["vip_entitlements"]["screen_view"] is True
    assert combined["vip_expires_at"] == first_profile["vip_expires_at"] + 90 * 86400

    grants = [
        item for item in client.get("/api/vip/grants", headers=auth_headers).json()
        if item["user_id"] == user["id"]
    ]
    first_grant = next(item for item in grants if item["plan_name"] == "远程控制包")
    removed = client.delete(
        f"/api/vip/users/{user['id']}/grants/{first_grant['id']}", headers=auth_headers,
    )
    assert removed.status_code == 200
    remaining = client.get("/api/auth/me", headers=user_headers).json()
    assert remaining["vip_active_plans"] == ["家庭投屏包"]
    assert remaining["vip_device_limit"] == 6
    assert remaining["vip_entitlements"].get("remote_control") is not True
    assert remaining["vip_entitlements"]["remote_cast"] is True
    assert remaining["vip_expires_at"] == combined["vip_expires_at"] - 30 * 86400


def test_pending_tv_cannot_read_or_buy_owner_membership(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    _user_data, user_headers = _user(client, auth_headers)
    registered = client.post(
        "/api/device/register",
        json={"device_id": "tv_pending_vip", "name": "待审批电视", "model": "test-tv"},
    ).json()
    pairing = client.post(
        "/api/pairing/device",
        json={"device_id": "tv_pending_vip", "device_token": registered["device_token"]},
    ).json()
    claimed = client.post(
        f"/api/pairing/{pairing['app_pairing']['code']}/claim", headers=user_headers,
    )
    assert claimed.status_code == 200
    device_headers = {
        "X-Device-ID": "tv_pending_vip",
        "X-Device-Token": registered["device_token"],
    }
    denied = client.get("/api/vip/device/status", headers=device_headers)
    assert denied.status_code == 403
    assert "尚未审批" in denied.json()["detail"]


def test_device_membership_api_and_free_account_device_limit(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    user, user_headers = _user(client, auth_headers)

    def register_and_pair(device_id: str) -> tuple[dict, str]:
        registered = client.post(
            "/api/device/register",
            json={"device_id": device_id, "name": device_id, "model": "test-tv"},
        )
        assert registered.status_code == 200
        token = registered.json()["device_token"]
        pairing = client.post(
            "/api/pairing/device",
            json={"device_id": device_id, "device_token": token},
        )
        assert pairing.status_code == 200
        code = pairing.json()["app_pairing"]["code"]
        claimed = client.post(f"/api/pairing/{code}/claim", headers=user_headers)
        return claimed, token

    first, first_token = register_and_pair("tv_vip_limit_01")
    assert first.status_code == 200
    approved = client.post(
        "/api/approvals", headers=user_headers,
        json={"device_id": "tv_vip_limit_01", "approve": True},
    )
    assert approved.status_code == 200
    device_headers = {
        "X-Device-ID": "tv_vip_limit_01",
        "X-Device-Token": first_token,
    }
    status_response = client.get("/api/vip/device/status", headers=device_headers)
    assert status_response.status_code == 200
    assert status_response.json()["owner"]["username"] == user["username"]
    assert client.get("/api/vip/device/plans", headers=device_headers).status_code == 200

    second, _second_token = register_and_pair("tv_vip_limit_02")
    assert second.status_code == 409
    assert "最多绑定 1 台电视" in second.json()["detail"]
