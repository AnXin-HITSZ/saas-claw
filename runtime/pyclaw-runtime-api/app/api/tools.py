"""Tool catalog and resolve endpoints — thin HTTP adaptation layer."""

from fastapi import APIRouter

from openclaw.tools.catalog import user_visible_catalog
from openclaw.tools.resolver import resolve_tools, ToolResolveInput

from app.schemas.tools import (
    ToolCatalogItem,
    ToolCatalogResponse,
    ToolResolveRequest,
    ToolResolveResponse,
    ResolvedTool,
    DeniedTool,
    PromptFragment,
)

router = APIRouter(prefix="/v1/tools", tags=["tools"])


@router.get("/catalog", response_model=ToolCatalogResponse)
def tool_catalog() -> ToolCatalogResponse:
    """Return the full tool catalog visible to users."""
    entries = user_visible_catalog()
    profiles = sorted({tag for e in entries for tag in (e.tags or [])})
    tools = [
        ToolCatalogItem(
            name=e.name,
            label=e.label,
            description=e.description,
            section_id=e.section_id,
            execution_scope=e.execution_scope,
            profiles=e.profiles or [],
            tags=e.tags or [],
            risk=e.risk or "",
            readonly=e.readonly,
            prompt_hint=e.prompt_hint or "",
        )
        for e in entries
    ]
    return ToolCatalogResponse(profiles=profiles, tools=tools)


@router.post("/resolve", response_model=ToolResolveResponse)
def tool_resolve(request: ToolResolveRequest) -> ToolResolveResponse:
    """Resolve tools against a policy profile."""
    result = resolve_tools(
        ToolResolveInput(
            profile=request.profile or "",
            allow=request.allow or [],
            deny=request.deny or [],
            also_allow=request.also_allow or [],
            readonly=request.readonly,
        )
    )
    return ToolResolveResponse(
        profile=result.profile,
        tools=[
            ResolvedTool(name=t.name, label=t.label, description=t.description)
            for t in result.tools
        ],
        denied_tools=[
            DeniedTool(name=d.name, reason=d.reason) for d in result.denied_tools
        ],
        prompt_fragments=[
            PromptFragment(text=f.text) for f in result.prompt_fragments
        ],
    )
