import unittest

from openclaw.routing.models import AgentRouteBinding, RouteContext, RoutePeer, binding_from_mapping
from openclaw.routing.resolve_route import resolve_agent_route


class RoutingResolverTests(unittest.TestCase):
    def context(self):
        return RouteContext(
            channel="feishu",
            account_id="default",
            peer=RoutePeer(kind="group", id="oc_group"),
            sender_id="ou_admin",
            text="/dev please inspect @ops",
            mentions=["ops"],
        )

    def test_peer_mention_wins_over_peer_command_when_priority_ties(self):
        context = self.context()
        route = resolve_agent_route(
            context,
            [
                AgentRouteBinding(
                    id="cmd",
                    enabled=True,
                    priority=10,
                    agent_id="dev-id",
                    agent_key="dev",
                    channel="feishu",
                    peer=RoutePeer(kind="group", id="oc_group"),
                    command_prefixes=["/dev"],
                ),
                AgentRouteBinding(
                    id="mention",
                    enabled=True,
                    priority=10,
                    agent_id="ops-id",
                    agent_key="ops",
                    channel="feishu",
                    peer=RoutePeer(kind="group", id="oc_group"),
                    mention_aliases=["ops"],
                ),
            ],
        )

        self.assertEqual(route.agent_key, "ops")
        self.assertEqual(route.binding_id, "mention")
        self.assertEqual(route.matched_by, "peer+mention")
        self.assertEqual(route.session_key, "agent:ops:feishu:default:group:oc_group")

    def test_priority_can_override_specificity(self):
        context = self.context()
        route = resolve_agent_route(
            context,
            [
                AgentRouteBinding(
                    id="mention",
                    enabled=True,
                    priority=10,
                    agent_id="ops-id",
                    agent_key="ops",
                    channel="feishu",
                    peer=RoutePeer(kind="group", id="oc_group"),
                    mention_aliases=["ops"],
                ),
                AgentRouteBinding(
                    id="sender",
                    enabled=True,
                    priority=20,
                    agent_id="admin-id",
                    agent_key="admin",
                    channel="feishu",
                    peer=RoutePeer(kind="group", id="oc_group"),
                    sender_ids=["ou_admin"],
                ),
            ],
        )

        self.assertEqual(route.agent_key, "admin")
        self.assertEqual(route.binding_id, "sender")
        self.assertEqual(route.matched_by, "peer+sender")

    def test_default_agent_is_used_without_matching_binding(self):
        route = resolve_agent_route(
            RouteContext(channel="wechat", account_id="gh_x", peer=RoutePeer(kind="direct", id="openid")),
            [],
            default_agent_key="default-agent",
        )

        self.assertEqual(route.agent_key, "default-agent")
        self.assertEqual(route.matched_by, "default")
        self.assertEqual(route.session_key, "agent:default-agent:wechat:gh_x:direct:openid")

    def test_binding_from_mapping_accepts_spring_response_shape(self):
        binding = binding_from_mapping(
            {
                "id": "1",
                "enabled": True,
                "priority": 100,
                "agentId": "agent-id",
                "agentKey": "ops",
                "channel": "feishu",
                "accountId": "default",
                "peerKind": "group",
                "peerId": "oc_group",
                "mentionAliases": ["Ops", "@admin"],
                "commandPrefixes": "/ops,/admin",
                "dmScope": "per-channel-peer",
            }
        )

        self.assertEqual(binding.agent_key, "ops")
        self.assertEqual(binding.peer, RoutePeer(kind="group", id="oc_group"))
        self.assertEqual(binding.mention_aliases, ["ops", "admin"])
        self.assertEqual(binding.command_prefixes, ["/ops", "/admin"])
        self.assertEqual(binding.dm_scope, "per-channel-peer")


if __name__ == "__main__":
    unittest.main()
