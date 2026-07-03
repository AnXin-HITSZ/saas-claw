import tempfile
import unittest
from pathlib import Path

from openclaw.tools.fs.path_guard import WorkspacePathError, resolve_workspace_path
from openclaw.tools.fs.find import execute_find
from openclaw.tools.fs.grep import execute_grep
from openclaw.tools.fs.list_dir import execute_list_dir
from openclaw.tools.fs.read import execute_read
from openclaw.tools.builder import build_tool_registry
from openclaw.tools.executor import make_base_context


class FsToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_default_registry_includes_list_dir(self):
        registry = build_tool_registry()

        self.assertIsNotNone(registry.resolve("list_dir"))
    async def test_read_tool_reads_workspace_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            target = base / "README.md"
            target.write_text("line1\nline2\nline3", encoding="utf-8")
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_read(context, {"path": "README.md", "offset": 1, "limit": 1})

            self.assertEqual(result.output(), "line2")
            self.assertEqual(result.details["lineCount"], 3)

    async def test_read_tool_reports_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            (base / "chatdata").mkdir()
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_read(context, {"path": "chatdata"})

            self.assertTrue(result.is_error)
            self.assertEqual(result.details["status"], "is_directory")
            self.assertEqual(result.details["suggestedTool"], "list_dir")

    async def test_list_dir_lists_workspace_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            chatdata = base / "chatdata"
            chatdata.mkdir()
            (chatdata / "demo.jsonl").write_text("{}\n", encoding="utf-8")
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_list_dir(context, {"path": "chatdata"})

            payload = result.details["payload"]
            self.assertEqual(payload["entries"][0]["name"], "demo.jsonl")
            self.assertEqual(result.details["entryCount"], 1)

    async def test_grep_finds_text_matches(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            (base / "a.py").write_text("print('hello')\n", encoding="utf-8")
            (base / "b.txt").write_text("hello text\n", encoding="utf-8")
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_grep(context, {"pattern": "hello", "glob": "*.py"})

            payload = result.details["payload"]
            self.assertEqual(result.details["matchCount"], 1)
            self.assertEqual(payload["matches"][0]["relativePath"], "a.py")

    async def test_find_discovers_files_by_glob(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            (base / "src").mkdir()
            (base / "src" / "main.py").write_text("", encoding="utf-8")
            (base / "README.md").write_text("", encoding="utf-8")
            context = make_base_context(cwd=base, workspace_dir=base)

            result = await execute_find(context, {"name": "*.py", "type": "file"})

            payload = result.details["payload"]
            self.assertEqual(result.details["entryCount"], 1)
            self.assertEqual(payload["entries"][0]["relativePath"], "src\\main.py")
    async def test_read_tool_blocks_workspace_escape(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir) / "workspace"
            base.mkdir()
            outside = Path(temp_dir) / "secret.txt"
            outside.write_text("secret", encoding="utf-8")

            with self.assertRaises(WorkspacePathError):
                resolve_workspace_path(str(outside), cwd=base, workspace_dir=base)


if __name__ == "__main__":
    unittest.main()

