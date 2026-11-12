from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator, Sequence

from auth import hash_password


SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    nickname TEXT NOT NULL DEFAULT '',
    phone TEXT NOT NULL DEFAULT '',
    avatar_mime TEXT NOT NULL DEFAULT '',
    avatar_blob BLOB,
    avatar_updated_at INTEGER NOT NULL DEFAULT 0,
    vip_expires_at INTEGER NOT NULL DEFAULT 0,
    vip_tag_style TEXT NOT NULL DEFAULT 'sky',
    vip_plan_name TEXT NOT NULL DEFAULT '',
    vip_plan_names_json TEXT NOT NULL DEFAULT '[]',
    vip_device_limit INTEGER NOT NULL DEFAULT 1,
    vip_benefits_json TEXT NOT NULL DEFAULT '[]',
    vip_entitlements_json TEXT NOT NULL DEFAULT '{}',
    vip_badge_text TEXT NOT NULL DEFAULT '',
    vip_accent_color TEXT NOT NULL DEFAULT '#2F80ED',
    role TEXT NOT NULL DEFAULT 'user',
    active INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    owner_user_id INTEGER,
    status TEXT NOT NULL DEFAULT 'pending',
    enabled INTEGER NOT NULL DEFAULT 1,
    token_hash TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'android_tv',
    model TEXT NOT NULL DEFAULT '',
    app_version TEXT NOT NULL DEFAULT '',
    last_seen_at INTEGER,
    lan_ip TEXT NOT NULL DEFAULT '',
    adb_port INTEGER NOT NULL DEFAULT 5555,
    wan_ip TEXT NOT NULL DEFAULT '',
    nat_type TEXT NOT NULL DEFAULT 'unknown',
    public_key TEXT NOT NULL DEFAULT '',
    current_activity TEXT NOT NULL DEFAULT '',
    playing_url TEXT NOT NULL DEFAULT '',
    position_ms INTEGER NOT NULL DEFAULT 0,
    battery_whitelist INTEGER NOT NULL DEFAULT 0,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(owner_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS device_user_bindings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_pk INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    created_by INTEGER,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(device_pk) REFERENCES devices(id) ON DELETE CASCADE,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(created_by) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE(device_pk, user_id)
);

CREATE TABLE IF NOT EXISTS pending_agents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL UNIQUE,
    requested_at INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending',
    reviewed_at INTEGER,
    reviewed_by INTEGER,
    FOREIGN KEY(device_id) REFERENCES devices(device_id) ON DELETE CASCADE,
    FOREIGN KEY(reviewed_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_pk INTEGER NOT NULL,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'pending',
    result_json TEXT,
    created_by INTEGER,
    created_at INTEGER NOT NULL,
    delivered_at INTEGER,
    completed_at INTEGER,
    FOREIGN KEY(device_pk) REFERENCES devices(id) ON DELETE CASCADE,
    FOREIGN KEY(created_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS device_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_pk INTEGER NOT NULL,
    type TEXT NOT NULL,
    data_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL,
    FOREIGN KEY(device_pk) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS device_apps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_pk INTEGER NOT NULL,
    package_name TEXT NOT NULL,
    label TEXT NOT NULL DEFAULT '',
    version_name TEXT NOT NULL DEFAULT '',
    version_code INTEGER NOT NULL DEFAULT 0,
    system INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    launchable INTEGER NOT NULL DEFAULT 0,
    first_install_time INTEGER NOT NULL DEFAULT 0,
    last_update_time INTEGER NOT NULL DEFAULT 0,
    icon_mime TEXT NOT NULL DEFAULT '',
    icon_base64 TEXT NOT NULL DEFAULT '',
    collected_at INTEGER NOT NULL,
    FOREIGN KEY(device_pk) REFERENCES devices(id) ON DELETE CASCADE,
    UNIQUE(device_pk, package_name)
);

CREATE TABLE IF NOT EXISTS apks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filename TEXT NOT NULL,
    stored_name TEXT NOT NULL UNIQUE,
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL,
    package_name TEXT NOT NULL DEFAULT '',
    min_sdk INTEGER NOT NULL DEFAULT 0,
    uploaded_by INTEGER,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(uploaded_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS app_releases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    platform TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    apk_id INTEGER NOT NULL,
    release_notes TEXT NOT NULL DEFAULT '',
    mandatory INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1,
    published_at INTEGER NOT NULL,
    published_by INTEGER,
    FOREIGN KEY(apk_id) REFERENCES apks(id) ON DELETE CASCADE,
    FOREIGN KEY(published_by) REFERENCES users(id),
    UNIQUE(platform, version_code)
);

CREATE TABLE IF NOT EXISTS p2p_signals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    device_pk INTEGER NOT NULL,
    direction TEXT NOT NULL,
    kind TEXT NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL,
    FOREIGN KEY(device_pk) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    updated_by INTEGER,
    FOREIGN KEY(updated_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS pairing_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code_hash TEXT NOT NULL UNIQUE,
    code_value TEXT NOT NULL DEFAULT '',
    purpose TEXT NOT NULL,
    device_pk INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    claimed_at INTEGER,
    claimed_by INTEGER,
    FOREIGN KEY(device_pk) REFERENCES devices(id) ON DELETE CASCADE,
    FOREIGN KEY(claimed_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS vip_plans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    days INTEGER NOT NULL,
    price_cents INTEGER NOT NULL,
    tag_style TEXT NOT NULL DEFAULT 'sky',
    description TEXT NOT NULL DEFAULT '',
    device_limit INTEGER NOT NULL DEFAULT 1,
    benefits_json TEXT NOT NULL DEFAULT '[]',
    entitlements_json TEXT NOT NULL DEFAULT '{}',
    badge_text TEXT NOT NULL DEFAULT '',
    accent_color TEXT NOT NULL DEFAULT '#2F80ED',
    recommended INTEGER NOT NULL DEFAULT 0,
    active INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS vip_orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no TEXT NOT NULL UNIQUE,
    user_id INTEGER NOT NULL,
    plan_id INTEGER NOT NULL,
    plan_name TEXT NOT NULL,
    days INTEGER NOT NULL,
    amount_cents INTEGER NOT NULL,
    tag_style TEXT NOT NULL,
    device_limit INTEGER NOT NULL DEFAULT 1,
    benefits_json TEXT NOT NULL DEFAULT '[]',
    entitlements_json TEXT NOT NULL DEFAULT '{}',
    badge_text TEXT NOT NULL DEFAULT '',
    accent_color TEXT NOT NULL DEFAULT '#2F80ED',
    status TEXT NOT NULL DEFAULT 'pending',
    payment_status TEXT NOT NULL DEFAULT 'unpaid',
    payment_provider TEXT NOT NULL DEFAULT '',
    payment_method TEXT NOT NULL DEFAULT '',
    provider_trade_no TEXT NOT NULL DEFAULT '',
    payment_payload_type TEXT NOT NULL DEFAULT '',
    payment_payload TEXT NOT NULL DEFAULT '',
    provider_response_json TEXT NOT NULL DEFAULT '{}',
    source TEXT NOT NULL DEFAULT 'manual',
    device_id TEXT NOT NULL DEFAULT '',
    client_ip TEXT NOT NULL DEFAULT '',
    paid_amount_cents INTEGER,
    updated_at INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER,
    created_at INTEGER NOT NULL,
    paid_at INTEGER,
    cancelled_at INTEGER,
    created_by INTEGER,
    reviewed_by INTEGER,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(plan_id) REFERENCES vip_plans(id),
    FOREIGN KEY(created_by) REFERENCES users(id),
    FOREIGN KEY(reviewed_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS vip_codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code_hash TEXT NOT NULL UNIQUE,
    code_hint TEXT NOT NULL,
    plan_id INTEGER NOT NULL,
    plan_name TEXT NOT NULL,
    days INTEGER NOT NULL,
    tag_style TEXT NOT NULL,
    device_limit INTEGER NOT NULL DEFAULT 1,
    benefits_json TEXT NOT NULL DEFAULT '[]',
    entitlements_json TEXT NOT NULL DEFAULT '{}',
    badge_text TEXT NOT NULL DEFAULT '',
    accent_color TEXT NOT NULL DEFAULT '#2F80ED',
    status TEXT NOT NULL DEFAULT 'unused',
    created_at INTEGER NOT NULL,
    created_by INTEGER,
    redeemed_at INTEGER,
    redeemed_by INTEGER,
    FOREIGN KEY(plan_id) REFERENCES vip_plans(id),
    FOREIGN KEY(created_by) REFERENCES users(id),
    FOREIGN KEY(redeemed_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS user_vip_grants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    plan_id INTEGER,
    plan_name TEXT NOT NULL,
    days INTEGER NOT NULL,
    tag_style TEXT NOT NULL DEFAULT 'sky',
    device_limit INTEGER NOT NULL DEFAULT 1,
    benefits_json TEXT NOT NULL DEFAULT '[]',
    entitlements_json TEXT NOT NULL DEFAULT '{}',
    badge_text TEXT NOT NULL DEFAULT '',
    accent_color TEXT NOT NULL DEFAULT '#2F80ED',
    source TEXT NOT NULL,
    source_id INTEGER,
    status TEXT NOT NULL DEFAULT 'active',
    granted_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER,
    revoked_by INTEGER,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY(plan_id) REFERENCES vip_plans(id),
    FOREIGN KEY(revoked_by) REFERENCES users(id),
    UNIQUE(source, source_id)
);

CREATE INDEX IF NOT EXISTS idx_commands_device_status
    ON commands(device_pk, status, created_at);
CREATE INDEX IF NOT EXISTS idx_device_user_bindings_user
    ON device_user_bindings(user_id, device_pk);
CREATE INDEX IF NOT EXISTS idx_device_events_device_created
    ON device_events(device_pk, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_apps_device_label
    ON device_apps(device_pk, label, package_name);
CREATE INDEX IF NOT EXISTS idx_app_releases_platform_version
    ON app_releases(platform, active, version_code DESC);
CREATE INDEX IF NOT EXISTS idx_p2p_signals_session
    ON p2p_signals(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_pairing_sessions_device_expiry
    ON pairing_sessions(device_pk, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_vip_orders_user_created
    ON vip_orders(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_vip_orders_status_created
    ON vip_orders(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_vip_codes_status_created
    ON vip_codes(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_vip_grants_user_status
    ON user_vip_grants(user_id, status, granted_at DESC);
"""


class Database:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._write_lock = threading.RLock()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=15, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 15000")
        return connection

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        with self._write_lock:
            connection = self.connect()
            try:
                connection.execute("BEGIN IMMEDIATE")
                yield connection
                connection.commit()
            except Exception:
                connection.rollback()
                raise
            finally:
                connection.close()

    def initialize(self, admin_username: str, admin_password: str) -> None:
        with self.transaction() as connection:
            connection.executescript(SCHEMA)
            user_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(users)").fetchall()
            }
            user_column_migrations = {
                "nickname": "ALTER TABLE users ADD COLUMN nickname TEXT NOT NULL DEFAULT ''",
                "phone": "ALTER TABLE users ADD COLUMN phone TEXT NOT NULL DEFAULT ''",
                "avatar_mime": "ALTER TABLE users ADD COLUMN avatar_mime TEXT NOT NULL DEFAULT ''",
                "avatar_blob": "ALTER TABLE users ADD COLUMN avatar_blob BLOB",
                "avatar_updated_at": "ALTER TABLE users ADD COLUMN avatar_updated_at INTEGER NOT NULL DEFAULT 0",
                "vip_expires_at": "ALTER TABLE users ADD COLUMN vip_expires_at INTEGER NOT NULL DEFAULT 0",
                "vip_tag_style": "ALTER TABLE users ADD COLUMN vip_tag_style TEXT NOT NULL DEFAULT 'sky'",
                "vip_plan_name": "ALTER TABLE users ADD COLUMN vip_plan_name TEXT NOT NULL DEFAULT ''",
                "vip_plan_names_json": "ALTER TABLE users ADD COLUMN vip_plan_names_json TEXT NOT NULL DEFAULT '[]'",
                "vip_device_limit": "ALTER TABLE users ADD COLUMN vip_device_limit INTEGER NOT NULL DEFAULT 1",
                "vip_benefits_json": "ALTER TABLE users ADD COLUMN vip_benefits_json TEXT NOT NULL DEFAULT '[]'",
                "vip_entitlements_json": "ALTER TABLE users ADD COLUMN vip_entitlements_json TEXT NOT NULL DEFAULT '{}'",
                "vip_badge_text": "ALTER TABLE users ADD COLUMN vip_badge_text TEXT NOT NULL DEFAULT ''",
                "vip_accent_color": "ALTER TABLE users ADD COLUMN vip_accent_color TEXT NOT NULL DEFAULT '#2F80ED'",
            }
            for name, statement in user_column_migrations.items():
                if name not in user_columns:
                    connection.execute(statement)
            # adb_port 是 /ws/tunnel 分配的本地端口：老库没有这一列，幂等补上。
            device_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(devices)").fetchall()
            }
            if "adb_port" not in device_columns:
                connection.execute(
                    "ALTER TABLE devices ADD COLUMN adb_port INTEGER NOT NULL DEFAULT 5555"
                )
            apk_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(apks)").fetchall()
            }
            apk_column_migrations = {
                "package_name": "ALTER TABLE apks ADD COLUMN package_name TEXT NOT NULL DEFAULT ''",
                "min_sdk": "ALTER TABLE apks ADD COLUMN min_sdk INTEGER NOT NULL DEFAULT 0",
            }
            for name, statement in apk_column_migrations.items():
                if name not in apk_columns:
                    connection.execute(statement)
            connection.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone_unique "
                "ON users(phone) WHERE phone <> ''"
            )
            device_app_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(device_apps)").fetchall()
            }
            if "icon_mime" not in device_app_columns:
                connection.execute(
                    "ALTER TABLE device_apps ADD COLUMN icon_mime TEXT NOT NULL DEFAULT ''"
                )
            if "icon_base64" not in device_app_columns:
                connection.execute(
                    "ALTER TABLE device_apps ADD COLUMN icon_base64 TEXT NOT NULL DEFAULT ''"
                )
            table_migrations = {
                "pairing_sessions": {
                    "code_value": "TEXT NOT NULL DEFAULT ''",
                },
                "vip_plans": {
                    "device_limit": "INTEGER NOT NULL DEFAULT 1",
                    "benefits_json": "TEXT NOT NULL DEFAULT '[]'",
                    "entitlements_json": "TEXT NOT NULL DEFAULT '{}'",
                    "badge_text": "TEXT NOT NULL DEFAULT ''",
                    "accent_color": "TEXT NOT NULL DEFAULT '#2F80ED'",
                    "recommended": "INTEGER NOT NULL DEFAULT 0",
                },
                "vip_orders": {
                    "device_limit": "INTEGER NOT NULL DEFAULT 1",
                    "benefits_json": "TEXT NOT NULL DEFAULT '[]'",
                    "entitlements_json": "TEXT NOT NULL DEFAULT '{}'",
                    "badge_text": "TEXT NOT NULL DEFAULT ''",
                    "accent_color": "TEXT NOT NULL DEFAULT '#2F80ED'",
                    "payment_status": "TEXT NOT NULL DEFAULT 'unpaid'",
                    "payment_provider": "TEXT NOT NULL DEFAULT ''",
                    "payment_method": "TEXT NOT NULL DEFAULT ''",
                    "provider_trade_no": "TEXT NOT NULL DEFAULT ''",
                    "payment_payload_type": "TEXT NOT NULL DEFAULT ''",
                    "payment_payload": "TEXT NOT NULL DEFAULT ''",
                    "provider_response_json": "TEXT NOT NULL DEFAULT '{}'",
                    "source": "TEXT NOT NULL DEFAULT 'manual'",
                    "device_id": "TEXT NOT NULL DEFAULT ''",
                    "client_ip": "TEXT NOT NULL DEFAULT ''",
                    "paid_amount_cents": "INTEGER",
                    "updated_at": "INTEGER NOT NULL DEFAULT 0",
                    "expires_at": "INTEGER",
                },
                "vip_codes": {
                    "device_limit": "INTEGER NOT NULL DEFAULT 1",
                    "benefits_json": "TEXT NOT NULL DEFAULT '[]'",
                    "entitlements_json": "TEXT NOT NULL DEFAULT '{}'",
                    "badge_text": "TEXT NOT NULL DEFAULT ''",
                    "accent_color": "TEXT NOT NULL DEFAULT '#2F80ED'",
                },
            }
            for table_name, columns in table_migrations.items():
                existing_columns = {
                    row["name"]
                    for row in connection.execute(f"PRAGMA table_info({table_name})").fetchall()
                }
                for column_name, definition in columns.items():
                    if column_name not in existing_columns:
                        connection.execute(
                            f"ALTER TABLE {table_name} ADD COLUMN {column_name} {definition}"
                        )
            connection.execute(
                "UPDATE vip_orders SET updated_at = created_at WHERE updated_at = 0"
            )
            # Preserve memberships created before per-package grant tracking was introduced.
            now = int(time.time())
            legacy_users = connection.execute(
                "SELECT * FROM users WHERE vip_expires_at > ? AND vip_plan_name <> ''",
                (now,),
            ).fetchall()
            for legacy in legacy_users:
                exists = connection.execute(
                    "SELECT 1 FROM user_vip_grants WHERE user_id = ? LIMIT 1",
                    (legacy["id"],),
                ).fetchone()
                if exists is not None:
                    continue
                remaining_days = max(1, (int(legacy["vip_expires_at"]) - now + 86399) // 86400)
                connection.execute(
                    """
                    INSERT INTO user_vip_grants(
                      user_id, plan_id, plan_name, days, tag_style, device_limit,
                      benefits_json, entitlements_json, badge_text, accent_color,
                      source, source_id, status, granted_at, expires_at
                    ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, 'legacy', ?, 'active', ?, ?)
                    """,
                    (
                        legacy["id"], legacy["vip_plan_name"], remaining_days,
                        legacy["vip_tag_style"], legacy["vip_device_limit"],
                        legacy["vip_benefits_json"], legacy["vip_entitlements_json"],
                        legacy["vip_badge_text"], legacy["vip_accent_color"],
                        legacy["id"], now, legacy["vip_expires_at"],
                    ),
                )
                connection.execute(
                    "UPDATE users SET vip_plan_names_json = ? WHERE id = ?",
                    (json.dumps([legacy["vip_plan_name"]], ensure_ascii=False), legacy["id"]),
                )
            default_entitlements = json.dumps(
                {
                    "remote_cast": True,
                    "remote_control": True,
                    "screen_view": True,
                    "app_install": True,
                    "p2p_access": True,
                    "relay_access": True,
                },
                separators=(",", ":"),
            )
            connection.execute(
                "UPDATE vip_plans SET entitlements_json = ? WHERE entitlements_json = '{}'",
                (default_entitlements,),
            )
            # Existing remote packages predate transport-specific entitlements. Preserve
            # their behavior by granting both authenticated P2P and relay transports.
            entitlement_tables = {
                "vip_plans": "entitlements_json",
                "vip_orders": "entitlements_json",
                "vip_codes": "entitlements_json",
                "user_vip_grants": "entitlements_json",
                "users": "vip_entitlements_json",
            }
            for table_name, column_name in entitlement_tables.items():
                rows = connection.execute(
                    f"SELECT id, {column_name} AS entitlements_json FROM {table_name}"
                ).fetchall()
                for row in rows:
                    try:
                        entitlements = json.loads(row["entitlements_json"] or "{}")
                    except (TypeError, json.JSONDecodeError):
                        continue
                    if not isinstance(entitlements, dict) or not any(
                        bool(entitlements.get(key))
                        for key in ("remote_cast", "remote_control", "screen_view", "app_install")
                    ):
                        continue
                    changed = False
                    for key in ("p2p_access", "relay_access"):
                        if key not in entitlements:
                            entitlements[key] = True
                            changed = True
                    if changed:
                        connection.execute(
                            f"UPDATE {table_name} SET {column_name} = ? WHERE id = ?",
                            (json.dumps(entitlements, ensure_ascii=False, separators=(",", ":")), row["id"]),
                        )
            starter_plan_details = (
                (1, '["公网远程投屏","远程控制与画面查看","P2P 与后端回退"]', "月度", "#2F80ED", 0, "月度会员"),
                (3, '["最多 3 台电视","公网远程投屏","远程控制与画面查看","远程安装应用","P2P 与后端回退"]', "超值", "#D68A13", 1, "年度会员"),
                (8, '["最多 8 台电视","公网远程投屏","远程控制与画面查看","远程安装应用","P2P 与后端回退"]', "家庭", "#7957C8", 0, "家庭长期会员"),
            )
            for device_limit, benefits_json, badge_text, accent_color, recommended, name in starter_plan_details:
                connection.execute(
                    """
                    UPDATE vip_plans SET device_limit = ?, benefits_json = ?, badge_text = ?,
                      accent_color = ?, recommended = ?
                    WHERE name = ? AND benefits_json = '[]'
                    """,
                    (device_limit, benefits_json, badge_text, accent_color, recommended, name),
                )
                connection.execute(
                    """
                    UPDATE users SET vip_device_limit = ?, vip_benefits_json = ?,
                      vip_entitlements_json = ?, vip_badge_text = ?, vip_accent_color = ?
                    WHERE vip_plan_name = ? AND vip_benefits_json = '[]'
                    """,
                    (
                        device_limit,
                        benefits_json,
                        default_entitlements,
                        badge_text,
                        accent_color,
                        name,
                    ),
                )
            existing = connection.execute(
                "SELECT id FROM users WHERE username = ?", (admin_username,)
            ).fetchone()
            if existing is None:
                connection.execute(
                    "INSERT INTO users(username, password_hash, role, created_at) VALUES (?, ?, 'admin', ?)",
                    (admin_username, hash_password(admin_password), int(time.time())),
                )
            connection.execute(
                """
                INSERT OR IGNORE INTO settings(key, value_json, updated_at)
                SELECT 'ai_api_model', value_json, ? FROM settings WHERE key = 'ai_model'
                """,
                (int(time.time()),),
            )
            defaults = {
                "ai_enabled": False,
                "ai_api_url": "",
                "ai_api_key": "",
                "ai_api_model": "",
                "p2p_enabled": True,
                "relay_enabled": True,
                "sales_enabled": False,
                "payment_enabled": False,
                "epay_payment_methods": ["alipay", "wxpay"],
                "epay_base_url": "https://pay.id0.cn",
                "epay_pid": "",
                "epay_key": "",
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
                "epay_verified_config_hash": "",
            }
            for key, value in defaults.items():
                connection.execute(
                    "INSERT OR IGNORE INTO settings(key, value_json, updated_at) VALUES (?, ?, ?)",
                    (key, json.dumps(value), int(time.time())),
                )
            now = int(time.time())
            if connection.execute("SELECT COUNT(*) FROM vip_plans").fetchone()[0] == 0:
                connection.executemany(
                    "INSERT INTO vip_plans(name, days, price_cents, tag_style, description, device_limit, benefits_json, entitlements_json, badge_text, accent_color, recommended, active, sort_order, created_at, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
                    (
                        ("月度会员", 30, 990, "sky", "适合短期远程协助", 1, '["公网远程投屏","远程控制与画面查看","P2P 与后端回退"]', '{"remote_cast":true,"remote_control":true,"screen_view":true,"app_install":true,"p2p_access":true,"relay_access":true}', "月度", "#2F80ED", 0, 10, now, now),
                        ("年度会员", 365, 6990, "gold", "全年家庭设备管理", 3, '["最多 3 台电视","公网远程投屏","远程控制与画面查看","远程安装应用","P2P 与后端回退"]', '{"remote_cast":true,"remote_control":true,"screen_view":true,"app_install":true,"p2p_access":true,"relay_access":true}', "超值", "#D68A13", 1, 20, now, now),
                        ("家庭长期会员", 1095, 15900, "violet", "三年远程协助服务", 8, '["最多 8 台电视","公网远程投屏","远程控制与画面查看","远程安装应用","P2P 与后端回退"]', '{"remote_cast":true,"remote_control":true,"screen_view":true,"app_install":true,"p2p_access":true,"relay_access":true}', "家庭", "#7957C8", 0, 30, now, now),
                    ),
                )

    def fetch_one(self, sql: str, parameters: Sequence[Any] = ()) -> dict[str, Any] | None:
        with self.connect() as connection:
            row = connection.execute(sql, parameters).fetchone()
            return dict(row) if row is not None else None

    def fetch_all(self, sql: str, parameters: Sequence[Any] = ()) -> list[dict[str, Any]]:
        with self.connect() as connection:
            return [dict(row) for row in connection.execute(sql, parameters).fetchall()]

    def execute(self, sql: str, parameters: Sequence[Any] = ()) -> int:
        with self.transaction() as connection:
            cursor = connection.execute(sql, parameters)
            return int(cursor.lastrowid)
