"""Approval / resume endpoints — thin HTTP adaptation layer."""

from fastapi import APIRouter

from app.schemas.approvals import AgentResumeRequest, ResumeApprovalResponse
from app.runtime.approval_handler import resume_agent

router = APIRouter(prefix="/v1/agent", tags=["agent"])


@router.post("/resume", response_model=ResumeApprovalResponse)
def agent_resume(request: AgentResumeRequest) -> ResumeApprovalResponse:
    """Resume an agent run after a human approval decision."""
    return resume_agent(request)
