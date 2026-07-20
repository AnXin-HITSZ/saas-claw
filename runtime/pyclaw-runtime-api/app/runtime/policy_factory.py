"""Tool policy factory — builds ToolPolicy and ToolRegistry from request parameters."""

from __future__ import annotations

from typing import Optional

from openclaw.tools.policy import ToolPolicy
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.registry import ToolRegistry


def build_policy(
    tool_profile: Optional[str] = None,
    tools_allow: Optional[list[str]] = None,
    tools_deny: Optional[list[str]] = None,
    tools_also_allow: Optional[list[str]] = None,
    readonly: bool = False,
) -> ToolPolicy:
    """Build a ToolPolicy from runtime request parameters."""
    return ToolPolicy(
        profile=tool_profile,
        allow=tools_allow or [],
        deny=tools_deny or [],
        also_allow=tools_also_allow or [],
        readonly=readonly,
    )


def build_registry(policy: ToolPolicy) -> ToolRegistry:
    """Build a ToolRegistry by applying the given policy to core tools."""
    return build_tool_registry(policy)
