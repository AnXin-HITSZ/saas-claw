"""Tools for operating on the current Claw workspace through sandbox-runner APIs."""

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


def create_workspace_info_tool() -> ToolDefinition:
    """Return a tool that queries current Claw workspace metadata."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        result = _sandbox_get(base_url, "/v1/workspace")
        return {"result": result}

    return ToolDefinition(
        name="workspace_info",
        label="工作区信息",
        description="获取当前 Claw 工作区的信息。",
        input_schema={
            "type": "object",
            "properties": {},
            "required": [],
        },
        execute=execute,
    )


def create_list_files_tool() -> ToolDefinition:
    """Return a tool that lists files in the current Claw workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        path = str(arguments.get("path", "."))
        result = _sandbox_get(base_url, f"/v1/workspace/files?path={quote(path, safe='/._-')}")
        return {"result": result}

    return ToolDefinition(
        name="list_files",
        label="列出文件",
        description="列出当前 Claw 工作区中的文件和目录。使用 path='.' 表示根目录。",
        input_schema={
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "相对于工作区根目录的目录路径。"},
            },
            "required": [],
        },
        execute=execute,
    )


def create_read_file_tool() -> ToolDefinition:
    """Return a tool that reads a file from the current Claw workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        file_path = quote(str(arguments["file_path"]), safe="/._-")
        result = _sandbox_get(base_url, f"/v1/workspace/files/{file_path}")
        return {"result": result}

    return ToolDefinition(
        name="read_file",
        label="读取文件",
        description="读取当前 Claw 工作区中的 UTF-8 文本文件。",
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "相对于工作区根目录的文件路径。"},
            },
            "required": ["file_path"],
        },
        execute=execute,
    )


def create_write_file_tool() -> ToolDefinition:
    """Return a tool that writes a file to the current Claw workspace."""

    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        base_url = _require_sandbox_url(context.metadata)
        file_path = quote(str(arguments["file_path"]), safe="/._-")
        content = arguments["content"]
        if not isinstance(content, str):
            content = json.dumps(content)
        result = _sandbox_put_json(base_url, f"/v1/workspace/files/{file_path}", {"content": content})
        return {"result": result}

    return ToolDefinition(
        name="write_file",
        label="写入文件",
        description="向当前 Claw 工作区中的文件写入 UTF-8 文本内容。",
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "相对于工作区根目录的文件路径。"},
                "content": {"type": "string", "description": "要写入的 UTF-8 文本内容。"},
            },
            "required": ["file_path", "content"],
        },
        execute=execute,
    )


def create_apply_patch_tool() -> ToolDefinition:
    """Return a tool that applies an exact-text patch in the current Claw workspace."""

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
        name="apply_patch",
        label="应用补丁",
        description="对当前 Claw 工作区中的文件应用精确文本替换补丁。",
        input_schema={
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "相对于工作区根目录的文件路径。"},
                "old_text": {"type": "string", "description": "需要替换的精确原文。"},
                "new_text": {"type": "string", "description": "替换后的新文本。"},
                "replace_all": {"type": "boolean", "description": "是否替换全部匹配项，而不是只替换唯一一次出现。"},
            },
            "required": ["file_path", "old_text", "new_text"],
        },
        execute=execute,
    )


def _require_sandbox_url(ctx: dict[str, Any] | None) -> str:
    if not ctx:
        raise RuntimeError("workspace tool requires runtime context (sandbox_base_url)")
    base_url = ctx.get("sandbox_base_url")
    if not base_url:
        raise RuntimeError("sandbox_base_url is not configured for this Claw workspace")
    return str(base_url)