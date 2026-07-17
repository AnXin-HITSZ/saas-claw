"""Tool execution pipeline."""

from __future__ import annotations

import asyncio
import inspect
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from openclaw.agent.events import AgentEvent
from openclaw.llm.types import ToolCallBlock, ToolResultMessage, tool_result_content
from openclaw.tools.approval import PendingToolApprovalError
from openclaw.tools.hooks import NoopToolHooks, ToolExecutionDecision, ToolHooks
from openclaw.tools.registry import ToolRegistry, normalize_tool
from openclaw.tools.results import blocked_result, error_result, ensure_tool_result
from openclaw.tools.schema import ToolArgumentError, validate_tool_arguments
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolResult


@dataclass
class ToolExecutionOutcome:
    call: ToolCallBlock
    result: ToolResult
    message: ToolResultMessage
    tool: ToolDefinition | None = None


async def execute_tool_call(
    call: ToolCallBlock,
    registry: ToolRegistry,
    context: ToolExecutionContext,
    hooks: ToolHooks | None = None,
) -> ToolExecutionOutcome:
    tool_like = registry.resolve(call.name)
    if tool_like is None:
        result = error_result(f"tool not found: {call.name}", details={"status": "not_found"})
        return _outcome(call, result, tool=None)

    tool = normalize_tool(tool_like)
    arguments = dict(call.input)
    hooks = hooks or NoopToolHooks()

    try:
        if tool.prepare_arguments is not None:
            arguments = tool.prepare_arguments(arguments)
        arguments = validate_tool_arguments(tool.input_schema, arguments)
    except ToolArgumentError as exc:
        result = error_result(str(exc), details={"status": "invalid_arguments"})
        return _outcome(call, result, tool=tool)
    except Exception as exc:
        result = error_result(str(exc), details={"status": "prepare_failed"})
        return _outcome(call, result, tool=tool)

    context = _context_for_call(context, call)

    try:
        decision = await _maybe_await(hooks.before_tool_call(call, tool, arguments, context))
    except PendingToolApprovalError:
        raise
    except Exception as exc:
        result = error_result(str(exc), details={"status": "before_hook_failed"})
        return _outcome(call, result, tool=tool)

    if not isinstance(decision, ToolExecutionDecision):
        decision = ToolExecutionDecision(status="ALLOW", arguments=arguments)

    if decision.status == "DENY":
        result = blocked_result(
            decision.reason or f"tool call blocked: {call.name}",
            denied_reason=decision.denied_reason or decision.reason,
        )
        return _outcome(call, result, tool=tool)

    if decision.status == "PENDING_APPROVAL":
        if decision.approval is None:
            result = error_result(
                "internal error: PENDING_APPROVAL decision missing approval payload",
                details={"status": "approval_missing"},
            )
            return _outcome(call, result, tool=tool)
        raise PendingToolApprovalError(decision.approval)

    if decision.arguments is not None:
        arguments = dict(decision.arguments)

    try:
        raw = await _maybe_await(tool.execute(context, arguments))
        result = ensure_tool_result(raw)
    except Exception as exc:
        result = error_result(str(exc), details={"status": "execute_failed"})

    try:
        after_result = await _maybe_await(hooks.after_tool_call(call, tool, arguments, result, context))
        result = ensure_tool_result(after_result)
    except Exception as exc:
        result.details = dict(result.details)
        result.details["afterHookError"] = str(exc)

    return _outcome(call, result, tool=tool)


async def execute_tool_call_batch(
    calls: list[ToolCallBlock],
    registry: ToolRegistry,
    context: ToolExecutionContext,
    hooks: ToolHooks | None = None,
) -> list[ToolExecutionOutcome]:
    if not calls:
        return []

    if can_execute_parallel(calls, registry):
        for call in calls:
            _emit_tool_start(context, call)
        outcomes = await asyncio.gather(
            *(execute_tool_call(call, registry, context, hooks) for call in calls)
        )
        for outcome in outcomes:
            _emit_tool_end(context, outcome)
    else:
        outcomes = []
        for call in calls:
            _emit_tool_start(context, call)
            outcome = await execute_tool_call(call, registry, context, hooks)
            outcomes.append(outcome)
            _emit_tool_end(context, outcome)
            if outcome.result.terminate:
                break
    return list(outcomes)


def _emit_tool_start(context: ToolExecutionContext, call: ToolCallBlock) -> None:
    if context.emit is not None:
        context.emit(AgentEvent("tool_execution_start", {"call": call}))


def _emit_tool_end(context: ToolExecutionContext, outcome: ToolExecutionOutcome) -> None:
    if context.emit is not None:
        context.emit(
            AgentEvent(
                "tool_execution_end",
                {
                    "call": outcome.call,
                    "message": outcome.message,
                    "result": outcome.result,
                    "is_error": outcome.result.is_error,
                },
            )
        )


def can_execute_parallel(calls: list[ToolCallBlock], registry: ToolRegistry) -> bool:
    if not calls:
        return False
    for call in calls:
        tool_like = registry.resolve(call.name)
        if tool_like is None:
            return False
        if normalize_tool(tool_like).execution_mode != "parallel":
            return False
    return True


def make_base_context(
    *,
    cwd: Path | str | None = None,
    workspace_dir: Path | str | None = None,
    session_id: str | None = None,
    chatdata_dir: Path | str | None = None,
    model: str | None = None,
    provider: str | None = None,
    emit: Any | None = None,
    readonly: bool = False,
    metadata: dict[str, Any] | None = None,
) -> ToolExecutionContext:
    cwd_path = Path(cwd) if cwd is not None else Path.cwd()
    workspace_path = Path(workspace_dir) if workspace_dir is not None else cwd_path
    return ToolExecutionContext(
        tool_call_id="",
        tool_name="",
        cwd=cwd_path,
        workspace_dir=workspace_path,
        session_id=session_id,
        chatdata_dir=Path(chatdata_dir) if chatdata_dir is not None else None,
        model=model,
        provider=provider,
        emit=emit,
        readonly=readonly,
        metadata=dict(metadata or {}),
    )


def _context_for_call(context: ToolExecutionContext, call: ToolCallBlock) -> ToolExecutionContext:
    return ToolExecutionContext(
        tool_call_id=call.id,
        tool_name=call.name,
        cwd=context.cwd,
        workspace_dir=context.workspace_dir,
        session_id=context.session_id,
        chatdata_dir=context.chatdata_dir,
        model=context.model,
        provider=context.provider,
        emit=context.emit,
        readonly=context.readonly,
        metadata=dict(context.metadata),
    )


def _outcome(call: ToolCallBlock, result: ToolResult, *, tool: ToolDefinition | None) -> ToolExecutionOutcome:
    message = ToolResultMessage(
        content=tool_result_content(
            tool_call_id=call.id,
            name=call.name,
            output=result.output(),
            is_error=result.is_error,
            details=result.details,
            progress=result.progress_dict(),
            terminate=result.terminate,
        )
    )
    return ToolExecutionOutcome(call=call, result=result, message=message, tool=tool)


async def _maybe_await(value: Any) -> Any:
    if inspect.isawaitable(value):
        return await value
    return value
