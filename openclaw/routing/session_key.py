"""Session key helpers for routed channel agents."""

from __future__ import annotations

from openclaw.routing.models import DmScope, RouteContext


def build_agent_main_session_key(agent_key: str) -> str:
    return f"agent:{safe_part(agent_key)}:main"


def build_agent_session_key(agent_key: str, context: RouteContext, dm_scope: DmScope) -> str:
    peer = context.peer
    if dm_scope == "main" or peer is None:
        return build_agent_main_session_key(agent_key)
    channel = safe_part(context.channel)
    account = safe_part(context.account_id or "default")
    kind = safe_part(peer.kind)
    peer_id = safe_part(peer.id)
    if dm_scope == "per-peer":
        return f"agent:{safe_part(agent_key)}:{kind}:{peer_id}"
    if dm_scope == "per-channel-peer":
        return f"agent:{safe_part(agent_key)}:{channel}:{kind}:{peer_id}"
    return f"agent:{safe_part(agent_key)}:{channel}:{account}:{kind}:{peer_id}"


def safe_part(value: str) -> str:
    cleaned = "".join(char if char.isalnum() or char in {"-", "_"} else "-" for char in str(value).strip())
    return cleaned.strip("-_") or "default"
