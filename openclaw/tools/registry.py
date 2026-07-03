"""Tool definitions and registry."""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Protocol

from openclaw.tools.results import ensure_tool_result
from openclaw.tools.schema import validate_tool_arguments
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult


class Tool(Protocol):
    name: str
    description: str
    input_schema: dict[str, Any]
    parallel: bool

    async def __call__(self, **kwargs: Any) -> Any:
        ...


@dataclass
class FunctionTool:
    name: str
    description: str
    func: Callable[..., Any | Awaitable[Any]]
    input_schema: dict[str, Any] = field(default_factory=lambda: {"type": "object", "properties": {}})
    parallel: bool = False

    async def __call__(self, **kwargs: Any) -> Any:
        result = self.func(**kwargs)
        if inspect.isawaitable(result):
            return await result
        return result


ToolLike = Tool | ToolDefinition


@dataclass
class ToolRegistry:
    tools: dict[str, ToolLike] = field(default_factory=dict)

    def register(self, tool: ToolLike) -> None:
        if not tool.name:
            raise ValueError("tool name cannot be empty")
        self.tools[tool.name] = tool

    def resolve(self, name: str) -> ToolLike | None:
        return self.tools.get(name)

    def resolve_definition(self, name: str) -> ToolDefinition | None:
        tool = self.resolve(name)
        if tool is None:
            return None
        return normalize_tool(tool)

    def to_llm_tools(self) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for tool_like in self.tools.values():
            tool = normalize_tool(tool_like)
            if not tool.metadata.expose_to_llm:
                continue
            items.append(
                {
                    "name": tool.name,
                    "description": tool.description,
                    "input_schema": tool.input_schema,
                }
            )
        return items

    def validate_input(self, tool: ToolLike, value: dict[str, Any]) -> None:
        validate_tool_arguments(normalize_tool(tool).input_schema, value)


def normalize_tool(tool: ToolLike) -> ToolDefinition:
    if isinstance(tool, ToolDefinition):
        return tool
    return wrap_function_tool(tool)


def wrap_function_tool(tool: Tool) -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
        raw = await tool(**arguments)
        return ensure_tool_result(raw)

    return ToolDefinition(
        name=tool.name,
        label=tool.name,
        description=tool.description,
        input_schema=tool.input_schema,
        execute=execute,
        execution_mode="parallel" if getattr(tool, "parallel", False) else "sequential",
        metadata=ToolMetadata(),
    )
