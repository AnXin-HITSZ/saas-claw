"""Core agent loop."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from openclaw.agent.events import (
    AgentEvent,
    message_end_event,
    message_start_event,
    message_update_event,
    turn_end_event,
)
from openclaw.llm.provider import LlmProvider, ProviderEvent
from openclaw.llm.types import (
    AgentMessage,
    AssistantMessage,
    ToolResultMessage,
    extract_tool_calls,
    message_to_dict,
)
from openclaw.tools.executor import execute_tool_call_batch, make_base_context
from openclaw.tools.hooks import ToolHooks
from openclaw.tools.registry import ToolRegistry

ContextTransform = Callable[[list[AgentMessage]], list[AgentMessage]]
EventEmitter = Callable[[AgentEvent], None]


@dataclass
class LoopConfig:
    model: str
    provider: LlmProvider
    system_prompt: str
    messages: list[AgentMessage]
    tools: ToolRegistry
    emit: EventEmitter
    transform_context: ContextTransform | None = None
    options: dict[str, Any] | None = None
    session_id: str | None = None
    cwd: Any | None = None
    workspace_dir: Any | None = None
    chatdata_dir: Any | None = None
    tool_hooks: ToolHooks | None = None
    workspace_only: bool = True
    readonly: bool = False
    tool_metadata: dict[str, Any] | None = None


async def run_agent_loop(config: LoopConfig) -> AssistantMessage:
    has_more_tool_calls = True
    last_assistant: AssistantMessage | None = None

    while has_more_tool_calls:
        has_more_tool_calls = False

        assistant = await stream_assistant_response(config)
        last_assistant = assistant

        if assistant.stop_reason in ("error", "aborted"):
            config.emit(turn_end_event(assistant))
            return assistant

        if assistant.stop_reason == "toolUse":
            tool_results = await execute_tool_calls(config, assistant)
            if tool_results:
                for message in tool_results:
                    config.messages.append(message)
                    config.emit(message_end_event(message))
                has_more_tool_calls = True
                continue

        config.emit(turn_end_event(assistant))
        return assistant

    assert last_assistant is not None
    return last_assistant


async def stream_assistant_response(config: LoopConfig) -> AssistantMessage:
    partial = AssistantMessage(content=[], provider=None, model=config.model)
    config.emit(message_start_event(partial))

    try:
        context_messages = (
            config.transform_context(config.messages)
            if config.transform_context is not None
            else list(config.messages)
        )
        llm_messages = [message_to_dict(message) for message in context_messages]
        events = config.provider.stream(
            model=config.model,
            system_prompt=config.system_prompt,
            messages=llm_messages,
            tools=config.tools.to_llm_tools(),
            options=config.options or {},
        )

        async for event in events:
            if event.type == "start":
                partial.provider = event.data.get("provider", partial.provider)
                partial.model = event.data.get("model", partial.model)
                config.emit(message_update_event(partial))
            elif event.type == "delta":
                _apply_delta(partial, event)
                config.emit(message_update_event(partial))
            elif event.type == "done":
                final = _assistant_from_done(event, partial, config.model)
                config.emit(message_end_event(final))
                return final
            elif event.type == "error":
                final = _assistant_error(
                    event.data.get("message", "provider error"),
                    model=config.model,
                    error_body=event.data,
                )
                config.emit(message_end_event(final))
                return final
    except asyncio.CancelledError:
        final = AssistantMessage(
            content=[],
            model=config.model,
            stop_reason="aborted",
            error_message="agent run was cancelled",
        )
        config.emit(message_end_event(final))
        return final
    except Exception as exc:  # Provider and context guards surface here.
        final = _assistant_error(str(exc), model=config.model, error_body=repr(exc))
        config.emit(message_end_event(final))
        return final

    final = AssistantMessage(content=partial.content, model=config.model, stop_reason="stop")
    config.emit(message_end_event(final))
    return final


async def execute_tool_calls(config: LoopConfig, assistant: AssistantMessage) -> list[ToolResultMessage]:
    calls = extract_tool_calls(assistant.content)
    if not calls:
        return []


    context = make_base_context(
        cwd=config.cwd,
        workspace_dir=config.workspace_dir,
        session_id=config.session_id,
        chatdata_dir=config.chatdata_dir,
        model=config.model,
        provider=getattr(config.provider, "provider_name", None),
        emit=config.emit,
        readonly=config.readonly,
        workspace_only=config.workspace_only,
        metadata=config.tool_metadata,
    )
    outcomes = await execute_tool_call_batch(calls, config.tools, context, config.tool_hooks)

    messages: list[ToolResultMessage] = []
    for outcome in outcomes:
        messages.append(outcome.message)

    return messages


def _apply_delta(partial: AssistantMessage, event: ProviderEvent) -> None:
    if "content" in event.data:
        partial.content = list(event.data["content"])
        return
    if "text" in event.data:
        if partial.content and partial.content[-1].get("type") == "text":
            partial.content[-1]["text"] += str(event.data["text"])
        else:
            partial.content.append({"type": "text", "text": str(event.data["text"])})


def _assistant_from_done(event: ProviderEvent, partial: AssistantMessage, model: str) -> AssistantMessage:
    message = event.data.get("message")
    if isinstance(message, AssistantMessage):
        if message.model is None:
            message.model = model
        return message
    return AssistantMessage(
        content=list(event.data.get("content", partial.content)),
        provider=event.data.get("provider", partial.provider),
        model=event.data.get("model", partial.model or model),
        usage=dict(event.data.get("usage") or {}),
        stop_reason=event.data.get("stopReason", event.data.get("stop_reason", "stop")),
        error_message=event.data.get("errorMessage", event.data.get("error_message")),
    )


def _assistant_error(message: str, *, model: str, error_body: Any = None) -> AssistantMessage:
    return AssistantMessage(
        content=[],
        model=model,
        stop_reason="error",
        error_message=message,
        error_body=error_body,
    )
