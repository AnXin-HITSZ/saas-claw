"""Public Agent runtime wrapper."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from openclaw.agent.events import AgentEvent, message_end_event, message_start_event
from openclaw.agent.loop import ContextTransform, LoopConfig, run_agent_loop
from openclaw.agent.state import AgentState
from openclaw.llm.provider import LlmProvider
from openclaw.llm.types import AssistantMessage, UserMessage, text_content
from openclaw.tools.hooks import ToolHooks
from openclaw.tools.registry import ToolRegistry


class Agent:
    def __init__(
        self,
        *,
        model: str,
        provider: LlmProvider,
        system_prompt: str,
        tools: ToolRegistry | None = None,
        transform_context: ContextTransform | None = None,
        model_options: dict[str, Any] | None = None,
        session_id: str | None = None,
        cwd: Any | None = None,
        workspace_dir: Any | None = None,
        chatdata_dir: Any | None = None,
        tool_hooks: ToolHooks | None = None,
        workspace_only: bool = True,
        readonly: bool = False,
        tool_metadata: dict[str, Any] | None = None,
    ) -> None:
        self.model = model
        self.provider = provider
        self.system_prompt = system_prompt
        self.tools = tools or ToolRegistry()
        self.transform_context = transform_context
        self.model_options = dict(model_options or {})
        self.session_id = session_id
        self.cwd = cwd
        self.workspace_dir = workspace_dir
        self.chatdata_dir = chatdata_dir
        self.tool_hooks = tool_hooks
        self.workspace_only = workspace_only
        self.readonly = readonly
        self.tool_metadata = dict(tool_metadata or {})
        self.state = AgentState()
        self._subscribers: list[Callable[[AgentEvent], None]] = []

    async def prompt(self, text: str) -> AssistantMessage:
        self.emit(AgentEvent("agent_start", {}))
        self.emit(AgentEvent("turn_start", {}))
        message = UserMessage(content=text_content(text))
        self.emit(message_start_event(message))
        self.emit(message_end_event(message))
        result = await run_agent_loop(self._loop_config())
        self.emit(AgentEvent("agent_end", {"message": result}))
        return result

    async def continue_(self) -> AssistantMessage:
        self.emit(AgentEvent("turn_start", {}))
        result = await run_agent_loop(self._loop_config())
        self.emit(AgentEvent("agent_end", {"message": result}))
        return result

    def subscribe(self, callback: Callable[[AgentEvent], None]) -> None:
        self._subscribers.append(callback)

    def emit(self, event: AgentEvent) -> None:
        self._process_event(event)
        for callback in list(self._subscribers):
            callback(event)

    def remove_last_assistant_error(self) -> bool:
        for index in range(len(self.state.messages) - 1, -1, -1):
            message = self.state.messages[index]
            if isinstance(message, AssistantMessage) and message.stop_reason == "error":
                del self.state.messages[index]
                return True
        return False

    def _loop_config(self) -> LoopConfig:
        return LoopConfig(
            model=self.model,
            provider=self.provider,
            system_prompt=self.system_prompt,
            messages=self.state.messages,
            tools=self.tools,
            emit=self.emit,
            transform_context=self.transform_context,
            options=self.model_options,
            session_id=self.session_id,
            cwd=self.cwd,
            workspace_dir=self.workspace_dir,
            chatdata_dir=self.chatdata_dir,
            tool_hooks=self.tool_hooks,
            workspace_only=self.workspace_only,
            readonly=self.readonly,
            tool_metadata=self.tool_metadata,
        )

    def _process_event(self, event: AgentEvent) -> None:
        if event.type == "message_start":
            message = event.payload.get("message")
            self.state.streaming_message = message if isinstance(message, AssistantMessage) else None
        elif event.type == "message_update":
            message = event.payload.get("message")
            if isinstance(message, AssistantMessage):
                self.state.streaming_message = message
        elif event.type == "message_end":
            message = event.payload.get("message")
            if message is not None and (not self.state.messages or self.state.messages[-1] is not message):
                self.state.messages.append(message)
            self.state.streaming_message = None
        elif event.type == "tool_execution_start":
            call = event.payload.get("call")
            call_id = getattr(call, "id", None)
            if call_id:
                self.state.pending_tool_call_ids.add(call_id)
        elif event.type == "tool_execution_end":
            call = event.payload.get("call")
            call_id = getattr(call, "id", None)
            if call_id:
                self.state.pending_tool_call_ids.discard(call_id)
        elif event.type == "turn_end":
            message = event.payload.get("message")
            if isinstance(message, AssistantMessage) and message.error_message:
                self.state.last_error = message.error_message
