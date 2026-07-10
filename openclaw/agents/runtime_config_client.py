"""Spring Backend runtime config client for routed agents."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any

from openclaw.routing.models import AgentRouteBinding, binding_from_mapping


@dataclass(frozen=True)
class AgentRuntimeToolPolicy:
    profile: str = "messaging"
    allow: list[str] | None = None
    deny: list[str] = field(default_factory=list)
    also_allow: list[str] = field(default_factory=list)
    workspace_only: bool = True
    readonly: bool = False
    shell_approval: str = "deny"
    web_access: bool = False


@dataclass(frozen=True)
class AgentRuntimeConfig:
    agent_id: str
    agent_key: str
    name: str | None = None
    enabled: bool = True
    provider: str = "openai"
    model: str | None = None
    api_mode: str = "auto"
    base_url: str | None = None
    api_key: str | None = None
    system: str | None = None
    workspace_dir: str | None = None
    runtime_type: str = "agent_session"
    tool_policy: AgentRuntimeToolPolicy = field(default_factory=AgentRuntimeToolPolicy)
    config_version: str | None = None


class RuntimeConfigClient:
    def __init__(self, *, base_url: str | None = None, token: str | None = None, timeout: float | None = None) -> None:
        self.base_url = (base_url or os.environ.get("OPENCLAW_SPRING_BACKEND_BASE_URL") or "").strip().rstrip("/")
        self.token = (
            token
            or os.environ.get("OPENCLAW_RUNTIME_CONFIG_TOKEN")
            or os.environ.get("OPENCLAW_INTERNAL_API_TOKEN")
            or os.environ.get("PYCLAW_API_TOKEN")
            or ""
        ).strip()
        self.timeout = timeout if timeout is not None else float(os.environ.get("OPENCLAW_RUNTIME_CONFIG_TIMEOUT_SECONDS", "3"))

    def load_route_bindings(self) -> list[AgentRouteBinding]:
        payload = self._get_json("/api/internal/route-bindings/runtime")
        if not isinstance(payload, list):
            raise ValueError("Spring route bindings runtime response must be a list")
        return [binding_from_mapping(item) for item in payload if isinstance(item, dict)]

    def load_agent_runtime_config(self, agent_key: str) -> AgentRuntimeConfig:
        payload = self._get_json(f"/api/internal/agents/{urllib.parse.quote(agent_key, safe='')}/runtime-config")
        if not isinstance(payload, dict):
            raise ValueError("Spring agent runtime response must be an object")
        policy = payload.get("toolPolicy") or payload.get("tool_policy") or {}
        if not isinstance(policy, dict):
            policy = {}
        return AgentRuntimeConfig(
            agent_id=str(payload.get("agentId") or payload.get("agent_id") or agent_key),
            agent_key=str(payload.get("agentKey") or payload.get("agent_key") or agent_key),
            name=_optional_str(payload.get("name")),
            enabled=_bool(payload.get("enabled"), True),
            provider=str(payload.get("provider") or "openai"),
            model=_optional_str(payload.get("model")),
            api_mode=str(payload.get("apiMode") or payload.get("api_mode") or "auto"),
            base_url=_optional_str(payload.get("baseUrl") or payload.get("base_url")),
            api_key=_optional_str(payload.get("apiKey") or payload.get("api_key")),
            system=_optional_str(payload.get("system")),
            workspace_dir=_optional_str(payload.get("workspaceDir") or payload.get("workspace_dir")),
            runtime_type=str(payload.get("runtimeType") or payload.get("runtime_type") or "agent_session"),
            tool_policy=AgentRuntimeToolPolicy(
                profile=str(policy.get("profile") or "messaging"),
                allow=_list_or_none(policy.get("allow")),
                deny=_list(policy.get("deny")),
                also_allow=_list(policy.get("alsoAllow") or policy.get("also_allow")),
                workspace_only=_bool(policy.get("workspaceOnly") or policy.get("workspace_only"), True),
                readonly=_bool(policy.get("readonly"), False),
                shell_approval=str(policy.get("shellApproval") or policy.get("shell_approval") or "deny"),
                web_access=_bool(policy.get("webAccess") or policy.get("web_access"), False),
            ),
            config_version=_optional_str(payload.get("configVersion") or payload.get("config_version")),
        )

    def _get_json(self, path: str) -> Any:
        if not self.base_url:
            raise ValueError("OPENCLAW_SPRING_BACKEND_BASE_URL is required for routed agent runtime config")
        if not self.token:
            raise ValueError("OPENCLAW_RUNTIME_CONFIG_TOKEN or PYCLAW_API_TOKEN is required for routed agent runtime config")
        request = urllib.request.Request(
            self.base_url + path,
            headers={"Authorization": "Bearer " + self.token},
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise ValueError(f"Spring runtime config request failed: HTTP {exc.code} {detail}") from exc
        except urllib.error.URLError as exc:
            raise ValueError(f"Spring runtime config request failed: {exc.reason}") from exc


def _list_or_none(value: Any) -> list[str] | None:
    if value is None:
        return None
    return _list(value)


def _list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    text = str(value).strip()
    return [item.strip() for item in text.split(",") if item.strip()] if text else []


def _bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _optional_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
