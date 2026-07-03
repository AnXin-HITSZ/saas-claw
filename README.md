# pyclaw

`pyclaw` 是一个 OpenClaw-inspired Python agent runtime。当前版本已经包含：

- OpenAI / OpenAI-compatible provider adapter
- mock provider
- session transcript JSONL 持久化
- CLI 入口：`pyclaw` / `python -m openclaw`
- 工具系统：`read`、`list_dir`、`write`、`edit`、`apply_patch`、`shell`、`web_fetch`、`web_search`
- 工具安全边界：workspace path guard、readonly guard、SSRF guard、shell cwd guard

## 1. 安装与虚拟环境

在 Windows cmd 中：

```cmd
cd /d D:\project\pyclaw
py -m venv .venv
.venv\Scripts\activate.bat
python -m pip install -e ".[openai]"
```

如果只运行 mock provider 或单元测试，不需要 OpenAI SDK，也可以：

```cmd
python -m pip install -e .
```

## 2. 环境变量

项目会默认读取当前目录下的 `.env`。

常用 `.env` 示例：

```env
OPENAI_API_KEY=your_api_key_here
OPENAI_MODEL=gpt-4.1-mini
OPENAI_API_MODE=auto
# OPENAI_BASE_URL=https://api.openai.com/v1
```

如果使用 OpenAI-compatible Chat Completions 服务，例如 DeepSeek 一类服务，通常需要：

```env
OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_MODEL=your-model-name
OPENAI_API_MODE=chat_completions
```

## 3. 基本运行

使用真实 provider：

```cmd
pyclaw "你好"
```

使用 mock provider：

```cmd
pyclaw --provider mock "你好"
```

等价 Python 模块入口：

```cmd
python -m openclaw "你好"
```

输出完整 assistant message JSON：

```cmd
pyclaw --json "你好"
```

## 4. 主命令参数

这些参数可以放在 prompt、`transcripts`、`tools` 子命令前面。

| 参数 | 可选值 / 默认值 | 说明 |
| --- | --- | --- |
| `--provider` | `openai` / `mock`，默认 `openai` | 选择 LLM provider。 |
| `--model` | 默认 `OPENAI_MODEL` 或 `gpt-4.1-mini` | 指定模型名。 |
| `--system` | 默认 `You are a helpful assistant.` | 指定 system prompt。 |
| `--env-file` | 默认 `.env` | 指定要加载的 env 文件。 |
| `--no-env-file` | 无 | 不加载 `.env`。 |
| `--chatdata-dir` | 默认 `./chatdata` | 指定 transcript 和 `sessions.json` 保存目录。 |
| `--session-id` | 默认自动生成 | 指定会话 ID，对应 transcript 文件名。 |
| `--format` | `text` / `detail` / `json`，默认 `text` | `transcripts show` 的输出格式。 |
| `--api-mode` | `auto` / `responses` / `chat_completions` / `chat-completions`，默认 `auto` | OpenAI SDK API 模式。 |
| `--reasoning-effort` | `low` / `medium` / `high` | 传递 reasoning effort。Chat Completions 模式会自动剔除不兼容字段。 |
| `--max-output-tokens` | 整数 | 限制最大输出 token。Chat Completions 会映射为 `max_tokens`。 |
| `--tool-profile` | `readonly` / `coding` / `full`，默认 `coding` | 控制 Agent 暴露给模型的工具集合。 |
| `--json` | 无 | 输出 JSON；在 `tools run` 中也表示以 JSON 格式输出工具结果。 |

## 5. Tool Profile

`--tool-profile` 控制模型能看到哪些工具。

| Profile | 暴露工具 | 适用场景 |
| --- | --- | --- |
| `readonly` | `read`、`list_dir` | 只允许读取 workspace 内文件。 |
| `coding` | `read`、`list_dir`、`write`、`edit`、`apply_patch` | 默认编码模式。 |
| `full` | `read`、`list_dir`、`write`、`edit`、`apply_patch`、`shell`、`web_fetch`、`web_search` | 显式启用 shell 和 web 工具。 |

示例：

```cmd
pyclaw --tool-profile readonly "请阅读 README 并总结"
pyclaw --tool-profile coding "请修改 README"
pyclaw --tool-profile full "请读取 README 并搜索相关资料"
```

## 6. Transcript 命令

默认 transcript 保存在：

```text
D:\project\pyclaw\chatdata
```

指定 session id 运行：

```cmd
pyclaw --provider mock --session-id demo "你好"
```

查看 transcript：

```cmd
pyclaw transcripts show demo --format text
pyclaw transcripts show demo --format detail
pyclaw transcripts show demo --format json
```

指定 transcript 目录：

```cmd
pyclaw --chatdata-dir D:\project\pyclaw\chatdata transcripts show demo --format detail
```

## 7. Tools 命令

### 7.1 查看工具列表

```cmd
pyclaw tools list
pyclaw --json tools list
```

### 7.2 查看工具详情

```cmd
pyclaw tools describe read
pyclaw --json tools describe read
```

### 7.3 手动执行工具

`tools run` 支持两种参数形式。

JSON 形式：

```cmd
pyclaw --json tools run read "{\"path\":\"README.md\"}"
```

`key=value` 形式：

```cmd
pyclaw tools run read path=README.md
```

注意：`tools run` 是人工显式执行入口，会使用 full registry；Agent prompt 默认仍使用 `--tool-profile coding`。

## 8. 当前工具参数

### read

读取 workspace 内文本文件。

```cmd
pyclaw --json tools run read "{\"path\":\"README.md\"}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | string | 是 | 要读取的文件路径。相对路径基于当前 cwd。 |
| `offset` | integer | 否 | 从第几行开始返回，0-based。 |
| `limit` | integer | 否 | 最多返回多少行。 |
| `max_chars` | integer | 否 | 最多返回多少字符，默认 20000。 |

### list_dir

列出 workspace 内目录。

```cmd
pyclaw --json tools run list_dir "{\"path\":\"chatdata\"}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | string | 否 | 要列出的目录路径，默认 `.`。 |
| `recursive` | boolean | 否 | 是否递归列出。 |
| `include_hidden` | boolean | 否 | 是否包含点号开头的隐藏项。 |
| `max_entries` | integer | 否 | 最多返回多少项，默认 200。 |

如果想查看 `chatdata` 下有哪些 transcript 文件，先执行：

```cmd
pyclaw --json tools run list_dir "{\"path\":\"chatdata\"}"
```

然后再读取具体文件，例如：

```cmd
pyclaw --json tools run read "{\"path\":\"chatdata/demo.jsonl\"}"
```

### write

写入 workspace 内文本文件。

```cmd
pyclaw --json tools run write "{\"path\":\"tmp.txt\",\"content\":\"hello\"}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | string | 是 | 要写入的文件路径。 |
| `content` | string | 是 | 写入内容。 |
| `overwrite` | boolean | 否 | 是否覆盖已有文件，默认 `true`。 |
| `create_dirs` | boolean | 否 | 是否创建父目录，默认 `true`。 |

### edit

对文件做精确文本替换。

```cmd
pyclaw --json tools run edit "{\"path\":\"tmp.txt\",\"old_text\":\"hello\",\"new_text\":\"hi\"}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | string | 是 | 要编辑的文件路径。 |
| `old_text` | string | 是 | 要匹配的旧文本。 |
| `new_text` | string | 是 | 替换后的新文本。 |
| `replace_all` | boolean | 否 | 是否替换所有匹配。默认要求只匹配一次。 |

### apply_patch

当前是保守版 exact-text patch，复用 `edit` 的执行逻辑。

```cmd
pyclaw --json tools run apply_patch "{\"path\":\"tmp.txt\",\"old_text\":\"hi\",\"new_text\":\"hello again\"}"
```

参数同 `edit`：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | string | 是 | 要修改的文件路径。 |
| `old_text` | string | 是 | 精确匹配文本。 |
| `new_text` | string | 是 | 替换文本。 |
| `replace_all` | boolean | 否 | 是否替换所有匹配。 |

### shell

在 workspace 内执行 shell 命令。

```cmd
pyclaw --json tools run shell "{\"command\":\"echo hello\"}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `command` | string | 是 | 要执行的 shell 命令。 |
| `cwd` | string | 否 | 命令执行目录，必须在 workspace 内，默认 `.`。 |
| `timeout_seconds` | integer | 否 | 超时时间，默认 30。 |
| `max_chars` | integer | 否 | stdout/stderr 最大保留字符数，默认 20000。 |

### web_fetch

抓取公开 HTTP(S) URL。

```cmd
pyclaw --json tools run web_fetch "{\"url\":\"https://example.com\"}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `url` | string | 是 | 公开 HTTP(S) URL。 |
| `timeout_seconds` | integer | 否 | 请求超时时间，默认 10。 |
| `max_bytes` | integer | 否 | 最大读取字节数，默认 200000。 |

安全限制：会阻止 localhost、private IP、loopback、link-local、reserved 等地址。

### web_search

简单 web 搜索。

```cmd
pyclaw --json tools run web_search "{\"query\":\"OpenClaw agent tools\",\"limit\":5}"
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | string | 是 | 搜索关键词。 |
| `limit` | integer | 否 | 最多返回多少条结果，默认 5。 |
| `timeout_seconds` | integer | 否 | 请求超时时间，默认 10。 |

## 9. Gateway 命令

当前 `gateway run` 只是保留入口，还没有实现：

```cmd
pyclaw gateway run
```

会返回：

```text
gateway run is registered but not implemented yet.
```

## 10. 验证命令

```cmd
py -m compileall openclaw tests
py -m unittest discover -s tests
```

当前版本测试结果：

```text
58 tests OK
```
