"""Session entry replay, compaction, and token estimation helpers."""

from __future__ import annotations

import copy
import json
import math
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal
from uuid import uuid4

from openclaw.llm.types import AgentMessage, AssistantMessage, UserMessage, message_from_dict, message_to_dict, text_content

SESSION_VERSION = 3
IMAGE_BLOCK_CHARS = 4_800
MESSAGE_OVERHEAD_TOKENS = 4

SessionEntry = dict[str, Any]
PrecheckRoute = Literal["fits", "compact_only", "truncate_tool_results_only", "compact_then_truncate"]


@dataclass
class SessionHeader:
    id: str
    cwd: str
    timestamp: str = field(default_factory=lambda: datetime.now(UTC).isoformat())
    version: int = SESSION_VERSION
    parentSession: str | None = None
    type: Literal["session"] = "session"

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "type": self.type,
            "version": self.version,
            "id": self.id,
            "timestamp": self.timestamp,
            "cwd": self.cwd,
        }
        if self.parentSession:
            data["parentSession"] = self.parentSession
        return data


@dataclass
class CompactionSettings:
    enabled: bool = True
    context_window_tokens: int = 120_000
    reserve_tokens: int = 16_384
    keep_recent_tokens: int = 20_000
    tool_result_max_chars: int = 20_000

    @property
    def prompt_budget_tokens(self) -> int:
        return max(0, self.context_window_tokens - self.reserve_tokens)


@dataclass
class CompactionPreparation:
    should_compact: bool
    summary_messages: list[AgentMessage] = field(default_factory=list)
    retained_messages: list[AgentMessage] = field(default_factory=list)
    first_kept_entry_id: str | None = None
    tokens_before: int = 0
    reason: str = ""


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def build_session_header(
    *,
    session_id: str,
    cwd: str | None = None,
    parent_session: str | None = None,
) -> dict[str, Any]:
    return SessionHeader(
        id=session_id,
        cwd=str(Path(cwd).resolve()) if cwd else str(Path.cwd()),
        parentSession=parent_session,
    ).to_dict()


def build_message_entry(message: AgentMessage, *, parent_id: str | None = None) -> dict[str, Any]:
    return {
        "type": "message",
        "id": uuid4().hex,
        "parentId": parent_id,
        "timestamp": now_iso(),
        "message": message_to_dict(message),
    }


def build_compaction_entry(
    *,
    summary: str,
    first_kept_entry_id: str,
    tokens_before: int,
    parent_id: str | None = None,
    details: dict[str, Any] | None = None,
    from_hook: bool = False,
) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "type": "compaction",
        "id": uuid4().hex,
        "parentId": parent_id,
        "timestamp": now_iso(),
        "summary": summary,
        "firstKeptEntryId": first_kept_entry_id,
        "tokensBefore": tokens_before,
    }
    if details:
        entry["details"] = details
    if from_hook:
        entry["fromHook"] = True
    return entry


def build_leaf_entry(*, leaf_id: str | None, parent_id: str | None = None) -> dict[str, Any]:
    return {
        "type": "leaf",
        "id": uuid4().hex,
        "parentId": parent_id,
        "timestamp": now_iso(),
        "leafId": leaf_id,
    }


def is_header(entry: dict[str, Any]) -> bool:
    return entry.get("type") == "session"


def is_control_entry(entry: dict[str, Any]) -> bool:
    return entry.get("type") in {"session", "leaf"}


def entry_id(entry: dict[str, Any]) -> str | None:
    value = entry.get("id")
    return str(value) if value else None


def entry_parent_id(entry: dict[str, Any]) -> str | None:
    value = entry.get("parentId")
    return str(value) if value else None


def data_entries(entries: list[SessionEntry]) -> list[SessionEntry]:
    return [entry for entry in entries if not is_control_entry(entry)]


def resolve_leaf_id(entries: list[SessionEntry]) -> str | None:
    leaf_id: str | None = None
    for entry in entries:
        if entry.get("type") == "leaf":
            raw_leaf = entry.get("leafId")
            leaf_id = str(raw_leaf) if raw_leaf else None
            continue
        if is_control_entry(entry):
            continue
        leaf_id = entry_id(entry)
    return leaf_id


def get_branch(entries: list[SessionEntry], leaf_id: str | None = None) -> list[SessionEntry]:
    candidates = data_entries(entries)
    if not candidates:
        return []

    current_id = leaf_id or resolve_leaf_id(entries)
    if not current_id:
        return candidates

    by_id = {entry["id"]: entry for entry in candidates if entry.get("id")}
    path: list[SessionEntry] = []
    seen: set[str] = set()

    while current_id and current_id not in seen:
        seen.add(current_id)
        entry = by_id.get(current_id)
        if entry is None:
            break
        path.insert(0, entry)
        current_id = entry_parent_id(entry)

    if len(path) <= 1 and _looks_like_legacy_linear_entries(candidates):
        return candidates
    return path


def build_session_context(entries: list[SessionEntry], leaf_id: str | None = None) -> list[AgentMessage]:
    branch = get_branch(entries, leaf_id)
    latest_compaction = find_latest_compaction(branch)
    if latest_compaction is None:
        return entries_to_messages(branch)

    messages: list[AgentMessage] = [compaction_summary_message(str(latest_compaction.get("summary", "")))]
    first_kept_id = latest_compaction.get("firstKeptEntryId")
    tail = entries_from_id(branch, str(first_kept_id) if first_kept_id else None)
    messages.extend(entries_to_messages(tail))
    return messages


def find_latest_compaction(entries: list[SessionEntry]) -> SessionEntry | None:
    for entry in reversed(entries):
        if entry.get("type") == "compaction":
            return entry
    return None


def entries_from_id(entries: list[SessionEntry], first_id: str | None) -> list[SessionEntry]:
    if not first_id:
        return []
    for index, entry in enumerate(entries):
        if entry_id(entry) == first_id:
            return entries[index:]
    latest_compaction = find_latest_compaction(entries)
    if latest_compaction is None:
        return []
    compaction_index = entries.index(latest_compaction)
    return entries[compaction_index + 1 :]


def entries_to_messages(entries: list[SessionEntry]) -> list[AgentMessage]:
    messages: list[AgentMessage] = []
    for entry in entries:
        if entry.get("type") != "message":
            continue
        message_data = entry.get("message")
        if isinstance(message_data, dict):
            messages.append(message_from_dict(message_data))
    return messages


def compaction_summary_message(summary: str) -> UserMessage:
    text = (
        "Earlier conversation was compacted. Use the following summary as prior context:\n\n"
        + summary.strip()
    ).strip()
    return UserMessage(content=text_content(text))


def prepare_compaction(
    entries: list[SessionEntry],
    *,
    settings: CompactionSettings,
    reason: str = "context_budget",
) -> CompactionPreparation:
    branch = get_branch(entries)
    message_pairs = _message_entry_pairs(branch)
    if len(message_pairs) <= 1:
        return CompactionPreparation(False, reason="not_enough_messages")

    context_messages = build_session_context(branch)
    tokens_before = estimate_context_tokens(context_messages)
    cut_index = find_cut_point(message_pairs, keep_recent_tokens=settings.keep_recent_tokens)
    if cut_index <= 0 or cut_index >= len(message_pairs):
        return CompactionPreparation(False, tokens_before=tokens_before, reason="no_safe_cut_point")

    summary_pairs = message_pairs[:cut_index]
    retained_pairs = message_pairs[cut_index:]
    first_kept_id = entry_id(retained_pairs[0][0])
    if first_kept_id is None:
        return CompactionPreparation(False, tokens_before=tokens_before, reason="missing_first_kept_id")

    return CompactionPreparation(
        should_compact=True,
        summary_messages=[message for _, message in summary_pairs],
        retained_messages=[message for _, message in retained_pairs],
        first_kept_entry_id=first_kept_id,
        tokens_before=tokens_before,
        reason=reason,
    )


def find_cut_point(message_pairs: list[tuple[SessionEntry, AgentMessage]], *, keep_recent_tokens: int) -> int:
    if keep_recent_tokens <= 0:
        return max(1, len(message_pairs) - 1)

    running = 0
    cut_index = 0
    for index in range(len(message_pairs) - 1, -1, -1):
        running += estimate_message_tokens(message_pairs[index][1])
        if running >= keep_recent_tokens:
            cut_index = index
            break

    cut_index = max(1, cut_index)
    while cut_index < len(message_pairs) and message_pairs[cut_index][1].role == "tool":
        cut_index += 1
    if cut_index >= len(message_pairs):
        cut_index = max(1, len(message_pairs) - 1)
    return cut_index


def summarize_messages(messages: list[AgentMessage], *, previous_summary: str | None = None) -> str:
    lines: list[str] = []
    if previous_summary:
        lines.append("Previous summary:")
        lines.append(previous_summary.strip())
        lines.append("")
    lines.append(f"Compacted {len(messages)} earlier messages.")
    for index, message in enumerate(messages[-12:], start=1):
        text = _message_plain_text(message)
        if not text:
            text = _message_non_text_summary(message)
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        text = " ".join(part.strip() for part in text.splitlines() if part.strip())
        if len(text) > 240:
            text = text[:240] + "...[truncated]"
        lines.append(f"{index}. {message.role}: {text}")
    return "\n".join(lines)


def normalize_usage(usage: dict[str, Any] | None) -> dict[str, int]:
    raw = usage or {}
    input_tokens = _int_field(raw, "input", "inputTokens", "prompt_tokens", "input_tokens", "prompt_n")
    output_tokens = _int_field(raw, "output", "outputTokens", "completion_tokens", "output_tokens", "predicted_n")
    cache_read = _int_field(raw, "cacheRead", "cache_read_input_tokens")
    cache_write = _int_field(raw, "cacheWrite", "cache_creation_input_tokens")
    reasoning = _int_field(raw, "reasoningTokens", "reasoning_tokens")
    total = _int_field(raw, "total", "total_tokens")
    if total <= 0:
        total = input_tokens + output_tokens + cache_write + reasoning
    return {
        "input": input_tokens,
        "output": output_tokens,
        "cacheRead": cache_read,
        "cacheWrite": cache_write,
        "reasoningTokens": reasoning,
        "total": total,
    }


def estimate_context_tokens(messages: list[AgentMessage]) -> int:
    last_usage_index: int | None = None
    usage_tokens = 0
    for index, message in enumerate(messages):
        if not isinstance(message, AssistantMessage) or not message.usage:
            continue
        normalized = normalize_usage(message.usage)
        if normalized["total"] > 0:
            last_usage_index = index
            usage_tokens = normalized["total"]

    if last_usage_index is None:
        return sum(estimate_message_tokens(message) for message in messages)

    trailing = sum(estimate_message_tokens(message) for message in messages[last_usage_index + 1 :])
    return usage_tokens + trailing


def estimate_message_tokens(message: AgentMessage) -> int:
    chars = len(message.role)
    for block in message.content:
        chars += estimate_block_chars(block)
    return math.ceil(chars / 4) + MESSAGE_OVERHEAD_TOKENS


def estimate_block_chars(block: dict[str, Any]) -> int:
    block_type = block.get("type")
    if block_type == "text":
        return len(str(block.get("text", "")))
    if block_type == "toolCall":
        return len(str(block.get("name", ""))) + len(_json_dumps(block.get("input") or {}))
    if block_type == "toolResult":
        return len(str(block.get("name", ""))) + len(_stringify(block.get("output")))
    if block_type == "image":
        return IMAGE_BLOCK_CHARS
    return len(_json_dumps(block))


def should_preemptively_compact_before_prompt(
    messages: list[AgentMessage],
    *,
    settings: CompactionSettings,
) -> PrecheckRoute:
    tokens = estimate_context_tokens(messages)
    if tokens <= settings.prompt_budget_tokens:
        return "fits"

    oversized_tool_result = any(
        block.get("type") == "toolResult" and len(_stringify(block.get("output"))) > settings.tool_result_max_chars
        for message in messages
        for block in message.content
    )
    if oversized_tool_result and settings.enabled:
        return "compact_then_truncate"
    if oversized_tool_result:
        return "truncate_tool_results_only"
    if settings.enabled:
        return "compact_only"
    return "truncate_tool_results_only"


def truncate_oversized_tool_results(
    messages: list[AgentMessage],
    *,
    max_chars: int,
) -> tuple[list[AgentMessage], int]:
    cloned = copy.deepcopy(messages)
    truncated = 0
    for message in cloned:
        for block in message.content:
            if block.get("type") != "toolResult":
                continue
            output = block.get("output")
            text = _stringify(output)
            if len(text) <= max_chars:
                continue
            block["output"] = text[:max_chars] + "\n...[truncated]"
            truncated += 1
    return cloned, truncated


def _message_entry_pairs(entries: list[SessionEntry]) -> list[tuple[SessionEntry, AgentMessage]]:
    pairs: list[tuple[SessionEntry, AgentMessage]] = []
    for entry in entries:
        if entry.get("type") != "message" or not isinstance(entry.get("message"), dict):
            continue
        pairs.append((entry, message_from_dict(entry["message"])))
    return pairs


def _looks_like_legacy_linear_entries(entries: list[SessionEntry]) -> bool:
    return bool(entries) and all(entry.get("type") == "message" and not entry.get("parentId") for entry in entries)


def _message_plain_text(message: AgentMessage) -> str:
    parts: list[str] = []
    for block in message.content:
        if block.get("type") == "text":
            parts.append(str(block.get("text", "")))
    return "".join(parts)


def _message_non_text_summary(message: AgentMessage) -> str:
    parts: list[str] = []
    for block in message.content:
        block_type = block.get("type")
        if block_type == "toolCall":
            parts.append(f"toolCall {block.get('name')} {_json_dumps(block.get('input') or {})}")
        elif block_type == "toolResult":
            parts.append(f"toolResult {block.get('name')} {_stringify(block.get('output'))}")
        else:
            parts.append(_json_dumps(block))
    return " ".join(parts)


def _int_field(raw: dict[str, Any], *names: str) -> int:
    for name in names:
        value = raw.get(name)
        if isinstance(value, bool):
            continue
        if isinstance(value, int | float):
            return max(0, int(value))
    return 0


def _json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)


def _stringify(value: Any) -> str:
    if isinstance(value, str):
        return value
    return _json_dumps(value)
