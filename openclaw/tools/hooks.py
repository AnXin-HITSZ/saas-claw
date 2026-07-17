"""Hook protocol for tool execution."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal, Protocol

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.approval import PendingToolApproval
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolResult

ToolExecutionDecisionStatus = Literal["ALLOW", "PENDING_APPROVAL", "DENY"]


@dataclass
class ToolExecutionDecision:
    """Strongly typed decision returned by ``ToolHooks.before_tool_call``.

    - ALLOW: run ``tool.execute`` with ``arguments``.
    - PENDING_APPROVAL: pause the Agent loop, persist ``approval``, bubble a
      ``PendingToolApprovalError`` up to the API layer.
    - DENY: skip ``tool.execute``, return a blocked tool result to the Agent
      loop so the model can react.
    """

    status: ToolExecutionDecisionStatus = "ALLOW"
    reason: str | None = None
    denied_reason: str | None = None
    arguments: dict[str, Any] | None = None
    approval: PendingToolApproval | None = None


class ToolHooks(Protocol):
    async def before_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        context: ToolExecutionContext,
    ) -> ToolExecutionDecision:
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
    ) -> ToolExecutionDecision:
        return ToolExecutionDecision(status="ALLOW", arguments=arguments)

    async def after_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        result: ToolResult,
        context: ToolExecutionContext,
    ) -> ToolResult:
        return result


__all__ = [
    "ToolExecutionDecisionStatus",
    "ToolExecutionDecision",
    "ToolHooks",
    "NoopToolHooks",
]
