import unittest

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.executor import execute_tool_call, make_base_context
from openclaw.tools.registry import ToolRegistry
from openclaw.tools.shell.exec import create_exec_tool, create_shell_tool


class ShellToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_shell_tool_runs_in_workspace(self):
        registry = ToolRegistry()
        registry.register(create_shell_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="shell", input={"command": "echo hello", "timeout_seconds": 5}),
            registry,
            make_base_context(),
        )

        self.assertFalse(outcome.result.details["payload"]["timed_out"])
        self.assertIn("hello", outcome.result.details["payload"]["stdout"].lower())
        self.assertEqual(outcome.result.details["payload"]["classification"]["safety"], "readonly")

    async def test_exec_tool_accepts_openclaw_parameter_names(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "echo hello", "timeout": 5}),
            registry,
            make_base_context(),
        )

        self.assertFalse(outcome.result.details["payload"]["timed_out"])
        self.assertIn("hello", outcome.result.details["payload"]["stdout"].lower())

    async def test_readonly_context_allows_readonly_shell_command(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "echo hello"}),
            registry,
            make_base_context(readonly=True),
        )

        self.assertFalse(outcome.result.is_error)
        self.assertEqual(outcome.result.details["payload"]["classification"]["safety"], "readonly")

    async def test_readonly_context_blocks_mutating_shell_command(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "git add ."}),
            registry,
            make_base_context(readonly=True),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.result.details["deniedReason"], "readonly")
        self.assertEqual(outcome.result.details["classification"]["safety"], "mutation")

    async def test_shell_tool_blocks_dangerous_command(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "git reset --hard"}),
            registry,
            make_base_context(),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.result.details["deniedReason"], "dangerous_command")

    async def test_unknown_command_requires_approval_in_auto_mode(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "py -m pip install demo"}),
            registry,
            make_base_context(),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.result.details["deniedReason"], "approval_required")
        self.assertEqual(outcome.result.details["classification"]["safety"], "unknown")

    async def test_approval_required_blocks_mutation_without_approval(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "git add ."}),
            registry,
            make_base_context(metadata={"shell_approval_mode": "require"}),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.result.details["deniedReason"], "approval_required")

    async def test_real_os_sandbox_fails_closed_when_unavailable(self):
        registry = ToolRegistry()
        registry.register(create_exec_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="exec", input={"command": "echo hello"}),
            registry,
            make_base_context(metadata={"shell_sandbox": "real_os"}),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.result.details["deniedReason"], "sandbox_unavailable")


if __name__ == "__main__":
    unittest.main()