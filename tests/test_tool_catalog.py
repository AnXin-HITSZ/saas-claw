import unittest

from openclaw.tools.builder import core_tool_definitions


class ToolCatalogTests(unittest.TestCase):
    def test_catalog_metadata_is_applied_to_materialized_tools(self):
        tools = {tool.name: tool for tool in core_tool_definitions()}

        self.assertEqual(tools["read"].metadata.section_id, "filesystem")
        self.assertIn("readonly", tools["read"].metadata.tags)
        self.assertEqual(tools["exec"].metadata.risk, "high")
        self.assertFalse(tools["web_fetch"].metadata.workspace_only)


if __name__ == "__main__":
    unittest.main()