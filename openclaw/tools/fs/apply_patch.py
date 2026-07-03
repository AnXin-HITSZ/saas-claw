"""Conservative patch tool based on exact replacement."""

from __future__ import annotations

from typing import Any

from openclaw.tools.fs.edit import execute_edit
from openclaw.tools.types import ToolDefinition, ToolMetadata

APPLY_PATCH_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["path", "old_text", "new_text"],
    "properties": {
        "path": {"type": "string"},
        "old_text": {"type": "string", "description": "Exact text to replace."},
        "new_text": {"type": "string", "description": "Replacement text."},
        "replace_all": {"type": "boolean"},
    },
    "additionalProperties": False,
}


def create_apply_patch_tool() -> ToolDefinition:
    return ToolDefinition(
        name="apply_patch",
        label="Apply Patch",
        description="Apply a conservative exact-text patch to a workspace file.",
        input_schema=APPLY_PATCH_SCHEMA,
        execute=execute_edit,
        execution_mode="sequential",
        metadata=ToolMetadata(
            section_id="filesystem",
            profiles=("coding", "full"),
            tags=("fs", "edit", "patch"),
            risk="medium",
            workspace_only=True,
        ),
    )
