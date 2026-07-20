"""Agent run executor — orchestrates a single agent run from request to response."""

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
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.policy import ToolPolicy
from openclaw.tools.registry import ToolRegistry
from openclaw.tools.catalog import CORE_TOOL_CATALOG, user_visible_catalog
from openclaw.tools.resolver import resolve_tools, ToolResolveInput
from openclaw.llm.openai_provider import OpenAIProvider

from app.runtime.provider_factory import build_provider
from app.runtime.policy_factory import build_policy
from app.runtime.session_factory import build_session
from app.schemas.runs import AgentRunRequest, AgentRunResponse, ApprovalResponse

logger = logging.getLogger(__name__)


def run_agent(request: AgentRunRequest) -> AgentRunResponse:
    """Execute a full agent run.

    Returns COMPLETED, PENDING_APPROVAL, or FAILED status.
    """
    try:
        # 1. Resolve model
        model = request.model or "gpt-4o"

        # 2. Build provider
        provider = build_provider(
            provider=request.provider,
            api_key=request.api_key,
            base_url=request.base_url,
            api_mode=request.api_mode,
            model=model,
            reasoning_effort=request.reasoning_effort,
            max_output_tokens=request.max_output_tokens,
        )

        # 3. Build tool policy and registry
        policy = build_policy(
            tool_profile=request.tool_profile,
            tools_allow=request.tools_allow,
            tools_deny=request.tools_deny,
            tools_also_allow=request.tools_also_allow,
        )
        registry = build_tool_registry(policy)

        # 4. Build tool metadata
        tool_metadata = _build_tool_metadata(registry)

        # 5. Build system prompt
        system_prompt = request.system or ""

        # 6. Build model options
        model_options = _build_model_options(request, model)

        # 7. Build session id
        session_id = request.session_id or f"run-{_short_id()}"

        # 8. Set up approval hooks if we have claw context
        approval_hooks = None
        if request.claw_id and request.sandbox_base_url:
            approval_hooks = _build_approval_hooks(request)

        # 9. Build agent
        agent = Agent(
            model=model,
            provider=provider,
            system_prompt=system_prompt,
            tools=registry,
            tool_metadata=tool_metadata,
            session_id=session_id,
            chatdata_dir=request.chatdata_dir,
        )

        # 10. Build session
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

        # 11. Run agent
        message = session.run_prompt(request.prompt)

        # 12. Check result
        if isinstance(message, AssistantMessage):
            return AgentRunResponse(
                status="COMPLETED",
                session_id=session_id,
                text=_assistant_text(message),
            )

        return AgentRunResponse(
            status="COMPLETED",
            session_id=session_id,
            message=str(message),
        )

    except PendingToolApprovalError as exc:
        approval = exc.args[0] if exc.args else None
        if isinstance(approval, PendingToolApproval):
            return AgentRunResponse(
                status="PENDING_APPROVAL",
                session_id=request.session_id,
                approval=ApprovalResponse(
                    id=approval.approval_id,
                    tool_name=approval.tool_name,
                    risk=approval.risk,
                    intent=approval.intent_summary,
                    arguments_preview=approval.arguments_preview,
                    pending_state_key=approval.pending_state_key,
                    expires_at=approval.expires_at,
                ),
            )
        return AgentRunResponse(
            status="PENDING_APPROVAL",
            session_id=request.session_id,
        )

    except Exception as exc:
        logger.exception("Agent run failed")
        return AgentRunResponse(
            status="FAILED",
            session_id=request.session_id,
            message=str(exc),
        )


# ---------------------------------------------------------------------------
# Internal helpers (extracted from openclaw/api.py)
# ---------------------------------------------------------------------------

def _build_tool_metadata(registry: ToolRegistry) -> dict:
    """Build tool metadata map for the agent."""
    metadata: dict = {}
    for name, tool in registry.tools.items():
        from openclaw.tools.registry import normalize_tool
        td = normalize_tool(tool)
        metadata[name] = {
            "label": td.label or name,
            "description": td.description or "",
            "readonly": td.metadata.readonly if td.metadata else False,
        }
    return metadata


def _build_model_options(request: AgentRunRequest, model: str) -> dict:
    """Build model options dict from request."""
    options: dict = {}
    if request.reasoning_effort:
        options["reasoning_effort"] = request.reasoning_effort
    if request.max_output_tokens:
        options["max_output_tokens"] = request.max_output_tokens
    if request.api_mode:
        options["api_mode"] = request.api_mode
    return options


def _build_approval_hooks(request: AgentRunRequest) -> ApprovalToolHooks:
    """Build approval tool hooks from request context."""
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
        model=request.model or "",
        system_prompt=request.system or "",
        api_mode=request.api_mode or "",
        tool_profile=request.tool_profile or "",
        tools_allow=request.tools_allow,
        tools_deny=request.tools_deny,
        tools_also_allow=request.tools_also_allow,
    )
    return ApprovalToolHooks(pending_store=None, request_context=context)


def _assistant_text(message: AssistantMessage) -> str:
    """Extract plain text from an AssistantMessage."""
    from openclaw.llm.types import text_content
    return text_content(message)


def _short_id() -> str:
    import uuid
    return uuid.uuid4().hex[:12]
