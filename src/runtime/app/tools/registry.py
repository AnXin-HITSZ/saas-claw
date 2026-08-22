"""工具基础设施：@tool 契约唯一来源，注册表 + 执行入口 + 规格构建。

架构约定：
- 契约唯一来源：name/description/parameters 由 @tool 函数自动生成（函数签名→parameters，
  docstring→description）；DB tool 表不再提供契约。
- DB tool 表职责收窄：启用清单（status=1）+ 审批 id + 可选敏感度覆盖；与 @tool 靠 name 对齐。
- 工具合一：一个工具 = 一个 @tool 函数（即 handler），state 经 config.configurable._state 传递，
  函数体内 get_state(config) 取回；执行约定 tool.ainvoke(args, config=with_state(config, state))。
- 敏感工具走审批门（approval_gate），非敏感直接执行。
"""
import json
import logging
from typing import Callable

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import BaseTool
from langgraph.errors import GraphBubbleUp

from ..db import Tool
from ..state import ClawState

logger = logging.getLogger(__name__)


_TOOL_REGISTRY: dict[str, tuple[BaseTool, bool]] = {}
"""注册表：工具名 → (@tool 实例, 代码声明敏感度)。工具域模块 import 时由 @register_tool 填充。"""

_AGENT_TOOLS = {"call_agent", "spawn_subagent"}
"""工具类型预留：走动态 Agent 调用的工具名集合（call_agent 调单个、spawn_subagent 并行派发），其余归 function。"""

_TOOL_RESULT_CACHE: dict[tuple[str, str], str] = {}
"""工具结果重放缓存：(thread_id, tool_call_id) → 结果文本。

LangGraph 审批 resume 会整节点重跑：中断点之前已完成的工具调用会再次进入
execute_tool_call。命中本缓存则直接回放结果，不重复执行、不重复发 tool_start/end 事件，
消除敏感工具/有副作用工具的二次执行。key 含 thread_id（子图独立 sub-thread 互不串扰）。

生命周期（对抗审查 confirmed）：条目只在「写入 → 最终 resume 回放」之间存活。多轮审批时
同一工具会被多次回放，因此【不能命中即删】；而 FIFO 淘汰可能把正在挂起等待审批的 thread
的活跃条目挤掉，resume 时误判未缓存 → 敏感工具二次执行。修复：
- LRU 淘汰：命中时 move-to-end，活跃条目（最近写入/回放）天然远离淘汰边界；
- 主动回收：run 无中断完成后 purge_thread_cache 清掉该 thread 全部条目（绝大多数死条目，
  显著缩小活跃集合）。注意挂起结束的 run 不能 purge——resume 仍需重放这些条目。
"""
_TOOL_RESULT_CACHE_MAX = 4096


def _tool_result_cache_trim() -> None:
    """LRU 裁剪：超出上限时淘汰最久未用的条目（dict 保序：pop(iter) 即最旧）。"""
    while len(_TOOL_RESULT_CACHE) > _TOOL_RESULT_CACHE_MAX:
        _TOOL_RESULT_CACHE.pop(next(iter(_TOOL_RESULT_CACHE)))


def purge_thread_cache(thread_id: str) -> None:
    """run 无中断完成后清理该 thread 的工具结果缓存条目（生命周期终点的主动回收）。

    由 main 的 gen() finally 调用：正常完成 / 审批全部恢复完成后，该 thread 不再有重放需求，
    条目可安全释放。仅用于「没有再次挂起」的完成路径；挂起结束的 run 不调本函数。
    """
    for key in [k for k in _TOOL_RESULT_CACHE if k[0] == thread_id]:
        _TOOL_RESULT_CACHE.pop(key, None)


def register_tool(*, sensitive: bool = False) -> Callable[[BaseTool], BaseTool]:
    """装饰器工厂：@register_tool(sensitive=True) 叠加在 @tool 外层，定义即注册。

    用法：
        @register_tool(sensitive=True)
        @tool
        async def write_file(path: str, content: str, *, config: RunnableConfig) -> str: ...

    展开：tool_fn = register_tool(sensitive=True)(tool(原函数))
    """
    def decorator(tool_fn: BaseTool) -> BaseTool:
        _TOOL_REGISTRY[tool_fn.name] = (tool_fn, sensitive)
        return tool_fn

    return decorator


def with_state(config: RunnableConfig, state: ClawState) -> RunnableConfig:
    """把 ClawState 挂进 config.configurable，随 ainvoke 传给 @tool 函数体。"""
    return {**config, "configurable": {**config.get("configurable", {}), "_state": state}}


def get_state(config: RunnableConfig) -> ClawState:
    """@tool 函数体取回 ClawState。"""
    return config.get("configurable", {})["_state"]


def tool_type(name: str) -> str:
    """工具类型预留——function 普通函数、agent 动态 Agent 调用。"""
    return "agent" if name in _AGENT_TOOLS else "function"


async def execute_tool_call(call: dict, state: ClawState, config: RunnableConfig) -> tuple[str, bool]:
    """执行一条工具调用：按 name 查注册表；敏感走审批门，否则直接执行。

    返回 (结果文本, 是否真实执行)：
    - 结果缓存命中（审批 resume 重放已完成的工具）→ 回放缓存值 + False，不重复执行/不重复发事件；
    - 首次执行 → 真实执行 + True。
    未实现的工具也返回 True（不缓存），避免把它当成可回放结果而吞掉提示。
    """
    name = call["name"]
    tool_call_id = call["id"]
    args = call.get("args", {})
    thread_id = config.get("configurable", {}).get("thread_id", "")

    entry = _TOOL_REGISTRY.get(name)
    if entry is None:
        return f"工具 {name} 未在 runtime 实现。", True

    # 结果重放缓存：命中 → 直接回放，跳过执行与事件（副作用不二次发生）
    # LRU：命中即 move-to-end（活跃条目远离淘汰边界，避免挂起期间被挤掉导致二次执行）
    cache_key = (thread_id, tool_call_id)
    if cache_key in _TOOL_RESULT_CACHE:
        result = _TOOL_RESULT_CACHE.pop(cache_key)
        _TOOL_RESULT_CACHE[cache_key] = result
        return result, False

    tool_fn, declared_sensitive = entry
    spec = _spec_by_name(state.get("tool_specs", []), name)
    is_sensitive = spec is not None and spec.get("is_sensitive")

    # 事件埋点：tool_start / tool_end（独立 span 挂在当前 agent span 下；dedup_key 由 tool_call_id
    # 派生 → 重放同 key 只落一条 trace，与结果缓存双保险）
    from ..trace import EVT_TOOL_END, EVT_TOOL_START, emit_event

    tool_span = f"tool:{tool_call_id}"
    await emit_event(
        state, EVT_TOOL_START, {"tool": name, "args_summary": _summarize_text(json.dumps(args, ensure_ascii=False, default=str))},
        config=config, span_id=tool_span, dedup_key=f"{tool_call_id}:tool_start",
    )

    try:
        # 注入确定性 _tool_call_id：供 call_agent/spawn_subagent 派生稳定 child span / 注册表 key
        # （resume 整节点重跑时 tool_call_id 不变，据此可 rehydrate 容器会话）。敏感工具经
        # approval_gate 执行同样需要——call_agent 透传子图审批依赖它定位跨 resume 的会话。
        tool_config = with_state(config, state)
        tool_config = {**tool_config, "configurable": {**tool_config["configurable"], "_tool_call_id": tool_call_id}}
        if is_sensitive:
            from .approval import approval_gate  # 延迟 import：避免与 approval 循环依赖
            result = await approval_gate(state, name, args, spec["id"], tool_config, tool_fn, tool_call_id)
        else:
            result = await tool_fn.ainvoke(args, config=tool_config)
    except GraphBubbleUp:
        raise  # langgraph 控制流异常（审批挂起 interrupt / NodeInterrupt / 递归上限）必须透传，
        # 转文本会吞掉挂起/终止机制；工具本体正常不会抛这类异常。
    except Exception as exc:
        # 工具内部异常（workspace 路径越界 ValueError / IO 失败 / 子 Agent 编译失败等）不击穿整轮：
        # 转成文本结果交还 LLM 自纠（与 call_agent 子 Agent 失败处理对齐），trace 照常记 tool_end。
        logger.warning("工具执行失败 tool=%s: %s", name, exc, exc_info=True)
        result = f"工具执行失败：{exc}"

    await emit_event(
        state, EVT_TOOL_END, {"tool": name, "result_summary": _summarize_text(result)},
        config=config, span_id=tool_span, dedup_key=f"{tool_call_id}:tool_end",
    )

    _TOOL_RESULT_CACHE[cache_key] = result
    _tool_result_cache_trim()
    return result, True


def _spec_by_name(tool_specs: list[dict], name: str) -> dict | None:
    for spec in tool_specs:
        if spec["name"] == name:
            return spec
    return None


def _summarize_text(text: str, limit: int = 200) -> str:
    """工具入参/结果摘要：trace 事件展示用，超长截断。"""
    if not text:
        return ""
    return text[:limit] + "……" if len(text) > limit else text


def build_tool_specs(tools: list[Tool]) -> list[dict]:
    """契约唯一来源是 @tool 注册表；DB 只提供启用清单 + 审批 id + 可选敏感度覆盖。"""
    from langchain_core.utils.function_calling import convert_to_openai_tool

    specs = []
    for t in tools:  # status=1 的启用工具
        entry = _TOOL_REGISTRY.get(t.name)
        if entry is None:
            continue  # 未实现：不暴露给 LLM
        tool_fn, declared_sensitive = entry
        contract = convert_to_openai_tool(tool_fn)["function"]
        specs.append({
            "id": t.id,
            "type": tool_type(t.name),
            "name": t.name,
            "description": contract["description"],
            "parameters": contract["parameters"],
            "is_sensitive": bool(t.is_sensitive) or declared_sensitive,
        })
    return specs


def materialize_tools(tool_specs: list[dict]) -> list[dict]:
    """剥掉 id/type/is_sensitive 等附加字段，只留 OpenAI Function Calling 契约三要素。"""
    return [
        {"name": s["name"], "description": s["description"], "parameters": s["parameters"]}
        for s in tool_specs
    ]