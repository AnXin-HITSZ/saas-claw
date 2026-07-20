"""Approval handler — resume an agent after a human approval decision."""

from __future__ import annotations

import logging
from typing import Optional

from openclaw.agent.agent import Agent
from openclaw.agent.state import AgentState
from openclaw.llm.types import AssistantMessage
from openclaw.tools.approval import (
    ApprovalRuntimeContext,
    PendingToolApproval,
    PendingToolApprovalError,
)
from openclaw.tools.approval_hooks import ApprovalToolHooks

from app.runtime.provider_factory import build_provider
from app.runtime.policy_factory import build_policy, build_registry
from app.runtime.session_factory import build_session
from app.runtime.run_executor import _build_model_options, _build_tool_metadata, _assistant_text
from app.schemas.approvals import AgentResumeRequest, ResumeApprovalResponse
from app.schemas.runs import ApprovalResponse

logger = logging.getLogger(__name__)


def resume_agent(request: AgentResumeRequest) -> ResumeApprovalResponse:
    """Resume an agent run after an approval decision.

    APPROVED: continue execution with the approved tool call.
    REJECTED: inject a rejection message and continue.
    """
    try:
        decision = request.decision.upper()
        model = request.model or "gpt-4o"

        # 1. Build provider
        provider = build_provider(
            provider=request.provider,
            api_key=request.api_key,
            base_url=request.base_url,
            api_mode=request.api_mode,
            model=model,
            reasoning_effort=request.reasoning_effort,
            max_output_tokens=request.max_output_tokens,
        )

        # 2. Build policy and registry
        policy = build_policy(
            tool_profile=request.tool_profile,
            tools_allow=request.tools_allow,
            tools_deny=request.tools_deny,
            tools_also_allow=request.tools_also_allow,
        )
        registry = build_registry(policy)

        # 3. Build approval hooks (with pre-approved call id if APPROVED)
        approval_hooks = None
        if request.claw_id and request.sandbox_base_url:
            context = ApprovalRuntimeContext(
                session_id=request.session_id or "",
                claw_id=request.claw_id or "",
                owner_user_id=request.owner_user_id or "",
                claw_name=request.claw_name or "",
                role_key=request.role_key or "",
                agent_key=request.agent_key or "",
                sandbox_base_url=request.sandbox_base_url or "",
                conversation_id=request.conversation_id,
                agent_instance_id=request.agent_instance_id,
                provider_name=request.provider,
                model=model,
                system_prompt=request.system or "",
                api_mode=request.api_mode or "",
                tool_profile=request.tool_profile or "",
                tools_allow=request.tools_allow,
                tools_deny=request.tools_deny,
                tools_also_allow=request.tools_also_allow,
            )
            approved_ids = {request.approval_id} if decision == "APPROVED" else set()
            approval_hooks = ApprovalToolHooks(
                pending_store=None,
                request_context=context,
                approved_tool_call_ids=approved_ids,
            )

        # 4. Build agent and session
        session_id = request.session_id or f"resume-{_short_id()}"
        tool_metadata = _build_tool_metadata(registry)
        model_options = _build_model_options_for_resume(request, model)

        agent = Agent(
            model=model,
            provider=provider,
            system_prompt=request.system or "",
            tools=registry,
            tool_metadata=tool_metadata,
            session_id=session_id,
            chatdata_dir=request.chatdata_dir,
        )

        session = build_session(
            agent=agent,
            session_id=session_id,
            chatdata_dir=request.chatdata_dir,
            context_window_tokens=request.context_window_tokens,
            reserve_tokens=request.reserve_tokens,
            keep_recent_tokens=request.keep_recent_tokens,
            tool_result_max_chars=request.tool_result_max_chars,
            disable_compaction=request.disable_compaction,
        )

        # 5. Continue agent execution
        if decision == "APPROVED":
            message = session.agent.continue_()
        elif decision == "REJECTED":
            rejection_msg = request.rejection_reason or "Tool execution was rejected by the user."
            message = session.run_prompt(
                f"[APPROVAL REJECTED] {rejection_msg}\nPlease continue without using that tool."
            )
        else:
            return ResumeApprovalResponse(
                status="FAILED",
                session_id=session_id,
                message=f"Unknown decision: {decision}",
            )

        # 6. Handle result
        if isinstance(message, AssistantMessage):
            return ResumeApprovalResponse(
                status="COMPLETED",
                session_id=session_id,
                text=_assistant_text(message),
            )
        return ResumeApprovalResponse(
            status="COMPLETED",
            session_id=session_id,
            message=str(message),
        )

    except PendingToolApprovalError as exc:
        approval = exc.args[0] if exc.args else None
        return ResumeApprovalResponse(
            status="PENDING_APPROVAL",
            session_id=request.session_id,
            message=str(approval) if approval else "Another approval is required",
        )

    except Exception as exc:
        logger.exception("Agent resume failed")
        return ResumeApprovalResponse(
            status="FAILED",
            session_id=request.session_id,
            message=str(exc),
        )


def _build_model_options_for_resume(request: AgentResumeRequest, model: str) -> dict:
    """Build model options dict from resume request."""
    options: dict = {}
    if request.reasoning_effort:
        options["reasoning_effort"] = request.reasoning_effort
    if request.max_output_tokens:
        options["max_output_tokens"] = request.max_output_tokens
    if request.api_mode:
        options["api_mode"] = request.api_mode
    return options


def _short_id() -> str:
    import uuid
    return uuid.uuid4().hex[:12]
