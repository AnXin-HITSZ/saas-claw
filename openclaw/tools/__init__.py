"""Tool registration and execution utilities."""

from openclaw.tools.approval import (
    ApprovalRuntimeContext,
    PendingToolApproval,
    PendingToolApprovalError,
)
from openclaw.tools.builder import build_tool_registry, core_tool_definitions
from openclaw.tools.hooks import NoopToolHooks, ToolExecutionDecision, ToolHooks
from openclaw.tools.registry import FunctionTool, Tool, ToolRegistry, normalize_tool
from openclaw.tools.results import blocked_result, error_result, json_result, text_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolProgress, ToolResult

__all__ = [
    "ApprovalRuntimeContext",
    "FunctionTool",
    "NoopToolHooks",
    "PendingToolApproval",
    "PendingToolApprovalError",
    "Tool",
    "ToolDefinition",
    "ToolExecutionContext",
    "ToolExecutionDecision",
    "ToolHooks",
    "ToolMetadata",
    "ToolProgress",
    "ToolRegistry",
    "ToolResult",
    "blocked_result",
    "build_tool_registry",
    "core_tool_definitions",
    "error_result",
    "json_result",
    "normalize_tool",
    "text_result",
]
