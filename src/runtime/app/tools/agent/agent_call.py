"""多 Agent 编排工具域：call_agent —— 运行时动态调用 subAgent（环检测 + 独立执行上下文）。"""
import uuid

from langchain_core.messages import HumanMessage
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

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

    assembled = _assemble_agent(agent)
    child_state = {
        **state,  # 继承会话上下文（user_id/claw_id 等）
        **assembled,  # 覆盖为子 Agent 的人格/模型/工具
        "messages": [HumanMessage(content=task)]  # 子 Agent 只从任务开始，不带父历史
    }
    child_config: RunnableConfig = {
        **config,
        "configurable": {
            **config.get("configurable", {}),
            "depth": current_depth + 1,
            "parent_id": config.get("configurable", {}).get("span_id"),  # trace
            "span_id": str(uuid.uuid4()),
        }
    }
    subgraph = get_agent_subgraph(agent, assembled["model_config"], assembled["tool_specs"])
    final_state = await subgraph.ainvoke(child_state, config=child_config)
    return final_state["messages"][-1].content