"""人格写工具：update_persona —— 向 Agent 人格文件追加修订（写 backend 持久化，本 Pod 不落盘）。

定位：与 read_persona 配对的写侧。人格文件（SOUL/IDENTITY/AGENTS）是 Agent 行为基线，
本 Pod 只读、不直接写 OSS，追加内容经 backend 程序通道落库持久化。
修改前应先 read_persona 拿原文格式基线，再基于真实内容追加；单次上限 16KB。默认敏感，经审批门。
"""
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool, get_state


_ALLOWED_FILES = {"SOUL.md", "IDENTITY.md", "AGENTS.md"}
_CONTENT_LIMIT = 16 * 1024  # 16KB


@register_tool(sensitive=True)  # 修改人格影响 Agent 行为，默认需审批
@tool
async def update_persona(file_name: str, content: str, *, config: RunnableConfig) -> str:
    """向 Agent 人格文件追加内容（非覆盖），写 backend 持久化。

    Args:
        file_name: 目标人格文件，仅允许 SOUL.md / IDENTITY.md / AGENTS.md。
        content: 要追加的内容，上限 16KB。
    """
    state = get_state(config)
    if file_name not in _ALLOWED_FILES:
        return f"仅允许修改 {sorted(_ALLOWED_FILES)}；收到 {file_name!r}。"
    if len(content.encode("utf-8")) > _CONTENT_LIMIT:
        return f"内容超长（上限 {_CONTENT_LIMIT // 1024}KB），已拒绝。"
    try:
        from ...http import post_multipart

        await post_multipart(
            "/tools/agent-files",
            fields={"agent_id": str(state.get("agent_id")), "action": "append"},
            files={"file": (file_name, content.encode("utf-8"))},
        )
        return f"已追加到 {file_name}。"
    except Exception as e:
        return f"人格更新失败：{e}"