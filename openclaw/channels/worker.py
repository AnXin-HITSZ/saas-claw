"""Ingress queue worker for channel messages."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any

from openclaw.channels.dispatcher import ChannelTurnDispatcher, ChannelTurnResult
from openclaw.channels.message.adapter import ChannelMessageReceiveAdapter
from openclaw.channels.message.ingress_queue import IngressQueue, IngressQueueClaim
from openclaw.channels.message.types import ChannelMessageReceiveAckPolicy, RawInboundEvent

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class IngressWorkerResult:
    claim: IngressQueueClaim
    turn: ChannelTurnResult | None = None
    completed: bool = False
    error: str | None = None


class IngressQueueWorker:
    def __init__(
        self,
        *,
        queue: IngressQueue,
        receive_adapters: dict[str, ChannelMessageReceiveAdapter],
        dispatcher: ChannelTurnDispatcher,
        owner_id: str = "pyclaw-channel-worker",
        channel: str | None = None,
    ) -> None:
        self.queue = queue
        self.receive_adapters = dict(receive_adapters)
        self.dispatcher = dispatcher
        self.owner_id = owner_id
        self.channel = channel

    async def process_one(self) -> IngressWorkerResult | None:
        claim = self.queue.claim_next(self.owner_id, channel=self.channel)
        if claim is None:
            return None
        channel = channel_from_claim(claim)
        LOGGER.info("claimed ingress event: event_id=%s channel=%s owner_id=%s", claim.event_id, channel, self.owner_id)
        try:
            event = raw_event_from_claim(claim)
            adapter = self.receive_adapters[event.channel]
            prepared = await adapter.prepare(event)
            turn = await self.dispatcher.dispatch(prepared)
            completed = self.queue.complete(claim)
            LOGGER.info(
                "completed ingress event: event_id=%s channel=%s session_id=%s sent=%s",
                claim.event_id,
                event.channel,
                turn.session_id,
                turn.send_result is not None,
            )
            return IngressWorkerResult(claim=claim, turn=turn, completed=completed)
        except BaseException as exc:
            self.queue.fail(claim, error=str(exc))
            LOGGER.exception("failed ingress event: event_id=%s channel=%s", claim.event_id, channel)
            return IngressWorkerResult(claim=claim, error=str(exc))

    async def run_forever(self, *, idle_sleep_seconds: float = 1.0, stop: asyncio.Event | None = None) -> None:
        while stop is None or not stop.is_set():
            result = await self.process_one()
            if result is None:
                await asyncio.sleep(idle_sleep_seconds)


def channel_from_claim(claim: IngressQueueClaim) -> str:
    payload = dict(claim.payload)
    event = payload.get("raw_event")
    if isinstance(event, dict):
        return str(event.get("channel") or payload.get("channel") or "unknown")
    return str(payload.get("channel") or "unknown")


def raw_event_from_claim(claim: IngressQueueClaim) -> RawInboundEvent:
    payload: dict[str, Any] = dict(claim.payload)
    event = payload.get("raw_event")
    if isinstance(event, dict):
        raw_policy = event.get("ack_policy", ChannelMessageReceiveAckPolicy.MANUAL)
        return RawInboundEvent(
            id=str(event.get("id", claim.event_id)),
            channel=str(event.get("channel", payload.get("channel", ""))),
            account_id=_optional_str(event.get("account_id")),
            platform_payload=dict(event.get("platform_payload") or payload),
            received_at=float(event.get("received_at", 0) or time.time()),
            ack_policy=ChannelMessageReceiveAckPolicy(raw_policy),
            lane_key=claim.lane_key,
        )
    return RawInboundEvent(
        id=claim.event_id,
        channel=str(payload.get("channel", "")),
        account_id=_optional_str(payload.get("account_id")),
        platform_payload=payload,
        lane_key=claim.lane_key,
    )


def raw_event_payload(event: RawInboundEvent) -> dict[str, Any]:
    return {
        "raw_event": {
            "id": event.id,
            "channel": event.channel,
            "account_id": event.account_id,
            "platform_payload": dict(event.platform_payload),
            "received_at": event.received_at,
            "ack_policy": event.ack_policy.value,
            "lane_key": event.lane_key,
        },
        "channel": event.channel,
        "account_id": event.account_id,
    }


def _optional_str(value: Any) -> str | None:
    if value is None or value == "":
        return None
    return str(value)
