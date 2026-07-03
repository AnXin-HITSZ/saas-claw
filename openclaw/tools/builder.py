"""Build default tool registries from catalog/profile policy."""

from __future__ import annotations

from openclaw.tools.catalog import materialize_core_tools
from openclaw.tools.policy import ToolPolicy, apply_tool_policy
from openclaw.tools.registry import ToolRegistry
from openclaw.tools.types import ToolDefinition


def core_tool_definitions() -> list[ToolDefinition]:
    return materialize_core_tools()


def build_tool_registry(policy: ToolPolicy | None = None) -> ToolRegistry:
    policy = policy or ToolPolicy()
    registry = ToolRegistry()
    for tool in apply_tool_policy(core_tool_definitions(), policy):
        registry.register(tool)
    return registry