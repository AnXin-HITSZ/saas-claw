"""沙箱工具域：workspace 包。

实现对 Claw Pod 本地 /workspace 工作区（PVC）的常用 shell 文件操作。
- 沙箱边界：所有工具经 path_guard.resolve_inside 归一化并校验，禁止 ../、绝对路径、符号链接逸出。
- 执行位置：本 Pod 进程即运行中的 Claw Pod（runtime 与沙箱同 Pod、同 PVC），无需远程连接。
- 工具与 DB 解耦：@register_tool 即执行器；DB tool 表 status=1 才暴露给 LLM（管理员在工具页配置）。

包结构（与 agent/persona 域一致）：一个工具 = 一个子模块，公共辅助集中在 common.py，
路径守卫在 path_guard.py。本文件只做聚合 import——子模块被 import 时 @register_tool
的模块级副作用即完成注册。新增工具：加一个子模块文件，并在此追加 import。
"""
from . import (  # noqa: F401
    append,
    info,
    ls,
    mkdir,
    read,
    rm,
    tree,
    workspace_info,
    write,
)
