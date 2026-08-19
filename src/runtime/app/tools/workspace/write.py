"""沙箱写文件工具：write —— 写入或整体覆盖 /workspace 内文件（父目录自动创建）。

定位：Agent 在沙箱落盘产物的主写入口。沙箱即本 Claw Pod 的 /workspace（PVC），
路径经 path_guard 约束不可逸出。整体覆盖语义：会清空原内容，
需在原文基础上增改时应先 read 再拼接，或改用 append。默认敏感，调用前经审批门。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve, _fmt_size


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def write(path: str, content: str = "", *, config: RunnableConfig) -> str:
    """写入或覆盖工作区内文件（父目录不存在时自动创建）。

    本工具是「整体覆盖」：目标已存在会被完全替换。要保留原内容再改，
    先用 read 读回全文、在内存拼好再 write；只往末尾续写则用 append。

    Args:
        path: 相对 /workspace 的文件路径。
        content: 要写入的完整内容（可为空字符串创建空文件）。
    """
    target = _resolve(path)
    try:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            f.write(content)
    except (PermissionError, OSError) as e:
        return f"写入失败：{e}"
    return f"已写入 {path}（{_fmt_size(len(content.encode('utf-8')))}）"
