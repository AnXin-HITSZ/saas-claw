# OpenClaw Tool System Python Final Implementation Log 002

本文记录 `pyclaw` 工具系统第二阶段的最终实现状态。

## 1. 最终实现范围

本阶段已补齐工具系统运行时主链路，并新增目录读取修复：

- `read` / `list_dir` / `write` / `edit` / `apply_patch` 文件工具
- `shell` 工具
- `web_fetch` / `web_search` 工具
- workspace path guard
- readonly guard
- shell cwd guard
- web SSRF guard
- provider-specific schema normalization
- CLI `pyclaw tools list`
- CLI `pyclaw tools describe`
- CLI `pyclaw tools run`

当前工具系统主链路为：

```text
ToolCatalog
  -> ToolDefinition
  -> schema normalization
  -> ToolRegistry
  -> prepare arguments
  -> validate arguments
  -> before hook
  -> execute
  -> ToolResult
  -> after hook
  -> ToolResultMessage
  -> transcript / CLI detail output
```

## 2. 文件工具

### read

文件：`openclaw/tools/fs/read.py`

能力：读取 workspace 内 UTF-8 文本文件。

参数：

- `path`
- `offset`
- `limit`
- `max_chars`

补充行为：

- 如果 `path` 是目录，不再触发 Windows 的 `[Errno 13] Permission denied`。
- 现在会返回结构化错误，并提示使用 `list_dir`。

### list_dir

文件：`openclaw/tools/fs/list_dir.py`

能力：列出 workspace 内的目录内容。

参数：

- `path`：目录路径，默认 `.`
- `recursive`：是否递归
- `include_hidden`：是否包含点号开头的隐藏项
- `max_entries`：最多返回多少项，默认 200

典型用途：

```cmd
pyclaw --json tools run list_dir "{\"path\":\"chatdata\"}"
```

然后读取具体文件：

```cmd
pyclaw --json tools run read "{\"path\":\"chatdata/demo.jsonl\"}"
```

### write

文件：`openclaw/tools/fs/write.py`

能力：写入 UTF-8 文本文件。

参数：

- `path`
- `content`
- `overwrite`
- `create_dirs`

安全边界：

- `context.readonly=True` 时拒绝写入。
- 默认 `workspace_only=True`。
- 默认 sequential。

### edit

文件：`openclaw/tools/fs/edit.py`

能力：对文件做精确文本替换。

参数：

- `path`
- `old_text`
- `new_text`
- `replace_all`

行为：

- 默认要求 `old_text` 只匹配一次。
- 多次匹配时必须显式传 `replace_all=true`。
- 未匹配时返回 blocked tool result。

### apply_patch

文件：`openclaw/tools/fs/apply_patch.py`

能力：当前实现为保守的 exact-text patch。

说明：

- 不是完整 unified diff parser。
- 复用 `edit` 的执行逻辑。
- 默认 sequential。

## 3. Shell 与 Web 工具

### shell

文件：`openclaw/tools/shell/exec.py`

能力：在 workspace 内运行 shell 命令并捕获结果。

参数：

- `command`
- `cwd`
- `timeout_seconds`
- `max_chars`

安全边界：

- `cwd` 必须位于 workspace 内。
- 默认 sequential。
- 只有 `full` profile 暴露给 Agent。

### web_fetch

文件：`openclaw/tools/web/fetch.py`

能力：抓取公开 HTTP(S) URL。

安全边界：

- 请求前校验 URL。
- redirect 后再次校验 final URL。
- 禁止 localhost、private IP、loopback、link-local、multicast、reserved、unspecified。
- 默认不使用系统代理。

### web_search

文件：`openclaw/tools/web/search.py`

能力：使用 DuckDuckGo HTML 页面做简单搜索，返回标题和 URL。

说明：当前是 stdlib 轻量实现，未来可替换为正式搜索 provider。

## 4. Tool Profile

当前 core tools：

```text
read
list_dir
write
edit
apply_patch
shell
web_fetch
web_search
```

profile 暴露规则：

```text
readonly: read, list_dir
coding:   read, list_dir, write, edit, apply_patch
full:     read, list_dir, write, edit, apply_patch, shell, web_fetch, web_search
```

CLI prompt 默认使用 `coding` profile。

## 5. Provider Schema Normalization

文件：`openclaw/tools/schema.py`

新增：

- `normalize_tool_schema_for_provider()`
- `tool_to_responses_schema()`
- `tool_to_chat_completions_schema()`

`openclaw/llm/openai_provider.py` 已改为调用这些 helper。

## 6. CLI 工具命令

```cmd
pyclaw tools list
pyclaw --json tools list
pyclaw tools describe read
pyclaw --json tools describe read
pyclaw --json tools run list_dir "{\"path\":\"chatdata\"}"
pyclaw --json tools run read "{\"path\":\"chatdata/demo.jsonl\"}"
```

## 7. 测试覆盖

新增/扩展测试覆盖：

- 文件读取
- 目录读取提示
- `list_dir` 列目录
- 默认 registry 包含 `list_dir`
- 文件写入
- readonly 阻断
- exact edit
- apply_patch 工具定义
- shell 执行
- SSRF guard
- provider schema normalization
- CLI tools list / describe / run

## 8. 验证命令

```cmd
py -m compileall openclaw tests
py -m unittest discover -s tests
```

当前结果：

```text
61 tests OK
```

## 9. 保守限制

当前仍有几个有意保守的限制：

- `apply_patch` 是 exact-text patch，不是完整 unified diff。
- `web_search` 是 stdlib 轻量实现。
- shell 工具只在 `full` profile 暴露给 Agent。
- `pyclaw tools run` 是显式人工执行入口，因此使用 full registry。
