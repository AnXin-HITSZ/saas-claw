"""Feishu webhook and outbound message adapter."""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import time
from dataclasses import dataclass
from typing import Any

from openclaw.channels.config import ChannelRuntimeConfig
from openclaw.channels.http import AsyncHttpClient, UrlLibHttpClient
from openclaw.channels.message.adapter import ChannelMessageReceiveAdapter, ChannelMessageSendLifecycleAdapter
from openclaw.channels.message.types import (
    ChannelMessageReceiveAckPolicy,
    ChannelMessageSendResult,
    ChannelMessageSendTextContext,
    MessageReceipt,
    MessageReceiptPart,
    PreparedInboundMessage,
    RawInboundEvent,
)
from openclaw.plugins.feishu.signature import verify_feishu_signature


@dataclass(frozen=True)
class FeishuWebhookEnvelope:
    event: RawInboundEvent | None
    lane_key: str | None = None
    challenge: str | None = None


class FeishuWebhookError(ValueError):
    pass


class FeishuReceiveAdapter(ChannelMessageReceiveAdapter):
    default_ack_policy = ChannelMessageReceiveAckPolicy.AFTER_RECEIVE_RECORD

    async def prepare(self, event: RawInboundEvent) -> PreparedInboundMessage:
        payload = dict(event.platform_payload)
        header = _feishu_header(payload)
        event_body = _feishu_event_body(payload)
        message = event_body.get("message") if isinstance(event_body.get("message"), dict) else event_body
        sender = event_body.get("sender") if isinstance(event_body.get("sender"), dict) else {}
        chat_id = str(message.get("chat_id") or payload.get("chat_id") or payload.get("open_chat_id") or event.id)
        sender_id = _sender_id(sender) or str(payload.get("sender_id") or "unknown")
        text = _feishu_text(message)
        message_id = str(message.get("message_id") or header.get("event_id") or payload.get("event_id") or payload.get("uuid") or event.id)
        return PreparedInboundMessage(
            id=message_id,
            channel="feishu",
            account_id=event.account_id or _optional_str(header.get("tenant_key") or payload.get("tenant_key")),
            conversation_id=chat_id,
            sender_id=sender_id,
            text=text,
            thread_id=_optional_str(message.get("thread_id")),
            reply_to_id=_optional_str(message.get("parent_id")),
            raw=payload,
        )


class FeishuTextSendAdapter:
    def __init__(
        self,
        config: ChannelRuntimeConfig,
        *,
        http_client: AsyncHttpClient | None = None,
        lifecycle: ChannelMessageSendLifecycleAdapter | None = None,
    ) -> None:
        self.config = config
        self.http_client = http_client or UrlLibHttpClient()
        self.lifecycle = lifecycle

    async def text(self, context: ChannelMessageSendTextContext) -> ChannelMessageSendResult:
        return await self._send_payload(
            context,
            msg_type="interactive",
            content=_feishu_markdown_card(context.text),
            payload_kind="card",
        )

    async def card(self, context: ChannelMessageSendTextContext, card: dict[str, Any]) -> ChannelMessageSendResult:
        return await self._send_payload(
            context,
            msg_type="interactive",
            content=card,
            payload_kind="card",
        )

    async def _send_payload(
        self,
        context: ChannelMessageSendTextContext,
        *,
        msg_type: str,
        content: dict[str, Any],
        payload_kind: str,
    ) -> ChannelMessageSendResult:
        token = await self._tenant_access_token()
        base_url = self.config.get_str("api_base_url", "https://open.feishu.cn") or "https://open.feishu.cn"
        url = f"{base_url.rstrip('/')}/open-apis/im/v1/messages?receive_id_type=chat_id"
        payload = {
            "receive_id": context.conversation_id,
            "msg_type": msg_type,
            "content": json.dumps(content, ensure_ascii=False),
        }
        response = await self.http_client.post_json(
            url,
            payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        data = response.json()
        code = int(data.get("code", 0))
        if response.status >= 400 or code != 0:
            raise RuntimeError(f"Feishu send failed: http={response.status} code={code} body={data}")
        message_id = _optional_str((data.get("data") or {}).get("message_id"))
        receipt = MessageReceipt(
            primary_platform_message_id=message_id,
            platform_message_ids=[message_id] if message_id else [],
            parts=[
                MessageReceiptPart(
                    platform_message_id=message_id,
                    payload_kind=payload_kind,
                    metadata={"http_status": response.status, "code": code},
                )
            ],
            thread_id=context.thread_id,
            reply_to_id=context.reply_to_id,
            metadata={"channel": "feishu", "conversation_id": context.conversation_id},
        )
        return ChannelMessageSendResult(receipt=receipt, message_id=message_id, raw=data)

    async def _tenant_access_token(self) -> str:
        configured = self.config.get_str("tenant_access_token")
        if configured:
            return configured
        app_id = self.config.require("app_id")
        app_secret = self.config.require("app_secret")
        base_url = self.config.get_str("api_base_url", "https://open.feishu.cn") or "https://open.feishu.cn"
        url = f"{base_url.rstrip('/')}/open-apis/auth/v3/tenant_access_token/internal"
        response = await self.http_client.post_json(url, {"app_id": app_id, "app_secret": app_secret})
        data = response.json()
        token = data.get("tenant_access_token")
        if response.status >= 400 or int(data.get("code", 0)) != 0 or not token:
            raise RuntimeError(f"Feishu token fetch failed: http={response.status} body={data}")
        return str(token)


def build_feishu_webhook_event(
    *,
    config: ChannelRuntimeConfig,
    headers: dict[str, str],
    body: bytes,
) -> FeishuWebhookEnvelope:
    _verify_feishu_headers(config, headers, body)
    payload = _parse_json_object(body)
    payload = _decrypt_feishu_payload(config, payload)
    header = _feishu_header(payload)
    expected_token = config.get_str("verification_token")
    actual_token = _optional_str(header.get("token") or payload.get("token"))
    if expected_token and actual_token != expected_token:
        raise FeishuWebhookError("invalid Feishu verification token")
    event_body = _feishu_event_body(payload)
    challenge = _feishu_challenge(payload, event_body)
    if challenge:
        return FeishuWebhookEnvelope(event=None, challenge=challenge)
    message = event_body.get("message") if isinstance(event_body.get("message"), dict) else event_body
    chat_id = str(message.get("chat_id") or payload.get("open_chat_id") or "unknown")
    event_id = _feishu_event_id(payload, message)
    tenant_key = _feishu_tenant_key(payload, config)
    lane_key = f"feishu:{tenant_key or 'default'}:{chat_id}"
    payload.setdefault("channel", "feishu")
    payload.setdefault("tenant_key", tenant_key)
    return FeishuWebhookEnvelope(
        event=RawInboundEvent(
            id=event_id,
            channel="feishu",
            account_id=tenant_key,
            platform_payload=payload,
            received_at=time.time(),
            ack_policy=ChannelMessageReceiveAckPolicy.AFTER_RECEIVE_RECORD,
            lane_key=lane_key,
        ),
        lane_key=lane_key,
    )


def _feishu_header(payload: dict[str, Any]) -> dict[str, Any]:
    header = payload.get("header")
    if isinstance(header, dict):
        return header
    return {}


def _feishu_event_id(payload: dict[str, Any], message: dict[str, Any]) -> str:
    header = _feishu_header(payload)
    return str(
        header.get("event_id")
        or payload.get("event_id")
        or payload.get("uuid")
        or message.get("message_id")
        or f"feishu:{time.time()}"
    )


def _feishu_tenant_key(payload: dict[str, Any], config: ChannelRuntimeConfig) -> str | None:
    header = _feishu_header(payload)
    return _optional_str(header.get("tenant_key") or payload.get("tenant_key") or payload.get("tenantKey") or config.account_id)


def _feishu_markdown_card(text: str) -> dict[str, Any]:
    return {
        "config": {"wide_screen_mode": True},
        "elements": [
            {
                "tag": "div",
                "text": {
                    "tag": "lark_md",
                    "content": text or " ",
                },
            }
        ],
    }


def _verify_feishu_headers(config: ChannelRuntimeConfig, headers: dict[str, str], body: bytes) -> None:
    secret = config.get_str("sign_secret")
    if not secret:
        return
    normalized = {key.lower(): value for key, value in headers.items()}
    timestamp = normalized.get("x-lark-request-timestamp") or normalized.get("x-feishu-request-timestamp")
    nonce = normalized.get("x-lark-request-nonce") or normalized.get("x-feishu-request-nonce")
    signature = normalized.get("x-lark-signature") or normalized.get("x-feishu-signature")
    if not timestamp or not nonce or not signature:
        raise FeishuWebhookError("missing Feishu signature headers")
    if not verify_feishu_signature(timestamp, nonce, secret, body, signature):
        raise FeishuWebhookError("invalid Feishu signature")


def _parse_json_object(body: bytes) -> dict[str, Any]:
    try:
        value = json.loads(body.decode("utf-8-sig") or "{}")
    except UnicodeDecodeError as exc:
        raise FeishuWebhookError("Feishu webhook body must be UTF-8 JSON") from exc
    except json.JSONDecodeError as exc:
        raise FeishuWebhookError("Feishu webhook body must be valid JSON") from exc
    if not isinstance(value, dict):
        raise FeishuWebhookError("Feishu webhook body must be a JSON object")
    return value




def _decrypt_feishu_payload(config: ChannelRuntimeConfig, payload: dict[str, Any]) -> dict[str, Any]:
    encrypted = _optional_str(payload.get("encrypt"))
    if not encrypted:
        return payload
    encrypt_key = config.get_str("encrypt_key")
    if not encrypt_key:
        raise FeishuWebhookError("Feishu encrypted webhook requires encrypt_key")
    try:
        from cryptography.hazmat.primitives import padding
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except ImportError as exc:  # pragma: no cover - depends on optional crypto dependency.
        raise FeishuWebhookError("cryptography is required to decrypt Feishu webhook payloads") from exc
    try:
        encrypted_bytes = base64.b64decode(encrypted)
    except binascii.Error as exc:
        raise FeishuWebhookError("invalid encrypted Feishu webhook payload") from exc

    aes_key = hashlib.sha256(encrypt_key.encode("utf-8")).digest()
    candidates: list[tuple[bytes, bytes]] = [
        (aes_key[:16], encrypted_bytes),
        (bytes(16), encrypted_bytes),
    ]
    if len(encrypted_bytes) > algorithms.AES.block_size // 8:
        candidates.append((encrypted_bytes[:16], encrypted_bytes[16:]))

    last_error: Exception | None = None
    for iv, ciphertext in candidates:
        try:
            decryptor = Cipher(algorithms.AES(aes_key), modes.CBC(iv)).decryptor()
            padded = decryptor.update(ciphertext) + decryptor.finalize()
            unpadder = padding.PKCS7(algorithms.AES.block_size).unpadder()
            plain = unpadder.update(padded) + unpadder.finalize()
            return _parse_json_object(plain)
        except (ValueError, FeishuWebhookError) as exc:
            last_error = exc
            continue
    raise FeishuWebhookError("invalid encrypted Feishu webhook payload") from last_error


def _feishu_challenge(payload: dict[str, Any], event_body: dict[str, Any]) -> str | None:
    return _optional_str(payload.get("challenge") or event_body.get("challenge"))

def _feishu_event_body(payload: dict[str, Any]) -> dict[str, Any]:
    event = payload.get("event")
    if isinstance(event, dict):
        return event
    return payload


def _feishu_text(message: dict[str, Any]) -> str:
    raw_content = message.get("content")
    if isinstance(raw_content, str):
        try:
            content = json.loads(raw_content)
        except json.JSONDecodeError:
            return raw_content
    elif isinstance(raw_content, dict):
        content = raw_content
    else:
        content = {}
    text = content.get("text")
    if text is not None:
        return str(text)
    return str(message.get("text") or f"[Feishu {message.get('message_type', 'message')}]")


def _sender_id(sender: dict[str, Any]) -> str | None:
    sender_id = sender.get("sender_id")
    if isinstance(sender_id, dict):
        return _optional_str(sender_id.get("open_id") or sender_id.get("union_id") or sender_id.get("user_id"))
    return _optional_str(sender.get("open_id") or sender.get("user_id"))


def _optional_str(value: Any) -> str | None:
    if value is None or value == "":
        return None
    return str(value)
