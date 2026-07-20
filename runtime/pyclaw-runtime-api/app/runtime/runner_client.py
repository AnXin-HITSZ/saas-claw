"""HTTP client for calling claw-runner instances."""

from __future__ import annotations

from typing import Optional

import httpx

from app.config.settings import settings


class RunnerClient:
    """Thin HTTP client that forwards workspace and tool calls to claw-runner."""

    def __init__(self, base_url: Optional[str] = None) -> None:
        self._base_url = (base_url or settings.runner_base_url).rstrip("/")
        self._timeout = httpx.Timeout(settings.runner_timeout_seconds)

    # ------------------------------------------------------------------
    # Health
    # ------------------------------------------------------------------

    def healthz(self) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.get(f"{self._base_url}/healthz")
            r.raise_for_status()
            return r.json()

    # ------------------------------------------------------------------
    # Workspace
    # ------------------------------------------------------------------

    def workspace_info(self) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.get(f"{self._base_url}/v1/workspace")
            r.raise_for_status()
            return r.json()

    def list_files(self, path: str = ".") -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.get(f"{self._base_url}/v1/workspace/files", params={"path": path})
            r.raise_for_status()
            return r.json()

    def read_file(self, file_path: str) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.get(f"{self._base_url}/v1/workspace/files/{file_path}")
            r.raise_for_status()
            return r.json()

    def write_file(self, file_path: str, content: str) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.put(
                f"{self._base_url}/v1/workspace/files/{file_path}",
                json={"content": content},
            )
            r.raise_for_status()
            return r.json()

    def apply_patch(
        self, file_path: str, old_text: str, new_text: str, replace_all: bool = False
    ) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.post(
                f"{self._base_url}/v1/workspace/patches",
                json={
                    "file_path": file_path,
                    "old_text": old_text,
                    "new_text": new_text,
                    "replace_all": replace_all,
                },
            )
            r.raise_for_status()
            return r.json()

    # ------------------------------------------------------------------
    # Tools
    # ------------------------------------------------------------------

    def execute_tool(self, tool_name: str, arguments: dict, context: dict) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.post(
                f"{self._base_url}/v1/tools/execute",
                json={"tool_name": tool_name, "arguments": arguments, "context": context},
            )
            r.raise_for_status()
            return r.json()

    # ------------------------------------------------------------------
    # Commands
    # ------------------------------------------------------------------

    def execute_command(self, command: str, cwd: Optional[str] = None) -> dict:
        with httpx.Client(timeout=self._timeout) as client:
            r = client.post(
                f"{self._base_url}/v1/commands/execute",
                json={"command": command, "cwd": cwd or "."},
            )
            r.raise_for_status()
            return r.json()


# Module-level singleton (lazily replaced per-request via sandbox_base_url)
_runner_client: Optional[RunnerClient] = None


def get_runner_client(base_url: Optional[str] = None) -> RunnerClient:
    """Return a RunnerClient, optionally scoped to a specific runner URL."""
    if base_url:
        return RunnerClient(base_url)
    global _runner_client
    if _runner_client is None:
        _runner_client = RunnerClient()
    return _runner_client
