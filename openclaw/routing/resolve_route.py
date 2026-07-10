"""Deterministic gateway route resolver."""

from __future__ import annotations

from openclaw.routing.models import AgentRouteBinding, ResolvedAgentRoute, RouteContext
from openclaw.routing.session_key import build_agent_main_session_key, build_agent_session_key


def resolve_agent_route(
    context: RouteContext,
    bindings: list[AgentRouteBinding],
    *,
    default_agent_key: str = "default",
) -> ResolvedAgentRoute:
    candidates = [binding for binding in bindings if binding.enabled and _scope_matches(context, binding)]
    candidates.sort(key=lambda binding: (binding.priority, _specificity_score(context, binding)), reverse=True)
    if candidates:
        binding = candidates[0]
        agent_key = binding.agent_key or binding.agent_id
        dm_scope = binding.dm_scope
        return ResolvedAgentRoute(
            agent_id=binding.agent_id,
            agent_key=agent_key,
            channel=context.channel,
            account_id=context.account_id,
            session_key=build_agent_session_key(agent_key, context, dm_scope),
            main_session_key=build_agent_main_session_key(agent_key),
            dm_scope=dm_scope,
            last_route_policy="session",
            matched_by=_matched_by(context, binding),
            binding_id=binding.id,
        )
    return ResolvedAgentRoute(
        agent_id=default_agent_key,
        agent_key=default_agent_key,
        channel=context.channel,
        account_id=context.account_id,
        session_key=build_agent_session_key(default_agent_key, context, "per-account-channel-peer"),
        main_session_key=build_agent_main_session_key(default_agent_key),
        dm_scope="per-account-channel-peer",
        last_route_policy="main",
        matched_by="default",
    )


def _scope_matches(context: RouteContext, binding: AgentRouteBinding) -> bool:
    if binding.channel and binding.channel != context.channel:
        return False
    if binding.account_id and binding.account_id != context.account_id:
        return False
    if binding.peer and (context.peer is None or binding.peer.kind != context.peer.kind or binding.peer.id != context.peer.id):
        return False
    if binding.parent_peer and (
        context.parent_peer is None
        or binding.parent_peer.kind != context.parent_peer.kind
        or binding.parent_peer.id != context.parent_peer.id
    ):
        return False
    if binding.guild_id and binding.guild_id != context.guild_id:
        return False
    if binding.team_id and binding.team_id != context.team_id:
        return False
    if binding.roles and not set(binding.roles).intersection(context.member_role_ids):
        return False
    if binding.sender_ids and (context.sender_id not in binding.sender_ids):
        return False
    if binding.mention_aliases and not set(binding.mention_aliases).intersection(_normalized_mentions(context)):
        return False
    if binding.command_prefixes and not _command_matches(context.command, context.text, binding.command_prefixes):
        return False
    return True


def _specificity_score(context: RouteContext, binding: AgentRouteBinding) -> int:
    score = 0
    if binding.peer and binding.mention_aliases and set(binding.mention_aliases).intersection(_normalized_mentions(context)):
        score += 1000
    if binding.peer and binding.command_prefixes and _command_matches(context.command, context.text, binding.command_prefixes):
        score += 900
    if binding.peer and binding.sender_ids and context.sender_id in binding.sender_ids:
        score += 800
    if binding.peer:
        score += 700
    if binding.parent_peer:
        score += 600
    if binding.guild_id or binding.team_id:
        score += 500
    if binding.roles:
        score += 100
    if binding.account_id:
        score += 50
    if binding.channel:
        score += 10
    return score


def _matched_by(context: RouteContext, binding: AgentRouteBinding) -> str:
    if binding.peer and binding.mention_aliases and set(binding.mention_aliases).intersection(_normalized_mentions(context)):
        return "peer+mention"
    if binding.peer and binding.command_prefixes and _command_matches(context.command, context.text, binding.command_prefixes):
        return "peer+command"
    if binding.peer and binding.sender_ids and context.sender_id in binding.sender_ids:
        return "peer+sender"
    if binding.peer:
        return "peer"
    if binding.parent_peer:
        return "parent-peer"
    if binding.guild_id or binding.team_id or binding.roles:
        return "guild-team-roles"
    if binding.account_id:
        return "account"
    if binding.channel:
        return "channel"
    return "default-binding"


def _normalized_mentions(context: RouteContext) -> set[str]:
    return {mention.strip().lower().lstrip("@") for mention in context.mentions if mention.strip()}


def _command_matches(command: str | None, text: str | None, prefixes: list[str]) -> bool:
    values = [value.strip() for value in prefixes if value.strip()]
    if not values:
        return False
    command_text = (command or "").strip()
    text_value = (text or "").strip()
    return any(command_text == prefix or text_value.startswith(prefix) for prefix in values)
