"""Tools for operating on a remote sandbox-runner workspace via HTTP API."""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import quote
import urllib.error
import urllib.request

from openclaw.tools.types import ToolDefinition, ToolExecutionContext

SANDBOX_TIMEOUT_SECONDS = 15


def _sandbox_get(base_url: str, path: str) -> dict[str, Any] | str:
    """Perform a GET request to the sandbox-runner API."""
    url = f"{base_url.rstrip('/')}{path}"
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=SANDBOX_TIMEOUT_SECONDS) as resp:
            body = resp.read().decode("utf-8")
            try:
                return json.loads(body)
            except json.JSONDecodeError:
                return body
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"sandbox runner returned {exc.code}: {exc.reason}")
    except urllib.error.URLError as exc:
        raise RuntimeError(f"sandbox runner unreachable: {exc.reason}")


def _sandbox_post_json(base_url: str, path: str, payload: dict[str, Any]) -> dict[str, Any] | str:
    """Perform a POST request to the sandbox-runner API."""
    url = f"{base_url.rstrip('/')}{path}"
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, method="POST", data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=SANDBOX_TIMEOUT_SECONDS) as resp:
            body = resp.read().decode("utf-8")
            try:
                return json.loads(body)
            except json.JSONDecodeError:
                return body
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"sandbox runner returned {exc.code}: {exc.reason}")
    except urllib.error.URLError as exc:
        raise RuntimeError(f"sandbox runner unreachable: {exc.reason}")


def _sandbox_put_json(base_url: str, path: str, payload: dict[str, Any]) -> dict[str, Any] | str:
    """Perform a PUT request to the sandbox-runner API."""
    url = f"{base_url.rstrip('/')}{path}"
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, method="PUT", data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=SANDBOX_TIMEOUT_SECONDS) as resp:
            body = resp.read().decode("utf-8")
            try:
                return json.loads(body)
            except json.JSONDecodeError:
                return body
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"sandbox runner returned {exc.code}: {exc.reason}")
    except urllib.error.URLError as exc:
        raise RuntimeError(f"sandbox runner unreachable: {exc.reason}")


def create_sandbox_workspace_info_tool() -> ToolDefinition:
    """Return a tool that queries sandbox workspace metadata."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        result = _sandbox_get(base_url, "/v1/workspace")
        return {"result": result}

    return ToolDefinition(
        name="sandbox_workspace_info",
        label="Sandbox Workspace Info",
        description="Get information about the Claw sandbox workspace.",
        input_schema={
            "type": "object",
            "properties": {},
            "required": [],
        },
        execute=execute,
    )


def create_sandbox_list_files_tool() -> ToolDefinition:
    """Return a tool that lists files in the sandbox workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        path = str(arguments.get("path", "."))
        result = _sandbox_get(base_url, f"/v1/workspace/files?path={quote(path, safe='/._-')}")
        return {"result": result}

    return ToolDefinition(
        name="sandbox_list_files",
        label="Sandbox List Files",
        description="List files and directories in the Claw sandbox workspace. Use path='.' for root.",
        input_schema={
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Directory path relative to workspace root."},
            },
            "required": [],
        },
        execute=execute,
    )


def create_sandbox_read_file_tool() -> ToolDefinition:
    """Return a tool that reads a file from the sandbox workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        file_path = quote(str(arguments["file_path"]), safe="/._-")
        result = _sandbox_get(base_url, f"/v1/workspace/files/{file_path}")
        return {"result": result}

    return ToolDefinition(
        name="sandbox_read_file",
        label="Sandbox Read File",
        description="Read a UTF-8 text file from the Claw sandbox workspace.",
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "File path relative to workspace root."},
            },
            "required": ["file_path"],
        },
        execute=execute,
    )


def create_sandbox_write_file_tool() -> ToolDefinition:
    """Return a tool that writes a file to the sandbox workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        file_path = quote(str(arguments["file_path"]), safe="/._-")
        content = arguments["content"]
        if not isinstance(content, str):
            content = json.dumps(content)
        result = _sandbox_put_json(base_url, f"/v1/workspace/files/{file_path}", {"content": content})
        return {"result": result}

    return ToolDefinition(
        name="sandbox_write_file",
        label="Sandbox Write File",
        description="Write UTF-8 text content to a file in the Claw sandbox workspace.",
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "File path relative to workspace root."},
                "content": {"type": "string", "description": "UTF-8 text content to write."},
            },
            "required": ["file_path", "content"],
        },
        execute=execute,
    )


def create_sandbox_apply_patch_tool() -> ToolDefinition:
    """Return a tool that applies an exact-text patch in the sandbox workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        payload = {
            "file_path": arguments["file_path"],
            "old_text": arguments["old_text"],
            "new_text": arguments["new_text"],
            "replace_all": bool(arguments.get("replace_all", False)),
        }
        result = _sandbox_post_json(base_url, "/v1/workspace/patches", payload)
        return {"result": result}

    return ToolDefinition(
        name="sandbox_apply_patch",
        label="Sandbox Apply Patch",
        description="Apply an exact-text replacement patch to a file in the Claw sandbox workspace.",
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "File path relative to workspace root."},
                "old_text": {"type": "string", "description": "Exact text to replace."},
                "new_text": {"type": "string", "description": "Replacement text."},
                "replace_all": {"type": "boolean", "description": "Replace all occurrences instead of exactly one."},
            },
            "required": ["file_path", "old_text", "new_text"],
        },
        execute=execute,
    )


def _require_sandbox_url(ctx: dict[str, Any] | None) -> str:
    if not ctx:
        raise RuntimeError("sandbox tool requires runtime context (sandbox_base_url)")
    base_url = ctx.get("sandbox_base_url")
    if not base_url:
        raise RuntimeError("sandbox_base_url is not configured for this Claw sandbox")
    return str(base_url)
