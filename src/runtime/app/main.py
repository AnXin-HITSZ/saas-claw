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
import json
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessageChunk, HumanMessage, BaseMessage
from langchain_core.runnables import RunnableConfig
from langgraph.types import Command
from pydantic import BaseModel
from redis import asyncio as aioredis
from typing import Any, AsyncIterator

from . import checkpointer
from .config import settings
from .graph import build_claw_graph
from .state import ClawState
from .trace import append_event, build_span_tree


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动：打开 RedisSaver（新版 from_conn_string 是同步上下文管理器，with 打开并 setup
    建索引），取得实例后注入 checkpointer.saver 并编译主图；退出时上下文关闭连接。"""
    with checkpointer.open_saver() as saver:
        checkpointer.saver = saver
        app.state.claw_graph = build_claw_graph().compile(checkpointer=saver)
        yield


app = FastAPI(lifespan=lifespan)
_kv = aioredis.from_url(settings.redis_url, decode_responses=True)


# 挂起审批 → 恢复 config：request_id → 恢复时用的 config（含 thread_id/depth/span 链）
_interrupt_registry: dict[str, RunnableConfig] = {}


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


async def _sse_stream(stream: AsyncIterator[tuple[str, Any]], config: RunnableConfig) -> AsyncIterator[str]:
    """把 graph.astream 的 (mode, chunk) 转成 SSE；messages 增量 → choices，interrupt → __interrupt__。

    审批挂起时把 request_id → config 记入 registry，供 /approvals/callback 恢复。
    """
    async for mode, chunk in stream:
        if mode == "messages":
            text = _extract_text(chunk[0])
            if text:
                yield _sse({"choices": [{"delta": {"content": text}}]})
        elif mode == "updates" and "__interrupt__" in chunk:
            for itr in chunk["__interrupt__"]:
                request_id = str(itr.value["request_id"])
                _interrupt_registry[request_id] = config
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
        try:
            async for sse in _sse_stream(
                    app.state.claw_graph.astream(input_state, config, stream_mode=["messages", "updates"]),
                    config,
            ):
                yield sse
        finally:
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
    config = _interrupt_registry.get(body.request_id)
    if config is None:
        raise HTTPException(status_code=404, detail=f"审批请求不存在或已处理: {body.request_id}")
    _interrupt_registry.pop(body.request_id, None)
    conversation_id = config["configurable"]["conversation_id"]

    async def gen():
        try:
            async for sse in _sse_stream(
                app.state.claw_graph.astream(
                    Command(resume=body.result), config, stream_mode=["messages", "updates"]
                ),
                config,
            ):
                yield sse
        finally:
            await _update_conversation_meta(conversation_id)
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
    snapshot = await app.state.claw_graph.aget_state(_base_config(conversation_id))
    messages = snapshot.values.get("messages", []) if snapshot.values else []
    return {"conversation_id": conversation_id, "messages": [_to_business_message(m) for m in messages]}


@app.get("/v1/conversations/{conversation_id}/trace")
async def get_conversation_trace(conversation_id: str) -> dict:
    raw_list = await _kv.lrange(f"trace:{settings.claw_id}:{conversation_id}", 0, -1)
    events = [json.loads(x) for x in raw_list if x]
    return {"conversation_id": conversation_id, "events": events, "tree": build_span_tree(events)}