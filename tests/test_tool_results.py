import unittest

from openclaw.llm.types import tool_result_content
from openclaw.tools.results import ensure_tool_result, json_result, text_result


class ToolResultTests(unittest.TestCase):
    def test_text_result_outputs_text_and_details(self):
        result = text_result("hello", details={"path": "README.md"})

        self.assertEqual(result.output(), "hello")
        self.assertEqual(result.details["path"], "README.md")

    def test_json_result_preserves_payload_in_details(self):
        result = json_result({"ok": True})

        self.assertEqual(result.output(), '{"ok": true}')
        self.assertEqual(result.details["payload"], {"ok": True})

    def test_tool_result_content_carries_structured_fields(self):
        content = tool_result_content(
            "call_1",
            "read",
            "contents",
            details={"chars": 8},
            progress={"current": 1},
            terminate=True,
        )

        block = content[0]
        self.assertEqual(block["details"], {"chars": 8})
        self.assertEqual(block["progress"], {"current": 1})
        self.assertTrue(block["terminate"])

    def test_ensure_tool_result_wraps_plain_values(self):
        self.assertEqual(ensure_tool_result("hello").output(), "hello")
        self.assertEqual(ensure_tool_result({"a": 1}).details["payload"], {"a": 1})


if __name__ == "__main__":
    unittest.main()
