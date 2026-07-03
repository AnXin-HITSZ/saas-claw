import io
import os
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from openclaw.cli import assistant_text, build_model_options, format_non_text_blocks, main
from openclaw.llm.types import AssistantMessage


class CliTests(unittest.TestCase):
    def run_cli(self, argv, *, chatdata_dir=None):
        stdout = io.StringIO()
        stderr = io.StringIO()
        full_argv = list(argv)
        if chatdata_dir is not None and "--chatdata-dir" not in full_argv:
            full_argv = ["--chatdata-dir", str(chatdata_dir), *full_argv]
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = main(full_argv)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_mock_prompt_prints_text_and_writes_transcript(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            chatdata_dir = Path(temp_dir)
            code, stdout, stderr = self.run_cli(
                ["--provider", "mock", "--session-id", "test-session", "hello"],
                chatdata_dir=chatdata_dir,
            )

            self.assertEqual(code, 0)
            self.assertEqual(stdout.strip(), "mock response: hello")
            self.assertEqual(stderr, "")

            transcript = chatdata_dir / "test-session.jsonl"
            self.assertTrue(transcript.exists())
            lines = transcript.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 3)
            entries = [json.loads(line) for line in lines]
            self.assertEqual(entries[0]["type"], "session")
            self.assertEqual(entries[1]["message"]["role"], "user")
            self.assertEqual(entries[2]["message"]["role"], "assistant")

            store = json.loads((chatdata_dir / "sessions.json").read_text(encoding="utf-8"))
            self.assertIn("test-session", store["sessions"])
            self.assertEqual(store["sessions"]["test-session"]["session_file"], "test-session.jsonl")

    def test_transcripts_show_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            chatdata_dir = Path(temp_dir)
            self.run_cli(
                ["--provider", "mock", "--session-id", "demo", "hello"],
                chatdata_dir=chatdata_dir,
            )

            code, stdout, stderr = self.run_cli(
                ["transcripts", "show", "demo", "--format", "text"],
                chatdata_dir=chatdata_dir,
            )

        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        self.assertIn("user: hello", stdout)
        self.assertIn("assistant: mock response: hello", stdout)

    def test_transcripts_show_detail(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            chatdata_dir = Path(temp_dir)
            self.run_cli(
                ["--provider", "mock", "--session-id", "demo", "hello"],
                chatdata_dir=chatdata_dir,
            )

            code, stdout, stderr = self.run_cli(
                ["transcripts", "show", "demo", "--format", "detail"],
                chatdata_dir=chatdata_dir,
            )

        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        self.assertIn("assistant provider=mock model=mock-model stop=stop", stdout)
        self.assertIn("mock response: hello", stdout)

    def test_transcripts_show_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            chatdata_dir = Path(temp_dir)
            self.run_cli(
                ["--provider", "mock", "--session-id", "demo", "hello"],
                chatdata_dir=chatdata_dir,
            )

            code, stdout, stderr = self.run_cli(
                ["transcripts", "show", "demo", "--format", "json"],
                chatdata_dir=chatdata_dir,
            )

        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        entries = json.loads(stdout)
        self.assertEqual(entries[0]["type"], "session")
        self.assertEqual(entries[1]["message"]["role"], "user")
        self.assertEqual(entries[2]["message"]["role"], "assistant")

    def test_transcripts_show_missing_session(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            code, stdout, stderr = self.run_cli(
                ["transcripts", "show", "missing"],
                chatdata_dir=temp_dir,
            )

        self.assertEqual(code, 2)
        self.assertEqual(stdout, "")
        self.assertIn("transcript not found", stderr)

    def test_gateway_run_is_registered_placeholder(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            code, stdout, stderr = self.run_cli(["gateway", "run"], chatdata_dir=temp_dir)

        self.assertEqual(code, 2)
        self.assertEqual(stdout, "")
        self.assertIn("gateway run is registered but not implemented yet", stderr)

    def test_no_arguments_prints_help(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            code, stdout, stderr = self.run_cli([], chatdata_dir=temp_dir)

        self.assertEqual(code, 0)
        self.assertIn("usage: pyclaw", stdout)
        self.assertEqual(stderr, "")

    def test_assistant_text_joins_text_blocks(self):
        message = AssistantMessage(
            content=[
                {"type": "text", "text": "hello"},
                {"type": "toolCall", "id": "call_1", "name": "noop", "input": {}},
                {"type": "text", "text": " world"},
            ]
        )

        self.assertEqual(assistant_text(message), "hello world")

    def test_tools_list(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            code, stdout, stderr = self.run_cli(["tools", "list"], chatdata_dir=temp_dir)

        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        self.assertIn("read", stdout)
        self.assertIn("list_dir", stdout)
        self.assertIn("web_fetch", stdout)

    def test_tools_describe_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            code, stdout, stderr = self.run_cli(["--json", "tools", "describe", "read"], chatdata_dir=temp_dir)

        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        data = json.loads(stdout)
        self.assertEqual(data["name"], "read")
        self.assertEqual(data["input_schema"]["required"], ["path"])

    def test_tools_run_read_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            (base / "sample.txt").write_text("hello tools", encoding="utf-8")
            old_cwd = os.getcwd()
            os.chdir(base)
            try:
                code, stdout, stderr = self.run_cli(
                    ["--json", "tools", "run", "read", '{"path":"sample.txt"}'],
                    chatdata_dir=base / "chatdata",
                )
            finally:
                os.chdir(old_cwd)

        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        data = json.loads(stdout)
        self.assertEqual(data["output"], "hello tools")
        self.assertFalse(data["is_error"])

    def test_tools_run_shell_requires_interactive_approval(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            code, stdout, stderr = self.run_cli(
                [
                    "--shell-approval",
                    "require",
                    "--json",
                    "tools",
                    "run",
                    "exec",
                    '{"command":"git add ."}',
                ],
                chatdata_dir=temp_dir,
            )

        self.assertEqual(code, 1)
        self.assertIn("pyclaw shell approval required", stderr)
        self.assertIn("git add .", stderr)
        self.assertNotEqual(stdout, "", f"code={code} stderr={stderr!r}")
        self.assertTrue(stdout.lstrip().startswith("{"), f"code={code} stdout={stdout!r} stderr={stderr!r}")
        data = json.loads(stdout)
        self.assertTrue(data["is_error"])
        self.assertEqual(data["details"]["deniedReason"], "approval_required")

    def test_format_non_text_blocks_includes_tool_details(self):
        message = {
            "content": [
                {
                    "type": "toolResult",
                    "tool_call_id": "call_1",
                    "name": "read",
                    "output": "hello",
                    "details": {"path": "README.md", "chars": 5},
                }
            ]
        }

        text = format_non_text_blocks(message)

        self.assertIn("toolResult read hello", text)
        self.assertIn('"path":"README.md"', text)
        self.assertIn('"chars":5', text)
    def test_build_model_options(self):
        class Args:
            reasoning_effort = "low"
            max_output_tokens = 128

        self.assertEqual(
            build_model_options(Args()),
            {"reasoning": {"effort": "low"}, "max_output_tokens": 128},
        )


if __name__ == "__main__":
    unittest.main()
