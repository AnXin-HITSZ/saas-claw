import tempfile
import unittest
from pathlib import Path

from openclaw.tools.executor import make_base_context
from openclaw.tools.fs.apply_patch import create_apply_patch_tool
from openclaw.tools.fs.edit import execute_edit
from openclaw.tools.fs.write import execute_write


class FsMutationToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_write_tool_writes_workspace_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_write(context, {"path": "notes/a.txt", "content": "hello"})

            self.assertFalse(result.is_error)
            self.assertEqual((base / "notes" / "a.txt").read_text(encoding="utf-8"), "hello")

    async def test_write_tool_respects_readonly(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            context = make_base_context(cwd=base, workspace_dir=base, readonly=True)

            result = await execute_write(context, {"path": "a.txt", "content": "hello"})

            self.assertTrue(result.is_error)
            self.assertEqual(result.details["deniedReason"], "readonly")

    async def test_write_tool_blocks_protected_workspace_path(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_write(context, {"path": ".git/config", "content": "nope"})

            self.assertTrue(result.is_error)
            self.assertEqual(result.details["deniedReason"], "protected_path")

    async def test_edit_tool_replaces_exact_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            target = base / "a.txt"
            target.write_text("hello old", encoding="utf-8")
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_edit(context, {"path": "a.txt", "old_text": "old", "new_text": "new"})

            self.assertFalse(result.is_error)
            self.assertEqual(target.read_text(encoding="utf-8"), "hello new")
            self.assertEqual(result.details["replacements"], 1)

    async def test_apply_patch_tool_uses_edit_pipeline(self):
        tool = create_apply_patch_tool()
        self.assertEqual(tool.name, "apply_patch")
        self.assertEqual(tool.execution_mode, "sequential")


if __name__ == "__main__":
    unittest.main()