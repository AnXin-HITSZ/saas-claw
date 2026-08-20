"""Claw Pod 入口：推理流式接口 + 审批恢复 + 会话查询（conversation domain 归 runtime）。

通道约定（SSE，MediaType text/event-stream）：
- POST /v1/chat/completions  （经网关转发，X-User-Id 由网关注入）
    请求体：{"messages": [...], "conversation_id": "<调用方生成的 uuid>", "alias"|"agent_id"?}
    SSE 事件：
      data: {"choices": [{"delta": {"content": "..."}}]}   ← 内容增量
      data: {"type": "__interrupt__", "payload": {...}}    ← 审批挂起
      data: [DONE]
- POST /approvals/callback   （backend 集群内直连，不经网关）
    请求体：{"request_id": "...", "result": {"decision": "approve"|"reject", "reason"?: "..."}}
- GET  /v1/conversations               会话列表（Redis 索引）
- GET  /v1/conversations/{id}/messages 消息历史（读本 Pod checkpointer）
- GET  /v1/conversations/{id}/trace    调用链（Redis trace list → span 树）

会话/trace 全部留在 Redis，绝不进 MySQL；backend 不碰会话业务逻辑。
"""
import asyncio
import json
import logging
import time
import uuid
from contextlib import asynccontextmanager

logger = logging.getLogger(__name__)

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from langchain_core.messages import AIMessageChunk, HumanMessage, BaseMessage
from langchain_core.runnables import RunnableConfig
from langgraph.types import Command
from pydantic import BaseModel
from redis import asyncio as aioredis
from typing import Any, AsyncIterator

from . import checkpointer
from .approval_outbox import outbox_loop
from .config import settings
from .graph import build_claw_graph
from .state import ClawState
from .tools.registry import purge_thread_cache
from .trace import append_event, build_span_tree


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动：打开 AsyncRedisSaver（from_conn_string 是异步上下文管理器，async with 打开时
    __aenter__ 自动 setup 建索引），取得实例后注入 checkpointer.saver 并编译主图；
    后台启动审批 outbox 冲刷任务（backend 短暂不可达时重试送达，直到落库）；
    退出时上下文关闭连接。"""
    async with checkpointer.open_saver_cm() as saver:
        checkpointer.saver = saver
        app.state.claw_graph = build_claw_graph().compile(checkpointer=saver)
        outbox_task = asyncio.create_task(outbox_loop())
        try:
            yield
        finally:
            outbox_task.cancel()


app = FastAPI(lifespan=lifespan)
_kv = aioredis.from_url(settings.redis_url, decode_responses=True)


# 挂起审批 → 恢复 config：request_id → 恢复时用的 config（含 thread_id/depth/span 链）
_interrupt_registry: dict[str, RunnableConfig] = {}

# ---- 审批恢复注册表持久化（Redis）：Pod 重启后 pending 审批仍可恢复 ----
# 快速路径走进程内 dict，同时镜像到 Redis（TTL 24h）。回调命中顺序：
#   内存 pop → Redis GETDEL（原子取+删，Pod 重启/多副本兜底）→ done 标记（重复回调幂等）。
# 幂等语义：
#   - 取到 config → 恢复执行；
#   - 未取到但 done 标记存在 → 已处理过的重复回调 → 200 静默成功（不重复恢复，避免重复执行敏感操作）；
#   - 两者皆无 → 404（从未注册或已过期）。
_REGISTRY_KEY = "interrupt:{claw_id}:{request_id}"
_REGISTRY_DONE_KEY = "interrupt:done:{claw_id}:{request_id}"
_REGISTRY_TTL_SECONDS = 24 * 3600


async def _registry_set(request_id: str, config: RunnableConfig) -> None:
    try:
        await _kv.set(
            _REGISTRY_KEY.format(claw_id=settings.claw_id, request_id=request_id),
            json.dumps(config, ensure_ascii=False, default=str),
            ex=_REGISTRY_TTL_SECONDS,
        )
    except Exception as exc:
        logger.warning("审批注册表持久化失败 request_id=%s: %s", request_id, exc)  # 只影响重启后恢复


async def _registry_getdel(request_id: str) -> RunnableConfig | None:
    """原子取+删（GETDEL）：命中返回 config，未命中返回 None。"""
    raw = await _kv.getdel(_REGISTRY_KEY.format(claw_id=settings.claw_id, request_id=request_id))
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception as exc:
        logger.warning("审批注册表 GETDEL 解析失败 request_id=%s: %s", request_id, exc)
        return None


async def _registry_del(request_id: str) -> None:
    """删除注册表条目。内存命中路径用：消费后同步清 Redis，保证重复回调走 GETDEL 未命中 → done 标记
    幂等 200（否则 Redis 里残留 config，重复回调会再次取到并恢复执行 → 敏感操作二次执行）。"""
    try:
        await _kv.delete(_REGISTRY_KEY.format(claw_id=settings.claw_id, request_id=request_id))
    except Exception as exc:
        logger.warning("审批注册表删除失败 request_id=%s: %s", request_id, exc)


async def _registry_mark_done(request_id: str) -> None:
    try:
        await _kv.set(
            _REGISTRY_DONE_KEY.format(claw_id=settings.claw_id, request_id=request_id),
            "1",
            ex=_REGISTRY_TTL_SECONDS,
        )
    except Exception as exc:
        logger.warning("审批 done 标记写入失败 request_id=%s: %s", request_id, exc)


async def _registry_is_done(request_id: str) -> bool:
    try:
        return bool(await _kv.exists(_REGISTRY_DONE_KEY.format(claw_id=settings.claw_id, request_id=request_id)))
    except Exception as exc:
        logger.warning("审批 done 标记查询失败 request_id=%s: %s", request_id, exc)
        return False


# ---- 同 conversation/thread 并发 run 互斥：新消息与审批恢复不可同时写同一 checkpoint ----
# 每个逻辑会话一把 asyncio 锁，串行化「聊天 run」与「审批恢复 run」；有界防止内存膨胀。
_thread_locks: dict[str, asyncio.Lock] = {}
_thread_locks_guard = asyncio.Lock()
_MAX_THREAD_LOCKS = 1024


async def _thread_lock(thread_id: str) -> asyncio.Lock:
    async with _thread_locks_guard:
        lock = _thread_locks.get(thread_id)
        if lock is None:
            if len(_thread_locks) >= _MAX_THREAD_LOCKS:
                _thread_locks.pop(next(iter(_thread_locks)))  # 有界：只淘汰最久未访问的锁
            lock = asyncio.Lock()
            _thread_locks[thread_id] = lock
        return lock


# ---- 请求/响应模型 ----
class ChatCompletionRequest(BaseModel):
    messages: list[dict]  # [{"role": "user", "content": "..."}, ...]
    conversation_id: str  # 调用方（前端）生成的 uuid
    alias: str | None = None
    agent_id: int | None = None


class ApprovalCallbackBody(BaseModel):
    request_id: str
    result: dict  # {"decision": "approve"|"reject", "reason"?: "..."} → Command(resume=result)


# ---- 工具函数 ----
def _base_config(conversation_id: str) -> RunnableConfig:
    """顶层执行 config：逻辑会话 thread + depth=0 + 新 root span。"""
    return {
        "configurable": {
            "thread_id": f"claw:{settings.claw_id}:{conversation_id}",
            "conversation_id": conversation_id,
            "depth": 0,
            "span_id": str(uuid.uuid4()),
            "parent_id": None,
        }
    }


def _extract_text(msg: AIMessageChunk) -> str:
    """messages stream 的 content 可能是 str 或多段 [{type,text}]，统一取文本增量。"""
    content = getattr(msg, "content", "")
    if isinstance(content, str):
        return content
    return "".join(seg.get("text", "") for seg in content if isinstance(seg, dict))


def _sse(obj: dict) -> str:
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n"


def _to_business_message(m: BaseMessage) -> dict:
    """checkpointer 里的 BaseMessage → 前端可读的业务格式。"""
    content = m.content
    if not isinstance(content, str):
        content = "".join(seg.get("text", "") for seg in content if isinstance(seg, dict))
    return {"role": m.type, "content": content}


async def _load_business_messages(conversation_id: str) -> list[dict]:
    """读 checkpointer 里本会话的全部消息，转业务格式（角色 human/ai/tool/system）。"""
    snapshot = await app.state.claw_graph.aget_state(_base_config(conversation_id))
    messages = snapshot.values.get("messages", []) if snapshot.values else []
    return [_to_business_message(m) for m in messages]


def _merge_trace_items(events: list[dict], messages: list[dict]) -> list[dict]:
    """把 trace 事件与消息合并为统一时间轴 items，按 timestamp_ms 升序（前端单接口时间轴）。

    消息本身无时间戳，用 chat_start/chat_end 轮次边界定时间：
    第 k 轮用户消息取 chat_start[k].ts、助手消息取 chat_end[k].ts；边界缺失时留空排尾（保序）。
    事件本身带 timestamp_ms，直接作为 kind=event 项。树结构仍由 /trace 的 tree 字段提供。
    """
    starts = [e for e in events if e.get("type") == "chat_start"]
    ends = [e for e in events if e.get("type") == "chat_end"]

    msg_items = []
    for i, m in enumerate(messages):
        turn = i // 2
        if i % 2 == 0:  # 用户消息 → 对应轮 chat_start
            ts = starts[turn].get("timestamp_ms") if turn < len(starts) else None
        else:  # 助手消息 → 对应轮 chat_end
            ts = ends[turn].get("timestamp_ms") if turn < len(ends) else None
        msg_items.append({"kind": "message", "role": m["role"], "content": m["content"], "timestamp_ms": ts})

    ev_items = [{"kind": "event", **e} for e in events]
    return sorted(msg_items + ev_items, key=lambda x: (x.get("timestamp_ms") is None, x.get("timestamp_ms") or 0))


async def _sse_stream(
    stream: AsyncIterator[tuple[str, Any]],
    config: RunnableConfig,
    interrupted: list[bool] | None = None,
) -> AsyncIterator[str]:
    """把 graph.astream 的 (mode, chunk) 转成 SSE；messages 增量 → choices，interrupt → __interrupt__，
    custom → trace_event（过程卡，实时透传给前端时间轴）。

    审批挂起时把 request_id → config 记入 registry（内存 + Redis 持久化），供 /approvals/callback 恢复；
    interrupted 非空时，挂起发生会 append True，供调用方决定是否回收工具结果缓存（挂起结束的
    run 不能回收——resume 仍需重放）。
    """
    async for mode, chunk in stream:
        if mode == "messages":
            text = _extract_text(chunk[0])
            if text:
                yield _sse({"choices": [{"delta": {"content": text}}]})
        elif mode == "custom":
            # 过程事件帧：工具/子agent/审批过程卡（与 trace 落盘事件同构）
            event = chunk.get("event", chunk) if isinstance(chunk, dict) else chunk
            yield _sse({"type": "trace_event", "event": event})
        elif mode == "updates" and "__interrupt__" in chunk:
            for itr in chunk["__interrupt__"]:
                request_id = str(itr.value["request_id"])
                _interrupt_registry[request_id] = config
                await _registry_set(request_id, config)  # 持久化：Pod 重启后 pending 审批仍可恢复
                if interrupted is not None:
                    interrupted.append(True)
                yield _sse({"type": "__interrupt__", "payload": itr.value})


# ---- 会话索引（Redis；conversation domain 归 runtime，不进 MySQL）----
def _conv_index_key() -> str:
    return f"conv:index:{settings.claw_id}"


def _conv_list_key() -> str:
    return f"conv:list:{settings.claw_id}"


async def _touch_conversation(conversation_id: str) -> None:
    """首次见到的会话登记进列表；已有则跳过（HSETNX 幂等）。"""
    created = await _kv.hsetnx(_conv_index_key(), conversation_id, "{}")
    if created:
        await _kv.lpush(_conv_list_key(), conversation_id)


async def _update_conversation_meta(conversation_id: str) -> dict | None:
    """聊天结束：读 checkpointer 得消息数，更新会话元信息（尽力而为）。"""
    try:
        snapshot = await app.state.claw_graph.aget_state(_base_config(conversation_id))
        messages = snapshot.values.get("messages", []) if snapshot.values else []
        last_message = messages[-1] if messages else None
        last_summary = _to_business_message(last_message)["content"][:100] if last_message else ""
        meta = {
            "conversation_id": conversation_id,
            "last_ts": int(time.time() * 1000),
            "msg_count": len(messages),
            "last_summary": last_summary,
        }
        await _kv.hset(_conv_index_key(), conversation_id, json.dumps(meta, ensure_ascii=False))
        return meta  # 返回值顺带喂给 chat_end 的 trace 事件
    except Exception:
        return None  # 索引失败不影响对话主流程


# ---- 端点 ----
@app.post("/v1/chat/completions")
async def chat_completions(req: ChatCompletionRequest, x_user_id: str | None = Header(default=None)) -> StreamingResponse:
    if x_user_id is None:
        raise HTTPException(status_code=401, detail="缺少用户身份（需网关注入 X-User-Id）")
    user_id = int(x_user_id)  # 唯一来源

    user_msg = next(
        (m.get("content") for m in reversed(req.messages) if m.get("role") == "user"), None
    )
    if not user_msg:
        raise HTTPException(status_code=400, detail="messages 需包含至少一条 user 消息")

    config = _base_config(req.conversation_id)
    thread_id = str(config["configurable"]["thread_id"])

    # 该会话存在挂起的审批中断：拒绝新消息。LangGraph 对挂起 thread 发新输入会从 checkpoint
    # 重放并再次 suspend（已实证），用户无法推进；直接 409 引导去审批页处理，同时避免
    # 「新消息 run」与「审批恢复 run」并发写同一 checkpoint。正确性由锁保证，此检查是 UX 层：
    # 只看 snapshot.interrupts（真正的挂起中断），不误伤 mid-stream 的第二条消息。
    try:
        snapshot = await app.state.claw_graph.aget_state(config)
        if snapshot.interrupts:
            raise HTTPException(status_code=409, detail="该会话有待处理的审批，请先到工具审批处理后再继续对话")
    except HTTPException:
        raise
    except Exception:
        pass  # 查不到状态（首次/Redis 不可达等）不阻断主流程

    input_state: ClawState = {
        "messages": [HumanMessage(content=user_msg)],  # 历史在 checkpointer 里，只带本轮增量
        "user_id": user_id,
        "conversation_id": req.conversation_id,
    }
    if req.alias:
        input_state["alias"] = req.alias
    if req.agent_id is not None:
        input_state["agent_id"] = req.agent_id

    await _touch_conversation(req.conversation_id)
    await append_event(input_state, "chat_start", {"alias": req.alias, "agent_id": req.agent_id})

    async def gen():
        interrupted: list[bool] = []
        lock = await _thread_lock(thread_id)
        async with lock:  # 同一会话的聊天 run 串行化，且与审批恢复 run 互斥（见 approvals_callback）
            try:
                async for sse in _sse_stream(
                        app.state.claw_graph.astream(input_state, config, stream_mode=["messages", "updates", "custom"]),
                        config,
                        interrupted,
                ):
                    yield sse
            except Exception as exc:  # 图 run 中途异常（模型配置缺失/供应商报错等）不能裸抛：ASGI 下
                # uvicorn 会直接关 TCP、不发 chunked 终止块，客户端（网关）收到 premature close、
                # 前端拿到断流而非可见错误。转成规范化 SSE error 事件，再由 finally 补 [DONE]，
                # 让流以带终止块的收尾正常结束。
                logger.warning("chat SSE 中途异常 conversation=%s: %s", req.conversation_id, exc, exc_info=True)
                yield _sse({"type": "error", "error": str(exc)[:500]})
            finally:
                if not interrupted:
                    # 本轮无挂起审批：该 thread 的工具结果缓存已无重放需求，回收防误淘汰/内存膨胀。
                    # 挂起结束的 run 不回收——resume 仍需重放这些条目（多轮审批场景）。
                    purge_thread_cache(thread_id)
                meta = await _update_conversation_meta(req.conversation_id)
                await append_event(input_state, "chat_end", meta or {})
                yield "data: [DONE]\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"X-Accel-Buffering": "no"},
    )


@app.post("/approvals/callback")
async def approvals_callback(body: ApprovalCallbackBody) -> StreamingResponse:
    # 命中顺序：进程内（快速路径）→ Redis GETDEL（Pod 重启/多副本兜底，原子取+删）→ done 标记。
    config = _interrupt_registry.pop(body.request_id, None)
    if config is not None:
        await _registry_del(body.request_id)  # 内存命中也要清 Redis：重复回调必须走 done 幂等分支
    else:
        config = await _registry_getdel(body.request_id)
        if config is None:
            if await _registry_is_done(body.request_id):
                # 幂等：已处理过的重复回调（backend 重试投递），静默成功，不重复恢复执行。
                return JSONResponse(content={"status": "already_handled"}, status_code=200)
            raise HTTPException(status_code=404, detail=f"审批请求不存在或已处理: {body.request_id}")
    conversation_id = config["configurable"]["conversation_id"]
    thread_id = str(config["configurable"]["thread_id"])

    async def gen():
        interrupted: list[bool] = []
        lock = await _thread_lock(thread_id)
        async with lock:  # 与同一会话的新消息 run 互斥：不并发写同一 checkpoint
            try:
                async for sse in _sse_stream(
                    app.state.claw_graph.astream(
                        Command(resume=body.result), config, stream_mode=["messages", "updates", "custom"]
                    ),
                    config,
                    interrupted,
                ):
                    yield sse
            except Exception as exc:  # 与 chat_completions 同策略：恢复 run 中途异常转 SSE error，避免
                # uvicorn 关 TCP 无 chunked 终止块导致客户端（网关/backend 回调方）断流。
                logger.warning("审批恢复 SSE 中途异常 request_id=%s: %s", body.request_id, exc, exc_info=True)
                yield _sse({"type": "error", "error": str(exc)[:500]})
            finally:
                if not interrupted:
                    # 恢复完成、未再次挂起：该 thread 不再有重放需求，回收工具结果缓存。
                    # 恢复后又挂起（多轮审批）则保留——下一次 resume 仍需重放。
                    purge_thread_cache(thread_id)
                await _update_conversation_meta(conversation_id)
                # 幂等标记：重复回调读到 done → 200 不再重复恢复（避免敏感操作执行两次）。
                await _registry_mark_done(body.request_id)
                yield "data: [DONE]\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"X-Accel-Buffering": "no"},
    )


@app.get("/v1/conversations")
async def list_conversations() -> dict:
    ids = await _kv.lrange(_conv_list_key(), 0, -1)
    if not ids:
        return {"list": []}
    metas = await _kv.hmget(_conv_index_key(), ids)
    items = []
    for cid, raw in zip(ids, metas):
        if raw:
            try:
                items.append(json.loads(raw))
            except (TypeError, json.JSONDecodeError):
                continue
    return {"list": items}


@app.get("/v1/conversations/{conversation_id}/messages")
async def get_conversation_messages(conversation_id: str) -> dict:
    return {"conversation_id": conversation_id, "messages": await _load_business_messages(conversation_id)}


@app.get("/v1/conversations/{conversation_id}/trace")
async def get_conversation_trace(conversation_id: str) -> dict:
    raw_list = await _kv.lrange(f"trace:{settings.claw_id}:{conversation_id}", 0, -1)
    events = [json.loads(x) for x in raw_list if x]
    messages = await _load_business_messages(conversation_id)
    return {
        "conversation_id": conversation_id,
        "events": events,  # 兼容旧调用方；前端时间轴改用 items
        "items": _merge_trace_items(events, messages),
        "tree": build_span_tree(events),
    }