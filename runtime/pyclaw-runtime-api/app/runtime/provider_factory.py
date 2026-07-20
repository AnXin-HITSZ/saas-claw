"""LLM provider factory — builds provider instances from request parameters."""

from __future__ import annotations

from typing import Optional

from openclaw.llm.openai_provider import OpenAIProvider, resolve_api_mode


def build_provider(
    provider: str = "openai",
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    api_mode: Optional[str] = None,
    model: str = "gpt-4o",
    reasoning_effort: Optional[str] = None,
    max_output_tokens: Optional[int] = None,
) -> OpenAIProvider:
    """Build an LLM provider instance from request parameters.

    Currently only OpenAI-compatible providers are supported.
    """
    resolved_mode = api_mode or resolve_api_mode(base_url) if base_url else "chat_completions"

    return OpenAIProvider(
        api_key=api_key,
        base_url=base_url,
        api_mode=resolved_mode,
        model=model,
        reasoning_effort=reasoning_effort,
        max_output_tokens=max_output_tokens,
    )
