"""沙箱列目录工具：ls —— 列出 /workspace 目录条目（权限/大小/时间一览）。

定位：Agent 探索沙箱工作区的第一入口。沙箱即本 Claw Pod 挂载的 /workspace（PVC），
路径经 path_guard 归一并约束，越界即拒。输出目录优先、同类按名排序，
让 Agent 在动手 read/write 前先看清目录结构。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .common import _resolve, _fmt_perm


@register_tool()  # 非敏感：读操作
@tool
async def ls(path: str = ".", *, config: RunnableConfig) -> str:
    """列出工作区目录内容（目录、大小、修改时间、权限）。

    动手读写某个文件前，先用本工具确认它确实存在、是文件还是目录；
    要递归看层级结构用 tree，要单条目详情用 info。目录名以 / 结尾便于区分。

    Args:
        path: 相对 /workspace 的目录路径，默认当前目录（.）。
    """
    target = _resolve(path)
    try:
        entries = sorted(os.scandir(target), key=lambda e: (not e.is_dir(), e.name))
    except FileNotFoundError:
        return f"目录不存在：{path}"
    except NotADirectoryError:
        return f"不是目录：{path}"
    except PermissionError:
        return f"无权限访问：{path}"

    if not entries:
        return f"（目录 {path} 为空）"

    from datetime import datetime

    lines = []
    for e in entries:
        try:
            st = e.stat(follow_symlinks=True)
        except (FileNotFoundError, OSError):
            continue  # 条目瞬时消失（如并发删除），跳过不报错
        name = e.name + ("/" if e.is_dir() else "")
        lines.append(
            f"{_fmt_perm(st.st_mode)} {st.st_size:>9} "
            f"{datetime.fromtimestamp(st.st_mtime):%m-%d %H:%M}  {name}"
        )
    return "\n".join(lines)
