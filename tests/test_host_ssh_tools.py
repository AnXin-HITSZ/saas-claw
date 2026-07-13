import asyncio
import os
import unittest
from unittest.mock import patch

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.executor import execute_tool_call, make_base_context
from openclaw.tools.host_ssh import HostSshClient, HostSshConfig, create_host_uname_tool
from openclaw.tools.policy import ToolPolicy
from openclaw.tools.registry import ToolRegistry


HOST_ENV = {
    "HOST_SSH_HOST": "203.0.113.10",
    "HOST_SSH_PORT": "22",
    "HOST_SSH_USERNAME": "pyclaw-ops",
    "HOST_SSH_KEY_PATH": "/var/run/secrets/pyclaw-host-ssh/id_ed25519",
    "HOST_SSH_KNOWN_HOSTS_PATH": "/var/run/secrets/pyclaw-host-ssh/known_hosts",
}


class FakeProcess:
    def __init__(self, stdout: bytes = b"ok\n", stderr: bytes = b"", returncode: int = 0):
        self._stdout = stdout
        self._stderr = stderr
        self.returncode = returncode
        self.killed = False

    async def communicate(self):
        return self._stdout, self._stderr

    def kill(self):
        self.killed = True
        self.returncode = -9


class HostSshToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_host_ssh_client_uses_exec_argv_without_shell(self):
        calls = []

        async def fake_create_subprocess_exec(*argv, stdout=None, stderr=None):
            calls.append((argv, stdout, stderr))
            return FakeProcess(stdout=b"Linux host\n")

        with patch("asyncio.create_subprocess_exec", fake_create_subprocess_exec):
            payload = await HostSshClient(HostSshConfig.from_env(HOST_ENV)).run(("uname", "-a"))

        argv, stdout_pipe, stderr_pipe = calls[0]
        self.assertEqual(argv[0], "ssh")
        self.assertIn("BatchMode=yes", argv)
        self.assertIn("StrictHostKeyChecking=yes", argv)
        self.assertEqual(argv[-3:], ("--", "uname", "-a"))
        self.assertIs(stdout_pipe, asyncio.subprocess.PIPE)
        self.assertIs(stderr_pipe, asyncio.subprocess.PIPE)
        self.assertEqual(payload["stdout"], "Linux host\n")
        self.assertEqual(payload["command"], ["uname", "-a"])

    async def test_host_uname_tool_reports_missing_env(self):
        registry = ToolRegistry()
        registry.register(create_host_uname_tool())

        with patch.dict(os.environ, {}, clear=True):
            outcome = await execute_tool_call(
                ToolCallBlock(id="call_1", name="host_uname", input={}),
                registry,
                make_base_context(),
            )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.result.details["status"], "missing_config")
        self.assertIn("HOST_SSH_HOST", outcome.result.output())

    async def test_host_uname_tool_executes_fixed_command(self):
        async def fake_create_subprocess_exec(*argv, stdout=None, stderr=None):
            return FakeProcess(stdout=b"Linux host\n")

        registry = ToolRegistry()
        registry.register(create_host_uname_tool())

        with patch.dict(os.environ, HOST_ENV, clear=True):
            with patch("asyncio.create_subprocess_exec", fake_create_subprocess_exec):
                outcome = await execute_tool_call(
                    ToolCallBlock(id="call_1", name="host_uname", input={"timeout_seconds": 5}),
                    registry,
                    make_base_context(),
                )

        self.assertFalse(outcome.result.is_error)
        self.assertEqual(outcome.result.details["payload"]["command"], ["uname", "-a"])
        self.assertIn("Linux host", outcome.result.details["payload"]["stdout"])

    def test_host_tools_are_full_or_explicitly_allowed(self):
        coding_registry = build_tool_registry(ToolPolicy(profile="coding"))
        self.assertIsNone(coding_registry.resolve("host_uname"))

        explicit_registry = build_tool_registry(ToolPolicy(profile="coding", also_allow={"group:host"}))
        self.assertIsNotNone(explicit_registry.resolve("host_uname"))
        self.assertIsNotNone(explicit_registry.resolve("host_df"))
        self.assertIsNotNone(explicit_registry.resolve("host_free"))


if __name__ == "__main__":
    unittest.main()