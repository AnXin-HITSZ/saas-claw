import json
import tempfile
import unittest
from pathlib import Path

from openclaw import Agent, AssistantMessage, MockProvider
from openclaw.llm.types import UserMessage, text_content
from openclaw.session import AgentSession, CompactionSettings, RetryPolicy, SessionContextPolicy, SessionStore, Transcript, estimate_context_tokens


class SessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_session_persists_messages_and_updates_store(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            provider = MockProvider(
                [
                    AssistantMessage(
                        content=[{"type": "text", "text": "done"}],
                        stop_reason="stop",
                    )
                ]
            )
            agent = Agent(model="mock-model", provider=provider, system_prompt="You are helpful.")
            store = SessionStore(base / "sessions.json")
            transcript = Transcript(base / "session-1.jsonl")
            session = AgentSession(
                session_id="session-1",
                agent=agent,
                store=store,
                transcript=transcript,
                retry_policy=RetryPolicy(base_delay_seconds=0),
                cwd=str(base),
                workspace_dir=str(base),
            )

            result = await session.run_prompt("hello")

            self.assertEqual(result.stop_reason, "stop")
            lines = transcript.path.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 3)
            entries = [json.loads(line) for line in lines]
            self.assertEqual(entries[0]["type"], "session")
            self.assertEqual(entries[1]["parentId"], None)
            self.assertEqual(entries[2]["parentId"], entries[1]["id"])
            for line in lines:
                json.loads(line)
            store_data = json.loads((base / "sessions.json").read_text(encoding="utf-8"))
            entry = store_data["sessions"]["session-1"]
            self.assertEqual(entry["status"], "active")
            self.assertEqual(entry["model"], "mock-model")

    async def test_retry_keeps_error_in_transcript_but_removes_from_memory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            provider = MockProvider(
                [
                    RuntimeError("rate limit exceeded: 429"),
                    AssistantMessage(
                        content=[{"type": "text", "text": "recovered"}],
                        stop_reason="stop",
                    ),
                ]
            )
            agent = Agent(model="mock-model", provider=provider, system_prompt="You are helpful.")
            session = AgentSession(
                session_id="session-1",
                agent=agent,
                store=SessionStore(base / "sessions.json"),
                transcript=Transcript(base / "session-1.jsonl"),
                retry_policy=RetryPolicy(max_attempts=2, base_delay_seconds=0),
            )

            result = await session.run_prompt("hello")

            self.assertEqual(result.stop_reason, "stop")
            self.assertEqual(result.content[0]["text"], "recovered")
            self.assertFalse(
                any(getattr(message, "stop_reason", None) == "error" for message in agent.state.messages)
            )
            transcript_text = session.transcript.path.read_text(encoding="utf-8")
            self.assertIn("rate limit exceeded", transcript_text)
            self.assertIn("recovered", transcript_text)

    async def test_session_rehydrates_transcript_for_same_session_id(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            transcript_path = base / "session-1.jsonl"

            first_provider = MockProvider(
                [
                    AssistantMessage(
                        content=[{"type": "text", "text": "first answer"}],
                        stop_reason="stop",
                    )
                ]
            )
            first_agent = Agent(model="mock-model", provider=first_provider, system_prompt="You are helpful.")
            first_session = AgentSession(
                session_id="session-1",
                agent=first_agent,
                store=SessionStore(base / "sessions.json"),
                transcript=Transcript(transcript_path),
                retry_policy=RetryPolicy(base_delay_seconds=0),
            )
            await first_session.run_prompt("first question")

            second_provider = MockProvider(
                [
                    AssistantMessage(
                        content=[{"type": "text", "text": "second answer"}],
                        stop_reason="stop",
                    )
                ]
            )
            second_agent = Agent(model="mock-model", provider=second_provider, system_prompt="You are helpful.")
            second_session = AgentSession(
                session_id="session-1",
                agent=second_agent,
                store=SessionStore(base / "sessions.json"),
                transcript=Transcript(transcript_path),
                retry_policy=RetryPolicy(base_delay_seconds=0),
            )

            await second_session.run_prompt("second question")

            sent_messages = second_provider.calls[0]["messages"]
            self.assertEqual([message["role"] for message in sent_messages], ["user", "assistant", "user"])
            self.assertEqual(sent_messages[0]["content"][0]["text"], "first question")
            self.assertEqual(sent_messages[1]["content"][0]["text"], "first answer")
            self.assertEqual(sent_messages[2]["content"][0]["text"], "second question")

    async def test_transcript_compaction_replays_summary_and_retained_tail(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            transcript = Transcript(Path(temp_dir) / "session-1.jsonl")
            transcript.append_message(UserMessage(content=text_content("old user message " * 20)))
            transcript.append_message(AssistantMessage(content=[{"type": "text", "text": "old assistant message " * 20}]))
            transcript.append_message(UserMessage(content=text_content("recent user message")))
            transcript.append_message(AssistantMessage(content=[{"type": "text", "text": "recent assistant message"}]))

            preparation = transcript.compact(
                settings=CompactionSettings(context_window_tokens=100, reserve_tokens=0, keep_recent_tokens=16),
                reason="test",
            )

            self.assertTrue(preparation.should_compact)
            entries = transcript.read_entries()
            self.assertTrue(any(entry.get("type") == "compaction" for entry in entries))
            context = transcript.read_context_messages()
            self.assertEqual(context[0].role, "user")
            self.assertIn("Earlier conversation was compacted", context[0].content[0]["text"])
            self.assertIn("recent", context[-1].content[0]["text"])

    async def test_token_estimation_uses_latest_assistant_usage_plus_tail(self) -> None:
        messages = [
            UserMessage(content=text_content("before")),
            AssistantMessage(
                content=[{"type": "text", "text": "answer"}],
                usage={"prompt_tokens": 10, "completion_tokens": 5},
            ),
            UserMessage(content=text_content("tail message")),
        ]

        tokens = estimate_context_tokens(messages)

        self.assertGreater(tokens, 15)
        self.assertLess(tokens, 30)

    async def test_session_pre_prompt_compacts_when_context_budget_is_exceeded(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            transcript_path = base / "session-1.jsonl"
            transcript = Transcript(transcript_path)
            transcript.append_message(UserMessage(content=text_content("old user " * 80)))
            transcript.append_message(AssistantMessage(content=[{"type": "text", "text": "old assistant " * 80}]))

            provider = MockProvider(
                [AssistantMessage(content=[{"type": "text", "text": "new answer"}], stop_reason="stop")]
            )
            agent = Agent(model="mock-model", provider=provider, system_prompt="You are helpful.")
            session = AgentSession(
                session_id="session-1",
                agent=agent,
                store=SessionStore(base / "sessions.json"),
                transcript=transcript,
                retry_policy=RetryPolicy(base_delay_seconds=0),
                context_policy=SessionContextPolicy(
                    compaction=CompactionSettings(
                        context_window_tokens=320,
                        reserve_tokens=0,
                        keep_recent_tokens=12,
                    )
                ),
            )

            result = await session.run_prompt("current question")

            self.assertEqual(result.stop_reason, "stop")
            self.assertTrue(any(entry.get("type") == "compaction" for entry in transcript.read_entries()))
            sent_text = provider.calls[0]["messages"][0]["content"][0]["text"]
            self.assertIn("Earlier conversation was compacted", sent_text)

if __name__ == "__main__":
    unittest.main()
