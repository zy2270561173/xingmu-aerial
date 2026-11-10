from __future__ import annotations

import hashlib
import hmac
from decimal import Decimal
from typing import Any, Mapping
from urllib.parse import urlsplit

import httpx


PAYMENT_METHODS = {"alipay", "wxpay"}
PAYMENT_PAYLOAD_FIELDS = ("payurl", "qrcode", "urlscheme")


def sign_params(parameters: Mapping[str, Any], merchant_key: str) -> str:
    pairs = []
    for key in sorted(parameters):
        if key in {"sign", "sign_type"}:
            continue
        value = parameters[key]
        if value is None or str(value) == "":
            continue
        pairs.append(f"{key}={value}")
    source = "&".join(pairs) + merchant_key
    return hashlib.md5(source.encode("utf-8")).hexdigest()


def verify_signature(parameters: Mapping[str, Any], merchant_key: str) -> bool:
    received = str(parameters.get("sign") or "").lower()
    return bool(received) and hmac.compare_digest(received, sign_params(parameters, merchant_key))


def money_from_cents(amount_cents: int) -> str:
    return f"{Decimal(amount_cents) / Decimal(100):.2f}"


async def create_payment(
    *,
    base_url: str,
    pid: str,
    merchant_key: str,
    order_no: str,
    payment_method: str,
    name: str,
    amount_cents: int,
    notify_url: str,
    return_url: str,
    client_ip: str,
    param: str = "",
) -> dict[str, Any]:
    if payment_method not in PAYMENT_METHODS:
        raise ValueError("unsupported payment method")
    payload: dict[str, str] = {
        "pid": pid,
        "type": payment_method,
        "out_trade_no": order_no,
        "notify_url": notify_url,
        "return_url": return_url,
        "name": name,
        "money": money_from_cents(amount_cents),
        "clientip": client_ip,
        "device": "jump",
        "param": param,
        "sign_type": "MD5",
    }
    payload["sign"] = sign_params(payload, merchant_key)
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(20.0, connect=6.0), follow_redirects=False
    ) as client:
        response = await client.post(f"{base_url.rstrip('/')}/mapi.php", data=payload)
        response.raise_for_status()
        body = response.json()
    if not isinstance(body, dict) or int(body.get("code") or 0) != 1:
        message = str(body.get("msg") if isinstance(body, dict) else "invalid response")
        raise RuntimeError(message[:240] or "payment provider rejected the request")
    trade_no = str(body.get("trade_no") or "").strip()
    if not trade_no:
        raise RuntimeError("payment provider did not return a trade number")
    available = [(field, str(body.get(field) or "")) for field in PAYMENT_PAYLOAD_FIELDS if body.get(field)]
    if len(available) != 1:
        raise RuntimeError("payment provider did not return exactly one payment target")
    payload_type, payment_target = available[0]
    try:
        target_scheme = urlsplit(payment_target).scheme.lower()
    except ValueError as exc:
        raise RuntimeError("payment provider returned an invalid payment target") from exc
    if target_scheme not in {"http", "https", "alipays", "weixin"}:
        raise RuntimeError("payment provider returned an unsupported payment target")
    return {
        "trade_no": trade_no,
        "payload_type": payload_type,
        "payment_target": payment_target,
        "provider_response": {
            "code": int(body.get("code") or 0),
            "msg": str(body.get("msg") or ""),
            "trade_no": trade_no,
            payload_type: payment_target,
        },
    }


async def test_merchant(base_url: str, pid: str, merchant_key: str) -> dict[str, Any]:
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(15.0, connect=6.0), follow_redirects=False
    ) as client:
        response = await client.get(
            f"{base_url.rstrip('/')}/api.php",
            params={"act": "query", "pid": pid, "key": merchant_key},
        )
        response.raise_for_status()
        body = response.json()
    if not isinstance(body, dict):
        raise RuntimeError("payment provider response is not JSON")
    code = int(body.get("code") or 0)
    if code != 1:
        raise RuntimeError(str(body.get("msg") or "merchant validation failed")[:240])
    returned_pid = str(body.get("pid") or "")
    if returned_pid and returned_pid != pid:
        raise RuntimeError("payment provider returned a different merchant ID")
    return {
        "ok": True,
        "merchant_pid": returned_pid or pid,
        "merchant_name": str(body.get("username") or ""),
        "merchant_active": int(body.get("active") or 0) == 1,
        "merchant_balance": str(body.get("money") or "0.00"),
        "settlement_type": int(body.get("type") or 0),
        "settlement_account": str(body.get("account") or ""),
        "orders": int(body.get("orders") or 0),
        "orders_today": int(body.get("order_today") or 0),
        "orders_lastday": int(body.get("order_lastday") or 0),
    }


async def query_order(
    base_url: str, pid: str, merchant_key: str, out_trade_no: str
) -> dict[str, Any] | None:
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(15.0, connect=6.0), follow_redirects=False
    ) as client:
        response = await client.get(
            f"{base_url.rstrip('/')}/api.php",
            params={
                "act": "order",
                "pid": pid,
                "key": merchant_key,
                "out_trade_no": out_trade_no,
            },
        )
        response.raise_for_status()
        body = response.json()
    if not isinstance(body, dict):
        raise RuntimeError("payment provider order response is not JSON")
    if int(body.get("code") or 0) != 1:
        return None
    return {
        "pid": str(body.get("pid") or ""),
        "trade_no": str(body.get("trade_no") or ""),
        "out_trade_no": str(body.get("out_trade_no") or ""),
        "api_trade_no": str(body.get("api_trade_no") or ""),
        "type": str(body.get("type") or ""),
        "name": str(body.get("name") or ""),
        "money": str(body.get("money") or ""),
        "status": int(body.get("status") or 0),
        "param": str(body.get("param") or ""),
    }
