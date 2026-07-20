"""Schemas for approval / resume endpoints."""

from __future__ import annotations

from typing import Optional

from pydantic import BaseModel


class AgentResumeRequest(BaseModel):
    """Request to resume an agent after an approval decision."""

    approval_id: str
    decision: str  # APPROVED | REJECTED
    rejection_reason: Optional[str] = None
    # Fields needed to rebuild the run context
    session_id: Optional[str] = None
    provider: str = "openai"
    model: str = "gpt-4o"
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    system: Optional[str] = None
    api_mode: Optional[str] = None
    reasoning_effort: Optional[str] = None
    max_output_tokens: Optional[int] = None
    chatdata_dir: Optional[str] = None
    tool_profile: Optional[str] = None
    tools_allow: Optional[list[str]] = None
    tools_deny: Optional[list[str]] = None
    tools_also_allow: Optional[list[str]] = None
    context_window_tokens: Optional[int] = None
    reserve_tokens: Optional[int] = None
    keep_recent_tokens: Optional[int] = None
    tool_result_max_chars: Optional[int] = None
    disable_compaction: bool = False
    mock_response: Optional[str] = None
    # Claw context fields
    claw_id: Optional[str] = None
    owner_user_id: Optional[str] = None
    claw_name: Optional[str] = None
    role_key: Optional[str] = None
    agent_key: Optional[str] = None
    sandbox_base_url: Optional[str] = None
    conversation_id: Optional[str] = None
    agent_instance_id: Optional[str] = None


class ResumeApprovalResponse(BaseModel):
    """Response from an approval resume."""

    status: str  # COMPLETED | PENDING_APPROVAL | FAILED
    session_id: Optional[str] = None
    message: Optional[str] = None
    text: Optional[str] = None
