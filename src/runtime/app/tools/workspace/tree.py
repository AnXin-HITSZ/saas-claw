"""沙箱目录树工具：tree —— 以树形展示 /workspace 目录结构（限深防爆）。

定位：一次性看清沙箱工作区的层级布局。沙箱即本 Claw Pod 的 /workspace（PVC），
路径经 path_guard 约束。深度上限 5、默认 2，避免大目录输出过长撑爆上下文。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve


@register_tool()  # 非敏感：只读列举
@tool
async def tree(path: str = ".", max_depth: int = 2, *, config: RunnableConfig) -> str:
    """以树形结构展示工作区目录（限制深度，避免超长输出）。

    刚进入一个陌生工作区、想快速掌握整体结构时用本工具；只看某一层的条目
    详情（大小/权限）用 ls。深度越大输出越长，按需调小 max_depth。

    Args:
        path: 相对 /workspace 的起始目录，默认当前目录（.）。
        max_depth: 最大递归深度（1~5，默认 2），防止大目录输出爆炸。
    """
    depth = max(1, min(int(max_depth), 5))  # 防御性裁剪
    root = _resolve(path)
    if not os.path.isdir(root):
        return f"不是目录：{path}"

    out: list[str] = []

    def walk(current: str, rel: str, level: int) -> None:
        if level > depth:
            return
        try:
            children = sorted(os.scandir(current), key=lambda e: e.name)
        except (PermissionError, FileNotFoundError, OSError):
            return
        last = len(children) - 1
        for i, e in enumerate(children):
            prefix = "    " * (level - 1) + ("└── " if i == last else "├── ")
            out.append(prefix + e.name + ("/" if e.is_dir() else ""))
            if e.is_dir() and level < depth:
                walk(e.path, rel, level + 1)

    out.append("/workspace/")
    walk(root, "", 1)
    return "\n".join(out) if len(out) > 1 else f"（目录 {path} 为空）"
