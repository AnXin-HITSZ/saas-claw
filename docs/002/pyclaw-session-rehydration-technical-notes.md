# pyclaw Session Rehydration 技术记录

## 背景

此前 `pyclaw --session-id demo "..."` 只做到了两件事：

1. 使用固定 session id 找到同一个 transcript 文件。
2. 把本次用户消息、助手消息、工具结果继续追加到该 JSONL 文件。

但是每次运行 CLI 都会重新创建一个 `Agent` 实例。新的 `Agent.state.messages` 初始为空，如果不主动读取历史 transcript，模型请求里就只会包含当前这一轮输入。因此用户即使复用同一个 `--session-id`，Agent 也无法知道上一轮发生了什么。

换句话说，旧行为是：

```text
--session-id demo
  -> 只决定写入哪个 transcript 文件
  -> 不决定模型上下文里有哪些历史消息
```

这就是用户看到“刚才让你做了什么”无法被回答的原因。

## 本次目标

让相同 `--session-id` 对应的历史 transcript 在新 CLI 进程启动后重新进入 Agent 上下文。

目标不是实现长期记忆系统，而是实现 session 级上下文重放：

```text
transcript JSONL
  -> 读取历史 message entries
  -> 反序列化为 AgentMessage
  -> 填入 Agent.state.messages
  -> 当前新 prompt 追加在历史消息之后
  -> provider 请求看到完整会话上下文
```

## 修改点

### 1. Transcript 支持读取历史 entries

文件：

```text
openclaw/session/transcript.py
```

新增：

```python
def read_entries(self) -> list[dict[str, Any]]:
    ...
```

作用：

- 如果 transcript 文件不存在，返回空列表。
- 逐行读取 JSONL。
- 跳过空行。
- 将每行 JSON 解析为 dict。

JSONL 仍然保持一行一个事件对象，例如：

```json
{"type":"message","id":"...","timestamp":"...","message":{"role":"user","content":[...]}}
```

### 2. Transcript 支持反序列化消息

文件：

```text
openclaw/session/transcript.py
```

新增：

```python
def read_messages(self) -> list[AgentMessage]:
    ...
```

作用：

- 只读取 `type == "message"` 的 transcript entry。
- 从 entry 的 `message` 字段取出消息 dict。
- 使用 `openclaw.llm.types.message_from_dict()` 转回 `UserMessage`、`AssistantMessage` 或 `ToolMessage`。

这样 transcript 的持久化格式和运行时消息模型之间有了双向通道：

```text
message_to_dict()
  -> 写入 JSONL

message_from_dict()
  -> 从 JSONL 恢复为运行时消息
```

### 3. AgentSession 启动前加载一次历史

文件：

```text
openclaw/session/agent_session.py
```

新增字段：

```python
self._history_loaded = False
```

新增方法：

```python
def load_history_once(self) -> None:
    if self._history_loaded:
        return
    self._history_loaded = True
    if self.agent.state.messages:
        return
    self.agent.state.messages.extend(self.transcript.read_messages())
```

`run_prompt()` 开始时调用：

```python
self.load_history_once()
```

执行顺序变为：

```text
AgentSession.run_prompt(text)
  -> load_history_once()
     -> 如果内存消息为空，从 transcript 读取历史消息
  -> agent.prompt(text)
     -> 追加当前 user message
     -> 调用 provider
     -> 追加 assistant/tool messages
  -> message_end event
     -> 新消息继续写回 transcript
```

## 为什么只加载一次

同一个 `AgentSession` 对象可能连续调用多次 `run_prompt()`。如果每次都把 transcript 全量塞回 `agent.state.messages`，会导致历史消息重复：

```text
user: A
assistant: B
user: A
assistant: B
user: C
```

因此 `_history_loaded` 用于保证同一个 session 对象只做一次 transcript replay。

## 为什么只在内存消息为空时加载

`Agent` 也可以被外部代码预先填入上下文，例如测试、嵌入式调用或未来的高级 session 管理逻辑。

如果 `agent.state.messages` 已经有内容，`AgentSession` 不再覆盖它，避免破坏调用方显式准备好的上下文。

规则是：

```text
内存已有上下文：尊重内存上下文
内存没有上下文：从 transcript 恢复 session 历史
```

## 当前能力边界

本次实现的是 session 记忆，不是长期记忆。

### Session 记忆

同一个 `--session-id` 下的历史消息会被重放进模型上下文。

示例：

```cmd
pyclaw --session-id demo "请记住：我喜欢 Python"
pyclaw --session-id demo "我喜欢什么语言？"
```

第二次请求会把第一次的 user/assistant 消息一起发送给 provider，因此模型可以基于上下文回答。

### 长期记忆

长期记忆通常需要额外系统：

- 从对话中抽取稳定事实。
- 存入结构化数据库或向量库。
- 新会话开始时按相关性检索。
- 将检索结果注入系统提示或上下文。

这些能力当前尚未实现。

## 与 transcript 查看命令的关系

`pyclaw transcripts show demo --format text/detail/json` 只是读取并展示 transcript 文件。

本次新增的 session rehydration 是运行时行为：

```text
transcripts show
  -> 给人看历史记录

AgentSession.load_history_once()
  -> 给模型恢复历史上下文
```

二者读取同一个 JSONL 来源，但用途不同。

## 测试

新增测试：

```text
tests/test_session.py::SessionTests.test_session_rehydrates_transcript_for_same_session_id
```

测试过程：

1. 创建第一个 `AgentSession`。
2. 使用 transcript 写入第一轮：
   - user: `first question`
   - assistant: `first answer`
3. 创建新的 `Agent` 和新的 `AgentSession`，但复用同一个 transcript 路径。
4. 运行第二轮：
   - user: `second question`
5. 检查第二个 provider 收到的消息顺序：

```text
user      first question
assistant first answer
user      second question
```

这证明新的 Agent 确实从 transcript 恢复了历史上下文。

验证命令：

```cmd
py -m compileall openclaw tests
py -m unittest discover -s tests
```

当前结果：

```text
Ran 69 tests
OK
```

## 后续可选增强

1. 增加 transcript 损坏行容错，避免单行 JSON 解析失败导致整个 session 无法恢复。
2. 增加最大重放消息数或 token 预算，防止很长 session 超出模型上下文。
3. 增加 `pyclaw sessions clear` 或 `pyclaw transcripts prune`，用于清理历史。
4. 实现真正的长期记忆：抽取、存储、检索、注入。
