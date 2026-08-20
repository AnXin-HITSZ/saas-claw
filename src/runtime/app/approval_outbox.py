"""审批提交 outbox：backend 短暂不可达时暂存审批请求，后台任务重试直到落库。

root cause（对抗审查 confirmed，spawn_subagent.py / approval.py）：_submit_batch_approval /
_submit_approval_request 失败被静默吞掉后仍 interrupt 挂起——backend 无审批记录，用户审批页
永远看不到卡，主图无限挂起且无兜底。outbox 让请求最终送达：
  - POST 失败 → 暂存 Redis（approval:outbox:{claw}:{request_id}，TTL 24h）；
  - 后台任务每 3s 冲刷，逐条 POST 到 backend，成功即删；
  - POST 按 request_id 幂等（backend 重复提交返回已有记录），重试安全。
后台任务由 main.lifespan 启动，与挂起的图并行（interrupt 只挂起该 run 的协程，不阻塞事件循环）。
"""
import asyncio
import json
import logging

from redis import asyncio as aioredis

from .config import settings

logger = logging.getLogger(__name__)

_redis = aioredis.from_url(settings.redis_url, decode_responses=True)

_OUTBOX_PREFIX = "approval:outbox:{claw_id}:"
_OUTBOX_TTL_SECONDS = 24 * 3600  # 24h 内必达；超时放弃（配合后端幂等，用户可重新触发）
_RETRY_INTERVAL_SECONDS = 3


def _key(request_id: str) -> str:
    return f"{_OUTBOX_PREFIX.format(claw_id=settings.claw_id)}{request_id}"


async def enqueue(path: str, payload: dict) -> None:
    """把审批请求暂存 outbox（后台任务会重试 POST 到 backend）。"""
    request_id = payload.get("request_id")
    if not request_id:
        return
    item = {"path": path, "payload": payload}
    try:
        await _redis.set(_key(request_id), json.dumps(item, ensure_ascii=False, default=str), ex=_OUTBOX_TTL_SECONDS)
    except Exception as exc:
        logger.warning("审批 outbox 暂存失败 request_id=%s: %s", request_id, exc)


async def flush_outbox() -> int:
    """扫一遍 outbox，逐条 POST 到 backend；成功即删（幂等）。返回成功条数。"""
    from .http import post_json

    keys = await _redis.keys(f"{_OUTBOX_PREFIX.format(claw_id=settings.claw_id)}*")
    ok = 0
    for key in keys:
        raw = await _redis.get(key)
        if not raw:
            continue
        try:
            item = json.loads(raw)
            await post_json(item["path"], item["payload"])
        except Exception as exc:
            logger.warning("审批 outbox 重试失败 key=%s: %s", key, exc)
            continue
        await _redis.delete(key)
        ok += 1
    return ok


async def outbox_loop() -> None:
    """后台任务：每 _RETRY_INTERVAL_SECONDS 冲刷 outbox，直到 backend 可达把请求落库。"""
    while True:
        try:
            await flush_outbox()
        except Exception:
            pass  # 扫描失败下轮再试
        await asyncio.sleep(_RETRY_INTERVAL_SECONDS)
