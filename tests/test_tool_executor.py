import asyncio
import tempfile
import unittest
from pathlib import Path

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.executor import execute_tool_call, execute_tool_call_batch, make_base_context
from openclaw.tools.hooks import ToolHookDecision
from openclaw.tools.registry import FunctionTool, ToolRegistry
from openclaw.tools.results import text_result
from openclaw.tools.types import ToolDefinition, ToolMetadata


class BlockingHooks:
    async def before_tool_call(self, call, tool, arguments, context):
        return ToolHookDecision(allowed=False, reason="blocked", denied_reason="test")

    async def after_tool_call(self, call, tool, arguments, result, context):
        return result


class RewritingHooks:
    async def before_tool_call(self, call, tool, arguments, context):
        args = dict(arguments)
        args["value"] = "rewritten"
        return ToolHookDecision(arguments=args)

    async def after_tool_call(self, call, tool, arguments, result, context):
        return text_result(result.output() + " after", details={"hooked": True})


class ToolExecutorTests(unittest.IsolatedAsyncioTestCase):
    async def test_function_tool_result_is_wrapped(self):
        registry = ToolRegistry()
        registry.register(FunctionTool(name="echo", description="Echo", func=lambda value: value))

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="echo", input={"value": "hi"}),
            registry,
            make_base_context(),
        )

        self.assertEqual(outcome.message.content[0]["output"], "hi")
        self.assertFalse(outcome.message.content[0]["is_error"])

    async def test_missing_tool_is_error_result(self):
        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="missing", input={}),
            ToolRegistry(),
            make_base_context(),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertIn("tool not found", outcome.result.output())

    async def test_before_hook_can_block(self):
        registry = ToolRegistry()
        registry.register(FunctionTool(name="echo", description="Echo", func=lambda: "never"))

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="echo", input={}),
            registry,
            make_base_context(),
            BlockingHooks(),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertEqual(outcome.message.content[0]["details"]["status"], "blocked")

    async def test_hooks_can_rewrite_arguments_and_result(self):
        registry = ToolRegistry()
        registry.register(FunctionTool(name="echo", description="Echo", func=lambda value: value))

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="echo", input={"value": "original"}),
            registry,
            make_base_context(),
            RewritingHooks(),
        )

        self.assertEqual(outcome.result.output(), "rewritten after")
        self.assertEqual(outcome.message.content[0]["details"], {"hooked": True})

    async def test_parallel_batch_runs_parallel_tools(self):
        order = []

        async def slow(context, arguments):
            await asyncio.sleep(0.05)
            order.append(arguments["value"])
            return text_result(arguments["value"])

        registry = ToolRegistry()
        for name in ("a", "b"):
            registry.register(
                ToolDefinition(
                    name=name,
                    label=name,
                    description=name,
                    input_schema={"type": "object", "properties": {"value": {"type": "string"}}},
                    execute=slow,
                    execution_mode="parallel",
                    metadata=ToolMetadata(),
                )
            )

        outcomes = await execute_tool_call_batch(
            [
                ToolCallBlock(id="1", name="a", input={"value": "a"}),
                ToolCallBlock(id="2", name="b", input={"value": "b"}),
            ],
            registry,
            make_base_context(),
        )

        self.assertEqual([item.result.output() for item in outcomes], ["a", "b"])
        self.assertCountEqual(order, ["a", "b"])


if __name__ == "__main__":
    unittest.main()
