"""沙箱元信息工具：info —— 查看 /workspace 内单个文件或目录的元信息。

定位：在不读取内容的前提下了解目标（类型/大小/修改时间/权限）。沙箱即本 Claw Pod
的 /workspace（PVC），路径经 path_guard 约束。面对可能是二进制或超大的文件时，
先 info 判断再决定是否 read。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve, _fmt_size, _fmt_perm


@register_tool()  # 非敏感：读操作
@tool
async def info(path: str, *, config: RunnableConfig) -> str:
    """查看工作区内文件或目录的元信息（类型、大小、修改时间、权限位）。

    read 一个来路不明的文件前，先用本工具确认它是文本且大小可控；
    要列目录下所有条目用 ls，要看整体目录树用 tree。

    Args:
        path: 相对 /workspace 的文件或目录路径。
    """
    target = _resolve(path)
    try:
        st = os.stat(target)
    except FileNotFoundError:
        return f"不存在：{path}"
    except PermissionError:
        return f"无权限访问：{path}"

    from datetime import datetime

    kind = "目录" if os.path.isdir(target) else "文件"
    return (
        f"{kind}：{path}\n"
        f"大小：{_fmt_size(st.st_size)}\n"
        f"修改：{datetime.fromtimestamp(st.st_mtime):%Y-%m-%d %H:%M:%S}\n"
        f"权限：{_fmt_perm(st.st_mode)}"
    )
