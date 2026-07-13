"""Tool catalog metadata and factories."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from openclaw.tools.fs.apply_patch import create_apply_patch_tool
from openclaw.tools.fs.edit import create_edit_tool
from openclaw.tools.fs.find import create_find_tool
from openclaw.tools.fs.grep import create_grep_tool
from openclaw.tools.fs.list_dir import create_list_dir_tool, create_ls_tool
from openclaw.tools.fs.read import create_read_tool
from openclaw.tools.fs.write import create_write_tool
from openclaw.tools.host_ssh import create_host_df_tool, create_host_free_tool, create_host_uname_tool
from openclaw.tools.shell.exec import create_exec_tool, create_shell_tool
from openclaw.tools.types import ToolDefinition, ToolMetadata, ToolRisk, ToolSource
from openclaw.tools.web.fetch import create_web_fetch_tool
from openclaw.tools.web.search import create_web_search_tool

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
    workspace_only: bool = True
    include_in_openclaw_group: bool = False


CORE_TOOL_CATALOG: tuple[ToolCatalogEntry, ...] = (
    ToolCatalogEntry(
        id="read",
        name="read",
        label="Read",
        description="Read a UTF-8 text file from the workspace.",
        section_id="filesystem",
        factory=create_read_tool,
        profiles=("readonly", "coding", "full"),
        tags=("fs", "read", "readonly"),
        risk="low",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="list_dir",
        name="list_dir",
        label="List Directory",
        description="List files and directories inside the workspace.",
        section_id="filesystem",
        factory=create_list_dir_tool,
        profiles=("readonly", "coding", "full"),
        tags=("fs", "list", "readonly"),
        risk="low",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="ls",
        name="ls",
        label="Ls",
        description="Alias for list_dir, compatible with OpenClaw session tools.",
        section_id="filesystem",
        factory=create_ls_tool,
        profiles=("readonly", "coding", "full"),
        tags=("fs", "list", "readonly", "alias"),
        risk="low",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="grep",
        name="grep",
        label="Grep",
        description="Search UTF-8 workspace files for text or regular-expression matches.",
        section_id="filesystem",
        factory=create_grep_tool,
        profiles=("readonly", "coding", "full"),
        tags=("fs", "search", "readonly"),
        risk="low",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="find",
        name="find",
        label="Find",
        description="Find files or directories in the workspace by name glob.",
        section_id="filesystem",
        factory=create_find_tool,
        profiles=("readonly", "coding", "full"),
        tags=("fs", "find", "readonly"),
        risk="low",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="write",
        name="write",
        label="Write",
        description="Write UTF-8 text to a workspace file.",
        section_id="filesystem",
        factory=create_write_tool,
        profiles=("coding", "full"),
        tags=("fs", "write", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="edit",
        name="edit",
        label="Edit",
        description="Replace exact UTF-8 text in a workspace file.",
        section_id="filesystem",
        factory=create_edit_tool,
        profiles=("coding", "full"),
        tags=("fs", "edit", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="apply_patch",
        name="apply_patch",
        label="Apply Patch",
        description="Apply a conservative exact-text patch to a workspace file.",
        section_id="filesystem",
        factory=create_apply_patch_tool,
        profiles=("coding", "full"),
        tags=("fs", "edit", "patch", "mutation"),
        risk="medium",
        include_in_openclaw_group=True,
    ),
    ToolCatalogEntry(
        id="shell",
        name="shell",
        label="Shell",
        description="Execute a shell command inside the workspace.",
        section_id="runtime",
        factory=create_shell_tool,
        profiles=("full",),
        tags=("runtime", "shell", "exec", "legacy", "mutation", "high-risk"),
        risk="high",
    ),
    ToolCatalogEntry(
        id="exec",
        name="exec",
        label="Exec",
        description="OpenClaw-compatible shell command execution entry point.",
        section_id="runtime",
        factory=create_exec_tool,
        profiles=("full",),
        tags=("runtime", "shell", "exec", "mutation", "high-risk"),
        risk="high",
    ),
    ToolCatalogEntry(
        id="web_fetch",
        name="web_fetch",
        label="Web Fetch",
        description="Fetch a public HTTP(S) URL with SSRF protection.",
        section_id="web",
        factory=create_web_fetch_tool,
        profiles=("full",),
        tags=("web", "fetch", "network"),
        risk="medium",
        workspace_only=False,
    ),
    ToolCatalogEntry(
        id="host_uname",
        name="host_uname",
        label="Host Uname",
        description="Read the ECS host kernel and system identity with uname -a over SSH.",
        section_id="host",
        factory=create_host_uname_tool,
        profiles=("full",),
        tags=("host", "ssh", "runtime", "readonly"),
        risk="low",
        workspace_only=False,
    ),
    ToolCatalogEntry(
        id="host_df",
        name="host_df",
        label="Host Disk Usage",
        description="Read ECS host filesystem disk usage with df -h over SSH.",
        section_id="host",
        factory=create_host_df_tool,
        profiles=("full",),
        tags=("host", "ssh", "runtime", "readonly"),
        risk="low",
        workspace_only=False,
    ),
    ToolCatalogEntry(
        id="host_free",
        name="host_free",
        label="Host Memory Usage",
        description="Read ECS host memory usage with free -h over SSH.",
        section_id="host",
        factory=create_host_free_tool,
        profiles=("full",),
        tags=("host", "ssh", "runtime", "readonly"),
        risk="low",
        workspace_only=False,
    ),
    ToolCatalogEntry(
        id="web_search",
        name="web_search",
        label="Web Search",
        description="Search the public web and return result titles and URLs.",
        section_id="web",
        factory=create_web_search_tool,
        profiles=("full",),
        tags=("web", "search", "network"),
        risk="medium",
        workspace_only=False,
    ),
)


def list_catalog_entries() -> list[ToolCatalogEntry]:
    return list(CORE_TOOL_CATALOG)


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
            workspace_only=entry.workspace_only,
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