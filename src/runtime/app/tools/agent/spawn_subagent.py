"""多 Agent 编排工具域：spawn_subagent —— 并行派发多个子任务，敏感操作聚合为一张批量审批卡。

定位：父 Agent 把一个复杂任务拆成若干子任务，用【自己的 config（来自 state）】克隆出多个
子 Agent 并行执行；子任务里触发的敏感工具操作不进后端逐条审批，而是由本容器聚合成一个
batch，在 main thread 上统一挂起等待用户一次审批，再按 approval_id 逐 child 恢复。

执行模型（LangGraph interrupt 位置匹配 + 子图独立 sub-thread，已用 venv 实证）：
- 每个子任务 = 一个 child，继承父 state（人格/模型/工具/user_id/conversation_id），只换任务
  （messages=[HumanMessage(task)]），在独立 sub-thread `{main_thread_id}::{child_span_id}` 上
  astream 执行；child 的 config 打 `_approval_child=True`，其 approval_gate 跳过 backend 提交、
  只 interrupt 挂起。
- 容器捕获所有 child 的 `__interrupt__`，聚合为一个 batch，在 main thread 用 interrupt() 挂起；
  batch 的 request_id = `approval:batch:{spawn_tool_call_id}:{round_idx}`（按轮次派生：round_idx = 已完成
  轮数，同轮在 resume 重跑时稳定 → backend 幂等；不同轮次互不冲突，避免多轮敏感操作复用同一记录）。
- resume 时整节点重跑：容器经注册表 rehydrate 后，先【回放】已完成轮次的 interrupt（LangGraph
  按调用位置返回已存储的 resume 值），再按 decisions 用 Command(resume=decision) 逐 child 恢复，
  跑到下一个 blocker 或完成，形成第 N+1 轮 batch。每轮 interrupt 只多一次调用，位置匹配天然对齐。
- depth = 父 depth + 1，超过 _DEPTH_LIMIT 就地终止（环检测）；兄弟间 depth 不共享（各自 = 父+1）。
"""
import asyncio
import logging
import uuid
from typing import Any

from langchain_core.messages import HumanMessage
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool
from langgraph.types import Command

from ...config import settings
from ...db import get_agent_by_id
from ...graph import get_agent_subgraph
from ...tools.registry import register_tool, get_state
from ...trace import (
    EVT_APPROVAL_PENDING,
    EVT_APPROVAL_RESOLVED,
    EVT_SUBAGENT_END,
    EVT_SUBAGENT_START,
    emit_event,
)

logger = logging.getLogger(__name__)

_DEPTH_LIMIT = 3


# ---- 容器会话注册表（跨 interrupt resume 持久化：resume 整节点重跑，函数局部变量丢失）----
# key = (main_thread_id, spawn_tool_call_id) → _SpawnSession；同一 Pod 内 resume 命中，全部完成后删除。
_SPAWN_SESSIONS: dict[tuple[str, str], "_SpawnSession"] = {}
_MAX_SESSIONS = 256


class _Child:
    """一路子任务的状态，跨 resume 持久化。"""
    __slots__ = ("index", "task", "name", "span_id", "interrupt_value",
                 "started", "blocked", "done", "result", "decision", "error")

    def __init__(self, index: int, task: str, name: str, span_id: str):
        self.index = index
        self.task = task
        self.name = name or f"子任务{index + 1}"
        self.span_id = span_id
        self.interrupt_value: dict | None = None  # 阻塞时 approval_gate 的 interrupt 载荷
        self.started = False
        self.blocked = False
        self.done = False
        self.result: str | None = None
        self.decision: dict | None = None  # 已决议、待恢复
        self.error: str | None = None


class _SpawnSession:
    """一次 spawn 调用的进度。batches/decisions 逐轮追加，回放 interrupt 用。"""
    __slots__ = ("spawn_id", "children", "batches", "decisions")

    def __init__(self, spawn_id: str, children: list[_Child]):
        self.spawn_id = spawn_id
        self.children = children
        self.batches: list[dict] = []    # 每轮 batch payload（interrupt 回放要原样传回）
        self.decisions: list[dict] = []  # 每轮 decision（与 batches 对齐）


def _get_session(thread_id: str, spawn_id: str, tasks: list[dict]) -> _SpawnSession:
    key = (thread_id, spawn_id)
    sess = _SPAWN_SESSIONS.get(key)
    if sess is not None:
        return sess
    children = []
    for i, t in enumerate(tasks):
        span_id = f"{spawn_id}:child:{i}"
        children.append(_Child(i, str(t.get("task", "")), str(t.get("name") or ""), span_id))
    sess = _SpawnSession(spawn_id, children)
    _SPAWN_SESSIONS[key] = sess
    _trim_sessions()
    return sess


def _trim_sessions() -> None:
    """有界且不误伤挂起会话：只淘汰【全部 child 已完成】的会话。

    挂起等待审批的会话必须保留——resume 整节点重跑靠 _SPAWN_SESSIONS rehydrate child 进度；
    若被 FIFO 挤掉，resume 会新建空 session、子任务全部重跑（重复执行敏感操作）。
    找不到安全可淘汰项时允许暂时超限（内存上限由 _MAX_SESSIONS 下的正常完成路径回收兜底）。
    """
    while len(_SPAWN_SESSIONS) > _MAX_SESSIONS:
        evictable = next(
            (k for k, s in _SPAWN_SESSIONS.items()
             if s is not None and all(c.done for c in s.children)),
            None,
        )
        if evictable is None:
            return  # 全部为挂起/进行中会话：保留，不淘汰
        _SPAWN_SESSIONS.pop(evictable)


@register_tool()  # 非敏感：容器自身不触发审批，子任务的敏感操作经 barrier 聚合
@tool
async def spawn_subagent(tasks: list[dict], *, config: RunnableConfig) -> str:
    """把一个复杂任务拆成多个子任务并行执行，子任务里的敏感操作合并成一次审批。

    Args:
        tasks: 子任务列表，每项 {"task": "子任务描述", "name": "可选别名"}。
            name 用于审批/trace 展示，缺省按序号命名。
    """
    from langgraph.types import interrupt

    state = get_state(config)
    cfg = config.get("configurable", {})
    depth = int(cfg.get("depth", 0))
    if depth >= _DEPTH_LIMIT:
        return f"子任务派发超过调用链深度上限 {_DEPTH_LIMIT}，已终止。"

    tasks = [t for t in (tasks or []) if isinstance(t, dict) and str(t.get("task", "")).strip()]
    if not tasks:
        return "子任务列表为空，未派发。"

    spawn_id = cfg.get("_tool_call_id") or str(uuid.uuid4())  # 兜底（execute_tool_call 已注入）
    thread_id = str(cfg.get("thread_id", ""))
    parent_span = str(cfg.get("span_id", ""))

    parent_agent = get_agent_by_id(state.get("agent_id"))
    if parent_agent is None:
        return "父 Agent 不存在或已停用。"

    sess = _get_session(thread_id, spawn_id, tasks)

    # ---- 回放已完成轮次的 interrupt：LangGraph 按位置返回存储的 resume 值（忽略之，用 decisions）----
    replayed = 0
    while replayed < len(sess.decisions):
        interrupt(sess.batches[replayed])
        replayed += 1

    subgraph = get_agent_subgraph(parent_agent, state.get("model_config"), state.get("tool_specs"))

    while True:
        # 推进：start 新 child + resume 已决议 child，跑到各自下一个 blocker 或完成
        blocked = await _advance_children(sess, subgraph, state, config, cfg, depth, parent_span, thread_id)
        if not blocked:
            break  # 全部完成

        round_idx = len(sess.decisions)
        batch = _build_batch(sess, blocked, state, round_idx)
        sess.batches.append(batch)

        await _submit_batch_approval(state, batch)
        await emit_event(
            state, EVT_APPROVAL_PENDING, _batch_event_data(batch),
            config=config, span_id=f"spawn:{spawn_id}", parent_id=parent_span,
            dedup_key=f"{spawn_id}:batch:{round_idx}:pending",
        )

        decision = interrupt(batch)

        await emit_event(
            state, EVT_APPROVAL_RESOLVED, {"request_id": batch["request_id"], "decision": _overall(decision)},
            config=config, span_id=f"spawn:{spawn_id}", parent_id=parent_span,
            dedup_key=f"{spawn_id}:batch:{round_idx}:resolved",
        )
        sess.decisions.append(decision)
        _apply_decisions(sess, decision)

    result = _summarize(sess)
    _SPAWN_SESSIONS.pop((thread_id, spawn_id), None)
    return result


async def _advance_children(sess, subgraph, state, config, cfg, depth, parent_span, thread_id) -> list[_Child]:
    """并行推进所有未完成 child 到下一个 blocker 或完成，返回本轮新阻塞的 child 列表。

    与「并行派发」契约对齐：全部 child 用 asyncio.gather 并发跑（各自独立 sub-thread，
    interrupt 各在其命名空间内、无同线程并发 interrupt 冲突）；单 child 失败只标记 error、
    不击穿整把扇出。返回值按 child.index 顺序聚合，保证 batch sub_requests 顺序稳定。
    """
    pending = []
    for child in sess.children:
        if child.done:
            continue
        if child.blocked and child.decision is None:
            continue  # 本轮已阻塞但尚未拿决策（应在 interrupt 后由 _apply_decisions 填充）
        pending.append(child)

    async def _step(child: _Child) -> None:
        is_resume = child.started
        if not is_resume:
            child.started = True
            run_input: Any = {**state, "messages": [HumanMessage(content=child.task)]}
            await emit_event(
                state, EVT_SUBAGENT_START, {"task": child.task, "name": child.name},
                config=config, span_id=child.span_id, parent_id=parent_span,
                dedup_key=f"{sess.spawn_id}:child:{child.index}:start",
            )
        else:
            run_input = Command(resume=child.decision)
            child.decision = None

        child_config = _child_config(config, cfg, depth, parent_span, thread_id, child)
        try:
            done, result, itr_value = await _run_child(subgraph, run_input, child_config)
        except Exception as exc:
            # 单 child 失败隔离：标记失败、继续推进其余 child，不击穿整把扇出。
            # _summarize 的「未完成」分支据此输出 child.error（此前该字段从未被赋值）。
            child.done = True
            child.blocked = False
            child.error = str(exc)
            await emit_event(
                state, EVT_SUBAGENT_END, {"task": child.task, "name": child.name, "status": "error"},
                config=config, span_id=child.span_id, parent_id=parent_span,
                dedup_key=f"{sess.spawn_id}:child:{child.index}:end",
            )
            return
        if done:
            child.done = True
            child.blocked = False
            child.result = result
            await emit_event(
                state, EVT_SUBAGENT_END, {"task": child.task, "name": child.name, "status": "done"},
                config=config, span_id=child.span_id, parent_id=parent_span,
                dedup_key=f"{sess.spawn_id}:child:{child.index}:end",
            )
        else:
            child.blocked = True
            child.interrupt_value = itr_value

    if pending:
        await asyncio.gather(*(_step(c) for c in pending))
    return [c for c in sess.children if c.blocked and c.decision is None and not c.done]


async def _run_child(subgraph, run_input, child_config: RunnableConfig):
    """跑一个 child 到完成或下一个 blocker。返回 (done, result, interrupt_value)。

    run_input 是初始 state 或 Command(resume=decision)；custom 帧（子过程事件）透传给父 stream。
    """
    interrupt_value = None
    async for mode, chunk in subgraph.astream(run_input, config=child_config, stream_mode=["updates", "custom"]):
        if mode == "updates" and "__interrupt__" in chunk:
            interrupt_value = chunk["__interrupt__"][0].value
            break
        if mode == "custom":
            _forward_live(chunk)
    if interrupt_value is not None:
        return False, None, interrupt_value

    # 流结束无 interrupt → 完成，读最终状态最后一条消息
    snapshot = await subgraph.aget_state(child_config)
    messages = snapshot.values.get("messages", []) if snapshot.values else []
    last = messages[-1] if messages else None
    content = last.content if last is not None else ""
    if not isinstance(content, str):
        content = "".join(seg.get("text", "") for seg in content if isinstance(seg, dict))
    return True, content, None


def _forward_live(chunk) -> None:
    """把 child 的 custom 过程帧透传到父 astream 的 custom 通道（live 实时过程卡）。"""
    try:
        from langgraph.config import get_stream_writer

        writer = get_stream_writer()
        if writer is not None:
            writer(chunk)
    except Exception:
        pass  # 脱离流上下文时 no-op；落盘仍由 child 的 emit_event 保证


def _child_config(config: RunnableConfig, cfg: dict, depth: int, parent_span: str, thread_id: str, child: _Child) -> RunnableConfig:
    """child 独立 sub-thread config：独立 thread_id + depth+1 + _approval_child 跳过单独审批。"""
    return {
        **config,
        "configurable": {
            **cfg,
            "thread_id": f"{thread_id}::{child.span_id}",
            "depth": depth + 1,
            "parent_id": parent_span,
            "span_id": child.span_id,
            "_approval_child": True,
        },
    }


def _build_batch(sess: _SpawnSession, blocked: list[_Child], state: dict, round_idx: int) -> dict:
    """聚合本轮阻塞的 child 为一张批量审批卡 payload。

    request_id 按轮次派生（approval:batch:{spawn_id}:{round_idx}）：round_idx = len(decisions)，
    同一轮在 resume 重跑时稳定（backend 按 request_id 幂等），不同轮次互不冲突——否则多轮敏感
    操作会复用同一 request_id，导致后续轮次在 backend 命中已处理记录、无法生成新的待审批卡。
    """
    sub_requests = []
    for child in blocked:
        v = child.interrupt_value or {}
        sub_requests.append({
            "request_id": v.get("request_id"),
            "agent_id": v.get("agent_id", state.get("agent_id")),
            "tool_id": v.get("tool_id"),
            "tool_name": v.get("tool_name", ""),
            "input_summary": v.get("input_summary", ""),
        })
    return {
        "type": "batch_approval",
        "request_id": f"approval:batch:{sess.spawn_id}:{round_idx}",
        "agent_id": state.get("agent_id"),
        "user_id": state.get("user_id"),
        "claw_id": settings.claw_id,
        "sub_requests": sub_requests,
    }


def _batch_event_data(batch: dict) -> dict:
    """trace 事件 data：审批卡摘要（不含敏感细节，只留 request_id + 子请求数 + 工具名）。"""
    return {
        "request_id": batch["request_id"],
        "sub_count": len(batch.get("sub_requests", [])),
        "tools": [s.get("tool_name", "") for s in batch.get("sub_requests", [])],
    }


def _overall(decision: Any) -> str:
    if isinstance(decision, dict):
        return str(decision.get("decision", ""))
    return ""


def _apply_decisions(sess: _SpawnSession, decision: Any) -> None:
    """把 batch decision 映射到本轮阻塞的 child：优先 per-child decisions，缺省用整体决策。"""
    decisions_map = decision.get("decisions", {}) if isinstance(decision, dict) else {}
    overall = {
        "decision": decision.get("decision", "reject") if isinstance(decision, dict) else "reject",
        "reason": decision.get("reason", "") if isinstance(decision, dict) else "",
    }
    for child in sess.children:
        if child.blocked and child.decision is None:
            request_id = child.interrupt_value.get("request_id") if child.interrupt_value else None
            d = decisions_map.get(request_id) if request_id else None
            child.decision = d if isinstance(d, dict) else overall


def _summarize(sess: _SpawnSession) -> str:
    """把 N 路子任务结果汇成给父 LLM 的文本。"""
    lines = [f"已并行执行 {len(sess.children)} 个子任务："]
    for child in sess.children:
        if child.done:
            text = (child.result or "").strip() or "（无输出）"
            if len(text) > 200:
                text = text[:200] + "……"
            lines.append(f"- [{child.name}] {text}")
        else:
            lines.append(f"- [{child.name}] 未完成：{child.error or '被用户拒绝'}")
    return "\n".join(lines)


async def _submit_batch_approval(state: dict, batch: dict) -> None:
    """提交批量审批到 backend（best-effort，失败不阻断 interrupt 挂起）。

    backend 侧契约（Task 10 落地）：POST /tools/approval-batches，request_id 幂等；
    处理完成后按 batch request_id 回调 runtime /approvals/callback 恢复主图。
    """
    try:
        from ...http import post_json

        await post_json("/tools/approval-batches", batch)
    except Exception as exc:
        # 通知失败会让用户审批页看不到卡片、主图永久挂起：显式记录 + 暂存 outbox 后台重试直到落库。
        # 不在此抛异常（会打断 interrupt 挂起）；outbox 按 batch request_id 幂等，重试安全。
        logger.warning("推送批量审批失败 request_id=%s: %s", batch.get("request_id"), exc)
        try:
            from ...approval_outbox import enqueue

            await enqueue("/tools/approval-batches", batch)
        except Exception:
            pass  # outbox 暂存失败（如 Redis 不可达）也不阻断 interrupt 挂起
