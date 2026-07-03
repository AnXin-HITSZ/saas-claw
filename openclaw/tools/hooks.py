"""Hook protocol for tool execution."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolResult


@dataclass
class ToolHookDecision:
    allowed: bool = True
    reason: str | None = None
    denied_reason: str | None = None
    arguments: dict[str, Any] | None = None


class ToolHooks(Protocol):
    async def before_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        context: ToolExecutionContext,
    ) -> ToolHookDecision:
        ...

    async def after_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        result: ToolResult,
        context: ToolExecutionContext,
    ) -> ToolResult:
        ...


class NoopToolHooks:
    async def before_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        context: ToolExecutionContext,
    ) -> ToolHookDecision:
        return ToolHookDecision(arguments=arguments)

    async def after_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        result: ToolResult,
        context: ToolExecutionContext,
    ) -> ToolResult:
        return result
