"""Policy pipeline helpers for selecting and constraining tools."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Literal

from openclaw.tools.catalog import build_tool_groups
from openclaw.tools.types import ToolDefinition

ToolProfile = Literal["minimal", "readonly", "coding", "messaging", "full"]

PROFILE_TAGS: dict[str, set[str]] = {
    "minimal": set(),
    "readonly": {"readonly"},
    "coding": {"readonly", "coding"},
    "messaging": {"messaging"},
    "full": {"readonly", "coding", "messaging", "full"},
}

CORE_TOOL_GROUPS: dict[str, set[str]] = build_tool_groups()


@dataclass
class ToolPolicy:
    allow: set[str] | None = None
    deny: set[str] = field(default_factory=set)
    profile: ToolProfile = "coding"
    also_allow: set[str] = field(default_factory=set)
    workspace_only: bool = True
    readonly: bool = False


@dataclass(frozen=True)
class ToolPolicyStage:
    name: str
    reason: str
    filter_tool: Callable[[ToolDefinition], bool]


@dataclass(frozen=True)
class ToolPolicyAuditEntry:
    stage: str
    reason: str
    before: tuple[str, ...]
    after: tuple[str, ...]
    removed: tuple[str, ...]


@dataclass(frozen=True)
class ToolPolicyPipelineResult:
    tools: list[ToolDefinition]
    audit: list[ToolPolicyAuditEntry]


def apply_tool_policy(tools: list[ToolDefinition], policy: ToolPolicy) -> list[ToolDefinition]:
    return apply_tool_policy_pipeline(tools, policy).tools


def apply_tool_policy_pipeline(tools: list[ToolDefinition], policy: ToolPolicy) -> ToolPolicyPipelineResult:
    selected = list(tools)
    audit: list[ToolPolicyAuditEntry] = []

    for stage in build_policy_stages(policy):
        before = tuple(tool.name for tool in selected)
        selected = [tool for tool in selected if stage.filter_tool(tool)]
        after = tuple(tool.name for tool in selected)
        removed = tuple(name for name in before if name not in set(after))
        audit.append(
            ToolPolicyAuditEntry(
                stage=stage.name,
                reason=stage.reason,
                before=before,
                after=after,
                removed=removed,
            )
        )

    return ToolPolicyPipelineResult(tools=selected, audit=audit)


def build_policy_stages(policy: ToolPolicy) -> list[ToolPolicyStage]:
    allow = expand_tool_names(policy.allow) if policy.allow is not None else None
    also_allow = expand_tool_names(policy.also_allow)
    deny = expand_tool_names(policy.deny)
    allowed_profiles = PROFILE_TAGS.get(policy.profile, PROFILE_TAGS["coding"])
    stages: list[ToolPolicyStage] = []

    if allow is None:
        stages.append(
            ToolPolicyStage(
                name="profile",
                reason=f"profile={policy.profile}",
                filter_tool=lambda tool: (
                    tool.name in also_allow
                    or not tool.metadata.profiles
                    or bool(allowed_profiles.intersection(tool.metadata.profiles))
                ),
            )
        )
    else:
        stages.append(
            ToolPolicyStage(
                name="allow",
                reason="explicit allow list",
                filter_tool=lambda tool: tool.name in allow or tool.name in also_allow,
            )
        )

    if policy.readonly:
        stages.append(
            ToolPolicyStage(
                name="readonly",
                reason="readonly policy",
                filter_tool=lambda tool: "readonly" in tool.metadata.tags,
            )
        )


    if deny:
        stages.append(
            ToolPolicyStage(
                name="deny",
                reason="explicit deny list",
                filter_tool=lambda tool: tool.name not in deny,
            )
        )

    return stages


def expand_tool_names(names: set[str] | None) -> set[str]:
    if not names:
        return set()
    expanded: set[str] = set()
    groups = build_tool_groups()
    for name in names:
        normalized = normalize_tool_name(name)
        expanded.update(groups.get(normalized, {normalized}))
    return expanded


def normalize_tool_name(name: str) -> str:
    return name.strip().lower().replace("-", "_")


def is_tool_allowed(tool: ToolDefinition, policy: ToolPolicy) -> bool:
    return bool(apply_tool_policy([tool], policy))