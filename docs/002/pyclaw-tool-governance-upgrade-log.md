# pyclaw 工具治理改造技术记录

## 背景

本次改造围绕四个方向推进：

1. `ToolPolicy` 从静态过滤升级为分层 pipeline。
2. `ToolCatalog` 成为工具元数据的单一来源。
3. 继续收紧 shell / file / network 安全边界。
4. 为后续正式 memory system 预留位置，但暂不继续扩 session 记忆工具。

这里的重点不是新增更多工具，而是让工具体系更可治理：工具从哪里来、如何分组、如何被策略过滤、如何执行、哪些边界必须拦截，都要有清晰路径。

## 1. ToolPolicy Pipeline

### 改造前

此前 `openclaw/tools/policy.py` 的核心是一个 `apply_tool_policy()` 函数，逻辑集中在一个循环中：

```text
for tool in tools:
  deny 过滤
  allow 过滤
  profile 过滤
  readonly 过滤
```

这种写法能工作，但问题是：

- 各层策略没有名字。
- 不方便记录每一层移除了哪些工具。
- 后续要接 provider policy、global policy、sandbox policy 时会把函数越堆越大。
- 不像原生 OpenClaw 的 `applyToolPolicyPipeline()` 那样具备分层策略模型。

### 改造后

新增类型：

```python
@dataclass(frozen=True)
class ToolPolicyStage:
    name: str
    reason: str
    filter_tool: Callable[[ToolDefinition], bool]

@dataclass(frozen=True)
class ToolPolicyAuditEntry:
    stage: str
    reason: str
    before: tuple[str, ...]
    after: tuple[str, ...]
    removed: tuple[str, ...]

@dataclass(frozen=True)
class ToolPolicyPipelineResult:
    tools: list[ToolDefinition]
    audit: list[ToolPolicyAuditEntry]
```

新增入口：

```python
def apply_tool_policy_pipeline(tools: list[ToolDefinition], policy: ToolPolicy) -> ToolPolicyPipelineResult:
    ...
```

原来的兼容入口仍保留：

```python
def apply_tool_policy(tools: list[ToolDefinition], policy: ToolPolicy) -> list[ToolDefinition]:
    return apply_tool_policy_pipeline(tools, policy).tools
```

这样外部调用不需要大改，但内部已经从“单次静态过滤”变成“有 stage 的 pipeline”。

### 当前 pipeline 顺序

当前实际顺序：

```text
profile 或 allow
  -> readonly
  -> deny
```

具体规则：

- 如果 `allow is None`，使用 `profile` stage。
- 如果 `allow` 显式存在，使用 `allow` stage，显式 allow 覆盖 profile。
- `also_allow` 可以把工具额外加入 profile 或 allow 结果。
- `readonly=True` 会只保留带 `readonly` tag 的工具。
- `deny` 最后执行，因此 deny 优先级最高。

`workspace_only` 当前不作为工具列表过滤 stage，因为它更像执行上下文约束。如果默认用它过滤工具，会把 `web_fetch` / `web_search` 从 `full` profile 中误删。当前仍由 `ToolExecutionContext.workspace_only` 控制文件路径解析。

### 后续可扩展方向

后续可以加入更多 stage：

```text
profile policy
provider policy
model policy
global config policy
agent config policy
sandbox policy
runtime policy
explicit CLI allow/deny
```

由于现在已经有 `ToolPolicyAuditEntry`，后续 CLI 可以增加：

```cmd
pyclaw tools list --policy-audit
```

用于显示每一层策略移除了哪些工具。

## 2. ToolCatalog 单一来源

### 改造前

此前工具元数据分散在多个地方：

```text
openclaw/tools/catalog.py
  -> 有工具名称、label、description、profiles、tags

openclaw/tools/builder.py
  -> 手写 create_read_tool(), create_write_tool(), ...

openclaw/tools/policy.py
  -> 手写 CORE_TOOL_GROUPS

各个工具 factory
  -> 也写一份 metadata
```

这会导致同一事实重复声明。例如新增一个只读工具时，既要加 catalog，又要改 builder，还要改 `group:readonly`，很容易漏。

### 改造后

`ToolCatalogEntry` 新增 factory 和完整元数据字段：

```python
@dataclass(frozen=True)
class ToolCatalogEntry:
    id: str
    name: str
    label: str
    description: str
    section_id: str
    factory: ToolFactory
    profiles: tuple[str, ...] = ()
    tags: tuple[str, ...] = ()
    risk: ToolRisk = "low"
    source: ToolSource = "core"
    plugin_id: str | None = None
    expose_to_llm: bool = True
    workspace_only: bool = True
    include_in_openclaw_group: bool = False
```

`catalog.py` 现在负责声明：

- 工具名称
- 展示名称
- 描述
- 所属 section
- profiles
- tags
- risk
- source
- workspace_only
- factory

并提供：

```python
def materialize_catalog_entry(entry: ToolCatalogEntry) -> ToolDefinition:
    ...

def materialize_core_tools() -> list[ToolDefinition]:
    ...

def build_tool_groups(entries: list[ToolCatalogEntry] | None = None) -> dict[str, set[str]]:
    ...
```

### builder.py 的变化

改造前：

```python
def core_tool_definitions() -> list[ToolDefinition]:
    return [
        create_read_tool(),
        create_list_dir_tool(),
        ...
    ]
```

改造后：

```python
def core_tool_definitions() -> list[ToolDefinition]:
    return materialize_core_tools()
```

也就是说，工具注册顺序和工具全集都从 `CORE_TOOL_CATALOG` 派生。

### policy.py 的变化

改造前 `CORE_TOOL_GROUPS` 是手写表：

```python
"group:readonly": {"read", "list_dir", "grep", ...}
```

改造后：

```python
CORE_TOOL_GROUPS: dict[str, set[str]] = build_tool_groups()
```

工具组根据 catalog 的 section/tag 自动派生：

```text
tag: readonly -> group:readonly
tag: fs       -> group:fs
tag: web      -> group:web
tag: network  -> group:network
tag: mutation -> group:mutation
section_id    -> group:<section_id>
```

同时保留兼容别名：

```text
group:filesystem -> group:fs
group:shell      -> group:runtime
```

### 当前收益

新增一个工具时，主要改：

```text
1. 新工具实现文件
2. CORE_TOOL_CATALOG 中新增一条 ToolCatalogEntry
```

之后这些能力自动获得：

```text
build_tool_registry
profile 过滤
group 过滤
tools list / describe
risk / tags / section 元数据
```

## 3. Shell / File / Network 安全边界增强

### 3.1 文件 mutation guard

文件：

```text
openclaw/tools/fs/path_guard.py
```

新增：

```python
class WorkspaceMutationError(PermissionError):
    ...

def ensure_workspace_mutation_allowed(path: Path, *, workspace_dir: Path) -> None:
    ...
```

当前阻止修改：

```text
.git
.venv
__pycache__
.mypy_cache
.pytest_cache
*.pyc
*.pyo
```

`write` / `edit` / `apply_patch` 会经过这个 guard。

这不是替代 workspace path guard，而是在其基础上增加“即使路径在 workspace 内，也不应被 Agent 修改”的保护层。

### 3.2 Shell guard

文件：

```text
openclaw/tools/shell/exec.py
```

新增：

```python
def validate_shell_command(command: str) -> str | None:
    ...

def normalize_timeout(value: Any) -> int:
    ...

def normalize_max_chars(value: Any) -> int:
    ...
```

当前行为：

- `context.readonly=True` 时直接拒绝 shell/exec。
- 空命令拒绝。
- NUL byte 拒绝。
- 过长命令拒绝。
- 明显破坏性命令拒绝。
- timeout 限制在 1 到 120 秒。
- 输出截断限制在 1000 到 100000 字符。

当前拦截模式包括：

```text
rm -rf ...
del /f /s /q ...
rmdir /s ...
Remove-Item -Recurse -Force ...
git reset --hard
git clean -f
format C:
shutdown
reg delete
```

这是保守版 shell guard，不等于完整命令安全分析。后续如果继续接近原生 OpenClaw，需要继续做：

- 命令 token 化。
- 区分只读命令和 mutation 命令。
- Windows / POSIX 分平台策略。
- approved command prefixes。
- 用户确认机制。

### 3.3 Network / SSRF guard

文件：

```text
openclaw/tools/web/ssrf_guard.py
```

增强：

- 只允许 `http` / `https`。
- 拒绝 URL embedded credentials，例如 `https://user:pass@example.com`。
- hostname 统一小写、去除 IPv6 方括号、去除末尾点。
- 拒绝 localhost。
- DNS 解析后拒绝非公网地址。

`web_fetch` / `web_search` 额外限制：

```text
web_fetch timeout: 1..60 秒
web_fetch max_bytes: 1024..1000000
web_search limit: 1..20
web_search timeout: 1..60 秒
```

## 4. Memory System 预留

本次没有继续补 session 记忆工具，例如：

```text
sessions_list
sessions_history
transcripts_show 给 Agent 用
```

原因是后续会接正式 memory system。当前 session transcript rehydration 只解决同一个 `--session-id` 的上下文连续性，不承担长期记忆职责。

后续正式 memory system 建议作为独立工具组加入：

```text
memory_search
memory_get
memory_put 或 memory_update
```

对应 catalog tag 可以是：

```text
memory
readonly      # 对 memory_search / memory_get
mutation      # 对 memory_put / memory_update
```

这样它可以自然进入现有机制：

```text
ToolCatalog
  -> group:memory 自动生成
  -> ToolPolicy pipeline 过滤
  -> ToolRegistry 注册
  -> Agent 调用
```

## 测试覆盖

新增 / 更新测试：

```text
tests/test_tool_policy.py
tests/test_tool_catalog.py
tests/test_shell_tool.py
tests/test_fs_mutation_tools.py
tests/test_web_guard.py
```

覆盖点：

- `group:fs` / `group:readonly` / `group:mutation` / `group:network` 自动派生。
- `apply_tool_policy_pipeline()` 生成 audit。
- catalog metadata 会覆盖 materialized tool metadata。
- shell readonly 上下文会被阻止。
- shell 危险命令会被阻止。
- 文件 mutation 会阻止 `.git/config`。
- SSRF guard 会阻止 embedded credentials。

验证命令：

```cmd
py -m compileall openclaw tests
py -m unittest discover -s tests
```

当前结果：

```text
Ran 76 tests
OK
```

## 当前实现边界

本次是 pyclaw 的工具治理升级，不是完整复制原生 OpenClaw。

已经迁移的核心思想：

```text
policy pipeline
catalog / group / profile 元数据
tool risk / source / tag
shell/file/network guard
```

尚未迁移的复杂能力：

```text
provider-specific policy
agent/global config policy
subagent inherited policy
MCP catalog/runtime
完整 shell mutation classifier
用户审批系统
正式 memory system
browser / cron / gateway 工具
```

后续建议优先级：

1. 给 pipeline 增加 provider/global/agent config layers。
2. 增加 `pyclaw tools list --json` 输出 catalog metadata。
3. 增加 shell command classifier，而不是仅用正则。
4. 接入正式 memory system，并通过 `group:memory` 管理。