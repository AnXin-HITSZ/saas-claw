# pyclaw 会话上下文功能实现记录

本文记录根据 `docs/003/openclaw-session-context-implementation-notes.md` 在 Python 版 pyclaw 中落地的实现。目标不是简单把历史消息数组读回来，而是补齐 OpenClaw 风格的 JSONL 会话 header、entry 链、branch replay、compaction entry、token 估算、模型调用前预检查和工具结果截断。

## 1. 本次新增与修改文件

| 文件 | 作用 |
| --- | --- |
| `openclaw/session/context.py` | 新增会话上下文核心模块，集中实现 session header、message/compaction/leaf entry、branch replay、compaction preparation、token 估算、precheck route 和工具结果截断。 |
| `openclaw/session/transcript.py` | 从纯 message JSONL 升级为 session JSONL 管理器；新增 header 初始化、parentId 串联、compaction 追加、leaf 操作、context replay。 |
| `openclaw/session/agent_session.py` | 接入 transcript replay、pre-prompt compaction、provider overflow 后 compaction retry、tool result truncate 和上下文 transform 组合。 |
| `openclaw/session/__init__.py` | 导出 session context 相关类型和 helper，方便测试、CLI 和后续模块复用。 |
| `openclaw/cli.py` | transcript 展示兼容 session/compaction entry；新增 context/compaction 相关运行参数。 |
| `tests/test_session.py` | 更新 JSONL header 断言；新增 compaction replay、usage token 估算、pre-prompt 自动压缩测试。 |
| `tests/test_cli.py` | 更新 transcript JSON 输出断言，兼容第一行 session header。 |

## 2. JSONL 存储格式升级

### 2.1 session header

新建 `Transcript(path, session_id=..., cwd=...)` 时，如果文件不存在或为空，会先写入第一行 header：

```json
{"type":"session","version":3,"id":"demo","timestamp":"...","cwd":"D:\\project\\pyclaw"}
```

对应代码：

- `openclaw/session/context.py`：`SessionHeader`、`build_session_header()`
- `openclaw/session/transcript.py`：`Transcript.ensure_header()`

兼容策略：如果老 transcript 已经存在且第一行不是 header，不会强行重写文件；读取时仍按 legacy linear entries 处理。

### 2.2 message entry parentId 串联

每条 message entry 现在会带：

```json
{
  "type": "message",
  "id": "...",
  "parentId": "上一条 entry 的 id 或 null",
  "timestamp": "...",
  "message": {"role":"user","content":[...]}
}
```

`Transcript.append_message()` 会读取当前 `leaf_id()`，并把它作为新 message 的 `parentId`。这使 transcript 不再只是线性日志，而是可以按 parent 链回放。

### 2.3 leaf entry

新增 `Transcript.append_leaf(leaf_id)`，可显式写入：

```json
{"type":"leaf","id":"...","parentId":"...","timestamp":"...","leafId":"目标 entry id"}
```

当前 CLI 主流程仍按“最后一条 data entry 是 leaf”运行；显式 leaf entry 是为后续分支切换、回滚、选择历史节点继续对话预留的能力。

## 3. Branch Replay 与 Context 构建

### 3.1 get_branch

`get_branch(entries, leaf_id=None)` 的逻辑：

1. 去掉 `session` 和 `leaf` 这类控制 entry。
2. 如果没有显式传入 `leaf_id`，使用 `resolve_leaf_id(entries)` 得到当前 leaf。
3. 通过 `by_id` 字典从 leaf 沿 `parentId` 回溯到 root。
4. 使用 `seen` 集合防止 parent 链循环。
5. 如果发现是旧版所有 message 都没有 parentId 的 transcript，则退回线性 replay。

### 3.2 build_session_context

`build_session_context(entries, leaf_id=None)` 会先取当前 branch，再寻找最新 `compaction` entry：

- 没有 compaction：把 branch 中所有 `message` entry 转成 `AgentMessage`。
- 有 compaction：先生成一条 synthetic user message，内容是 compaction summary；再追加 `firstKeptEntryId` 开始的 retained tail。

这样 JSONL 文件中旧历史不会被删除，但模型看到的是“摘要 + 近期尾部”。

## 4. Compaction 实现

### 4.1 compaction entry

压缩不是删除旧消息，而是追加：

```json
{
  "type": "compaction",
  "id": "...",
  "parentId": "当前 leaf id",
  "timestamp": "...",
  "summary": "...",
  "firstKeptEntryId": "...",
  "tokensBefore": 1234,
  "details": {
    "reason": "pre_prompt:compact_only",
    "summaryMessageCount": 8,
    "retainedMessageCount": 3
  }
}
```

对应代码：

- `build_compaction_entry()`
- `Transcript.append_compaction()`
- `Transcript.compact()`

### 4.2 cut point 选择

`prepare_compaction(entries, settings=...)` 会：

1. 构建当前 branch。
2. 收集 branch 中的 message entry 与 message 对。
3. 使用 `estimate_context_tokens()` 记录 `tokensBefore`。
4. 从尾部向前累计 token，尽量保留最近 `keep_recent_tokens`。
5. 如果 cut point 落在 tool result 上，会向后移动，避免 retained tail 从孤立 tool result 开始。
6. 生成 `summary_messages`、`retained_messages` 和 `first_kept_entry_id`。

### 4.3 摘要生成策略

本阶段实现的是确定性 fallback summary：

- 不额外调用 LLM，避免 compaction 本身引入 provider 依赖和测试不稳定。
- 保留最近若干条被压缩消息的 role 与文本摘要。
- 单条摘要文本最多保留 240 字符，避免 fallback summary 自己撑爆上下文。
- 如果存在上一轮 compaction，会把 previous summary 放入新的 summary 开头。

后续如果要升级成真实 LLM summary，可以把 `summarize_messages()` 替换为异步 provider summarizer；JSONL entry 结构无需改变。

## 5. Token 估算

`openclaw/session/context.py` 中实现：

- `normalize_usage()`：兼容 `prompt_tokens`、`completion_tokens`、`input_tokens`、`output_tokens`、`inputTokens`、`outputTokens`、`total_tokens`、`reasoning_tokens` 等字段。
- `estimate_context_tokens()`：优先使用最近 assistant message 的 provider usage；对该 assistant 之后的 tail message 用启发式补估。
- `estimate_message_tokens()`：按 message role 和 content blocks 估算。
- `estimate_block_chars()`：分别处理 `text`、`toolCall`、`toolResult`、`image` 和未知 block。

启发式仍是 `ceil(chars / 4)`，但不是直接对整段 JSON 字符串取长度，而是按 block 类型估算。image block 固定折算为 `IMAGE_BLOCK_CHARS = 4800`。

## 6. 溢出保护与 AgentSession 接入

### 6.1 历史加载

原逻辑：

```python
self.agent.state.messages.extend(self.transcript.read_messages())
```

新逻辑：

```python
self.agent.state.messages.extend(self.transcript.read_context_messages())
```

因此同一个 `--session-id` 重新进入时，会按 branch + compaction 构建真正发送给模型的上下文，而不是盲目塞入所有历史 message。

### 6.2 pre-prompt precheck

`AgentSession.__init__()` 会保存用户原本传入的 `agent.transform_context`，然后把 agent 的 transform 替换为 `_transform_context_before_prompt()`。

模型调用前执行顺序：

1. 先执行用户原本的 transform_context。
2. 用 `should_preemptively_compact_before_prompt()` 判断 route。
3. route 为 `compact_only` 或 `compact_then_truncate` 时，调用 `run_auto_compaction()`。
4. route 为 `truncate_tool_results_only` 或 `compact_then_truncate` 时，对 provider-bound messages 做 tool result 截断。
5. 再次估算 token；如果仍超过预算，抛出 `ContextOverflowError`。

### 6.3 provider overflow 后恢复

如果 provider 返回的 assistant error 看起来是 context overflow，`AgentSession.handle_post_agent_run()` 会：

1. 从内存 state 中移除最后一个 assistant error。
2. 调用 `run_auto_compaction(reason="provider_overflow")`。
3. 调用 `agent.continue_()` 重试。
4. 每轮 run 只尝试一次 overflow recovery，避免死循环。

### 6.4 tool result truncation

`truncate_oversized_tool_results()` 会 deep copy messages，只截断发送给 provider 的 tool result output，不修改 transcript 原始记录。

这点很重要：transcript 仍保留完整审计信息；模型上下文只拿截断版。

## 7. CLI 新增参数

本次新增以下参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--context-window-tokens` | `120000` | 用于预检查的模型上下文窗口估算值。 |
| `--reserve-tokens` | `16384` | 为模型输出和安全边界预留的 token。 |
| `--keep-recent-tokens` | `20000` | 自动压缩后尽量保留的近期尾部 token。 |
| `--tool-result-max-chars` | `20000` | 单个 tool result 发送给 provider 前保留的最大字符数。 |
| `--disable-compaction` | false | 禁用自动 compaction；超大 tool result 仍可能被截断。 |

示例：

```cmd
pyclaw "继续分析这个项目" --session-id demo --context-window-tokens 200000 --reserve-tokens 20000 --keep-recent-tokens 30000
```

## 8. Transcript 展示变化

`pyclaw transcripts show demo --format text`：

- 只展示 message entry。
- 跳过 session header 和 compaction entry。

`pyclaw transcripts show demo --format detail`：

- 展示 session header。
- 展示 message 的 provider/model/stop/usage。
- 展示 compaction 的 `firstKeptEntryId`、`tokensBefore`、`details` 和 summary。

`pyclaw transcripts show demo --format json`：

- 输出原始 JSONL entries，包括 session header、message、compaction、leaf 等。
- 适合调试、审计和后续 migration。

## 9. 与原生 OpenClaw 的对应关系

| OpenClaw 设计 | pyclaw 当前实现 |
| --- | --- |
| JSONL session header | 已实现，version=3。 |
| message entry parent tree | 已实现，message parentId 串联。 |
| leaf control | 已提供 `append_leaf()` 和 `resolve_leaf_id()`；主流程暂未做分支 UI。 |
| `getBranch()` | 已实现 `get_branch()`。 |
| `buildSessionContext()` | 已实现 `build_session_context()`。 |
| compaction entry | 已实现 append-only compaction entry。 |
| LLM summary | 当前为确定性 fallback summary，后续可替换为真实 LLM summarizer。 |
| provider usage token 优先 | 已实现 `normalize_usage()` 与 `estimate_context_tokens()`。 |
| preemptive compaction route | 已实现 fits/compact/truncate/compact_then_truncate。 |
| oversized tool result truncation | 已实现 provider-bound copy 截断，不改 transcript。 |
| provider overflow recovery | 已实现一次 compaction + retry。 |
| timeout-triggered compaction | 尚未作为独立 timeout 压力策略实现；当前 timeout 仍走 retry policy。 |
| compaction timeout guard | 尚未实现异步 LLM compaction，所以暂不需要 timeout guard。 |

## 10. 当前边界与后续建议

1. 当前 compaction summary 是本地确定性摘要，不是 LLM 摘要。优点是稳定、无成本；缺点是信息压缩质量有限。
2. 已有 JSONL branch replay 能支持后续“从某个历史节点继续”功能，但 CLI 暂未提供 branch 操作命令。
3. `--context-window-tokens` 仍是手动估算，后续可以接 provider/model catalog 自动填充。
4. timeout-triggered compaction 和 compaction timeout guard 可以等真实 LLM summarizer 接入后再做，否则当前没有异步压缩任务需要 guard。
5. 当前 tool result truncation 只影响 provider 调用，不修改 transcript。这符合审计优先策略。

## 11. 验证

已执行：

```powershell
py -m compileall openclaw tests
py -m unittest discover -s tests
```

结果：

- 编译通过。
- 单元测试通过：`Ran 89 tests OK`。
