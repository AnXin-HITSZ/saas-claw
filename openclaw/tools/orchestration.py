"""Orchestration tools: discover, request-install, call-agent.

These tools let an Agent discover and interact with other Agents within the
same Claw. All business logic is delegated to the Spring Backend
Conversation Orchestrator — the FastAPI Runtime only executes tool calls.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from openclaw.tools.types import (
    ToolDefinition,
    ToolMetadata,
)

LOGGER = logging.getLogger(__name__)

_SPRING_BASE_URL = os.environ.get("PYCLAW_SPRING_BASE_URL", "http://localhost:8080")


def _spring_url(path: str) -> str:
    return _SPRING_BASE_URL.rstrip("/") + path


def _post_json(path: str, payload: dict[str, Any]) -> dict[str, Any]:
    try:
        import httpx  # type: ignore[import]
    except ImportError:
        return {"error": "httpx not available; install 'httpx' to use orchestration tools"}

    url = _spring_url(path)
    try:
        resp = httpx.post(url, json=payload, timeout=60.0)
        resp.raise_for_status()
        return resp.json()  # type: ignore[no-any-return]
    except Exception as exc:
        LOGGER.warning("Orchestration call to %s failed: %s", url, exc)
        return {"error": str(exc)}


# ---- Tool: discover_agents ----


def _exec_discover_agents(claw_id: str, query: str = "", **_: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"clawId": claw_id, "query": query}
    result = _post_json("/api/orchestrator/agents/discover", payload)
    if isinstance(result, dict) and result.get("error"):
        return {"status": "error", "message": result["error"]}
    return {"status": "ok", "candidates": result if isinstance(result, list) else []}


def create_discover_agents_tool() -> ToolDefinition:
    return ToolDefinition(
        name="discover_agents",
        label="发现 Agent",
        description="搜索当前 Claw 可见的已发布 Agent Package。根据关键字、能力和所需 Profile 筛选候选项。",
        input_schema={
            "type": "object",
            "properties": {
                "claw_id": {"type": "string", "description": "当前 Claw ID"},
                "query": {"type": "string", "description": "搜索关键字（可选）"},
            },
            "required": ["claw_id"],
        },
        tool_metadata=ToolMetadata(
            risk="low",
            readonly=True,
            execution_scope="claw_sandbox",
            tags=["orchestration", "agent-discovery"],
        ),
        executor=_exec_discover_agents,
    )


# ---- Tool: request_agent_install ----


def _exec_request_agent_install(
    claw_id: str,
    package_version_id: str,
    requesting_agent_instance_id: str = "",
    reason: str = "",
    **_: Any,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "clawId": claw_id,
        "packageVersionId": package_version_id,
        "requestingAgentInstanceId": requesting_agent_instance_id or None,
        "reason": reason,
    }
    result = _post_json("/api/orchestrator/agents/install-requests", payload)
    if isinstance(result, dict) and result.get("error"):
        return {"status": "error", "message": result["error"]}
    return {
        "status": "pending_approval",
        "approval_id": result.get("id"),
        "message": "Agent install request created. Awaiting user approval.",
    }


def create_request_agent_install_tool() -> ToolDefinition:
    return ToolDefinition(
        name="request_agent_install",
        label="请求安装 Agent",
        description="为当前 Claw 提交 Agent Package 的安装请求。需要用户审批后才能安装。",
        input_schema={
            "type": "object",
            "properties": {
                "claw_id": {"type": "string", "description": "当前 Claw ID"},
                "package_version_id": {"type": "string", "description": "要安装的 Package Version ID"},
                "requesting_agent_instance_id": {"type": "string", "description": "发起请求的 Agent Instance ID（可选）"},
                "reason": {"type": "string", "description": "安装原因（可选）"},
            },
            "required": ["claw_id", "package_version_id"],
        },
        tool_metadata=ToolMetadata(
            risk="medium",
            readonly=False,
            execution_scope="claw_sandbox",
            tags=["orchestration", "agent-install"],
        ),
        executor=_exec_request_agent_install,
    )


# ---- Tool: call_agent ----


def _exec_call_agent(
    claw_id: str,
    calling_agent_instance_id: str,
    message: str,
    target_agent_instance_id: str = "",
    target_role_key: str = "",
    conversation_id: str = "",
    **_: Any,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "clawId": claw_id,
        "callingAgentInstanceId": calling_agent_instance_id,
        "message": message,
        "targetAgentInstanceId": target_agent_instance_id or None,
        "targetRoleKey": target_role_key or None,
        "conversationId": conversation_id or None,
    }
    result = _post_json("/api/orchestrator/agents/call", payload)
    if isinstance(result, dict) and result.get("error"):
        return {"status": "error", "message": result["error"]}
    return {
        "status": result.get("status", "COMPLETED"),
        "text": result.get("text", ""),
        "agent_instance_id": result.get("agentInstanceId", ""),
    }


def create_call_agent_tool() -> ToolDefinition:
    return ToolDefinition(
        name="call_agent",
        label="调用 Agent",
        description="调用当前 Claw 中的另一个已启用 Agent Instance（通过 roleKey 或 agentInstanceId 指定），并返回其回复。",
        input_schema={
            "type": "object",
            "properties": {
                "claw_id": {"type": "string", "description": "当前 Claw ID"},
                "calling_agent_instance_id": {"type": "string", "description": "发起调用的 Agent Instance ID"},
                "message": {"type": "string", "description": "要发送给目标 Agent 的消息"},
                "target_agent_instance_id": {"type": "string", "description": "目标 Agent Instance ID（与 target_role_key 二选一）"},
                "target_role_key": {"type": "string", "description": "目标 Agent 的 roleKey（与 target_agent_instance_id 二选一）"},
                "conversation_id": {"type": "string", "description": "当前 Conversation ID（可选）"},
            },
            "required": ["claw_id", "calling_agent_instance_id", "message"],
        },
        tool_metadata=ToolMetadata(
            risk="medium",
            readonly=False,
            execution_scope="claw_sandbox",
            tags=["orchestration", "agent-call"],
        ),
        executor=_exec_call_agent,
    )
