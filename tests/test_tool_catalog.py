import unittest

from openclaw.tools.builder import core_tool_definitions


class ToolCatalogTests(unittest.TestCase):
    def test_catalog_metadata_is_applied_to_materialized_tools(self):
        tools = {tool.name: tool for tool in core_tool_definitions()}

        self.assertIn("sandbox_read_file", tools)
        self.assertEqual(tools["sandbox_read_file"].metadata.section_id, "sandbox")
        self.assertIn("readonly", tools["sandbox_read_file"].metadata.tags)
        self.assertTrue(tools["sandbox_read_file"].metadata.readonly)
        self.assertEqual(tools["sandbox_apply_patch"].metadata.risk, "medium")
        self.assertNotIn("read", tools)
        self.assertNotIn("web_fetch", tools)


if __name__ == "__main__":
    unittest.main()
