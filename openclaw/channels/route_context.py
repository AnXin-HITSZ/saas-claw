"""Build gateway route context from prepared channel messages."""

from __future__ import annotations

import json
import re
from typing import Any

from openclaw.channels.message.types import PreparedInboundMessage
from openclaw.routing.models import RouteContext, RoutePeer

COMMAND_RE = re.compile(r"^\s*(/[A-Za-z0-9_-]+)")


def route_context_from_message(message: PreparedInboundMessage) -> RouteContext:
    raw = dict(message.raw)
    channel = message.channel.lower()
    peer_kind = _peer_kind(channel, raw)
    mentions = _mentions(channel, raw, message.text)
    command = _command(message.text)
    return RouteContext(
        channel=channel,
        account_id=message.account_id or "default",
        peer=RoutePeer(kind=peer_kind, id=message.conversation_id),
        parent_peer=RoutePeer(kind="thread", id=message.thread_id) if message.thread_id else None,
        guild_id=_optional_str(raw.get("guild_id") or raw.get("open_chat_id")),
        team_id=_optional_str(raw.get("tenant_key") or raw.get("team_id")),
        member_role_ids=_list(raw.get("member_role_ids") or raw.get("roles")),
        sender_id=message.sender_id,
        sender_name=_sender_name(channel, raw),
        text=message.text,
        mentions=mentions,
        command=command,
    )


def _peer_kind(channel: str, raw: dict[str, Any]) -> str:
    if channel == "wechat":
        return "direct"
    message = _message(raw)
    chat_type = str(message.get("chat_type") or raw.get("chat_type") or "").lower()
    if chat_type in {"p2p", "private", "direct"}:
        return "direct"
    return "group"


def _mentions(channel: str, raw: dict[str, Any], text: str) -> list[str]:
    found: list[str] = []
    message = _message(raw)
    mentions = message.get("mentions")
    if isinstance(mentions, list):
        for item in mentions:
            if isinstance(item, dict):
                name = item.get("name") or item.get("key") or item.get("id")
                if name:
                    found.append(str(name).lstrip("@"))
    found.extend(match.lstrip("@") for match in re.findall(r"@([A-Za-z0-9_\-\u4e00-\u9fff]+)", text or ""))
    return list(dict.fromkeys(item.strip().lower() for item in found if item.strip()))


def _command(text: str) -> str | None:
    match = COMMAND_RE.match(text or "")
    return match.group(1) if match else None


def _message(raw: dict[str, Any]) -> dict[str, Any]:
    event = raw.get("event")
    if isinstance(event, dict) and isinstance(event.get("message"), dict):
        return dict(event["message"])
    if isinstance(raw.get("message"), dict):
        return dict(raw["message"])
    return raw


def _sender_name(channel: str, raw: dict[str, Any]) -> str | None:
    event = raw.get("event")
    sender = event.get("sender") if isinstance(event, dict) else raw.get("sender")
    if isinstance(sender, dict):
        return _optional_str(sender.get("sender_name") or sender.get("name"))
    return None


def _list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip().startswith("["):
        try:
            parsed = json.loads(value)
            if isinstance(parsed, list):
                return [str(item).strip() for item in parsed if str(item).strip()]
        except json.JSONDecodeError:
            pass
    return [item.strip() for item in str(value).split(",") if item.strip()]


def _optional_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
