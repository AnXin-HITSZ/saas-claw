"""Read-only host inspection tools over a pinned SSH Secret."""

from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass
from typing import Any

from openclaw.tools.results import error_result, json_result
from openclaw.tools.shell.exec import normalize_max_chars, normalize_timeout
from openclaw.tools.types import ToolDefinition, ToolExecutionContext, ToolResult

HOST_SSH_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "timeout_seconds": {
            "type": "integer",
            "description": "Maximum SSH execution time in seconds.",
        },
        "max_chars": {
            "type": "integer",
            "description": "Maximum stdout/stderr characters retained in the result.",
        },
    },
    "additionalProperties": False,
}


@dataclass(frozen=True)
class HostSshConfig:
    host: str
    port: int
    username: str
    key_path: str
    known_hosts_path: str

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "HostSshConfig":
        source = env if env is not None else os.environ
        missing = [
            name
            for name in (
                "HOST_SSH_HOST",
                "HOST_SSH_PORT",
                "HOST_SSH_USERNAME",
                "HOST_SSH_KEY_PATH",
                "HOST_SSH_KNOWN_HOSTS_PATH",
            )
            if not source.get(name)
        ]
        if missing:
            raise ValueError("missing host SSH configuration: " + ", ".join(missing))

        try:
            port = int(source["HOST_SSH_PORT"])
        except ValueError as exc:
            raise ValueError("HOST_SSH_PORT must be an integer") from exc
        if port < 1 or port > 65535:
            raise ValueError("HOST_SSH_PORT must be between 1 and 65535")

        return cls(
            host=source["HOST_SSH_HOST"],
            port=port,
            username=source["HOST_SSH_USERNAME"],
            key_path=source["HOST_SSH_KEY_PATH"],
            known_hosts_path=source["HOST_SSH_KNOWN_HOSTS_PATH"],
        )


class HostSshClient:
    """Execute fixed remote commands through ssh without invoking a local shell."""

    def __init__(self, config: HostSshConfig | None = None) -> None:
        self.config = config or HostSshConfig.from_env()

    async def run(
        self,
        command: tuple[str, ...],
        *,
        timeout_seconds: int = 30,
        max_chars: int = 20000,
    ) -> dict[str, Any]:
        if not command or any(not item for item in command):
            raise ValueError("host SSH command must be a non-empty argument tuple")

        argv = (
            "ssh",
            "-i",
            self.config.key_path,
            "-o",
            "BatchMode=yes",
            "-o",
            f"UserKnownHostsFile={self.config.known_hosts_path}",
            "-o",
            "StrictHostKeyChecking=yes",
            "-p",
            str(self.config.port),
            f"{self.config.username}@{self.config.host}",
            "--",
            *command,
        )
        process = await asyncio.create_subprocess_exec(
            *argv,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        timed_out = False
        try:
            stdout_bytes, stderr_bytes = await asyncio.wait_for(
                process.communicate(),
                timeout=timeout_seconds,
            )
        except asyncio.TimeoutError:
            timed_out = True
            process.kill()
            stdout_bytes, stderr_bytes = await process.communicate()

        stdout = stdout_bytes.decode("utf-8", errors="replace")
        stderr = stderr_bytes.decode("utf-8", errors="replace")
        return {
            "command": list(command),
            "exit_code": process.returncode,
            "stdout": stdout[-max_chars:],
            "stderr": stderr[-max_chars:],
            "timed_out": timed_out,
            "timeout_seconds": timeout_seconds,
            "max_chars": max_chars,
            "host": self.config.host,
            "port": self.config.port,
            "username": self.config.username,
        }


async def execute_host_command(
    _context: ToolExecutionContext,
    arguments: dict[str, Any],
    *,
    command: tuple[str, ...],
) -> ToolResult:
    try:
        config = HostSshConfig.from_env()
    except ValueError as exc:
        return error_result(str(exc), details={"status": "missing_config"})

    timeout = normalize_timeout(arguments.get("timeout_seconds", 30) or 30)
    max_chars = normalize_max_chars(arguments.get("max_chars", 20000) or 20000)
    try:
        payload = await HostSshClient(config).run(command, timeout_seconds=timeout, max_chars=max_chars)
    except FileNotFoundError:
        return error_result("ssh executable not found", details={"status": "ssh_not_found"})
    except ValueError as exc:
        return error_result(str(exc), details={"status": "invalid_command"})

    return json_result(payload, is_error=bool(payload["timed_out"] or payload["exit_code"] not in (0, None)))


def create_host_uname_tool() -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
        return await execute_host_command(context, arguments, command=("uname", "-a"))

    return ToolDefinition(
        name="host_uname",
        label="Host Uname",
        description="Read the ECS host kernel and system identity with uname -a over SSH.",
        input_schema=HOST_SSH_SCHEMA,
        execute=execute,
        execution_mode="parallel",
    )


def create_host_df_tool() -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
        return await execute_host_command(context, arguments, command=("df", "-h"))

    return ToolDefinition(
        name="host_df",
        label="Host Disk Usage",
        description="Read ECS host filesystem disk usage with df -h over SSH.",
        input_schema=HOST_SSH_SCHEMA,
        execute=execute,
        execution_mode="parallel",
    )


def create_host_free_tool() -> ToolDefinition:
    async def execute(context: ToolExecutionContext, arguments: dict[str, Any]) -> ToolResult:
        return await execute_host_command(context, arguments, command=("free", "-h"))

    return ToolDefinition(
        name="host_free",
        label="Host Memory Usage",
        description="Read ECS host memory usage with free -h over SSH.",
        input_schema=HOST_SSH_SCHEMA,
        execute=execute,
        execution_mode="parallel",
    )