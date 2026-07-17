import unittest

from openclaw.tools.builder import core_tool_definitions
from openclaw.tools.resolver import ToolResolveInput, resolve_tools


class ToolCatalogTests(unittest.TestCase):
    def test_catalog_metadata_is_applied_to_materialized_tools(self):
        tools = {tool.name: tool for tool in core_tool_definitions()}

        self.assertIn("read_file", tools)
        self.assertEqual(tools["read_file"].metadata.section_id, "workspace")
        self.assertEqual(tools["read_file"].metadata.execution_scope, "claw_sandbox")
        self.assertIn("readonly", tools["read_file"].metadata.tags)
        self.assertTrue(tools["read_file"].metadata.readonly)
        self.assertEqual(tools["apply_patch"].metadata.risk, "medium")
        self.assertNotIn("read", tools)
        self.assertFalse(any(name.startswith("sandbox" + "_") for name in tools))

    def test_resolve_tools_returns_workspace_tools(self):
        result = resolve_tools(ToolResolveInput(profile="coding"))

        names = [tool.name for tool in result.tools]
        self.assertIn("read_file", names)
        self.assertIn("write_file", names)
        self.assertFalse(any(name.startswith("sandbox" + "_") for name in names))
        self.assertGreaterEqual(len(result.prompt_fragments), 1)
        prompt = "\n".join(fragment.content for fragment in result.prompt_fragments)
        self.assertIn("当前可用工具：", prompt)
        self.assertIn("read_file", prompt)
        self.assertNotIn("sandbox" + "_", prompt)


if __name__ == "__main__":
    unittest.main()