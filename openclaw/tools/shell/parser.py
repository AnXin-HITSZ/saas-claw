"""Shell command tokenization and lightweight AST parsing."""

from __future__ import annotations

import os
import shlex
from dataclasses import dataclass
from typing import Literal

ShellDialect = Literal["cmd", "powershell", "bash"]
CONTROL_OPERATORS = ("&&", "||", "|", ";")
REDIRECT_OPERATORS = (">>", "2>", "1>", ">", "<")


@dataclass(frozen=True)
class ShellCommandSegment:
    raw: str
    argv: tuple[str, ...]
    redirects: tuple[str, ...] = ()
    connector_after: str | None = None


@dataclass(frozen=True)
class ShellCommandAst:
    raw: str
    dialect: ShellDialect
    segments: tuple[ShellCommandSegment, ...]
    parse_errors: tuple[str, ...] = ()

    @property
    def has_control_flow(self) -> bool:
        return any(segment.connector_after for segment in self.segments)


def detect_shell_dialect(command: str = "", *, explicit: str | None = None, platform: str | None = None) -> ShellDialect:
    if explicit:
        normalized = explicit.strip().lower()
        if normalized in {"cmd", "powershell", "bash"}:
            return normalized  # type: ignore[return-value]
    text = command.strip().lower()
    if text.startswith(("powershell ", "powershell.exe ", "pwsh ", "pwsh.exe ")):
        return "powershell"
    if text.startswith(("bash ", "sh ", "wsl ")):
        return "bash"
    platform_name = platform if platform is not None else os.name
    return "cmd" if platform_name == "nt" else "bash"


def parse_shell_command(command: str, *, dialect: ShellDialect | None = None) -> ShellCommandAst:
    resolved = dialect or detect_shell_dialect(command)
    errors: list[str] = []
    pieces = split_command_segments(command, resolved)
    segments: list[ShellCommandSegment] = []
    for raw, connector in pieces:
        try:
            tokens = tokenize_command(raw, resolved)
        except ValueError as exc:
            errors.append(str(exc))
            tokens = fallback_tokenize(raw)
        redirects = tuple(token for token in tokens if is_redirect_token(token))
        argv = tuple(token for token in tokens if not is_redirect_token(token))
        if argv or raw.strip():
            segments.append(ShellCommandSegment(raw=raw.strip(), argv=argv, redirects=redirects, connector_after=connector))
    return ShellCommandAst(raw=command, dialect=resolved, segments=tuple(segments), parse_errors=tuple(errors))


def split_command_segments(command: str, dialect: ShellDialect) -> list[tuple[str, str | None]]:
    pieces: list[tuple[str, str | None]] = []
    start = 0
    quote: str | None = None
    i = 0
    while i < len(command):
        char = command[i]
        if quote:
            if char == quote:
                quote = None
            elif is_escape_char(char, dialect):
                i += 1
            i += 1
            continue
        if char in {'"', "'"}:
            quote = char
            i += 1
            continue
        op = match_control_operator(command, i)
        if op is not None:
            pieces.append((command[start:i], op))
            i += len(op)
            start = i
            continue
        i += 1
    pieces.append((command[start:], None))
    return pieces


def tokenize_command(command: str, dialect: ShellDialect) -> tuple[str, ...]:
    if dialect == "bash":
        return tuple(shlex.split(command, posix=True))
    return tuple(tokenize_windows_like(command, dialect))


def tokenize_windows_like(command: str, dialect: ShellDialect) -> list[str]:
    tokens: list[str] = []
    current: list[str] = []
    quote: str | None = None
    i = 0
    while i < len(command):
        char = command[i]
        if quote:
            if char == quote:
                quote = None
            elif is_escape_char(char, dialect) and i + 1 < len(command):
                i += 1
                current.append(command[i])
            else:
                current.append(char)
            i += 1
            continue
        if char in {'"', "'"}:
            quote = char
        elif char.isspace():
            if current:
                tokens.append("".join(current))
                current = []
        elif is_escape_char(char, dialect) and i + 1 < len(command):
            i += 1
            current.append(command[i])
        else:
            current.append(char)
        i += 1
    if quote:
        raise ValueError("unterminated quoted string")
    if current:
        tokens.append("".join(current))
    return tokens


def fallback_tokenize(command: str) -> tuple[str, ...]:
    return tuple(part for part in command.strip().split() if part)


def match_control_operator(command: str, index: int) -> str | None:
    for op in CONTROL_OPERATORS:
        if command.startswith(op, index):
            return op
    return None


def is_escape_char(char: str, dialect: ShellDialect) -> bool:
    if dialect == "cmd":
        return char == "^"
    if dialect == "powershell":
        return char == "`"
    return char == "\\"


def is_redirect_token(token: str) -> bool:
    return token in REDIRECT_OPERATORS or any(token.startswith(op) and len(token) > len(op) for op in REDIRECT_OPERATORS)