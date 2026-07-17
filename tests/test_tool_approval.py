"""Tests for ApprovalToolHooks and the executor's ToolExecutionDecision handling."""

from __future__ import annotations

import asyncio
import tempfile
import unittest
from pathlib import Path

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.approval import (
    ApprovalRuntimeContext,
    PendingToolApproval,
    PendingToolApprovalError,
)
from openclaw.tools.approval_hooks import ApprovalToolHooks
from openclaw.tools.approval_store import FilePendingApprovalStore
from openclaw.tools.executor import execute_tool_call, make_base_context
from openclaw.tools.hooks import ToolExecutionDecision
from openclaw.tools.registry import ToolRegistry
from openclaw.tools.results import text_result
from openclaw.tools.types import ToolDefinition, ToolMetadata


def _make_tool(name: str, risk: str) -> ToolDefinition:
    async def execute(context, arguments):
        return text_result(f"executed {name}")

    return ToolDefinition(
        name=name,
        label=name,
        description=name,
        input_schema={"type": "object", "properties": {"path": {"type": "string"}}},
        execute=execute,
        metadata=ToolMetadata(risk=risk),
    )


class ApprovalHookTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.store = FilePendingApprovalStore(self.tempdir.name)
        self.request_context = ApprovalRuntimeContext(
            session_id="session-1",
            claw_id="claw-1",
            owner_user_id="user-1",
            sandbox_base_url="http://sandbox.local",
        )

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    async def test_low_risk_returns_allow(self):
        hooks = ApprovalToolHooks(pending_store=self.store, request_context=self.request_context)
        registry = ToolRegistry()
        registry.register(_make_tool("read_file", "low"))

        outcome = await execute_tool_call(
            ToolCallBlock(id="call-1", name="read_file", input={"path": "a.txt"}),
            registry,
            make_base_context(metadata={"sandbox_base_url": "http://sandbox.local"}),
            hooks,
        )

        self.assertFalse(outcome.result.is_error)
        self.assertEqual(outcome.result.output(), "executed read_file")

    async def test_medium_risk_returns_pending_approval(self):
        hooks = ApprovalToolHooks(pending_store=self.store, request_context=self.request_context)
        registry = ToolRegistry()
        registry.register(_make_tool("write_file", "medium"))

        with self.assertRaises(PendingToolApprovalError) as ctx:
            await execute_tool_call(
                ToolCallBlock(id="call-2", name="write_file", input={"path": "a.txt"}),
                registry,
                make_base_context(metadata={"sandbox_base_url": "http://sandbox.local"}),
                hooks,
            )

        approval: PendingToolApproval = ctx.exception.approval
        self.assertEqual(approval.tool_name, "write_file")
        self.assertEqual(approval.risk, "medium")
        self.assertEqual(approval.session_id, "session-1")
        state = self.store.load(approval.approval_id)
        self.assertIsNotNone(state)
        self.assertEqual(state["tool_call"]["name"], "write_file")
        self.assertEqual(state["session_id"], "session-1")

    async def test_hard_policy_missing_sandbox_denies(self):
        request_context = ApprovalRuntimeContext(session_id="session-1")
        hooks = ApprovalToolHooks(pending_store=self.store, request_context=request_context)
        registry = ToolRegistry()
        registry.register(_make_tool("read_file", "low"))

        outcome = await execute_tool_call(
            ToolCallBlock(id="call-3", name="read_file", input={"path": "a.txt"}),
            registry,
            make_base_context(),
            hooks,
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.message.content[0]["details"]["status"], "blocked")
        self.assertIn("sandbox_base_url", outcome.result.output())

    async def test_hard_policy_owner_mismatch_denies(self):
        hooks = ApprovalToolHooks(pending_store=self.store, request_context=self.request_context)
        registry = ToolRegistry()
        registry.register(_make_tool("read_file", "low"))

        outcome = await execute_tool_call(
            ToolCallBlock(id="call-4", name="read_file", input={"path": "a.txt", "owner_user_id": "other"}),
            registry,
            make_base_context(metadata={"sandbox_base_url": "http://sandbox.local"}),
            hooks,
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.message.content[0]["details"]["status"], "blocked")

    async def test_decision_is_strongly_typed(self):
        """The decision returned by ApprovalToolHooks must be a ToolExecutionDecision."""

        hooks = ApprovalToolHooks(pending_store=self.store, request_context=self.request_context)
        tool = _make_tool("read_file", "low")

        decision = await hooks.before_tool_call(
            ToolCallBlock(id="call-5", name="read_file", input={}),
            tool,
            {"path": "a.txt"},
            make_base_context(metadata={"sandbox_base_url": "http://sandbox.local"}),
        )
        self.assertIsInstance(decision, ToolExecutionDecision)
        self.assertEqual(decision.status, "ALLOW")


class PendingApprovalStoreTests(unittest.TestCase):
    def test_save_load_delete_round_trip(self):
        with tempfile.TemporaryDirectory() as tempdir:
            store = FilePendingApprovalStore(tempdir)
            store.save("approval_1", {"foo": "bar"}, ttl_seconds=60)
            self.assertEqual(store.load("approval_1"), {"foo": "bar"})
            store.delete("approval_1")
            self.assertIsNone(store.load("approval_1"))

    def test_missing_returns_none(self):
        with tempfile.TemporaryDirectory() as tempdir:
            store = FilePendingApprovalStore(tempdir)
            self.assertIsNone(store.load("nope"))

    def test_expired_is_treated_as_missing(self):
        with tempfile.TemporaryDirectory() as tempdir:
            store = FilePendingApprovalStore(tempdir)
            store.save("approval_1", {"foo": "bar"}, ttl_seconds=1)
            path = Path(tempdir) / "approval_1.json"
            # Manually rewrite the expires_at to be in the past.
            import json
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["expires_at"] = 1.0
            path.write_text(json.dumps(payload), encoding="utf-8")
            self.assertIsNone(store.load("approval_1"))


if __name__ == "__main__":
    unittest.main()
