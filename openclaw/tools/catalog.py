"""Tool catalog metadata and factories."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from openclaw.tools.sandbox_workspace import (
    create_sandbox_apply_patch_tool,
    create_sandbox_list_files_tool,
    create_sandbox_read_file_tool,
    create_sandbox_workspace_info_tool,
    create_sandbox_write_file_tool,
)
from openclaw.tools.types import ToolDefinition, ToolMetadata, ToolRisk, ToolSource

ToolFactory = Callable[[], ToolDefinition]


@dataclass(frozen=True)
class ToolCatalogEntry:
    id: str
    name: str
    label: str
    description: str
    section_id: str
    factory: ToolFactory
    profiles: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    risk: ToolRisk = "low"
    source: ToolSource = "core"
    plugin_id: str | None = None
    expose_to_llm: bool = True
    readonly: bool = False
    requires_approval: bool = False
    prompt_hint: str = ""
    include_in_openclaw_group: bool = False


CORE_TOOL_CATALOG: tuple[ToolCatalogEntry, ...] = (
    ToolCatalogEntry(
        id="sandbox_workspace_info",
        name="sandbox_workspace_info",
        label="Sandbox Workspace Info",
        description="Get information about the Claw sandbox workspace.",
        section_id="sandbox",
        factory=create_sandbox_workspace_info_tool,
        profiles=("minimal", "readonly", "coding", "messaging", "full"),
        tags=("sandbox", "workspace", "readonly"),
        risk="low",
        readonly=True,
        include_in_openclaw_group=True,
        prompt_hint="Inspect the current Claw sandbox workspace identity and root directory.",
    ),
    ToolCatalogEntry(
        id="sandbox_list_files",
        name="sandbox_list_files",
        label="Sandbox List Files",
        description="List files and directories in the Claw sandbox workspace directory tree.",
        section_id="sandbox",
        factory=create_sandbox_list_files_tool,
        profiles=("minimal", "readonly", "coding", "messaging", "full"),
        tags=("sandbox", "files", "readonly"),
        risk="low",
        readonly=True,
        include_in_openclaw_group=True,
        prompt_hint="List files and directories in the current Claw sandbox workspace.",
    ),
    ToolCatalogEntry(
        id="sandbox_read_file",
        name="sandbox_read_file",
        label="Sandbox Read File",
        description="Read a UTF-8 text file from the Claw sandbox workspace.",
        section_id="sandbox",
        factory=create_sandbox_read_file_tool,
        profiles=("minimal", "readonly", "coding", "messaging", "full"),
        tags=("sandbox", "files", "readonly"),
        risk="low",
        readonly=True,
        include_in_openclaw_group=True,
        prompt_hint="Read a UTF-8 text file from the current Claw sandbox workspace.",
    ),
    ToolCatalogEntry(
        id="sandbox_write_file",
        name="sandbox_write_file",
        label="Sandbox Write File",
        description="Write UTF-8 text content to a file in the Claw sandbox workspace.",
        section_id="sandbox",
        factory=create_sandbox_write_file_tool,
        profiles=("coding", "full"),
        tags=("sandbox", "files", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
        prompt_hint="Write a UTF-8 text file in the current Claw sandbox workspace.",
    ),
    ToolCatalogEntry(
        id="sandbox_apply_patch",
        name="sandbox_apply_patch",
        label="Sandbox Apply Patch",
        description="Apply an exact-text patch to a file in the Claw sandbox workspace.",
        section_id="sandbox",
        factory=create_sandbox_apply_patch_tool,
        profiles=("coding", "full"),
        tags=("sandbox", "files", "patch", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
        prompt_hint="Apply an exact-text replacement patch inside the current Claw sandbox workspace.",
    ),
)


def list_catalog_entries() -> list[ToolCatalogEntry]:
    return list(CORE_TOOL_CATALOG)


def user_visible_catalog() -> list[ToolCatalogEntry]:
    return list_catalog_entries()


def materialize_catalog_entry(entry: ToolCatalogEntry) -> ToolDefinition:
    tool = entry.factory()
    if tool.name != entry.name:
        raise ValueError(f"catalog entry {entry.id!r} factory returned tool {tool.name!r}")
    return ToolDefinition(
        name=entry.name,
        label=entry.label,
        description=entry.description,
        input_schema=tool.input_schema,
        execute=tool.execute,
        prepare_arguments=tool.prepare_arguments,
        execution_mode=tool.execution_mode,
        metadata=ToolMetadata(
            section_id=entry.section_id,
            profiles=entry.profiles,
            tags=entry.tags,
            risk=entry.risk,
            source=entry.source,
            plugin_id=entry.plugin_id,
            expose_to_llm=entry.expose_to_llm,
            readonly=entry.readonly,
            requires_approval=entry.requires_approval,
        ),
    )


def materialize_core_tools() -> list[ToolDefinition]:
    return [materialize_catalog_entry(entry) for entry in CORE_TOOL_CATALOG]


def build_tool_groups(entries: list[ToolCatalogEntry] | None = None) -> dict[str, set[str]]:
    source_entries = entries if entries is not None else list_catalog_entries()
    groups: dict[str, set[str]] = {}

    for entry in source_entries:
        section_key = f"group:{entry.section_id.lower()}"
        groups.setdefault(section_key, set()).add(entry.name)
        for tag in entry.tags:
            groups.setdefault(f"group:{tag.lower()}", set()).add(entry.name)
        if entry.include_in_openclaw_group:
            groups.setdefault("group:openclaw", set()).add(entry.name)

    if "group:fs" in groups:
        groups.setdefault("group:filesystem", set()).update(groups["group:fs"])
    if "group:runtime" in groups:
        groups.setdefault("group:shell", set()).update(groups["group:runtime"])

    return groups
