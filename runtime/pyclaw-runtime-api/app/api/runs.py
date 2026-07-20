"""Agent run endpoints — thin HTTP adaptation layer."""

from fastapi import APIRouter

from app.schemas.runs import AgentRunRequest, AgentRunResponse
from app.runtime.run_executor import run_agent

router = APIRouter(prefix="/v1/agent", tags=["agent"])


@router.post("/run", response_model=AgentRunResponse)
def agent_run(request: AgentRunRequest) -> AgentRunResponse:
    """Start an agent run with the given prompt and configuration."""
    return run_agent(request)
