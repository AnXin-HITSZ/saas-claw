"""sessions.json persistence."""

from __future__ import annotations

import json
import os
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal


@dataclass
class SessionEntry:
    session_id: str
    updated_at: str
    session_file: str
    status: Literal["active", "archived", "error"] = "active"
    cwd: str | None = None
    workspace_dir: str | None = None
    model: str | None = None
    provider: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


class SessionStore:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.entries: dict[str, SessionEntry] = {}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.load()

    def load(self) -> dict[str, SessionEntry]:
        if not self.path.exists():
            self.entries = {}
            return self.entries

        last_error: Exception | None = None
        for _ in range(3):
            try:
                text = self.path.read_text(encoding="utf-8")
                if not text.strip():
                    time.sleep(0.05)
                    continue
                raw = json.loads(text)
                sessions = raw.get("sessions", raw)
                self.entries = {
                    key: SessionEntry(**value) for key, value in dict(sessions).items()
                }
                return self.entries
            except (OSError, json.JSONDecodeError, TypeError) as exc:
                last_error = exc
                time.sleep(0.05)
        if last_error is not None:
            raise last_error
        self.entries = {}
        return self.entries

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = {"sessions": {key: asdict(value) for key, value in self.entries.items()}}
        with tmp_path.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, self.path)

    def update_entry(self, entry: SessionEntry) -> None:
        self.entries[entry.session_id] = entry
        self.save()

    def touch(
        self,
        session_id: str,
        *,
        session_file: str,
        status: Literal["active", "archived", "error"] = "active",
        cwd: str | None = None,
        workspace_dir: str | None = None,
        model: str | None = None,
        provider: str | None = None,
    ) -> SessionEntry:
        existing = self.entries.get(session_id)
        entry = SessionEntry(
            session_id=session_id,
            updated_at=datetime.now(timezone.utc).isoformat(),
            session_file=session_file,
            status=status,
            cwd=cwd if cwd is not None else (existing.cwd if existing else None),
            workspace_dir=workspace_dir if workspace_dir is not None else (existing.workspace_dir if existing else None),
            model=model if model is not None else (existing.model if existing else None),
            provider=provider if provider is not None else (existing.provider if existing else None),
            metadata=existing.metadata if existing else {},
        )
        self.update_entry(entry)
        return entry
