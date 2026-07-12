# pyclaw 第一阶段 API 服务入口实现记录

本文记录根据 `docs/000/pyclaw-k8s-deployment-technical-design.md` 完成的第一阶段实现：为 pyclaw 新增最小 FastAPI HTTP 服务入口，使项目从纯 CLI 运行形态迈向可被 Docker / Kubernetes Deployment 承载的长驻服务形态。

## 1. 本阶段目标

第一阶段只做最小服务化入口，不引入 Worker、Redis、数据库或 Helm。目标是让本地可以启动一个长期运行的 HTTP 服务：

```bash
uvicorn openclaw.api:app --host 0.0.0.0 --port 8000
```

并提供两个接口：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/healthz` | 健康检查，给本地测试、Docker HEALTHCHECK、K8s liveness/readiness probe 使用。 |
| `POST` | `/v1/agent/run` | 提交一次最小 agent 调用，内部复用现有 `Agent` / `AgentSession` / transcript / tools 能力。 |

## 2. 本次修改文件

| 文件 | 修改说明 |
| --- | --- |
| `openclaw/api.py` | 新增 FastAPI 服务入口，包含请求/响应模型、healthz、agent run、provider/session/tool/context 构造逻辑。 |
| `pyproject.toml` | 新增 `api` 与 `all` optional dependencies。 |
| `tests/test_api.py` | 新增 API 测试；当环境未安装 FastAPI/httpx 时自动 skip。 |
| `docs/000/pyclaw-api-entrypoint-implementation-notes.md` | 本实现记录。 |

## 3. 依赖设计

新增可选依赖：

```toml
[project.optional-dependencies]
api = ["fastapi>=0.115.0", "uvicorn[standard]>=0.30.0", "httpx>=0.27.0"]
all = ["openai>=1.68.0", "fastapi>=0.115.0", "uvicorn[standard]>=0.30.0", "httpx>=0.27.0"]
```

安装 API 运行依赖：

```cmd
python -m pip install -e ".[api]"
```

如果同时需要 OpenAI provider 与 API 服务：

```cmd
python -m pip install -e ".[all]"
```

说明：

- `fastapi`：HTTP 框架。
- `uvicorn[standard]`：ASGI 服务进程，用来长期运行 `openclaw.api:app`。
- `httpx`：FastAPI `TestClient` 依赖，用于 API 单元测试。

## 4. `openclaw/api.py` 的定位

当前项目已有 CLI 入口：

```text
pyclaw -> openclaw.__main__:main -> openclaw.cli:main
```

本次新增 HTTP 入口：

```text
uvicorn openclaw.api:app -> openclaw/api.py 中的 app 对象
```

二者关系：

| 入口 | 使用场景 | 生命周期 |
| --- | --- | --- |
| `openclaw/cli.py` | 本地命令行、开发调试、一次性任务 | 执行一次后退出 |
| `openclaw/api.py` | Docker / K8s / HTTP 调用 | 长驻进程 |

`openclaw/api.py` 不替代 CLI，而是在现有能力外面包了一层服务边界。

## 5. API 模型

### 5.1 HealthResponse

```python
class HealthResponse(BaseModel):
    status: str = "ok"
    service: str = "pyclaw-api"
```

返回示例：

```json
{
  "status": "ok",
  "service": "pyclaw-api"
}
```

### 5.2 AgentRunRequest

核心字段：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `prompt` | 必填 | 用户输入。 |
| `session_id` | 自动生成 | transcript session id。 |
| `provider` | `openai` | 当前支持 `openai` / `mock`。 |
| `model` | `OPENAI_MODEL` 或 `gpt-4.1-mini` | 模型名称。 |
| `system` | `You are a helpful assistant.` | system prompt。 |
| `api_mode` | `auto` | OpenAI SDK API 模式。 |
| `reasoning_effort` | null | 推理模型 effort 参数。 |
| `max_output_tokens` | null | 最大输出 token。 |
| `chatdata_dir` | 默认 `./chatdata` 或 `OPENCLAW_CHATDATA_DIR` | transcript 与 sessions.json 存储目录。 |
| `tool_profile` | `coding` | 工具 profile。 |
| `tools_allow` | null | 显式工具 allowlist。 |
| `tools_deny` | null | 显式工具 denylist。 |
| `tools_also_allow` | null | 在 profile 之上额外允许的工具。 |
| `shell_approval` | `deny` | API 服务默认拒绝非只读 shell 命令。 |
| `context_window_tokens` | `120000` | 上下文窗口估算。 |
| `reserve_tokens` | `16384` | 输出与安全预留。 |
| `keep_recent_tokens` | `20000` | compaction 后保留近期上下文。 |
| `tool_result_max_chars` | `20000` | provider 调用前单个 tool result 最大字符数。 |
| `disable_compaction` | false | 是否关闭自动压缩。 |
| `mock_response` | null | mock provider 测试返回文本。 |

### 5.3 AgentRunResponse

```python
class AgentRunResponse(BaseModel):
    session_id: str
    message: dict[str, Any]
    text: str
```

返回示例：

```json
{
  "session_id": "demo",
  "message": {
    "role": "assistant",
    "content": [
      {"type": "text", "text": "你好！"}
    ],
    "stopReason": "stop"
  },
  "text": "你好！"
}
```

## 6. 请求示例

### 6.1 健康检查

```bash
curl http://localhost:8000/healthz
```

返回：

```json
{"status":"ok","service":"pyclaw-api"}
```

### 6.2 Mock provider 调用

```bash
curl -X POST http://localhost:8000/v1/agent/run ^
  -H "Content-Type: application/json" ^
  -d "{\"prompt\":\"你好\",\"provider\":\"mock\",\"session_id\":\"demo\"}"
```

返回类似：

```json
{
  "session_id": "demo",
  "message": {
    "role": "assistant",
    "content": [{"type":"text","text":"mock response: 你好"}],
    "provider": "mock",
    "model": "gpt-4.1-mini",
    "stopReason": "stop"
  },
  "text": "mock response: 你好"
}
```

### 6.3 OpenAI provider 调用

先设置环境变量，生产环境应通过 K8s Secret 注入：

```cmd
set OPENAI_API_KEY=你的 key
```

再调用：

```bash
curl -X POST http://localhost:8000/v1/agent/run ^
  -H "Content-Type: application/json" ^
  -d "{\"prompt\":\"你好\",\"session_id\":\"demo\",\"provider\":\"openai\",\"model\":\"gpt-4.1-mini\"}"
```

## 7. 内部调用流程

`POST /v1/agent/run` 的内部流程：

```text
HTTP request
  -> AgentRunRequest
  -> run_agent_request()
  -> load_env_file_if_configured()
  -> build_provider()
  -> build_policy()
  -> build_tool_registry()
  -> Agent(...)
  -> build_session()
  -> AgentSession.run_prompt(prompt)
  -> Agent / provider / tools / transcript
  -> AgentRunResponse
```

关键点：

1. 仍然复用已有 `Agent`，不复制 agent loop。
2. 仍然复用 `AgentSession`，所以 session transcript、compaction、retry、tool result guard 都继续生效。
3. 仍然复用 `ToolPolicy` 和 `build_tool_registry()`，所以 API 和 CLI 共享工具体系。
4. `chatdata_dir` 可以由请求传入，也可以使用 `OPENCLAW_CHATDATA_DIR` 或默认 `./chatdata`。

## 8. 安全默认值

API 服务入口和本地 CLI 的一个重要差异是：

- CLI 面向本地交互用户。
- API 面向远程 HTTP 调用，天然风险更高。

因此本次 API 默认：

```python
shell_approval = "deny"
```

这意味着：

- readonly shell 命令仍可按 shell guard 结果处理。
- mutation / unknown / dangerous 命令不会因为无人交互而被自动批准。
- 后续如果要开放远程工具执行，必须先补鉴权、授权、审批和审计。

当前 API 没有实现用户鉴权，部署到公网前必须加上至少一种保护：

1. Ingress 层鉴权。
2. API token。
3. 内网访问限制。
4. OAuth / OIDC。
5. 操作审批流。

## 9. 与 K8s 的关系

有了 `openclaw/api.py` 后，容器启动命令可以变成：

```bash
uvicorn openclaw.api:app --host 0.0.0.0 --port 8000
```

这满足 K8s Deployment 的基本要求：

- 进程长期运行。
- 有固定端口。
- 有 HTTP health check。
- 可通过 Service 暴露。
- 可通过 Ingress 对外访问。

后续 Deployment 可以基于：

```yaml
readinessProbe:
  httpGet:
    path: /healthz
    port: 8000
livenessProbe:
  httpGet:
    path: /healthz
    port: 8000
```

## 10. 当前尚未完成的内容

本阶段只完成第一步 API 服务入口，以下内容还没有实现：

1. Dockerfile。
2. Helm Chart。
3. K8s Deployment / Service / Ingress / PVC / Secret 模板。
4. API 鉴权。
5. 异步任务队列。
6. 多副本状态外置。
7. 任务状态查询接口。

这些会在下一阶段继续推进。

## 11. 验证方式

### 11.1 编译与单元测试

```powershell
py -m compileall openclaw tests
py -m unittest discover -s tests
```

如果未安装 API 可选依赖，`tests/test_api.py` 会跳过 FastAPI 客户端测试。安装方式：

```cmd
python -m pip install -e ".[api]"
```

### 11.2 本地启动

安装依赖后：

```cmd
uvicorn openclaw.api:app --host 0.0.0.0 --port 8000
```

访问：

```cmd
curl http://localhost:8000/healthz
```

## 12. 下一步建议

下一阶段建议实现：

1. `Dockerfile`
2. `.dockerignore`
3. 本地 `docker build` / `docker run` 验证
4. 最小 Helm Chart：Deployment、Service、ConfigMap、Secret、PVC

完成后，pyclaw 就具备从本地 HTTP 服务走向 K8s 测试集群部署的基础形态。