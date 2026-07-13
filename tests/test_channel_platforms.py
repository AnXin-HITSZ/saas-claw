from __future__ import annotations

import base64
import hashlib
import json
import time
import unittest
from types import SimpleNamespace
from uuid import uuid4

from openclaw.channels.config import ChannelRuntimeConfig
from openclaw.channels.dispatcher import ChannelTurnDispatcher
from openclaw.channels.http import HttpResponse
from openclaw.channels.message.ingress_queue import IngressQueueClaim, IngressQueueRecord
from openclaw.channels.message.types import (
    ChannelMessageSendResult,
    ChannelMessageSendTextContext,
    MessageReceipt,
    PreparedInboundMessage,
)
from openclaw.channels.worker import IngressQueueWorker, raw_event_from_claim, raw_event_payload
from openclaw.llm.types import AssistantMessage, text_content
from openclaw.plugins.feishu.adapter import (
    FeishuReceiveAdapter,
    FeishuTextSendAdapter,
    FeishuWebhookError,
    build_feishu_webhook_event,
)
from openclaw.plugins.feishu.signature import build_feishu_signature
from openclaw.plugins.wechat.adapter import (
    WeChatReceiveAdapter,
    WeChatTextSendAdapter,
    build_wechat_passive_text_response,
    build_wechat_webhook_event,
    parse_wechat_payload,
)
from openclaw.plugins.wechat.signature import build_wechat_signature

try:
    from cryptography.hazmat.primitives import padding
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ModuleNotFoundError:  # pragma: no cover - depends on optional api extra.
    padding = None
    Cipher = None
    algorithms = None
    modes = None


try:
    from fastapi import HTTPException

    from openclaw.channels.api_routes import _ensure_channel_enabled, _passive_reply_text
except ModuleNotFoundError:  # pragma: no cover - depends on optional api extra.
    HTTPException = None
    _ensure_channel_enabled = None
    _passive_reply_text = None


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


def encrypt_feishu_payload(payload: dict, encrypt_key: str, *, zero_iv: bool = False, prefix_iv: bool = False) -> dict[str, str]:
    assert padding is not None
    assert Cipher is not None
    assert algorithms is not None
    assert modes is not None
    aes_key = hashlib.sha256(encrypt_key.encode("utf-8")).digest()
    padder = padding.PKCS7(algorithms.AES.block_size).padder()
    plain = json.dumps(payload).encode()
    padded = padder.update(plain) + padder.finalize()
    iv = b"0123456789abcdef" if prefix_iv else bytes(16) if zero_iv else aes_key[:16]
    encryptor = Cipher(algorithms.AES(aes_key), modes.CBC(iv)).encryptor()
    encrypted = encryptor.update(padded) + encryptor.finalize()
    encrypted_payload = iv + encrypted if prefix_iv else encrypted
    return {"encrypt": base64.b64encode(encrypted_payload).decode()}


class FakeHttpClient:
    def __init__(self) -> None:
        self.posts: list[tuple[str, dict, dict | None]] = []
        self.gets: list[str] = []

    async def post_json(self, url: str, payload: dict, *, headers: dict | None = None, timeout_seconds: float = 10) -> HttpResponse:
        self.posts.append((url, payload, headers))
        if "tenant_access_token" in url:
            return HttpResponse(200, json.dumps({"code": 0, "tenant_access_token": "tenant-token"}).encode(), {})
        if "open-apis/im" in url:
            return HttpResponse(200, json.dumps({"code": 0, "data": {"message_id": "om_1"}}).encode(), {})
        return HttpResponse(200, json.dumps({"errcode": 0, "msgid": "wm_1"}).encode(), {})

    async def get_json(self, url: str, *, headers: dict | None = None, timeout_seconds: float = 10) -> HttpResponse:
        self.gets.append(url)
        return HttpResponse(200, json.dumps({"access_token": "wechat-token"}).encode(), {})


class PlatformAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def test_wechat_webhook_xml_to_prepared_message(self) -> None:
        body = (
            "<xml><ToUserName><![CDATA[gh_1]]></ToUserName>"
            "<FromUserName><![CDATA[openid_1]]></FromUserName>"
            "<CreateTime>1710000000</CreateTime><MsgType><![CDATA[text]]></MsgType>"
            "<Content><![CDATA[hello]]></Content><MsgId>42</MsgId></xml>"
        ).encode()
        query = {
            "timestamp": "1710000000",
            "nonce": "nonce",
            "signature": build_wechat_signature("token", "1710000000", "nonce"),
        }
        config = ChannelRuntimeConfig("wechat", account_id="acct", config={"token": "token"})

        envelope = build_wechat_webhook_event(config=config, query=query, body=body)
        prepared = await WeChatReceiveAdapter().prepare(envelope.event)

        self.assertEqual(envelope.lane_key, "wechat:acct:openid_1")
        self.assertEqual(prepared.id, "42")
        self.assertEqual(prepared.conversation_id, "openid_1")
        self.assertEqual(prepared.text, "hello")

    async def test_wechat_passive_text_response_swaps_sender_and_recipient(self) -> None:
        xml = build_wechat_passive_text_response(
            inbound_payload={"FromUserName": "openid_1", "ToUserName": "gh_1"},
            text="hello <pyclaw>",
            now=1710000001,
        )
        payload = parse_wechat_payload(xml.encode())

        self.assertEqual(payload["ToUserName"], "openid_1")
        self.assertEqual(payload["FromUserName"], "gh_1")
        self.assertEqual(payload["CreateTime"], "1710000001")
        self.assertEqual(payload["MsgType"], "text")
        self.assertEqual(payload["Content"], "hello <pyclaw>")

    async def test_wechat_text_send_records_receipt(self) -> None:
        client = FakeHttpClient()
        adapter = WeChatTextSendAdapter(
            ChannelRuntimeConfig("wechat", config={"access_token": "token", "api_base_url": "https://wechat.test"}),
            http_client=client,
        )

        result = await adapter.text(ChannelMessageSendTextContext("wechat", "acct", "openid_1", "hi"))

        self.assertEqual(result.message_id, "wm_1")
        self.assertEqual(client.posts[0][1]["touser"], "openid_1")

    async def test_feishu_webhook_signature_and_prepare(self) -> None:
        payload = {
            "schema": "2.0",
            "header": {"event_id": "evt", "tenant_key": "tenant", "token": "verify-token"},
            "event": {
                "sender": {"sender_id": {"open_id": "ou_1"}},
                "message": {
                    "message_id": "om_in",
                    "chat_id": "oc_1",
                    "message_type": "text",
                    "content": json.dumps({"text": "hello"}),
                },
            },
            "tenant_key": "tenant",
        }
        body = json.dumps(payload).encode()
        signature = build_feishu_signature("1710000000", "nonce", "secret", body)
        headers = {
            "X-Lark-Request-Timestamp": "1710000000",
            "X-Lark-Request-Nonce": "nonce",
            "X-Lark-Signature": signature,
        }
        config = ChannelRuntimeConfig("feishu", config={"sign_secret": "secret", "verification_token": "verify-token"})

        envelope = build_feishu_webhook_event(config=config, headers=headers, body=body)
        prepared = await FeishuReceiveAdapter().prepare(envelope.event)  # type: ignore[arg-type]

        self.assertEqual(envelope.event.id if envelope.event else None, "evt")
        self.assertEqual(envelope.lane_key, "feishu:tenant:oc_1")
        self.assertEqual(prepared.conversation_id, "oc_1")
        self.assertEqual(prepared.sender_id, "ou_1")
        self.assertEqual(prepared.text, "hello")

    async def test_feishu_url_verification_accepts_nested_challenge(self) -> None:
        payload = {
            "schema": "2.0",
            "header": {"event_id": "evt-verify", "tenant_key": "tenant", "token": "verify-token"},
            "event": {"challenge": "challenge-code"},
        }
        config = ChannelRuntimeConfig("feishu", config={"verification_token": "verify-token"})

        envelope = build_feishu_webhook_event(config=config, headers={}, body=json.dumps(payload).encode())

        self.assertIsNone(envelope.event)
        self.assertEqual(envelope.challenge, "challenge-code")

    @unittest.skipIf(padding is None, "cryptography extra is not installed")
    async def test_feishu_encrypted_url_verification_returns_challenge(self) -> None:
        payload = {
            "schema": "2.0",
            "header": {"event_id": "evt-verify", "tenant_key": "tenant", "token": "verify-token"},
            "event": {"challenge": "encrypted-challenge"},
        }
        body = json.dumps(encrypt_feishu_payload(payload, "encrypt-key")).encode()
        config = ChannelRuntimeConfig(
            "feishu",
            config={"verification_token": "verify-token", "encrypt_key": "encrypt-key"},
        )

        envelope = build_feishu_webhook_event(config=config, headers={}, body=body)

        self.assertIsNone(envelope.event)
        self.assertEqual(envelope.challenge, "encrypted-challenge")

    @unittest.skipIf(padding is None, "cryptography extra is not installed")
    async def test_feishu_encrypted_url_verification_accepts_zero_iv(self) -> None:
        payload = {
            "schema": "2.0",
            "header": {"event_id": "evt-verify", "tenant_key": "tenant", "token": "verify-token"},
            "event": {"challenge": "zero-iv-challenge"},
        }
        body = json.dumps(encrypt_feishu_payload(payload, "encrypt-key", zero_iv=True)).encode()
        config = ChannelRuntimeConfig(
            "feishu",
            config={"verification_token": "verify-token", "encrypt_key": "encrypt-key"},
        )

        envelope = build_feishu_webhook_event(config=config, headers={}, body=body)

        self.assertIsNone(envelope.event)
        self.assertEqual(envelope.challenge, "zero-iv-challenge")

    @unittest.skipIf(padding is None, "cryptography extra is not installed")
    async def test_feishu_encrypted_url_verification_accepts_prefixed_iv(self) -> None:
        payload = {
            "schema": "2.0",
            "header": {"event_id": "evt-verify", "tenant_key": "tenant", "token": "verify-token"},
            "event": {"challenge": "prefixed-iv-challenge"},
        }
        body = json.dumps(encrypt_feishu_payload(payload, "encrypt-key", prefix_iv=True)).encode()
        config = ChannelRuntimeConfig(
            "feishu",
            config={"verification_token": "verify-token", "encrypt_key": "encrypt-key"},
        )

        envelope = build_feishu_webhook_event(config=config, headers={}, body=body)

        self.assertIsNone(envelope.event)
        self.assertEqual(envelope.challenge, "prefixed-iv-challenge")

    async def test_feishu_encrypted_payload_requires_encrypt_key(self) -> None:
        body = json.dumps({"encrypt": "ciphertext"}).encode()
        config = ChannelRuntimeConfig("feishu", config={})

        with self.assertRaisesRegex(FeishuWebhookError, "requires encrypt_key"):
            build_feishu_webhook_event(config=config, headers={}, body=body)
    @unittest.skipIf(padding is None, "cryptography extra is not installed")
    async def test_feishu_encrypted_payload_rejects_wrong_encrypt_key(self) -> None:
        payload = {
            "schema": "2.0",
            "header": {"event_id": "evt-verify", "tenant_key": "tenant", "token": "verify-token"},
            "event": {"challenge": "encrypted-challenge"},
        }
        body = json.dumps(encrypt_feishu_payload(payload, "encrypt-key")).encode()
        config = ChannelRuntimeConfig("feishu", config={"encrypt_key": "wrong-key"})

        with self.assertRaisesRegex(FeishuWebhookError, "invalid encrypted Feishu webhook payload"):
            build_feishu_webhook_event(config=config, headers={}, body=body)

    async def test_feishu_text_and_card_send(self) -> None:
        client = FakeHttpClient()
        adapter = FeishuTextSendAdapter(
            ChannelRuntimeConfig("feishu", config={"tenant_access_token": "token", "api_base_url": "https://feishu.test"}),
            http_client=client,
        )

        text = await adapter.text(ChannelMessageSendTextContext("feishu", "tenant", "oc_1", "hi"))
        card = await adapter.card(ChannelMessageSendTextContext("feishu", "tenant", "oc_1", "card"), {"config": {}})

        sent_card = json.loads(client.posts[0][1]["content"])
        self.assertEqual(text.message_id, "om_1")
        self.assertEqual(text.receipt.parts[0].payload_kind, "card")
        self.assertEqual(card.receipt.parts[0].payload_kind, "card")
        self.assertEqual(client.posts[0][1]["msg_type"], "interactive")
        self.assertEqual(sent_card["elements"][0]["text"], {"tag": "lark_md", "content": "hi"})


class WorkerDispatcherTests(unittest.IsolatedAsyncioTestCase):
    async def test_worker_claims_dispatches_sends_and_completes(self) -> None:
        queue = MemoryIngressQueue()
        message = PreparedInboundMessage(
            id="evt-1",
            channel="wechat",
            account_id="acct",
            conversation_id="openid",
            sender_id="openid",
            text="hello",
        )
        raw = parse_wechat_payload(
            b"<xml><FromUserName>openid</FromUserName><ToUserName>acct</ToUserName><Content>hello</Content><MsgId>evt-1</MsgId></xml>"
        )
        envelope = build_wechat_webhook_event(
            config=ChannelRuntimeConfig("wechat", account_id="acct", config={"token": "token"}),
            query={
                "timestamp": "1",
                "nonce": "n",
                "signature": build_wechat_signature("token", "1", "n"),
            },
            body=b"<xml><FromUserName>openid</FromUserName><ToUserName>acct</ToUserName><Content>hello</Content><MsgId>evt-1</MsgId></xml>",
        )
        self.assertEqual(raw["Content"], "hello")
        queue.enqueue("evt-feishu", "feishu", {"channel": "feishu", "text": "do not process"}, lane_key="feishu:tenant:chat")
        queue.enqueue(envelope.event.id, "wechat", raw_event_payload(envelope.event), lane_key=envelope.lane_key)
        send_adapter = RecordingSendAdapter()
        worker = IngressQueueWorker(
            queue=queue,
            receive_adapters={"wechat": WeChatReceiveAdapter()},
            dispatcher=ChannelTurnDispatcher(
                session_factory=lambda _: FakeSession("session-1", "reply"),
                send_adapters={"wechat": send_adapter},
            ),
            channel="wechat",
        )

        result = await worker.process_one()

        self.assertIsNotNone(result)
        self.assertTrue(result.completed)  # type: ignore[union-attr]
        self.assertEqual(queue.get("evt-1").status, "completed")  # type: ignore[union-attr]
        self.assertEqual(queue.get("evt-feishu").status, "pending")  # type: ignore[union-attr]
        self.assertEqual(send_adapter.sent[0].text, "reply")
        self.assertEqual(message.channel, "wechat")

    async def test_raw_event_from_claim_tolerates_missing_received_at(self) -> None:
        claim = IngressQueueClaim(
            event_id="evt-no-time",
            claim_token="token",
            owner_id="worker",
            lane_key="wechat:acct:openid",
            payload={
                "raw_event": {
                    "id": "evt-no-time",
                    "channel": "wechat",
                    "account_id": "acct",
                    "platform_payload": {"Content": "hello"},
                }
            },
        )

        event = raw_event_from_claim(claim)

        self.assertEqual(event.id, "evt-no-time")
        self.assertEqual(event.channel, "wechat")
        self.assertGreater(event.received_at, 0)

    @unittest.skipIf(_passive_reply_text is None, "fastapi extra is not installed")
    async def test_passive_reply_uses_fallback_for_cancelled_agent_run(self) -> None:
        assert _passive_reply_text is not None
        turn = SimpleNamespace(
            assistant=AssistantMessage(content=[], stop_reason="aborted", error_message="agent run was cancelled"),
            assistant_text="agent run was cancelled",
        )

        self.assertEqual(_passive_reply_text(turn, "fallback text"), "fallback text")

    @unittest.skipIf(_ensure_channel_enabled is None, "fastapi extra is not installed")
    async def test_disabled_channel_is_rejected(self) -> None:
        assert HTTPException is not None
        assert _ensure_channel_enabled is not None
        with self.assertRaises(HTTPException) as caught:
            _ensure_channel_enabled(ChannelRuntimeConfig("wechat", enabled=False))

        self.assertEqual(caught.exception.status_code, 404)


class FakeSession:
    def __init__(self, session_id: str, response: str) -> None:
        self.session_id = session_id
        self.response = response

    async def run_prompt(self, text: str) -> AssistantMessage:
        return AssistantMessage(content=text_content(self.response), provider="fake", model="fake")


class RecordingSendAdapter:
    lifecycle = None

    def __init__(self) -> None:
        self.sent: list[ChannelMessageSendTextContext] = []

    async def text(self, context: ChannelMessageSendTextContext) -> ChannelMessageSendResult:
        self.sent.append(context)
        return ChannelMessageSendResult(receipt=MessageReceipt(primary_platform_message_id="sent"))


if __name__ == "__main__":
    unittest.main()
