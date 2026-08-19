"""工具域注册入口：显式 import 各功能域模块，触发 @register_tool 注册进 _TOOL_REGISTRY。

新增功能域（如 files、shell）时在此追加 import 即可；模块级注册副作用在 import 时生效。
"""
from . import agent, persona, workspace  # noqa: F401