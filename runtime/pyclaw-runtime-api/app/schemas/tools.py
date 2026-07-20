"""Schemas for tool catalog and resolve endpoints."""

from __future__ import annotations

from typing import Optional

from pydantic import BaseModel


class ToolCatalogItem(BaseModel):
    """A single tool entry in the catalog."""

    name: str
    label: str = ""
    description: str = ""
    section_id: str = ""
    execution_scope: str = ""
    profiles: list[str] = []
    tags: list[str] = []
    risk: str = ""
    readonly: bool = False
    prompt_hint: str = ""


class ToolCatalogResponse(BaseModel):
    """Full tool catalog response."""

    profiles: list[str] = []
    tools: list[ToolCatalogItem] = []


class ToolResolveRequest(BaseModel):
    """Request to resolve tools against a policy profile."""

    profile: Optional[str] = None
    allow: Optional[list[str]] = None
    deny: Optional[list[str]] = None
    also_allow: Optional[list[str]] = None
    readonly: bool = False


class ResolvedTool(BaseModel):
    """A tool that passed policy resolution."""

    name: str
    label: str = ""
    description: str = ""


class DeniedTool(BaseModel):
    """A tool that was denied by policy."""

    name: str
    reason: str = ""


class PromptFragment(BaseModel):
    """A prompt fragment for the LLM system prompt."""

    text: str = ""


class ToolResolveResponse(BaseModel):
    """Full tool resolution response."""

    profile: Optional[str] = None
    tools: list[ResolvedTool] = []
    denied_tools: list[DeniedTool] = []
    prompt_fragments: list[PromptFragment] = []
