from __future__ import annotations

from dataclasses import dataclass
import hashlib
import secrets
import threading
import time


@dataclass(frozen=True)
class PlayerSession:
    source_url: str
    media_type: str
    title: str
    owner_user_id: int
    expires_at: int


class PlayerSessionStore:
    def __init__(self, lifetime_seconds: int = 30 * 60) -> None:
        self.lifetime_seconds = lifetime_seconds
        self._sessions: dict[str, PlayerSession] = {}
        self._resources: dict[str, dict[str, str]] = {}
        self._resource_tokens: dict[str, dict[str, str]] = {}
        self._lock = threading.Lock()

    def create(
        self,
        source_url: str,
        media_type: str,
        title: str,
        owner_user_id: int,
    ) -> tuple[str, PlayerSession]:
        now = int(time.time())
        ticket = secrets.token_urlsafe(32)
        session = PlayerSession(
            source_url=source_url,
            media_type=media_type,
            title=title,
            owner_user_id=owner_user_id,
            expires_at=now + self.lifetime_seconds,
        )
        with self._lock:
            self._purge_locked(now)
            session_key = self._digest(ticket)
            self._sessions[session_key] = session
            self._resources[session_key] = {}
            self._resource_tokens[session_key] = {}
        return ticket, session

    def get(self, ticket: str) -> PlayerSession | None:
        now = int(time.time())
        with self._lock:
            self._purge_locked(now)
            return self._sessions.get(self._digest(ticket))

    def register_resource(self, ticket: str, target_url: str) -> str | None:
        now = int(time.time())
        session_key = self._digest(ticket)
        with self._lock:
            self._purge_locked(now)
            if session_key not in self._sessions:
                return None
            reverse = self._resource_tokens.setdefault(session_key, {})
            existing = reverse.get(target_url)
            if existing is not None:
                return existing
            resources = self._resources.setdefault(session_key, {})
            if len(resources) >= 20_000:
                raise ValueError("too many player resources")
            token = secrets.token_urlsafe(18)
            resources[token] = target_url
            reverse[target_url] = token
            return token

    def get_resource(self, ticket: str, resource_token: str) -> str | None:
        now = int(time.time())
        session_key = self._digest(ticket)
        with self._lock:
            self._purge_locked(now)
            if session_key not in self._sessions:
                return None
            return self._resources.get(session_key, {}).get(resource_token)

    def _purge_locked(self, now: int) -> None:
        expired = [key for key, value in self._sessions.items() if value.expires_at <= now]
        for key in expired:
            self._sessions.pop(key, None)
            self._resources.pop(key, None)
            self._resource_tokens.pop(key, None)

    @staticmethod
    def _digest(ticket: str) -> str:
        return hashlib.sha256(ticket.encode("ascii", errors="ignore")).hexdigest()
