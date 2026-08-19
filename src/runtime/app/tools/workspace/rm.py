"""沙箱删除工具：rm —— 删除 /workspace 内文件或空目录（不可恢复）。

定位：清理沙箱产物。沙箱即本 Claw Pod 的 /workspace（PVC），路径经 path_guard 约束。
安全取向：只删单个文件或空目录，拒绝删非空目录，避免一条指令抹掉整棵子树。
默认敏感，调用前经审批门。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def rm(path: str, *, config: RunnableConfig) -> str:
    """删除工作区内文件或空目录（删除不可恢复）。

    仅支持删单个文件或空目录；非空目录会被拒绝，需先逐项清空再删本目录，
    这是防止误删整棵内容的刻意约束。删除前请确认路径无误。

    Args:
        path: 相对 /workspace 的文件或目录路径。
    """
    target = _resolve(path)
    try:
        if os.path.isdir(target):
            os.rmdir(target)  # 仅空目录；非空抛 OSError，由下方统一兜底
            return f"已删除空目录 {path}"
        os.remove(target)
        return f"已删除文件 {path}"
    except OSError as e:
        if os.path.isdir(target) and not os.listdir(target):
            return f"删除失败：{e}"
        if os.path.isdir(target):
            return f"目录非空，拒绝删除（请先清空内容）：{path}"
        return f"删除失败：{e}"
