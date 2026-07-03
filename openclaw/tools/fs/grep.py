"""Text search tool for workspace files."""

from __future__ import annotations

import fnmatch
import re
from pathlib import Path
from typing import Any

from openclaw.tools.fs.path_guard import resolve_workspace_path
from openclaw.tools.results import json_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

GREP_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["pattern"],
    "properties": {
        "pattern": {"type": "string", "description": "Regex or literal text to search for."},
        "path": {"type": "string", "description": "File or directory path to search. Defaults to cwd."},
        "glob": {"type": "string", "description": "Optional filename glob such as *.py."},
        "case_sensitive": {"type": "boolean", "description": "Whether matching is case-sensitive."},
        "regex": {"type": "boolean", "description": "Treat pattern as a regular expression. Defaults to true."},
        "max_matches": {"type": "integer", "description": "Maximum number of matches to return."},
        "max_file_bytes": {"type": "integer", "description": "Skip files larger than this many bytes."},
    },
    "additionalProperties": False,
}


async def execute_grep(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    raw_path = str(arguments.get("path") or ".")
    root = resolve_workspace_path(
        raw_path,
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        workspace_only=context.workspace_only,
    )
    pattern_text = str(arguments["pattern"])
    case_sensitive = bool(arguments.get("case_sensitive", True))
    use_regex = bool(arguments.get("regex", True))
    max_matches = int(arguments.get("max_matches", 100) or 100)
    max_file_bytes = int(arguments.get("max_file_bytes", 1_000_000) or 1_000_000)
    glob_pattern = arguments.get("glob")
    flags = 0 if case_sensitive else re.IGNORECASE
    pattern = re.compile(pattern_text if use_regex else re.escape(pattern_text), flags)

    matches: list[dict[str, Any]] = []
    scanned_files = 0
    skipped_files = 0

    for file_path in _iter_files(root):
        if glob_pattern and not fnmatch.fnmatch(file_path.name, str(glob_pattern)):
            continue
        try:
            stat = file_path.stat()
        except OSError:
            skipped_files += 1
            continue
        if stat.st_size > max_file_bytes:
            skipped_files += 1
            continue
        scanned_files += 1
        try:
            lines = file_path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            skipped_files += 1
            continue
        for line_number, line in enumerate(lines, start=1):
            if pattern.search(line):
                matches.append(
                    {
                        "path": str(file_path),
                        "relativePath": _display_path(file_path, context.workspace_dir),
                        "line": line_number,
                        "text": line,
                    }
                )
                if len(matches) >= max_matches:
                    return _grep_result(root, matches, scanned_files, skipped_files, True)

    return _grep_result(root, matches, scanned_files, skipped_files, False)


def _iter_files(root: Path) -> list[Path]:
    if root.is_file():
        return [root]
    if not root.exists():
        raise FileNotFoundError(f"path not found: {root}")
    if not root.is_dir():
        raise FileNotFoundError(f"not a searchable path: {root}")
    return [item for item in root.rglob("*") if item.is_file()]


def _display_path(path: Path, workspace_dir: Path) -> str:
    try:
        return str(path.relative_to(workspace_dir.resolve()))
    except ValueError:
        return str(path)


def _grep_result(
    root: Path,
    matches: list[dict[str, Any]],
    scanned_files: int,
    skipped_files: int,
    truncated: bool,
) -> ToolResult:
    return json_result(
        {"path": str(root), "matches": matches},
        details={
            "path": str(root),
            "matchCount": len(matches),
            "scannedFiles": scanned_files,
            "skippedFiles": skipped_files,
            "truncated": truncated,
        },
    )


def create_grep_tool() -> ToolDefinition:
    return ToolDefinition(
        name="grep",
        label="Grep",
        description="Search UTF-8 workspace files for text or regular-expression matches.",
        input_schema=GREP_SCHEMA,
        execute=execute_grep,
        execution_mode="parallel",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("readonly", "coding", "full"),
            tags=("fs", "search", "readonly"),
            risk="low",
            workspace_only=True,
        ),
    )
