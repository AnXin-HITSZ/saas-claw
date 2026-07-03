# pyclaw 工具体系升级操作记录

日期：2026-07-02
目录：`D:\project\pyclaw`
参考目标：将原生 OpenClaw 的工具体系逐步迁移到 pyclaw，优先补强工具底座和基础工具面。

## 1. 本次升级范围

本次没有一次性迁移 `cron`、`message`、`gateway`、`browser`、`MCP` 等重型平台工具，而是先升级工具体系底座，原因是这些高级工具都依赖统一的：

- 工具定义模型
- 工具执行上下文
- 工具结果模型
- 工具策略过滤
- 工具 catalog
- CLI 调试入口

当前优先完成的是：

1. 补充 OpenClaw 风格的只读发现工具：`grep`、`find`。
2. 给 `list_dir` 增加 OpenClaw 兼容别名：`ls`。
3. 给现有 `shell` 增加 OpenClaw 兼容入口：`exec`。
4. 将工具策略从单工具名过滤升级为支持工具组过滤。
5. 扩展工具元数据，为未来 plugin/MCP/client/runtime 工具预留来源字段。
6. 将 CLI 增加 `--tools-allow`、`--tools-deny`、`--tools-also-allow` 参数，便于对照执行。
7. 补充测试并完成编译、单元测试验证。

## 2. 操作过程记录

### 2.1 读取当前结构

先查看了当前项目的工具结构：

- `openclaw/tools/types.py`
- `openclaw/tools/results.py`
- `openclaw/tools/registry.py`
- `openclaw/tools/policy.py`
- `openclaw/tools/builder.py`
- `openclaw/tools/executor.py`
- `openclaw/tools/catalog.py`
- `openclaw/cli.py`

确认当前项目已经具备 `ToolExecutionContext`、`ToolResult`、`ToolDefinition`、`ToolRegistry`、`execute_tool_call_batch` 等基础组件，因此本次采用“补强现有结构”的方式，而不是重写工具系统。

### 2.2 apply_patch 失败说明

尝试使用 `apply_patch` 修改文件时，Windows 沙箱报错：

```text
helper_sid_resolve_failed: resolve SID for offline user CodexSandboxOffline failed
```

因此后续改用 PowerShell 写入项目内文件。所有写入都限制在：

```text
D:\project\pyclaw
```

没有修改项目外路径。

### 2.3 扩展工具元数据

修改：`openclaw/tools/types.py`

新增：

```python
ToolSource = Literal["core", "plugin", "mcp", "client", "runtime"]
```

并给 `ToolMetadata` 增加：

```python
source: ToolSource = "core"
plugin_id: str | None = None
```

用途：

- 当前核心工具默认 `source="core"`。
- 未来插件工具可以标记 `source="plugin"` 和 `plugin_id="browser"`。
- MCP 工具可以标记 `source="mcp"`。
- Tool Search / catalog 可以按来源筛选工具。

### 2.4 新增 grep 工具

新增：`openclaw/tools/fs/grep.py`

工具名：

```text
grep
```

参数：

```text
pattern          必填，正则或普通文本
path             可选，默认 .
glob             可选，例如 *.py
case_sensitive   可选，默认 true
regex            可选，默认 true
max_matches      可选，默认 100
max_file_bytes   可选，默认 1000000
```

行为：

- 在 workspace 内搜索 UTF-8 文本文件。
- 支持文件或目录输入。
- 支持 glob 限制文件名。
- 支持 regex/literal 两种匹配模式。
- 返回结构化 JSON，包括 `matches`、`matchCount`、`scannedFiles`、`skippedFiles`、`truncated`。

### 2.5 新增 find 工具

新增：`openclaw/tools/fs/find.py`

工具名：

```text
find
```

参数：

```text
path             可选，默认 .
name             可选，文件名 glob，默认 *
type             可选，file / directory / any
max_entries      可选，默认 200
include_hidden   可选，默认 false
```

行为：

- 在 workspace 内递归查找路径。
- 可按文件/目录类型过滤。
- 返回结构化 JSON，包括路径、相对路径、类型和大小。

### 2.6 增加 ls 兼容别名

修改：`openclaw/tools/fs/list_dir.py`

新增：

```python
def create_ls_tool() -> ToolDefinition:
    ...
```

`ls` 和 `list_dir` 使用同一个执行函数，区别只是工具名和说明不同。

目的：

- pyclaw 保留更语义化的 `list_dir`。
- 同时兼容原生 OpenClaw session 工具名 `ls`。

### 2.7 增加 exec 兼容入口

修改：`openclaw/tools/shell/exec.py`

保留旧工具：

```text
shell
```

新增 OpenClaw 兼容工具：

```text
exec
```

当前 `exec` 仍然复用同步 shell 执行逻辑，不是完整的 OpenClaw `exec/process` 后台进程体系。

已兼容的参数：

```text
command
workdir
env
timeout
max_chars
```

`shell` 仍兼容：

```text
command
cwd
timeout_seconds
max_chars
```

同时 `execute_shell` 现在支持：

- `cwd` / `workdir` 双参数名
- `timeout_seconds` / `timeout` 双参数名
- `env` 环境变量覆盖

后续完整迁移 OpenClaw shell 体系时，需要再实现：

```text
process
background
yieldMs
poll/log/write/send-keys/kill
PTY
approval
node/sandbox/gateway host routing
```

### 2.8 升级工具策略

修改：`openclaw/tools/policy.py`

新增工具 profile：

```text
minimal
readonly
coding
messaging
full
```

新增工具组：

```text
group:fs
group:filesystem
group:readonly
group:runtime
group:shell
group:web
group:openclaw
```

新增 `also_allow`：

```python
ToolPolicy(
    profile="coding",
    also_allow={"web_fetch"},
)
```

策略执行顺序：

1. 展开 allow / deny / also_allow 中的工具组。
2. 先应用 deny。
3. 如果存在 allow，则只保留 allow 或 also_allow 中的工具。
4. 如果不存在 allow，则按 profile 的 tags 过滤。
5. 如果 `readonly=True`，再过滤掉没有 `readonly` tag 的工具。

示例：

```python
ToolPolicy(allow={"group:readonly"})
```

会允许：

```text
read, list_dir, ls, grep, find
```

不会允许：

```text
write, edit, apply_patch, exec
```

### 2.9 更新工具构造入口

修改：`openclaw/tools/builder.py`

`core_tool_definitions()` 当前返回：

```text
read
list_dir
ls
grep
find
write
edit
apply_patch
shell
exec
web_fetch
web_search
```

然后通过：

```python
apply_tool_policy(...)
```

进入 registry。

### 2.10 更新 catalog

修改：`openclaw/tools/catalog.py`

新增 catalog 条目：

```text
ls
grep
find
exec
```

并将 shell 类工具 section 从 `shell` 调整为：

```text
runtime
```

这样更接近 OpenClaw 的工具分类方式：文件系统、运行时、Web、会话、平台能力等。

### 2.11 更新 CLI 参数

修改：`openclaw/cli.py`

`--tool-profile` 现在支持：

```text
minimal
readonly
coding
messaging
full
```

新增：

```text
--tools-allow
--tools-deny
--tools-also-allow
```

示例：

```cmd
pyclaw --tools-allow group:readonly tools run ls path=openclaw max_entries=3
```

```cmd
pyclaw --tool-profile coding --tools-also-allow web_fetch "读取 README 并抓取某个 URL"
```

```cmd
pyclaw --tool-profile full --tools-deny group:runtime "只允许文件和 web，不允许 shell"
```

## 3. 测试覆盖

新增/修改：

- `tests/test_fs_tools.py`
- `tests/test_shell_tool.py`
- `tests/test_tool_policy.py`

覆盖内容：

1. `grep` 可以按内容和 glob 查找文本。
2. `find` 可以按文件名 glob 查找文件。
3. `exec` 可以接受 OpenClaw 风格参数名。
4. `group:fs` 可以展开为具体工具名。
5. `group:readonly` 只允许只读工具。
6. `also_allow` 可以在 profile 基础上额外加工具。
7. `readonly=True` 会过滤 mutating/runtime 工具。

## 4. 验证结果

执行：

```cmd
py -m compileall openclaw tests
```

结果：通过。

执行：

```cmd
py -m unittest discover -s tests
```

结果：

```text
Ran 68 tests in 0.628s
OK
```

另外尝试执行：

```cmd
py -m openclaw --help
py -m openclaw tools list
```

在当前 Codex Windows 沙箱中触发同类 SID 解析错误，没有完成输出。这不是 pyclaw 代码编译或单测失败，单元测试已经覆盖 CLI 主流程。

## 5. 当前迁移状态

当前 pyclaw 已具备更接近 OpenClaw 的基础工具面：

```text
read
list_dir
ls
grep
find
write
edit
apply_patch
shell
exec
web_fetch
web_search
```

已经具备的底座能力：

- `ToolDefinition`
- `ToolExecutionContext`
- `ToolResult`
- `ToolMetadata`
- `ToolRegistry`
- `ToolPolicy`
- 工具组展开
- profile 过滤
- readonly 过滤
- CLI tools list/describe/run
- 批量工具执行 executor

尚未迁移的 OpenClaw 大能力：

```text
process
sessions_list / sessions_history / sessions_send / sessions_spawn / sessions_yield
subagents
session_status
get_goal / create_goal / update_goal
update_plan
transcripts tool
cron
message
gateway
nodes
browser
MCP
Tool Search
image / pdf / tts / generation tools
```

## 6. 后续建议

下一阶段建议优先实现：

1. `process`：让 `exec` 支持后台命令和日志轮询。
2. `session_status`：让 Agent 可以自查 cwd、workspace、session、tool profile。
3. `transcripts` 工具：把当前 CLI transcript 能力暴露给 Agent。
4. `goal` 和 `update_plan`：补齐任务管理能力。
5. `sessions_list/history`：为后续 subagents 打基础。

不建议立刻迁移 `browser`、`cron`、`gateway`，因为它们依赖更完整的平台运行时和权限模型。
