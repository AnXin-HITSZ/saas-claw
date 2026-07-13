from __future__ import annotations

import asyncio
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from openclaw.channels import (
    ChannelCapabilities,
    ChannelMeta,
    ChannelPlugin,
    ChannelRegistry,
    ChannelRuntimeConfig,
    ChannelTurnDispatcher,
    HttpResponse,
    IngressQueueWorker,
    build_channel_session_id,
    load_channel_agent_config,
    load_channel_config,
)
from openclaw.channels.message.ingress_queue import (
    IngressQueueClaim,
    IngressQueueRecord,
    MySQLIngressQueue,
    create_ingress_queue_from_env,
    parse_mysql_dsn,
    validate_mysql_identifier,
)
from openclaw.channels.message.receive import create_message_receive_context
from openclaw.channels.message.types import (
    ChannelMessageReceiveAckPolicy,
    ChannelMessageReceiveStage,
    ChannelMessageSendResult,
    ChannelMessageSendTextContext,
    MessageReceipt,
    PreparedInboundMessage,
    RawInboundEvent,
)
from openclaw.llm.types import AssistantMessage
from openclaw.plugins.feishu import FeishuReceiveAdapter, FeishuTextSendAdapter, SequentialQueue, build_feishu_webhook_event
from openclaw.plugins.wechat import (
    WeChatReceiveAdapter,
    WeChatTextSendAdapter,
    build_wechat_signature,
    build_wechat_webhook_event,
    verify_wechat_signature,
)
from openclaw.state import JsonPluginStateStore

class MemoryIngressQueue:
    def __init__(self, *, stale_after_seconds: float = 300) -> None:
        self.stale_after_seconds = stale_after_seconds
        self.records: dict[str, IngressQueueRecord] = {}
        self.claims: dict[str, tuple[str, str, float]] = {}

    def enqueue(self, event_id: str, channel: str, payload: dict, *, lane_key: str | None = None) -> bool:
        if event_id in self.records:
            return False
        self.records[event_id] = IngressQueueRecord(
            event_id=event_id,
            channel=channel,
            payload=dict(payload),
            lane_key=lane_key,
        )
        return True

    def claim_next(self, owner_id: str, *, blocked_lane_keys=(), channel: str | None = None) -> IngressQueueClaim | None:
        now = time.time()
        self._release_stale(now)
        blocked = set(blocked_lane_keys)
        for record in list(self.records.values()):
            if record.status != "pending":
                continue
            if channel and record.channel != channel:
                continue
            if record.lane_key and record.lane_key in blocked:
                continue
            if record.lane_key and self._lane_has_active_claim(record.lane_key):
                continue
            token = uuid4().hex
            self.records[record.event_id] = IngressQueueRecord(
                event_id=record.event_id,
                channel=record.channel,
                payload=record.payload,
                lane_key=record.lane_key,
                status="claimed",
                attempts=record.attempts + 1,
            )
            self.claims[record.event_id] = (token, owner_id, now)
            return IngressQueueClaim(record.event_id, token, owner_id, record.lane_key, record.payload)
        return None

    def complete(self, claim: IngressQueueClaim) -> bool:
        return self._transition(claim, "completed")

    def fail(self, claim: IngressQueueClaim, *, error: str | None = None) -> bool:
        return self._transition(claim, "failed")

    def release(self, claim: IngressQueueClaim) -> bool:
        return self._transition(claim, "pending", clear_claim=True)

    def get(self, event_id: str) -> IngressQueueRecord | None:
        return self.records.get(event_id)

    def _transition(self, claim: IngressQueueClaim, status: str, *, clear_claim: bool = False) -> bool:
        stored = self.claims.get(claim.event_id)
        record = self.records.get(claim.event_id)
        if stored is None or record is None or stored[0] != claim.claim_token or record.status != "claimed":
            return False
        self.records[claim.event_id] = IngressQueueRecord(
            event_id=record.event_id,
            channel=record.channel,
            payload=record.payload,
            lane_key=record.lane_key,
            status=status,
            attempts=record.attempts,
        )
        if clear_claim or status in {"completed", "failed"}:
            self.claims.pop(claim.event_id, None)
        return True

    def _release_stale(self, now: float) -> None:
        for event_id, (_, _, claimed_at) in list(self.claims.items()):
            record = self.records.get(event_id)
            if record and record.status == "claimed" and claimed_at < now - self.stale_after_seconds:
                self.records[event_id] = IngressQueueRecord(
                    event_id=record.event_id,
                    channel=record.channel,
                    payload=record.payload,
                    lane_key=record.lane_key,
                    status="pending",
                    attempts=record.attempts,
                )
                self.claims.pop(event_id, None)

    def _lane_has_active_claim(self, lane_key: str) -> bool:
        return any(record.lane_key == lane_key and record.status == "claimed" for record in self.records.values())


class _FakeUrlopenResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class ChannelConfigLoadingTests(unittest.TestCase):
    def test_load_channel_config_from_spring_backend(self) -> None:
        payload = {
            "channel": "wechat",
            "accountId": "gh_demo",
            "name": "demo-wechat",
            "enabled": True,
            "config": {
                "token": "wechat-token",
                "appId": "wx_app",
            },
        }

        with patch.dict(
            os.environ,
            {
                "OPENCLAW_CHANNEL_CONFIG_SOURCE": "spring",
                "OPENCLAW_SPRING_BACKEND_BASE_URL": "http://spring:8080",
                "PYCLAW_API_TOKEN": "internal-token",
            },
            clear=True,
        ), patch("urllib.request.urlopen", return_value=_FakeUrlopenResponse(payload)) as urlopen:
            config = load_channel_config("wechat", account_id="gh_demo")

        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "http://spring:8080/api/internal/channels/wechat/runtime-config?accountId=gh_demo")
        self.assertEqual(request.headers["Authorization"], "Bearer internal-token")
        self.assertTrue(config.enabled)
        self.assertEqual(config.account_id, "gh_demo")
        self.assertEqual(config.name, "demo-wechat")
        self.assertEqual(config.require("token"), "wechat-token")
        self.assertEqual(config.get_str("app_id"), "wx_app")

    def test_load_channel_config_from_spring_backend_disabled(self) -> None:
        payload = {"channel": "feishu", "enabled": False, "config": {}}
        with patch.dict(
            os.environ,
            {
                "OPENCLAW_CHANNEL_CONFIG_SOURCE": "spring",
                "OPENCLAW_SPRING_BACKEND_BASE_URL": "http://spring:8080",
                "PYCLAW_API_TOKEN": "internal-token",
            },
            clear=True,
        ), patch("urllib.request.urlopen", return_value=_FakeUrlopenResponse(payload)):
            config = load_channel_config("feishu")

        self.assertFalse(config.enabled)
        self.assertEqual(config.config["enabled"], False)

class ChannelRegistryTests(unittest.TestCase):
    def test_register_and_get_plugin(self) -> None:
        registry = ChannelRegistry()
        plugin = ChannelPlugin(
            id="wechat",
            meta=ChannelMeta(name="WeChat"),
            capabilities=ChannelCapabilities(inbound=True, outbound_text=True),
        )

        registry.register(plugin)

        self.assertIs(registry.get("wechat"), plugin)
        with self.assertRaises(ValueError):
            registry.register(plugin)


class MessageReceiveContextTests(unittest.IsolatedAsyncioTestCase):
    async def test_ack_after_policy_stage_is_idempotent(self) -> None:
        calls: list[str] = []
        event = RawInboundEvent(
            id="evt-1",
            channel="wechat",
            account_id="acct-1",
            platform_payload={"text": "hello"},
            ack_policy=ChannelMessageReceiveAckPolicy.AFTER_AGENT_DISPATCH,
        )
        context = create_message_receive_context(event, ack=lambda _: calls.append("ack"))

        self.assertFalse(await context.ack_after_stage(ChannelMessageReceiveStage.RECEIVE_RECORD))
        self.assertTrue(await context.ack_after_stage(ChannelMessageReceiveStage.AGENT_DISPATCH))
        self.assertFalse(await context.ack())
        self.assertEqual(calls, ["ack"])

    async def test_nack_prevents_later_ack(self) -> None:
        calls: list[str] = []
        event = RawInboundEvent(
            id="evt-1",
            channel="wechat",
            account_id=None,
            platform_payload={},
        )
        context = create_message_receive_context(
            event,
            ack=lambda _: calls.append("ack"),
            nack=lambda _, __: calls.append("nack"),
        )

        self.assertTrue(await context.nack(RuntimeError("boom")))
        self.assertFalse(await context.ack())
        self.assertEqual(calls, ["nack"])


class IngressQueueTests(unittest.TestCase):
    def test_enqueue_claim_complete_and_duplicate_detection(self) -> None:
        queue = MemoryIngressQueue()

        self.assertTrue(queue.enqueue("evt-1", "wechat", {"text": "hello"}, lane_key="chat-1"))
        self.assertFalse(queue.enqueue("evt-1", "wechat", {"text": "hello"}, lane_key="chat-1"))

        claim = queue.claim_next("worker-1")
        self.assertIsNotNone(claim)
        assert claim is not None
        self.assertEqual(claim.payload["text"], "hello")

        self.assertIsNone(queue.claim_next("worker-2"))
        self.assertTrue(queue.complete(claim))
        self.assertFalse(queue.complete(claim))
        self.assertEqual(queue.get("evt-1").status, "completed")  # type: ignore[union-attr]

    def test_lane_is_blocked_while_claimed(self) -> None:
        queue = MemoryIngressQueue()
        queue.enqueue("evt-1", "wechat", {"n": 1}, lane_key="same-chat")
        queue.enqueue("evt-2", "wechat", {"n": 2}, lane_key="same-chat")
        queue.enqueue("evt-3", "wechat", {"n": 3}, lane_key="other-chat")

        first = queue.claim_next("worker-1")
        second = queue.claim_next("worker-2")

        self.assertEqual(first.event_id if first else None, "evt-1")
        self.assertEqual(second.event_id if second else None, "evt-3")

    def test_stale_claim_is_released(self) -> None:
        queue = MemoryIngressQueue(stale_after_seconds=0.01)
        queue.enqueue("evt-1", "wechat", {})
        first = queue.claim_next("worker-1")
        self.assertIsNotNone(first)
        time.sleep(0.02)

        second = queue.claim_next("worker-2")

        self.assertIsNotNone(second)
        self.assertEqual(second.owner_id if second else None, "worker-2")

    def test_claim_next_can_filter_by_channel(self) -> None:
        queue = MemoryIngressQueue()
        queue.enqueue("evt-feishu", "feishu", {"text": "first"})
        queue.enqueue("evt-wechat", "wechat", {"text": "second"})

        claim = queue.claim_next("worker", channel="wechat")

        self.assertIsNotNone(claim)
        self.assertEqual(claim.event_id if claim else None, "evt-wechat")
        self.assertEqual(queue.get("evt-feishu").status, "pending")  # type: ignore[union-attr]

    def test_mysql_dsn_parse_and_identifier_validation(self) -> None:
        config = parse_mysql_dsn(
            "mysql+pymysql://pyclaw:secret@example.internal:3307/pyclaw_channels?charset=utf8mb4&table=channel_ingress"
        )

        self.assertEqual(config.host, "example.internal")
        self.assertEqual(config.port, 3307)
        self.assertEqual(config.user, "pyclaw")
        self.assertEqual(config.password, "secret")
        self.assertEqual(config.database, "pyclaw_channels")
        self.assertEqual(config.table_name, "channel_ingress")
        self.assertEqual(validate_mysql_identifier("ingress_queue_2"), "ingress_queue_2")
        with self.assertRaises(ValueError):
            validate_mysql_identifier("ingress_queue;drop")
        with self.assertRaises(ValueError):
            parse_mysql_dsn("mysql+pymysql://u:p@example.internal/db?charset=utf8mb4;drop")

    def test_env_factory_requires_mysql_dsn(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                create_ingress_queue_from_env()

    def test_env_factory_creates_mysql_queue_without_schema_for_tests(self) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENCLAW_INGRESS_QUEUE_DSN": "mysql+pymysql://pyclaw:secret@example.internal:3306/pyclaw",
                "OPENCLAW_INGRESS_QUEUE_STALE_AFTER_SECONDS": "123",
            },
            clear=True,
        ):
            queue = create_ingress_queue_from_env(init_schema=False)

        self.assertIsInstance(queue, MySQLIngressQueue)
        self.assertEqual(queue.stale_after_seconds, 123)


class PluginStateAndWeChatSignatureTests(unittest.IsolatedAsyncioTestCase):
    async def test_json_store_register_lookup_delete(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = JsonPluginStateStore[dict[str, str]](Path(temp_dir) / "state.json")

            await store.register("key", {"value": "ok"})

            self.assertEqual(await store.lookup("key"), {"value": "ok"})
            self.assertTrue(await store.delete("key"))
            self.assertIsNone(await store.lookup("key"))

    async def test_wechat_signature_verification(self) -> None:
        token = "token"
        timestamp = "1710000000"
        nonce = "nonce"
        signature = build_wechat_signature(token, timestamp, nonce)

        self.assertTrue(verify_wechat_signature(token, timestamp, nonce, signature))
        self.assertFalse(verify_wechat_signature(token, timestamp, nonce, "bad-signature"))

class PlatformAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def test_wechat_webhook_xml_prepare_and_send_text(self) -> None:
        token = "wechat-token"
        timestamp = "1710000000"
        nonce = "nonce"
        signature = build_wechat_signature(token, timestamp, nonce)
        config = ChannelRuntimeConfig(
            channel="wechat",
            account_id="acct",
            config={"token": token, "access_token": "access-token", "api_base_url": "https://wechat.test"},
        )
        body = b"""
<xml>
  <ToUserName>gh_account</ToUserName>
  <FromUserName>openid-1</FromUserName>
  <CreateTime>1710000001</CreateTime>
  <MsgType>text</MsgType>
  <Content>Hello</Content>
  <MsgId>msg-1</MsgId>
</xml>
"""

        envelope = build_wechat_webhook_event(
            config=config,
            query={"signature": signature, "timestamp": timestamp, "nonce": nonce},
            body=body,
            content_type="application/xml",
        )
        prepared = await WeChatReceiveAdapter().prepare(envelope.event)

        self.assertEqual(envelope.lane_key, "wechat:acct:openid-1")
        self.assertEqual(prepared.conversation_id, "openid-1")
        self.assertEqual(prepared.text, "Hello")

        class FakeHttp:
            async def post_json(self, url, payload, *, headers=None, timeout_seconds=10):
                self.url = url
                self.payload = payload
                return HttpResponse(200, b'{"errcode": 0, "msgid": "platform-msg-1"}', {})

        fake_http = FakeHttp()
        result = await WeChatTextSendAdapter(config, http_client=fake_http).text(
            ChannelMessageSendTextContext(
                channel="wechat",
                account_id="acct",
                conversation_id="openid-1",
                text="reply",
            )
        )

        self.assertIn("access_token=access-token", fake_http.url)
        self.assertEqual(fake_http.payload["text"]["content"], "reply")
        self.assertEqual(result.message_id, "platform-msg-1")

    async def test_feishu_webhook_prepare_and_send_text(self) -> None:
        config = ChannelRuntimeConfig(
            channel="feishu",
            account_id="tenant-1",
            config={"tenant_access_token": "tenant-token", "api_base_url": "https://feishu.test"},
        )
        body = json.dumps(
            {
                "event_id": "evt-feishu-1",
                "tenant_key": "tenant-1",
                "event": {
                    "sender": {"sender_id": {"open_id": "ou_1"}},
                    "message": {
                        "message_id": "om_1",
                        "chat_id": "oc_1",
                        "message_type": "text",
                        "content": json.dumps({"text": "Hello Feishu"}),
                    },
                },
            }
        ).encode("utf-8")

        envelope = build_feishu_webhook_event(config=config, headers={}, body=body)
        assert envelope.event is not None
        prepared = await FeishuReceiveAdapter().prepare(envelope.event)

        self.assertEqual(envelope.lane_key, "feishu:tenant-1:oc_1")
        self.assertEqual(prepared.conversation_id, "oc_1")
        self.assertEqual(prepared.sender_id, "ou_1")
        self.assertEqual(prepared.text, "Hello Feishu")

        class FakeHttp:
            async def post_json(self, url, payload, *, headers=None, timeout_seconds=10):
                self.url = url
                self.payload = payload
                self.headers = headers or {}
                return HttpResponse(200, b'{"code": 0, "data": {"message_id": "om_reply"}}', {})

        fake_http = FakeHttp()
        result = await FeishuTextSendAdapter(config, http_client=fake_http).text(
            ChannelMessageSendTextContext(
                channel="feishu",
                account_id="tenant-1",
                conversation_id="oc_1",
                text="reply",
            )
        )

        sent_card = json.loads(fake_http.payload["content"])
        self.assertEqual(fake_http.headers["Authorization"], "Bearer tenant-token")
        self.assertEqual(fake_http.payload["receive_id"], "oc_1")
        self.assertEqual(fake_http.payload["msg_type"], "interactive")
        self.assertEqual(sent_card["elements"][0]["text"], {"tag": "lark_md", "content": "reply"})
        self.assertEqual(result.message_id, "om_reply")

class SequentialQueueTests(unittest.IsolatedAsyncioTestCase):
    async def test_same_key_runs_sequentially_but_different_key_can_overlap(self) -> None:
        queue = SequentialQueue()
        events: list[str] = []

        async def task(name: str, delay: float) -> str:
            events.append(f"start:{name}")
            await asyncio.sleep(delay)
            events.append(f"end:{name}")
            return name

        results = await asyncio.gather(
            queue.run("same", lambda: task("a", 0.02)),
            queue.run("same", lambda: task("b", 0)),
            queue.run("other", lambda: task("c", 0)),
        )

        self.assertEqual(results, ["a", "b", "c"])
        self.assertLess(events.index("end:a"), events.index("start:b"))
        self.assertLess(events.index("start:c"), events.index("end:a"))

class ChannelRuntimeIntegrationTests(unittest.IsolatedAsyncioTestCase):
    def test_load_channel_config_from_env(self) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENCLAW_WECHAT_TOKEN": "wechat-token",
                "OPENCLAW_WECHAT_APP_ID": "wx-app",
                "OPENCLAW_WECHAT_ENABLED": "true",
            },
            clear=True,
        ):
            config = load_channel_config("wechat")

        self.assertEqual(config.channel, "wechat")
        self.assertTrue(config.enabled)
        self.assertEqual(config.require("token"), "wechat-token")
        self.assertEqual(config.get_str("app_id"), "wx-app")

    def test_load_channel_agent_config_from_env(self) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENCLAW_CHANNEL_PROVIDER": "mock",
                "OPENCLAW_CHANNEL_MODEL": "channel-model",
                "OPENCLAW_CHANNEL_TOOL_PROFILE": "minimal",
                "OPENCLAW_CHANNEL_WEBHOOK_SYNC": "true",
            },
            clear=True,
        ):
            config = load_channel_agent_config()

        self.assertEqual(config.provider, "mock")
        self.assertEqual(config.model, "channel-model")
        self.assertEqual(config.tool_profile, "minimal")
        self.assertTrue(config.webhook_sync)

    async def test_dispatcher_builds_session_id_and_sends_reply(self) -> None:
        sent: list[ChannelMessageSendTextContext] = []
        message = PreparedInboundMessage(
            id="evt-dispatch",
            channel="wechat",
            account_id="acct",
            conversation_id="chat-1",
            sender_id="user-1",
            text="hello",
        )

        class FakeSession:
            session_id = build_channel_session_id(message)

            async def run_prompt(self, text: str) -> AssistantMessage:
                self.prompt = text
                return AssistantMessage(content=[{"type": "text", "text": f"reply:{text}"}])

        class FakeSendAdapter:
            lifecycle = None

            async def text(self, context: ChannelMessageSendTextContext) -> ChannelMessageSendResult:
                sent.append(context)
                return ChannelMessageSendResult(receipt=MessageReceipt(primary_platform_message_id="msg-1"))

        dispatcher = ChannelTurnDispatcher(
            session_factory=lambda _: FakeSession(),
            send_adapters={"wechat": FakeSendAdapter()},
        )

        result = await dispatcher.dispatch(message)

        self.assertEqual(result.session_id, "channel-wechat-acct-chat-1")
        self.assertEqual(result.assistant_text, "reply:hello")
        self.assertEqual(sent[0].conversation_id, "chat-1")
        self.assertEqual(sent[0].text, "reply:hello")
        self.assertEqual(result.send_result.message_id, None)  # type: ignore[union-attr]

    async def test_ingress_worker_claims_prepares_dispatches_and_completes(self) -> None:
        queue = MemoryIngressQueue()
        event = RawInboundEvent(
            id="evt-worker",
            channel="wechat",
            account_id="acct",
            platform_payload={"text": "hello", "conversation_id": "chat-1", "sender_id": "user-1"},
            lane_key="wechat:acct:chat-1",
        )
        self.assertTrue(
            queue.enqueue(
                event.id,
                event.channel,
                {
                    "raw_event": {
                        "id": event.id,
                        "channel": event.channel,
                        "account_id": event.account_id,
                        "platform_payload": dict(event.platform_payload),
                        "received_at": event.received_at,
                        "ack_policy": event.ack_policy.value,
                    }
                },
                lane_key=event.lane_key,
            )
        )

        class FakeReceiveAdapter:
            default_ack_policy = ChannelMessageReceiveAckPolicy.MANUAL

            async def prepare(self, raw: RawInboundEvent) -> PreparedInboundMessage:
                return PreparedInboundMessage(
                    id=raw.id,
                    channel=raw.channel,
                    account_id=raw.account_id,
                    conversation_id=str(raw.platform_payload["conversation_id"]),
                    sender_id=str(raw.platform_payload["sender_id"]),
                    text=str(raw.platform_payload["text"]),
                    raw=raw.platform_payload,
                )

        class FakeSession:
            session_id = "channel-wechat-acct-chat-1"

            async def run_prompt(self, text: str) -> AssistantMessage:
                return AssistantMessage(content=[{"type": "text", "text": text.upper()}])

        dispatcher = ChannelTurnDispatcher(session_factory=lambda _: FakeSession())
        worker = IngressQueueWorker(
            queue=queue,
            receive_adapters={"wechat": FakeReceiveAdapter()},
            dispatcher=dispatcher,
        )

        result = await worker.process_one()

        self.assertIsNotNone(result)
        self.assertTrue(result.completed if result else False)
        self.assertEqual(queue.get("evt-worker").status, "completed")  # type: ignore[union-attr]
        self.assertEqual(result.turn.assistant_text if result and result.turn else None, "HELLO")


if __name__ == "__main__":
    unittest.main()
