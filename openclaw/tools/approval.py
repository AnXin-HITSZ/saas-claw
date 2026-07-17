"""Pending tool approval runtime types."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class PendingToolApproval:
    approval_id: str
    session_id: str
    tool_call_id: str
    tool_name: str
    risk: str
    intent_summary: str
    arguments_preview: dict[str, Any]
    pending_state_key: str
    expires_at: str
    claw_id: str | None = None
    owner_user_id: str | None = None
    claw_name: str | None = None
    role_key: str | None = None
    agent_key: str | None = None


class PendingToolApprovalError(Exception):
    """Raised by the tool executor when a tool call requires user approval.

    The Agent loop must not swallow this exception; it must bubble up to the
    API layer so that the caller can persist an approval request and return a
    PENDING_APPROVAL response to the user.
    """

    def __init__(self, approval: PendingToolApproval) -> None:
        super().__init__(f"tool call {approval.tool_name} requires user approval")
        self.approval = approval


@dataclass
class ApprovalRuntimeContext:
    """Runtime context threaded into ``ApprovalToolHooks``.

    Carries the Claw / session identifiers that are needed to bind an
    approval request to the correct owner and to rebuild the runtime later
    when the resume endpoint is called. The context must NOT contain any
    secret material (API keys, encrypted tokens, etc.). Secrets are
    reinjected from Spring on ``/v1/agent/resume``.
    """

    session_id: str
    claw_id: str | None = None
    owner_user_id: str | None = None
    claw_name: str | None = None
    role_key: str | None = None
    agent_key: str | None = None
    sandbox_base_url: str | None = None
    messages_snapshot: list[dict[str, Any]] = field(default_factory=list)
    provider_name: str | None = None
    model: str | None = None
    system_prompt: str | None = None
    api_mode: str | None = None
    tool_profile: str | None = None
    tools_allow: list[str] | None = None
    tools_deny: list[str] | None = None
    tools_also_allow: list[str] | None = None


def summarise_intent(tool_name: str, arguments: dict[str, Any]) -> str:
    """Return a short human-facing description of the pending tool call."""

    if tool_name == "write_file":
        path = arguments.get("path") or arguments.get("file_path") or ""
        return f"准备写入文件 {path}".strip() if path else "准备写入文件"
    if tool_name == "apply_patch":
        path = arguments.get("path") or arguments.get("file_path") or ""
        return f"准备对文件 {path} 应用补丁".strip() if path else "准备应用文件补丁"
    if tool_name == "read_file":
        path = arguments.get("path") or arguments.get("file_path") or ""
        return f"准备读取文件 {path}".strip() if path else "准备读取文件"
    return f"准备执行工具 {tool_name}"


_MAX_PREVIEW_CHARS = 200
_MAX_PREVIEW_ITEMS = 8


def build_arguments_preview(arguments: dict[str, Any]) -> dict[str, Any]:
    """Return a truncated, sanitised copy of the arguments dict."""

    preview: dict[str, Any] = {}
    for index, (key, value) in enumerate(arguments.items()):
        if index >= _MAX_PREVIEW_ITEMS:
            preview["…"] = f"共 {len(arguments)} 项，已截断 {len(arguments) - _MAX_PREVIEW_ITEMS} 项"
            break
        preview[str(key)] = _truncate_value(value)
    return preview


def _truncate_value(value: Any) -> Any:
    if isinstance(value, str):
        if len(value) <= _MAX_PREVIEW_CHARS:
            return value
        return value[:_MAX_PREVIEW_CHARS] + f"…(共 {len(value)} 字符)"
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    if isinstance(value, list):
        return [_truncate_value(item) for item in value[:_MAX_PREVIEW_ITEMS]]
    if isinstance(value, dict):
        return {str(k): _truncate_value(v) for k, v in list(value.items())[:_MAX_PREVIEW_ITEMS]}
    text = str(value)
    if len(text) <= _MAX_PREVIEW_CHARS:
        return text
    return text[:_MAX_PREVIEW_CHARS] + f"…(共 {len(text)} 字符)"


__all__ = [
    "PendingToolApproval",
    "PendingToolApprovalError",
    "ApprovalRuntimeContext",
    "summarise_intent",
    "build_arguments_preview",
]
