"""OpenAI-compatible provider adapter.

The OpenAI SDK is an optional dependency. Install with:

    py -m pip install "pyclaw[openai]"

Two API modes are supported:

- responses: OpenAI Responses API.
- chat_completions: OpenAI-compatible Chat Completions API.
"""

from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator
from typing import Any

from openclaw.llm.provider import ProviderEvent
from openclaw.llm.types import AssistantMessage, tool_call_content
from openclaw.tools.schema import tool_to_chat_completions_schema, tool_to_responses_schema


class OpenAIProvider:
    """Provider adapter backed by the OpenAI SDK."""

    provider_name = "openai"

    def __init__(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        organization: str | None = None,
        project: str | None = None,
        timeout: float | None = None,
        api_mode: str | None = None,
        client: Any | None = None,
    ) -> None:
        api_key = api_key if api_key is not None else os.environ.get("OPENAI_API_KEY")
        base_url = base_url if base_url is not None else os.environ.get("OPENAI_BASE_URL")
        organization = organization if organization is not None else os.environ.get("OPENAI_ORGANIZATION")
        project = project if project is not None else os.environ.get("OPENAI_PROJECT")
        self.api_mode = resolve_api_mode(api_mode or os.environ.get("OPENAI_API_MODE"), base_url)

        if client is not None:
            self.client = client
            return

        try:
            from openai import AsyncOpenAI
        except ImportError as exc:
            raise RuntimeError(
                "OpenAIProvider requires the optional 'openai' package. "
                'Install it with: py -m pip install "pyclaw[openai]"'
            ) from exc

        kwargs: dict[str, Any] = {}
        if api_key is not None:
            kwargs["api_key"] = api_key
        if base_url is not None:
            kwargs["base_url"] = base_url
        if organization is not None:
            kwargs["organization"] = organization
        if project is not None:
            kwargs["project"] = project
        if timeout is not None:
            kwargs["timeout"] = timeout
        self.client = AsyncOpenAI(**kwargs)

    async def stream(
        self,
        *,
        model: str,
        system_prompt: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        options: dict[str, Any] | None = None,
    ) -> AsyncIterator[ProviderEvent]:
        if self.api_mode == "chat_completions":
            async for event in self._stream_chat_completions(
                model=model,
                system_prompt=system_prompt,
                messages=messages,
                tools=tools,
                options=options,
            ):
                yield event
            return

        async for event in self._stream_responses(
            model=model,
            system_prompt=system_prompt,
            messages=messages,
            tools=tools,
            options=options,
        ):
            yield event

    async def _stream_responses(
        self,
        *,
        model: str,
        system_prompt: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        options: dict[str, Any] | None = None,
    ) -> AsyncIterator[ProviderEvent]:
        options = dict(options or {})
        request = {
            "model": model,
            "instructions": system_prompt,
            "input": convert_messages_to_openai_input(messages),
            "tools": convert_tools_to_openai_responses(tools),
            "stream": True,
        }
        request.update(options)

        yield ProviderEvent("start", {"provider": self.provider_name, "model": model})

        stream = await self.client.responses.create(**request)
        text_parts: list[str] = []
        tool_calls: list[dict[str, Any]] = []
        completed_response: Any | None = None

        async for event in stream:
            event_type = _get(event, "type")
            event_data = dump_openai_obj(event)

            if event_type in {"response.output_text.delta", "response.refusal.delta"}:
                delta = str(_get(event, "delta", "") or "")
                if delta:
                    text_parts.append(delta)
                    yield ProviderEvent("delta", {"text": delta, "raw": event_data})
                continue

            if event_type == "response.output_item.done":
                item = _get(event, "item")
                call = function_call_from_openai_item(item)
                if call is not None:
                    tool_calls.append(call)
                continue

            if event_type == "response.completed":
                completed_response = _get(event, "response")
                continue

            if event_type in {"response.failed", "response.incomplete", "error"}:
                yield ProviderEvent(
                    "error",
                    {
                        "message": extract_openai_error_message(event),
                        "raw": event_data,
                    },
                )
                return

        message = assistant_from_openai_response(
            completed_response,
            model=model,
            provider=self.provider_name,
            fallback_text="".join(text_parts),
            fallback_tool_calls=tool_calls,
        )
        yield ProviderEvent("done", {"message": message})

    async def _stream_chat_completions(
        self,
        *,
        model: str,
        system_prompt: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        options: dict[str, Any] | None = None,
    ) -> AsyncIterator[ProviderEvent]:
        options = normalize_chat_completion_options(dict(options or {}))
        request = {
            "model": model,
            "messages": convert_messages_to_chat_completions(system_prompt, messages),
            "stream": True,
        }
        converted_tools = convert_tools_to_chat_completions(tools)
        if converted_tools:
            request["tools"] = converted_tools
        request.update(options)

        yield ProviderEvent("start", {"provider": self.provider_name, "model": model})

        stream = await self.client.chat.completions.create(**request)
        text_parts: list[str] = []
        tool_call_parts: dict[int, dict[str, Any]] = {}
        finish_reason = "stop"
        usage: dict[str, Any] = {}

        async for chunk in stream:
            usage = dump_openai_obj(_get(chunk, "usage")) or usage
            choices = _get(chunk, "choices", []) or []
            if not choices:
                continue
            choice = choices[0]
            finish_reason = str(_get(choice, "finish_reason", finish_reason) or finish_reason)
            delta = _get(choice, "delta")
            content_delta = str(_get(delta, "content", "") or "")
            if content_delta:
                text_parts.append(content_delta)
                yield ProviderEvent("delta", {"text": content_delta, "raw": dump_openai_obj(chunk)})

            for tool_delta in _get(delta, "tool_calls", []) or []:
                merge_chat_tool_call_delta(tool_call_parts, tool_delta)

        content: list[dict[str, Any]] = []
        text = "".join(text_parts)
        if text:
            content.append({"type": "text", "text": text})
        for call in chat_tool_calls_to_content(tool_call_parts):
            content.append(call)

        stop_reason = "toolUse" if any(block.get("type") == "toolCall" for block in content) else "stop"
        if finish_reason == "length":
            stop_reason = "length"

        yield ProviderEvent(
            "done",
            {
                "message": AssistantMessage(
                    content=content,
                    provider=self.provider_name,
                    model=model,
                    usage=usage,
                    stop_reason=stop_reason,
                )
            },
        )


def resolve_api_mode(raw_mode: str | None, base_url: str | None) -> str:
    mode = (raw_mode or "auto").strip().lower().replace("-", "_")
    if mode in {"responses", "chat_completions"}:
        return mode
    if mode != "auto":
        raise ValueError("OPENAI_API_MODE must be one of: auto, responses, chat_completions")
    normalized_base = (base_url or "").lower()
    if normalized_base and "api.openai.com" not in normalized_base:
        return "chat_completions"
    return "responses"


def normalize_chat_completion_options(options: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(options)
    # Reasoning options are Responses-API-specific in this project. Most
    # OpenAI-compatible chat-completions endpoints reject the nested object.
    normalized.pop("reasoning", None)
    if "max_output_tokens" in normalized and "max_tokens" not in normalized:
        normalized["max_tokens"] = normalized.pop("max_output_tokens")
    return normalized


def convert_tools_to_openai_responses(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [tool_to_responses_schema(tool, provider="openai", api_mode="responses") for tool in tools]


def convert_tools_to_openai(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Backward-compatible alias for Responses API tool conversion."""

    return convert_tools_to_openai_responses(tools)


def convert_tools_to_chat_completions(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [tool_to_chat_completions_schema(tool, provider="openai", api_mode="chat_completions") for tool in tools]


def convert_messages_to_chat_completions(
    system_prompt: str,
    messages: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    converted: list[dict[str, Any]] = []
    if system_prompt:
        converted.append({"role": "system", "content": system_prompt})

    for message in messages:
        role = message.get("role")
        content = list(message.get("content") or [])
        if role == "tool":
            for block in content:
                if block.get("type") != "toolResult":
                    continue
                converted.append(
                    {
                        "role": "tool",
                        "tool_call_id": str(block.get("tool_call_id", "")),
                        "content": _stringify_tool_output(block.get("output")),
                    }
                )
            continue

        text_parts: list[str] = []
        tool_calls: list[dict[str, Any]] = []
        for block in content:
            block_type = block.get("type")
            if block_type == "text":
                text_parts.append(str(block.get("text", "")))
            elif block_type == "toolCall":
                tool_calls.append(
                    {
                        "id": str(block.get("id", "")),
                        "type": "function",
                        "function": {
                            "name": str(block.get("name", "")),
                            "arguments": json.dumps(block.get("input") or {}, ensure_ascii=False),
                        },
                    }
                )

        if role == "assistant":
            if not text_parts and not tool_calls:
                continue
            item: dict[str, Any] = {"role": "assistant", "content": "\n".join(text_parts) or None}
            if tool_calls:
                item["tool_calls"] = tool_calls
            converted.append(item)
        elif role in {"user", "system"}:
            converted.append({"role": role, "content": "\n".join(text_parts)})
    return converted


def convert_messages_to_openai_input(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for message in messages:
        role = message.get("role")
        content = list(message.get("content") or [])

        if role == "tool":
            for block in content:
                if block.get("type") != "toolResult":
                    continue
                items.append(
                    {
                        "type": "function_call_output",
                        "call_id": str(block.get("tool_call_id", "")),
                        "output": _stringify_tool_output(block.get("output")),
                    }
                )
            continue

        text_parts: list[str] = []
        for block in content:
            block_type = block.get("type")
            if block_type == "text":
                text_parts.append(str(block.get("text", "")))
            elif block_type == "toolCall":
                items.append(
                    {
                        "type": "function_call",
                        "call_id": str(block.get("id", "")),
                        "name": str(block.get("name", "")),
                        "arguments": json.dumps(block.get("input") or {}, ensure_ascii=False),
                    }
                )

        if text_parts:
            items.append(
                {
                    "role": "assistant" if role == "assistant" else "user",
                    "content": "\n".join(text_parts),
                }
            )
    return items


def assistant_from_openai_response(
    response: Any,
    *,
    model: str,
    provider: str,
    fallback_text: str = "",
    fallback_tool_calls: list[dict[str, Any]] | None = None,
) -> AssistantMessage:
    content: list[dict[str, Any]] = []
    content.extend(fallback_tool_calls or [])
    text = fallback_text
    usage = {}

    if response is not None:
        usage = dump_openai_obj(_get(response, "usage")) or {}
        content = []
        output = _get(response, "output", []) or []
        for item in output:
            call = function_call_from_openai_item(item)
            if call is not None:
                content.append(call)
                continue
            text_part = text_from_openai_item(item)
            if text_part:
                text += text_part

    if text:
        content.insert(0, {"type": "text", "text": text})

    stop_reason = "toolUse" if any(block.get("type") == "toolCall" for block in content) else "stop"
    if response is not None:
        status = str(_get(response, "status", "") or "")
        if status == "incomplete":
            stop_reason = "length"
        elif status == "failed":
            stop_reason = "error"

    return AssistantMessage(
        content=content,
        provider=provider,
        model=str(_get(response, "model", model) or model),
        usage=usage,
        stop_reason=stop_reason,
        error_message=extract_response_error_message(response) if stop_reason == "error" else None,
        error_body=dump_openai_obj(response) if stop_reason == "error" else None,
    )


def function_call_from_openai_item(item: Any) -> dict[str, Any] | None:
    if item is None:
        return None
    item_type = _get(item, "type")
    if item_type != "function_call":
        return None
    arguments = _get(item, "arguments", "{}") or "{}"
    try:
        parsed = json.loads(arguments) if isinstance(arguments, str) else dict(arguments)
    except (TypeError, json.JSONDecodeError):
        parsed = {"_raw": arguments}
    return tool_call_content(
        call_id=str(_get(item, "call_id", _get(item, "id", ""))),
        name=str(_get(item, "name", "")),
        input=parsed,
    )[0]


def merge_chat_tool_call_delta(parts: dict[int, dict[str, Any]], tool_delta: Any) -> None:
    index = int(_get(tool_delta, "index", 0) or 0)
    current = parts.setdefault(index, {"id": "", "name": "", "arguments": ""})
    call_id = _get(tool_delta, "id")
    if call_id:
        current["id"] = str(call_id)
    function = _get(tool_delta, "function")
    name = _get(function, "name")
    if name:
        current["name"] += str(name)
    arguments = _get(function, "arguments")
    if arguments:
        current["arguments"] += str(arguments)


def chat_tool_calls_to_content(parts: dict[int, dict[str, Any]]) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []
    for index in sorted(parts):
        part = parts[index]
        arguments = part.get("arguments") or "{}"
        try:
            parsed = json.loads(arguments)
        except json.JSONDecodeError:
            parsed = {"_raw": arguments}
        calls.append(
            tool_call_content(
                call_id=str(part.get("id") or f"call_{index}"),
                name=str(part.get("name") or ""),
                input=parsed if isinstance(parsed, dict) else {"value": parsed},
            )[0]
        )
    return calls


def text_from_openai_item(item: Any) -> str:
    if item is None:
        return ""
    if _get(item, "type") == "message":
        parts: list[str] = []
        for content_item in _get(item, "content", []) or []:
            if _get(content_item, "type") in {"output_text", "text"}:
                parts.append(str(_get(content_item, "text", "")))
        return "".join(parts)
    if _get(item, "type") in {"output_text", "text"}:
        return str(_get(item, "text", ""))
    return ""


def extract_openai_error_message(event: Any) -> str:
    response = _get(event, "response")
    message = extract_response_error_message(response)
    if message:
        return message
    error = _get(event, "error")
    if error is not None:
        return str(_get(error, "message", error))
    return str(_get(event, "message", "OpenAI response failed"))


def extract_response_error_message(response: Any) -> str | None:
    if response is None:
        return None
    error = _get(response, "error")
    if error is None:
        incomplete_details = _get(response, "incomplete_details")
        reason = _get(incomplete_details, "reason") if incomplete_details is not None else None
        return str(reason) if reason else None
    return str(_get(error, "message", error))


def dump_openai_obj(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool, list, dict)):
        return value
    if hasattr(value, "model_dump"):
        return value.model_dump()
    if hasattr(value, "to_dict"):
        return value.to_dict()
    return value


def _get(value: Any, key: str, default: Any = None) -> Any:
    if value is None:
        return default
    if isinstance(value, dict):
        return value.get(key, default)
    return getattr(value, key, default)


def _stringify_tool_output(output: Any) -> str:
    if isinstance(output, str):
        return output
    return json.dumps(output, ensure_ascii=False)

