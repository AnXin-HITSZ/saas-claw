"""JSONL transcript persistence."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

from openclaw.llm.types import AgentMessage, message_from_dict
from openclaw.session.context import (
    CompactionPreparation,
    CompactionSettings,
    build_compaction_entry,
    build_leaf_entry,
    build_message_entry,
    build_session_context,
    build_session_header,
    find_latest_compaction,
    prepare_compaction,
    resolve_leaf_id,
    summarize_messages,
)


class Transcript:
    def __init__(
        self,
        path: str | Path,
        *,
        session_id: str | None = None,
        cwd: str | None = None,
        parent_session: str | None = None,
    ) -> None:
        self.path = Path(path)
        self.session_id = session_id or self.path.stem
        self.cwd = cwd
        self.parent_session = parent_session
        self._lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.ensure_header()

    def append_message(self, message: AgentMessage) -> dict[str, Any]:
        entry = build_message_entry(message, parent_id=self.leaf_id())
        append_jsonl_entry(self.path, entry, self._lock)
        return entry

    def append_compaction(
        self,
        *,
        summary: str,
        first_kept_entry_id: str,
        tokens_before: int,
        details: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        entry = build_compaction_entry(
            summary=summary,
            first_kept_entry_id=first_kept_entry_id,
            tokens_before=tokens_before,
            parent_id=self.leaf_id(),
            details=details,
        )
        append_jsonl_entry(self.path, entry, self._lock)
        return entry

    def append_leaf(self, leaf_id: str | None) -> dict[str, Any]:
        entry = build_leaf_entry(leaf_id=leaf_id, parent_id=self.leaf_id())
        append_jsonl_entry(self.path, entry, self._lock)
        return entry

    def compact(self, *, settings: CompactionSettings, reason: str = "context_budget") -> CompactionPreparation:
        entries = self.read_entries()
        preparation = prepare_compaction(entries, settings=settings, reason=reason)
        if not preparation.should_compact or preparation.first_kept_entry_id is None:
            return preparation

        previous = find_latest_compaction(entries)
        previous_summary = str(previous.get("summary", "")) if previous else None
        summary = summarize_messages(preparation.summary_messages, previous_summary=previous_summary)
        self.append_compaction(
            summary=summary,
            first_kept_entry_id=preparation.first_kept_entry_id,
            tokens_before=preparation.tokens_before,
            details={
                "reason": reason,
                "summaryMessageCount": len(preparation.summary_messages),
                "retainedMessageCount": len(preparation.retained_messages),
            },
        )
        return preparation

    def read_entries(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        entries: list[dict[str, Any]] = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            entry = json.loads(line)
            if isinstance(entry, dict):
                entries.append(entry)
        return entries

    def read_data_entries(self) -> list[dict[str, Any]]:
        return [entry for entry in self.read_entries() if entry.get("type") != "session"]

    def read_messages(self) -> list[AgentMessage]:
        messages: list[AgentMessage] = []
        for entry in self.read_data_entries():
            if entry.get("type") != "message":
                continue
            message_data = entry.get("message")
            if isinstance(message_data, dict):
                messages.append(message_from_dict(message_data))
        return messages

    def read_context_messages(self) -> list[AgentMessage]:
        return build_session_context(self.read_entries(), self.leaf_id())

    def leaf_id(self) -> str | None:
        return resolve_leaf_id(self.read_entries())

    def ensure_header(self) -> None:
        if self.path.exists() and self.path.stat().st_size > 0:
            return
        header = build_session_header(
            session_id=self.session_id,
            cwd=self.cwd,
            parent_session=self.parent_session,
        )
        append_jsonl_entry(self.path, header, self._lock)


def build_transcript_entry(message: AgentMessage) -> dict[str, Any]:
    return build_message_entry(message, parent_id=None)


def append_jsonl_entry(path: Path, entry: dict[str, Any], lock: threading.Lock | None = None) -> None:
    line = json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n"
    if lock is None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(line)
        return
    with lock:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(line)
