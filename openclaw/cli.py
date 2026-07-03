"""Command line interface for the Python OpenClaw runtime."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Sequence
from uuid import uuid4

from openclaw.agent.agent import Agent
from openclaw.config import load_env_file
from openclaw.llm.openai_provider import OpenAIProvider
from openclaw.llm.provider import MockProvider
from openclaw.llm.types import AssistantMessage, ToolCallBlock, message_to_dict
from openclaw.session.agent_session import AgentSession, SessionContextPolicy
from openclaw.session.paths import (
    resolve_chatdata_dir,
    resolve_session_store_path,
    resolve_session_transcript_path,
)
from openclaw.session.context import CompactionSettings
from openclaw.session.store import SessionStore
from openclaw.session.transcript import Transcript
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.catalog import list_catalog_entries
from openclaw.tools.executor import execute_tool_call, make_base_context
from openclaw.tools.policy import ToolPolicy
from openclaw.tools.registry import normalize_tool
from openclaw.tools.shell.approval import ShellApprovalRequest

DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."
DEFAULT_MODEL = "gpt-4.1-mini"
TRANSCRIPT_FORMATS = ("text", "detail", "json")


class CliError(Exception):
    """User-facing CLI failure."""


def main(argv: Sequence[str] | None = None) -> int:
    """Run the CLI and return a process exit code."""

    parser = build_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)

    try:
        return asyncio.run(run(args, parser))
    except KeyboardInterrupt:
        print("Interrupted.", file=sys.stderr)
        return 130
    except CliError as exc:
        print(f"pyclaw: {exc}", file=sys.stderr)
        return 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="pyclaw",
        description="OpenClaw-inspired Python agent runtime.",
    )
    parser.add_argument(
        "arguments",
        nargs="*",
        help='Prompt text, or command path such as "transcripts show <session-id>".',
    )
    parser.add_argument("--provider", choices=["openai", "mock"], default="openai", help="LLM provider to use.")
    parser.add_argument("--model", default=None, help="Model name. Defaults to OPENAI_MODEL or gpt-4.1-mini.")
    parser.add_argument("--system", default=DEFAULT_SYSTEM_PROMPT, help="System prompt for the agent.")
    parser.add_argument("--env-file", default=".env", help="Path to a .env file to load before provider creation.")
    parser.add_argument("--no-env-file", action="store_true", help="Do not load a .env file.")
    parser.add_argument(
        "--chatdata-dir",
        default=None,
        help="Directory for sessions.json and transcript JSONL files. Defaults to ./chatdata.",
    )
    parser.add_argument(
        "--session-id",
        default=None,
        help="Session id used for transcript persistence. Defaults to a generated id.",
    )
    parser.add_argument(
        "--format",
        dest="transcript_format",
        choices=TRANSCRIPT_FORMATS,
        default="text",
        help="Output format for transcripts show: text, detail, or json.",
    )
    parser.add_argument(
        "--api-mode",
        choices=["auto", "responses", "chat_completions", "chat-completions"],
        default="auto",
        help="OpenAI SDK API mode. Use chat_completions for OpenAI-compatible providers.",
    )
    parser.add_argument(
        "--reasoning-effort",
        choices=["low", "medium", "high"],
        default=None,
        help="Pass reasoning.effort through to providers that support it.",
    )
    parser.add_argument(
        "--max-output-tokens",
        type=int,
        default=None,
        help="Pass max_output_tokens through to providers that support it.",
    )
    parser.add_argument(
        "--tool-profile",
        choices=["minimal", "readonly", "coding", "messaging", "full"],
        default="coding",
        help="Default tool profile for agent prompts. Use full to expose runtime and web tools.",
    )
    parser.add_argument(
        "--tools-allow",
        default=None,
        help="Comma-separated tool allowlist. Supports groups such as group:fs, group:web, group:runtime.",
    )
    parser.add_argument(
        "--tools-deny",
        default=None,
        help="Comma-separated tool denylist. Supports groups such as group:runtime.",
    )
    parser.add_argument(
        "--tools-also-allow",
        default=None,
        help="Comma-separated tools to add on top of the selected profile.",
    )
    parser.add_argument(
        "--context-window-tokens",
        type=int,
        default=120_000,
        help="Estimated model context window used by pre-prompt compaction.",
    )
    parser.add_argument(
        "--reserve-tokens",
        type=int,
        default=16_384,
        help="Tokens reserved for model output and safety margin during context precheck.",
    )
    parser.add_argument(
        "--keep-recent-tokens",
        type=int,
        default=20_000,
        help="Approximate recent tail tokens retained after automatic compaction.",
    )
    parser.add_argument(
        "--tool-result-max-chars",
        type=int,
        default=20_000,
        help="Maximum characters retained per tool result before provider calls.",
    )
    parser.add_argument(
        "--disable-compaction",
        action="store_true",
        help="Disable automatic session compaction; oversized tool results may still be truncated.",
    )
    parser.add_argument(
        "--shell-approval",
        choices=["auto", "require", "deny"],
        default="auto",
        help="Shell approval mode: auto allows known mutation commands, require prompts before non-readonly commands, deny blocks them.",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="Approve shell commands that require confirmation without prompting. Use with care.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print JSON output, or pass JSON input for tools run.",
    )
    return parser


async def run(args: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    if not args.no_env_file:
        load_env_file(args.env_file)

    if args.arguments[:2] == ["gateway", "run"]:
        raise CliError("gateway run is registered but not implemented yet.")
    if args.arguments[:1] == ["gateway"]:
        raise CliError('unknown gateway command. Did you mean "gateway run"?')
    if args.arguments[:2] == ["transcripts", "show"]:
        return show_transcript_command(args)
    if args.arguments[:1] == ["transcripts"]:
        raise CliError('unknown transcripts command. Did you mean "transcripts show <session-id>"?')
    if args.arguments[:1] == ["tools"]:
        return await tools_command(args)

    prompt = " ".join(args.arguments).strip()
    if not prompt:
        parser.print_help()
        return 0

    message = await run_prompt(args, prompt)
    if args.json:
        print(json.dumps(message_to_dict(message), ensure_ascii=False, indent=2))
    else:
        print(assistant_text(message))
    return 0


async def tools_command(args: argparse.Namespace) -> int:
    if len(args.arguments) < 2:
        raise CliError("missing tools command. Usage: pyclaw tools list|describe|run")
    command = args.arguments[1]
    if command == "list":
        return tools_list_command(args)
    if command == "describe":
        return tools_describe_command(args)
    if command == "run":
        return await tools_run_command(args)
    raise CliError('unknown tools command. Did you mean "tools list", "tools describe", or "tools run"?')


def tools_list_command(args: argparse.Namespace) -> int:
    entries = list_catalog_entries()
    if args.json:
        print(json.dumps([entry.__dict__ for entry in entries], ensure_ascii=False, indent=2))
        return 0
    for entry in entries:
        profiles = ",".join(entry.profiles) or "-"
        tags = ",".join(entry.tags) or "-"
        print(f"{entry.name:14} {entry.section_id:10} profiles={profiles:20} tags={tags}")
    return 0


def tools_describe_command(args: argparse.Namespace) -> int:
    if len(args.arguments) < 3:
        raise CliError("missing tool name. Usage: pyclaw tools describe <tool-name>")
    name = args.arguments[2]
    registry = build_tool_registry(build_tool_policy(args, default_profile="full"))
    tool_like = registry.resolve(name)
    if tool_like is None:
        raise CliError(f"tool not found: {name}")
    tool = normalize_tool(tool_like)
    data = {
        "name": tool.name,
        "label": tool.label,
        "description": tool.description,
        "execution_mode": tool.execution_mode,
        "metadata": tool.metadata.__dict__,
        "input_schema": tool.input_schema,
    }
    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(f"name: {data['name']}")
        print(f"label: {data['label']}")
        print(f"description: {data['description']}")
        print(f"execution_mode: {data['execution_mode']}")
        print("metadata: " + json.dumps(data["metadata"], ensure_ascii=False, separators=(",", ":")))
        print("input_schema: " + json.dumps(data["input_schema"], ensure_ascii=False, indent=2))
    return 0


async def tools_run_command(args: argparse.Namespace) -> int:
    if len(args.arguments) < 3:
        raise CliError("missing tool name. Usage: pyclaw tools run <tool-name> --json '{...}'")
    name = args.arguments[2]
    raw_arguments = args.arguments[3:]
    tool_input = parse_tool_run_arguments(raw_arguments)
    registry = build_tool_registry(build_tool_policy(args, default_profile="full"))
    context = make_base_context(
        cwd=os.getcwd(),
        workspace_dir=os.getcwd(),
        metadata=build_tool_context_metadata(args),
    )
    outcome = await execute_tool_call(ToolCallBlock(id="manual_1", name=name, input=tool_input), registry, context)
    block = outcome.message.content[0]
    if args.json:
        print(json.dumps(block, ensure_ascii=False, indent=2))
    else:
        print(str(block.get("output", "")))
    return 1 if block.get("is_error") else 0


def parse_tool_run_arguments(values: list[str]) -> dict[str, Any]:
    if not values:
        return {}
    raw = " ".join(values).strip()
    if raw.startswith("{"):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise CliError(f"invalid tool JSON input: {exc.msg}") from exc
        if not isinstance(data, dict):
            raise CliError("tool JSON input must be an object")
        return data
    parsed: dict[str, Any] = {}
    for item in values:
        if "=" not in item:
            raise CliError("tool arguments must be JSON or key=value pairs")
        key, value = item.split("=", 1)
        parsed[key] = value
    return parsed


def show_transcript_command(args: argparse.Namespace) -> int:
    if len(args.arguments) < 3:
        raise CliError("missing session id. Usage: pyclaw transcripts show <session-id> --format text")
    if len(args.arguments) > 3:
        extra = " ".join(args.arguments[3:])
        raise CliError(f"unexpected transcripts show arguments: {extra}")

    session_id = sanitize_session_id(args.arguments[2])
    path = resolve_session_transcript_path(resolve_chatdata_dir(args.chatdata_dir), session_id)
    entries = read_transcript_entries(path)
    if args.transcript_format == "json":
        print(json.dumps(entries, ensure_ascii=False, indent=2))
    elif args.transcript_format == "detail":
        print(format_transcript_detail(entries))
    else:
        print(format_transcript_text(entries))
    return 0


def read_transcript_entries(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise CliError(f"transcript not found: {path}")

    entries: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            entry = json.loads(line)
        except json.JSONDecodeError as exc:
            raise CliError(f"invalid transcript JSONL at line {line_number}: {exc.msg}") from exc
        if isinstance(entry, dict):
            entries.append(entry)
    return entries


def format_transcript_text(entries: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for entry in entries:
        if entry.get("type") != "message":
            continue
        message = dict(entry.get("message") or {})
        role = str(message.get("role") or "unknown")
        text = message_text(message)
        lines.append(f"{role}: {text}" if text else f"{role}: [non-text content]")
    return "\n".join(lines)


def format_transcript_detail(entries: list[dict[str, Any]]) -> str:
    sections: list[str] = []
    for entry in entries:
        entry_type = entry.get("type")
        if entry_type == "session":
            sections.append(
                f"[{entry.get('timestamp', '')}] session id={entry.get('id', '')} cwd={entry.get('cwd', '')}".strip()
            )
            continue
        if entry_type == "compaction":
            details = entry.get("details")
            header = (
                f"[{entry.get('timestamp', '')}] compaction "
                f"firstKeptEntryId={entry.get('firstKeptEntryId', '')} "
                f"tokensBefore={entry.get('tokensBefore', '')}"
            )
            if isinstance(details, dict) and details:
                header += " details=" + json.dumps(details, ensure_ascii=False, separators=(",", ":"))
            sections.append(header + "\n" + str(entry.get("summary", "")))
            continue
        if entry_type != "message":
            continue

        message = dict(entry.get("message") or {})
        role = str(message.get("role") or "unknown")
        timestamp = str(entry.get("timestamp") or message.get("timestamp") or "")
        header_parts = [f"[{timestamp}]" if timestamp else "[no timestamp]", role]
        provider = message.get("provider")
        model = message.get("model")
        stop_reason = message.get("stopReason") or message.get("stop_reason")
        if provider:
            header_parts.append(f"provider={provider}")
        if model:
            header_parts.append(f"model={model}")
        if stop_reason:
            header_parts.append(f"stop={stop_reason}")
        usage = message.get("usage")
        if isinstance(usage, dict) and usage:
            header_parts.append(f"usage={json.dumps(usage, ensure_ascii=False, separators=(',', ':'))}")
        body = message_text(message) or format_non_text_blocks(message)
        sections.append(" ".join(header_parts) + "\n" + body)
    return "\n\n".join(sections)


def message_text(message: dict[str, Any]) -> str:
    parts: list[str] = []
    for block in message.get("content") or []:
        if isinstance(block, dict) and block.get("type") == "text":
            parts.append(str(block.get("text", "")))
    return "".join(parts)


def format_non_text_blocks(message: dict[str, Any]) -> str:
    lines: list[str] = []
    for block in message.get("content") or []:
        if not isinstance(block, dict):
            continue
        block_type = block.get("type")
        if block_type == "toolCall":
            lines.append(
                "toolCall "
                + str(block.get("name", ""))
                + " "
                + json.dumps(block.get("input") or {}, ensure_ascii=False, separators=(",", ":"))
            )
        elif block_type == "toolResult":
            prefix = "toolResult error" if block.get("is_error") else "toolResult"
            parts = [prefix, str(block.get("name", "")), str(block.get("output", ""))]
            details = block.get("details")
            if isinstance(details, dict) and details:
                parts.append("details=" + json.dumps(details, ensure_ascii=False, separators=(",", ":")))
            progress = block.get("progress")
            if isinstance(progress, dict) and progress:
                parts.append("progress=" + json.dumps(progress, ensure_ascii=False, separators=(",", ":")))
            if block.get("terminate"):
                parts.append("terminate=true")
            lines.append(" ".join(parts))
        elif block_type != "text":
            lines.append(json.dumps(block, ensure_ascii=False, separators=(",", ":")))
    return "\n".join(lines) if lines else "[empty message]"


async def run_prompt(args: argparse.Namespace, prompt: str) -> AssistantMessage:
    model = args.model or os.environ.get("OPENAI_MODEL") or DEFAULT_MODEL
    provider = build_provider(args.provider, prompt, api_mode=args.api_mode)
    cwd = os.getcwd()
    policy = build_tool_policy(args)
    agent = Agent(
        model=model,
        provider=provider,
        system_prompt=args.system,
        tools=build_tool_registry(policy),
        model_options=build_model_options(args),
        cwd=cwd,
        workspace_dir=cwd,
        readonly=policy.readonly,
        tool_metadata=build_tool_context_metadata(args),
    )
    session = build_agent_session(args, agent)
    agent.session_id = session.session_id
    agent.chatdata_dir = resolve_chatdata_dir(args.chatdata_dir)
    return await session.run_prompt(prompt)




def build_tool_context_metadata(args: argparse.Namespace) -> dict[str, Any]:
    metadata: dict[str, Any] = {
        "shell_approval_mode": args.shell_approval,
    }
    if args.yes:
        metadata["shell_approval_callback"] = approve_shell_without_prompt
    elif args.shell_approval == "require":
        metadata["shell_approval_callback"] = prompt_shell_approval
    return metadata


def approve_shell_without_prompt(request: ShellApprovalRequest) -> bool:
    print_shell_approval_summary(request, approved_by="--yes")
    return True


def prompt_shell_approval(request: ShellApprovalRequest) -> bool:
    print_shell_approval_summary(request)
    if not sys.stdin.isatty():
        print("pyclaw: shell command rejected because stdin is not interactive.", file=sys.stderr)
        return False
    try:
        print("Approve shell command? [y/N] ", end="", file=sys.stderr, flush=True)
        answer = input().strip().lower()
    except EOFError:
        print("pyclaw: shell command rejected because approval input ended.", file=sys.stderr)
        return False
    return answer in {"y", "yes"}


def print_shell_approval_summary(request: ShellApprovalRequest, *, approved_by: str | None = None) -> None:
    print("", file=sys.stderr)
    print("pyclaw shell approval required", file=sys.stderr)
    print(f"tool: {request.tool_name or 'shell'}", file=sys.stderr)
    if request.session_id:
        print(f"session: {request.session_id}", file=sys.stderr)
    print(f"safety: {request.safety}", file=sys.stderr)
    print("command:", file=sys.stderr)
    print(request.command, file=sys.stderr)
    if request.reasons:
        print("reasons:", file=sys.stderr)
        for reason in request.reasons:
            print(f"- {reason}", file=sys.stderr)
    if approved_by:
        print(f"approved by {approved_by}", file=sys.stderr)


def build_tool_policy(args: argparse.Namespace, *, default_profile: str | None = None) -> ToolPolicy:
    profile = default_profile or args.tool_profile
    return ToolPolicy(
        profile=profile,
        allow=parse_tool_name_set(args.tools_allow),
        deny=parse_tool_name_set(args.tools_deny) or set(),
        also_allow=parse_tool_name_set(args.tools_also_allow) or set(),
        readonly=profile == "readonly",
    )


def parse_tool_name_set(value: str | None) -> set[str] | None:
    if value is None:
        return None
    names = {item.strip() for item in value.split(",") if item.strip()}
    return names


def build_agent_session(args: argparse.Namespace, agent: Agent) -> AgentSession:
    chatdata_dir = resolve_chatdata_dir(args.chatdata_dir)
    session_id = sanitize_session_id(args.session_id or uuid4().hex)
    return AgentSession(
        session_id=session_id,
        agent=agent,
        store=SessionStore(resolve_session_store_path(chatdata_dir)),
        transcript=Transcript(resolve_session_transcript_path(chatdata_dir, session_id), session_id=session_id, cwd=os.getcwd()),
        cwd=os.getcwd(),
        workspace_dir=os.getcwd(),
        context_policy=build_session_context_policy(args),
    )

def build_session_context_policy(args: argparse.Namespace) -> SessionContextPolicy:
    return SessionContextPolicy(
        compaction=CompactionSettings(
            enabled=not args.disable_compaction,
            context_window_tokens=args.context_window_tokens,
            reserve_tokens=args.reserve_tokens,
            keep_recent_tokens=args.keep_recent_tokens,
            tool_result_max_chars=args.tool_result_max_chars,
        )
    )

def sanitize_session_id(value: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip())
    sanitized = sanitized.strip(".-_")
    if not sanitized:
        raise CliError("session id must contain at least one letter or number")
    return sanitized


def build_provider(name: str, prompt: str, *, api_mode: str = "auto") -> Any:
    if name == "mock":
        return MockProvider(
            [
                AssistantMessage(
                    content=[{"type": "text", "text": f"mock response: {prompt}"}],
                    provider="mock",
                    model="mock-model",
                    stop_reason="stop",
                )
            ]
        )

    if name == "openai":
        if not os.environ.get("OPENAI_API_KEY"):
            raise CliError("OPENAI_API_KEY is not set. Put it in .env or set it in the shell.")
        return OpenAIProvider(api_mode=normalize_api_mode_arg(api_mode))

    raise CliError(f"unsupported provider: {name}")


def normalize_api_mode_arg(value: str) -> str:
    return value.replace("-", "_")


def build_model_options(args: argparse.Namespace) -> dict[str, Any]:
    options: dict[str, Any] = {}
    if args.reasoning_effort:
        options["reasoning"] = {"effort": args.reasoning_effort}
    if args.max_output_tokens is not None:
        options["max_output_tokens"] = args.max_output_tokens
    return options


def assistant_text(message: AssistantMessage) -> str:
    parts: list[str] = []
    for block in message.content:
        if block.get("type") == "text":
            parts.append(str(block.get("text", "")))
    if parts:
        return "".join(parts)
    if message.error_message:
        return message.error_message
    return ""


if __name__ == "__main__":
    raise SystemExit(main())
