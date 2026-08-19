"""沙箱路径守卫：把一切文件操作约束在 /workspace/ 内，禁止经 ../ 、符号链接、绝对路径逸出。

原则：解析（resolve）后再校验（check），不做「仅去前缀」的脆皮校验。
- os.path.realpath 会把 ..、符号链接、相对路径全部归一到真实绝对路径。
- 校验点在 realpath 之后，符号链接即使指到 /workspace 外也会被拒。
- 根目录 /workspace 本身（realpath 后 == WORKSPACE_ROOT）是合法目标。
"""
import os

from ...config import settings

WORKSPACE_ROOT = os.path.realpath(settings.workspace_root)


def is_inside_workspace(raw_path: str) -> bool:
    """判断一个（未归一化的）路径是否落在工作区内。

    返回 False 的路径一定不能碰；返回 True 的路径仍要以 resolve_inside 的返回值为准，
    因为本函数不产生新路径，只回答「这条路径归一化后会不会越界」。
    """
    try:
        resolved = os.path.realpath(os.path.join(WORKSPACE_ROOT, raw_path))
    except (ValueError, TypeError, OSError):
        return False
    return _contained(resolved)


def resolve_inside(raw_path: str) -> str:
    """把用户给的路径归一到工作区内的绝对路径。

    归一化规则：绝对路径按原样、相对路径相对 WORKSPACE_ROOT 解析（均会触发 realpath 去 ..），
    拒绝任何最终落在工作区外（含越界符号链接）的路径；工作区根本身合法。
    返回的路径保证在工作区内，可直接用于 open / os 操作。
    """
    resolved = os.path.realpath(os.path.join(WORKSPACE_ROOT, raw_path))
    if not _contained(resolved):
        raise ValueError(
            f"路径超出工作区：{raw_path!r} → {resolved}（沙箱仅允许 {WORKSPACE_ROOT} 内）"
        )
    return resolved


def _contained(resolved_abs: str) -> bool:
    """前缀容器校验：realpath 之后的绝对路径是否位于 WORKSPACE_ROOT 之下（含根本身）。

    注意比较前给 WORKSPACE_ROOT 加 os.sep，避免 /workspace2 这类前缀误判。
    """
    return resolved_abs == WORKSPACE_ROOT or resolved_abs.startswith(WORKSPACE_ROOT + os.sep)
