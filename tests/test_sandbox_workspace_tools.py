import unittest
from unittest.mock import patch

from openclaw.llm.types import ToolCallBlock
from openclaw.tools.executor import execute_tool_call, make_base_context
from openclaw.tools.registry import ToolRegistry
from openclaw.tools.sandbox_workspace import create_sandbox_apply_patch_tool, create_sandbox_list_files_tool


class SandboxWorkspaceToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_sandbox_tool_reads_base_url_from_execution_context_metadata(self):
        registry = ToolRegistry()
        registry.register(create_sandbox_list_files_tool())
        context = make_base_context(metadata={"sandbox_base_url": "http://sandbox-runner:8000"})

        with patch("openclaw.tools.sandbox_workspace._sandbox_get", return_value={"items": []}) as sandbox_get:
            outcome = await execute_tool_call(
                ToolCallBlock(id="call_1", name="sandbox_list_files", input={"path": "."}),
                registry,
                context,
            )

        self.assertFalse(outcome.result.is_error)
        sandbox_get.assert_called_once_with("http://sandbox-runner:8000", "/v1/workspace/files?path=.")


    async def test_sandbox_apply_patch_uses_arguments_and_context_metadata(self):
        registry = ToolRegistry()
        registry.register(create_sandbox_apply_patch_tool())
        context = make_base_context(metadata={"sandbox_base_url": "http://sandbox-runner:8000"})

        with patch("openclaw.tools.sandbox_workspace._sandbox_post_json", return_value={"patched": True}) as sandbox_post:
            outcome = await execute_tool_call(
                ToolCallBlock(
                    id="call_1",
                    name="sandbox_apply_patch",
                    input={
                        "file_path": "hello.txt",
                        "old_text": "hello",
                        "new_text": "hello pyclaw",
                        "replace_all": True,
                    },
                ),
                registry,
                context,
            )

        self.assertFalse(outcome.result.is_error)
        sandbox_post.assert_called_once_with(
            "http://sandbox-runner:8000",
            "/v1/workspace/patches",
            {
                "file_path": "hello.txt",
                "old_text": "hello",
                "new_text": "hello pyclaw",
                "replace_all": True,
            },
        )

    async def test_sandbox_write_file_uses_json_payload_and_context_metadata(self):
        from openclaw.tools.sandbox_workspace import create_sandbox_write_file_tool

        registry = ToolRegistry()
        registry.register(create_sandbox_write_file_tool())
        context = make_base_context(metadata={"sandbox_base_url": "http://sandbox-runner:8000"})

        with patch("openclaw.tools.sandbox_workspace._sandbox_put_json", return_value={"size": 12}) as sandbox_put:
            outcome = await execute_tool_call(
                ToolCallBlock(
                    id="call_1",
                    name="sandbox_write_file",
                    input={
                        "file_path": "hello.txt",
                        "content": "hello pyclaw",
                    },
                ),
                registry,
                context,
            )

        self.assertFalse(outcome.result.is_error)
        sandbox_put.assert_called_once_with(
            "http://sandbox-runner:8000",
            "/v1/workspace/files/hello.txt",
            {"content": "hello pyclaw"},
        )

    async def test_sandbox_tool_reports_missing_runtime_context(self):
        registry = ToolRegistry()
        registry.register(create_sandbox_list_files_tool())

        outcome = await execute_tool_call(
            ToolCallBlock(id="call_1", name="sandbox_list_files", input={"path": "."}),
            registry,
            make_base_context(),
        )

        self.assertTrue(outcome.result.is_error)
        self.assertIn("sandbox_base_url", outcome.result.output())


if __name__ == "__main__":
    unittest.main()