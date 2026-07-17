"""Store for pending tool approval runtime state.

Runtime state contains just enough context to rebuild the Agent loop when the
user resumes an approval (messages, the pending assistant tool_call, non-secret
identifiers). Secret material (API keys, encrypted tokens, provider config) is
NEVER persisted here; Spring re-injects it on ``/v1/agent/resume``.

Two backends are provided:

- ``RedisPendingApprovalStore``: production backend, requires ``redis`` client
  library. TTL is enforced by Redis.
- ``FilePendingApprovalStore``: development / test fallback. TTL is enforced
  lazily on read.
"""

from __future__ import annotations

import json
import os
import tempfile
import time
from pathlib import Path
from typing import Any, Protocol

DEFAULT_TTL_SECONDS = 30 * 60


class PendingApprovalStore(Protocol):
    def save(self, approval_id: str, state: dict[str, Any], ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None: ...

    def load(self, approval_id: str) -> dict[str, Any] | None: ...

    def delete(self, approval_id: str) -> None: ...


def pending_state_key(approval_id: str) -> str:
    return f"agent:pending_approval:{approval_id}"


class FilePendingApprovalStore:
    """File-backed pending approval store used for local development / tests.

    Each approval is written as a JSON file under ``base_dir``. Expiry is
    lazily enforced on load: expired records are treated as missing and the
    file is removed.
    """

    def __init__(self, base_dir: Path | str | None = None) -> None:
        if base_dir is None:
            base_dir = Path(tempfile.gettempdir()) / "pyclaw-pending-approvals"
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def save(self, approval_id: str, state: dict[str, Any], ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None:
        payload = {
            "state": state,
            "expires_at": time.time() + max(1, int(ttl_seconds)),
        }
        path = self._path(approval_id)
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    def load(self, approval_id: str) -> dict[str, Any] | None:
        path = self._path(approval_id)
        if not path.exists():
            return None
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        expires_at = float(payload.get("expires_at") or 0)
        if expires_at and expires_at < time.time():
            try:
                path.unlink()
            except OSError:
                pass
            return None
        state = payload.get("state")
        return dict(state) if isinstance(state, dict) else None

    def delete(self, approval_id: str) -> None:
        path = self._path(approval_id)
        try:
            path.unlink()
        except FileNotFoundError:
            return
        except OSError:
            return

    def _path(self, approval_id: str) -> Path:
        safe = "".join(ch for ch in approval_id if ch.isalnum() or ch in ("_", "-"))
        if not safe:
            safe = "invalid"
        return self.base_dir / f"{safe}.json"


class RedisPendingApprovalStore:
    """Redis-backed pending approval store."""

    def __init__(self, client: Any) -> None:
        self.client = client

    def save(self, approval_id: str, state: dict[str, Any], ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None:
        self.client.set(
            pending_state_key(approval_id),
            json.dumps(state, ensure_ascii=False),
            ex=max(1, int(ttl_seconds)),
        )

    def load(self, approval_id: str) -> dict[str, Any] | None:
        raw = self.client.get(pending_state_key(approval_id))
        if raw is None:
            return None
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            return None
        return dict(data) if isinstance(data, dict) else None

    def delete(self, approval_id: str) -> None:
        self.client.delete(pending_state_key(approval_id))


def build_default_pending_approval_store() -> PendingApprovalStore:
    """Construct the default pending-approval store from environment.

    Priority order:

    1. ``OPENCLAW_REDIS_URL`` -> ``RedisPendingApprovalStore``
    2. ``OPENCLAW_PENDING_APPROVAL_DIR`` -> ``FilePendingApprovalStore``
    3. ``FilePendingApprovalStore`` under the system temp directory.
    """

    redis_url = os.environ.get("OPENCLAW_REDIS_URL") or os.environ.get("REDIS_URL")
    if redis_url:
        try:
            import redis  # type: ignore[import-not-found]

            client = redis.Redis.from_url(redis_url)
            return RedisPendingApprovalStore(client)
        except Exception:
            pass
    dir_override = os.environ.get("OPENCLAW_PENDING_APPROVAL_DIR")
    return FilePendingApprovalStore(dir_override)


__all__ = [
    "DEFAULT_TTL_SECONDS",
    "PendingApprovalStore",
    "FilePendingApprovalStore",
    "RedisPendingApprovalStore",
    "pending_state_key",
    "build_default_pending_approval_store",
]
