from __future__ import annotations

import asyncio
import hashlib
import json
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit


# How long a session lives without a hello/keepalive from either side. Every accepted hello or
# keepalive pushes it forward again (sliding TTL), so a session stays alive exactly as long as one
# of the two sides is still talking to the rendezvous.
SESSION_TTL_SECONDS = 120

# Sent back to clients so they know when to refresh. Clients must treat "expires_at" from any
# rendezvous reply as the new deadline instead of the one they got when the session was created.
MAX_DATAGRAM_BYTES = 2048


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


@dataclass
class Session:
    session_id: str
    device_id: str
    control_token_hash: str
    device_token_hash: str
    channel_key: str
    expires_at: int
    endpoints: dict[str, tuple[str, int]] = field(default_factory=dict)
    last_seen_at: int = 0


class P2PSessionStore:
    def __init__(self) -> None:
        self._items: dict[str, Session] = {}
        self._lock = threading.RLock()

    def create(self, device_id: str) -> tuple[Session, str, str]:
        control_token = secrets.token_urlsafe(32)
        device_token = secrets.token_urlsafe(32)
        now = int(time.time())
        session = Session(
            session_id=secrets.token_urlsafe(18), device_id=device_id,
            control_token_hash=_digest(control_token), device_token_hash=_digest(device_token),
            channel_key=secrets.token_urlsafe(32), expires_at=now + SESSION_TTL_SECONDS,
            last_seen_at=now,
        )
        with self._lock:
            self._prune()
            self._items[session.session_id] = session
        return session, control_token, device_token

    def register(self, session_id: str, role: str, token: str, endpoint: tuple[str, int]) -> tuple[Session | None, tuple[str, int] | None]:
        with self._lock:
            self._prune()
            session = self._authorize(session_id, role, token)
            if session is None:
                return None, None
            session.endpoints[role] = endpoint
            self._touch(session)
            peer = session.endpoints.get("device" if role == "control" else "control")
            return session, peer

    def keepalive(self, session_id: str, role: str, token: str) -> Session | None:
        """Refresh a session without changing its endpoints (used after the peer is established)."""
        with self._lock:
            self._prune()
            session = self._authorize(session_id, role, token)
            if session is None:
                return None
            self._touch(session)
            return session

    def lookup(self, session_id: str) -> Session | None:
        with self._lock:
            self._prune()
            return self._items.get(session_id)

    def stats(self) -> dict[str, int]:
        with self._lock:
            self._prune()
            now = int(time.time())
            open_sessions = sum(1 for item in self._items.values() if len(item.endpoints) >= 2)
            return {
                "sessions": len(self._items),
                "established": open_sessions,
                "closest_expiry_seconds": min(
                    (item.expires_at - now for item in self._items.values()), default=0
                ),
            }

    def _authorize(self, session_id: str, role: str, token: str) -> Session | None:
        session = self._items.get(session_id)
        if session is None:
            return None
        if role == "control":
            expected = session.control_token_hash
        elif role == "device":
            expected = session.device_token_hash
        else:
            return None
        if not secrets.compare_digest(expected, _digest(token)):
            return None
        return session

    @staticmethod
    def _touch(session: Session) -> None:
        now = int(time.time())
        session.last_seen_at = now
        session.expires_at = now + SESSION_TTL_SECONDS

    def _prune(self) -> None:
        now = int(time.time())
        for key in [key for key, value in self._items.items() if value.expires_at <= now]:
            self._items.pop(key, None)


class RendezvousProtocol(asyncio.DatagramProtocol):
    def __init__(self, store: P2PSessionStore) -> None:
        self.store = store
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if len(data) > MAX_DATAGRAM_BYTES:
            return
        try:
            message = json.loads(data.decode("utf-8"))
            kind = message.get("type")
            if kind not in ("hello", "keepalive"):
                return
            session_id = str(message.get("session_id") or "")
            role = str(message.get("role") or "")
            token = str(message.get("token") or "")
            if kind == "keepalive":
                session = self.store.keepalive(session_id, role, token)
                if session is None or self.transport is None:
                    # The session is gone (TTL, restart, or a stale token): tell the client to
                    # renegotiate through the HTTP/WebSocket control plane instead of retrying
                    # forever against a rendezvous that will never answer.
                    self._reply(addr, {"type": "expired", "session_id": session_id})
                    return
                self._reply(addr, {
                    "type": "alive", "session_id": session.session_id,
                    "expires_at": session.expires_at, "ttl": SESSION_TTL_SECONDS,
                })
                return
            session, peer = self.store.register(session_id, role, token, addr)
            if session is None or self.transport is None:
                self._reply(addr, {"type": "expired", "session_id": session_id})
                return
            response: dict[str, Any] = {
                "type": "waiting", "session_id": session.session_id,
                "expires_at": session.expires_at, "ttl": SESSION_TTL_SECONDS,
            }
            if peer:
                response = {
                    "type": "peer", "session_id": session.session_id,
                    "host": peer[0], "port": peer[1],
                    "expires_at": session.expires_at, "ttl": SESSION_TTL_SECONDS,
                }
            self.transport.sendto(json.dumps(response, separators=(",", ":")).encode("utf-8"), addr)
            if peer:
                reverse = {
                    "type": "peer", "session_id": session.session_id,
                    "host": addr[0], "port": addr[1],
                    "expires_at": session.expires_at, "ttl": SESSION_TTL_SECONDS,
                }
                self.transport.sendto(json.dumps(reverse, separators=(",", ":")).encode("utf-8"), peer)
        except (UnicodeDecodeError, ValueError, TypeError):
            return

    def _reply(self, addr: tuple[str, int], payload: dict[str, Any]) -> None:
        if self.transport is None:
            return
        self.transport.sendto(json.dumps(payload, separators=(",", ":")).encode("utf-8"), addr)


async def start_rendezvous(app):
    settings = app.state.settings
    if settings.p2p_udp_port <= 0:
        return None
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: RendezvousProtocol(app.state.p2p_sessions),
        local_addr=(settings.p2p_udp_host, settings.p2p_udp_port),
    )
    return transport


def public_rendezvous(settings) -> tuple[str, int]:
    host = settings.p2p_public_host
    if not host and settings.public_base_url:
        host = urlsplit(settings.public_base_url).hostname or ""
    return host, settings.p2p_public_port or settings.p2p_udp_port
