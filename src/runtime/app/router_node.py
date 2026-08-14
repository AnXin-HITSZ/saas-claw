"""路由节点：请求未显式指定 Agent 时，按描述让 LLM 选择本 Claw 的 Agent（路由兜底）。

执行链：prepare 已解析出 agent_id/alias 时本节点直接放行；
两者皆无（原始请求只带 messages）→ 收集本 Claw 所有 Agent 作路由目录，
构造路由 prompt 让 LLM 只输出 alias，再解析回 agent_id 并组装上下文。
"""
from .config import settings
from .db import get_agents_by_claw, get_agent_by_alias
from .state import ClawState


def router_node(state: ClawState) -> dict:
    """路由兜底：只有既无 agent_id 也无 alias 时才触发。"""
    if state.get("agent_id") is not None or state.get("alias"):
        return {}  # prepare 已确定目标 Agent，直接放行

    agents = get_agents_by_claw(settings.claw_id)
    if not agents:
        return {}

    alias = _route_alias(state, agents)
    if not alias:
        return {}  # 路由失败：交给 executor 兜底提示

    agent = get_agent_by_alias(state["user_id"], alias)
    if agent is None:
        return {}
    from .graph import _assemble_agent  # 延迟 import：避免 graph ↔ router 循环依赖
    return _assemble_agent(agent)


def _route_alias(state: ClawState, agents: list) -> str | None:
    """用路由 LLM 依据 description 选出最合适的 Agent，返回其 alias（失败回退 None）。"""
    from langchain_core.messages import SystemMessage, HumanMessage

    from .db import get_model_config
    from .llm import build_chat_model

    # 使用独立路由模型（settings.router_model）；未配置（get_model_config 查不到）则返回 None → 上层提示配置
    cfg = get_model_config(settings.router_model)
    if cfg is None:
        return None
    llm = build_chat_model({
        "model_name": cfg.model_name,
        "endpoint": cfg.endpoint,
        "api_key": cfg.api_key,
        "temperature": 0.0,  # 路由要确定性：同一请求应稳定选同一个 Agent
        "max_tokens": 64,  # 只需输出一个 alias，控制成本与延迟
    })
    catalog = "\n".join(
        f"- alias: {a.alias} | name: {a.name} | 说明: {a.description or '（无）'}"
        for a in agents
    )
    prompt = (
        f"以下是当前 Claw 的 Agent 目录：\n{catalog}\n\n"
        f"用户请求：{state['messages'][-1].content}\n"
        "请选择最合适的 Agent，只输出其 alias（一个词），不要输出任何其他内容。"
    )
    resp = llm.invoke([SystemMessage(content="你是 Agent 路由器。"), HumanMessage(content=prompt)])
    alias = str(resp.content or "").strip()
    return alias if any(a.alias == alias for a in agents) else None