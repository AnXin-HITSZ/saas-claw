"""Production-facing session wrapper around Agent."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from pathlib import Path

from openclaw.agent.agent import Agent
from openclaw.agent.events import AgentEvent
from openclaw.agent.loop import ContextTransform
from openclaw.custom.hooks import NoopSessionHooks, SessionHooks
from openclaw.guards.context_guard import ContextOverflowError
from openclaw.guards.transcript_tool_result_guard import TranscriptToolResultGuard
from openclaw.llm.types import AgentMessage, AssistantMessage
from openclaw.session.context import (
    CompactionPreparation,
    CompactionSettings,
    estimate_context_tokens,
    should_preemptively_compact_before_prompt,
    truncate_oversized_tool_results,
)
from openclaw.session.store import SessionStore
from openclaw.session.transcript import Transcript


@dataclass
class RetryPolicy:
    enabled: bool = True
    max_attempts: int = 3
    base_delay_seconds: float = 1.0
    max_delay_seconds: float = 30.0


@dataclass
class SessionContextPolicy:
    compaction: CompactionSettings = field(default_factory=CompactionSettings)
    terminal_overflow_message: str = (
        "Context overflow: prompt too large for the model. Try a new session or use a larger-context model."
    )


class AgentSession:
    def __init__(
        self,
        *,
        session_id: str,
        agent: Agent,
        store: SessionStore,
        transcript: Transcript,
        retry_policy: RetryPolicy | None = None,
        hooks: SessionHooks | None = None,
        transcript_guard: TranscriptToolResultGuard | None = None,
        cwd: str | None = None,
        workspace_dir: str | None = None,
        context_policy: SessionContextPolicy | None = None,
    ) -> None:
        self.session_id = session_id
        self.agent = agent
        self.store = store
        self.transcript = transcript
        self.retry_policy = retry_policy or RetryPolicy()
        self.hooks = hooks or NoopSessionHooks()
        self.transcript_guard = transcript_guard or TranscriptToolResultGuard()
        self.cwd = cwd
        self.workspace_dir = workspace_dir
        self.context_policy = context_policy or SessionContextPolicy()
        self.retry_count = 0
        self.overflow_recovery_attempted = False
        self._hook_tasks: list[asyncio.Task[None]] = []
        self._history_loaded = False
        self._user_transform_context: ContextTransform | None = self.agent.transform_context
        self.agent.transform_context = self._transform_context_before_prompt
        self.agent.subscribe(self._on_event)

    async def run_prompt(self, text: str) -> AssistantMessage:
        self.load_history_once()
        message = await self.agent.prompt(text)
        return await self.handle_post_agent_run(message)

    def load_history_once(self) -> None:
        if self._history_loaded:
            return
        self._history_loaded = True
        if self.agent.state.messages:
            return
        self.agent.state.messages.extend(self.transcript.read_context_messages())

    async def handle_post_agent_run(self, message: AssistantMessage) -> AssistantMessage:
        current = message
        while True:
            if self.is_context_overflow(current) and not self.overflow_recovery_attempted:
                self.overflow_recovery_attempted = True
                self.agent.remove_last_assistant_error()
                self.run_auto_compaction(reason="provider_overflow")
                current = await self.agent.continue_()
                continue

            if self.is_retryable_error(current) and await self.prepare_retry():
                current = await self.agent.continue_()
                continue

            if not self.is_retryable_error(current):
                self.retry_count = 0
            await self._drain_hook_tasks()
            return current

    def is_retryable_error(self, message: AssistantMessage) -> bool:
        if message.stop_reason != "error" or not message.error_message:
            return False
        if self.is_context_overflow(message):
            return False
        text = message.error_message.lower()
        retry_markers = (
            "overloaded",
            "rate limit",
            "429",
            "5xx",
            "500",
            "502",
            "503",
            "504",
            "network error",
            "timeout",
            "stream ended",
        )
        return any(marker in text for marker in retry_markers)

    def is_context_overflow(self, message: AssistantMessage) -> bool:
        if message.stop_reason != "error" or not message.error_message:
            return False
        text = message.error_message.lower()
        return (
            "context length" in text
            or "context overflow" in text
            or "maximum context" in text
            or isinstance(message.error_body, ContextOverflowError)
        )

    async def prepare_retry(self) -> bool:
        if not self.retry_policy.enabled:
            return False
        self.retry_count += 1
        if self.retry_count > self.retry_policy.max_attempts:
            self.agent.emit(AgentEvent("auto_retry_end", {"retry_count": self.retry_count}))
            return False
        delay = min(
            self.retry_policy.base_delay_seconds * 2 ** (self.retry_count - 1),
            self.retry_policy.max_delay_seconds,
        )
        self.agent.emit(AgentEvent("auto_retry_start", {"retry_count": self.retry_count, "delay": delay}))
        self.agent.remove_last_assistant_error()
        if delay > 0:
            await asyncio.sleep(delay)
        return True

    def run_auto_compaction(self, *, reason: str = "context_budget") -> CompactionPreparation | None:
        settings = self.context_policy.compaction
        if not settings.enabled:
            return None
        preparation = self.transcript.compact(settings=settings, reason=reason)
        if not preparation.should_compact:
            return preparation
        compacted_messages = self.transcript.read_context_messages()
        self.agent.state.messages[:] = compacted_messages
        self.agent.emit(
            AgentEvent(
                "session_compaction",
                {
                    "reason": reason,
                    "tokens_before": preparation.tokens_before,
                    "first_kept_entry_id": preparation.first_kept_entry_id,
                },
            )
        )
        return preparation

    def _transform_context_before_prompt(self, messages: list[AgentMessage]) -> list[AgentMessage]:
        transformed = self._apply_user_transform(messages)
        settings = self.context_policy.compaction
        route = should_preemptively_compact_before_prompt(transformed, settings=settings)

        if route in {"compact_only", "compact_then_truncate"}:
            preparation = self.run_auto_compaction(reason=f"pre_prompt:{route}")
            if preparation is not None and preparation.should_compact:
                transformed = self._apply_user_transform(self.agent.state.messages)
                route = should_preemptively_compact_before_prompt(transformed, settings=settings)

        if route in {"truncate_tool_results_only", "compact_then_truncate"}:
            transformed, truncated = truncate_oversized_tool_results(
                transformed,
                max_chars=settings.tool_result_max_chars,
            )
            if truncated:
                self.agent.emit(AgentEvent("tool_results_truncated", {"count": truncated, "route": route}))

        tokens = estimate_context_tokens(transformed)
        if tokens > settings.prompt_budget_tokens:
            raise ContextOverflowError(
                f"context length exceeded: {tokens} > {settings.prompt_budget_tokens}"
            )
        return transformed

    def _apply_user_transform(self, messages: list[AgentMessage]) -> list[AgentMessage]:
        if self._user_transform_context is None:
            return list(messages)
        return self._user_transform_context(messages)

    def _on_event(self, event: AgentEvent) -> None:
        if event.type != "message_end":
            return
        message = event.payload.get("message")
        if message is None:
            return
        safe_message = self.transcript_guard.before_append(message)
        self.transcript.append_message(safe_message)
        status = "error" if isinstance(message, AssistantMessage) and message.stop_reason == "error" else "active"
        self.store.touch(
            self.session_id,
            session_file=str(Path(self.transcript.path).name),
            status=status,
            cwd=self.cwd,
            workspace_dir=self.workspace_dir,
            model=self.agent.model,
            provider=getattr(self.agent.provider, "__class__", type(self.agent.provider)).__name__,
        )
        self._schedule_after_persisted(safe_message)

    def _schedule_after_persisted(self, message: AgentMessage) -> None:
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        self._hook_tasks.append(loop.create_task(self.hooks.after_message_persisted(message)))

    async def _drain_hook_tasks(self) -> None:
        if not self._hook_tasks:
            return
        tasks = self._hook_tasks
        self._hook_tasks = []
        await asyncio.gather(*tasks)
