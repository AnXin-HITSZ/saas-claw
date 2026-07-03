"""Small JSON-schema subset for tool argument validation."""

from __future__ import annotations

from copy import deepcopy
from typing import Any


class ToolArgumentError(ValueError):
    """Raised when tool arguments do not match the declared schema."""


def normalize_tool_schema(schema: dict[str, Any] | None) -> dict[str, Any]:
    normalized = deepcopy(schema or {})
    normalized.setdefault("type", "object")
    normalized.setdefault("properties", {})
    normalized.setdefault("required", [])
    return normalized


def validate_tool_arguments(schema: dict[str, Any], value: dict[str, Any]) -> dict[str, Any]:
    normalized_schema = normalize_tool_schema(schema)
    if not isinstance(value, dict):
        raise ToolArgumentError("tool arguments must be an object")
    _validate_value(normalized_schema, value, path="")
    return dict(value)


def _validate_value(schema: dict[str, Any], value: Any, *, path: str) -> None:
    expected_type = schema.get("type")
    if expected_type is not None and not _matches_type(value, expected_type):
        raise ToolArgumentError(f"argument {path or 'value'} must be {_type_label(expected_type)}")

    if "enum" in schema and value not in schema["enum"]:
        allowed = ", ".join(str(item) for item in schema["enum"])
        raise ToolArgumentError(f"argument {path or 'value'} must be one of: {allowed}")

    if _schema_allows_object(schema) and isinstance(value, dict):
        _validate_object(schema, value, path=path)

    if _schema_allows_array(schema) and isinstance(value, list):
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                _validate_value(item_schema, item, path=f"{path}[{index}]" if path else f"[{index}]")


def _validate_object(schema: dict[str, Any], value: dict[str, Any], *, path: str) -> None:
    properties = dict(schema.get("properties") or {})
    for key in schema.get("required", []) or []:
        if key not in value:
            dotted = f"{path}.{key}" if path else str(key)
            raise ToolArgumentError(f"missing required argument: {dotted}")

    additional = schema.get("additionalProperties", True)
    for key, item in value.items():
        dotted = f"{path}.{key}" if path else str(key)
        child_schema = properties.get(key)
        if child_schema is None:
            if additional is False:
                raise ToolArgumentError(f"unknown argument: {dotted}")
            if isinstance(additional, dict):
                _validate_value(additional, item, path=dotted)
            continue
        if isinstance(child_schema, dict):
            _validate_value(child_schema, item, path=dotted)


def _matches_type(value: Any, expected_type: Any) -> bool:
    types = expected_type if isinstance(expected_type, list) else [expected_type]
    return any(_matches_single_type(value, item) for item in types)


def _matches_single_type(value: Any, expected_type: str) -> bool:
    if expected_type == "null":
        return value is None
    if expected_type == "boolean":
        return isinstance(value, bool)
    if expected_type == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected_type == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected_type == "string":
        return isinstance(value, str)
    if expected_type == "array":
        return isinstance(value, list)
    if expected_type == "object":
        return isinstance(value, dict)
    return True


def _schema_allows_object(schema: dict[str, Any]) -> bool:
    expected_type = schema.get("type")
    if expected_type is None:
        return "properties" in schema or "required" in schema
    types = expected_type if isinstance(expected_type, list) else [expected_type]
    return "object" in types


def _schema_allows_array(schema: dict[str, Any]) -> bool:
    expected_type = schema.get("type")
    if expected_type is None:
        return "items" in schema
    types = expected_type if isinstance(expected_type, list) else [expected_type]
    return "array" in types


def _type_label(expected_type: Any) -> str:
    if isinstance(expected_type, list):
        return " or ".join(str(item) for item in expected_type)
    return str(expected_type)


def normalize_tool_schema_for_provider(
    schema: dict[str, Any] | None,
    *,
    provider: str | None = None,
    model: str | None = None,
    api_mode: str | None = None,
) -> dict[str, Any]:
    """Return a provider-safe JSON schema for function/tool parameters."""

    normalized = normalize_tool_schema(schema)
    return _strip_unsupported_schema_fields(normalized)


def tool_to_responses_schema(
    tool: dict[str, Any],
    *,
    provider: str | None = None,
    model: str | None = None,
    api_mode: str | None = "responses",
) -> dict[str, Any]:
    return {
        "type": "function",
        "name": tool["name"],
        "description": tool.get("description", ""),
        "parameters": normalize_tool_schema_for_provider(
            tool.get("input_schema") or tool.get("parameters"),
            provider=provider,
            model=model,
            api_mode=api_mode,
        ),
        "strict": bool(tool.get("strict", False)),
    }


def tool_to_chat_completions_schema(
    tool: dict[str, Any],
    *,
    provider: str | None = None,
    model: str | None = None,
    api_mode: str | None = "chat_completions",
) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": tool["name"],
            "description": tool.get("description", ""),
            "parameters": normalize_tool_schema_for_provider(
                tool.get("input_schema") or tool.get("parameters"),
                provider=provider,
                model=model,
                api_mode=api_mode,
            ),
        },
    }


def _strip_unsupported_schema_fields(value: Any) -> Any:
    if isinstance(value, list):
        return [_strip_unsupported_schema_fields(item) for item in value]
    if not isinstance(value, dict):
        return value

    allowed = {
        "type",
        "description",
        "properties",
        "required",
        "additionalProperties",
        "items",
        "enum",
        "default",
        "minimum",
        "maximum",
        "minLength",
        "maxLength",
    }
    stripped: dict[str, Any] = {}
    for key, item in value.items():
        if key not in allowed:
            continue
        stripped[key] = _strip_unsupported_schema_fields(item)
    if stripped.get("type") == "object":
        stripped.setdefault("properties", {})
        stripped.setdefault("required", [])
    return stripped