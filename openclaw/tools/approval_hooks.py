"""Approval-driven tool hooks.

``ApprovalToolHooks`` inspects each tool call before ``tool.execute`` runs:

- low risk        -> ALLOW
- medium/high risk -> PENDING_APPROVAL (writes runtime state, raises via
  the executor so the API layer can return PENDING_APPROVAL to Spring).
- policy violation -> DENY (executor returns a blocked tool result to the
  Agent loop, no approval is created).
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Callable
from typing import Any

from openclaw.llm.types import ToolCallBlock, message_to_dict
from openclaw.tools.approval import (
    ApprovalRuntimeContext,
    PendingToolApproval,
    build_arguments_preview,
    summarise_intent,
)
from openclaw.tools.approval_store import (
    DEFAULT_TTL_SECONDS,
    PendingApprovalStore,
    pending_state_key,
)
from openclaw.tools.hooks import NoopToolHooks, ToolExecutionDecision
from openclaw.tools.types import ToolDefinition, ToolExecutionContext


MessagesSnapshotProvider = Callable[[], list[dict[str, Any]]]


class ApprovalToolHooks(NoopToolHooks):
    def __init__(
        self,
        *,
        pending_store: PendingApprovalStore,
        request_context: ApprovalRuntimeContext,
        messages_snapshot_provider: MessagesSnapshotProvider | None = None,
        ttl_seconds: int = DEFAULT_TTL_SECONDS,
        clock: Any | None = None,
    ) -> None:
        self.pending_store = pending_store
        self.request_context = request_context
        self.ttl_seconds = ttl_seconds
        self._clock = clock or time.time
        self._messages_snapshot_provider = messages_snapshot_provider
        self._current_assistant_snapshot: dict[str, Any] | None = None

    def set_assistant_snapshot(self, assistant_message_dict: dict[str, Any]) -> None:
        """Record the assistant message (with tool_calls) that produced the pending call."""

        self._current_assistant_snapshot = assistant_message_dict

    async def before_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        context: ToolExecutionContext,
    ) -> ToolExecutionDecision:
        hard_policy_error = _check_hard_policy(tool, arguments, context, self.request_context)
        if hard_policy_error:
            return ToolExecutionDecision(status="DENY", denied_reason=hard_policy_error, reason=hard_policy_error)

        risk = getattr(tool.metadata, "risk", "low") or "low"
        if risk == "low":
            return ToolExecutionDecision(status="ALLOW", arguments=arguments)

        approval = self._create_pending_approval(call, tool, arguments)
        return ToolExecutionDecision(
            status="PENDING_APPROVAL",
            reason="该工具调用需要用户确认后继续执行。",
            arguments=arguments,
            approval=approval,
        )

    def _create_pending_approval(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
    ) -> PendingToolApproval:
        approval_id = f"approval_{uuid.uuid4().hex}"
        state_key = pending_state_key(approval_id)
        expires_at_epoch = int(self._clock()) + int(self.ttl_seconds)
        expires_at_iso = _format_expires_at(expires_at_epoch)

        approval = PendingToolApproval(
            approval_id=approval_id,
            session_id=self.request_context.session_id,
            tool_call_id=call.id,
            tool_name=call.name,
            risk=getattr(tool.metadata, "risk", "low") or "low",
            intent_summary=summarise_intent(call.name, arguments),
            arguments_preview=build_arguments_preview(arguments),
            pending_state_key=state_key,
            expires_at=expires_at_iso,
            claw_id=self.request_context.claw_id,
            owner_user_id=self.request_context.owner_user_id,
            claw_name=self.request_context.claw_name,
            role_key=self.request_context.role_key,
            agent_key=self.request_context.agent_key,
        )

        messages_snapshot: list[dict[str, Any]] = []
        if self._messages_snapshot_provider is not None:
            messages_snapshot = list(self._messages_snapshot_provider() or [])
        elif self.request_context.messages_snapshot:
            messages_snapshot = list(self.request_context.messages_snapshot)

        state: dict[str, Any] = {
            "approval_id": approval_id,
            "session_id": self.request_context.session_id,
            "claw_id": self.request_context.claw_id,
            "owner_user_id": self.request_context.owner_user_id,
            "claw_name": self.request_context.claw_name,
            "role_key": self.request_context.role_key,
            "agent_key": self.request_context.agent_key,
            "sandbox_base_url": self.request_context.sandbox_base_url,
            "provider_name": self.request_context.provider_name,
            "model": self.request_context.model,
            "system_prompt": self.request_context.system_prompt,
            "api_mode": self.request_context.api_mode,
            "tool_profile": self.request_context.tool_profile,
            "tools_allow": self.request_context.tools_allow,
            "tools_deny": self.request_context.tools_deny,
            "tools_also_allow": self.request_context.tools_also_allow,
            "messages": messages_snapshot,
            "assistant_message": self._current_assistant_snapshot,
            "tool_call": {
                "id": call.id,
                "name": call.name,
                "input": dict(arguments),
            },
            "risk": approval.risk,
            "intent_summary": approval.intent_summary,
            "arguments_preview": approval.arguments_preview,
            "expires_at_epoch": expires_at_epoch,
        }
        self.pending_store.save(approval_id, state, ttl_seconds=self.ttl_seconds)
        return approval


def _check_hard_policy(
    tool: ToolDefinition,
    arguments: dict[str, Any],
    context: ToolExecutionContext,
    runtime_context: ApprovalRuntimeContext,
) -> str | None:
    """Reject calls that violate hard security invariants regardless of user approval."""

    execution_scope = getattr(tool.metadata, "execution_scope", None)
    if execution_scope == "claw_sandbox":
        sandbox_base_url = runtime_context.sandbox_base_url
        if not sandbox_base_url or not str(sandbox_base_url).strip():
            metadata_url = (context.metadata or {}).get("sandbox_base_url")
            if not metadata_url or not str(metadata_url).strip():
                return "sandbox_base_url 未配置，无法在 Claw 沙箱内执行该工具。"

    if runtime_context.claw_id:
        arg_claw_id = arguments.get("claw_id") or arguments.get("clawId")
        if arg_claw_id and str(arg_claw_id) != str(runtime_context.claw_id):
            return "工具参数中的 Claw 标识与当前 Claw 不一致，拒绝执行。"

    if runtime_context.owner_user_id:
        arg_owner = arguments.get("owner_user_id") or arguments.get("ownerUserId")
        if arg_owner and str(arg_owner) != str(runtime_context.owner_user_id):
            return "工具参数中的 owner_user_id 与当前 Claw 所有者不一致，拒绝执行。"

    return None


def _format_expires_at(epoch_seconds: int) -> str:
    from datetime import datetime, timezone

    return datetime.fromtimestamp(epoch_seconds, tz=timezone.utc).isoformat()


def messages_to_dict_list(messages: list[Any]) -> list[dict[str, Any]]:
    """Helper to serialize an ``AgentMessage`` list to plain dicts for the pending state."""

    return [message_to_dict(message) for message in messages]


__all__ = [
    "ApprovalToolHooks",
    "messages_to_dict_list",
]
