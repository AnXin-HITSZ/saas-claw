"""人格写工具：update_persona —— 全量覆盖 / find 定点替换，runtime 直写 OSS + 同步 agent_file。

非敏感（用户已确认去掉审批门）：写入仅限本 Agent 三份人格文件，白名单 + 归属校验收口权限面。
修改前应先调用 read_persona 读取原文格式基线；edit 模式内部会读真实文件匹配 find，机制上保证
「基于原文定点修改」，find 未命中则报错提示重读。
"""
import hashlib

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from ..registry import register_tool, get_state


_ALLOWED_FILES = {"SOUL.md", "IDENTITY.md", "AGENTS.md"}
_CONTENT_LIMIT = 16 * 1024  # 单次写入内容上限（overwrite 全文 / edit 替换片段）
_TOTAL_LIMIT = 64 * 1024    # 单文件总量上限（防 append/重复修改无界增长）


def _persona_key(agent_id: int, file_name: str) -> str:
    return f"agent/{agent_id}/{file_name}"


async def _persist(agent_id: int, file_name: str, content: str) -> str:
    """直写 OSS（agent/{id}/{file}）+ upsert agent_file 行（hash/size/type）。

    必须更新 file_hash：runtime 的 _persona_cache 按 hash 比对，不更新则人格修改不生效。
    """
    from ...oss import url_from_key, write_object_text

    total = len(content.encode("utf-8"))
    if total > _TOTAL_LIMIT:
        raise ValueError(f"人格文件过大（上限 {_TOTAL_LIMIT // 1024}KB），建议精简内容或拆到对应文件")
    key = _persona_key(agent_id, file_name)
    write_object_text(key, content)
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()

    from ...db import upsert_agent_file

    upsert_agent_file(
        agent_id=agent_id,
        file_name=file_name,
        file_url=url_from_key(key),
        file_size=total,
        file_hash=digest,
        file_type="md",
    )
    return key


@register_tool()  # 非敏感：用户已确认去掉人格写审批门（人格潜移默化演进，不打扰用户审批）
@tool
async def update_persona(
    file_name: str,
    operation: str = "overwrite",
    content: str | None = None,
    find: str | None = None,
    replace: str | None = None,
    *,
    config: RunnableConfig,
) -> str:
    """修改 Agent 人格文件（非敏感，直写 OSS）。修改前应先调用 read_persona 读取原文。

    Args:
        file_name: 目标人格文件，仅允许 SOUL.md / IDENTITY.md / AGENTS.md。
        operation: "overwrite" 全量覆盖（需 content）；"edit" 定点替换（需 find + replace）。
        content: overwrite 模式的完整新内容。
        find: edit 模式定位的原文片段（基于真实文件精确匹配，命中首个）。
        replace: edit 模式替换后的新片段。
    """
    if file_name not in _ALLOWED_FILES:
        return f"仅允许修改 {sorted(_ALLOWED_FILES)}；收到 {file_name!r}。"
    state = get_state(config)
    agent_id = state.get("agent_id")

    try:
        if operation == "overwrite":
            if not content or not content.strip():
                return "overwrite 模式必须提供 content（完整新内容，不能为空白）。"
            if len(content.encode("utf-8")) > _CONTENT_LIMIT:
                return f"内容超长（单次上限 {_CONTENT_LIMIT // 1024}KB），已拒绝。"
            await _persist(agent_id, file_name, content)
            return f"已全量覆盖 {file_name}（{len(content)} 字符）。"

        if operation == "edit":
            if not find or replace is None:
                return "edit 模式必须提供 find（原文片段）与 replace（新片段）。"
            if len(replace.encode("utf-8")) > _CONTENT_LIMIT:
                return f"替换内容超长（单次上限 {_CONTENT_LIMIT // 1024}KB），已拒绝。"
            # 机制兜底「先读再改」：基于真实文件内容定位 find，保证定点精度。
            from .read_persona import read_persona_content

            current = await read_persona_content(agent_id, file_name)
            if current is None:
                return f"人格文件 {file_name} 不存在，无法定点修改；请先用 overwrite 创建，或先 read_persona 确认。"
            idx = current.find(find)
            if idx < 0:
                return f"未在 {file_name} 中找到目标片段，请先调用 read_persona 获取原文后重试。"
            merged = current[:idx] + replace + current[idx + len(find):]
            await _persist(agent_id, file_name, merged)
            return f"已在 {file_name} 中定点替换 1 处（原文 {len(current)} 字符）。"

        return f"未知操作 {operation!r}，仅支持 overwrite / edit。"
    except Exception as exc:
        return f"人格更新失败：{exc}"
