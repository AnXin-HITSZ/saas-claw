import unittest

from openclaw.tools.schema import tool_to_chat_completions_schema, tool_to_responses_schema


class ToolSchemaNormalizationTests(unittest.TestCase):
    def test_responses_schema_shape(self):
        tool = {"name": "read", "description": "Read", "input_schema": {"required": ["path"]}}

        converted = tool_to_responses_schema(tool)

        self.assertEqual(converted["type"], "function")
        self.assertEqual(converted["name"], "read")
        self.assertEqual(converted["parameters"]["type"], "object")
        self.assertEqual(converted["parameters"]["required"], ["path"])

    def test_chat_completions_schema_shape(self):
        tool = {"name": "read", "description": "Read", "input_schema": {"type": "object"}}

        converted = tool_to_chat_completions_schema(tool)

        self.assertEqual(converted["type"], "function")
        self.assertEqual(converted["function"]["name"], "read")
        self.assertEqual(converted["function"]["parameters"]["properties"], {})


if __name__ == "__main__":
    unittest.main()
