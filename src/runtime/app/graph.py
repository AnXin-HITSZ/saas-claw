"""LangGraph 主图：固定图 prepare→router→executor，Agent 子图按模板+配置签名缓存。

每轮请求：
  客户端 → /v1/chat/completions → prepare（人格+模型+工具物化）→ router（路由兜底）
        → executor（Agent 子图：LLM↔工具循环）→ END

设计要点：
- 子图模板：_SUBGRAPH_TEMPLATES 注册表按 Agent 配置分派，默认 react，新增模板即注册新函数。
- 子图共享全局 checkpointer：主图/子图同一 saver，interrupt 挂起由该层 thread_id 管理。
- 工具并行：tools 节点并发执行非敏感工具调用；敏感工具串行保证审批 interrupt 顺序。
- 签名缓存扁平：只签名"自己的"模板+模型+工具，Agent 调 Agent 走动态调用，无嵌套签名。
"""
import asyncio
import json
from typing import Callable

from langchain_core.messages import SystemMessage, ToolMessage, BaseMessage, AIMessage
from langchain_core.runnables import Runnable, RunnableConfig
from langgraph.graph import START, END, StateGraph
from langgraph.graph.state import CompiledStateGraph

from . import checkpointer  # saver 属性由 main.lifespan 注入，子图编译时动态读取
from .router_node import router_node
from .db import Agent
from .state import ClawState


# ---- Agent 子图缓存：agent_id → (模板, 配置签名, 编译后子图) ----
_agent_subgraphs: dict[int, tuple[str, str, CompiledStateGraph]] = {}


def _signature(model_config: dict, tool_specs: list[dict]) -> str:
    """影响子图编译的配置摘要（模型参数 + 工具集）。persona 不进签名：每轮动态注入。"""
    return json.dumps(
        [model_config, [t["name"] for t in tool_specs]],
        ensure_ascii=False, sort_keys=True,
    )


# ---- 子图模板注册表 ----
def _compile_react_subgraph(model_config: dict, tool_specs: list[dict]) -> CompiledStateGraph:
    """ReAct 模板：LLM ↔ 工具循环。"""
    from .llm import build_chat_model
    from .tools.registry import materialize_tools

    model_with_tools = build_chat_model(model_config).bind_tools(materialize_tools(tool_specs))

    builder = StateGraph(ClawState)
    builder.add_node("llm", _llm_node(model_with_tools))
    builder.add_node("tools", _tool_node)
    builder.add_edge(START, "llm")
    builder.add_conditional_edges(
        "llm",
        _should_continue,
        {"tools": "tools", END: END},
    )
    builder.add_edge("tools", "llm")
    return builder.compile(checkpointer=checkpointer.saver)


_SUBGRAPH_TEMPLATES: dict[str, Callable[..., CompiledStateGraph]] = {
    "react": _compile_react_subgraph,
}


def get_agent_subgraph(agent: Agent, model_config: dict, tool_specs: list[dict]) -> CompiledStateGraph:
    """按 Agent 的 template + 配置签名取/建子图（扁平签名：只依赖自己的配置）。"""
    template = agent.template or "react"
    sig = template + "|" + _signature(model_config, tool_specs)
    cached = _agent_subgraphs.get(agent.id)
    if cached is None or cached[0] != template or cached[1] != sig:
        build = _SUBGRAPH_TEMPLATES.get(template)
        if build is None:
            raise ValueError(f"未知子图模板: {template}")
        _agent_subgraphs[agent.id] = (template, sig, build(model_config, tool_specs))
    return _agent_subgraphs[agent.id][2]


# ---- ReAct 子图内部节点 ----
def _llm_node(model_with_tools: Runnable) -> Callable[[ClawState], dict[str, list[BaseMessage]]]:
    """LLM 节点：最新 persona 前置注入（不进历史，每轮最新），返回 AI 消息。"""
    def _fn(state: ClawState) -> dict:
        response = model_with_tools.invoke(
            [SystemMessage(content=state["persona"]), *state["messages"]]
        )
        return {"messages": [response]}
    return _fn


# 会 interrupt 的工具必须串行（同线程不支持并发 interrupt）：敏感工具经 approval_gate、
# spawn_subagent 经 batch barrier 都会在节点内 interrupt。混入 asyncio.gather 只会向上传播
# 第一个 GraphInterrupt、静默吞掉兄弟中断——第二个 spawn 的 batch 已 POST 进 backend 却从未
# 注册/露出，审批决策串线或永久挂起。
_INTERRUPTING_TOOLS = {"spawn_subagent"}


async def _tool_node(state: ClawState, config: RunnableConfig) -> dict:
    """工具节点：执行上一条 AI 消息的全部工具调用。

    - 非敏感且不会 interrupt 的工具 asyncio.gather 并发；
    - 会 interrupt 的工具（敏感工具 + spawn_subagent）逐个 await：审批 interrupt 需保证顺序，
      同线程不支持并发 interrupt。
    """
    from .tools.registry import execute_tool_call

    last_message = state["messages"][-1]
    assert isinstance(last_message, AIMessage)
    calls = last_message.tool_calls

    spec_by_name = {s["name"]: s for s in state.get("tool_specs", [])}
    serial = [
        c for c in calls
        if spec_by_name.get(c["name"], {}).get("is_sensitive") or c["name"] in _INTERRUPTING_TOOLS
    ]
    serial_ids = {c["id"] for c in serial}
    parallel = [c for c in calls if c["id"] not in serial_ids]

    results: dict[str, str] = {}
    for c in serial:  # 串行：审批 interrupt 顺序唯一
        results[c["id"]], _ = await execute_tool_call(c, state, config)
    if parallel:  # 并行：多个非敏感、非 interrupt 工具一次派发
        batch = await asyncio.gather(*(execute_tool_call(c, state, config) for c in parallel))
        for c, (res, _executed) in zip(parallel, batch):
            results[c["id"]] = res

    return {
        "messages": [
            ToolMessage(content=results[c["id"]], tool_call_id=c["id"])
            for c in calls
        ]
    }


def _should_continue(state: ClawState) -> str:
    last_message = state["messages"][-1]
    return "tools" if isinstance(last_message, AIMessage) and last_message.tool_calls else END


# ---- 固定主图 ----
def build_claw_graph():
    """固定图拓扑：prepare → router → executor → END。checkpointer 由 main.py 注入（与子图同一 saver）。"""
    builder = StateGraph(ClawState)
    builder.add_node("prepare", prepare_node)
    builder.add_node("router", router_node)
    builder.add_node("executor", executor_node)
    builder.add_edge(START, "prepare")
    builder.add_edge("prepare", "router")
    builder.add_edge("router", "executor")
    builder.add_edge("executor", END)
    return builder


def _assemble_agent(agent: Agent) -> dict:
    """Agent 实体 → State 增量：人格 + 模型参数 + 工具清单。"""
    from .db import get_agent_files, get_model_config, get_tools
    from .persona import build_system_message
    from .tools.registry import build_tool_specs

    agent_files = get_agent_files(agent.id)
    cfg = get_model_config(agent.base_model)
    return {
        "agent_id": agent.id,
        "persona": build_system_message(agent, agent_files),
        "model_config": {
            "model_name": cfg.model_name,
            "endpoint": cfg.endpoint,
            "api_key": cfg.api_key,
            "temperature": agent.temperature,
            "max_tokens": agent.max_tokens,
        },
        "tool_specs": build_tool_specs(get_tools()),
    }


def prepare_node(state: ClawState) -> dict:
    """首节点：请求带 agent_id/alias → 解析并组装；否则交 router。

    归属校验（claw-boundary）：get_agent_by_id 只按 id+status 查、不校验归属，必须补
    claw_id/user_id 约束，防止跨 Claw/跨用户越权驱动他人 Agent（IDOR：泄露其 persona/
    system_prompt/model api_key/文件并盗用额度）。与 call_agent 的边界校验对齐。
    """
    from .config import settings
    from .db import get_agent_by_alias, get_agent_by_id

    if state.get("agent_id"):
        agent_id = state["agent_id"]
        assert agent_id is not None, "agent_id 必须在 prepare 阶段已解析"
        agent = get_agent_by_id(agent_id)
    elif state.get("alias"):
        agent = get_agent_by_alias(state["user_id"], state["alias"])
    else:
        return {}
    if agent is None:
        return {}
    if agent.claw_id != settings.claw_id or agent.user_id != state.get("user_id"):
        return {}  # 越权/跨 Claw：不组装，executor 兜底返回「未能确定要执行的 Agent」
    return _assemble_agent(agent)


async def executor_node(state: ClawState, config: RunnableConfig) -> dict:
    """executor：解析 Agent，取/建子图并用 astream 执行，只回传最终 AI 回复（工具中间消息不入历史）。

    config 传给子图：审批 interrupt 的挂起/恢复依赖同一个 thread_id。
    - 用 astream(stream_mode=["updates","custom"]) 而非 invoke：让子图内 emit_event 的 live 过程帧
      （工具/子agent/审批）经 custom 通道透传到主图 custom 流，实现实时过程卡。
    - 子图 __interrupt__ 在 astream 里被吞成帧（不向上抛），需检测后重新 interrupt() 把挂起透传给主图。
    """
    from langgraph.types import interrupt

    from .config import settings
    from .db import get_agent_by_id

    agent_id = state.get("agent_id")
    model_config = state.get("model_config")
    tool_specs = state.get("tool_specs")
    if agent_id is None or model_config is None or tool_specs is None:
        return {"messages": [AIMessage(content="未能确定要执行的 Agent 或其上下文，请稍后重试。")]}

    agent = get_agent_by_id(agent_id)
    if agent is None:
        return {"messages": [AIMessage(content="Agent 不存在或已停用。")]}
    if agent.claw_id != settings.claw_id or agent.user_id != state.get("user_id"):
        return {"messages": [AIMessage(content="Agent 不属于当前 Claw 或当前用户，已拒绝执行。")]}

    subgraph = get_agent_subgraph(agent, model_config, tool_specs)
    async for mode, chunk in subgraph.astream(dict(state), config=config, stream_mode=["updates", "custom"]):
        if mode == "custom":
            _forward_main_live(chunk)
        elif mode == "updates" and "__interrupt__" in chunk:
            for itr in chunk["__interrupt__"]:
                interrupt(itr.value)  # 子图挂起 → 主图层面重新挂起（透传同一 payload/request_id）

    final_state = await subgraph.aget_state(config)
    messages = final_state.values.get("messages", []) if final_state.values else []
    if not messages:
        return {"messages": [AIMessage(content="（子 Agent 未产出消息）")]}
    return {"messages": [messages[-1]]}


def _forward_main_live(chunk) -> None:
    """把子图的 custom 过程帧透传到主图 custom 流（live 实时过程卡）。"""
    try:
        from langgraph.config import get_stream_writer

        writer = get_stream_writer()
        if writer is not None:
            writer(chunk)
    except Exception:
        pass  # 脱离流上下文时 no-op；落盘仍由 emit_event 保证