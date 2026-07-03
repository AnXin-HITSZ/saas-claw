"""List-directory tool."""

from __future__ import annotations

from typing import Any

from openclaw.tools.fs.path_guard import resolve_workspace_path
from openclaw.tools.results import json_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

LIST_DIR_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "path": {"type": "string", "description": "Directory path to list, relative to cwd unless absolute."},
        "recursive": {"type": "boolean", "description": "Whether to list files recursively."},
        "include_hidden": {"type": "boolean", "description": "Whether to include dotfiles."},
        "max_entries": {"type": "integer", "description": "Maximum number of entries to return."},
    },
    "required": [],
    "additionalProperties": False,
}


async def execute_list_dir(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    raw_path = str(arguments.get("path") or ".")
    path = resolve_workspace_path(
        raw_path,
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        workspace_only=context.workspace_only,
    )
    if not path.exists():
        raise FileNotFoundError(f"directory not found: {path}")
    if not path.is_dir():
        raise NotADirectoryError(f"not a directory: {path}")

    recursive = bool(arguments.get("recursive", False))
    include_hidden = bool(arguments.get("include_hidden", False))
    max_entries = int(arguments.get("max_entries", 200) or 200)
    iterator = path.rglob("*") if recursive else path.iterdir()
    entries: list[dict[str, Any]] = []

    for item in iterator:
        relative = item.relative_to(path)
        if not include_hidden and any(part.startswith(".") for part in relative.parts):
            continue
        stat = item.stat()
        entries.append(
            {
                "name": item.name,
                "path": str(item),
                "relativePath": str(relative),
                "type": "directory" if item.is_dir() else "file",
                "size": stat.st_size,
            }
        )
        if len(entries) >= max_entries:
            break

    return json_result(
        {"path": str(path), "entries": entries},
        details={
            "path": str(path),
            "entryCount": len(entries),
            "recursive": recursive,
            "truncated": len(entries) >= max_entries,
        },
    )


def create_list_dir_tool() -> ToolDefinition:
    return ToolDefinition(
        name="list_dir",
        label="List Directory",
        description="List files and directories inside the workspace.",
        input_schema=LIST_DIR_SCHEMA,
        execute=execute_list_dir,
        execution_mode="parallel",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("readonly", "coding", "full"),
            tags=("fs", "list", "readonly"),
            risk="low",
            workspace_only=True,
        ),
    )

def create_ls_tool() -> ToolDefinition:
    tool = create_list_dir_tool()
    return ToolDefinition(
        name="ls",
        label="Ls",
        description="Alias for list_dir, compatible with OpenClaw session tools.",
        input_schema=tool.input_schema,
        execute=tool.execute,
        execution_mode=tool.execution_mode,
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("readonly", "coding", "full"),
            tags=("fs", "list", "readonly", "alias"),
            risk="low",
            workspace_only=True,
        ),
    )

