"""Resolve user-facing tools for a concrete Claw runtime context."""

from __future__ import annotations

from dataclasses import dataclass, field

from openclaw.tools.catalog import ToolCatalogEntry, list_catalog_entries, materialize_core_tools
from openclaw.tools.policy import ToolPolicy, apply_tool_policy_pipeline, expand_tool_names


@dataclass(frozen=True)
class ToolResolveInput:
    profile: str = "coding"
    allow: set[str] | None = None
    deny: set[str] = field(default_factory=set)
    also_allow: set[str] = field(default_factory=set)
    readonly: bool = False


@dataclass(frozen=True)
class ResolvedTool:
    name: str
    label: str
    description: str
    section_id: str
    execution_scope: str
    profiles: tuple[str, ...]
    tags: tuple[str, ...]
    risk: str
    readonly: bool
    prompt_hint: str


@dataclass(frozen=True)
class DeniedTool:
    name: str
    reason: str


@dataclass(frozen=True)
class PromptFragment:
    key: str
    content: str


@dataclass(frozen=True)
class ToolResolveResult:
    profile: str
    tools: list[ResolvedTool]
    denied_tools: list[DeniedTool]
    prompt_fragments: list[PromptFragment]


def user_visible_catalog() -> list[ToolCatalogEntry]:
    return [entry for entry in list_catalog_entries() if entry.user_visible]


def resolve_tools(request: ToolResolveInput) -> ToolResolveResult:
    policy = build_runtime_policy(request)
    pipeline = apply_tool_policy_pipeline(materialize_core_tools(), policy)
    selected = {tool.name for tool in pipeline.tools}

    visible_entries = user_visible_catalog()
    available = [to_resolved_tool(entry) for entry in visible_entries if entry.name in selected]
    available.sort(key=lambda tool: (tool.section_id, tool.name))

    available_names = {tool.name for tool in available}
    denied = [
        DeniedTool(entry.name, deny_reason(entry, request, selected))
        for entry in visible_entries
        if entry.name not in available_names
    ]
    denied.sort(key=lambda tool: tool.name)

    return ToolResolveResult(
        profile=policy.profile,
        tools=available,
        denied_tools=denied,
        prompt_fragments=build_prompt_fragments(available),
    )


def build_runtime_policy(request: ToolResolveInput) -> ToolPolicy:
    return ToolPolicy(
        profile=normalize_profile(request.profile),
        allow=request.allow,
        deny=set(request.deny),
        also_allow=set(request.also_allow),
        readonly=request.readonly or normalize_profile(request.profile) == "readonly",
    )


def to_resolved_tool(entry: ToolCatalogEntry) -> ResolvedTool:
    return ResolvedTool(
        name=entry.name,
        label=entry.label,
        description=entry.description,
        section_id=entry.section_id,
        execution_scope=entry.execution_scope,
        profiles=entry.profiles,
        tags=entry.tags,
        risk=entry.risk,
        readonly=entry.readonly,
        prompt_hint=entry.prompt_hint,
    )


def deny_reason(entry: ToolCatalogEntry, request: ToolResolveInput, selected: set[str]) -> str:
    if entry.name not in selected:
        expanded_allow = expand_tool_names(request.allow) if request.allow is not None else None
        if expanded_allow is not None and entry.name not in expanded_allow:
            return "not included in explicit allow list"
        expanded_deny = expand_tool_names(request.deny)
        if entry.name in expanded_deny:
            return "explicitly denied by Agent policy"
        if request.readonly and not ("readonly" in entry.tags):
            return "readonly policy only allows readonly tools"
        return f"not included by profile={normalize_profile(request.profile)}"
    return "not available in this runtime context"


def build_prompt_fragments(tools: list[ResolvedTool]) -> list[PromptFragment]:
    by_scope: dict[str, list[ResolvedTool]] = {}
    for tool in tools:
        by_scope.setdefault(tool.execution_scope, []).append(tool)

    fragments: list[PromptFragment] = []
    claw_workspace_tools = by_scope.get("claw_sandbox", [])
    if claw_workspace_tools:
        lines = [
            "当前工作区是当前 Claw 专属的沙箱工作区。",
            "你只能使用当前可用工具检查或修改该工作区内的资源。",
            "当前可用工具：",
        ]
        lines.extend(f"- {tool.name}: {tool.prompt_hint or tool.description}" for tool in claw_workspace_tools)
        fragments.append(PromptFragment(key="claw_workspace", content="\n".join(lines)))

    return fragments


def normalize_profile(value: str | None) -> str:
    normalized = (value or "coding").strip().lower().replace("-", "_")
    if normalized not in {"minimal", "readonly", "coding", "messaging", "full"}:
        return "coding"
    return normalized
