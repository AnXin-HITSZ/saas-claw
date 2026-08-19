from .path_guard import resolve_inside


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