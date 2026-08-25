"""
Service-account login against claim-service. Photos are behind bearer auth, so
this service signs in as its own account (role SERVICE) and caches the token
until shortly before it expires. No OIDC provider: claim-service issues the JWTs.
"""
from __future__ import annotations

import logging
import os
import threading
import time
from datetime import datetime, timezone
from typing import Callable

log = logging.getLogger("assessment.auth")

LoginCall = Callable[[str, str, str], dict]   # (url, username, password) -> login response JSON


class TokenProvider:
    def __init__(self, base_url: str, username: str, password: str, login: LoginCall | None = None,
                 clock: Callable[[], float] = time.time):
        self.base_url, self.username, self.password = base_url, username, password
        self._login = login or _http_login
        self._clock = clock
        self._token: str | None = None
        self._expires_at = 0.0
        self._lock = threading.Lock()

    def bearer(self) -> str:
        with self._lock:
            if self._token is None or self._clock() > self._expires_at - 60:
                data = self._login(f"{self.base_url}/api/v1/auth/login", self.username, self.password)
                self._token = data["accessToken"]
                self._expires_at = datetime.fromisoformat(data["expiresAt"].replace("Z", "+00:00")).timestamp()
                log.info("signed in to claim-service as %s (token valid until %s)", self.username, data["expiresAt"])
            return f"Bearer {self._token}"

    def invalidate(self) -> None:
        with self._lock:
            self._token = None


def _http_login(url: str, username: str, password: str) -> dict:
    import httpx
    r = httpx.post(url, json={"username": username, "password": password}, timeout=10.0)
    r.raise_for_status()
    return r.json()


def from_env(base_url: str) -> TokenProvider | None:
    user = os.getenv("SERVICE_ACCOUNT_USERNAME", "assessment-service")
    pwd = os.getenv("SERVICE_ACCOUNT_PASSWORD")
    if not pwd:
        log.warning("SERVICE_ACCOUNT_PASSWORD not set: photo fetches will be unauthenticated and rejected")
        return None
    return TokenProvider(base_url, user, pwd)


def now_utc() -> datetime:
    return datetime.now(timezone.utc)
