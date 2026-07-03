# OpenClaw 会话存储、重放、压缩与 Token 计数实现技术文档

本文档基于 `D:\learn\openclaw` 源码，整理图中 5 个方面在 OpenClaw 生产代码中的对应实现：

| 方面 | claw0 概念 | OpenClaw 对应实现 |
| --- | --- | --- |
| 存储格式 | JSONL 文件，每个会话一个 | 相同的 JSONL 格式，且有 session header、树形 entry、leaf 控制 |
| 重放 | `_rebuild_history()` | `buildSessionContext()` / `getBranch()` 按分支重建 LLM 上下文 |
| 溢出处理 | 3 阶段保护 | 预检查、自动压缩、工具结果截断/放弃，并补充 timeout compaction |
| 压缩 | LLM 摘要替换旧消息 | compaction entry 保存摘要和 `firstKeptEntryId`，重放时摘要 + retained tail |
| token 估算 | `len(text) // 4` 启发式 | 优先使用 provider usage；无 usage 或 tail 部分用字符启发式估算 |

## 1. 关键源码总览

| 主题 | 源码位置 | 说明 |
| --- | --- | --- |
| 通用 JSONL 存储 | [packages/agent-core/src/harness/session/jsonl-storage.ts:22](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:22) | JSONL 第一行 session header |
| JSONL 读取与校验 | [packages/agent-core/src/harness/session/jsonl-storage.ts:176](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:176) | 读取文件、逐行 parse entry、维护 leaf |
| JSONL 追加写入 | [packages/agent-core/src/harness/session/jsonl-storage.ts:243](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:243) | 创建 header、append entry |
| 生产 SessionManager 类型 | [src/agents/sessions/session-manager.ts:56](D:/learn/openclaw/src/agents/sessions/session-manager.ts:56) | session header、message、compaction、branch summary 等 entry |
| SessionManager 加载/新建 | [src/agents/sessions/session-manager.ts:1451](D:/learn/openclaw/src/agents/sessions/session-manager.ts:1451) | 加载 JSONL、迁移版本、新建 `.jsonl` 文件 |
| SessionManager 追加持久化 | [src/agents/sessions/session-manager.ts:2094](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2094) | full rewrite 或 append JSONL entry |
| message 持久化入口 | [src/agents/sessions/agent-session.ts:617](D:/learn/openclaw/src/agents/sessions/agent-session.ts:617) | `message_end` 时 append 到 session |
| 分支重放入口 | [src/agents/sessions/session-manager.ts:2557](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2557) | `getBranch()` 从 leaf 回溯到 root |
| 重建模型上下文 | [packages/agent-core/src/harness/session/session.ts:27](D:/learn/openclaw/packages/agent-core/src/harness/session/session.ts:27) | `buildSessionContext()` 生成 LLM messages |
| compaction summary 消息 | [packages/agent-core/src/harness/messages.ts:90](D:/learn/openclaw/packages/agent-core/src/harness/messages.ts:90) | 创建 `compactionSummary` message |
| compaction summary 转 LLM | [packages/agent-core/src/harness/messages.ts:122](D:/learn/openclaw/packages/agent-core/src/harness/messages.ts:122) | `convertToLlm()` 把摘要转成 user message |
| token usage 归一化 | [src/agents/usage.ts:129](D:/learn/openclaw/src/agents/usage.ts:129) | 将各 provider usage 字段统一为 input/output/cache/total |
| context token 估算 | [packages/agent-core/src/harness/compaction/compaction.ts:204](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:204) | 优先 usage，补估 trailing tokens |
| 字符启发式估算 | [packages/agent-core/src/harness/compaction/compaction.ts:261](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:261) | `Math.ceil(chars / 4)` |
| compaction 阈值 | [packages/agent-core/src/harness/compaction/compaction.ts:131](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:131) | reserveTokens、keepRecentTokens |
| compaction 准备 | [packages/agent-core/src/harness/compaction/compaction.ts:633](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:633) | 选择 cut point，确定摘要范围和 retained tail |
| compaction 执行 | [packages/agent-core/src/harness/compaction/compaction.ts:729](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:729) | 生成 summary，返回 `firstKeptEntryId` |
| 多阶段摘要 | [src/agents/compaction.ts:341](D:/learn/openclaw/src/agents/compaction.ts:341) | `summarizeInStages()` 分块摘要再合并 |
| 摘要 fallback | [src/agents/compaction.ts:155](D:/learn/openclaw/src/agents/compaction.ts:155) | chunk 重试、partial summary、fallback |
| 自动压缩触发 | [src/agents/sessions/agent-session.ts:2001](D:/learn/openclaw/src/agents/sessions/agent-session.ts:2001) | overflow / threshold 两类 |
| 预检查溢出路由 | [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:279](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:279) | fits / compact_only / truncate_tool_results_only / compact_then_truncate |
| provider overflow 恢复 | [src/agents/embedded-agent-runner/run.ts:2635](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2635) | 捕获上下文溢出错误后 compact、truncate、give up |
| timeout-triggered compaction | [src/agents/embedded-agent-runner/run.ts:2473](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2473) | LLM timeout 且 prompt token 压力大时先压缩再重试 |
| compaction timeout 保护 | [src/agents/embedded-agent-runner/run/compaction-timeout.ts:13](D:/learn/openclaw/src/agents/embedded-agent-runner/run/compaction-timeout.ts:13) | 压缩期间超时只给一次 grace，再失败则终止 |

## 2. 存储格式：JSONL，每个会话一个文件

OpenClaw 的会话存储确实是 JSONL。通用 harness 版本在 `JsonlSessionStorage`，生产会话管理在 `SessionManager`。

### 2.1 文件结构

JSONL 文件第一行是 session header：

```ts
interface SessionHeader {
  type: "session";
  version: 3;
  id: string;
  timestamp: string;
  cwd: string;
  parentSession?: string;
}
```

源码：

- [packages/agent-core/src/harness/session/jsonl-storage.ts:22](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:22)
- [src/agents/sessions/session-manager.ts:56](D:/learn/openclaw/src/agents/sessions/session-manager.ts:56)

header 后面每一行是一个 session entry，例如：

- `message`
- `thinking_level_change`
- `model_change`
- `compaction`
- `branch_summary`
- `custom`
- `custom_message`
- `label`
- `session_info`
- `leaf`

生产类型定义见：

- [src/agents/sessions/session-manager.ts:79](D:/learn/openclaw/src/agents/sessions/session-manager.ts:79)
- [src/agents/sessions/session-manager.ts:95](D:/learn/openclaw/src/agents/sessions/session-manager.ts:95)
- [src/agents/sessions/session-manager.ts:183](D:/learn/openclaw/src/agents/sessions/session-manager.ts:183)

### 2.2 读取与解析

通用 JSONL 读取流程：

1. `readTextFile(filePath)` 读取完整文件。
2. `split("\n")` 分行，过滤空行。
3. 第一行调用 `parseHeaderLine()`。
4. 后续行调用 `parseEntryLine()`。
5. 逐条 entry 更新 `leafId` 和 `appendParentId`。

源码：

- [packages/agent-core/src/harness/session/jsonl-storage.ts:176](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:176)
- [packages/agent-core/src/harness/session/jsonl-storage.ts:194](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:194)
- [packages/agent-core/src/harness/session/jsonl-storage.ts:198](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:198)

生产 `SessionManager` 还做了更多兼容：

- 支持版本迁移。
- 支持保留未知 opaque entry。
- 空文件或损坏 header 会尝试恢复或重写。
- 可以从 `.jsonl` 文件重建内存索引。

源码：

- [src/agents/sessions/session-manager.ts:349](D:/learn/openclaw/src/agents/sessions/session-manager.ts:349)
- [src/agents/sessions/session-manager.ts:1075](D:/learn/openclaw/src/agents/sessions/session-manager.ts:1075)
- [src/agents/sessions/session-manager.ts:1506](D:/learn/openclaw/src/agents/sessions/session-manager.ts:1506)

### 2.3 写入与追加

通用 harness 写入：

- 新会话：写入 header 和换行。
- 普通 entry：`appendFile(filePath, JSON.stringify(entry) + "\n")`。
- leaf 控制：同样 append 一条 leaf entry。

源码：

- [packages/agent-core/src/harness/session/jsonl-storage.ts:243](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:243)
- [packages/agent-core/src/harness/session/jsonl-storage.ts:268](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:268)
- [packages/agent-core/src/harness/session/jsonl-storage.ts:277](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts:277)

生产 `SessionManager` 写入更复杂：

- 如果 session 还没有 assistant message，不立即落盘完整内容，避免只保存半截用户输入。
- 首次 flush 会 `writeFullFile()`。
- 后续通常通过 `appendSerializedJsonlEntrySync()` 追加 entry。
- 追加前后维护 session file snapshot/cache，避免并发或外部 rewrite 造成状态错乱。

源码：

- [src/agents/sessions/session-manager.ts:2094](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2094)
- [src/agents/sessions/session-manager.ts:2112](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2112)
- [src/agents/sessions/session-manager.ts:2121](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2121)

消息真正被持久化的触发点在 `AgentSession.handleAgentEvent()`：当 `message_end` 到来时，普通 LLM message 被写成 `SessionMessageEntry`。

源码：[src/agents/sessions/agent-session.ts:617](D:/learn/openclaw/src/agents/sessions/agent-session.ts:617)

## 3. 重放：不是 `_rebuild_history()`，而是 branch + buildSessionContext

图中 claw0 的 `_rebuild_history()` 在 OpenClaw 中对应两层逻辑：

1. `SessionManager.getBranch()`：从当前 leaf 沿 `parentId` 回溯到 root，得到当前分支。
2. `buildSessionContext()`：把分支 entries 转成真正要送给 LLM 的 messages。

### 3.1 分支回溯

`getBranch()` 逻辑：

- 起点是当前 `leafId`。
- 用 `byId` 查当前 entry。
- 将 entry 插到 path 前面。
- 继续跟随 `parentId`。
- 防止循环：用 `seen` 记录访问过的 id。

源码：[src/agents/sessions/session-manager.ts:2557](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2557)

### 3.2 上下文构建

通用 `buildSessionContext(pathEntries)` 的职责：

- 扫描 `thinking_level_change`、`model_change`、assistant message，得到最新 thinking/model 状态。
- 找到最新 `compaction`。
- 如果没有 compaction：按顺序 append 所有可进入模型上下文的 message。
- 如果有 compaction：先放入一条合成的 `compactionSummary`，然后只 replay `firstKeptEntryId` 之后的 retained tail。

源码：

- [packages/agent-core/src/harness/session/session.ts:27](D:/learn/openclaw/packages/agent-core/src/harness/session/session.ts:27)
- [packages/agent-core/src/harness/session/session.ts:68](D:/learn/openclaw/packages/agent-core/src/harness/session/session.ts:68)
- [packages/agent-core/src/harness/session/session.ts:81](D:/learn/openclaw/packages/agent-core/src/harness/session/session.ts:81)

生产 `SessionManager.buildSessionContext()` 直接复用 core 逻辑：

- [src/agents/sessions/session-manager.ts:383](D:/learn/openclaw/src/agents/sessions/session-manager.ts:383)
- [src/agents/sessions/session-manager.ts:430](D:/learn/openclaw/src/agents/sessions/session-manager.ts:430)
- [src/agents/sessions/session-manager.ts:2580](D:/learn/openclaw/src/agents/sessions/session-manager.ts:2580)

### 3.3 特殊消息如何进入 LLM

`convertToLlm()` 将 harness message 变成模型消息：

- `bashExecution` 转成 user text。
- `custom` 转成 user message。
- `branchSummary` 转成带 summary tag 的 user message。
- `compactionSummary` 转成带 summary tag 的 user message。
- `user`、`assistant`、`toolResult` 原样保留。

源码：

- [packages/agent-core/src/harness/messages.ts:122](D:/learn/openclaw/packages/agent-core/src/harness/messages.ts:122)
- [packages/agent-core/src/harness/messages.ts:148](D:/learn/openclaw/packages/agent-core/src/harness/messages.ts:148)
- [packages/agent-core/src/harness/messages.ts:159](D:/learn/openclaw/packages/agent-core/src/harness/messages.ts:159)

## 4. 压缩：LLM 摘要替换旧消息

OpenClaw 的 compaction 不是修改旧消息，而是追加一条 `compaction` entry：

```ts
interface CompactionEntry {
  type: "compaction";
  summary: string;
  firstKeptEntryId: string;
  tokensBefore: number;
  details?: unknown;
  fromHook?: boolean;
}
```

源码：[src/agents/sessions/session-manager.ts:95](D:/learn/openclaw/src/agents/sessions/session-manager.ts:95)

### 4.1 压缩准备

`prepareCompaction()` 做这些事：

1. 如果最后一条已经是 compaction，则跳过。
2. 找到上一次 compaction。
3. 如果之前压缩过，则取 previous summary，并从上次 retained tail 起点继续计算。
4. 用 `estimateContextTokens(buildSessionContext(...).messages)` 计算压缩前 token。
5. 用 `findCutPoint()` 保留最近 `keepRecentTokens` 以内的尾部上下文。
6. 将旧历史放入 `messagesToSummarize`。
7. 如果 cut point 切在一个 turn 中间，则额外生成 `turnPrefixMessages`。
8. 提取被摘要历史中的读写文件信息。

源码：

- [packages/agent-core/src/harness/compaction/compaction.ts:633](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:633)
- [packages/agent-core/src/harness/compaction/compaction.ts:662](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:662)
- [packages/agent-core/src/harness/compaction/compaction.ts:664](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:664)
- [packages/agent-core/src/harness/compaction/compaction.ts:676](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:676)

默认 compaction 设置：

- `enabled: true`
- `reserveTokens: 16384`
- `keepRecentTokens: 20000`

源码：[packages/agent-core/src/harness/compaction/compaction.ts:131](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:131)

### 4.2 cut point 选择

`findCutPoint()` 从尾部往前累计消息 token，直到达到 `keepRecentTokens`，然后选择一个合法 cut point。

合法 cut point 会避开不安全的 `toolResult`，尽量从 user/assistant/custom/summary 等边界切开。

源码：

- [packages/agent-core/src/harness/compaction/compaction.ts:387](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:387)
- [packages/agent-core/src/harness/compaction/compaction.ts:399](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:399)
- [packages/agent-core/src/harness/compaction/compaction.ts:313](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:313)

### 4.3 摘要生成与落盘

`compact()` 根据 `CompactionPreparation` 生成摘要：

- 普通情况：调用 `generateSummary()`。
- split turn 情况：同时摘要旧 history 和当前 turn prefix，再组合成一个 summary。
- 最后追加文件操作列表。
- 返回 `summary`、`firstKeptEntryId`、`tokensBefore`、`details`。

源码：

- [packages/agent-core/src/harness/compaction/compaction.ts:729](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:729)
- [packages/agent-core/src/harness/compaction/compaction.ts:763](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:763)
- [packages/agent-core/src/harness/compaction/compaction.ts:800](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:800)

`AgentSession.runCompactionWork()` 负责调用 `compact()`，然后通过 `sessionManager.appendCompaction()` 保存摘要，并把 agent state 的 messages 替换为新的 compacted context。

源码：

- [src/agents/sessions/agent-session.ts:1901](D:/learn/openclaw/src/agents/sessions/agent-session.ts:1901)
- [src/agents/sessions/agent-session.ts:1957](D:/learn/openclaw/src/agents/sessions/agent-session.ts:1957)
- [src/agents/sessions/agent-session.ts:1974](D:/learn/openclaw/src/agents/sessions/agent-session.ts:1974)
- [src/agents/sessions/agent-session.ts:1981](D:/learn/openclaw/src/agents/sessions/agent-session.ts:1981)

### 4.4 多阶段摘要与 fallback

OpenClaw 生产代码还补了大 transcript 的多阶段摘要：

- `summarizeInStages()` 调用 `buildStageSplitPlanWithWorker()` 判断是否需要分块。
- 多块时先对每个 chunk 生成 partial summary。
- 再把 partial summaries 合并成最终 summary。
- `summarizeWithFallback()` 负责渐进 fallback：重试、超大消息 retry、partial summary、最终 fallback note。

源码：

- [src/agents/compaction.ts:341](D:/learn/openclaw/src/agents/compaction.ts:341)
- [src/agents/compaction.ts:362](D:/learn/openclaw/src/agents/compaction.ts:362)
- [src/agents/compaction.ts:374](D:/learn/openclaw/src/agents/compaction.ts:374)
- [src/agents/compaction.ts:400](D:/learn/openclaw/src/agents/compaction.ts:400)
- [src/agents/compaction.ts:155](D:/learn/openclaw/src/agents/compaction.ts:155)

## 5. 溢出处理：从“3 阶段保护”到生产级恢复

图中说的“3 阶段保护”在 OpenClaw 中可对应为：

1. **预检查阶段**：模型调用前估算 prompt 压力，决定是否提前压缩或截断。
2. **溢出恢复阶段**：provider 返回 context overflow 后，执行自动压缩并 retry。
3. **截断/放弃阶段**：如果压缩不够，尝试截断超大 tool result；仍失败则返回可见错误，让用户 reset/new 或换大上下文模型。

生产代码还额外加入：

- timeout-triggered compaction：LLM timeout 且 prompt token 使用率高时先压缩再 retry。
- compaction timeout guard：压缩过程卡住时只给一次 grace window。
- post-compaction loop guard：压缩后如果工具调用结果反复循环，提前 abort。

### 5.1 预检查：preemptive compaction

核心函数是 `shouldPreemptivelyCompactBeforePrompt()`。

输入：

- 当前 messages
- 可选 unwindowed messages
- system prompt
- 当前 prompt
- context token budget
- reserveTokens
- tool result max chars
- 可选 LLM boundary token pressure

输出 route：

- `fits`
- `compact_only`
- `truncate_tool_results_only`
- `compact_then_truncate`

源码：

- [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:279](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:279)
- [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:313](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:313)
- [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:325](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:325)
- [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:339](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:339)

其中 token 估算会给每条消息加边界 overhead，并针对 tool result、bashExecution、summary、assistant tool call 等分别估算。

源码：

- [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:158](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:158)
- [src/agents/embedded-agent-runner/run/preemptive-compaction.ts:221](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts:221)

### 5.2 mid-turn precheck

如果是在工具调用后、下一次模型调用前发现会溢出，OpenClaw 会抛出内部控制流信号 `MidTurnPrecheckSignal`，让 attempt runner 走 overflow recovery，而不是当成普通 provider failure。

源码：

- [src/agents/embedded-agent-runner/run/midturn-precheck.ts:19](D:/learn/openclaw/src/agents/embedded-agent-runner/run/midturn-precheck.ts:19)
- [src/agents/embedded-agent-runner/run/midturn-precheck.ts:23](D:/learn/openclaw/src/agents/embedded-agent-runner/run/midturn-precheck.ts:23)

### 5.3 provider overflow 后自动压缩

`run.ts` 中会检查：

- `promptError` 是否是 context overflow。
- assistant error text 是否像 context overflow。
- 尝试从错误文本提取 observed tokens。
- 如果本 attempt 已经发生 compaction，则避免重复 compaction。
- 否则调用 `contextEngine.compact()`，并设置 safety timeout。
- 压缩成功后 retry prompt。

源码：

- [src/agents/embedded-agent-runner/run.ts:2614](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2614)
- [src/agents/embedded-agent-runner/run.ts:2635](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2635)
- [src/agents/embedded-agent-runner/run.ts:2674](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2674)
- [src/agents/embedded-agent-runner/run.ts:2692](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2692)
- [src/agents/embedded-agent-runner/run.ts:2757](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2757)
- [src/agents/embedded-agent-runner/run.ts:2817](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2817)

### 5.4 工具结果截断

如果压缩后还是不够，或者判断是超大 tool result 导致的溢出，OpenClaw 会尝试 `truncateOversizedToolResultsInSession()`。

源码：

- [src/agents/embedded-agent-runner/run.ts:2826](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2826)
- [src/agents/embedded-agent-runner/run.ts:2873](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2873)
- [src/agents/embedded-agent-runner/run.ts:2894](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2894)

### 5.5 最终放弃与用户可见错误

当达到最大 overflow compaction attempts，或错误本身是 compaction failure，最终会返回：

> Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model.

源码：[src/agents/embedded-agent-runner/run.ts:2917](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2917)

### 5.6 timeout-triggered compaction

如果不是明确 context overflow，而是 LLM timeout，同时最后一次 prompt token 使用率超过 65%，OpenClaw 会先 compact 再 retry，避免“大上下文导致反复超时”的死循环。

源码：

- [src/agents/embedded-agent-runner/run.ts:2473](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2473)
- [src/agents/embedded-agent-runner/run.ts:2480](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2480)
- [src/agents/embedded-agent-runner/run.ts:2489](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2489)
- [src/agents/embedded-agent-runner/run.ts:2553](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts:2553)

### 5.7 compaction timeout guard

压缩期间如果 run timeout：

- 第一次且确实是 compaction pending/in-flight：extend。
- 第二次或不是 compaction 导致：abort。
- 如果 timeout 发生在 compaction 中，重试时优先选择 pre-compaction snapshot，且会裁剪不安全的 assistant tail。

源码：

- [src/agents/embedded-agent-runner/run/compaction-timeout.ts:13](D:/learn/openclaw/src/agents/embedded-agent-runner/run/compaction-timeout.ts:13)
- [src/agents/embedded-agent-runner/run/compaction-timeout.ts:21](D:/learn/openclaw/src/agents/embedded-agent-runner/run/compaction-timeout.ts:21)
- [src/agents/embedded-agent-runner/run/compaction-timeout.ts:68](D:/learn/openclaw/src/agents/embedded-agent-runner/run/compaction-timeout.ts:68)
- [src/agents/embedded-agent-runner/run/compaction-timeout.ts:79](D:/learn/openclaw/src/agents/embedded-agent-runner/run/compaction-timeout.ts:79)

## 6. Token 计数：API usage 优先，启发式补缺口

图中 `len(text) // 4` 在 OpenClaw 中确实存在，但只是一部分。

### 6.1 provider usage 归一化

OpenClaw 支持多种 provider usage 字段：

- `input` / `output`
- `inputTokens` / `outputTokens`
- `prompt_tokens` / `completion_tokens`
- `input_tokens` / `output_tokens`
- `cache_read_input_tokens`
- `cache_creation_input_tokens`
- `cached_tokens`
- `reasoning_tokens`
- `total_tokens`
- llama.cpp 风格 `prompt_n` / `predicted_n`

源码：

- [src/agents/usage.ts:8](D:/learn/openclaw/src/agents/usage.ts:8)
- [src/agents/usage.ts:129](D:/learn/openclaw/src/agents/usage.ts:129)

`normalizeUsage()` 会把这些字段统一成：

- `input`
- `output`
- `cacheRead`
- `cacheWrite`
- `reasoningTokens`
- `total`

并处理 OpenAI 风格 cached tokens 是否已包含在 prompt total 中的问题。

源码：[src/agents/usage.ts:153](D:/learn/openclaw/src/agents/usage.ts:153)

### 6.2 context token 估算

`estimateContextTokens(messages)` 的策略：

1. 找到最近一个成功 assistant message 的 `usage`。
2. 如果没有 usage：全部消息用 `estimateTokens()` 估算。
3. 如果有 usage：用 provider usage 作为历史已知 token。
4. 对这个 assistant message 之后新增的 tail messages，用 `estimateTokens()` 补估。
5. 返回 `tokens = usageTokens + trailingTokens`。

源码：

- [packages/agent-core/src/harness/compaction/compaction.ts:192](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:192)
- [packages/agent-core/src/harness/compaction/compaction.ts:204](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:204)
- [packages/agent-core/src/harness/compaction/compaction.ts:221](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:221)

### 6.3 字符启发式

`estimateTokens(message)` 使用 `Math.ceil(chars / 4)`，但它不是简单对整段 JSON 字符串算长度，而是按 message 类型分开处理：

- user：字符串长度或 content block 文本长度。
- assistant：text/thinking/toolCall 的名称和参数 JSON。
- toolResult/custom：content 字符长度。
- bashExecution：command + output。
- branchSummary/compactionSummary：summary。
- image block 固定折算为 `IMAGE_BLOCK_CHARS = 4800`。

源码：

- [packages/agent-core/src/harness/compaction/compaction.ts:247](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:247)
- [packages/agent-core/src/harness/compaction/compaction.ts:261](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:261)
- [packages/agent-core/src/harness/compaction/compaction.ts:278](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:278)
- [packages/agent-core/src/harness/compaction/compaction.ts:291](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts:291)

### 6.4 context window guard

OpenClaw 还会解析有效 context window：

- provider/model 元数据中的 context window。
- 配置文件 `models.providers[].models[].contextTokens/contextWindow`。
- agent 默认 `agents.defaults.contextTokens` cap。
- 缺省值。

并计算 warning/block 阈值：

- hard min：`max(4000, contextWindow * 0.1)`
- warn below：`max(8000, contextWindow * 0.2)`

源码：

- [src/agents/context-window-guard.ts:53](D:/learn/openclaw/src/agents/context-window-guard.ts:53)
- [src/agents/context-window-guard.ts:127](D:/learn/openclaw/src/agents/context-window-guard.ts:127)
- [src/agents/context-window-guard.ts:202](D:/learn/openclaw/src/agents/context-window-guard.ts:202)

## 7. 对 Python 版 pyclaw 的迁移建议

### 7.1 JSONL session 格式

建议保留 OpenClaw 的结构：

```python
class SessionHeader(BaseModel):
    type: Literal["session"] = "session"
    version: int = 3
    id: str
    timestamp: str
    cwd: str
    parentSession: str | None = None

class SessionEntryBase(BaseModel):
    type: str
    id: str
    parentId: str | None
    timestamp: str
```

不要只保存 `messages: []` 数组。append-only JSONL 更适合：

- 崩溃恢复。
- 流式追加。
- 分支。
- compaction 作为一条不可变事件。
- 外部工具/插件追加状态。

### 7.2 重放接口

建议实现：

```python
def get_branch(entries: list[SessionEntry], leaf_id: str | None) -> list[SessionEntry]:
    by_id = {entry.id: entry for entry in entries}
    path = []
    seen = set()
    current_id = leaf_id
    while current_id and current_id not in seen:
        seen.add(current_id)
        entry = by_id.get(current_id)
        if entry is None:
            break
        path.insert(0, entry)
        current_id = entry.parentId
    return path

def build_session_context(path: list[SessionEntry]) -> list[Message]:
    latest_compaction = find_latest_compaction(path)
    if not latest_compaction:
        return entries_to_messages(path)
    return [
        compaction_summary_message(latest_compaction.summary),
        *entries_after(path, latest_compaction.firstKeptEntryId),
    ]
```

### 7.3 Token 计数策略

建议优先级：

1. 有 provider usage：使用 provider usage。
2. 没有 usage：用 tokenizer，如 `tiktoken` 或模型 provider tokenizer。
3. tokenizer 不可用：再用 `ceil(chars / 4)`。
4. 对 tool result、image、JSON payload 使用单独估算因子，不要只按纯文本估算。

### 7.4 溢出保护

建议按 OpenClaw 拆 4 层：

1. **pre-prompt precheck**：调用模型前估算 prompt 是否超过 `contextWindow - reserveTokens`。
2. **auto compaction**：超过阈值或 provider overflow 时 append compaction entry。
3. **tool result truncation**：优先截断最近的超大 tool result，而不是无脑摘要全部历史。
4. **terminal fallback**：压缩/截断均失败时返回可见错误，并建议 reset/new 或换大上下文模型。

### 7.5 compaction entry 不要删除旧历史

OpenClaw 的关键设计是：压缩不是删除旧 JSONL 行，而是追加：

```json
{
  "type": "compaction",
  "summary": "...",
  "firstKeptEntryId": "...",
  "tokensBefore": 123456
}
```

重放时用 summary 替代旧历史。这样旧 transcript 仍可审计、debug、重做压缩。

## 8. 推荐阅读顺序

建议按下列顺序阅读源码：

1. [packages/agent-core/src/harness/session/jsonl-storage.ts](D:/learn/openclaw/packages/agent-core/src/harness/session/jsonl-storage.ts)
2. [src/agents/sessions/session-manager.ts](D:/learn/openclaw/src/agents/sessions/session-manager.ts)
3. [packages/agent-core/src/harness/session/session.ts](D:/learn/openclaw/packages/agent-core/src/harness/session/session.ts)
4. [packages/agent-core/src/harness/messages.ts](D:/learn/openclaw/packages/agent-core/src/harness/messages.ts)
5. [packages/agent-core/src/harness/compaction/compaction.ts](D:/learn/openclaw/packages/agent-core/src/harness/compaction/compaction.ts)
6. [src/agents/compaction.ts](D:/learn/openclaw/src/agents/compaction.ts)
7. [src/agents/sessions/agent-session.ts](D:/learn/openclaw/src/agents/sessions/agent-session.ts)
8. [src/agents/usage.ts](D:/learn/openclaw/src/agents/usage.ts)
9. [src/agents/context-window-guard.ts](D:/learn/openclaw/src/agents/context-window-guard.ts)
10. [src/agents/embedded-agent-runner/run/preemptive-compaction.ts](D:/learn/openclaw/src/agents/embedded-agent-runner/run/preemptive-compaction.ts)
11. [src/agents/embedded-agent-runner/run.ts](D:/learn/openclaw/src/agents/embedded-agent-runner/run.ts)
12. [src/agents/embedded-agent-runner/run/compaction-timeout.ts](D:/learn/openclaw/src/agents/embedded-agent-runner/run/compaction-timeout.ts)

