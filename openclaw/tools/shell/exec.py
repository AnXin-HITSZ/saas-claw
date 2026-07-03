"""Shell command execution tools."""

from __future__ import annotations

import asyncio
import os
import re
import subprocess
from typing import Any

from openclaw.tools.fs.path_guard import resolve_workspace_path
from openclaw.tools.results import blocked_result, json_result
from openclaw.tools.shell.approval import resolve_shell_approval
from openclaw.tools.shell.guard import ShellCommandClassification, classify_shell_command
from openclaw.tools.shell.parser import ShellCommandAst, ShellCommandSegment, detect_shell_dialect
from openclaw.tools.shell.sandbox import resolve_shell_sandbox
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

SHELL_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["command"],
    "properties": {
        "command": {"type": "string", "description": "Command to execute."},
        "cwd": {"type": "string", "description": "Working directory inside the workspace."},
        "workdir": {"type": "string", "description": "OpenClaw-compatible alias for cwd."},
        "shell": {"type": "string", "enum": ["cmd", "powershell", "bash"], "description": "Parser dialect."},
        "env": {"type": "object", "additionalProperties": {"type": "string"}},
        "timeout_seconds": {"type": "integer"},
        "timeout": {"type": "integer", "description": "OpenClaw-compatible alias for timeout_seconds."},
        "max_chars": {"type": "integer"},
    },
    "additionalProperties": False,
}

EXEC_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["command"],
    "properties": {
        "command": {"type": "string", "description": "Shell command to execute."},
        "workdir": {"type": "string", "description": "Working directory inside the workspace."},
        "shell": {"type": "string", "enum": ["cmd", "powershell", "bash"], "description": "Parser dialect."},
        "env": {"type": "object", "additionalProperties": {"type": "string"}},
        "timeout": {"type": "integer", "description": "Timeout in seconds."},
        "max_chars": {"type": "integer"},
    },
    "additionalProperties": False,
}

DANGEROUS_COMMAND_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\brm\s+-[^\n]*[rf][^\n]*\s+[/~.]", re.IGNORECASE),
    re.compile(r"\bdel\s+[^\n]*(/[fsq]\s+)+[^\n]*", re.IGNORECASE),
    re.compile(r"\brmdir\s+[^\n]*/s\b", re.IGNORECASE),
    re.compile(r"\bremove-item\b[^\n]*\b-recurse\b[^\n]*\b-force\b", re.IGNORECASE),
    re.compile(r"\bgit\s+reset\s+--hard\b", re.IGNORECASE),
    re.compile(r"\bgit\s+clean\b[^\n]*\s-[^\n]*f", re.IGNORECASE),
    re.compile(r"\bformat\s+[a-z]:", re.IGNORECASE),
    re.compile(r"\bshutdown\b", re.IGNORECASE),
    re.compile(r"\breg\s+delete\b", re.IGNORECASE),
)

MAX_COMMAND_CHARS = 4000
MAX_TIMEOUT_SECONDS = 120
MIN_MAX_CHARS = 1000
MAX_OUTPUT_CHARS = 100000


async def execute_shell(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    command = str(arguments["command"])
    dialect = detect_shell_dialect(command, explicit=str(arguments.get("shell") or "") or None)
    classification = classify_shell_command(command, dialect=dialect)
    denied_reason = validate_shell_command(command, classification)
    if denied_reason is not None:
        return blocked_result(
            denied_reason,
            denied_reason="dangerous_command",
            details={"classification": classification_to_dict(classification)},
        )
    if context.readonly and classification.safety != "readonly":
        return blocked_result(
            "readonly mode blocks non-readonly shell commands",
            denied_reason="readonly",
            details={"classification": classification_to_dict(classification)},
        )

    approval = resolve_shell_approval(
        command=command,
        classification=classification,
        tool_name=context.tool_name,
        session_id=context.session_id,
        metadata=context.metadata,
    )
    if not approval.approved:
        return blocked_result(
            approval.reason,
            denied_reason="approval_required",
            details={"classification": classification_to_dict(classification), "approval": approval.__dict__},
        )

    cwd_arg = str(arguments.get("workdir") or arguments.get("cwd") or ".")
    cwd = resolve_workspace_path(
        cwd_arg,
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        workspace_only=context.workspace_only,
    )
    sandbox = resolve_shell_sandbox(cwd=cwd, workspace_dir=context.workspace_dir, metadata=context.metadata)
    if not sandbox.allowed:
        return blocked_result(
            sandbox.reason,
            denied_reason="sandbox_unavailable",
            details={"classification": classification_to_dict(classification), "sandbox": sandbox_to_dict(sandbox)},
        )

    timeout = normalize_timeout(arguments.get("timeout") or arguments.get("timeout_seconds", 30) or 30)
    max_chars = normalize_max_chars(arguments.get("max_chars", 20000) or 20000)
    env_override = arguments.get("env") if isinstance(arguments.get("env"), dict) else {}
    env = os.environ.copy()
    env.update({str(key): str(value) for key, value in env_override.items()})

    def run() -> dict[str, Any]:
        try:
            completed = subprocess.run(
                command,
                cwd=str(sandbox.cwd),
                shell=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
                env=env,
            )
            stdout = completed.stdout or ""
            stderr = completed.stderr or ""
            return {
                "command": command,
                "cwd": str(sandbox.cwd),
                "exit_code": completed.returncode,
                "stdout": stdout[-max_chars:],
                "stderr": stderr[-max_chars:],
                "timed_out": False,
                "timeout_seconds": timeout,
                "max_chars": max_chars,
                "classification": classification_to_dict(classification),
                "approval": approval.__dict__,
                "sandbox": sandbox_to_dict(sandbox),
            }
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout or ""
            stderr = exc.stderr or ""
            return {
                "command": command,
                "cwd": str(sandbox.cwd),
                "exit_code": None,
                "stdout": str(stdout)[-max_chars:],
                "stderr": str(stderr)[-max_chars:],
                "timed_out": True,
                "timeout_seconds": timeout,
                "max_chars": max_chars,
                "classification": classification_to_dict(classification),
                "approval": approval.__dict__,
                "sandbox": sandbox_to_dict(sandbox),
            }

    payload = await asyncio.to_thread(run)
    return json_result(payload, is_error=bool(payload["timed_out"] or payload["exit_code"] not in (0, None)))


def validate_shell_command(command: str, classification: ShellCommandClassification | None = None) -> str | None:
    if not command.strip():
        return "empty shell command is not allowed"
    if "\x00" in command:
        return "shell command contains a NUL byte"
    if len(command) > MAX_COMMAND_CHARS:
        return f"shell command is too long: {len(command)} chars"
    if classification is not None and classification.ast.parse_errors:
        return "shell command could not be parsed safely"
    for pattern in DANGEROUS_COMMAND_PATTERNS:
        if pattern.search(command):
            return "shell command matches a blocked destructive pattern"
    if classification is not None and classification.safety == "dangerous":
        return "shell command is classified as dangerous"
    return None


def normalize_timeout(value: Any) -> int:
    try:
        timeout = int(value)
    except (TypeError, ValueError):
        timeout = 30
    return max(1, min(timeout, MAX_TIMEOUT_SECONDS))


def normalize_max_chars(value: Any) -> int:
    try:
        max_chars = int(value)
    except (TypeError, ValueError):
        max_chars = 20000
    return max(MIN_MAX_CHARS, min(max_chars, MAX_OUTPUT_CHARS))


def classification_to_dict(classification: ShellCommandClassification) -> dict[str, Any]:
    return {
        "safety": classification.safety,
        "reasons": list(classification.reasons),
        "requiresApproval": classification.requires_approval,
        "ast": ast_to_dict(classification.ast),
    }


def ast_to_dict(ast: ShellCommandAst) -> dict[str, Any]:
    return {
        "raw": ast.raw,
        "dialect": ast.dialect,
        "parseErrors": list(ast.parse_errors),
        "segments": [segment_to_dict(segment) for segment in ast.segments],
    }


def segment_to_dict(segment: ShellCommandSegment) -> dict[str, Any]:
    return {
        "raw": segment.raw,
        "argv": list(segment.argv),
        "redirects": list(segment.redirects),
        "connectorAfter": segment.connector_after,
    }


def sandbox_to_dict(sandbox: Any) -> dict[str, Any]:
    return {
        "allowed": bool(sandbox.allowed),
        "mode": sandbox.mode,
        "reason": sandbox.reason,
        "cwd": str(sandbox.cwd),
    }

def create_shell_tool() -> ToolDefinition:
    return ToolDefinition(
        name="shell",
        label="Shell",
        description="Execute a shell command inside the workspace and capture stdout/stderr.",
        input_schema=SHELL_SCHEMA,
        execute=execute_shell,
        execution_mode="sequential",
        metadata=ToolMetadata(
            section_id="runtime",
            profiles=("full",),
            tags=("runtime", "shell", "exec", "legacy", "mutation", "high-risk"),
            risk="high",
            workspace_only=True,
        ),
    )


def create_exec_tool() -> ToolDefinition:
    return ToolDefinition(
        name="exec",
        label="Exec",
        description="Execute a shell command inside the workspace. This is the OpenClaw-compatible shell entry point.",
        input_schema=EXEC_SCHEMA,
        execute=execute_shell,
        execution_mode="sequential",
        metadata=ToolMetadata(
            section_id="runtime",
            profiles=("full",),
            tags=("runtime", "shell", "exec", "mutation", "high-risk"),
            risk="high",
            workspace_only=True,
        ),
    )