import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HAS_API_DEPS = bool(importlib.util.find_spec("fastapi")) and bool(importlib.util.find_spec("httpx"))


@unittest.skipUnless(HAS_API_DEPS, "FastAPI/httpx are not installed; install with .[api]")
class ApiTests(unittest.TestCase):
    def setUp(self) -> None:
        from fastapi.testclient import TestClient
        from openclaw.api import app

        self.client = TestClient(app)

    def test_healthz(self) -> None:
        response = self.client.get("/healthz")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")
        self.assertEqual(response.json()["service"], "pyclaw-api")

    def test_agent_run_with_mock_provider_writes_transcript(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            response = self.client.post(
                "/v1/agent/run",
                json={
                    "prompt": "hello api",
                    "provider": "mock",
                    "session_id": "api-demo",
                    "chatdata_dir": temp_dir,
                },
            )

            self.assertEqual(response.status_code, 200, response.text)
            data = response.json()
            self.assertEqual(data["session_id"], "api-demo")
            self.assertEqual(data["text"], "mock response: hello api")
            self.assertEqual(data["message"]["role"], "assistant")

            transcript = Path(temp_dir) / "api-demo.jsonl"
            self.assertTrue(transcript.exists())
            entries = [json.loads(line) for line in transcript.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(entries[0]["type"], "session")
            self.assertEqual(entries[1]["message"]["role"], "user")
            self.assertEqual(entries[2]["message"]["role"], "assistant")

    def test_openai_provider_without_api_key_returns_400(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict("os.environ", {"OPENAI_API_KEY": ""}, clear=False):
                response = self.client.post(
                    "/v1/agent/run",
                    json={
                        "prompt": "hello",
                        "provider": "openai",
                        "chatdata_dir": temp_dir,
                    },
                )

            self.assertEqual(response.status_code, 400)
            self.assertIn("OPENAI_API_KEY", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()