"""沙箱概览工具：workspace_info —— 查看 /workspace 根目录的整体概况。

定位：沙箱会话开始时的定位入口。沙箱即本 Claw Pod 的 /workspace（PVC），
本工具直接给出根路径、修改时间与一级子目录，让 Agent 一眼确认工作区位置与现状，
再决定用 ls/tree 深入。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .path_guard import WORKSPACE_ROOT


@register_tool()  # 非敏感：只读
@tool
async def workspace_info(*, config: RunnableConfig) -> str:
    """查看工作区根目录 /workspace 的概况（根路径、修改时间、一级子目录）。

    刚开始一段沙箱操作、还不清楚工作区里有什么时先调本工具定位；
    要看某个子目录的详细条目用 ls，要看多层结构用 tree。

    Args: 无
    """
    try:
        st = os.stat(WORKSPACE_ROOT)
        children = sorted(
            (e.name for e in os.scandir(WORKSPACE_ROOT)),
        )
    except (PermissionError, FileNotFoundError, OSError) as e:
        return f"工作区不可访问：{e}"
    from datetime import datetime

    lines = [
        f"工作区根：{WORKSPACE_ROOT}",
        f"修改时间：{datetime.fromtimestamp(st.st_mtime):%Y-%m-%d %H:%M:%S}",
    ]
    if children:
        lines.append("一级子目录：" + ", ".join(children))
    else:
        lines.append("（工作区为空）")
    return "\n".join(lines)
