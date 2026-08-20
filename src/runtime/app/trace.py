"""分布式追踪：emit_event 把一条事件同时【落盘 Redis】+【live 推给 SSE】，两路共用同一份事件。

设计（对话过程可回放 + 可实时观察，事件模型只有一套）：
- 落盘：key = trace:{claw_id}:{conversation_id}，会话一棵树，call_agent/spawn 嵌套调用共享；
  重开会话时 GET /trace 从此重建 items/tree。消息权威在 checkpointer，trace 只补过程事件。
- 有界保留（对齐工具缓存/spawn 会话的内存治理）：每个会话的 trace 列表 LTRIM 只留最近
  _TRACE_LIST_MAX 条（更早的过程卡可裁剪，降级优雅——main 对缺失 turn 边界会留空排尾）；
  trace:seen 判重集加 TTL（审批回调 24h 内必达，7 天余量足够，活动会话顺延自动续期），
  空闲会话的 seen 集会释放，防长期会话内存膨胀。
- live：langgraph astream(custom) 通道。节点内 get_stream_writer() 拿到 writer，把事件
  以 {"type":"trace_event","event":{...}} 帧推出，main._sse_stream 转成 SSE 过程卡实时显示。
  脱离流上下文（无 custom 流）时 get_stream_writer 不可用，try/except 兜底为 no-op。
- span：事件携带 span_id/parent_id，缺省从 config.configurable 取（_base_config/ call_agent/
  spawn 在 config 里维护 span 链），build_span_tree 据此聚合成调用树（森林）。

两路都是「尽力而为」：任一路失败只吞掉，绝不阻断 Agent 主流程（与审批通知同策略）。
"""
import json
import time
import uuid

from langchain_core.runnables import RunnableConfig
from redis import asyncio as aioredis

from .config import settings
from .state import ClawState


_redis = aioredis.from_url(settings.redis_url, decode_responses=True)

# 有界保留参数（见模块 docstring）：trace 列表每个会话最多保留条数；seen 判重集 TTL。
_TRACE_LIST_MAX = 2000
_SEEN_SET_TTL_SECONDS = 7 * 24 * 3600


# ---- 事件类型常量（前端时间轴按 type 分发渲染；集中定义避免拼写漂移）----
EVT_CHAT_START = "chat_start"          # 一轮对话开始（turn 边界，含 ts，供 /trace 给消息定时间戳）
EVT_CHAT_END = "chat_end"              # 一轮对话结束（turn 边界）
EVT_TOOL_START = "tool_start"          # 工具开始执行
EVT_TOOL_END = "tool_end"              # 工具执行完成（含结果摘要）
EVT_SUBAGENT_START = "subagent_start"  # 子 Agent 调用开始（call_agent / spawn 派发）
EVT_SUBAGENT_END = "subagent_end"      # 子 Agent 调用完成
EVT_APPROVAL_PENDING = "approval_pending"    # 敏感工具挂起等待审批
EVT_APPROVAL_RESOLVED = "approval_resolved"  # 审批完成（approve / reject）


def _emit_live(event: dict) -> None:
    """把事件推给 astream(custom) 通道（live 过程卡）；脱离流上下文时兜底为 no-op。"""
    try:
        from langgraph.config import get_stream_writer

        writer = get_stream_writer()  # 节点内有 custom 流时返回 writer，否则抛异常/返回 no-op
        if writer is not None:
            writer({"type": "trace_event", "event": event})
    except Exception:
        pass  # live 推送失败不影响落盘与主流程


async def emit_event(
        state: ClawState,
        event_type: str,
        data: dict,
        *,
        config: RunnableConfig | None = None,
        span_id: str | None = None,
        parent_id: str | None = None,
        dedup_key: str | None = None,
) -> dict:
    """记录一条追踪事件：先构造事件体 → 落盘 Redis（幂等去重）→ live 推 SSE，返回事件体本身。

    span_id/parent_id 缺省从 config.configurable 取（_base_config/call_agent/spawn 维护的 span 链），
    也可显式传入覆盖（如工具节点为每个 tool_call 生成独立 span）。任一路失败只吞掉，不抛。

    dedup_key（重放去重）：LangGraph 审批 resume 会整节点重跑，中断点之前的 emit_event 会
    再执行一次。传入稳定键（如 f"{tool_call_id}:{event_type}"）时：event_id 由该键确定性派生，
    且落盘前用 Redis Set(SADD) 判重——重放的同名事件不会二次入 list，trace 不会出现重复过程卡。
    不传 dedup_key 则退化为随机 event_id、不判重（turn 边界等天然唯一的事件）。
    """
    cfg = (config or {}).get("configurable", {})
    if span_id is None:
        span_id = cfg.get("span_id")
    if parent_id is None:
        parent_id = cfg.get("parent_id")

    # dedup_key 命中 → event_id 确定性派生（uuid5），使重放产出同一 id，天然可判重
    event_id = str(uuid.uuid5(uuid.NAMESPACE_URL, dedup_key)) if dedup_key else str(uuid.uuid4())

    event = {
        "event_id": event_id,
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

    conversation_id = state.get("conversation_id")

    # 1) 落盘（重开重建的权威来源）；有 dedup_key 时先 SADD 判重，重放的同名事件跳过入队
    try:
        if dedup_key is not None:
            seen_key = f"trace:seen:{settings.claw_id}:{conversation_id}"
            added = await _redis.sadd(seen_key, event_id)
            if not added:  # 已存在 → 本次是重放，落盘直接跳过（保持 list 无重复）
                return event
            # 有界：seen 集只服务 resume 重放判重（审批回调 24h 内必达），7 天 TTL 有余量；
            # 活动会话每次 SADD 顺延自动续期，空闲会话的集合自动释放防内存膨胀。
            await _redis.expire(seen_key, _SEEN_SET_TTL_SECONDS)
        key = f"trace:{settings.claw_id}:{conversation_id}"
        await _redis.rpush(key, json.dumps(event, ensure_ascii=False, default=str))
        # 有界：每会话只留最近 _TRACE_LIST_MAX 条事件；消息权威在 checkpointer，
        # 裁剪只影响非常久远的过程卡展示，main 对缺失 turn 边界会留空排尾，降级优雅。
        await _redis.ltrim(key, -_TRACE_LIST_MAX, -1)
    except Exception:
        pass  # 落盘失败不阻断执行

    # 2) live 推送（当前页面实时过程卡）——重放已在上面 return，不会重复推
    _emit_live(event)

    return event


# 兼容别名：历史调用方（main.py 的 chat_start/chat_end）仍可用 append_event，语义等同 emit_event。
append_event = emit_event


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
