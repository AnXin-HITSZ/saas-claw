"""Tool registration and execution utilities."""

from openclaw.tools.builder import build_tool_registry, core_tool_definitions
from openclaw.tools.hooks import NoopToolHooks, ToolHookDecision, ToolHooks
from openclaw.tools.registry import FunctionTool, Tool, ToolRegistry, normalize_tool
from openclaw.tools.results import blocked_result, error_result, json_result, text_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolProgress, ToolResult

__all__ = [
    "FunctionTool",
    "NoopToolHooks",
    "Tool",
    "ToolDefinition",
    "ToolExecutionContext",
    "ToolHookDecision",
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
