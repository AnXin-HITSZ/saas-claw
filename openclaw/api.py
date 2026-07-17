"""FastAPI service entrypoint for pyclaw."""

from __future__ import annotations

import hmac
from dataclasses import asdict, dataclass
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
from openclaw.config import load_env_file
from openclaw.llm.openai_provider import OpenAIProvider
from openclaw.llm.provider import MockProvider
from openclaw.agents.runtime_config_client import AgentRuntimeConfig, AgentRuntimeToolPolicy, RuntimeConfigClient
from openclaw.channels.config import load_channel_agent_config
from openclaw.channels.dispatcher import build_channel_session_id
from openclaw.channels.api_routes import create_channel_router
from openclaw.channels.route_context import route_context_from_message
from openclaw.agent.events import message_end_event
from openclaw.llm.types import (
    AssistantMessage,
    ToolCallBlock,
    ToolResultMessage,
    extract_tool_calls,
    message_from_dict,
    message_to_dict,
    tool_result_content,
)
from openclaw.routing.resolve_route import resolve_agent_route
from openclaw.session.agent_session import AgentSession, SessionContextPolicy
from openclaw.session.context import CompactionSettings
from openclaw.session.ids import sanitize_session_id
from openclaw.session.paths import resolve_chatdata_dir, resolve_session_store_path, resolve_session_transcript_path
from openclaw.session.store import SessionStore
from openclaw.session.transcript import Transcript
from openclaw.tools.approval import (
    ApprovalRuntimeContext,
    PendingToolApproval,
    PendingToolApprovalError,
)
from openclaw.tools.approval_hooks import ApprovalToolHooks
from openclaw.tools.approval_store import (
    DEFAULT_TTL_SECONDS as APPROVAL_TTL_SECONDS,
    PendingApprovalStore,
    build_default_pending_approval_store,
)
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.catalog import user_visible_catalog
from openclaw.tools.executor import execute_tool_call_batch, make_base_context
from openclaw.tools.policy import ToolPolicy
from openclaw.tools.resolver import ToolResolveInput, ToolResolveResult, resolve_tools

ApiProviderName = Literal["openai", "mock"]
ApiMode = Literal["auto", "responses", "chat_completions", "chat-completions"]
ToolProfileName = Literal["minimal", "readonly", "coding", "messaging", "full"]


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
    system: str | None = None
    api_mode: ApiMode = "auto"
    reasoning_effort: Literal["low", "medium", "high"] | None = None
    max_output_tokens: int | None = Field(default=None, ge=1)
    chatdata_dir: str | None = None
    tool_profile: ToolProfileName = "coding"
    tools_allow: list[str] | None = None
    tools_deny: list[str] | None = None
    tools_also_allow: list[str] | None = None
    context_window_tokens: int = Field(default=120_000, ge=1)
    reserve_tokens: int = Field(default=16_384, ge=0)
    keep_recent_tokens: int = Field(default=20_000, ge=0)
    tool_result_max_chars: int = Field(default=20_000, ge=1)
    disable_compaction: bool = False
    mock_response: str | None = None
    # Claw context fields
    claw_id: str | None = None
    owner_user_id: str | None = None
    claw_name: str | None = None
    role_key: str | None = None
    agent_key: str | None = None
    sandbox_base_url: str | None = None


class ApprovalResponse(BaseModel):
    id: str
    tool_name: str
    risk: str
    intent: str
    arguments_preview: dict[str, Any]
    pending_state_key: str
    expires_at: str


class AgentRunResponse(BaseModel):
    status: Literal["COMPLETED", "PENDING_APPROVAL", "FAILED"] = "COMPLETED"
    session_id: str
    message: dict[str, Any] | None = None
    text: str = ""
    approval: ApprovalResponse | None = None

class ToolCatalogItem(BaseModel):
    name: str
    label: str
    description: str
    section_id: str
    execution_scope: str
    profiles: list[str]
    tags: list[str]
    risk: str
    readonly: bool
    prompt_hint: str


class ToolCatalogResponse(BaseModel):
    profiles: list[str]
    tools: list[ToolCatalogItem]


class ToolResolveRequest(BaseModel):
    profile: ToolProfileName = "coding"
    allow: list[str] | None = None
    deny: list[str] | None = None
    also_allow: list[str] | None = None
    readonly: bool = False


class ResolvedToolResponse(BaseModel):
    name: str
    label: str
    description: str
    section_id: str
    execution_scope: str
    profiles: list[str]
    tags: list[str]
    risk: str
    readonly: bool
    prompt_hint: str


class DeniedToolResponse(BaseModel):
    name: str
    reason: str


class PromptFragmentResponse(BaseModel):
    key: str
    content: str


class ToolResolveResponse(BaseModel):
    profile: str
    tools: list[ResolvedToolResponse]
    denied_tools: list[DeniedToolResponse]
    prompt_fragments: list[PromptFragmentResponse]

app = FastAPI(title="pyclaw API", version="0.1.0")




def include_routes(target_app: FastAPI, router: Any) -> None:
    target_app.include_router(router)
    route_paths = {getattr(route, "path", None) for route in target_app.routes}
    missing_routes = [route for route in getattr(router, "routes", []) if getattr(route, "path", None) not in route_paths]
    if missing_routes:
        target_app.router.routes.extend(missing_routes)


async def build_channel_agent_session(message: Any) -> AgentSession:
    load_env_file_if_configured()
    try:
        runtime_config, session_id, route_metadata = load_routed_channel_agent_config(message)
    except ValueError:
        if not env_bool("OPENCLAW_CHANNEL_LEGACY_ROUTING_FALLBACK", default=True):
            raise
        runtime_config, session_id, route_metadata = load_legacy_channel_agent_config(message)
    request = AgentRunRequest(
        prompt="channel bootstrap",
        session_id=session_id,
        provider=runtime_config.provider,  # type: ignore[arg-type]
        model=runtime_config.model,
        api_key=runtime_config.api_key,
        base_url=runtime_config.base_url,
        system=runtime_config.system,
        api_mode=runtime_config.api_mode,  # type: ignore[arg-type]
        chatdata_dir=os.environ.get("OPENCLAW_CHANNEL_CHATDATA_DIR"),
        tool_profile=runtime_config.tool_policy.profile,  # type: ignore[arg-type]
        tools_allow=runtime_config.tool_policy.allow,
        tools_deny=runtime_config.tool_policy.deny,
        tools_also_allow=runtime_config.tool_policy.also_allow,
    )
    cwd = runtime_config.workspace_dir or os.getcwd()
    model = resolve_model(request)
    provider = build_provider(request, model=model)
    policy = build_policy_from_runtime(runtime_config)
    agent = Agent(
        model=model,
        provider=provider,
        system_prompt=request.system or "",
        tools=build_tool_registry(policy),
        model_options=build_model_options(request),
        session_id=session_id,
        cwd=cwd,
        workspace_dir=cwd,
        chatdata_dir=resolve_chatdata_dir(request.chatdata_dir),
        readonly=policy.readonly,
        tool_metadata={**build_tool_metadata(request), **route_metadata},
    )
    return build_session(request, agent, session_id=session_id, cwd=cwd)


def load_routed_channel_agent_config(message: Any) -> tuple[AgentRuntimeConfig, str, dict[str, Any]]:
    context = route_context_from_message(message)
    client = RuntimeConfigClient()
    route = resolve_agent_route(
        context,
        client.load_route_bindings(),
        default_agent_key=os.environ.get("OPENCLAW_DEFAULT_AGENT_KEY", "default"),
    )
    runtime_config = client.load_agent_runtime_config(route.agent_key)
    return runtime_config, route.session_key, {
        "route_agent_id": route.agent_id,
        "route_agent_key": route.agent_key,
        "route_binding_id": route.binding_id,
        "route_matched_by": route.matched_by,
        "route_session_key": route.session_key,
        "route_main_session_key": route.main_session_key,
        "route_dm_scope": route.dm_scope,
    }


def load_legacy_channel_agent_config(message: Any) -> tuple[AgentRuntimeConfig, str, dict[str, Any]]:
    channel_agent = load_channel_agent_config()
    return (
        AgentRuntimeConfig(
            agent_id="legacy-channel",
            agent_key="legacy-channel",
            provider=channel_agent.provider,
            model=channel_agent.model,
            api_mode=channel_agent.api_mode,
            system=channel_agent.system,
            workspace_dir=os.getcwd(),
            tool_policy=AgentRuntimeToolPolicy(
                profile=channel_agent.tool_profile,
            ),
        ),
        build_channel_session_id(message),
        {"route_matched_by": "legacy-fallback"},
    )


def build_policy_from_runtime(runtime_config: AgentRuntimeConfig) -> ToolPolicy:
    tool_policy = runtime_config.tool_policy
    profile = tool_policy.profile
    return ToolPolicy(
        profile=profile,  # type: ignore[arg-type]
        allow=normalize_name_set(tool_policy.allow),
        deny=normalize_name_set(tool_policy.deny) or set(),
        also_allow=normalize_name_set(tool_policy.also_allow) or set(),
        readonly=tool_policy.readonly or profile == "readonly",
    )


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



@app.get("/v1/tools/catalog", response_model=ToolCatalogResponse)
def tools_catalog(_: None = Depends(require_api_token)) -> ToolCatalogResponse:
    tools = [
        ToolCatalogItem(
            name=entry.name,
            label=entry.label,
            description=entry.description,
            section_id=entry.section_id,
            execution_scope=entry.execution_scope,
            profiles=list(entry.profiles),
            tags=list(entry.tags),
            risk=entry.risk,
            readonly=entry.readonly,
            prompt_hint=entry.prompt_hint,
        )
        for entry in user_visible_catalog()
    ]
    return ToolCatalogResponse(profiles=["minimal", "readonly", "messaging", "coding", "full"], tools=tools)


@app.post("/v1/tools/resolve", response_model=ToolResolveResponse)
def tools_resolve(request: ToolResolveRequest, _: None = Depends(require_api_token)) -> ToolResolveResponse:
    result = resolve_tools(
        ToolResolveInput(
            profile=request.profile,
            allow=normalize_name_set(request.allow),
            deny=normalize_name_set(request.deny) or set(),
            also_allow=normalize_name_set(request.also_allow) or set(),
            readonly=request.readonly,
        )
    )
    return ToolResolveResponse(
        profile=result.profile,
        tools=[ResolvedToolResponse(**asdict(tool)) for tool in result.tools],
        denied_tools=[DeniedToolResponse(**asdict(tool)) for tool in result.denied_tools],
        prompt_fragments=[PromptFragmentResponse(**asdict(fragment)) for fragment in result.prompt_fragments],
    )

_PENDING_APPROVAL_STORE: PendingApprovalStore | None = None


def get_pending_approval_store() -> PendingApprovalStore:
    global _PENDING_APPROVAL_STORE
    if _PENDING_APPROVAL_STORE is None:
        _PENDING_APPROVAL_STORE = build_default_pending_approval_store()
    return _PENDING_APPROVAL_STORE


def _set_pending_approval_store(store: PendingApprovalStore | None) -> None:
    """Test/DI helper to inject a fake pending-approval store."""

    global _PENDING_APPROVAL_STORE
    _PENDING_APPROVAL_STORE = store


@app.post("/v1/agent/run", response_model=AgentRunResponse)
async def run_agent(
    request: AgentRunRequest,
    _: None = Depends(require_api_token),
) -> AgentRunResponse:
    try:
        result = await run_agent_request(request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return _build_agent_run_response(result)


async def run_agent_request(request: AgentRunRequest) -> "AgentRunOutcome":
    load_env_file_if_configured()
    require_sandbox_base_url(request)
    session_id = sanitize_session_id(request.session_id or uuid4().hex)
    cwd = os.getcwd()
    model = resolve_model(request)
    provider = build_provider(request, model=model)
    policy = build_policy(request)
    resolved_tools = resolve_runtime_tools(policy)

    request_context = ApprovalRuntimeContext(
        session_id=session_id,
        claw_id=request.claw_id,
        owner_user_id=request.owner_user_id,
        claw_name=request.claw_name,
        role_key=request.role_key,
        agent_key=request.agent_key,
        sandbox_base_url=request.sandbox_base_url,
        provider_name=request.provider,
        model=model,
        system_prompt=request.system,
        api_mode=request.api_mode,
        tool_profile=request.tool_profile,
        tools_allow=list(request.tools_allow) if request.tools_allow else None,
        tools_deny=list(request.tools_deny) if request.tools_deny else None,
        tools_also_allow=list(request.tools_also_allow) if request.tools_also_allow else None,
    )
    approval_hooks = ApprovalToolHooks(
        pending_store=get_pending_approval_store(),
        request_context=request_context,
        ttl_seconds=APPROVAL_TTL_SECONDS,
    )

    agent = Agent(
        model=model,
        provider=provider,
        system_prompt=compose_runtime_system_prompt(request.system, resolved_tools),
        tools=build_tool_registry(policy),
        model_options=build_model_options(request),
        session_id=session_id,
        cwd=cwd,
        workspace_dir=cwd,
        chatdata_dir=resolve_chatdata_dir(request.chatdata_dir),
        tool_hooks=approval_hooks,
        readonly=policy.readonly,
        tool_metadata=build_tool_metadata(request),
    )
    approval_hooks._messages_snapshot_provider = lambda: [message_to_dict(m) for m in agent.state.messages]
    session = build_session(request, agent, session_id=session_id, cwd=cwd)
    try:
        message = await session.run_prompt(request.prompt)
    except PendingToolApprovalError as exc:
        return AgentRunOutcome(
            status="PENDING_APPROVAL",
            session_id=session_id,
            message=None,
            approval=exc.approval,
        )
    return AgentRunOutcome(status="COMPLETED", session_id=session_id, message=message)


@dataclass
class AgentRunOutcome:
    status: Literal["COMPLETED", "PENDING_APPROVAL", "FAILED"]
    session_id: str
    message: AssistantMessage | None = None
    approval: PendingToolApproval | None = None


def _build_agent_run_response(outcome: "AgentRunOutcome") -> AgentRunResponse:
    if outcome.status == "PENDING_APPROVAL" and outcome.approval is not None:
        return AgentRunResponse(
            status="PENDING_APPROVAL",
            session_id=outcome.session_id,
            message=None,
            text="该工具调用需要你确认后继续执行。",
            approval=_approval_to_response(outcome.approval),
        )
    message = outcome.message
    if message is None:
        return AgentRunResponse(
            status="FAILED",
            session_id=outcome.session_id,
            message=None,
            text="",
        )
    return AgentRunResponse(
        status="COMPLETED",
        session_id=outcome.session_id,
        message=message_to_dict(message),
        text=assistant_text(message),
    )


def _approval_to_response(approval: PendingToolApproval) -> ApprovalResponse:
    return ApprovalResponse(
        id=approval.approval_id,
        tool_name=approval.tool_name,
        risk=approval.risk,
        intent=approval.intent_summary,
        arguments_preview=approval.arguments_preview,
        pending_state_key=approval.pending_state_key,
        expires_at=approval.expires_at,
    )


class AgentResumeRequest(BaseModel):
    approval_id: str
    decision: Literal["APPROVED", "REJECTED"]
    rejection_reason: str | None = None
    provider: ApiProviderName = "openai"
    model: str | None = None
    api_key: str | None = None
    base_url: str | None = None
    system: str | None = None
    api_mode: ApiMode = "auto"
    reasoning_effort: Literal["low", "medium", "high"] | None = None
    max_output_tokens: int | None = Field(default=None, ge=1)
    chatdata_dir: str | None = None
    tool_profile: ToolProfileName = "coding"
    tools_allow: list[str] | None = None
    tools_deny: list[str] | None = None
    tools_also_allow: list[str] | None = None
    context_window_tokens: int = Field(default=120_000, ge=1)
    reserve_tokens: int = Field(default=16_384, ge=0)
    keep_recent_tokens: int = Field(default=20_000, ge=0)
    tool_result_max_chars: int = Field(default=20_000, ge=1)
    disable_compaction: bool = False
    mock_response: str | None = None
    sandbox_base_url: str | None = None


@app.post("/v1/agent/resume", response_model=AgentRunResponse)
async def resume_agent(
    request: AgentResumeRequest,
    _: None = Depends(require_api_token),
) -> AgentRunResponse:
    try:
        outcome = await resume_agent_request(request)
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return _build_agent_run_response(outcome)


async def resume_agent_request(request: AgentResumeRequest) -> AgentRunOutcome:
    load_env_file_if_configured()
    store = get_pending_approval_store()
    state = store.load(request.approval_id)
    if state is None:
        raise LookupError(f"pending approval not found or expired: {request.approval_id}")
    try:
        session_id = str(state.get("session_id") or "").strip()
        if not session_id:
            raise ValueError("pending approval state is missing session_id")
        pending_tool_call = state.get("tool_call")
        if not isinstance(pending_tool_call, dict):
            raise ValueError("pending approval state is missing tool_call payload")

        cwd = os.getcwd()

        if not request.sandbox_base_url:
            raise ValueError("sandbox_base_url is required for Claw sandbox tool execution")

        model = first_non_blank(request.model, state.get("model"), os.environ.get("OPENAI_MODEL"))
        if model is None:
            raise ValueError("model is required. Provide it in the resume request.")
        model = str(model)

        run_request = _resume_request_to_run_request(request, session_id=session_id, model=model)

        provider = build_provider(run_request, model=model)
        policy = build_policy(run_request)
        resolved_tools = resolve_runtime_tools(policy)

        request_context = ApprovalRuntimeContext(
            session_id=session_id,
            claw_id=state.get("claw_id"),
            owner_user_id=state.get("owner_user_id"),
            claw_name=state.get("claw_name"),
            role_key=state.get("role_key"),
            agent_key=state.get("agent_key"),
            sandbox_base_url=request.sandbox_base_url,
            provider_name=run_request.provider,
            model=model,
            system_prompt=run_request.system,
            api_mode=run_request.api_mode,
            tool_profile=run_request.tool_profile,
            tools_allow=list(run_request.tools_allow) if run_request.tools_allow else None,
            tools_deny=list(run_request.tools_deny) if run_request.tools_deny else None,
            tools_also_allow=list(run_request.tools_also_allow) if run_request.tools_also_allow else None,
        )
        approval_hooks = ApprovalToolHooks(
            pending_store=store,
            request_context=request_context,
            ttl_seconds=APPROVAL_TTL_SECONDS,
        )

        agent = Agent(
            model=model,
            provider=provider,
            system_prompt=compose_runtime_system_prompt(run_request.system, resolved_tools),
            tools=build_tool_registry(policy),
            model_options=build_model_options(run_request),
            session_id=session_id,
            cwd=cwd,
            workspace_dir=cwd,
            chatdata_dir=resolve_chatdata_dir(run_request.chatdata_dir),
            tool_hooks=approval_hooks,
            readonly=policy.readonly,
            tool_metadata=build_tool_metadata(_build_tool_metadata_source(run_request, state)),
        )
        approval_hooks._messages_snapshot_provider = lambda: [message_to_dict(m) for m in agent.state.messages]

        # Restore prior conversation into agent state.
        prior_messages = [message_from_dict(m) for m in (state.get("messages") or []) if isinstance(m, dict)]
        agent.state.messages.extend(prior_messages)

        tool_call = ToolCallBlock(
            id=str(pending_tool_call.get("id") or ""),
            name=str(pending_tool_call.get("name") or ""),
            input=dict(pending_tool_call.get("input") or {}),
        )

        # Build the session BEFORE emitting/executing any tool_result so that
        # the transcript subscription is wired up first.
        session = build_session(run_request, agent, session_id=session_id, cwd=cwd)

        if request.decision == "REJECTED":
            reason = request.rejection_reason or "用户拒绝执行该工具调用"
            tool_result_message = _build_reject_tool_result_message(tool_call, reason)
            agent.state.messages.append(tool_result_message)
            agent.emit(message_end_event(tool_result_message))
        else:
            outcomes = await execute_tool_call_batch(
                [tool_call],
                agent.tools,
                make_base_context(
                    cwd=cwd,
                    workspace_dir=cwd,
                    session_id=session_id,
                    chatdata_dir=resolve_chatdata_dir(run_request.chatdata_dir),
                    model=model,
                    provider=getattr(provider, "provider_name", None),
                    emit=agent.emit,
                    readonly=policy.readonly,
                    metadata=agent.tool_metadata,
                ),
                approval_hooks,
            )
            for outcome in outcomes:
                agent.state.messages.append(outcome.message)
                agent.emit(message_end_event(outcome.message))
        try:
            message = await session.handle_post_agent_run(await agent.continue_())
        except PendingToolApprovalError as exc:
            return AgentRunOutcome(
                status="PENDING_APPROVAL",
                session_id=session_id,
                message=None,
                approval=exc.approval,
            )
        return AgentRunOutcome(status="COMPLETED", session_id=session_id, message=message)
    finally:
        store.delete(request.approval_id)


def _resume_request_to_run_request(
    request: AgentResumeRequest, *, session_id: str, model: str
) -> AgentRunRequest:
    return AgentRunRequest(
        prompt="resume",
        session_id=session_id,
        provider=request.provider,
        model=model,
        api_key=request.api_key,
        base_url=request.base_url,
        system=request.system,
        api_mode=request.api_mode,
        reasoning_effort=request.reasoning_effort,
        max_output_tokens=request.max_output_tokens,
        chatdata_dir=request.chatdata_dir,
        tool_profile=request.tool_profile,
        tools_allow=request.tools_allow,
        tools_deny=request.tools_deny,
        tools_also_allow=request.tools_also_allow,
        context_window_tokens=request.context_window_tokens,
        reserve_tokens=request.reserve_tokens,
        keep_recent_tokens=request.keep_recent_tokens,
        tool_result_max_chars=request.tool_result_max_chars,
        disable_compaction=request.disable_compaction,
        mock_response=request.mock_response,
        sandbox_base_url=request.sandbox_base_url,
    )


def _build_tool_metadata_source(run_request: AgentRunRequest, state: dict[str, Any]) -> AgentRunRequest:
    return AgentRunRequest(
        prompt=run_request.prompt,
        session_id=run_request.session_id,
        provider=run_request.provider,
        model=run_request.model,
        api_key=run_request.api_key,
        base_url=run_request.base_url,
        system=run_request.system,
        api_mode=run_request.api_mode,
        chatdata_dir=run_request.chatdata_dir,
        tool_profile=run_request.tool_profile,
        tools_allow=run_request.tools_allow,
        tools_deny=run_request.tools_deny,
        tools_also_allow=run_request.tools_also_allow,
        sandbox_base_url=run_request.sandbox_base_url,
        claw_id=state.get("claw_id"),
        owner_user_id=state.get("owner_user_id"),
        claw_name=state.get("claw_name"),
        role_key=state.get("role_key"),
        agent_key=state.get("agent_key"),
    )


def _build_reject_tool_result_message(tool_call: ToolCallBlock, reason: str) -> ToolResultMessage:
    content = tool_result_content(
        tool_call_id=tool_call.id,
        name=tool_call.name,
        output=f"用户拒绝执行该工具调用：{reason}",
        is_error=True,
        details={"status": "rejected_by_user", "reason": reason},
    )
    return ToolResultMessage(content=content)


def load_env_file_if_configured() -> None:
    env_file = os.environ.get("OPENCLAW_ENV_FILE", ".env")
    if env_file:
        load_env_file(env_file)


def env_bool(name: str, *, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}

def resolve_model(request: AgentRunRequest) -> str:
    model = first_non_blank(request.model, os.environ.get("OPENAI_MODEL"))
    if model is None:
        raise ValueError("model is required. Configure Agent/Provider model or set OPENAI_MODEL.")
    return model

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



def require_sandbox_base_url(request: AgentRunRequest) -> None:
    if not request.sandbox_base_url or not request.sandbox_base_url.strip():
        raise ValueError("sandbox_base_url is required for Claw sandbox tool execution")


def resolve_runtime_tools(policy: ToolPolicy) -> ToolResolveResult:
    return resolve_tools(
        ToolResolveInput(
            profile=policy.profile,
            allow=policy.allow,
            deny=policy.deny,
            also_allow=policy.also_allow,
            readonly=policy.readonly,
        )
    )


def compose_runtime_system_prompt(base_prompt: str | None, resolved_tools: ToolResolveResult) -> str:
    fragments = [fragment.content for fragment in resolved_tools.prompt_fragments if fragment.content]
    parts = [base_prompt.strip() if base_prompt else ""]
    parts.extend(fragments)
    return "\n\n".join(part for part in parts if part)


def first_non_blank(*values: str | None) -> str | None:
    for value in values:
        if value is not None and value.strip():
            return value.strip()
    return None

def build_model_options(request: AgentRunRequest) -> dict[str, Any]:
    options: dict[str, Any] = {}
    if request.reasoning_effort:
        options["reasoning"] = {"effort": request.reasoning_effort}
    if request.max_output_tokens is not None:
        options["max_output_tokens"] = request.max_output_tokens
    return options


def build_tool_metadata(request: AgentRunRequest) -> dict[str, Any]:
    meta: dict[str, Any] = {}
    if request.sandbox_base_url:
        meta["sandbox_base_url"] = request.sandbox_base_url
        meta["claw_id"] = request.claw_id
        meta["owner_user_id"] = request.owner_user_id
        meta["claw_name"] = request.claw_name
        meta["role_key"] = request.role_key
        meta["agent_key"] = request.agent_key
    return meta


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


__all__ = [
    "app",
    "run_agent_request",
    "resume_agent_request",
    "AgentRunRequest",
    "AgentRunResponse",
    "AgentResumeRequest",
    "AgentRunOutcome",
    "ApprovalResponse",
    "HealthResponse",
]
