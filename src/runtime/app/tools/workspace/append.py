"""沙箱追加工具：append —— 向 /workspace 内文件末尾追加内容（不覆盖）。

定位：write 的增量补充版。沙箱即本 Claw Pod 的 /workspace（PVC），路径经 path_guard
约束。适合写日志、累积结果等只增不改的场景；文件不存在会新建，父目录自动创建。
默认敏感，调用前经审批门。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def append(path: str, content: str, *, config: RunnableConfig) -> str:
    """向工作区内文件末尾追加内容（自动补换行分隔）。

    只往末尾续写时用本工具，无需先 read 全文，也不会覆盖已有内容；
    要整体重写或替换中间片段则用 write。追加内容末尾会确保有换行。

    Args:
        path: 相对 /workspace 的文件路径。
        content: 要追加的内容。
    """
    target = _resolve(path)
    try:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "a", encoding="utf-8") as f:
            f.write(content if content.endswith("\n") else content + "\n")
    except (PermissionError, OSError) as e:
        return f"追加失败：{e}"
    return f"已追加到 {path}"
