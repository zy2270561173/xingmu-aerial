from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import secrets
import time
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping
from urllib.parse import parse_qsl, quote

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import PlainTextResponse

from dependencies import get_authenticated_device, get_current_user, require_admin
from epay import create_payment, query_order, verify_signature
from membership import grant_membership, grant_public, revoke_membership_grant
from models import (
    VipCodeCreateRequest,
    VipCodeRedeemRequest,
    VipOrderCreateRequest,
    VipOrderReviewRequest,
    VipPaymentCreateRequest,
    VipPlanCreateRequest,
    VipPlanUpdateRequest,
)
from serializers import parse_json, user_public
from services import user_has_device_access


router = APIRouter(prefix="/api/vip", tags=["vip-sales"])
logger = logging.getLogger(__name__)


def _base(request: Request) -> str:
    return request.app.state.settings.public_base_url or str(request.base_url).rstrip("/")


def _settings_for_app(app: Any) -> dict[str, Any]:
    rows = app.state.db.fetch_all("SELECT key, value_json FROM settings")
    return {row["key"]: parse_json(row["value_json"]) for row in rows}


def _settings(request: Request) -> dict[str, Any]:
    return _settings_for_app(request.app)


def _sales_enabled(request: Request) -> bool:
    return bool(_settings(request).get("sales_enabled", False))


def _payment_methods(settings: Mapping[str, Any]) -> list[str]:
    configured = settings.get("epay_payment_methods", ["alipay", "wxpay"])
    if not isinstance(configured, list):
        return ["alipay", "wxpay"]
    return [item for item in ("alipay", "wxpay") if item in configured]


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _row_get(row: Mapping[str, Any], key: str, default: Any = None) -> Any:
    try:
        value = row[key]
    except (KeyError, IndexError):
        return default
    return default if value is None else value


def _plan_public(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row["name"],
        "days": row["days"],
        "price_cents": row["price_cents"],
        "tag_style": row["tag_style"],
        "description": row["description"],
        "device_limit": int(row.get("device_limit") or 1),
        "benefits": parse_json(row.get("benefits_json"), []) or [],
        "entitlements": parse_json(row.get("entitlements_json"), {}) or {},
        "badge_text": row.get("badge_text") or "",
        "accent_color": row.get("accent_color") or "#2F80ED",
        "recommended": bool(row.get("recommended")),
        "active": bool(row["active"]),
        "sort_order": row["sort_order"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def _order_public(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "order_no": row["order_no"],
        "user_id": row["user_id"],
        "username": row.get("username") or "",
        "nickname": row.get("nickname") or "",
        "phone": row.get("phone") or "",
        "plan_id": row["plan_id"],
        "plan_name": row["plan_name"],
        "days": row["days"],
        "amount_cents": row["amount_cents"],
        "tag_style": row["tag_style"],
        "device_limit": int(row.get("device_limit") or 1),
        "benefits": parse_json(row.get("benefits_json"), []) or [],
        "entitlements": parse_json(row.get("entitlements_json"), {}) or {},
        "badge_text": row.get("badge_text") or "",
        "accent_color": row.get("accent_color") or "#2F80ED",
        "status": row["status"],
        "payment_status": row.get("payment_status") or "unpaid",
        "payment_provider": row.get("payment_provider") or "",
        "payment_method": row.get("payment_method") or "",
        "provider_trade_no": row.get("provider_trade_no") or "",
        "payment_payload_type": row.get("payment_payload_type") or "",
        "payment_target": row.get("payment_payload") or "",
        "source": row.get("source") or "manual",
        "device_id": row.get("device_id") or "",
        "device_name": row.get("device_name") or "",
        "created_at": row["created_at"],
        "updated_at": row.get("updated_at") or row["created_at"],
        "expires_at": row.get("expires_at"),
        "paid_at": row.get("paid_at"),
        "cancelled_at": row.get("cancelled_at"),
    }


def _order_select(where: str = "") -> str:
    return (
        "SELECT vip_orders.*, users.username, users.nickname, users.phone, "
        "COALESCE(devices.name, '') AS device_name FROM vip_orders "
        "JOIN users ON users.id = vip_orders.user_id "
        "LEFT JOIN devices ON devices.device_id = vip_orders.device_id "
        + where
    )


async def _push_membership_for_app(
    app: Any, user_id: int, fallback_base_url: str = ""
) -> dict[str, Any]:
    user = app.state.db.fetch_one("SELECT * FROM users WHERE id = ?", (user_id,))
    if user is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user not found")
    base_url = app.state.settings.public_base_url or fallback_base_url.rstrip("/")
    owner = user_public(user, base_url)
    devices = app.state.db.fetch_all(
        "SELECT device_id FROM devices "
        "WHERE owner_user_id = ? AND status = 'approved' AND enabled = 1",
        (user_id,),
    )
    for device in devices:
        await app.state.hub.send_device(
            device["device_id"], {"type": "binding_updated", "owner": owner}
        )
    await app.state.hub.broadcast_control(
        "*", {"type": "owner_profile_updated", "owner_user_id": user_id}
    )
    return owner


async def _push_membership(request: Request, user_id: int) -> dict[str, Any]:
    return await _push_membership_for_app(request.app, user_id, str(request.base_url))


def _device_owner(request: Request, device: Mapping[str, Any]) -> dict[str, Any]:
    if device.get("status") != "approved" or not bool(device.get("enabled")):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "电视绑定尚未审批通过")
    owner_id = device.get("owner_user_id")
    if not owner_id:
        raise HTTPException(status.HTTP_409_CONFLICT, "电视尚未绑定账号")
    owner = request.app.state.db.fetch_one("SELECT * FROM users WHERE id = ? AND active = 1", (owner_id,))
    if owner is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "绑定账号不可用")
    return owner


def _new_order_no(now: int) -> str:
    return time.strftime("VIP%Y%m%d", time.localtime(now)) + secrets.token_hex(5).upper()


def _create_order_row(
    request: Request,
    *,
    plan: Mapping[str, Any],
    user_id: int,
    created_by: int | None,
    source: str,
    device_id: str = "",
    payment_method: str = "",
    client_ip: str = "",
) -> int:
    now = int(time.time())
    return request.app.state.db.execute(
        """
        INSERT INTO vip_orders(
          order_no, user_id, plan_id, plan_name, days, amount_cents, tag_style,
          device_limit, benefits_json, entitlements_json, badge_text, accent_color,
          status, payment_status, payment_provider, payment_method, source, device_id,
          client_ip, created_at, updated_at, expires_at, created_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'unpaid', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            _new_order_no(now), user_id, plan["id"], plan["name"], plan["days"], plan["price_cents"],
            plan["tag_style"], int(plan.get("device_limit") or 1),
            plan.get("benefits_json") or "[]", plan.get("entitlements_json") or "{}",
            plan.get("badge_text") or "", plan.get("accent_color") or "#2F80ED",
            "epay" if payment_method else "", payment_method, source, device_id,
            client_ip, now, now, now + 15 * 60 if payment_method else None, created_by,
        ),
    )


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else "127.0.0.1"


async def _start_payment(
    payload: VipPaymentCreateRequest,
    request: Request,
    user: Mapping[str, Any],
    source: str,
    forced_device_id: str = "",
) -> dict[str, Any]:
    settings = _settings(request)
    if not settings.get("sales_enabled") or not settings.get("payment_enabled"):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "在线购买暂未开启")
    if payload.payment_method not in _payment_methods(settings):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "该支付方式未在后台启用")
    plan = request.app.state.db.fetch_one(
        "SELECT * FROM vip_plans WHERE id = ? AND active = 1", (payload.plan_id,)
    )
    if plan is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "VIP plan not found")
    if int(plan["price_cents"]) <= 0:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "免费套餐请使用后台授权或兑换码发放")
    device_id = forced_device_id or payload.device_id
    if device_id:
        device = request.app.state.db.fetch_one(
            "SELECT * FROM devices WHERE device_id = ?", (device_id,)
        )
        if device is None or not user_has_device_access(request.app.state.db, device, dict(user)):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "当前账号未绑定该电视")
    pid = str(settings.get("epay_pid") or "").strip()
    merchant_key = str(settings.get("epay_key") or "").strip()
    base_url = str(settings.get("epay_base_url") or "https://pay.id0.cn").rstrip("/")
    if not pid or not merchant_key:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "支付商户尚未配置完整")
    order_id = _create_order_row(
        request,
        plan=plan,
        user_id=int(user["id"]),
        created_by=int(user["id"]),
        source=source,
        device_id=device_id,
        payment_method=payload.payment_method,
        client_ip=_client_ip(request),
    )
    order = request.app.state.db.fetch_one("SELECT * FROM vip_orders WHERE id = ?", (order_id,))
    assert order is not None
    notify_url = str(settings.get("epay_notify_url") or "").strip() or f"{_base(request)}/api/vip/payments/epay/notify"
    return_url = str(settings.get("epay_return_url") or "").strip()
    try:
        provider = await create_payment(
            base_url=base_url,
            pid=pid,
            merchant_key=merchant_key,
            order_no=order["order_no"],
            payment_method=payload.payment_method,
            name=plan["name"],
            amount_cents=int(plan["price_cents"]),
            notify_url=notify_url,
            return_url=return_url,
            client_ip=_client_ip(request),
            param=device_id,
        )
    except httpx.TimeoutException as exc:
        detail = "支付平台连接超时"
        now = int(time.time())
        request.app.state.db.execute(
            "UPDATE vip_orders SET updated_at = ? WHERE id = ?",
            (now, order_id),
        )
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, detail) from exc
    except (httpx.HTTPError, RuntimeError, ValueError) as exc:
        detail = str(exc).replace(merchant_key, "***")[:240] or "支付平台拒绝创建订单"
        now = int(time.time())
        request.app.state.db.execute(
            "UPDATE vip_orders SET status = 'cancelled', payment_status = 'failed', cancelled_at = ?, updated_at = ? WHERE id = ?",
            (now, now, order_id),
        )
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, detail) from exc
    now = int(time.time())
    request.app.state.db.execute(
        """
        UPDATE vip_orders SET provider_trade_no = ?, payment_payload_type = ?,
          payment_payload = ?, provider_response_json = ?, updated_at = ? WHERE id = ?
        """,
        (
            provider["trade_no"], provider["payload_type"], provider["payment_target"],
            _json(provider["provider_response"]), now, order_id,
        ),
    )
    result = request.app.state.db.fetch_one(_order_select("WHERE vip_orders.id = ?"), (order_id,))
    assert result is not None
    return _order_public(result)


def _redeem_for_user(payload: VipCodeRedeemRequest, request: Request, user_id: int) -> tuple[int, int]:
    if not _sales_enabled(request):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "VIP sales are disabled")
    now = int(time.time())
    digest = hashlib.sha256(payload.code.encode("ascii", "ignore")).hexdigest()
    with request.app.state.db.transaction() as connection:
        code = connection.execute("SELECT * FROM vip_codes WHERE code_hash = ?", (digest,)).fetchone()
        if code is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "redemption code is invalid")
        if code["status"] != "unused":
            raise HTTPException(status.HTTP_409_CONFLICT, "redemption code has already been used")
        expires_at = grant_membership(
            connection, user_id, code, now, source="code", source_id=int(code["id"])
        )
        updated = connection.execute(
            "UPDATE vip_codes SET status = 'redeemed', redeemed_at = ?, redeemed_by = ? WHERE id = ? AND status = 'unused'",
            (now, user_id, code["id"]),
        )
        if updated.rowcount != 1:
            raise HTTPException(status.HTTP_409_CONFLICT, "redemption code has already been used")
    return expires_at, user_id


@router.get("/status")
def vip_status(request: Request, user: dict = Depends(get_current_user)) -> dict:
    settings = _settings(request)
    return {
        "sales_enabled": bool(settings.get("sales_enabled")),
        "payment_enabled": bool(settings.get("payment_enabled")),
        "payment_methods": _payment_methods(settings),
        "user": user_public(user, _base(request)),
    }


@router.get("/device/status")
def device_vip_status(request: Request, device: dict = Depends(get_authenticated_device)) -> dict:
    owner = _device_owner(request, device)
    settings = _settings(request)
    return {
        "online": True,
        "sales_enabled": bool(settings.get("sales_enabled")),
        "payment_enabled": bool(settings.get("payment_enabled")),
        "payment_methods": _payment_methods(settings),
        "owner": user_public(owner, _base(request)),
    }


@router.get("/plans")
def list_plans(request: Request, user: dict = Depends(get_current_user)) -> list[dict]:
    where = "" if user["role"] == "admin" else "WHERE active = 1"
    rows = request.app.state.db.fetch_all(f"SELECT * FROM vip_plans {where} ORDER BY sort_order, id")
    return [_plan_public(row) for row in rows]


@router.get("/device/plans")
def list_device_plans(request: Request, device: dict = Depends(get_authenticated_device)) -> list[dict]:
    _device_owner(request, device)
    if not _sales_enabled(request):
        return []
    rows = request.app.state.db.fetch_all("SELECT * FROM vip_plans WHERE active = 1 ORDER BY sort_order, id")
    return [_plan_public(row) for row in rows]


@router.post("/plans", status_code=status.HTTP_201_CREATED)
def create_plan(payload: VipPlanCreateRequest, request: Request, _admin: dict = Depends(require_admin)) -> dict:
    now = int(time.time())
    plan_id = request.app.state.db.execute(
        """
        INSERT INTO vip_plans(
          name, days, price_cents, tag_style, description, device_limit, benefits_json,
          entitlements_json, badge_text, accent_color, recommended, active, sort_order,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            payload.name, payload.days, payload.price_cents, payload.tag_style, payload.description,
            payload.device_limit, _json(payload.benefits), _json(payload.entitlements), payload.badge_text,
            payload.accent_color.upper(), int(payload.recommended), int(payload.active), payload.sort_order,
            now, now,
        ),
    )
    row = request.app.state.db.fetch_one("SELECT * FROM vip_plans WHERE id = ?", (plan_id,))
    assert row is not None
    return _plan_public(row)


@router.patch("/plans/{plan_id}")
def update_plan(plan_id: int, payload: VipPlanUpdateRequest, request: Request, _admin: dict = Depends(require_admin)) -> dict:
    row = request.app.state.db.fetch_one("SELECT * FROM vip_plans WHERE id = ?", (plan_id,))
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "VIP plan not found")
    values = payload.model_dump(exclude_unset=True)
    if "benefits" in values:
        values["benefits_json"] = _json(values.pop("benefits"))
    if "entitlements" in values:
        values["entitlements_json"] = _json(values.pop("entitlements"))
    if "accent_color" in values and values["accent_color"]:
        values["accent_color"] = values["accent_color"].upper()
    if values:
        columns = ", ".join(f"{key} = ?" for key in values)
        request.app.state.db.execute(
            f"UPDATE vip_plans SET {columns}, updated_at = ? WHERE id = ?",
            (*[int(value) if isinstance(value, bool) else value for value in values.values()], int(time.time()), plan_id),
        )
    updated = request.app.state.db.fetch_one("SELECT * FROM vip_plans WHERE id = ?", (plan_id,))
    assert updated is not None
    return _plan_public(updated)


@router.delete("/plans/{plan_id}")
def deactivate_plan(plan_id: int, request: Request, _admin: dict = Depends(require_admin)) -> dict:
    request.app.state.db.execute("UPDATE vip_plans SET active = 0, updated_at = ? WHERE id = ?", (int(time.time()), plan_id))
    return {"deactivated": True, "plan_id": plan_id}


@router.get("/orders")
def list_orders(request: Request, user: dict = Depends(get_current_user)) -> list[dict]:
    params = () if user["role"] == "admin" else (user["id"],)
    where = "" if user["role"] == "admin" else "WHERE vip_orders.user_id = ? "
    rows = request.app.state.db.fetch_all(_order_select(where) + "ORDER BY vip_orders.created_at DESC, vip_orders.id DESC", params)
    return [_order_public(row) for row in rows]


@router.post("/orders", status_code=status.HTTP_201_CREATED)
def create_order(payload: VipOrderCreateRequest, request: Request, user: dict = Depends(get_current_user)) -> dict:
    if user["role"] != "admin" and not _sales_enabled(request):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "VIP sales are disabled")
    target_id = payload.user_id if user["role"] == "admin" and payload.user_id else user["id"]
    if user["role"] != "admin" and payload.user_id not in (None, user["id"]):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "cannot create an order for another user")
    target = request.app.state.db.fetch_one("SELECT id FROM users WHERE id = ?", (target_id,))
    if target is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user not found")
    plan = request.app.state.db.fetch_one("SELECT * FROM vip_plans WHERE id = ? AND active = 1", (payload.plan_id,))
    if plan is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "VIP plan not found")
    order_id = _create_order_row(request, plan=plan, user_id=target_id, created_by=user["id"], source="manual")
    row = request.app.state.db.fetch_one(_order_select("WHERE vip_orders.id = ?"), (order_id,))
    assert row is not None
    return _order_public(row)


@router.post("/payments", status_code=status.HTTP_201_CREATED)
async def start_user_payment(payload: VipPaymentCreateRequest, request: Request, user: dict = Depends(get_current_user)) -> dict:
    return await _start_payment(payload, request, user, "web")


@router.post("/device/payments", status_code=status.HTTP_201_CREATED)
async def start_device_payment(payload: VipPaymentCreateRequest, request: Request, device: dict = Depends(get_authenticated_device)) -> dict:
    owner = _device_owner(request, device)
    return await _start_payment(payload, request, owner, "tv", device["device_id"])


@router.get("/device/payments/{order_no}")
async def device_payment_status(order_no: str, request: Request, device: dict = Depends(get_authenticated_device)) -> dict:
    owner = _device_owner(request, device)
    row = request.app.state.db.fetch_one(
        _order_select("WHERE vip_orders.order_no = ? AND vip_orders.user_id = ? AND vip_orders.device_id = ?"),
        (order_no, owner["id"], device["device_id"]),
    )
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "payment order not found")
    await reconcile_payment_order(request.app, order_no, str(request.base_url))
    row = request.app.state.db.fetch_one(
        _order_select("WHERE vip_orders.order_no = ? AND vip_orders.user_id = ? AND vip_orders.device_id = ?"),
        (order_no, owner["id"], device["device_id"]),
    )
    assert row is not None
    return _order_public(row)


@router.get("/payments/{order_no}")
async def user_payment_status(order_no: str, request: Request, user: dict = Depends(get_current_user)) -> dict:
    where = "WHERE vip_orders.order_no = ?" if user["role"] == "admin" else "WHERE vip_orders.order_no = ? AND vip_orders.user_id = ?"
    params = (order_no,) if user["role"] == "admin" else (order_no, user["id"])
    row = request.app.state.db.fetch_one(_order_select(where), params)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "payment order not found")
    await reconcile_payment_order(request.app, order_no, str(request.base_url))
    row = request.app.state.db.fetch_one(_order_select(where), params)
    assert row is not None
    return _order_public(row)


def _payment_cents(value: Any) -> int:
    try:
        paid_amount = Decimal(str(value or ""))
        scaled = paid_amount * 100
        if paid_amount.as_tuple().exponent < -2 or scaled != scaled.to_integral_value():
            raise ValueError("amount has more than two decimals")
        return int(scaled)
    except (InvalidOperation, ValueError) as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "invalid payment amount") from exc


async def _settle_verified_order(
    app: Any, values: Mapping[str, Any], fallback_base_url: str = ""
) -> bool:
    settings = _settings_for_app(app)
    pid = str(settings.get("epay_pid") or "")
    if not pid or str(values.get("pid") or "") != pid:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "payment merchant does not match")
    order_no = str(values.get("out_trade_no") or "")
    with app.state.db.transaction() as connection:
        order = connection.execute("SELECT * FROM vip_orders WHERE order_no = ?", (order_no,)).fetchone()
        if order is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "payment order not found")
        paid_cents = _payment_cents(values.get("money"))
        if (
            paid_cents != int(order["amount_cents"])
            or str(values.get("name") or "") != order["plan_name"]
            or str(values.get("type") or "") != order["payment_method"]
            or (order["device_id"] and str(values.get("param") or "") != order["device_id"])
            or (order["provider_trade_no"] and str(values.get("trade_no") or "") != order["provider_trade_no"])
        ):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "payment details do not match the order")
        if order["status"] == "paid" and order["payment_status"] == "paid":
            return False
        if order["status"] != "pending":
            raise HTTPException(status.HTTP_409_CONFLICT, "order cannot be paid")
        now = int(time.time())
        grant_membership(
            connection, order["user_id"], order, now,
            source="order", source_id=int(order["id"]),
        )
        connection.execute(
            """
            UPDATE vip_orders SET status = 'paid', payment_status = 'paid', paid_at = ?,
              paid_amount_cents = ?, provider_trade_no = ?, updated_at = ? WHERE id = ?
            """,
            (now, paid_cents, str(values.get("trade_no") or order["provider_trade_no"]), now, order["id"]),
        )
        owner_id = int(order["user_id"])
    await _push_membership_for_app(app, owner_id, fallback_base_url)
    return True


async def reconcile_payment_order(
    app: Any, order_no: str, fallback_base_url: str = "", *, force: bool = False
) -> bool:
    order = app.state.db.fetch_one(
        "SELECT * FROM vip_orders WHERE order_no = ? AND payment_provider = 'epay'",
        (order_no,),
    )
    if order is None or order["status"] != "pending":
        return False
    now = int(time.time())
    if not force and int(order.get("updated_at") or 0) > now - 3:
        return False
    app.state.db.execute(
        "UPDATE vip_orders SET updated_at = ? WHERE id = ? AND status = 'pending'",
        (now, order["id"]),
    )
    settings = _settings_for_app(app)
    pid = str(settings.get("epay_pid") or "").strip()
    merchant_key = str(settings.get("epay_key") or "").strip()
    if not pid or not merchant_key:
        return False
    try:
        provider_order = await query_order(
            str(settings.get("epay_base_url") or "https://pay.id0.cn").rstrip("/"),
            pid,
            merchant_key,
            order_no,
        )
        if provider_order is None or int(provider_order.get("status") or 0) != 1:
            return False
        return await _settle_verified_order(app, provider_order, fallback_base_url)
    except HTTPException as exc:
        logger.warning("payment reconciliation rejected for %s: %s", order_no, exc.detail)
    except (httpx.HTTPError, RuntimeError, ValueError) as exc:
        logger.warning("payment reconciliation unavailable for %s: %s", order_no, type(exc).__name__)
    return False


async def payment_reconciliation_worker(app: Any) -> None:
    while True:
        await asyncio.sleep(5)
        now = int(time.time())
        orders = app.state.db.fetch_all(
            """
            SELECT order_no FROM vip_orders
            WHERE status = 'pending' AND payment_provider = 'epay'
              AND created_at >= ? AND updated_at <= ?
            ORDER BY created_at ASC LIMIT 10
            """,
            (now - 86400, now - 5),
        )
        for order in orders:
            try:
                await reconcile_payment_order(app, order["order_no"], force=True)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning(
                    "payment reconciliation failed for %s: %s",
                    order["order_no"], type(exc).__name__,
                )


async def _accept_epay_notification(
    request: Request, values: Mapping[str, str]
) -> PlainTextResponse:
    settings = _settings(request)
    merchant_key = str(settings.get("epay_key") or "")
    pid = str(settings.get("epay_pid") or "")
    if not merchant_key or not pid or values.get("pid") != pid or not verify_signature(values, merchant_key):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "invalid payment signature")
    if values.get("trade_status") != "TRADE_SUCCESS":
        raise HTTPException(status.HTTP_409_CONFLICT, "payment has not succeeded")
    await _settle_verified_order(request.app, values, str(request.base_url))
    return PlainTextResponse("success")


@router.get("/payments/epay/notify", response_class=PlainTextResponse, include_in_schema=False)
async def epay_notify(request: Request) -> PlainTextResponse:
    return await _accept_epay_notification(request, dict(request.query_params))


@router.post("/payments/epay/notify", response_class=PlainTextResponse, include_in_schema=False)
async def epay_notify_post(request: Request) -> PlainTextResponse:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/x-www-form-urlencoded":
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "unsupported callback content type")
    try:
        values = dict(parse_qsl((await request.body()).decode("utf-8"), keep_blank_values=True))
    except UnicodeDecodeError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "invalid callback encoding") from exc
    return await _accept_epay_notification(request, values)


@router.post("/orders/{order_id}/review")
async def review_order(order_id: int, payload: VipOrderReviewRequest, request: Request, admin: dict = Depends(require_admin)) -> dict:
    now = int(time.time())
    with request.app.state.db.transaction() as connection:
        order = connection.execute("SELECT * FROM vip_orders WHERE id = ?", (order_id,)).fetchone()
        if order is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "VIP order not found")
        if order["status"] != "pending":
            raise HTTPException(status.HTTP_409_CONFLICT, "VIP order has already been reviewed")
        if payload.status == "paid":
            grant_membership(
                connection, order["user_id"], order, now,
                source="order", source_id=int(order["id"]),
            )
            connection.execute(
                "UPDATE vip_orders SET status = 'paid', payment_status = 'paid', paid_at = ?, updated_at = ?, reviewed_by = ? WHERE id = ?",
                (now, now, admin["id"], order_id),
            )
        else:
            connection.execute(
                "UPDATE vip_orders SET status = 'cancelled', cancelled_at = ?, updated_at = ?, reviewed_by = ? WHERE id = ?",
                (now, now, admin["id"], order_id),
            )
        owner_id = int(order["user_id"])
    if payload.status == "paid":
        await _push_membership(request, owner_id)
    row = request.app.state.db.fetch_one(_order_select("WHERE vip_orders.id = ?"), (order_id,))
    assert row is not None
    return _order_public(row)


@router.get("/grants")
def list_membership_grants(
    request: Request, _admin: dict = Depends(require_admin)
) -> list[dict[str, Any]]:
    rows = request.app.state.db.fetch_all(
        """
        SELECT user_vip_grants.*, users.username, users.nickname, users.phone
        FROM user_vip_grants JOIN users ON users.id = user_vip_grants.user_id
        WHERE user_vip_grants.status = 'active'
        ORDER BY user_vip_grants.user_id, user_vip_grants.granted_at DESC,
          user_vip_grants.id DESC
        """
    )
    return [
        {
            **grant_public(row),
            "username": row.get("username") or "",
            "nickname": row.get("nickname") or "",
            "phone": row.get("phone") or "",
        }
        for row in rows
    ]


@router.delete("/users/{user_id}/grants/{grant_id}")
async def revoke_grant(
    user_id: int,
    grant_id: int,
    request: Request,
    admin: dict = Depends(require_admin),
) -> dict[str, Any]:
    now = int(time.time())
    with request.app.state.db.transaction() as connection:
        revoked = revoke_membership_grant(connection, user_id, grant_id, admin["id"], now)
    owner = await _push_membership(request, user_id)
    return {"revoked": revoked, "user": owner}


@router.get("/codes")
def list_codes(request: Request, _admin: dict = Depends(require_admin)) -> list[dict]:
    rows = request.app.state.db.fetch_all(
        """
        SELECT vip_codes.id, vip_codes.code_hint, vip_codes.plan_id, vip_codes.plan_name,
          vip_codes.days, vip_codes.tag_style, vip_codes.device_limit, vip_codes.benefits_json,
          vip_codes.entitlements_json, vip_codes.badge_text, vip_codes.accent_color,
          vip_codes.status, vip_codes.created_at, vip_codes.redeemed_at,
          vip_codes.redeemed_by, users.username AS redeemed_username
        FROM vip_codes LEFT JOIN users ON users.id = vip_codes.redeemed_by
        ORDER BY vip_codes.created_at DESC, vip_codes.id DESC
        """
    )
    for row in rows:
        row["benefits"] = parse_json(row.pop("benefits_json"), []) or []
        row["entitlements"] = parse_json(row.pop("entitlements_json"), {}) or {}
    return rows


@router.post("/codes", status_code=status.HTTP_201_CREATED)
def create_codes(payload: VipCodeCreateRequest, request: Request, admin: dict = Depends(require_admin)) -> list[dict]:
    plan = request.app.state.db.fetch_one("SELECT * FROM vip_plans WHERE id = ?", (payload.plan_id,))
    if plan is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "VIP plan not found")
    now = int(time.time())
    created = []
    with request.app.state.db.transaction() as connection:
        for _ in range(payload.count):
            raw = "STAR-" + secrets.token_hex(8).upper()
            code_hash = hashlib.sha256(raw.encode("ascii")).hexdigest()
            hint = raw[:9] + "..." + raw[-4:]
            cursor = connection.execute(
                """
                INSERT INTO vip_codes(
                  code_hash, code_hint, plan_id, plan_name, days, tag_style, device_limit,
                  benefits_json, entitlements_json, badge_text, accent_color, created_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    code_hash, hint, plan["id"], plan["name"], plan["days"], plan["tag_style"],
                    int(plan.get("device_limit") or 1), plan.get("benefits_json") or "[]",
                    plan.get("entitlements_json") or "{}", plan.get("badge_text") or "",
                    plan.get("accent_color") or "#2F80ED", now, admin["id"],
                ),
            )
            created.append({
                "id": cursor.lastrowid,
                "code": raw,
                "code_hint": hint,
                "plan_name": plan["name"],
                "days": plan["days"],
                "device_limit": int(plan.get("device_limit") or 1),
                "benefits": parse_json(plan.get("benefits_json"), []) or [],
                "entitlements": parse_json(plan.get("entitlements_json"), {}) or {},
                "tag_style": plan["tag_style"],
                "redeem_uri": "starlight-tvcast://vip?server=" + quote(_base(request), safe="") + "&code=" + quote(raw, safe=""),
            })
    return created


@router.post("/redeem")
async def redeem_code(payload: VipCodeRedeemRequest, request: Request, user: dict = Depends(get_current_user)) -> dict:
    expires_at, user_id = _redeem_for_user(payload, request, user["id"])
    owner = await _push_membership(request, user_id)
    return {"redeemed": True, "expires_at": expires_at, "user": owner}


@router.post("/device/redeem")
async def redeem_device_code(payload: VipCodeRedeemRequest, request: Request, device: dict = Depends(get_authenticated_device)) -> dict:
    owner = _device_owner(request, device)
    expires_at, user_id = _redeem_for_user(payload, request, owner["id"])
    public_owner = await _push_membership(request, user_id)
    return {"redeemed": True, "expires_at": expires_at, "user": public_owner}
