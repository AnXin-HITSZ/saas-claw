"""人格组装器：每轮 invoke 首节点，从 OSS 组装 system message。

注入顺序：SOUL.md → IDENTITY.md → AGENTS.md → system_prompt，
越靠后行为约束力越强。file_hash 命中则复用，避免每轮打 OSS。
"""
from .db import Agent, AgentFile
from .oss import read_object_text


# 固定注入顺序（文档约定，可扩展）
PERSONA_ORDER = ["SOUL.md", "IDENTITY.md", "AGENTS.md"]

# file_hash 缓存：agent_id → {file_name: (file_hash, content)}
_persona_cache: dict[int, dict[str, tuple[str | None, str]]] = {}


def _read_persona_file(f: AgentFile) -> str | None:
    """读单个人格文件，hash 未变则复用缓存"""
    files = _persona_cache.setdefault(f.agent_id, {})
    hit = files.get(f.file_name)
    if hit is not None and hit[0] == f.file_hash:
        return hit[1]
    content = read_object_text(f.file_url)
    files[f.file_name] = (f.file_hash, content)
    return content


def build_system_message(agent: Agent, agent_files: list[AgentFile]) -> str:
    """按序拼接 SOUL → IDENTITY → AGENTS → system_prompt，返回完整 system message"""
    by_name = {f.file_name: f for f in agent_files}
    sections: list[str] = []
    for name in PERSONA_ORDER:
        f = by_name.get(name)
        if f is not None:
            content = _read_persona_file(f)
            if content:
                sections.append(content.strip())
    if agent.system_prompt:
        sections.append(agent.system_prompt.strip())
    return "\n\n".join(sections)