import unittest

from openclaw.tools.schema import ToolArgumentError, validate_tool_arguments


class ToolSchemaTests(unittest.TestCase):
    def test_required_argument(self):
        with self.assertRaisesRegex(ToolArgumentError, "missing required argument: path"):
            validate_tool_arguments({"type": "object", "required": ["path"]}, {})

    def test_type_validation(self):
        schema = {"type": "object", "properties": {"limit": {"type": "integer"}}}

        with self.assertRaisesRegex(ToolArgumentError, "limit must be integer"):
            validate_tool_arguments(schema, {"limit": "5"})

    def test_enum_validation(self):
        schema = {"type": "object", "properties": {"mode": {"type": "string", "enum": ["read", "write"]}}}

        with self.assertRaisesRegex(ToolArgumentError, "mode must be one of"):
            validate_tool_arguments(schema, {"mode": "delete"})

    def test_reject_additional_properties(self):
        schema = {"type": "object", "properties": {}, "additionalProperties": False}

        with self.assertRaisesRegex(ToolArgumentError, "unknown argument: extra"):
            validate_tool_arguments(schema, {"extra": True})


if __name__ == "__main__":
    unittest.main()
