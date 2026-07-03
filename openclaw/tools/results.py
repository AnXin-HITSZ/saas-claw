"""Helpers for producing structured tool results."""

from __future__ import annotations

import json
from typing import Any

from openclaw.llm.types import ContentBlock
from openclaw.tools.types import ToolResult


def text_block(text: str) -> ContentBlock:
    return {"type": "text", "text": text}


def image_block(data: str, mime_type: str) -> ContentBlock:
    return {"type": "image", "data": data, "mimeType": mime_type}


def text_result(
    text: str,
    *,
    details: dict[str, Any] | None = None,
    is_error: bool = False,
    terminate: bool = False,
) -> ToolResult:
    return ToolResult(
        content=[text_block(text)],
        details=dict(details or {}),
        is_error=is_error,
        terminate=terminate,
    )


def json_result(
    payload: Any,
    *,
    details: dict[str, Any] | None = None,
    is_error: bool = False,
    terminate: bool = False,
) -> ToolResult:
    result_details = {"payload": payload}
    result_details.update(details or {})
    return ToolResult(
        content=[text_block(json.dumps(payload, ensure_ascii=False))],
        details=result_details,
        is_error=is_error,
        terminate=terminate,
    )


def blocked_result(
    reason: str,
    *,
    denied_reason: str | None = None,
    details: dict[str, Any] | None = None,
) -> ToolResult:
    result_details = {
        "status": "blocked",
        "deniedReason": denied_reason or reason,
    }
    result_details.update(details or {})
    return ToolResult(
        content=[text_block(reason)],
        details=result_details,
        is_error=True,
    )


def error_result(message: str, *, details: dict[str, Any] | None = None) -> ToolResult:
    result_details = {"status": "error"}
    result_details.update(details or {})
    return ToolResult(
        content=[text_block(message)],
        details=result_details,
        is_error=True,
    )


def ensure_tool_result(value: Any) -> ToolResult:
    if isinstance(value, ToolResult):
        return value
    if isinstance(value, list) and all(isinstance(item, dict) for item in value):
        return ToolResult(content=[dict(item) for item in value])
    if isinstance(value, dict):
        return json_result(value)
    return text_result(str(value))
