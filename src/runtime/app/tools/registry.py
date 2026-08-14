"""工具基础设施：@tool 契约唯一来源，注册表 + 执行入口 + 规格构建。

架构约定：
- 契约唯一来源：name/description/parameters 由 @tool 函数自动生成（函数签名→parameters，
  docstring→description）；DB tool 表不再提供契约。
- DB tool 表职责收窄：启用清单（status=1）+ 审批 id + 可选敏感度覆盖；与 @tool 靠 name 对齐。
- 工具合一：一个工具 = 一个 @tool 函数（即 handler），state 经 config.configurable._state 传递，
  函数体内 get_state(config) 取回；执行约定 tool.ainvoke(args, config=with_state(config, state))。
- 敏感工具走审批门（approval_gate），非敏感直接执行。
"""
from typing import Callable

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import BaseTool

from ..db import Tool
from ..state import ClawState


_TOOL_REGISTRY: dict[str, tuple[BaseTool, bool]] = {}
"""注册表：工具名 → (@tool 实例, 代码声明敏感度)。工具域模块 import 时由 @register_tool 填充。"""

_AGENT_TOOLS = {"call_agent"}
"""工具类型预留：走动态 Agent 调用的工具名集合，其余归 function。"""


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


async def execute_tool_call(call: dict, state: ClawState, config: RunnableConfig) -> str:
    """执行一条工具调用：按 name 查注册表；敏感走审批门，否则直接执行。"""
    name = call["name"]
    args = call.get("args", {})
    entry = _TOOL_REGISTRY.get(name)
    if entry is None:
        return f"工具 {name} 未在 runtime 实现。"
    tool_fn, declared_sensitive = entry
    spec = _spec_by_name(state.get("tool_specs", []), name)
    if spec is not None and spec.get("is_sensitive"):
        from .approval import approval_gate  # 延迟 import：避免与 approval 循环依赖
        return await approval_gate(state, name, args, spec["id"], config, tool_fn)
    return await tool_fn.ainvoke(args, config=with_state(config, state))


def _spec_by_name(tool_specs: list[dict], name: str) -> dict | None:
    for spec in tool_specs:
        if spec["name"] == name:
            return spec
    return None


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