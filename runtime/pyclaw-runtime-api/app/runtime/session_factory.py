"""Session factory — creates AgentSession instances for agent runs."""

from __future__ import annotations

from typing import Optional

from openclaw.session.agent_session import AgentSession, SessionContextPolicy
from openclaw.session.store import SessionStore
from openclaw.session.transcript import Transcript
from openclaw.session.paths import (
    resolve_session_transcript_path,
    resolve_session_store_path,
)
from openclaw.agent.agent import Agent
from openclaw.session.context import CompactionSettings


def build_session(
    agent: Agent,
    session_id: str,
    chatdata_dir: Optional[str] = None,
    context_window_tokens: Optional[int] = None,
    reserve_tokens: Optional[int] = None,
    keep_recent_tokens: Optional[int] = None,
    tool_result_max_chars: Optional[int] = None,
    disable_compaction: bool = False,
) -> AgentSession:
    """Build an AgentSession that wraps the given Agent."""
    base_dir = chatdata_dir or "chatdata"
    transcript_path = resolve_session_transcript_path(base_dir, session_id)
    store_path = resolve_session_store_path(base_dir)

    transcript = Transcript(
        path=transcript_path,
        session_id=session_id,
        cwd=None,
        parent_session=None,
    )
    store = SessionStore(store_path)

    compaction = CompactionSettings(
        enabled=not disable_compaction,
        context_window_tokens=context_window_tokens or 128000,
        reserve_tokens=reserve_tokens or 16000,
        keep_recent_tokens=keep_recent_tokens or 32000,
        tool_result_max_chars=tool_result_max_chars or 80000,
    )

    context_policy = SessionContextPolicy(compaction=compaction)

    return AgentSession(
        session_id=session_id,
        agent=agent,
        store=store,
        transcript=transcript,
        context_policy=context_policy,
    )
