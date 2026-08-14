"""LangGraph State：Claw Pod 会话级全局状态。"""
from typing import Annotated, Any, TypedDict

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages


class ClawState(TypedDict, total=False):
    """对话状态。

    注意：checkpointer 会把整个 State 序列化存进 Redis，因此这里
    只放可序列化的原始类型（int/str/dict/list/BaseMessage），
    不放 SQLAlchemy 模型对象（Agent/Tool...）。
    """
    messages: Annotated[list[BaseMessage], add_messages]
    user_id: int  # 请求方用户（backend API Key 身份一致）
    conversation_id: str  # 会话标识（thread_id 组成，多会话区分）
    alias: str  # 请求显式指定 Agent 别名
    agent_id: int | None  # 解析结果；路由节点兜底
    persona: str  # 本轮最新人格（LLM 调用前注入，不进历史）
    model_config: dict[str, Any]  # 模型参数物化（含 temperature/max_tokens）
    tool_specs: list[dict[str, Any]]  # 工具清单（含 id，审批要 toolId）