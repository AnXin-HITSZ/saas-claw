"""Orchestration tools: discover, request-install, call-agent.

These tools let an Agent discover and interact with other Agents within the
same Claw. All business logic is delegated to the Spring Backend
Conversation Orchestrator via internal service-to-service endpoints.

FastAPI Runtime only executes tool calls — it does NOT route or decide
permissions. Spring owns all business validation, authorization, and
agent-to-agent call orchestration.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from openclaw.tools.types import ToolDefinition, ToolExecutionContext

LOGGER = logging.getLogger(__name__)

_SPRING_BASE_URL = os.environ.get("PYCLAW_SPRING_BASE_URL", "http://localhost:8080")
_INTERNAL_TOKEN = os.environ.get("PYCLAW_API_TOKEN", "")


def _spring_url(path: str) -> str:
    return _SPRING_BASE_URL.rstrip("/") + path


def _internal_headers() -> dict[str, str]:
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if _INTERNAL_TOKEN:
        headers["Authorization"] = f"Bearer {_INTERNAL_TOKEN}"
    return headers


def _post_json(path: str, payload: dict[str, Any]) -> dict[str, Any]:
    try:
        import httpx  # type: ignore[import]
    except ImportError:
        return {"error": "httpx not available; install 'httpx' to use orchestration tools"}

    url = _spring_url(path)
    try:
        resp = httpx.post(url, json=payload, headers=_internal_headers(), timeout=60.0)
        resp.raise_for_status()
        return resp.json()  # type: ignore[no-any-return]
    except Exception as exc:
        LOGGER.warning("Orchestration call to %s failed: %s", url, exc)
        return {"error": str(exc)}


def _runtime_context(context: ToolExecutionContext) -> dict[str, Any]:
    """Extract runtime-injected fields from the tool execution context.

    These are injected by the OpenClaw Runtime from the /v1/agent/run request
    body and must never be provided by the model.
    """
    meta = context.metadata or {}
    return {
        "claw_id": str(meta.get("claw_id") or ""),
        "conversation_id": str(meta.get("conversation_id") or ""),
        "agent_instance_id": str(meta.get("agent_instance_id") or ""),
        "role_key": str(meta.get("role_key") or ""),
        "agent_key": str(meta.get("agent_key") or ""),
        "owner_user_id": str(meta.get("owner_user_id") or ""),
    }


# ---- Tool: discover_agents ----


def _exec_discover_agents(context: ToolExecutionContext, query: str = "", **_: Any) -> dict[str, Any]:
    ctx = _runtime_context(context)
    if not ctx["claw_id"]:
        return {"status": "error", "message": "Runtime context missing claw_id"}

    payload: dict[str, Any] = {"clawId": ctx["claw_id"], "query": query}
    result = _post_json("/api/internal/orchestrator/agents/discover", payload)
    if isinstance(result, dict) and result.get("error"):
        return {"status": "error", "message": result["error"]}
    return {"status": "ok", "candidates": result if isinstance(result, list) else []}


def create_discover_agents_tool() -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        return _exec_discover_agents(context, **arguments)

    return ToolDefinition(
        name="discover_agents",
        label="发现 Agent",
        description="搜索当前 Claw 可见的已发布 Agent Package。根据关键字、能力和所需 Profile 筛选候选项。",
        input_schema={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "搜索关键字（可选）"},
            },
        },
        execute=execute,
    )


# ---- Tool: request_agent_install ----


def _exec_request_agent_install(
    context: ToolExecutionContext,
    package_version_id: str,
    reason: str = "",
    **_: Any,
) -> dict[str, Any]:
    ctx = _runtime_context(context)
    if not ctx["claw_id"]:
        return {"status": "error", "message": "Runtime context missing claw_id"}

    payload: dict[str, Any] = {
        "clawId": ctx["claw_id"],
        "packageVersionId": package_version_id,
        "requestingAgentInstanceId": ctx["agent_instance_id"] or None,
        "reason": reason,
    }
    result = _post_json("/api/internal/orchestrator/agents/install-requests", payload)
    if isinstance(result, dict) and result.get("error"):
        return {"status": "error", "message": result["error"]}
    return {
        "status": "pending_approval",
        "approval_id": result.get("id"),
        "message": "Agent install request created. Awaiting user approval.",
    }


def create_request_agent_install_tool() -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        return _exec_request_agent_install(context, **arguments)

    return ToolDefinition(
        name="request_agent_install",
        label="请求安装 Agent",
        description="提交 Agent Package 安装请求（需要用户审批）。",
        input_schema={
            "type": "object",
            "properties": {
                "package_version_id": {"type": "string", "description": "要安装的 Package Version ID"},
                "reason": {"type": "string", "description": "安装原因（可选）"},
            },
            "required": ["package_version_id"],
        },
        execute=execute,
    )


# ---- Tool: call_agent ----


def _exec_call_agent(
    context: ToolExecutionContext,
    message: str,
    target_agent_instance_id: str = "",
    target_role_key: str = "",
    **_: Any,
) -> dict[str, Any]:
    ctx = _runtime_context(context)
    if not ctx["claw_id"]:
        return {"status": "error", "message": "Runtime context missing claw_id"}
    if not ctx["agent_instance_id"]:
        return {"status": "error", "message": "Runtime context missing agent_instance_id"}

    payload: dict[str, Any] = {
        "clawId": ctx["claw_id"],
        "callingAgentInstanceId": ctx["agent_instance_id"],
        "message": message,
        "targetAgentInstanceId": target_agent_instance_id or None,
        "targetRoleKey": target_role_key or None,
        "conversationId": ctx["conversation_id"] or None,
    }

    # Remove None values that Spring would reject or treat inconsistently
    payload = {k: v for k, v in payload.items() if v is not None}

    result = _post_json("/api/internal/orchestrator/agents/call", payload)
    if isinstance(result, dict) and result.get("error"):
        return {"status": "error", "message": result["error"]}

    return {
        "status": result.get("status", "COMPLETED"),
        "agent_instance_id": result.get("agentInstanceId", ""),
        "role_key": result.get("roleKey", ""),
        "text": result.get("text", ""),
        "message_id": result.get("messageId", ""),
    }


def create_call_agent_tool() -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> dict[str, Any]:
        return _exec_call_agent(context, **arguments)

    return ToolDefinition(
        name="call_agent",
        label="调用 Agent",
        description="调用当前 Claw 中的另一个已启用 Agent Instance（通过 roleKey 或 agentInstanceId 指定），并返回其回复。",
        input_schema={
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "要发送给目标 Agent 的消息"},
                "target_agent_instance_id": {"type": "string", "description": "目标 Agent Instance ID（与 target_role_key 二选一）"},
                "target_role_key": {"type": "string", "description": "目标 Agent 的 roleKey（与 target_agent_instance_id 二选一）"},
            },
            "required": ["message"],
        },
        execute=execute,
    )
