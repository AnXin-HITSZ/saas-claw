"""Simple web search tool."""

from __future__ import annotations

import asyncio
import html
import re
from typing import Any
from urllib.parse import quote_plus, urlparse, parse_qs, unquote
from urllib.request import ProxyHandler, build_opener

from openclaw.tools.results import json_result
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolMetadata, ToolResult
from openclaw.tools.web.ssrf_guard import validate_public_http_url

WEB_SEARCH_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["query"],
    "properties": {
        "query": {"type": "string"},
        "limit": {"type": "integer"},
        "timeout_seconds": {"type": "integer"},
    },
    "additionalProperties": False,
}

RESULT_RE = re.compile(r'<a[^>]+class="result-link"[^>]+href="([^"]+)"[^>]*>(.*?)</a>', re.IGNORECASE | re.DOTALL)
TAG_RE = re.compile(r"<[^>]+>")


async def execute_web_search(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
    query = str(arguments["query"])
    limit = max(1, min(int(arguments.get("limit", 5) or 5), 20))
    timeout = max(1, min(int(arguments.get("timeout_seconds", 10) or 10), 60))
    url = f"https://duckduckgo.com/html/?q={quote_plus(query)}"
    validate_public_http_url(url)

    def search() -> str:
        opener = build_opener(ProxyHandler({}))
        with opener.open(url, timeout=timeout) as response:
            validate_public_http_url(response.geturl())
            data = response.read(300000)
        return data.decode("utf-8", errors="replace")

    body = await asyncio.to_thread(search)
    results: list[dict[str, str]] = []
    for href, title_html in RESULT_RE.findall(body):
        title = html.unescape(TAG_RE.sub("", title_html)).strip()
        link = html.unescape(href)
        parsed = urlparse(link)
        if parsed.path == "/l/":
            link = unquote(parse_qs(parsed.query).get("uddg", [link])[0])
        if title and link:
            results.append({"title": title, "url": link})
        if len(results) >= limit:
            break
    return json_result({"query": query, "results": results}, details={"resultCount": len(results)})


def create_web_search_tool() -> ToolDefinition:
    return ToolDefinition(
        name="web_search",
        label="Web Search",
        description="Search the public web and return result titles and URLs.",
        input_schema=WEB_SEARCH_SCHEMA,
        execute=execute_web_search,
        execution_mode="parallel",
        metadata=ToolMetadata(
            section_id="web",
            profiles=("full",),
            tags=("web", "search"),
            risk="medium",
            workspace_only=False,
        ),
    )
