# OpenClaw Tool System Python Technical Design

本文档基于 `openclaw-tool-system-implementation-notes.md`，给出 Python 版 `pyclaw` 下一阶段工具系统的详细落地设计。

目标不是逐行翻译 TypeScript 版 OpenClaw，而是把其核心设计抽象迁移到当前 Python 项目中：

```text
Tool contract
  -> schema validation
  -> tool registry
  -> prepare / before / execute / after pipeline
  -> sequential / parallel scheduling
  -> structured tool result
  -> path / sandbox / policy guard
  -> transcript persistence
```

当前 `pyclaw` 已有最小工具闭环：

```text
openclaw/tools/registry.py
  FunctionTool
  ToolRegistry

openclaw/agent/loop.py
  execute_tool_calls()
  _execute_one_tool_call()

openclaw/llm/types.py
  ToolCallBlock
  ToolResultBlock
  ToolResultMessage
```

但当前实现仍然偏最小化：

- 工具结果只是普通 `output`。
- schema 只检查 required 字段。
- 没有 prepare arguments。
- 没有 before / after hook。
- 没有 structured details / progress / terminate。
- 没有 policy pipeline。
- 没有 path guard / sandbox guard。
- 没有工具目录 catalog。
- 没有 provider-specific schema normalization。

下一阶段应从“函数注册表”升级为“工具运行时系统”。

## 1. 设计目标

Python 版工具系统第一阶段增强应实现：

1. 统一工具契约：`ToolDefinition`、`ToolCall`、`ToolResult`。
2. 结构化工具结果：给模型看的 `content` 与给系统看的 `details` 分离。
3. 参数预处理：`prepare_arguments()`。
4. 参数校验：JSON Schema 最小校验，后续可接 Pydantic / jsonschema。
5. before hook：执行前可阻断、审计、改写参数。
6. after hook：执行后可改写结果、标记错误、请求 terminate。
7. 顺序 / 并行调度：按工具声明决定是否允许并行。
8. 工具目录：工具元数据与工具实现解耦。
9. 安全边界：workspace path guard、write allowlist、未来 sandbox 扩展。
10. transcript 兼容：工具调用与工具结果仍可写入 JSONL。

暂不在本阶段完整实现：

- 完整 sandbox fs bridge。
- 完整 SSRF 网络防护。
- 完整 plugin runtime。
- browser / media / cron / subagent 等复杂工具。
- 完整 provider schema normalization 矩阵。

但接口要预留这些能力。

## 2. 当前实现与目标实现对比

当前 `FunctionTool`：

```python
@dataclass
class FunctionTool:
    name: str
    description: str
    func: Callable[..., Any | Awaitable[Any]]
    input_schema: dict[str, Any]
    parallel: bool = False

    async def __call__(self, **kwargs: Any) -> Any:
        ...
```

目标工具契约：

```python
@dataclass
class ToolDefinition:
    name: str
    label: str
    description: str
    input_schema: dict[str, Any]
    execute: ToolExecute
    prepare_arguments: ToolArgumentPreparer | None = None
    execution_mode: Literal["sequential", "parallel"] = "sequential"
    metadata: ToolMetadata = field(default_factory=ToolMetadata)
```

主要差异：

| 能力 | 当前 | 目标 |
| --- | --- | --- |
| 名称 | `name` | `name` |
| UI 标签 | 无 | `label` |
| 描述 | `description` | `description` |
| 参数 schema | `input_schema` | `input_schema` + normalization |
| 参数校验 | required 字段检查 | schema validator |
| 参数预处理 | 无 | `prepare_arguments` |
| 执行模式 | `parallel: bool` | `execution_mode` |
| 结果 | 任意对象 | `ToolResult` |
| 进度 | 无 | `ToolProgress` |
| 阻断 | exception -> error | `ToolResult.blocked()` |
| before hook | 无 | `before_tool_call` |
| after hook | 无 | `after_tool_call` |
| 工具目录 | 无 | `ToolCatalogEntry` |

## 3. 推荐目录结构

建议在现有 `openclaw/tools` 下扩展，而不是新建完全独立的 `core/tools`：

```text
openclaw/
  tools/
    __init__.py
    registry.py          # 兼容现有 ToolRegistry，并逐步升级
    types.py             # ToolDefinition, ToolResult, ToolContext
    schema.py            # schema validation / normalization
    executor.py          # prepare / before / execute / after
    catalog.py           # tool catalog metadata
    policy.py            # allow/deny/profile/workspace policy
    hooks.py             # before/after hook protocol
    results.py           # text_result/json_result/blocked_result
    fs/
      __init__.py
      path_guard.py      # workspace path guard
      read.py            # future read tool
      write.py           # future write tool
      edit.py            # future edit tool
    shell/
      __init__.py
      exec.py            # future exec tool
    web/
      __init__.py
      ssrf_guard.py      # future network guard
      fetch.py
```

迁移原则：

- `registry.py` 保持兼容，避免破坏已有测试。
- 新能力先通过新类型和 executor 引入。
- `agent/loop.py` 的 `execute_tool_calls()` 后续改为调用 `tools/executor.py`。
- 基础文件工具、shell 工具、web 工具分阶段补。

## 4. 核心类型设计

### 4.1 Content

当前 `llm/types.py` 已使用 `dict[str, Any]` 表示 content block。

工具系统内部建议先保持 dict 兼容，但提供 helper：

```python
ContentBlock = dict[str, Any]


def text_block(text: str) -> ContentBlock:
    return {"type": "text", "text": text}


def image_block(data: str, mime_type: str) -> ContentBlock:
    return {"type": "image", "data": data, "mimeType": mime_type}
```

原因：

- 现有 provider 和 transcript 已接受 dict content。
- 后续如果引入 Pydantic，也可以在边界处转换。

### 4.2 ToolResult

目标：

```python
@dataclass
class ToolResult:
    content: list[ContentBlock]
    details: dict[str, Any] = field(default_factory=dict)
    progress: ToolProgress | None = None
    terminate: bool = False
    is_error: bool = False
```

字段含义：

- `content`：给模型看的内容。
- `details`：给日志、UI、transcript、诊断看的结构化信息。
- `progress`：长任务进度。
- `terminate`：提示当前工具 batch 后停止继续执行。
- `is_error`：是否作为错误工具结果返回。

常用 helper：

```python
def text_result(text: str, *, details: dict[str, Any] | None = None) -> ToolResult:
    return ToolResult(content=[{"type": "text", "text": text}], details=details or {})


def json_result(payload: Any) -> ToolResult:
    return ToolResult(
        content=[{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}],
        details={"payload": payload},
    )


def blocked_result(reason: str, *, denied_reason: str) -> ToolResult:
    return ToolResult(
        content=[{"type": "text", "text": reason}],
        details={"status": "blocked", "deniedReason": denied_reason},
        is_error=True,
    )
```

### 4.3 ToolDefinition

```python
ToolExecute = Callable[["ToolExecutionContext", dict[str, Any]], Awaitable[ToolResult]]
ToolArgumentPreparer = Callable[[dict[str, Any]], dict[str, Any]]


@dataclass
class ToolDefinition:
    name: str
    label: str
    description: str
    input_schema: dict[str, Any]
    execute: ToolExecute
    prepare_arguments: ToolArgumentPreparer | None = None
    execution_mode: Literal["sequential", "parallel"] = "sequential"
    metadata: ToolMetadata = field(default_factory=ToolMetadata)
```

### 4.4 ToolExecutionContext

执行工具时不要只传 `**kwargs`，还应传上下文：

```python
@dataclass
class ToolExecutionContext:
    tool_call_id: str
    tool_name: str
    session_id: str | None
    cwd: Path
    workspace_dir: Path
    chatdata_dir: Path | None = None
    model: str | None = None
    provider: str | None = None
    emit: Callable[[AgentEvent], None] | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
```

用途：

- 文件工具需要 `cwd` / `workspace_dir`。
- transcript / session 工具需要 `session_id` / `chatdata_dir`。
- before / after hook 需要 agent metadata。
- 长任务进度需要 `emit`。

## 5. Schema 校验设计

当前 `ToolRegistry.validate_input()` 只检查 required：

```python
required = tool.input_schema.get("required", [])
for key in required:
    if key not in value:
        raise ValueError(...)
```

下一阶段建议实现一个轻量 schema validator：

```python
def validate_tool_arguments(schema: dict[str, Any], value: dict[str, Any]) -> dict[str, Any]:
    ...
```

第一阶段支持：

- `type: object`
- `properties`
- `required`
- `additionalProperties`
- primitive type：`string`、`number`、`integer`、`boolean`、`array`、`object`
- `enum`
- nullable：`type: ["string", "null"]`

不建议一开始手写完整 JSON Schema 实现。

可选路线：

1. 零依赖最小 validator。
2. 后续引入 `jsonschema`。
3. 对复杂工具使用 Pydantic model，再导出 JSON Schema。

设计建议：

```python
class ToolArgumentError(ValueError):
    pass


def validate_tool_arguments(schema: dict[str, Any], value: dict[str, Any]) -> dict[str, Any]:
    # Return normalized value or raise ToolArgumentError.
```

错误信息要适合模型读取：

```text
missing required argument: path
argument "limit" must be integer
argument "mode" must be one of: read, write
```

## 6. Provider Schema Normalization

不同 provider 对工具 schema 接受程度不同。

当前项目已经同时支持：

- OpenAI Responses API
- OpenAI-compatible Chat Completions

因此工具 schema 输出至少要适配两种格式：

Responses API：

```python
{
    "type": "function",
    "name": tool["name"],
    "description": tool["description"],
    "parameters": tool["input_schema"],
    "strict": False,
}
```

Chat Completions：

```python
{
    "type": "function",
    "function": {
        "name": tool["name"],
        "description": tool["description"],
        "parameters": tool["input_schema"],
    },
}
```

当前转换在 `openclaw/llm/openai_provider.py` 中完成。

下一阶段建议把 schema normalization 移到 `tools/schema.py`：

```python
def normalize_tool_schema(
    schema: dict[str, Any],
    *,
    provider: str | None,
    model: str | None,
    api_mode: str | None,
) -> dict[str, Any]:
    ...
```

第一阶段处理：

- 确保 root schema 是 object。
- 没有 `properties` 时补 `{}`。
- 没有 required 时补 `[]`。
- 去掉 provider 不支持的字段。
- 避免出现 Python 专用类型。

## 7. ToolRegistry 升级策略

为了兼容当前代码，`ToolRegistry` 不要一次性重写。

建议分两层：

```python
class ToolRegistry:
    def register(self, tool: ToolLike) -> None:
        ...

    def resolve(self, name: str) -> ToolLike | None:
        ...

    def to_llm_tools(self) -> list[dict[str, Any]]:
        ...
```

其中 `ToolLike` 同时支持：

- 旧的 `FunctionTool`
- 新的 `ToolDefinition`

适配函数：

```python
def normalize_tool(tool: ToolLike) -> ToolDefinition:
    if isinstance(tool, FunctionTool):
        return wrap_function_tool(tool)
    return tool
```

这样可以逐步迁移测试和调用方。

## 8. 工具执行流水线

目标是把当前 `_execute_one_tool_call()`：

```python
tool = config.tools.resolve(call.name)
config.tools.validate_input(tool, call.input)
output = await tool(**call.input)
```

升级为：

```text
resolve tool
  -> prepare arguments
  -> validate arguments
  -> before hook
  -> execute
  -> normalize result
  -> after hook
  -> create ToolResultMessage
```

推荐新增：

```text
openclaw/tools/executor.py
```

核心函数：

```python
async def execute_tool_call(
    call: ToolCallBlock,
    registry: ToolRegistry,
    context: ToolExecutionContext,
    hooks: ToolHooks | None = None,
) -> ToolExecutionOutcome:
    ...
```

`ToolExecutionOutcome`：

```python
@dataclass
class ToolExecutionOutcome:
    call: ToolCallBlock
    result: ToolResult
    message: ToolResultMessage
    tool: ToolDefinition | None = None
```

异常处理规则：

- 工具不存在：返回 `ToolResult.is_error=True`。
- 参数校验失败：返回 `is_error=True`。
- before hook 阻断：返回 `blocked_result()`。
- execute 抛异常：捕获并返回 `is_error=True`。
- after hook 抛异常：保留原结果，同时 details 写入 after hook 错误。

## 9. 顺序与并行调度

当前逻辑：

```python
if all(tool.parallel for call in calls):
    await asyncio.gather(...)
else:
    sequential
```

建议改成：

```python
def can_execute_parallel(calls: list[ToolCallBlock], registry: ToolRegistry) -> bool:
    for call in calls:
        tool = registry.resolve(call.name)
        if tool is None:
            return False
        if normalize_tool(tool).execution_mode != "parallel":
            return False
    return True
```

原则：

- 任何一个工具要求 sequential，整个 batch 顺序执行。
- 找不到工具时顺序执行，保证错误消息顺序稳定。
- 文件写入、shell 执行、apply_patch 默认 sequential。
- 纯查询、只读工具可 parallel。

推荐默认：

| 工具类型 | 默认模式 |
| --- | --- |
| read | parallel |
| write/edit/apply_patch | sequential |
| exec/process | sequential |
| web_search/web_fetch | parallel |
| memory_search | parallel |
| session mutation | sequential |

## 10. Hook 设计

新增：

```text
openclaw/tools/hooks.py
```

协议：

```python
class ToolHooks(Protocol):
    async def before_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        context: ToolExecutionContext,
    ) -> ToolHookDecision:
        ...

    async def after_tool_call(
        self,
        call: ToolCallBlock,
        tool: ToolDefinition,
        arguments: dict[str, Any],
        result: ToolResult,
        context: ToolExecutionContext,
    ) -> ToolResult:
        ...
```

before hook 返回：

```python
@dataclass
class ToolHookDecision:
    allowed: bool = True
    reason: str | None = None
    denied_reason: str | None = None
    arguments: dict[str, Any] | None = None
```

用途：

- 用户审批。
- 策略阻断。
- loop detection。
- 参数改写。
- 审计记录。
- 插件扩展。

## 11. Policy Pipeline 设计

新增：

```text
openclaw/tools/policy.py
```

第一阶段实现：

```python
@dataclass
class ToolPolicy:
    allow: set[str] | None = None
    deny: set[str] = field(default_factory=set)
    profile: Literal["minimal", "coding", "full"] = "coding"
    workspace_only: bool = True
```

函数：

```python
def apply_tool_policy(tools: list[ToolDefinition], policy: ToolPolicy) -> list[ToolDefinition]:
    ...
```

执行规则：

1. deny 优先。
2. allow 存在时只保留 allow。
3. profile 决定默认工具集合。
4. workspace_only 写入上下文，供文件工具检查。

第二阶段再扩展：

- provider policy。
- model policy。
- sender / group policy。
- inherited subagent policy。
- plugin policy。
- sandbox policy。

## 12. Tool Catalog 设计

新增：

```text
openclaw/tools/catalog.py
```

目的：

- 描述工具，不立即实例化工具。
- 供 CLI、文档、UI、profile 策略使用。
- 后续支持插件工具和 lazy loading。

数据结构：

```python
@dataclass(frozen=True)
class ToolCatalogEntry:
    id: str
    name: str
    label: str
    description: str
    section_id: str
    profiles: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    include_in_openclaw_group: bool = False
```

第一批目录：

```python
CORE_TOOL_DEFINITIONS = [
    ToolCatalogEntry(
        id="read",
        name="read",
        label="Read",
        description="Read a file from the workspace.",
        section_id="filesystem",
        profiles=("coding", "full"),
        tags=("fs", "read"),
    ),
    ...
]
```

## 13. 文件工具安全设计

第一阶段建议优先实现只读安全边界：

```text
openclaw/tools/fs/path_guard.py
```

核心函数：

```python
def resolve_workspace_path(path: str, *, cwd: Path, workspace_dir: Path) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = cwd / candidate
    resolved = candidate.resolve()
    workspace = workspace_dir.resolve()
    try:
        resolved.relative_to(workspace)
    except ValueError as exc:
        raise PermissionError(f"path escapes workspace: {path}") from exc
    return resolved
```

Windows 注意点：

- 需要处理 drive letter。
- `Path.resolve()` 会解析 symlink。
- 不要只用字符串 startswith。
- 最终应使用 `relative_to()`。

第一阶段文件工具：

```text
read
  workspaceOnly
  path guard
  line offset / limit
  max chars

write
  workspaceOnly
  path guard
  parent mkdir optional
  overwrite policy

edit
  workspaceOnly
  path guard
  exact match replacement
```

写入工具默认 sequential。

## 14. Shell 工具设计

Shell 工具风险高，建议第二阶段实现。

设计要求：

- 默认禁用。
- 需要显式 policy 开启。
- sequential。
- 支持 timeout。
- 捕获 stdout/stderr/exit_code。
- 限制 cwd 到 workspace。
- 不直接暴露任意 PowerShell 复杂组合能力。

结果结构：

```python
json_result({
    "command": command,
    "cwd": str(cwd),
    "exit_code": process.returncode,
    "stdout": stdout[-max_chars:],
    "stderr": stderr[-max_chars:],
    "timed_out": timed_out,
})
```

## 15. Web 工具设计

Web 工具建议第三阶段实现。

必须有 SSRF 防护，不要只做简单 URL 判断。

最低要求：

- 只允许 `http` / `https`。
- 禁止 localhost / loopback。
- 禁止 private IP。
- 禁止 link-local。
- 限制 redirects。
- 限制 response bytes。
- 限制 timeout。
- 限制 content type。
- 默认不使用系统代理，除非显式 trust。

推荐使用 `httpx.AsyncClient`，并在请求前后都检查最终地址。

## 16. ToolResultMessage 兼容

当前 `ToolResultBlock`：

```python
@dataclass
class ToolResultBlock:
    tool_call_id: str
    name: str
    output: Any
    is_error: bool = False
    type: Literal["toolResult"] = field(default="toolResult", init=False)
```

为了兼容结构化结果，可以扩展为：

```python
@dataclass
class ToolResultBlock:
    tool_call_id: str
    name: str
    output: Any
    is_error: bool = False
    details: dict[str, Any] = field(default_factory=dict)
    progress: dict[str, Any] | None = None
    terminate: bool = False
    type: Literal["toolResult"] = field(default="toolResult", init=False)
```

兼容原则：

- `output` 保留，给模型和旧代码使用。
- `details` 新增，给 transcript / debug / CLI 使用。
- 旧 transcript 仍能读取。

## 17. Agent Loop 改造点

当前：

```python
async def execute_tool_calls(config, assistant):
    ...
```

建议改造：

```python
async def execute_tool_calls(config: LoopConfig, assistant: AssistantMessage) -> list[ToolResultMessage]:
    calls = extract_tool_calls(assistant.content)
    context = ToolExecutionContext(
        session_id=config.session_id,
        cwd=config.cwd,
        workspace_dir=config.workspace_dir,
        model=config.model,
        provider=getattr(config.provider, "provider_name", None),
        emit=config.emit,
    )
    return await execute_tool_call_batch(calls, config.tools, context, config.tool_hooks)
```

因此 `LoopConfig` 后续要扩展：

```python
@dataclass
class LoopConfig:
    ...
    session_id: str | None = None
    cwd: Path | None = None
    workspace_dir: Path | None = None
    tool_hooks: ToolHooks | None = None
```

`AgentSession` 创建 loop config 时注入 session/cwd/workspace。

## 18. Transcript 与工具结果

工具结果写入 transcript 时应保留：

- `tool_call_id`
- `name`
- `output`
- `is_error`
- `details`
- `progress`
- `terminate`

CLI `transcripts show --format detail` 后续可展示：

```text
tool read status=ok path=README.md chars=1024
tool exec exit_code=1 timed_out=false
tool web_fetch status=blocked deniedReason=ssrf-private-ip
```

`--format json` 可完整输出。

## 19. 最小实现计划

建议按以下顺序实现代码：

### Step 1：结构化结果

新增：

```text
openclaw/tools/types.py
openclaw/tools/results.py
```

并让旧 `FunctionTool` 返回值自动包装成 `ToolResult`。

### Step 2：executor

新增：

```text
openclaw/tools/executor.py
```

把 `_execute_one_tool_call()` 的逻辑迁移进去。

### Step 3：schema validator

新增：

```text
openclaw/tools/schema.py
```

先实现 required/type/enum。

### Step 4：hooks

新增：

```text
openclaw/tools/hooks.py
```

支持 before 阻断和 after 改写结果。

### Step 5：path guard + read tool

新增：

```text
openclaw/tools/fs/path_guard.py
openclaw/tools/fs/read.py
```

实现第一个真实文件工具。

### Step 6：write/edit/apply_patch

实现 workspaceOnly 写入工具。

### Step 7：catalog + builder

新增：

```text
openclaw/tools/catalog.py
openclaw/tools/builder.py
```

用于根据 profile 生成工具集。

## 20. 测试计划

新增测试：

```text
tests/test_tool_results.py
tests/test_tool_schema.py
tests/test_tool_executor.py
tests/test_tool_policy.py
tests/test_fs_path_guard.py
tests/test_fs_tools.py
```

覆盖：

- 普通函数结果自动包装为 `ToolResult`。
- `ToolResult.details` 写入 `ToolResultMessage`。
- 缺少 required 参数。
- 参数类型错误。
- before hook 阻断。
- after hook 改写结果。
- sequential 工具按顺序执行。
- parallel 工具并发执行。
- path guard 阻止 `..` 越界。
- path guard 阻止绝对路径逃逸。
- transcript detail/json 能展示工具 details。

## 21. 与当前 CLI 的关系

当前 CLI 已有：

```cmd
pyclaw "你好"
pyclaw transcripts show demo --format text
pyclaw transcripts show demo --format detail
pyclaw transcripts show demo --format json
```

工具系统增强后，CLI 可继续扩展：

```cmd
pyclaw tools list
pyclaw tools describe read
pyclaw tools run read --json '{"path":"README.md"}'
```

但工具调用的主要入口仍然是模型返回 `toolCall`，不是用户手动执行工具。

## 22. 风险与约束

### 安全风险

文件、shell、web 工具都可能越权。

必须默认保守：

- 写入工具默认 workspaceOnly。
- shell 工具默认不开启。
- web 工具默认 strict network guard。
- 所有阻断都以结构化 `ToolResult` 返回。

### 兼容风险

当前已有 `FunctionTool` 测试和 agent loop。

升级时不要破坏：

- `FunctionTool(func=lambda...)`
- `ToolRegistry.register()`
- `ToolRegistry.to_llm_tools()`
- `ToolResultMessage.content[0]["output"]`

### Provider 风险

不同 provider 对 schema 支持不同。

工具 schema normalization 必须独立出来，否则 `OpenAIProvider` 会越来越臃肿。

## 23. 验收标准

第一阶段完成后，应满足：

1. 旧测试全部通过。
2. 工具结果支持 `details`。
3. 工具参数校验能报清晰错误。
4. before hook 能阻断工具。
5. after hook 能改写工具结果。
6. sequential / parallel 调度可测试。
7. transcript 能保存结构化工具结果。
8. 至少一个文件 read 工具具备 workspace path guard。
9. 文档说明如何添加一个新工具。

## 24. 添加新工具的推荐流程

以 `read` 工具为例：

1. 在 `tools/catalog.py` 增加目录项。
2. 在 `tools/fs/read.py` 定义 schema。
3. 实现 execute 函数，返回 `ToolResult`。
4. 在 builder 中按 profile 注册。
5. 写 schema / executor / path guard 测试。
6. 用 mock provider 触发 toolCall，验证 agent loop。
7. 用 `pyclaw transcripts show --format detail` 检查结果。

示例：

```python
READ_SCHEMA = {
    "type": "object",
    "required": ["path"],
    "properties": {
        "path": {"type": "string", "description": "Path to read"},
        "offset": {"type": "integer"},
        "limit": {"type": "integer"},
    },
    "additionalProperties": False,
}


async def execute_read(ctx: ToolExecutionContext, args: dict[str, Any]) -> ToolResult:
    path = resolve_workspace_path(args["path"], cwd=ctx.cwd, workspace_dir=ctx.workspace_dir)
    text = path.read_text(encoding="utf-8")
    return text_result(
        text,
        details={
            "path": str(path),
            "chars": len(text),
        },
    )
```

## 25. 总结

下一阶段 Python 工具系统的核心不是“多写几个 callable”，而是建立一条稳定、安全、可扩展的工具执行链：

```text
ToolCatalog
  -> ToolDefinition
  -> schema normalization
  -> ToolRegistry
  -> prepare arguments
  -> validate
  -> before hook / policy
  -> execute
  -> ToolResult
  -> after hook
  -> ToolResultMessage
  -> transcript
```

这样后续迁移 read/write/edit/exec/web_fetch/web_search 时，代码不会散落在 agent loop 中，也能逐步接近原生 OpenClaw 的工具系统边界。
