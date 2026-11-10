from __future__ import annotations

import hashlib
import json
import time

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status

from dependencies import require_admin
from epay import test_merchant
from models import AiConnectionTestRequest, EpayConnectionTestRequest, SettingsUpdateRequest
from serializers import parse_json


router = APIRouter(prefix="/api/settings", tags=["settings"])

PUBLIC_DEFAULTS = {
    "ai_enabled": False,
    "ai_api_url": "",
    "ai_api_model": "",
    "p2p_enabled": True,
    "relay_enabled": True,
    "sales_enabled": False,
    "payment_enabled": False,
    "epay_payment_methods": ["alipay", "wxpay"],
    "epay_base_url": "https://pay.id0.cn",
    "epay_pid": "",
    "epay_notify_url": "",
    "epay_return_url": "",
    "epay_merchant_name": "",
    "epay_merchant_account": "",
    "epay_merchant_active": False,
    "epay_merchant_balance": "",
    "epay_merchant_settlement_type": 0,
    "epay_merchant_orders": 0,
    "epay_merchant_orders_today": 0,
    "epay_merchant_orders_lastday": 0,
    "epay_verified_pid": "",
    "epay_verified_at": 0,
}


def load_settings(request: Request) -> dict:
    rows = request.app.state.db.fetch_all("SELECT key, value_json FROM settings")
    return {row["key"]: parse_json(row["value_json"]) for row in rows}


def public_settings(values: dict) -> dict:
    response = {
        key: values.get(key, default) for key, default in PUBLIC_DEFAULTS.items()
    }
    response["ai_api_key_set"] = bool(values.get("ai_api_key"))
    response["epay_key_set"] = bool(values.get("epay_key"))
    return response


def epay_config_hash(base_url: str, pid: str, merchant_key: str) -> str:
    source = "\0".join((base_url.rstrip("/"), pid.strip(), merchant_key))
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def store_settings(request: Request, values: dict, updated_by: int | None = None) -> None:
    now = int(time.time())
    with request.app.state.db.transaction() as connection:
        for key, value in values.items():
            connection.execute(
                """
                INSERT INTO settings(key, value_json, updated_at, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """,
                (key, json.dumps(value, ensure_ascii=False), now, updated_by),
            )


def upstream_error_message(response: httpx.Response, api_key: str) -> str:
    message = ""
    try:
        body = response.json()
        if isinstance(body, dict):
            error = body.get("error")
            if isinstance(error, dict):
                message = str(error.get("message") or error.get("code") or "")
            elif error:
                message = str(error)
            if not message:
                message = str(body.get("message") or body.get("detail") or "")
    except ValueError:
        message = response.text.strip()
    if api_key:
        message = message.replace(api_key, "***")
    return (message or response.reason_phrase or "unknown upstream error")[:320]


@router.get("")
def get_settings(request: Request, _user: dict = Depends(require_admin)) -> dict:
    return public_settings(load_settings(request))


@router.put("")
async def update_settings(
    payload: SettingsUpdateRequest,
    request: Request,
    user: dict = Depends(require_admin),
) -> dict:
    values = {
        "ai_enabled": payload.ai_enabled,
        "ai_api_url": payload.ai_api_url,
        "ai_api_model": payload.ai_api_model,
        "p2p_enabled": payload.p2p_enabled,
        "relay_enabled": payload.relay_enabled,
        "sales_enabled": payload.sales_enabled,
        "payment_enabled": payload.payment_enabled,
        "epay_payment_methods": payload.epay_payment_methods,
        "epay_base_url": payload.epay_base_url,
        "epay_pid": payload.epay_pid.strip(),
        "epay_notify_url": payload.epay_notify_url,
        "epay_return_url": payload.epay_return_url,
    }
    if payload.clear_ai_api_key:
        values["ai_api_key"] = ""
    elif payload.ai_api_key:
        values["ai_api_key"] = payload.ai_api_key
    if payload.clear_epay_key:
        values["epay_key"] = ""
    elif payload.epay_key:
        values["epay_key"] = payload.epay_key.strip()
    stored = load_settings(request)
    effective_epay_key = str(values.get("epay_key", stored.get("epay_key") or ""))
    if payload.payment_enabled and (not payload.epay_pid.strip() or not effective_epay_key):
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "启用在线支付前必须填写商户 PID 和商户密钥",
        )
    new_hash = epay_config_hash(payload.epay_base_url, payload.epay_pid, effective_epay_key) if effective_epay_key else ""
    if payload.payment_enabled and new_hash != str(stored.get("epay_verified_config_hash") or ""):
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "请先点击验证商户配置，验证成功后再启用在线购买",
        )
    if payload.payment_enabled and not bool(stored.get("epay_merchant_active")):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "该易支付商户当前不可用")
    if new_hash != str(stored.get("epay_verified_config_hash") or ""):
        values.update({
            "epay_merchant_name": "", "epay_merchant_account": "",
            "epay_merchant_active": False, "epay_merchant_balance": "",
            "epay_merchant_settlement_type": 0, "epay_merchant_orders": 0,
            "epay_merchant_orders_today": 0, "epay_merchant_orders_lastday": 0,
            "epay_verified_pid": "", "epay_verified_at": 0,
            "epay_verified_config_hash": "",
        })
    store_settings(request, values, user["id"])
    response = public_settings({**load_settings(request), **values})
    await request.app.state.hub.broadcast_control(
        "*", {"type": "settings_updated", "settings": response}
    )
    return response


@router.post("/payment/test")
async def test_epay_connection(
    payload: EpayConnectionTestRequest,
    request: Request,
    user: dict = Depends(require_admin),
) -> dict:
    stored = load_settings(request)
    merchant_key = (payload.epay_key or "").strip()
    if not merchant_key and payload.use_stored_epay_key:
        merchant_key = str(stored.get("epay_key") or "")
    if not merchant_key:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "请填写商户密钥")
    started = time.perf_counter()
    try:
        result = await test_merchant(payload.epay_base_url, payload.epay_pid, merchant_key)
    except httpx.TimeoutException:
        return {"ok": False, "message": "支付接口连接超时", "latency_ms": None}
    except (httpx.RequestError, httpx.HTTPStatusError, ValueError) as exc:
        message = str(exc).replace(merchant_key, "***")
        return {"ok": False, "message": f"无法连接支付接口：{message[:240]}", "latency_ms": None}
    except RuntimeError as exc:
        message = str(exc).replace(merchant_key, "***")
        return {"ok": False, "message": message[:240], "latency_ms": None}
    verified_at = int(time.time())
    merchant_values = {
        "epay_merchant_name": result["merchant_name"],
        "epay_merchant_account": result["settlement_account"],
        "epay_merchant_active": result["merchant_active"],
        "epay_merchant_balance": result["merchant_balance"],
        "epay_merchant_settlement_type": result["settlement_type"],
        "epay_merchant_orders": result["orders"],
        "epay_merchant_orders_today": result["orders_today"],
        "epay_merchant_orders_lastday": result["orders_lastday"],
        "epay_verified_pid": result["merchant_pid"],
        "epay_verified_at": verified_at,
        "epay_verified_config_hash": epay_config_hash(
            payload.epay_base_url, payload.epay_pid, merchant_key
        ),
    }
    store_settings(request, merchant_values, user["id"])
    merchant_active = bool(result["merchant_active"])
    return {
        **result,
        "ok": merchant_active,
        "message": "易支付商户验证成功" if merchant_active else "商户存在，但当前状态为封禁",
        "verified_at": verified_at,
        "latency_ms": max(1, round((time.perf_counter() - started) * 1000)),
    }


@router.post("/ai/test")
async def test_ai_connection(
    payload: AiConnectionTestRequest,
    request: Request,
    _user: dict = Depends(require_admin),
) -> dict:
    stored = load_settings(request)
    api_key = payload.ai_api_key or ""
    if not api_key and payload.use_stored_api_key:
        api_key = str(stored.get("ai_api_key") or "")

    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    endpoint = f"{payload.ai_api_url}/chat/completions"
    started = time.perf_counter()
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(20.0, connect=6.0), follow_redirects=False
        ) as client:
            response = await client.post(
                endpoint,
                headers=headers,
                json={
                    "model": payload.ai_api_model,
                    "messages": [{"role": "user", "content": "Reply with OK."}],
                    "max_tokens": 1,
                    "stream": False,
                },
            )
    except httpx.TimeoutException:
        return {"ok": False, "message": "API request timed out", "latency_ms": None}
    except httpx.RequestError as exc:
        message = str(exc)
        if api_key:
            message = message.replace(api_key, "***")
        return {
            "ok": False,
            "message": f"Cannot connect to API URL: {message[:260]}",
            "latency_ms": None,
        }

    latency_ms = max(1, round((time.perf_counter() - started) * 1000))
    if response.is_error:
        return {
            "ok": False,
            "message": f"Upstream HTTP {response.status_code}: {upstream_error_message(response, api_key)}",
            "latency_ms": latency_ms,
        }
    try:
        body = response.json()
    except ValueError:
        return {
            "ok": False,
            "message": "API responded, but the body is not valid JSON",
            "latency_ms": latency_ms,
        }
    if not isinstance(body, dict) or not isinstance(body.get("choices"), list):
        return {
            "ok": False,
            "message": "API responded, but it is not compatible with Chat Completions",
            "latency_ms": latency_ms,
        }
    return {
        "ok": True,
        "message": "AI API connection succeeded",
        "latency_ms": latency_ms,
        "model": str(body.get("model") or payload.ai_api_model),
    }
