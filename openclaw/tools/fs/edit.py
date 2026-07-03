"""Exact text edit tool."""

from __future__ import annotations

from typing import Any

from openclaw.tools.fs.path_guard import WorkspaceMutationError, ensure_workspace_mutation_allowed, resolve_workspace_path
from openclaw.tools.results import blocked_result, text_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult

EDIT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["path", "old_text", "new_text"],
    "properties": {
        "path": {"type": "string"},
        "old_text": {"type": "string", "description": "Exact text to replace."},
        "new_text": {"type": "string", "description": "Replacement text."},
        "replace_all": {"type": "boolean", "description": "Replace all occurrences instead of exactly one."},
    },
    "additionalProperties": False,
}


async def execute_edit(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    if context.readonly:
        return blocked_result("readonly mode blocks file edits", denied_reason="readonly")
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
    old_text = str(arguments["old_text"])
    new_text = str(arguments["new_text"])
    replace_all = bool(arguments.get("replace_all", False))
    text = path.read_text(encoding="utf-8")
    count = text.count(old_text)
    if count == 0:
        return blocked_result("old_text was not found", denied_reason="no_match")
    if not replace_all and count != 1:
        return blocked_result("old_text matched multiple times; set replace_all=true", denied_reason="ambiguous_match")
    updated = text.replace(old_text, new_text) if replace_all else text.replace(old_text, new_text, 1)
    path.write_text(updated, encoding="utf-8")
    replacements = count if replace_all else 1
    return text_result(
        f"edited {path}: {replacements} replacement(s)",
        details={"path": str(path), "replacements": replacements, "chars": len(updated)},
    )


def create_edit_tool() -> ToolDefinition:
    return ToolDefinition(
        name="edit",
        label="Edit",
        description="Replace exact UTF-8 text in a workspace file.",
        input_schema=EDIT_SCHEMA,
        execute=execute_edit,
        execution_mode="sequential",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("coding", "full"),
            tags=("fs", "edit"),
            risk="medium",
            workspace_only=True,
        ),
    )
