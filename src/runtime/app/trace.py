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


def build_span_tree_old(events: list[dict]) -> list[dict]:
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


def build_span_tree(events: list[dict]) -> list[dict]:
    """LRANGE 事件列表 → 调用树（森林）。

    - 每个 span（span_id 或兜底 event_id）对应一个容器节点，
      该 span 的全部事件聚合进 node.events，首条事件定型节点的 type/data 等字段；
    - children 按 parent_id 挂接，父子先后出现顺序无关；
    - 根 = 首条事件无 parent_id 的容器，按首现顺序返回，保证无重复。
    """
    by_span: dict[str, dict] = {}
    children: dict[str, list[dict]] = {}
    roots: list[dict] = []

    for ev in events:
        span_id = ev.get("span_id") or ev["event_id"]
        node = by_span.get(span_id)
        if node is None:  # 首次出现：建容器（用第一条事件封装）
            node = {**ev, "events": [], "children": []}  # 非首条不再覆盖
            by_span[span_id] = node
            if not ev.get("parent_id"):
                roots.append(node)  # 根判定只做一次
            children.setdefault(ev.get("parent_id"), []).append(node)  # 只在创建时登记父子关系（容器入桶一次）
        node["events"].append(ev)  # 同 span 的所有事件全部收集

    for span_id, node in by_span.items():  # 每个容器统一挂孩子
        node["children"] = children.get(span_id, [])

    return roots  # 根去重了