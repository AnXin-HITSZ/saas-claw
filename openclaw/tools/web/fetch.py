"""Web fetch tool."""

from __future__ import annotations

import asyncio
from typing import Any
from urllib.request import ProxyHandler, build_opener

from openclaw.tools.results import text_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult
from openclaw.tools.web.ssrf_guard import validate_public_http_url

WEB_FETCH_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["url"],
    "properties": {
        "url": {"type": "string"},
        "timeout_seconds": {"type": "integer"},
        "max_bytes": {"type": "integer"},
    },
    "additionalProperties": False,
}


async def execute_web_fetch(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    url = validate_public_http_url(str(arguments["url"]))
    timeout = max(1, min(int(arguments.get("timeout_seconds", 10) or 10), 60))
    max_bytes = max(1024, min(int(arguments.get("max_bytes", 200000) or 200000), 1000000))

    def fetch() -> tuple[str, str, int, str]:
        opener = build_opener(ProxyHandler({}))
        with opener.open(url, timeout=timeout) as response:
            final_url = response.geturl()
            validate_public_http_url(final_url)
            content_type = response.headers.get("content-type", "")
            status = getattr(response, "status", 200)
            data = response.read(max_bytes + 1)
        encoding = "utf-8"
        if "charset=" in content_type.lower():
            encoding = content_type.lower().split("charset=", 1)[1].split(";", 1)[0].strip() or "utf-8"
        text = data[:max_bytes].decode(encoding, errors="replace")
        return text, content_type, status, final_url

    text, content_type, status, final_url = await asyncio.to_thread(fetch)
    return text_result(
        text,
        details={
            "url": url,
            "finalUrl": final_url,
            "status": status,
            "contentType": content_type,
            "chars": len(text),
            "truncated": len(text.encode("utf-8")) >= max_bytes,
        },
    )


def create_web_fetch_tool() -> ToolDefinition:
    return ToolDefinition(
        name="web_fetch",
        label="Web Fetch",
        description="Fetch a public HTTP(S) URL with SSRF protection.",
        input_schema=WEB_FETCH_SCHEMA,
        execute=execute_web_fetch,
        execution_mode="parallel",
        metadata=ToolMetadata(
            section_id="web",
            profiles=("full",),
            tags=("web", "fetch"),
            risk="medium",
            workspace_only=False,
        ),
    )
