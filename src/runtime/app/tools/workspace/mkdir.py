"""沙箱建目录工具：mkdir —— 在 /workspace 内创建目录（含父目录，幂等）。

定位：为后续 write 预先准备目录结构。沙箱即本 Claw Pod 的 /workspace（PVC），
路径经 path_guard 约束。行为等价 mkdir -p：中间目录一并创建，已存在不报错。
默认敏感，调用前经审批门。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def mkdir(path: str, *, config: RunnableConfig) -> str:
    """创建工作区内的目录（含父目录，已存在不报错）。

    write 会自动补建文件所在父目录，故仅在需要预先建立空目录结构时才用本工具。
    行为等价 mkdir -p，逐级创建、幂等。

    Args:
        path: 相对 /workspace 的目录路径。
    """
    target = _resolve(path)
    try:
        os.makedirs(target, exist_ok=True)
    except (PermissionError, OSError) as e:
        return f"创建目录失败：{e}"
    return f"已创建目录 {path}"
