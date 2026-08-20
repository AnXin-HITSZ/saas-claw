"""人格组装器：每轮 invoke 首节点，从 OSS 组装 system message + 人格初始化状态感知。

注入顺序：SOUL.md → IDENTITY.md → AGENTS.md → system_prompt，
越靠后行为约束力越强。file_hash 命中则复用，避免每轮打 OSS。

人格状态（build_system_message 返回，供 _assemble_agent 决定是否注入指令）：
- CONFIGURED：任一人格文件读出内容，或 system_prompt 非空 —— 已有身份基线，不注入指令；
- EMPTY：无人格文件且无 system_prompt —— 首次对话典型场景，Agent 应主动询问用户设定人格；
- UNREADABLE：配了人格文件但读取全部失败（OSS 不可达/凭证异常）—— 不询问不覆盖，
  如实告知用户稍后重试，避免把用户已有意图误判为未初始化。
"""
import logging
from enum import Enum

from .db import Agent, AgentFile
from .oss import read_object_text

logger = logging.getLogger(__name__)


# 固定注入顺序（文档约定，可扩展）
PERSONA_ORDER = ["SOUL.md", "IDENTITY.md", "AGENTS.md"]

# file_hash 缓存：agent_id → {file_name: (file_hash, content)}
_persona_cache: dict[int, dict[str, tuple[str | None, str]]] = {}


class PersonaStatus(Enum):
    CONFIGURED = "configured"
    EMPTY = "empty"
    UNREADABLE = "unreadable"


def _read_persona_file(f: AgentFile) -> str | None:
    """读单个人格文件，hash 未变则复用缓存；读取失败返回 None（不写缓存，下轮重试）。"""
    files = _persona_cache.setdefault(f.agent_id, {})
    hit = files.get(f.file_name)
    # file_hash 为 NULL 的存量行不参与缓存比对（None==None 恒等会让缓存永不失效）
    if hit is not None and f.file_hash is not None and hit[0] == f.file_hash:
        return hit[1]
    try:
        content = read_object_text(f.file_url)
    except Exception as exc:
        # OSS 未配好/不可达/凭证错误时不能裸抛：本函数在 prepare 首节点调用，异常会击穿整条
        # SSE 流。降级返回 None，build_system_message 的 `if content:` 守卫自然回退为仅
        # system_prompt。失败不写缓存，后续请求仍会重试。
        logger.warning("读取人格文件失败 agent_id=%s file=%s: %s", f.agent_id, f.file_name, exc)
        return None
    files[f.file_name] = (f.file_hash, content)
    return content


def build_system_message(agent: Agent, agent_files: list[AgentFile]) -> tuple[str, PersonaStatus]:
    """按序拼接 SOUL → IDENTITY → AGENTS → system_prompt，返回 (完整 system message, 人格状态)。

    状态按文件粒度聚合（对抗评审确认，避免多文件部分成功误判）：
    - 任一人格文件读出内容（any_ok）或 system_prompt 非空 → CONFIGURED；
    - 无 OK、无 system_prompt，且至少一个文件读取失败（any_failed）→ UNREADABLE（fail-closed：
      OSS 瞬态故障不诱导覆盖用户已有意图）；
    - 其余（无文件行 / 文件内容全空，且无 system_prompt）→ EMPTY。
    空文件（读到 ""）不算失败，OSS 可达性以「是否有任意一次成功读取」为准。
    """
    by_name = {f.file_name: f for f in agent_files}
    sections: list[str] = []
    any_ok = False
    any_failed = False
    for name in PERSONA_ORDER:
        f = by_name.get(name)
        if f is not None:
            content = _read_persona_file(f)
            if content and content.strip():
                sections.append(content.strip())
                any_ok = True
            elif content is None:
                any_failed = True  # 读取失败（OSS 不可达/凭证异常）；空白文件不算失败
    if agent.system_prompt and agent.system_prompt.strip():
        sections.append(agent.system_prompt.strip())

    has_system_prompt = bool(agent.system_prompt and agent.system_prompt.strip())
    if any_ok or has_system_prompt:
        status = PersonaStatus.CONFIGURED
    elif any_failed:
        status = PersonaStatus.UNREADABLE
    else:
        status = PersonaStatus.EMPTY
    return "\n\n".join(sections), status


def build_persona_directive(agent: Agent, status: PersonaStatus) -> str:
    """人格状态指令：非 CONFIGURED 时由 _assemble_agent 追加进 persona 串。

    每请求重算、配置好即消失；内插平台配置的身份字段（name/alias/description），
    让 Agent 能基于已知身份自介而不虚构。语言跟随产品默认（中文）。
    """
    name = agent.name or "本 Agent"
    parts = [f"名称「{name}」"]
    if agent.alias:
        parts.append(f"别名「{agent.alias}」")
    if agent.description and agent.description.strip():
        parts.append(f"简介「{agent.description.strip()}」")
    identity = "、".join(parts)

    if status == PersonaStatus.EMPTY:
        return (
            "【人格状态：未初始化】你当前尚未配置人格：没有人格文件（SOUL.md / IDENTITY.md / AGENTS.md），"
            "也没有 System Prompt，你的性格、语气、行为准则尚未定义。"
            f"你已知的基础身份来自平台配置：{identity}，可用这些做基础自我介绍，但不要自行虚构完整性格设定。"
            "在对话刚开始且合适时，主动询问用户想为你设定怎样的人格（性格特点、说话语气、擅长领域、行为偏好等），"
            "引导用户给出一段人格描述。若用户直接给出了人格设定（未等你提问）：不要重复提问，直接按用户设定的"
            "行事，并调用 update_persona 工具（operation=overwrite，写入 SOUL.md 或对应文件）持久化，"
            "此操作无需审批。若已问过且用户未给出设定，不要反复追问，正常执行用户任务即可。"
        )
    # UNREADABLE
    return (
        "【人格状态：人格文件读取异常】你配置了人格文件，但当前无法从存储读取内容"
        "（存储可能暂时不可达或凭证异常）。这不代表你没有已配置的人格：不要询问用户重新设定人格，"
        "也不要自行创建或覆盖人格文件（否则可能覆盖用户已有设定）。如实告知用户人格文件暂时不可读、"
        "建议稍后重试或检查平台配置，然后正常执行用户任务；存储恢复后本状态会自动消失。"
    )
