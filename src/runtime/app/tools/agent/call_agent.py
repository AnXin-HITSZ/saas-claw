"""多 Agent 编排工具域：call_agent —— 运行时动态调用 subAgent（环检测 + 边界校验 + 独立执行上下文）。"""
import uuid

from langchain_core.messages import HumanMessage
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ...config import settings
from ...db import get_agent_by_id
from ...graph import _assemble_agent, get_agent_subgraph
from ...tools.registry import register_tool, get_state


_DEPTH_LIMIT = 3  # 环检测：调用链最大深度（根 Agent 为 0）


@register_tool(sensitive=True)  # 跨 Agent 调用默认需审批
@tool
async def call_agent(agent_id: int, task: str, *, config: RunnableConfig) -> str:
    """调用另一个 Agent 完成子任务（subAgent 编排，可并行派发）。

    Args:
        agent_id: 目标 Agent 的数字 id。
        task: 交给子 Agent 的任务描述。
    """
    state = get_state(config)
    current_depth = int(config.get("configurable", {}).get("depth", 0))
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

    assembled = _assemble_agent(agent)
    child_state = {
        **state,  # 继承会话上下文（user_id/claw_id 等）
        **assembled,  # 覆盖为子 Agent 的人格/模型/工具
        "messages": [HumanMessage(content=task)]  # 子 Agent 只从任务开始，不带父历史
    }
    child_span_id = str(uuid.uuid4())
    parent_thread_id = config.get("configurable", {}).get("thread_id", "")
    child_config: RunnableConfig = {
        **config,
        "configurable": {
            **config.get("configurable", {}),
            # 独立 sub-thread：子 Agent 内部消息/审批中断不与父历史串扰（对齐 spawn._child_config）。
            "thread_id": f"{parent_thread_id}::{child_span_id}",
            # call_agent 本身已 sensitive 预审批，子 Agent 内部敏感工具跳过单独 backend 提交，
            # 避免生成无回调路径的孤儿审批卡。
            "_approval_child": True,
            "depth": current_depth + 1,
            "parent_id": config.get("configurable", {}).get("span_id"),  # trace
            "span_id": child_span_id,
        }
    }

    from ...trace import EVT_SUBAGENT_END, EVT_SUBAGENT_START, emit_event

    await emit_event(
        state, EVT_SUBAGENT_START,
        {"agent_id": agent_id, "task": task},
        config=config, span_id=child_span_id, parent_id=config.get("configurable", {}).get("span_id"),
    )

    subgraph = get_agent_subgraph(agent, assembled["model_config"], assembled["tool_specs"])
    try:
        final_state = await subgraph.ainvoke(child_state, config=child_config)
    except Exception as exc:
        # 子 Agent 异常（LLM 失败/内部敏感工具 interrupt）不静默击穿父轮次：显式返回错误给父 LLM。
        await emit_event(
            state, EVT_SUBAGENT_END,
            {"agent_id": agent_id, "status": "error"},
            config=config, span_id=child_span_id, parent_id=config.get("configurable", {}).get("span_id"),
        )
        return f"调用 Agent {agent_id} 失败：{exc}"

    await emit_event(
        state, EVT_SUBAGENT_END,
        {"agent_id": agent_id, "status": "done"},
        config=config, span_id=child_span_id, parent_id=config.get("configurable", {}).get("span_id"),
    )

    return final_state["messages"][-1].content