import unittest

from openclaw.tools.builder import build_tool_registry
from openclaw.tools.catalog import build_tool_groups, materialize_core_tools
from openclaw.tools.policy import ToolPolicy, apply_tool_policy_pipeline, expand_tool_names


class ToolPolicyTests(unittest.TestCase):
    def test_expand_tool_group_names(self):
        expanded = expand_tool_names({"group:fs"})

        self.assertIn("read", expanded)
        self.assertIn("grep", expanded)
        self.assertIn("apply_patch", expanded)

    def test_allow_group_selects_group_tools(self):
        registry = build_tool_registry(ToolPolicy(allow={"group:readonly"}, profile="minimal"))

        self.assertIsNotNone(registry.resolve("read"))
        self.assertIsNotNone(registry.resolve("grep"))
        self.assertIsNotNone(registry.resolve("find"))
        self.assertIsNone(registry.resolve("write"))
        self.assertIsNone(registry.resolve("exec"))

    def test_also_allow_adds_tool_to_profile(self):
        registry = build_tool_registry(ToolPolicy(profile="coding", also_allow={"web_fetch"}))

        self.assertIsNotNone(registry.resolve("read"))
        self.assertIsNotNone(registry.resolve("web_fetch"))

    def test_readonly_policy_filters_mutating_tools(self):
        registry = build_tool_registry(ToolPolicy(profile="full", readonly=True))

        self.assertIsNotNone(registry.resolve("read"))
        self.assertIsNotNone(registry.resolve("grep"))
        self.assertIsNone(registry.resolve("write"))
        self.assertIsNone(registry.resolve("exec"))

    def test_pipeline_reports_removed_tools(self):
        result = apply_tool_policy_pipeline(materialize_core_tools(), ToolPolicy(profile="coding"))

        stage_names = [entry.stage for entry in result.audit]
        self.assertIn("profile", stage_names)
        removed_by_profile = next(entry.removed for entry in result.audit if entry.stage == "profile")
        self.assertIn("exec", removed_by_profile)

    def test_catalog_builds_groups_from_tags(self):
        groups = build_tool_groups()

        self.assertIn("read", groups["group:readonly"])
        self.assertIn("write", groups["group:mutation"])
        self.assertIn("web_fetch", groups["group:network"])


if __name__ == "__main__":
    unittest.main()