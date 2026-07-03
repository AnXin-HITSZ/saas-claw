"""Approval primitives for shell command execution."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal

from openclaw.tools.shell.guard import ShellCommandClassification

ShellApprovalMode = Literal["auto", "require", "deny"]
ShellApprovalCallback = Callable[["ShellApprovalRequest"], bool]


@dataclass(frozen=True)
class ShellApprovalRequest:
    command: str
    safety: str
    reasons: tuple[str, ...]
    tool_name: str
    session_id: str | None = None


@dataclass(frozen=True)
class ShellApprovalDecision:
    approved: bool
    reason: str
    mode: ShellApprovalMode


def resolve_shell_approval(
    *,
    command: str,
    classification: ShellCommandClassification,
    tool_name: str,
    session_id: str | None,
    metadata: dict[str, Any],
) -> ShellApprovalDecision:
    mode = normalize_approval_mode(metadata.get("shell_approval_mode", "auto"))
    if classification.safety == "readonly":
        return ShellApprovalDecision(True, "readonly command", mode)
    if classification.safety == "dangerous":
        return ShellApprovalDecision(False, "dangerous command is blocked before approval", mode)
    if classification.safety == "unknown" and mode == "auto":
        return ShellApprovalDecision(False, "unknown command requires explicit approval", mode)
    if mode == "deny":
        return ShellApprovalDecision(False, "approval policy denies non-readonly shell commands", mode)

    approved_commands = metadata.get("approved_shell_commands")
    if isinstance(approved_commands, (set, list, tuple)) and command in {str(item) for item in approved_commands}:
        return ShellApprovalDecision(True, "command was pre-approved", mode)

    callback = metadata.get("shell_approval_callback")
    if callable(callback):
        request = ShellApprovalRequest(
            command=command,
            safety=classification.safety,
            reasons=classification.reasons,
            tool_name=tool_name,
            session_id=session_id,
        )
        try:
            approved = bool(callback(request))
        except Exception as exc:
            return ShellApprovalDecision(False, f"approval callback failed: {exc}", mode)
        return ShellApprovalDecision(approved, "approval callback decision", mode)

    if mode == "require":
        return ShellApprovalDecision(False, "shell command requires approval but no approval was provided", mode)
    return ShellApprovalDecision(True, "auto approval for mutation command", mode)


def normalize_approval_mode(value: Any) -> ShellApprovalMode:
    normalized = str(value).strip().lower()
    if normalized in {"auto", "require", "deny"}:
        return normalized  # type: ignore[return-value]
    return "auto"