"""Core message and content-block types."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field, is_dataclass
from time import time
from typing import Any, Literal

StopReason = Literal["stop", "length", "toolUse", "error", "aborted"]
MessageRole = Literal["user", "assistant", "tool", "system"]
ContentBlock = dict[str, Any]


@dataclass
class TextBlock:
    text: str
    type: Literal["text"] = field(default="text", init=False)


@dataclass
class ToolCallBlock:
    id: str
    name: str
    input: dict[str, Any]
    type: Literal["toolCall"] = field(default="toolCall", init=False)


@dataclass
class ToolResultBlock:
    tool_call_id: str
    name: str
    output: Any
    is_error: bool = False
    details: dict[str, Any] = field(default_factory=dict)
    progress: dict[str, Any] | None = None
    terminate: bool = False
    type: Literal["toolResult"] = field(default="toolResult", init=False)


@dataclass
class BaseMessage:
    content: list[ContentBlock]
    role: str
    timestamp: float = field(default_factory=time)


@dataclass
class UserMessage(BaseMessage):
    role: Literal["user"] = field(default="user", init=False)


@dataclass
class AssistantMessage(BaseMessage):
    role: Literal["assistant"] = field(default="assistant", init=False)
    provider: str | None = None
    model: str | None = None
    usage: dict[str, Any] = field(default_factory=dict)
    stop_reason: StopReason = "stop"
    error_message: str | None = None
    error_code: str | None = None
    error_type: str | None = None
    error_body: Any | None = None


@dataclass
class ToolResultMessage(BaseMessage):
    role: Literal["tool"] = field(default="tool", init=False)


AgentMessage = UserMessage | AssistantMessage | ToolResultMessage | BaseMessage


def block_to_dict(block: ContentBlock | TextBlock | ToolCallBlock | ToolResultBlock) -> ContentBlock:
    if isinstance(block, dict):
        return dict(block)
    if is_dataclass(block):
        return asdict(block)
    raise TypeError(f"unsupported content block: {type(block)!r}")


def text_content(text: str) -> list[ContentBlock]:
    return [block_to_dict(TextBlock(text=text))]


def tool_call_content(call_id: str, name: str, input: dict[str, Any]) -> list[ContentBlock]:
    return [block_to_dict(ToolCallBlock(id=call_id, name=name, input=input))]


def tool_result_content(
    tool_call_id: str,
    name: str,
    output: Any,
    *,
    is_error: bool = False,
    details: dict[str, Any] | None = None,
    progress: dict[str, Any] | None = None,
    terminate: bool = False,
) -> list[ContentBlock]:
    return [
        block_to_dict(
            ToolResultBlock(
                tool_call_id=tool_call_id,
                name=name,
                output=output,
                is_error=is_error,
                details=dict(details or {}),
                progress=progress,
                terminate=terminate,
            )
        )
    ]


def extract_tool_calls(content: list[ContentBlock]) -> list[ToolCallBlock]:
    calls: list[ToolCallBlock] = []
    for block in content:
        if block.get("type") != "toolCall":
            continue
        calls.append(
            ToolCallBlock(
                id=str(block.get("id", "")),
                name=str(block.get("name", "")),
                input=dict(block.get("input") or {}),
            )
        )
    return calls


def message_to_dict(message: AgentMessage) -> dict[str, Any]:
    data: dict[str, Any] = {
        "role": message.role,
        "content": [block_to_dict(block) for block in message.content],
        "timestamp": message.timestamp,
    }
    if isinstance(message, AssistantMessage):
        data.update(
            {
                "provider": message.provider,
                "model": message.model,
                "usage": message.usage,
                "stopReason": message.stop_reason,
                "errorMessage": message.error_message,
                "errorCode": message.error_code,
                "errorType": message.error_type,
                "errorBody": message.error_body,
            }
        )
    return {key: value for key, value in data.items() if value is not None}


def message_from_dict(data: dict[str, Any]) -> AgentMessage:
    role = data.get("role")
    content = list(data.get("content") or [])
    timestamp = float(data.get("timestamp") or time())
    if role == "user":
        return UserMessage(content=content, timestamp=timestamp)
    if role == "assistant":
        return AssistantMessage(
            content=content,
            timestamp=timestamp,
            provider=data.get("provider"),
            model=data.get("model"),
            usage=dict(data.get("usage") or {}),
            stop_reason=data.get("stopReason", data.get("stop_reason", "stop")),
            error_message=data.get("errorMessage", data.get("error_message")),
            error_code=data.get("errorCode", data.get("error_code")),
            error_type=data.get("errorType", data.get("error_type")),
            error_body=data.get("errorBody", data.get("error_body")),
        )
    if role == "tool":
        return ToolResultMessage(content=content, timestamp=timestamp)
    return BaseMessage(role=str(role), content=content, timestamp=timestamp)


