"""分布式追踪：append_event 把事件追加到会话级 Redis list（调用树素材）。

- key: trace:{claw_id}:{conversation_id} —— 逻辑会话一棵树，call_agent 嵌套调用共享；
- 事件 JSON 携带 span_id/parent_id，嵌套 span 由上层构造（call_agent、审批门、main.py）；
- 无 TTL、与 checkpointer 同策略：事件留存由上层/运维兜底清理。
"""
import json
import time
import uuid

from redis import asyncio as aioredis

from .config import settings
from .state import ClawState


_redis = aioredis.from_url(settings.redis_url, decode_responses=True)


async def append_event(
        state: ClawState,
        event_type: str,
        data: dict,
        *,
        span_id: str | None = None,
        parent_id: str | None = None,
) -> None:
    """追加一条追踪事件；失败仅吞掉，不影响 Agent 主流程（与审批通知同策略）。"""
    try:
        event = {
            "event_id": str(uuid.uuid4()),
            "type": event_type,
            "claw_id": settings.claw_id,
            "agent_id": state.get("agent_id"),
            "user_id": state.get("user_id"),
            "conversation_id": state.get("conversation_id"),
            "span_id": span_id,
            "parent_id": parent_id,
            "timestamp_ms": int(time.time() * 1000),
            "data": data,
        }
        key = f"trace:{settings.claw_id}:{state.get('conversation_id')}"
        await _redis.rpush(key, json.dumps(event, ensure_ascii=False, default=str))
    except Exception:
        pass  # 追踪失败不阻断执行


def build_span_tree(events: list[dict]) -> list[dict]:
    """LRANGE 事件列表 → 调用树（按 span_id/parent_id 组织，parent 为空的是根）。"""
    by_span: dict[str, dict] = {}
    children: dict[str, list[dict]] = {}
    for ev in events:
        span_id = ev.get("span_id") or ev["event_id"]
        node = {**ev, "children": []}
        by_span[span_id] = node
        children.setdefault(ev.get("parent_id"), []).append(node)
    for ev in events:
        span_id = ev.get("span_id") or ev["event_id"]
        by_span[span_id]["children"] = children.get(span_id, [])
    return [by_span[ev.get("span_id") or ev["event_id"]] for ev in events if not ev.get("parent_id")]