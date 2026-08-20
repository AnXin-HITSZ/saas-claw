"""多 Agent 编排工具域：list_agents —— 枚举当前 Claw 内的兄弟 Agent（供 LLM 路由选择）。

定位：Agent 发现自身无法完成某任务时，先调本工具拿到本 Claw 下可协作的 Agent 目录，
再据 description 挑合适的 alias/id 交给 call_agent 调用。只读、无副作用、非敏感。
"""
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ...config import settings
from ...db import get_agents_by_claw
from ...tools.registry import register_tool, get_state


@register_tool()  # 非敏感：只读枚举
@tool
async def list_agents(*, config: RunnableConfig) -> str:
    """列出当前 Claw 内的其他 Agent（不含自身），供协作路由选择。

    返回每个 Agent 的 alias、name、id、description；alias 在同一用户下唯一，
    后续可用 call_agent(agent_id=…) 调用目标。当前 Claw 内没有其他 Agent 时返回空提示。

    Args: 无
    """
    state = get_state(config)
    current_agent_id = state.get("agent_id")
    agents = [a for a in get_agents_by_claw(settings.claw_id) if a.id != current_agent_id]

    if not agents:
        return "当前 Claw 内没有其他可协作的 Agent。"

    lines = ["当前 Claw 内可协作的 Agent："]
    for a in agents:
        desc = (a.description or "（无说明）").strip()
        lines.append(f"- alias: {a.alias} | id: {a.id} | name: {a.name} | 说明: {desc}")
    return "\n".join(lines)
