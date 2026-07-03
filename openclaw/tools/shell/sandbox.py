"""Sandbox policy abstraction for shell command execution."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

ShellSandboxMode = Literal["none", "workspace", "real_os"]


@dataclass(frozen=True)
class ShellSandboxDecision:
    allowed: bool
    mode: ShellSandboxMode
    reason: str
    cwd: Path


def resolve_shell_sandbox(*, cwd: Path, workspace_dir: Path, metadata: dict[str, Any]) -> ShellSandboxDecision:
    mode = normalize_sandbox_mode(metadata.get("shell_sandbox", "workspace"))
    if mode == "none":
        return ShellSandboxDecision(True, mode, "no additional OS sandbox requested", cwd)
    if mode == "workspace":
        return ShellSandboxDecision(True, mode, "workspace cwd boundary", cwd)
    if metadata.get("real_os_sandbox_available") is True:
        return ShellSandboxDecision(True, mode, "external real OS sandbox is marked available", cwd)
    return ShellSandboxDecision(False, mode, "real OS sandbox is not available in this Python runtime", cwd)


def normalize_sandbox_mode(value: Any) -> ShellSandboxMode:
    normalized = str(value).strip().lower()
    if normalized in {"none", "workspace", "real_os"}:
        return normalized  # type: ignore[return-value]
    return "workspace"