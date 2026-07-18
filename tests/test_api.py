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

    def test_channel_webhook_routes_are_registered(self) -> None:
        from openclaw.api import app

        routes = {(getattr(route, "path", None), tuple(sorted(getattr(route, "methods", []) or []))) for route in app.routes}

        self.assertIn(("/v1/channels/wechat/webhook", ("GET",)), routes)
        self.assertIn(("/v1/channels/wechat/webhook", ("POST",)), routes)
        self.assertIn(("/v1/channels/feishu/webhook", ("POST",)), routes)

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

    def test_agent_run_requires_api_token_when_configured(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict("os.environ", {"PYCLAW_API_TOKEN": "secret-token"}, clear=False):
                response = self.client.post(
                    "/v1/agent/run",
                    json={
                        "prompt": "hello api",
                        "provider": "mock",
                        "session_id": "api-demo",
                        "chatdata_dir": temp_dir,
                    },
                )

                self.assertEqual(response.status_code, 401)

                allowed = self.client.post(
                    "/v1/agent/run",
                    headers={"Authorization": "Bearer secret-token"},
                    json={
                        "prompt": "hello api",
                        "provider": "mock",
                        "session_id": "api-demo",
                        "chatdata_dir": temp_dir,
                    },
                )

            self.assertEqual(allowed.status_code, 200, allowed.text)

    def test_runtime_tool_resolution_log_includes_effective_tools(self) -> None:
        from openclaw.api import (
            AgentRunRequest,
            build_policy,
            log_runtime_tool_resolution,
            resolve_runtime_tools,
        )

        request = AgentRunRequest(
            prompt="hello",
            provider="mock",
            model="mock-model",
            tool_profile="coding",
            tools_deny=["shell"],
            sandbox_base_url="http://sandbox.local",
            claw_id="claw-1",
            role_key="writer",
            agent_key="agent-a",
        )
        policy = build_policy(request)
        resolved_tools = resolve_runtime_tools(policy)

        with self.assertLogs("openclaw.api", level="INFO") as logs:
            log_runtime_tool_resolution(
                phase="run",
                request=request,
                model="mock-model",
                policy=policy,
                resolved_tools=resolved_tools,
            )

        output = "\n".join(logs.output)
        self.assertIn("phase=run", output)
        self.assertIn("provider=mock", output)
        self.assertIn("requested_profile=coding", output)
        self.assertIn("workspace_info", output)
        self.assertIn("read_file", output)
        self.assertIn("tools_deny=['shell']", output)

    def test_empty_tools_allow_uses_profile_defaults(self) -> None:
        from openclaw.api import AgentRunRequest, build_policy, resolve_runtime_tools

        request = AgentRunRequest(
            prompt="hello",
            provider="mock",
            model="mock-model",
            tool_profile="full",
            tools_allow=[],
            sandbox_base_url="http://sandbox.local",
        )

        policy = build_policy(request)
        resolved_tools = resolve_runtime_tools(policy)

        self.assertIsNone(policy.allow)
        self.assertIn("workspace_info", [tool.name for tool in resolved_tools.tools])
        self.assertIn("read_file", [tool.name for tool in resolved_tools.tools])

    def test_tools_resolve_empty_allow_uses_profile_defaults(self) -> None:
        response = self.client.post(
            "/v1/tools/resolve",
            json={
                "profile": "full",
                "allow": [],
            },
        )

        self.assertEqual(response.status_code, 200, response.text)
        tool_names = [tool["name"] for tool in response.json()["tools"]]
        self.assertIn("workspace_info", tool_names)
        self.assertIn("read_file", tool_names)


if __name__ == "__main__":
    unittest.main()
