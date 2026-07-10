"""Gateway routing primitives."""

from openclaw.routing.models import AgentRouteBinding, ResolvedAgentRoute, RouteContext, RoutePeer
from openclaw.routing.resolve_route import resolve_agent_route

__all__ = ["AgentRouteBinding", "ResolvedAgentRoute", "RouteContext", "RoutePeer", "resolve_agent_route"]
