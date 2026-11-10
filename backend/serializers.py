from __future__ import annotations

import json
import time
from ipaddress import ip_address
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from entitlements import FREE_BENEFITS, effective_entitlements, entitlement_catalog, paid_membership_active


def parse_json(value: str | None, fallback: Any = None) -> Any:
    if value is None:
        return fallback
    try:
        return json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return fallback


def user_public(row: dict[str, Any], base_url: str = "") -> dict[str, Any]:
    avatar_updated_at = int(row.get("avatar_updated_at") or 0)
    avatar_url = ""
    if row.get("avatar_mime") and avatar_updated_at:
        avatar_url = f"{base_url}/api/users/{row['id']}/avatar?v={avatar_updated_at}"
    vip_expires_at = int(row.get("vip_expires_at") or 0)
    vip_active = paid_membership_active(row)
    stored_benefits = parse_json(row.get("vip_benefits_json"), []) or []
    benefits = list(FREE_BENEFITS)
    for benefit in stored_benefits if vip_active else []:
        if benefit not in benefits:
            benefits.append(benefit)
    stored_plans = parse_json(row.get("vip_plan_names_json"), []) or []
    active_plans = stored_plans if vip_active and stored_plans else ["免费会员"]
    return {
        "id": row["id"],
        "username": row["username"],
        "nickname": row.get("nickname") or "",
        "phone": row.get("phone") or "",
        "avatar_url": avatar_url,
        "membership_tier": "admin" if row.get("role") == "admin" else "vip" if vip_active else "free",
        "membership_name": "超级管理员" if row.get("role") == "admin" else (row.get("vip_plan_name") or "VIP") if vip_active else "免费会员",
        "vip_active": vip_active,
        "vip_expires_at": vip_expires_at,
        "vip_tag_style": row.get("vip_tag_style") or "sky",
        "vip_plan_name": (row.get("vip_plan_name") or "VIP") if vip_active else "免费会员",
        "vip_active_plans": active_plans,
        "vip_device_limit": int(row.get("vip_device_limit") or 1),
        "vip_benefits": benefits,
        "vip_entitlements": effective_entitlements(row),
        "entitlement_catalog": entitlement_catalog(row),
        "vip_badge_text": (row.get("vip_badge_text") or "VIP") if vip_active else "免费",
        "vip_accent_color": row.get("vip_accent_color") or "#2F80ED",
        "role": row["role"],
        "active": bool(row.get("active", True)),
        "created_at": row.get("created_at"),
        "device_count": int(row.get("device_count", 0)),
        "pending_count": int(row.get("pending_count", 0)),
    }


def sanitize_media_url(value: Any) -> str:
    if not isinstance(value, str) or not value:
        return ""
    try:
        parts = urlsplit(value)
        if parts.scheme not in {"http", "https"}:
            return ""
        return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
    except ValueError:
        return ""


def normalized_nat_type(value: Any, lan_ip: Any) -> str:
    nat_type = str(value or "").strip().lower()
    if nat_type not in {"", "unknown"}:
        return nat_type
    try:
        if ip_address(str(lan_ip or "")).is_private:
            return "not_tested"
    except ValueError:
        pass
    return nat_type or "not_tested"


def device_public(row: dict[str, Any], online: bool = False) -> dict[str, Any]:
    metadata = parse_json(row.get("metadata_json"), {}) or {}
    last_seen_at = int(row.get("last_seen_at") or 0)
    heartbeat_age_seconds = max(0, int(time.time()) - last_seen_at) if last_seen_at else None
    missed_heartbeats = (
        heartbeat_age_seconds // 5 if heartbeat_age_seconds is not None else 0
    )
    power_state = (
        "online" if online else "assumed_off" if missed_heartbeats >= 5 else "reconnecting"
    )
    return {
        "id": row["id"],
        "device_id": row["device_id"],
        "name": row["name"],
        "owner_user_id": row.get("owner_user_id"),
        "status": row["status"],
        "enabled": bool(row["enabled"]),
        "online": online,
        "platform": row["platform"],
        "model": row["model"],
        "app_version": row["app_version"],
        "last_seen_at": row.get("last_seen_at"),
        "heartbeat_age_seconds": heartbeat_age_seconds,
        "missed_heartbeats": missed_heartbeats,
        "power_state": power_state,
        "lan_ip": row["lan_ip"],
        "wan_ip": row["wan_ip"],
        "nat_type": normalized_nat_type(row["nat_type"], row["lan_ip"]),
        "public_key": row["public_key"],
        "current_activity": row["current_activity"],
        "playing_url": row["playing_url"],
        "position_ms": row["position_ms"],
        "battery_whitelist": bool(row["battery_whitelist"]),
        "metadata": metadata,
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def command_public(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "device_id": row.get("device_id"),
        "type": row["type"],
        "payload": parse_json(row.get("payload_json"), {}) or {},
        "status": row["status"],
        "result": parse_json(row.get("result_json"), None),
        "created_at": row["created_at"],
        "delivered_at": row.get("delivered_at"),
        "completed_at": row.get("completed_at"),
    }
