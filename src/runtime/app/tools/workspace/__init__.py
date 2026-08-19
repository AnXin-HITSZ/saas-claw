"""沙箱工具域：workspace 包。

实现对 Claw Pod 本地 /workspace 工作区（PVC）的常用 shell 文件操作。
- 沙箱边界：所有工具经 path_guard.resolve_inside 归一化并校验，禁止 ../、绝对路径、符号链接逸出。
- 执行位置：本 Pod 进程即运行中的 Claw Pod（runtime 与沙箱同 Pod、同 PVC），无需远程连接。
- 工具与 DB 解耦：@register_tool 即执行器；DB tool 表 status=1 才暴露给 LLM（管理员在工具页配置）。

注册约定：
    @register_tool(sensitive=False)   # 非敏感：写入类建议 sensitive=True，改由管理员在工具页确认
    @tool
    async def name(...) -> str: ...

新工具追加到本文件即可，import 即注册。
"""
import os

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool
from .path_guard import WORKSPACE_ROOT, resolve_inside


# ---------------- 内部辅助 ----------------

def _resolve(path: str) -> str:
    """归一化 + 沙箱校验，越界抛 ValueError。所有工具共用。"""
    return resolve_inside(path)


def _fmt_size(n: int) -> str:
    """字节 → 人类可读（B/KB/MB/GB，保留 1 位小数）。"""
    if n < 1024:
        return f"{n} B"
    for unit in ("KB", "MB", "GB"):
        n /= 1024
        if n < 1024:
            return f"{n:.1f} {unit}"
    return f"{n:.1f} TB"


def _fmt_perm(mode: int) -> str:
    """数值权限位 → rwx 字符串（-rw-r--r-- 风格，不含文件类型位）。"""
    bits = []
    for shift in (6, 3, 0):
        v = (mode >> shift) & 0o7
        bits.append("".join(ch if (v & (0o4 >> i)) else "-" for i, ch in enumerate("rwx")))
    return "-" + "".join(bits)


# ---------------- 只读工具 ----------------

@register_tool()  # 非敏感：读操作
@tool
async def ls(path: str = ".", *, config: RunnableConfig) -> str:
    """列出工作区目录内容。

    列出 /workspace 下的目录条目（目录、大小、修改时间、权限）。
    目录名以 / 结尾，文件按大小排序输出，便于快速浏览工作区。

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


@register_tool()  # 非敏感：读操作
@tool
async def read(path: str, *, config: RunnableConfig) -> str:
    """读取工作区内文本文件的完整内容。

    适用于代码、配置文件、日志等文本；二进制文件请用 info 查看大小而非 read。

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


@register_tool()  # 非敏感：读操作
@tool
async def info(path: str, *, config: RunnableConfig) -> str:
    """查看工作区内文件或目录的元信息。

    返回类型、大小、修改时间、权限位；路径不存在时给出根工作区的总览。

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


@register_tool()  # 非敏感：只读列举
@tool
async def tree(path: str = ".", max_depth: int = 2, *, config: RunnableConfig) -> str:
    """以树形结构展示工作区目录（限制深度，避免超长输出）。

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


# ---------------- 写入类工具 ----------------

@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def write(path: str, content: str = "", *, config: RunnableConfig) -> str:
    """写入或覆盖工作区内文件。

    创建新文件或整体覆盖已有文件；父目录不存在时自动创建。
    追加内容请先 read 再 write 拼接，或使用 append 工具。

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


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def append(path: str, content: str, *, config: RunnableConfig) -> str:
    """向工作区内文件追加内容（自动换行分隔）。

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


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def mkdir(path: str, *, config: RunnableConfig) -> str:
    """创建工作区内的目录（含父目录）。

    Args:
        path: 相对 /workspace 的目录路径。
    """
    target = _resolve(path)
    try:
        os.makedirs(target, exist_ok=True)
    except (PermissionError, OSError) as e:
        return f"创建目录失败：{e}"
    return f"已创建目录 {path}"


@register_tool(sensitive=True)  # 敏感：修改工作区内容
@tool
async def rm(path: str, *, config: RunnableConfig) -> str:
    """删除工作区内文件或空目录。

    目录仅支持删除空目录；非空目录请逐项删除（避免误删整棵内容）。
    删除不可恢复，操作前请确认。

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


# ---------------- 工作区总览（便捷入口） ----------------

@register_tool()  # 非敏感：只读
@tool
async def workspace_info(*, config: RunnableConfig) -> str:
    """查看工作区根目录 /workspace 的概况。

    返回根目录绝对路径、当前占用情况与一级子目录列表，用于沙箱会话开始时快速定位。

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
