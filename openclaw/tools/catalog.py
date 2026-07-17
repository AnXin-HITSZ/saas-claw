"""Tool catalog metadata and factories."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from openclaw.tools.sandbox_workspace import (
    create_apply_patch_tool,
    create_list_files_tool,
    create_read_file_tool,
    create_workspace_info_tool,
    create_write_file_tool,
)
from openclaw.tools.types import ExecutionScope, ToolDefinition, ToolMetadata, ToolRisk, ToolSource

ToolFactory = Callable[[], ToolDefinition]


@dataclass(frozen=True)
class ToolCatalogEntry:
    id: str
    name: str
    label: str
    description: str
    section_id: str
    factory: ToolFactory
    execution_scope: ExecutionScope = "claw_sandbox"
    profiles: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    risk: ToolRisk = "low"
    source: ToolSource = "core"
    plugin_id: str | None = None
    expose_to_llm: bool = True
    user_visible: bool = True
    readonly: bool = False
    requires_approval: bool = False
    prompt_hint: str = ""
    include_in_openclaw_group: bool = False


CORE_TOOL_CATALOG: tuple[ToolCatalogEntry, ...] = (
    ToolCatalogEntry(
        id="workspace_info",
        name="workspace_info",
        label="工作区信息",
        description="获取当前 Claw 工作区的信息。",
        section_id="workspace",
        factory=create_workspace_info_tool,
        profiles=("minimal", "readonly", "coding", "messaging", "full"),
        tags=("workspace", "readonly"),
        risk="low",
        readonly=True,
        include_in_openclaw_group=True,
        prompt_hint="查看当前 Claw 工作区身份和根目录。",
    ),
    ToolCatalogEntry(
        id="list_files",
        name="list_files",
        label="列出文件",
        description="列出当前 Claw 工作区目录树中的文件和目录。",
        section_id="workspace",
        factory=create_list_files_tool,
        profiles=("minimal", "readonly", "coding", "messaging", "full"),
        tags=("workspace", "files", "readonly"),
        risk="low",
        readonly=True,
        include_in_openclaw_group=True,
        prompt_hint="列出当前 Claw 工作区中的文件和目录。",
    ),
    ToolCatalogEntry(
        id="read_file",
        name="read_file",
        label="读取文件",
        description="读取当前 Claw 工作区中的 UTF-8 文本文件。",
        section_id="workspace",
        factory=create_read_file_tool,
        profiles=("readonly", "coding", "full"),
        tags=("workspace", "files", "readonly"),
        risk="low",
        readonly=True,
        include_in_openclaw_group=True,
        prompt_hint="读取当前 Claw 工作区中的 UTF-8 文本文件。",
    ),
    ToolCatalogEntry(
        id="write_file",
        name="write_file",
        label="写入文件",
        description="向当前 Claw 工作区中的文件写入 UTF-8 文本内容。",
        section_id="workspace",
        factory=create_write_file_tool,
        profiles=("coding", "full"),
        tags=("workspace", "files", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
        prompt_hint="在当前 Claw 工作区中写入 UTF-8 文本文件。",
    ),
    ToolCatalogEntry(
        id="apply_patch",
        name="apply_patch",
        label="应用补丁",
        description="对当前 Claw 工作区中的文件应用精确文本替换补丁。",
        section_id="workspace",
        factory=create_apply_patch_tool,
        profiles=("coding", "full"),
        tags=("workspace", "files", "patch", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
        prompt_hint="在当前 Claw 工作区中应用精确文本替换补丁。",
    ),
)


def list_catalog_entries() -> list[ToolCatalogEntry]:
    return list(CORE_TOOL_CATALOG)


def user_visible_catalog() -> list[ToolCatalogEntry]:
    return [entry for entry in list_catalog_entries() if entry.user_visible]


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
            execution_scope=entry.execution_scope,
        ),
    )


def materialize_core_tools() -> list[ToolDefinition]:
    return [materialize_catalog_entry(entry) for entry in CORE_TOOL_CATALOG]


def build_tool_groups(entries: list[ToolCatalogEntry] | None = None) -> dict[str, set[str]]:
    source_entries = entries if entries is not None else list_catalog_entries()
    groups: dict[str, set[str]] = {}

    for entry in source_entries:
        section_key = f"group:{entry.section_id.lower()}"
        scope_key = f"group:{entry.execution_scope.lower()}"
        groups.setdefault(section_key, set()).add(entry.name)
        groups.setdefault(scope_key, set()).add(entry.name)
        for tag in entry.tags:
            groups.setdefault(f"group:{tag.lower()}", set()).add(entry.name)
        if entry.include_in_openclaw_group:
            groups.setdefault("group:openclaw", set()).add(entry.name)

    return groups
