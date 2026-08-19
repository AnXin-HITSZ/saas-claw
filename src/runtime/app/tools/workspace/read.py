"""沙箱读文件工具：read —— 读取 /workspace 内文本文件全文（UTF-8，坏字节替换）。

定位：Agent 查看沙箱文件内容的工具。沙箱即本 Claw Pod 的 /workspace（PVC），
路径经 path_guard 约束不可逸出。仅适合文本；二进制文件应改用 info 看大小，
避免把乱码灌进上下文。
"""
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve


@register_tool()  # 非敏感：读操作
@tool
async def read(path: str, *, config: RunnableConfig) -> str:
    """读取工作区内文本文件的完整内容。

    改写某个文件（write 覆盖）前，通常先用本工具读回原文再在其上拼接，
    而不是凭记忆重写。二进制或超大文件请先用 info 判断，勿直接 read。

    Args:
        path: 相对 /workspace 的文件路径。
    """
    target = _resolve(path)
    try:
        with open(target, "r", encoding="utf-8", errors="replace") as f:
            return f.read() or "（文件为空）"
    except FileNotFoundError:
        return f"文件不存在：{path}"
    except IsADirectoryError:
        return f"是目录，不是文件：{path}"
    except PermissionError:
        return f"无权限读取：{path}"
