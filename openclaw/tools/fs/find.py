"""File discovery tool for workspace paths."""

from __future__ import annotations

import fnmatch
from typing import Any

from openclaw.tools.fs.path_guard import resolve_workspace_path
from openclaw.tools.results import json_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

FIND_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "path": {"type": "string", "description": "Directory path to search. Defaults to cwd."},
        "name": {"type": "string", "description": "Filename glob to match, for example *.py."},
        "type": {"type": "string", "enum": ["file", "directory", "any"]},
        "max_entries": {"type": "integer", "description": "Maximum number of entries to return."},
        "include_hidden": {"type": "boolean", "description": "Whether to include hidden dot paths."},
    },
    "additionalProperties": False,
}


async def execute_find(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    raw_path = str(arguments.get("path") or ".")
    root = resolve_workspace_path(
        raw_path,
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        workspace_only=context.workspace_only,
    )
    if not root.exists():
        raise FileNotFoundError(f"path not found: {root}")
    if not root.is_dir():
        raise NotADirectoryError(f"not a directory: {root}")

    name_pattern = str(arguments.get("name") or "*")
    kind = str(arguments.get("type") or "any")
    max_entries = int(arguments.get("max_entries", 200) or 200)
    include_hidden = bool(arguments.get("include_hidden", False))

    entries: list[dict[str, Any]] = []
    for item in root.rglob("*"):
        relative = item.relative_to(root)
        if not include_hidden and any(part.startswith(".") for part in relative.parts):
            continue
        item_kind = "directory" if item.is_dir() else "file"
        if kind != "any" and item_kind != kind:
            continue
        if not fnmatch.fnmatch(item.name, name_pattern):
            continue
        stat = item.stat()
        entries.append(
            {
                "name": item.name,
                "path": str(item),
                "relativePath": str(relative),
                "type": item_kind,
                "size": stat.st_size,
            }
        )
        if len(entries) >= max_entries:
            break

    return json_result(
        {"path": str(root), "entries": entries},
        details={
            "path": str(root),
            "entryCount": len(entries),
            "truncated": len(entries) >= max_entries,
        },
    )


def create_find_tool() -> ToolDefinition:
    return ToolDefinition(
        name="find",
        label="Find",
        description="Find files or directories in the workspace by name glob.",
        input_schema=FIND_SCHEMA,
        execute=execute_find,
        execution_mode="parallel",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("readonly", "coding", "full"),
            tags=("fs", "find", "readonly"),
            risk="low",
            workspace_only=True,
        ),
    )
