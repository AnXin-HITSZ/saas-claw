"""敏感工具审批门：interrupt 挂起等待用户审批，批准后执行工具。

执行语义（LangGraph interrupt 基于异常，resume 时节点从头重跑）：
- 首次执行：interrupt() 抛出 GraphInterrupt 挂起，其后的代码不执行；
- resume：节点重跑，interrupt() 返回 resume 值（{"decision": "approve"/"reject", "reason": "..."}），其后再 await 执行工具；
- 因此 interrupt 之前的副作用（推送审批请求）在 resume 时重跑一次——backend 须按 request_id 幂等。
"""
import json
import logging

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import BaseTool

from ..config import settings
from ..state import ClawState
from .registry import with_state

logger = logging.getLogger(__name__)


async def approval_gate(
        state: ClawState,
        tool_name: str,
        args: dict,
        tool_id: int,
        config: RunnableConfig,
        tool_fn: BaseTool,
        tool_call_id: str,
) -> str:
    """敏感工具执行门：通知 backend → interrupt 挂起 → resume 后按 decision 执行或拒绝。

    tool_call_id：LLM 生成的工具调用 id（AI message 中稳定，resume 重跑不变）。
    - request_id 由 tool_call_id 确定性派生（approval:{tool_call_id}），修复「重跑生成新 uuid
      导致 backend 幂等失效、产生重复审批记录」的问题；
    - 子线程 child 模式（spawn 容器派发的子任务）：跳过单独 backend 提交，仅 interrupt 挂起，
      由 spawn 容器聚合为一张批量审批卡统一提交——避免「每子任务独立审批」的用户侧噪音。
    """
    from langgraph.types import interrupt

    from ..trace import EVT_APPROVAL_PENDING, EVT_APPROVAL_RESOLVED, emit_event

    request_id = f"approval:{tool_call_id}"
    is_child = bool(config.get("configurable", {}).get("_approval_child"))

    if not is_child:
        await _submit_approval_request(state, tool_name, args, tool_id, request_id)

    # 审批卡事件只在非 child 模式（单条审批）发；child 模式由 spawn 容器在 batch 层发一张，
    # 避免时间轴出现「子审批 + 批量审批」两张卡。child 的敏感工具仍由 execute_tool_call 埋 tool_start/end。
    if not is_child:
        await emit_event(
            state, EVT_APPROVAL_PENDING,
            {"tool": tool_name, "request_id": request_id, "input_summary": _summarize(args)},
            config=config, dedup_key=f"{request_id}:pending",
        )

    decision = interrupt({
        "type": "tool_approval",
        "request_id": request_id,
        "agent_id": state.get("agent_id"),
        "user_id": state.get("user_id"),
        "claw_id": settings.claw_id,
        "tool_id": tool_id,
        "tool_name": tool_name,
        "input_summary": _summarize(args),
    })

    action, reason = _parse_decision(decision)

    if not is_child:
        await emit_event(
            state, EVT_APPROVAL_RESOLVED,
            {"tool": tool_name, "request_id": request_id, "decision": action, "reason": reason},
            config=config, dedup_key=f"{request_id}:resolved",
        )

    if action == "approve":
        return await tool_fn.ainvoke(args, config=with_state(config, state))
    if action == "reject":
        message = f"用户拒绝了工具 {tool_name} 的执行。"
        if reason:
            message += f" 拒绝理由：{reason}"
        return message
    return f"工具 {tool_name} 审批未通过（未识别的决策值 {decision!r}）。"


def _parse_decision(decision: object) -> tuple[str, str]:
    """解析 resume 决策：允许 / 拒绝（+可选理由）。

    前端三种选择（action 1/2/3）在运行时归并为两种行为：
      {"decision": "approve"}                 → 执行工具（action=1 允许）；
      {"decision": "reject", "reason": "..."} → 不执行，理由并入返回文本（action=2/3 拒绝+理由）。
    非 dict（如中断/超时不带值）视为未识别，走兜底消息。
    """
    if not isinstance(decision, dict):
        return "", ""
    return str(decision.get("decision", "")), str(decision.get("reason") or "")


async def _submit_approval_request(state: ClawState, tool_name: str, args: dict, tool_id: int, request_id: str) -> None:
    """推送审批请求到 backend；失败仅吞掉，不阻断 interrupt 挂起。"""
    payload = {
        "request_id": request_id,
        "user_id": state.get("user_id"),
        "claw_id": settings.claw_id,
        "agent_id": state.get("agent_id"),
        "tool_id": tool_id,
        "input_summary": _summarize(args),
    }
    try:
        from ..http import post_json

        await post_json("/tools/approval-requests", payload)
    except Exception as exc:
        # 通知失败会让用户审批页看不到卡片、主图永久挂起：显式记录 + 暂存 outbox 后台重试直到落库。
        # 不在此抛异常（会打断 interrupt 挂起）；outbox 按 request_id 幂等，重试安全。
        logger.warning("推送审批请求失败 request_id=%s: %s", request_id, exc)
        try:
            from ..approval_outbox import enqueue

            await enqueue("/tools/approval-requests", payload)
        except Exception:
            pass  # outbox 暂存失败（如 Redis 不可达）也不阻断 interrupt 挂起


def _summarize(args: dict, limit: int = 500) -> str:
    """审批展示用输入摘要，超长截断。"""
    text = json.dumps(args, ensure_ascii=False, default=str)
    return text[:limit] + "……" if len(text) > limit else text