"""多 Agent 编排工具域：call_agent —— 运行时动态调用 subAgent（环检测 + 边界校验 + 独立执行上下文 + 子图审批透传）。

子图审批透传语义：子 Agent 内部敏感工具触发审批时，本工具在【父线程】重新 interrupt() 把同一
审批载荷透传给前端（backend 提交/事件由子图 approval_gate 完整走），审批通过后回调恢复主图，
再由本工具按决策用 Command(resume) 恢复子图 checkpoint。
"""
import logging
import uuid

from langchain_core.messages import HumanMessage
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool
from langgraph.errors import GraphBubbleUp
from langgraph.types import Command, interrupt

from ...config import settings
from ...db import get_agent_by_id
from ...graph import _assemble_agent, get_agent_subgraph
from ...tools.registry import register_tool, get_state

logger = logging.getLogger(__name__)

_DEPTH_LIMIT = 3  # 环检测：调用链最大深度（根 Agent 为 0）


# ---- 子图审批会话注册表（跨 interrupt resume 持久化：resume 整节点重跑，函数局部变量丢失）----
# key = (thread_id, call_agent 的 tool_call_id)。子 Agent 内部敏感工具触发审批时，call_agent 在
# 父线程重新 interrupt() 透传给前端；resume 时靠本注册表回放已完成轮次、按决策用 Command(resume)
# 恢复子图。子图完成后即删除，避免脏会话串扰后续同 key 调用。
_CALL_AGENT_SESSIONS: dict[tuple[str, str], "_CallAgentSession"] = {}
_MAX_SESSIONS = 128


class _CallAgentSession:
    """一次 call_agent 调用的子图推进进度，跨 resume 持久化。"""
    __slots__ = ("started", "batches", "decisions")

    def __init__(self):
        self.started = False             # 子图是否已首次启动（True 表示已在子线程跑过，resume 需恢复）
        self.batches: list[dict] = []    # 每轮审批 interrupt 载荷（按轮次；batches[-1] 为当前待决议轮）
        self.decisions: list[dict] = []  # 已决议（与 batches[:len(decisions)] 对齐）


def _get_session(thread_id: str, tool_call_id: str) -> _CallAgentSession:
    key = (thread_id, tool_call_id)
    sess = _CALL_AGENT_SESSIONS.get(key)
    if sess is not None:
        return sess
    sess = _CallAgentSession()
    _CALL_AGENT_SESSIONS[key] = sess
    _trim_sessions()
    return sess


def _drop_session(thread_id: str, tool_call_id: str) -> None:
    _CALL_AGENT_SESSIONS.pop((thread_id, tool_call_id), None)


def _trim_sessions() -> None:
    """有界且不误伤挂起会话：只淘汰【未启动】的会话。

    挂起等待审批的会话必须保留——resume 整节点重跑靠本注册表 rehydrate 推进进度；已完成会话在
    调用结束时已 pop。找不到可安全淘汰项时允许暂时超限（完成路径正常回收兜底）。
    """
    while len(_CALL_AGENT_SESSIONS) > _MAX_SESSIONS:
        evictable = next(
            (k for k, s in _CALL_AGENT_SESSIONS.items() if s is not None and not s.started),
            None,
        )
        if evictable is None:
            return  # 全部为挂起/进行中会话：保留，不淘汰
        _CALL_AGENT_SESSIONS.pop(evictable)


@register_tool(sensitive=True)  # 跨 Agent 调用默认需审批
@tool
async def call_agent(agent_id: int, task: str, *, config: RunnableConfig) -> str:
    """调用另一个 Agent 完成子任务（subAgent 编排，可并行派发）。

    Args:
        agent_id: 目标 Agent 的数字 id。
        task: 交给子 Agent 的任务描述。
    """
    state = get_state(config)
    cfg = config.get("configurable", {})
    current_depth = int(cfg.get("depth", 0))
    if current_depth >= _DEPTH_LIMIT:
        return f"Agent 调用链超过深度上限 {_DEPTH_LIMIT}，已终止。"

    agent = get_agent_by_id(agent_id)
    if agent is None:
        return f"Agent {agent_id} 不存在或已停用。"

    # 边界校验（claw-boundary gap）：目标 Agent 必须归属当前 Claw 且属于当前用户，
    # 否则拒绝跨 Claw/跨用户越权调用。get_agent_by_id 只按 id+status 查，这里补归属约束。
    if agent.claw_id != settings.claw_id:
        return f"Agent {agent_id} 不属于当前 Claw，已拒绝调用。"
    if agent.user_id != state.get("user_id"):
        return f"Agent {agent_id} 不属于当前用户，已拒绝调用。"

    # 会话键：execute_tool_call 注入的 _tool_call_id（LLM tool_call id）在 resume 重跑时稳定，
    # 与 thread_id 一起唯一定位本次调用，跨审批 resume 据此 rehydrate 子图推进进度。
    thread_id = str(cfg.get("thread_id", ""))
    tool_call_id = str(cfg.get("_tool_call_id") or uuid.uuid4())
    sess = _get_session(thread_id, tool_call_id)

    assembled = _assemble_agent(agent)
    child_state = {
        **state,  # 继承会话上下文（user_id/claw_id 等）
        **assembled,  # 覆盖为子 Agent 的人格/模型/工具
        "messages": [HumanMessage(content=task)]  # 子 Agent 只从任务开始，不带父历史
    }
    # 确定性派生 child span/thread：resume 重跑时不变，子图 checkpoint（含审批挂起状态）不丢。
    child_span_id = f"call:{tool_call_id}"
    parent_thread_id = thread_id
    child_config: RunnableConfig = {
        **config,
        "configurable": {
            **cfg,
            # 独立 sub-thread：子 Agent 内部消息/审批中断不与父历史串扰（对齐 spawn._child_config）。
            "thread_id": f"{parent_thread_id}::{child_span_id}",
            # 不再设 _approval_child：子 Agent 内部敏感工具走完整审批（backend 提交 + 事件 + 挂起），
            # 由本函数透传到父线程，前端可正常审批并回调恢复。spawn 容器仍用 _approval_child 聚合。
            "depth": current_depth + 1,
            "parent_id": cfg.get("span_id"),  # trace
            "span_id": child_span_id,
        }
    }

    from ...trace import EVT_SUBAGENT_END, EVT_SUBAGENT_START, emit_event

    await emit_event(
        state, EVT_SUBAGENT_START,
        {"agent_id": agent_id, "task": task},
        config=config, span_id=child_span_id, parent_id=cfg.get("span_id"),
        dedup_key=f"{tool_call_id}:subagent_start",
    )

    try:
        subgraph = get_agent_subgraph(agent, assembled["model_config"], assembled["tool_specs"])

        # 回放已完成轮次的 interrupt：LangGraph 按调用位置返回已存储的 resume 值（忽略之，用 decisions）。
        # 保证后续「待决议轮」的 interrupt() 落在正确的位置（= len(decisions)）。
        replayed = 0
        while replayed < len(sess.decisions):
            interrupt(sess.batches[replayed])
            replayed += 1

        while True:
            if not sess.started:
                run_input = child_state
                sess.started = True
            else:
                # 子图挂起在上一轮审批：先取该轮决策（首次运行/新轮在此抛 GraphInterrupt 挂起父线程；
                # resume 时返回存储值），再按决策恢复子图。
                decision = interrupt(sess.batches[-1])
                sess.decisions.append(decision)
                run_input = Command(resume=decision)

            done, result = await _run_child(subgraph, run_input, child_config)
            if done:
                _drop_session(thread_id, tool_call_id)
                break
            sess.batches.append(result)  # 子图新阻塞：记录本轮审批载荷，回到循环顶部透传给父线程

        await emit_event(
            state, EVT_SUBAGENT_END,
            {"agent_id": agent_id, "status": "done"},
            config=config, span_id=child_span_id, parent_id=cfg.get("span_id"),
            dedup_key=f"{tool_call_id}:subagent_end",
        )
        return result
    except GraphBubbleUp:
        raise  # 审批挂起 interrupt 是控制流异常，必须透传给父线程（前端审批），不能转文本
    except Exception as exc:
        # 子 Agent 异常（LLM 失败/子图编译失败/子图内部工具异常）不静默击穿父轮次：显式返回错误给父 LLM。
        _drop_session(thread_id, tool_call_id)
        await emit_event(
            state, EVT_SUBAGENT_END,
            {"agent_id": agent_id, "status": "error"},
            config=config, span_id=child_span_id, parent_id=cfg.get("span_id"),
            dedup_key=f"{tool_call_id}:subagent_end",
        )
        return f"调用 Agent {agent_id} 失败：{exc}"


async def _run_child(
        subgraph, run_input, child_config: RunnableConfig,
) -> tuple[bool, str | dict]:
    """跑子 Agent 到完成或下一个审批 blocker。返回 (done, result)。

    - 子图审批挂起时 astream 在 updates 帧产出 __interrupt__。注意不能用 ainvoke：嵌套子图
      （langgraph 的 is_nested=True）下 _suppress_interrupt 不会把 GraphInterrupt 压成 __interrupt__
      返回值，而是直接抛异常——此处 astream 捕获挂起载荷，供 call_agent 在父线程重新 interrupt() 透传；
    - custom 帧（子 Agent 过程事件）透传给父 astream 的 custom 通道（live 实时过程卡）。
    """
    interrupt_value = None
    async for mode, chunk in subgraph.astream(run_input, config=child_config, stream_mode=["updates", "custom"]):
        if mode == "updates" and "__interrupt__" in chunk:
            interrupt_value = chunk["__interrupt__"][0].value
            break
        if mode == "custom":
            _forward_live(chunk)
    if interrupt_value is not None:
        return False, interrupt_value

    # 流结束无 interrupt → 完成，读最终状态最后一条消息
    snapshot = await subgraph.aget_state(child_config)
    messages = snapshot.values.get("messages", []) if snapshot.values else []
    last = messages[-1] if messages else None
    content = last.content if last is not None else ""
    if not isinstance(content, str):
        content = "".join(seg.get("text", "") for seg in content if isinstance(seg, dict))
    return True, content


def _forward_live(chunk) -> None:
    """把子 Agent 的 custom 过程帧透传到父 astream 的 custom 通道（live 实时过程卡）。"""
    try:
        from langgraph.config import get_stream_writer

        writer = get_stream_writer()
        if writer is not None:
            writer(chunk)
    except Exception:
        pass  # 脱离流上下文时 no-op；落盘仍由子 Agent 的 emit_event 保证
