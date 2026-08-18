"""人格读工具：read_persona —— 读取人格文件原始全文（保留格式基线）。

定位：system message 里的人格是 persona.py 剥了空白/合并后的内化版（strip + "\n\n".join），
丢失原始格式与章节结构。Agent 要追加/改写人格时，须先读原文拿格式基线，再 update_persona。
"""
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool, get_state


_ALLOWED_FILES = {"SOUL.md", "IDENTITY.md", "AGENTS.md"}


async def read_persona_content(agent_id: int, file_name: str) -> str | None:
    """读人格文件原始全文：DB 行定位 file_url → 直连 OSS 读原文。

    返回 None 表示文件不存在（区别于「存在但内容为空」）；相对路径与写权限由 backend 把守，
    读允许本 Pod 直连 OSS。读取异常向上抛，由调用方（两处 tool）兜底为提示文本。
    """
    from ...db import get_agent_files
    from ...oss import read_object_text

    for f in get_agent_files(agent_id):
        if f.file_name == file_name:
            return read_object_text(f.file_url)
    return None


@register_tool()  # 不敏感：内容本就每轮注入 system message，读自身无新增风险
@tool
async def read_persona(file_name: str, *, config: RunnableConfig) -> str:
    """读取 Agent 人格文件的原始全文（保留原始空白与章节结构）。

    修改人格（update_persona 的 find 参数）前应先调用本工具，
    确保基于原文件真实格式定位替换片段，而不是凭 system message 中已内化的版本。

    Args:
        file_name: 目标人格文件，仅允许 SOUL.md / IDENTITY.md / AGENTS.md。
    """
    state = get_state(config)
    if file_name not in _ALLOWED_FILES:
        return f"仅允许读取 {sorted(_ALLOWED_FILES)}；收到 {file_name!r}。"
    try:
        content = await read_persona_content(state.get("agent_id"), file_name)
        if content is None:
            return f"人格文件 {file_name} 不存在（尚未创建）。"
        return content or "(文件内容为空)"
    except Exception as e:
        return f"读取人格文件失败：{e}"