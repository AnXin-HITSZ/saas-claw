"""Read-file tool."""

from __future__ import annotations

from typing import Any

from openclaw.tools.fs.path_guard import resolve_workspace_path
from openclaw.tools.results import error_result, text_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

READ_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["path"],
    "properties": {
        "path": {"type": "string", "description": "Path to read, relative to cwd unless absolute."},
        "offset": {"type": "integer", "description": "Zero-based line offset."},
        "limit": {"type": "integer", "description": "Maximum number of lines to return."},
        "max_chars": {"type": "integer", "description": "Maximum number of characters to return."},
    },
    "additionalProperties": False,
}


async def execute_read(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    path = resolve_workspace_path(
        str(arguments["path"]),
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        workspace_only=context.workspace_only,
    )
    if path.is_dir():
        return error_result(
            f"path is a directory, use list_dir instead: {path}",
            details={"status": "is_directory", "path": str(path), "suggestedTool": "list_dir"},
        )
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    offset = int(arguments.get("offset", 0) or 0)
    limit = arguments.get("limit")
    selected_lines = lines[offset: offset + int(limit)] if limit is not None else lines[offset:]
    output = "\n".join(selected_lines)
    max_chars = int(arguments.get("max_chars", 20000) or 20000)
    truncated = len(output) > max_chars
    if truncated:
        output = output[:max_chars]
    return text_result(
        output,
        details={
            "path": str(path),
            "chars": len(text),
            "returnedChars": len(output),
            "lineCount": len(lines),
            "offset": offset,
            "limit": limit,
            "truncated": truncated,
        },
    )


def create_read_tool() -> ToolDefinition:
    return ToolDefinition(
        name="read",
        label="Read",
        description="Read a UTF-8 text file from the workspace.",
        input_schema=READ_SCHEMA,
        execute=execute_read,
        execution_mode="parallel",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("readonly", "coding", "full"),
            tags=("fs", "read", "readonly"),
            risk="low",
            workspace_only=True,
        ),
    )
