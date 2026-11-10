from __future__ import annotations

from ipaddress import ip_address, ip_network
from typing import Any

from fastapi import HTTPException, status

from entitlements import effective_entitlements, entitlement_label


def same_lan(source: str, target: str) -> bool:
    try:
        source_ip = ip_address(source)
        target_ip = ip_address(target)
    except ValueError:
        return False
    if source_ip == target_ip:
        return True
    if source_ip.version != target_ip.version or not source_ip.is_private or not target_ip.is_private:
        return False
    prefix = 24 if source_ip.version == 4 else 64
    return target_ip in ip_network(f"{source_ip}/{prefix}", strict=False)


def require_entitlement(
    user: dict[str, Any],
    entitlement: str,
    *,
    source_ip: str = "",
    device_lan_ip: str = "",
    local_allowed: bool = True,
) -> None:
    if user.get("role") == "admin":
        return
    if local_allowed and same_lan(source_ip, device_lan_ip):
        return
    if effective_entitlements(user).get(entitlement):
        return
    raise HTTPException(
        status.HTTP_403_FORBIDDEN,
        f"当前账号没有“{entitlement_label(entitlement)}”权益，请开通包含该功能的套餐",
    )
