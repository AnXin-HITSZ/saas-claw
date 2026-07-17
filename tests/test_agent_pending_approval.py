"""End-to-end pending approval tests through the Agent API surface."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest

HAS_API_DEPS = bool(importlib.util.find_spec("fastapi")) and bool(importlib.util.find_spec("httpx"))


def _build_scripted_provider(sequence):
    """Return a MockProvider with the scripted messages configured."""

    from openclaw.llm.provider import MockProvider
    from openclaw.llm.types import AssistantMessage

    responses = []
    for entry in sequence:
        stop_reason = entry.get("stop_reason", "stop")
        responses.append(
            AssistantMessage(
                content=entry["content"],
                provider="mock",
                model="mock-model",
                stop_reason=stop_reason,
            )
        )
    return MockProvider(responses)


@unittest.skipUnless(HAS_API_DEPS, "FastAPI/httpx are not installed; install with .[api]")
class AgentPendingApprovalApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        from openclaw import api
        from openclaw.tools.approval_store import FilePendingApprovalStore

        self.tempdir = tempfile.TemporaryDirectory()
        self.approval_dir = tempfile.TemporaryDirectory()
        self.store = FilePendingApprovalStore(self.approval_dir.name)
        api._set_pending_approval_store(self.store)

        self._original_build_provider = api.build_provider

        tool_call_message = [
            {
                "type": "toolCall",
                "id": "call_1",
                "name": "write_file",
                "input": {"file_path": "a.txt", "content": "hi"},
            }
        ]
        final_text_message = [{"type": "text", "text": "文件操作已处理"}]

        # The first invocation drives the loop into a toolUse; each subsequent
        # invocation (on resume) delivers the final assistant reply.
        self._call_index = 0

        def _fake_build_provider(request, *, model):  # noqa: ANN001
            # Every run gets its own MockProvider so ordering is predictable.
            if request.prompt.startswith("PLEASE_WRITE"):
                return _build_scripted_provider(
                    [
                        {"content": tool_call_message, "stop_reason": "toolUse"},
                        {"content": final_text_message, "stop_reason": "stop"},
                    ]
                )
            # Resume path — the resumed loop must produce a single stop message.
            return _build_scripted_provider(
                [
                    {"content": final_text_message, "stop_reason": "stop"},
                ]
            )

        api.build_provider = _fake_build_provider

    async def asyncTearDown(self) -> None:
        from openclaw import api

        api.build_provider = self._original_build_provider
        api._set_pending_approval_store(None)
        self.tempdir.cleanup()
        self.approval_dir.cleanup()

    async def test_medium_risk_returns_pending_approval_via_api(self) -> None:
        from openclaw.api import AgentRunRequest, run_agent_request

        request = AgentRunRequest(
            prompt="PLEASE_WRITE hello",
            provider="mock",
            model="mock-model",
            tool_profile="coding",
            sandbox_base_url="http://sandbox.local",
            chatdata_dir=self.tempdir.name,
            claw_id="claw-1",
            owner_user_id="user-1",
        )
        outcome = await run_agent_request(request)
        self.assertEqual(outcome.status, "PENDING_APPROVAL")
        self.assertIsNotNone(outcome.approval)
        self.assertEqual(outcome.approval.tool_name, "write_file")
        state = self.store.load(outcome.approval.approval_id)
        self.assertIsNotNone(state)
        self.assertEqual(state["tool_call"]["name"], "write_file")

    async def test_resume_reject_produces_completed(self) -> None:
        from openclaw.api import AgentResumeRequest, AgentRunRequest, resume_agent_request, run_agent_request

        run_request = AgentRunRequest(
            prompt="PLEASE_WRITE hello",
            provider="mock",
            model="mock-model",
            tool_profile="coding",
            sandbox_base_url="http://sandbox.local",
            chatdata_dir=self.tempdir.name,
            claw_id="claw-1",
            owner_user_id="user-1",
        )
        outcome = await run_agent_request(run_request)
        self.assertEqual(outcome.status, "PENDING_APPROVAL")

        resume_request = AgentResumeRequest(
            approval_id=outcome.approval.approval_id,
            decision="REJECTED",
            rejection_reason="用户取消",
            provider="mock",
            model="mock-model",
            tool_profile="coding",
            sandbox_base_url="http://sandbox.local",
            chatdata_dir=self.tempdir.name,
        )
        resume_outcome = await resume_agent_request(resume_request)
        self.assertEqual(resume_outcome.status, "COMPLETED")
        self.assertIsNotNone(resume_outcome.message)
        self.assertIsNone(self.store.load(outcome.approval.approval_id))


if __name__ == "__main__":
    unittest.main()
