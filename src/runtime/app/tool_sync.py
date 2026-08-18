"""启动工具同步：把代码里的 @tool 注册表全量推到 backend tool 表（工具域自举，契约代码为王）。

对应 B4：runtime 是工具契约唯一来源；backend /tools/sync 按 name upsert——
新工具 status=1 自动启用，已存在工具只刷元数据（status 由平台控制，可随时下架）。
没有本同步，DB 无行的工具（read_persona / update_persona 等）不会被暴露给 LLM。
"""
import json

from langchain_core.utils.function_calling import convert_to_openai_tool

from .http import post_json
from .tools.registry import iter_registered_tools


async def sync_tools_to_backend() -> None:
    """把注册表所有工具全量同步到 backend；失败抛异常（启动 fail-fast）。

    每工具契约取自 @tool 自动生成（与 build_tool_specs 同源，同一 OpenAI Function Calling 契约）：
    name/description 直接读，parameters 走 convert_to_openai_tool。
    """
    items = []
    for name, tool_fn, sensitive in iter_registered_tools():
        contract = convert_to_openai_tool(tool_fn)["function"]
        items.append({
            "name": name,
            "description": contract.get("description"),
            "schema_json": json.dumps(contract.get("parameters", {}), ensure_ascii=False),
            "is_sensitive": 1 if sensitive else 0,
        })
    await post_json("/tools/sync", items)