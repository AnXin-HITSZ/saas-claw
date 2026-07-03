"""Write-file tool."""

from __future__ import annotations

from typing import Any

from openclaw.tools.fs.path_guard import WorkspaceMutationError, ensure_workspace_mutation_allowed, resolve_workspace_path
from openclaw.tools.results import blocked_result, text_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

WRITE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["path", "content"],
    "properties": {
        "path": {"type": "string", "description": "Path to write, relative to cwd unless absolute."},
        "content": {"type": "string", "description": "UTF-8 text content to write."},
        "overwrite": {"type": "boolean", "description": "Whether to overwrite an existing file."},
        "create_dirs": {"type": "boolean", "description": "Whether to create missing parent directories."},
    },
    "additionalProperties": False,
}


async def execute_write(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    if context.readonly:
        return blocked_result("readonly mode blocks file writes", denied_reason="readonly")
    path = resolve_workspace_path(
        str(arguments["path"]),
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        workspace_only=context.workspace_only,
    )
    try:
        ensure_workspace_mutation_allowed(path, workspace_dir=context.workspace_dir)
    except WorkspaceMutationError as exc:
        return blocked_result(str(exc), denied_reason="protected_path")
    overwrite = bool(arguments.get("overwrite", True))
    create_dirs = bool(arguments.get("create_dirs", True))
    existed = path.exists()
    if existed and not overwrite:
        return blocked_result(f"file already exists: {path}", denied_reason="overwrite_false")
    if create_dirs:
        path.parent.mkdir(parents=True, exist_ok=True)
    content = str(arguments["content"])
    path.write_text(content, encoding="utf-8")
    return text_result(
        f"wrote {len(content)} chars to {path}",
        details={"path": str(path), "chars": len(content), "overwrote": existed},
    )


def create_write_tool() -> ToolDefinition:
    return ToolDefinition(
        name="write",
        label="Write",
        description="Write UTF-8 text to a workspace file.",
        input_schema=WRITE_SCHEMA,
        execute=execute_write,
        execution_mode="sequential",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("coding", "full"),
            tags=("fs", "write"),
            risk="medium",
            workspace_only=True,
        ),
    )
