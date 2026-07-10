"""Gateway route context and binding models."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

PeerKind = Literal["direct", "group", "channel", "thread"]
DmScope = Literal["main", "per-peer", "per-channel-peer", "per-account-channel-peer"]


@dataclass(frozen=True)
class RoutePeer:
    kind: PeerKind
    id: str


@dataclass(frozen=True)
class RouteContext:
    channel: str
    account_id: str = "default"
    peer: RoutePeer | None = None
    parent_peer: RoutePeer | None = None
    guild_id: str | None = None
    team_id: str | None = None
    member_role_ids: list[str] = field(default_factory=list)
    sender_id: str | None = None
    sender_name: str | None = None
    text: str | None = None
    mentions: list[str] = field(default_factory=list)
    command: str | None = None


@dataclass(frozen=True)
class AgentRouteBinding:
    id: str | None
    enabled: bool
    priority: int
    agent_id: str
    agent_key: str | None = None
    channel: str | None = None
    account_id: str | None = None
    peer: RoutePeer | None = None
    parent_peer: RoutePeer | None = None
    guild_id: str | None = None
    team_id: str | None = None
    roles: list[str] = field(default_factory=list)
    sender_ids: list[str] = field(default_factory=list)
    mention_aliases: list[str] = field(default_factory=list)
    command_prefixes: list[str] = field(default_factory=list)
    dm_scope: DmScope = "per-account-channel-peer"


@dataclass(frozen=True)
class ResolvedAgentRoute:
    agent_id: str
    agent_key: str
    channel: str
    account_id: str
    session_key: str
    main_session_key: str
    dm_scope: DmScope
    last_route_policy: Literal["main", "session"]
    matched_by: str
    binding_id: str | None = None


def binding_from_mapping(value: dict[str, Any]) -> AgentRouteBinding:
    return AgentRouteBinding(
        id=_optional_str(value.get("id")),
        enabled=_bool(value.get("enabled"), True),
        priority=int(value.get("priority") or 0),
        agent_id=str(value.get("agentId") or value.get("agent_id") or ""),
        agent_key=_optional_str(value.get("agentKey") or value.get("agent_key")),
        channel=_norm(value.get("channel")),
        account_id=_optional_str(value.get("accountId") or value.get("account_id")),
        peer=_peer(value.get("peerKind") or value.get("peer_kind"), value.get("peerId") or value.get("peer_id")),
        parent_peer=_peer(
            value.get("parentPeerKind") or value.get("parent_peer_kind"),
            value.get("parentPeerId") or value.get("parent_peer_id"),
        ),
        guild_id=_optional_str(value.get("guildId") or value.get("guild_id")),
        team_id=_optional_str(value.get("teamId") or value.get("team_id")),
        roles=_list(value.get("roles")),
        sender_ids=_list(value.get("senderIds") or value.get("sender_ids")),
        mention_aliases=[_mention_alias(item) for item in _list(value.get("mentionAliases") or value.get("mention_aliases"))],
        command_prefixes=_list(value.get("commandPrefixes") or value.get("command_prefixes")),
        dm_scope=_dm_scope(value.get("dmScope") or value.get("dm_scope")),
    )


def _peer(kind: Any, peer_id: Any) -> RoutePeer | None:
    kind_text = _norm(kind)
    id_text = _optional_str(peer_id)
    if not kind_text or not id_text:
        return None
    if kind_text not in {"direct", "group", "channel", "thread"}:
        kind_text = "group"
    return RoutePeer(kind=kind_text, id=id_text)  # type: ignore[arg-type]



def _mention_alias(value: Any) -> str:
    text = _norm(value) or ""
    return text.lstrip("@")
def _dm_scope(value: Any) -> DmScope:
    text = (_optional_str(value) or "per-account-channel-peer").replace("_", "-")
    if text not in {"main", "per-peer", "per-channel-peer", "per-account-channel-peer"}:
        return "per-account-channel-peer"
    return text  # type: ignore[return-value]


def _list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    text = str(value).strip()
    if not text:
        return []
    return [item.strip() for item in text.split(",") if item.strip()]


def _bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _norm(value: Any) -> str | None:
    text = _optional_str(value)
    return text.lower().replace("_", "-") if text else None


def _optional_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
