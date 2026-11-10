from __future__ import annotations

import json
import time
from typing import Any, Mapping

from fastapi import HTTPException, status


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _parse(value: Any, fallback: Any) -> Any:
    try:
        return json.loads(value or "")
    except (TypeError, json.JSONDecodeError):
        return fallback


def grant_public(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": int(row["id"]),
        "user_id": int(row["user_id"]),
        "plan_id": row.get("plan_id"),
        "plan_name": row["plan_name"],
        "days": int(row["days"]),
        "tag_style": row.get("tag_style") or "sky",
        "device_limit": int(row.get("device_limit") or 1),
        "benefits": _parse(row.get("benefits_json"), []),
        "entitlements": _parse(row.get("entitlements_json"), {}),
        "badge_text": row.get("badge_text") or "",
        "accent_color": row.get("accent_color") or "#2F80ED",
        "source": row.get("source") or "manual",
        "status": row.get("status") or "active",
        "granted_at": int(row.get("granted_at") or 0),
        "expires_at": int(row.get("expires_at") or 0),
        "revoked_at": row.get("revoked_at"),
    }


def _active_grants(connection, user_id: int) -> list[dict[str, Any]]:
    return [
        dict(row)
        for row in connection.execute(
            "SELECT * FROM user_vip_grants WHERE user_id = ? AND status = 'active' "
            "ORDER BY granted_at, id",
            (user_id,),
        ).fetchall()
    ]


def _aggregate(connection, user_id: int, expires_at: int) -> None:
    grants = _active_grants(connection, user_id)
    if not grants or expires_at <= int(time.time()):
        connection.execute(
            """
            UPDATE users SET vip_expires_at = 0, vip_plan_name = '', vip_plan_names_json = '[]',
              vip_device_limit = 1, vip_benefits_json = '[]', vip_entitlements_json = '{}',
              vip_badge_text = '', vip_accent_color = '#2F80ED', vip_tag_style = 'sky'
            WHERE id = ?
            """,
            (user_id,),
        )
        return

    names: list[str] = []
    benefits: list[str] = []
    entitlements: dict[str, bool] = {}
    device_limit = 1
    for grant in grants:
        name = str(grant.get("plan_name") or "").strip()
        if name and name not in names:
            names.append(name)
        for benefit in _parse(grant.get("benefits_json"), []):
            text = str(benefit).strip()
            if text and text not in benefits:
                benefits.append(text)
        for key, enabled in _parse(grant.get("entitlements_json"), {}).items():
            entitlements[str(key)] = bool(entitlements.get(str(key))) or bool(enabled)
        device_limit = max(device_limit, int(grant.get("device_limit") or 1))

    newest = grants[-1]
    display_name = " + ".join(names)
    badge = newest.get("badge_text") or ("组合" if len(names) > 1 else "")
    connection.execute(
        """
        UPDATE users SET vip_expires_at = ?, vip_plan_name = ?, vip_plan_names_json = ?,
          vip_device_limit = ?, vip_benefits_json = ?, vip_entitlements_json = ?,
          vip_tag_style = ?, vip_badge_text = ?, vip_accent_color = ?
        WHERE id = ?
        """,
        (
            expires_at, display_name, _json(names), device_limit, _json(benefits),
            _json(entitlements), newest.get("tag_style") or "sky", badge,
            newest.get("accent_color") or "#2F80ED", user_id,
        ),
    )
    connection.execute(
        "UPDATE user_vip_grants SET expires_at = ? WHERE user_id = ? AND status = 'active'",
        (expires_at, user_id),
    )


def grant_membership(
    connection,
    user_id: int,
    plan: Mapping[str, Any],
    now: int,
    *,
    source: str,
    source_id: int,
) -> int:
    plan = dict(plan)
    user = connection.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    if user is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "user not found")
    existing = connection.execute(
        "SELECT id FROM user_vip_grants WHERE source = ? AND source_id = ?",
        (source, source_id),
    ).fetchone()
    if existing is not None:
        return int(user["vip_expires_at"] or 0)

    current_expires = int(user["vip_expires_at"] or 0)
    if current_expires <= now:
        connection.execute(
            "UPDATE user_vip_grants SET status = 'expired' "
            "WHERE user_id = ? AND status = 'active'",
            (user_id,),
        )
        current_expires = now
    days = int(plan["days"])
    expires_at = current_expires + days * 86400
    plan_name = plan.get("plan_name") or plan["name"]
    plan_id = plan.get("plan_id") or plan.get("id")
    connection.execute(
        """
        INSERT INTO user_vip_grants(
          user_id, plan_id, plan_name, days, tag_style, device_limit,
          benefits_json, entitlements_json, badge_text, accent_color,
          source, source_id, status, granted_at, expires_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)
        """,
        (
            user_id, plan_id, plan_name, days, plan.get("tag_style") or "sky",
            int(plan.get("device_limit") or 1), plan.get("benefits_json") or "[]",
            plan.get("entitlements_json") or "{}", plan.get("badge_text") or "",
            plan.get("accent_color") or "#2F80ED", source, source_id, now, expires_at,
        ),
    )
    _aggregate(connection, user_id, expires_at)
    return expires_at


def revoke_membership_grant(connection, user_id: int, grant_id: int, admin_id: int, now: int) -> dict[str, Any]:
    grant_row = connection.execute(
        "SELECT * FROM user_vip_grants WHERE id = ? AND user_id = ? AND status = 'active'",
        (grant_id, user_id),
    ).fetchone()
    if grant_row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "active VIP package grant not found")
    grant = dict(grant_row)
    connection.execute(
        "UPDATE user_vip_grants SET status = 'revoked', revoked_at = ?, revoked_by = ? WHERE id = ?",
        (now, admin_id, grant_id),
    )
    user = connection.execute("SELECT vip_expires_at FROM users WHERE id = ?", (user_id,)).fetchone()
    assert user is not None
    remaining = _active_grants(connection, user_id)
    expires_at = max(now, int(user["vip_expires_at"] or 0) - int(grant["days"]) * 86400) if remaining else 0
    _aggregate(connection, user_id, expires_at)
    return grant_public({**grant, "status": "revoked", "revoked_at": now})
