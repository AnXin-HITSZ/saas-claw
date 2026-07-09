"""FastAPI service entrypoint for pyclaw."""

from __future__ import annotations

import hmac
import os
from pathlib import Path
from typing import Any, Literal
from uuid import uuid4

try:
    from fastapi import Depends, FastAPI, Header, HTTPException
    from pydantic import BaseModel, Field
except ImportError as exc:  # pragma: no cover - exercised only without the api extra installed.
    raise RuntimeError(
        "openclaw.api requires the api extra. Install with: python -m pip install -e .[api]"
    ) from exc

from openclaw.agent.agent import Agent
from openclaw.cli import DEFAULT_MODEL, DEFAULT_SYSTEM_PROMPT, sanitize_session_id
from openclaw.config import load_env_file
from openclaw.llm.openai_provider import OpenAIProvider
from openclaw.llm.provider import MockProvider
from openclaw.channels.config import load_channel_agent_config
from openclaw.channels.dispatcher import build_channel_session_id
from openclaw.channels.api_routes import create_channel_router
from openclaw.llm.types import AssistantMessage, message_to_dict
from openclaw.session.agent_session import AgentSession, SessionContextPolicy
from openclaw.session.context import CompactionSettings
from openclaw.session.paths import resolve_chatdata_dir, resolve_session_store_path, resolve_session_transcript_path
from openclaw.session.store import SessionStore
from openclaw.session.transcript import Transcript
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.policy import ToolPolicy

ApiProviderName = Literal["openai", "mock"]
ApiMode = Literal["auto", "responses", "chat_completions", "chat-completions"]
ToolProfileName = Literal["minimal", "readonly", "coding", "messaging", "full"]
ShellApprovalModeName = Literal["auto", "require", "deny"]


class HealthResponse(BaseModel):
    status: str = "ok"
    service: str = "pyclaw-api"


class AgentRunRequest(BaseModel):
    prompt: str = Field(..., min_length=1)
    session_id: str | None = None
    provider: ApiProviderName = "openai"
    model: str | None = None
    api_key: str | None = None
    base_url: str | None = None
    system: str = DEFAULT_SYSTEM_PROMPT
    api_mode: ApiMode = "auto"
    reasoning_effort: Literal["low", "medium", "high"] | None = None
    max_output_tokens: int | None = Field(default=None, ge=1)
    chatdata_dir: str | None = None
    tool_profile: ToolProfileName = "coding"
    tools_allow: list[str] | None = None
    tools_deny: list[str] | None = None
    tools_also_allow: list[str] | None = None
    shell_approval: ShellApprovalModeName = "deny"
    context_window_tokens: int = Field(default=120_000, ge=1)
    reserve_tokens: int = Field(default=16_384, ge=0)
    keep_recent_tokens: int = Field(default=20_000, ge=0)
    tool_result_max_chars: int = Field(default=20_000, ge=1)
    disable_compaction: bool = False
    mock_response: str | None = None


class AgentRunResponse(BaseModel):
    session_id: str
    message: dict[str, Any]
    text: str


app = FastAPI(title="pyclaw API", version="0.1.0")




def include_routes(target_app: FastAPI, router: Any) -> None:
    target_app.include_router(router)
    route_paths = {getattr(route, "path", None) for route in target_app.routes}
    missing_routes = [route for route in getattr(router, "routes", []) if getattr(route, "path", None) not in route_paths]
    if missing_routes:
        target_app.router.routes.extend(missing_routes)


async def build_channel_agent_session(message: Any) -> AgentSession:
    load_env_file_if_configured()
    channel_agent = load_channel_agent_config()
    request = AgentRunRequest(
        prompt="channel bootstrap",
        session_id=build_channel_session_id(message),
        provider=channel_agent.provider,
        model=channel_agent.model,
        system=channel_agent.system or DEFAULT_SYSTEM_PROMPT,
        api_mode=channel_agent.api_mode,
        chatdata_dir=channel_agent.chatdata_dir,
        tool_profile=channel_agent.tool_profile,
        shell_approval=channel_agent.shell_approval,
    )
    session_id = build_channel_session_id(message)
    cwd = os.getcwd()
    model = request.model or os.environ.get("OPENAI_MODEL") or DEFAULT_MODEL
    provider = build_provider(request, model=model)
    policy = build_policy(request)
    agent = Agent(
        model=model,
        provider=provider,
        system_prompt=request.system,
        tools=build_tool_registry(policy),
        model_options=build_model_options(request),
        session_id=session_id,
        cwd=cwd,
        workspace_dir=cwd,
        chatdata_dir=resolve_chatdata_dir(request.chatdata_dir),
        readonly=policy.readonly,
        tool_metadata=build_tool_metadata(request),
    )
    return build_session(request, agent, session_id=session_id, cwd=cwd)


include_routes(app, create_channel_router(session_factory=build_channel_agent_session))

def require_api_token(authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("PYCLAW_API_TOKEN")
    if not expected:
        return

    prefix = "Bearer "
    if not authorization or not authorization.startswith(prefix):
        raise HTTPException(status_code=401, detail="Missing API token")

    token = authorization[len(prefix) :]
    if not hmac.compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="Invalid API token")

@app.get("/healthz", response_model=HealthResponse)
def healthz() -> HealthResponse:
    return HealthResponse()


@app.post("/v1/agent/run", response_model=AgentRunResponse)
async def run_agent(
    request: AgentRunRequest,
    _: None = Depends(require_api_token),
) -> AgentRunResponse:
    try:
        message, session_id = await run_agent_request(request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return AgentRunResponse(
        session_id=session_id,
        message=message_to_dict(message),
        text=assistant_text(message),
    )


async def run_agent_request(request: AgentRunRequest) -> tuple[AssistantMessage, str]:
    load_env_file_if_configured()
    session_id = sanitize_session_id(request.session_id or uuid4().hex)
    cwd = os.getcwd()
    model = request.model or os.environ.get("OPENAI_MODEL") or DEFAULT_MODEL
    provider = build_provider(request, model=model)
    policy = build_policy(request)
    agent = Agent(
        model=model,
        provider=provider,
        system_prompt=request.system,
        tools=build_tool_registry(policy),
        model_options=build_model_options(request),
        session_id=session_id,
        cwd=cwd,
        workspace_dir=cwd,
        chatdata_dir=resolve_chatdata_dir(request.chatdata_dir),
        readonly=policy.readonly,
        tool_metadata=build_tool_metadata(request),
    )
    session = build_session(request, agent, session_id=session_id, cwd=cwd)
    message = await session.run_prompt(request.prompt)
    return message, session_id


def load_env_file_if_configured() -> None:
    env_file = os.environ.get("OPENCLAW_ENV_FILE", ".env")
    if env_file:
        load_env_file(env_file)


def build_provider(request: AgentRunRequest, *, model: str) -> Any:
    if request.provider == "mock":
        text = request.mock_response if request.mock_response is not None else f"mock response: {request.prompt}"
        return MockProvider(
            [
                AssistantMessage(
                    content=[{"type": "text", "text": text}],
                    provider="mock",
                    model=model,
                    stop_reason="stop",
                )
            ]
        )

    if request.provider == "openai":
        if not (request.api_key or os.environ.get("OPENAI_API_KEY")):
            raise ValueError("OPENAI_API_KEY is not set. Provide it through a Kubernetes Secret, local .env file, or provider config.")
        return OpenAIProvider(
            api_key=request.api_key,
            base_url=request.base_url,
            api_mode=normalize_api_mode(request.api_mode),
        )

    raise ValueError(f"unsupported provider: {request.provider}")


def build_policy(request: AgentRunRequest) -> ToolPolicy:
    profile = request.tool_profile
    return ToolPolicy(
        profile=profile,
        allow=normalize_name_set(request.tools_allow),
        deny=normalize_name_set(request.tools_deny) or set(),
        also_allow=normalize_name_set(request.tools_also_allow) or set(),
        readonly=profile == "readonly",
    )


def build_model_options(request: AgentRunRequest) -> dict[str, Any]:
    options: dict[str, Any] = {}
    if request.reasoning_effort:
        options["reasoning"] = {"effort": request.reasoning_effort}
    if request.max_output_tokens is not None:
        options["max_output_tokens"] = request.max_output_tokens
    return options


def build_tool_metadata(request: AgentRunRequest) -> dict[str, Any]:
    return {"shell_approval_mode": request.shell_approval}


def build_session(request: AgentRunRequest, agent: Agent, *, session_id: str, cwd: str) -> AgentSession:
    chatdata_dir = resolve_chatdata_dir(request.chatdata_dir)
    return AgentSession(
        session_id=session_id,
        agent=agent,
        store=SessionStore(resolve_session_store_path(chatdata_dir)),
        transcript=Transcript(resolve_session_transcript_path(chatdata_dir, session_id), session_id=session_id, cwd=cwd),
        cwd=cwd,
        workspace_dir=cwd,
        context_policy=SessionContextPolicy(
            compaction=CompactionSettings(
                enabled=not request.disable_compaction,
                context_window_tokens=request.context_window_tokens,
                reserve_tokens=request.reserve_tokens,
                keep_recent_tokens=request.keep_recent_tokens,
                tool_result_max_chars=request.tool_result_max_chars,
            )
        ),
    )


def normalize_name_set(values: list[str] | None) -> set[str] | None:
    if values is None:
        return None
    names = {value.strip() for value in values if value.strip()}
    return names


def normalize_api_mode(value: str) -> str:
    return value.replace("-", "_")


def assistant_text(message: AssistantMessage) -> str:
    parts: list[str] = []
    for block in message.content:
        if block.get("type") == "text":
            parts.append(str(block.get("text", "")))
    if parts:
        return "".join(parts)
    if message.error_message:
        return message.error_message
    return ""


__all__ = ["app", "run_agent_request", "AgentRunRequest", "AgentRunResponse", "HealthResponse"]
