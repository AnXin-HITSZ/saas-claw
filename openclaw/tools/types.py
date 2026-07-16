"""Core tool runtime types."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

from openclaw.llm.types import ContentBlock

ExecutionMode = Literal["sequential", "parallel"]
ToolRisk = Literal["low", "medium", "high"]
ToolSource = Literal["core", "plugin", "mcp", "client", "runtime"]


@dataclass
class ToolProgress:
    current: int | None = None
    total: int | None = None
    label: str | None = None
    status: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            key: value
            for key, value in {
                "current": self.current,
                "total": self.total,
                "label": self.label,
                "status": self.status,
            }.items()
            if value is not None
        }


@dataclass
class ToolResult:
    content: list[ContentBlock]
    details: dict[str, Any] = field(default_factory=dict)
    progress: ToolProgress | dict[str, Any] | None = None
    terminate: bool = False
    is_error: bool = False

    def output(self) -> Any:
        if len(self.content) == 1 and self.content[0].get("type") == "text":
            return self.content[0].get("text", "")
        return list(self.content)

    def progress_dict(self) -> dict[str, Any] | None:
        if self.progress is None:
            return None
        if isinstance(self.progress, ToolProgress):
            return self.progress.to_dict()
        return dict(self.progress)


@dataclass
class ToolMetadata:
    section_id: str | None = None
    profiles: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    risk: ToolRisk = "low"
    source: ToolSource = "core"
    plugin_id: str | None = None
    expose_to_llm: bool = True
    readonly: bool = False
    requires_approval: bool = False
    workspace_only: bool = True


@dataclass
class ToolExecutionContext:
    tool_call_id: str
    tool_name: str
    cwd: Path
    workspace_dir: Path
    session_id: str | None = None
    chatdata_dir: Path | None = None
    model: str | None = None
    provider: str | None = None
    emit: Callable[[Any], None] | None = None
    readonly: bool = False
    workspace_only: bool = True
    metadata: dict[str, Any] = field(default_factory=dict)


ToolExecute = Callable[[ToolExecutionContext, dict[str, Any]], ToolResult | Awaitable[ToolResult]]
ToolArgumentPreparer = Callable[[dict[str, Any]], dict[str, Any]]


@dataclass
class ToolDefinition:
    name: str
    label: str
    description: str
    input_schema: dict[str, Any]
    execute: ToolExecute
    prepare_arguments: ToolArgumentPreparer | None = None
    execution_mode: ExecutionMode = "sequential"
    metadata: ToolMetadata = field(default_factory=ToolMetadata)


