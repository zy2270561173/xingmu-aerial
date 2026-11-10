from __future__ import annotations

import json
import time
from typing import Any, Mapping


FREE_BENEFITS = [
    "最多 1 台电视",
    "基础局域网投屏",
    "网络视频、手机本地视频和照片",
    "局域网 APK 上传安装",
]

ENTITLEMENT_DEFINITIONS = (
    {
        "key": "lan_cast",
        "label": "基础局域网投屏",
        "description": "同一局域网内投网络视频、手机本地视频和照片",
        "free": True,
        "configurable": False,
    },
    {
        "key": "lan_app_install",
        "label": "局域网安装应用",
        "description": "同一局域网内上传 APK 并通知电视安装",
        "free": True,
        "configurable": False,
    },
    {
        "key": "screen_view",
        "label": "远程电视画面",
        "description": "在手机 App 或 Web 后台实时查看电视画面",
        "free": False,
        "configurable": True,
    },
    {
        "key": "remote_cast",
        "label": "公网远程投屏",
        "description": "不在同一局域网时向电视投放视频、图片或幻灯片",
        "free": False,
        "configurable": True,
    },
    {
        "key": "remote_control",
        "label": "公网远程控制",
        "description": "不在同一局域网时控制按键、应用、设置和电源",
        "free": False,
        "configurable": True,
    },
    {
        "key": "app_install",
        "label": "公网远程安装应用",
        "description": "通过公网向远程电视上传并安装 APK",
        "free": False,
        "configurable": True,
    },
    {
        "key": "p2p_access",
        "label": "P2P 点对点通道",
        "description": "跨公网时申请认证并优先由手机与电视直连",
        "free": False,
        "configurable": True,
    },
    {
        "key": "relay_access",
        "label": "后端回退通道",
        "description": "P2P 不可用时使用后端信令、画面和控制回退",
        "free": False,
        "configurable": True,
    },
)

CONFIGURABLE_ENTITLEMENTS = {
    item["key"] for item in ENTITLEMENT_DEFINITIONS if item["configurable"]
}


def _parse_entitlements(value: Any) -> dict[str, bool]:
    if isinstance(value, Mapping):
        return {str(key): bool(enabled) for key, enabled in value.items()}
    try:
        parsed = json.loads(value or "{}")
    except (TypeError, json.JSONDecodeError):
        return {}
    if not isinstance(parsed, dict):
        return {}
    return {str(key): bool(enabled) for key, enabled in parsed.items()}


def paid_membership_active(user: Mapping[str, Any], now: int | None = None) -> bool:
    return int(user.get("vip_expires_at") or 0) > int(now if now is not None else time.time())


def effective_entitlements(user: Mapping[str, Any]) -> dict[str, bool]:
    result = {item["key"]: bool(item["free"]) for item in ENTITLEMENT_DEFINITIONS}
    if user.get("role") == "admin":
        return {key: True for key in result}
    if paid_membership_active(user):
        for key, enabled in _parse_entitlements(user.get("vip_entitlements_json")).items():
            if key in result:
                result[key] = result[key] or enabled
    return result


def entitlement_catalog(user: Mapping[str, Any]) -> list[dict[str, Any]]:
    effective = effective_entitlements(user)
    return [
        {
            **item,
            "enabled": bool(effective.get(item["key"])),
            "requirement": "免费会员已包含" if item["free"] else "需要包含此权益的生效套餐",
        }
        for item in ENTITLEMENT_DEFINITIONS
    ]


def entitlement_label(key: str) -> str:
    return next(
        (str(item["label"]) for item in ENTITLEMENT_DEFINITIONS if item["key"] == key),
        key,
    )
