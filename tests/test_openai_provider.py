import unittest

from openclaw.llm.openai_provider import (
    OpenAIProvider,
    assistant_from_openai_response,
    convert_messages_to_chat_completions,
    convert_messages_to_openai_input,
    convert_tools_to_openai,
    resolve_api_mode,
)
from openclaw.llm.types import AssistantMessage, ToolResultMessage, message_to_dict, tool_call_content, tool_result_content


class FakeResponses:
    def __init__(self, events):
        self.events = events
        self.requests = []

    async def create(self, **kwargs):
        self.requests.append(kwargs)
        return FakeStream(self.events)


class FakeClient:
    def __init__(self, events):
        self.responses = FakeResponses(events)


class FakeChatCompletions:
    def __init__(self, events):
        self.events = events
        self.requests = []

    async def create(self, **kwargs):
        self.requests.append(kwargs)
        return FakeStream(self.events)


class FakeChat:
    def __init__(self, events):
        self.completions = FakeChatCompletions(events)


class FakeChatClient:
    def __init__(self, events):
        self.chat = FakeChat(events)


class FakeStream:
    def __init__(self, events):
        self.events = events

    def __aiter__(self):
        self._iter = iter(self.events)
        return self

    async def __anext__(self):
        try:
            return next(self._iter)
        except StopIteration:
            raise StopAsyncIteration


class OpenAIProviderTests(unittest.IsolatedAsyncioTestCase):
    def test_convert_tools_to_openai_function_tools(self) -> None:
        tools = convert_tools_to_openai(
            [
                {
                    "name": "read_file",
                    "description": "Read a file",
                    "input_schema": {"type": "object", "required": ["path"]},
                }
            ]
        )

        self.assertEqual(tools[0]["type"], "function")
        self.assertEqual(tools[0]["name"], "read_file")
        self.assertEqual(tools[0]["parameters"]["required"], ["path"])

    def test_convert_messages_to_openai_input(self) -> None:
        assistant = AssistantMessage(
            content=tool_call_content("call_1", "read_file", {"path": "README.md"}),
            stop_reason="toolUse",
        )
        tool = ToolResultMessage(
            content=tool_result_content("call_1", "read_file", "contents")
        )

        converted = convert_messages_to_openai_input(
            [
                {"role": "user", "content": [{"type": "text", "text": "summarize"}]},
                message_to_dict(assistant),
                message_to_dict(tool),
            ]
        )

        self.assertEqual(converted[0]["role"], "user")
        self.assertEqual(converted[1]["type"], "function_call")
        self.assertEqual(converted[1]["call_id"], "call_1")
        self.assertEqual(converted[2]["type"], "function_call_output")
        self.assertEqual(converted[2]["output"], "contents")

    def test_convert_messages_to_chat_completions_skips_empty_assistant(self) -> None:
        converted = convert_messages_to_chat_completions(
            "You are helpful.",
            [
                {"role": "user", "content": [{"type": "text", "text": "hi"}]},
                message_to_dict(AssistantMessage(content=[], stop_reason="error", error_message="boom")),
                {"role": "user", "content": [{"type": "text", "text": "try again"}]},
            ],
        )

        self.assertEqual([item["role"] for item in converted], ["system", "user", "user"])
        self.assertNotIn({"role": "assistant", "content": None}, converted)

    def test_assistant_from_response_extracts_text_and_function_call(self) -> None:
        response = {
            "model": "gpt-test",
            "status": "completed",
            "usage": {"input_tokens": 10, "output_tokens": 5},
            "output": [
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": "Need file."}],
                },
                {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "read_file",
                    "arguments": '{"path":"README.md"}',
                },
            ],
        }

        message = assistant_from_openai_response(response, model="fallback", provider="openai")

        self.assertEqual(message.stop_reason, "toolUse")
        self.assertEqual(message.content[0]["text"], "Need file.")
        self.assertEqual(message.content[1]["type"], "toolCall")
        self.assertEqual(message.content[1]["input"]["path"], "README.md")
        self.assertEqual(message.usage["input_tokens"], 10)

    def test_resolve_api_mode_uses_chat_for_custom_base_url(self) -> None:
        self.assertEqual(resolve_api_mode("auto", "https://api.deepseek.com"), "chat_completions")
        self.assertEqual(resolve_api_mode("auto", None), "responses")
        self.assertEqual(resolve_api_mode("chat-completions", None), "chat_completions")

    async def test_stream_yields_done_message_from_fake_events(self) -> None:
        response = {
            "model": "gpt-test",
            "status": "completed",
            "output": [
                {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "read_file",
                    "arguments": '{"path":"README.md"}',
                }
            ],
        }
        events = [
            {"type": "response.output_item.done", "item": response["output"][0]},
            {"type": "response.completed", "response": response},
        ]
        provider = OpenAIProvider(client=FakeClient(events), api_mode="responses")

        output = [
            event
            async for event in provider.stream(
                model="gpt-test",
                system_prompt="You are helpful.",
                messages=[],
                tools=[],
            )
        ]

        self.assertEqual(output[0].type, "start")
        self.assertEqual(output[-1].type, "done")
        message = output[-1].data["message"]
        self.assertEqual(message.stop_reason, "toolUse")
        self.assertEqual(message.content[0]["name"], "read_file")

    async def test_chat_completions_stream_yields_done_message(self) -> None:
        events = [
            {
                "choices": [
                    {
                        "delta": {"content": "hello"},
                        "finish_reason": None,
                    }
                ]
            },
            {
                "choices": [
                    {
                        "delta": {"content": " world"},
                        "finish_reason": "stop",
                    }
                ]
            },
        ]
        client = FakeChatClient(events)
        provider = OpenAIProvider(client=client, api_mode="chat_completions")

        output = [
            event
            async for event in provider.stream(
                model="deepseek-test",
                system_prompt="You are helpful.",
                messages=[{"role": "user", "content": [{"type": "text", "text": "hi"}]}],
                tools=[],
                options={"max_output_tokens": 64, "reasoning": {"effort": "low"}},
            )
        ]

        self.assertEqual(output[0].type, "start")
        self.assertEqual(output[-1].type, "done")
        message = output[-1].data["message"]
        self.assertEqual(message.content[0]["text"], "hello world")
        request = client.chat.completions.requests[0]
        self.assertEqual(request["messages"][0]["role"], "system")
        self.assertEqual(request["messages"][1]["role"], "user")
        self.assertEqual(request["max_tokens"], 64)
        self.assertNotIn("max_output_tokens", request)
        self.assertNotIn("reasoning", request)


if __name__ == "__main__":
    unittest.main()

