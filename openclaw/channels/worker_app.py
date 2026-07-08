"""Standalone channel ingress queue worker process."""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
from collections.abc import Iterable
from typing import Any

from openclaw.api import build_channel_agent_session, load_env_file_if_configured
from openclaw.channels.config import ChannelRuntimeConfig, load_channel_config
from openclaw.channels.dispatcher import ChannelTurnDispatcher
from openclaw.channels.message.adapter import ChannelMessageReceiveAdapter, ChannelMessageSendAdapter
from openclaw.channels.message.ingress_queue import create_ingress_queue_from_env
from openclaw.channels.worker import IngressQueueWorker
from openclaw.plugins.feishu.adapter import FeishuReceiveAdapter, FeishuTextSendAdapter
from openclaw.plugins.wechat.adapter import WeChatReceiveAdapter, WeChatTextSendAdapter

LOGGER = logging.getLogger("openclaw.channels.worker_app")
DEFAULT_CHANNELS = ("wechat", "feishu")


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    configure_logging(args.log_level)
    asyncio.run(run(args))


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run pyclaw channel ingress queue workers.")
    parser.add_argument(
        "--channels",
        default=os.environ.get("OPENCLAW_CHANNEL_WORKER_CHANNELS", ",".join(DEFAULT_CHANNELS)),
        help="Comma-separated channel names to consume. Defaults to wechat,feishu.",
    )
    parser.add_argument(
        "--poll-interval-seconds",
        type=float,
        default=float(os.environ.get("OPENCLAW_CHANNEL_WORKER_POLL_INTERVAL_SECONDS", "1")),
        help="Sleep time when no pending message is available.",
    )
    parser.add_argument(
        "--owner-id",
        default=os.environ.get("OPENCLAW_CHANNEL_WORKER_OWNER_ID", "pyclaw-channel-worker"),
        help="Worker owner id stored in ingress queue claims.",
    )
    parser.add_argument(
        "--log-level",
        default=os.environ.get("OPENCLAW_CHANNEL_WORKER_LOG_LEVEL", "INFO"),
        help="Python logging level.",
    )
    return parser.parse_args(argv)


async def run(args: argparse.Namespace) -> None:
    if args.poll_interval_seconds < 0:
        raise ValueError("--poll-interval-seconds must be greater than or equal to 0")

    load_env_file_if_configured()
    queue = create_ingress_queue_from_env()
    workers = build_workers(parse_channels(args.channels), queue=queue, owner_id=args.owner_id)
    if not workers:
        raise RuntimeError("no enabled channel workers were configured")

    stop = asyncio.Event()
    install_signal_handlers(stop)
    LOGGER.info(
        "starting channel workers: channels=%s poll_interval_seconds=%s owner_id=%s",
        ",".join(worker.channel or "all" for worker in workers),
        args.poll_interval_seconds,
        args.owner_id,
    )
    tasks = [
        asyncio.create_task(worker.run_forever(idle_sleep_seconds=args.poll_interval_seconds, stop=stop))
        for worker in workers
    ]
    try:
        await asyncio.gather(*tasks)
    finally:
        stop.set()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        LOGGER.info("channel workers stopped")


def build_workers(channels: Iterable[str], *, queue: Any, owner_id: str) -> list[IngressQueueWorker]:
    workers: list[IngressQueueWorker] = []
    for channel in channels:
        config = load_channel_config(channel)
        if not config.enabled:
            LOGGER.info("skip disabled channel worker: channel=%s", channel)
            continue
        receive_adapter, send_adapter = build_channel_adapters(channel, config)
        workers.append(
            IngressQueueWorker(
                queue=queue,
                receive_adapters={channel: receive_adapter},
                dispatcher=ChannelTurnDispatcher(
                    session_factory=build_channel_agent_session,
                    send_adapters={channel: send_adapter},
                ),
                owner_id=f"{owner_id}-{channel}",
                channel=channel,
            )
        )
    return workers


def build_channel_adapters(
    channel: str,
    config: ChannelRuntimeConfig,
) -> tuple[ChannelMessageReceiveAdapter, ChannelMessageSendAdapter]:
    if channel == "wechat":
        return WeChatReceiveAdapter(), WeChatTextSendAdapter(config)
    if channel == "feishu":
        return FeishuReceiveAdapter(), FeishuTextSendAdapter(config)
    raise ValueError(f"unsupported channel worker: {channel}")


def parse_channels(value: str) -> list[str]:
    channels = [part.strip().lower() for part in value.split(",") if part.strip()]
    if not channels:
        raise ValueError("at least one channel must be configured")
    return channels


def install_signal_handlers(stop: asyncio.Event) -> None:
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:  # pragma: no cover - Windows fallback.
            signal.signal(sig, lambda _signum, _frame: stop.set())


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


if __name__ == "__main__":  # pragma: no cover
    main()
