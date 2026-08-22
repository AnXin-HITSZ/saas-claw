# Runtime 审批与子 Agent 深入研读笔记

> 记录对 `src/runtime/`（Claw Pod）审批机制与子 Agent 调用的逐层研读。
> 主题：LangGraph 中断/恢复底层机制 → 审批完整生命周期 → 子 Agent 调用与审批透传 → SSE 流式与并发 → 幂等去重细节。
> 配套代码：`src/runtime/` 全量；langgraph 源码在 `src/runtime/.venv/Lib/site-packages/langgraph/`。

---

## 0. 一张总览图

```
用户消息 → main.py /v1/chat/completions
  → claw_graph.astream (主图: prepare→router→executor)        ← main.py:343
      → executor_node astream 子图 (react: llm→tools→llm)     ← graph.py:251
          → _tool_node → execute_tool_call                    ← registry.py:96
              → 敏感工具 → approval_gate → interrupt()         ← approval.py:21
  挂起 → __interrupt__ chunk 逐层透传 → SSE 推前端              ← main.py:247
  用户审批 → backend → POST /approvals/callback → Command(resume) → 图恢复  ← main.py:370
```

**一句话核心**：`interrupt()` 抛 `GraphInterrupt` 挂起整个图 → 状态存 Redis checkpoint → 前端审批 → 后端回调 `Command(resume=…)` 让图从 checkpoint 重放恢复。**"整节点重跑"** 是理解一切后续代码的钥匙（中断点之前已执行的代码会再跑一遍，所以处处有"幂等 + 去重"）。

---

## 1. LangGraph 中断/恢复底层机制（地基）

### 1.1 interrupt() 真的抛异常

`interrupt(value)` 在节点内部抛 `GraphInterrupt((Interrupt(value=value, id=...),))`（[langgraph/types.py:851](src/runtime/.venv/Lib/site-packages/langgraph/types.py#L851)）。

- `Interrupt` 只有两个字段：`value`（你塞进去的载荷 dict）和 `id`（checkpoint 命名空间哈希）。
- **request_id 是 `value` 里的一个键，不是 `Interrupt.id`**。

### 1.2 两步分工：一个"出 chunk"，一个"放异常"

| 函数 | 位置 | 职责 |
|---|---|---|
| `_runner.commit` | [langgraph/pregel/_runner.py:588](src/runtime/.venv/Lib/site-packages/langgraph/pregel/_runner.py#L588) | 把 `(INTERRUPT, 载荷)` 写进 checkpoint → 产生 `__interrupt__` updates chunk（**任何层级都出 chunk**） |
| `_suppress_interrupt` | [langgraph/pregel/_loop.py:1317](src/runtime/.venv/Lib/site-packages/langgraph/pregel/_loop.py#L1317) | 决定异常**是否也逃逸** |

`_suppress_interrupt` 的关键判定（[:1336](src/runtime/.venv/Lib/site-packages/langgraph/pregel/_loop.py#L1336)）：

```python
if isinstance(exc_value, GraphInterrupt) and not self.is_nested:
    ...  # 抑制：转成 __interrupt__ 输出，异常不逃逸
```

`is_nested` 的判定（[:314](src/runtime/.venv/Lib/site-packages/langgraph/pregel/_loop.py#L314)）：

```python
self.is_nested = CONFIG_KEY_TASK_ID in self.config.get(CONF, {})
```

### 1.3 所以"触发审批为什么不是抛异常"

- **节点内部确实抛异常**，但 pregel 引擎在**顶层图**（`is_nested=False`）把它吞掉、转成 `__interrupt__` 流事件 → 消费方从 chunk 读，不 try/except。
- **嵌套子图**（`is_nested=True`）是例外：不抑制，异常会逃逸。这正是 call_agent 原始 bug 的根因（见 §7）。
- 设计成"流事件"而非"裸异常"：挂起是**有状态、可恢复的暂停**，载荷进 checkpoint，可 `Command(resume)` 续跑。

### 1.4 resume = 节点从头重跑 + 位置匹配

审批恢复时，LangGraph 从 checkpoint 重放，**包含 interrupt 的节点从第一行重新执行**（不是从暂停处续跑）。`interrupt()` 按**调用位置**（`scratchpad.interrupt_counter()`）查 resume 值：

```python
idx = scratchpad.interrupt_counter()
if scratchpad.resume and idx < len(scratchpad.resume):
    return scratchpad.resume[idx]   # 有这个位置的值 → 返回决策（不抛）
raise GraphInterrupt(...)           # 没有 → 新挂起
```

决策来自 `/approvals/callback` 的 `Command(resume=body.result)`（[main.py:392](src/runtime/app/main.py#L392)）。

**重要推论**：节点从头重跑 → `interrupt()` 之前的代码会重跑（如 POST 审批、发 trace 事件）→ 必须幂等 + dedup_key。

---

## 2. 审批完整生命周期（代码走读）

### 2.1 触发：execute_tool_call

Agent 的 `_tool_node`（[graph.py:109-143](src/runtime/app/graph.py#L109-L143)）拆出 AI 消息里的工具调用，逐个走 `execute_tool_call`（[registry.py:96-161](src/runtime/app/tools/registry.py#L96-L161)）：

```python
spec = _spec_by_name(state.get("tool_specs", []), name)
is_sensitive = spec is not None and spec.get("is_sensitive")   # registry.py:123
if is_sensitive:
    result = await approval_gate(state, name, args, spec["id"], ...)  # :141-143
```

**会 interrupt 的工具必须串行**（`_INTERRUPTING_TOOLS` + 敏感判定，[graph.py:123-128](src/runtime/app/graph.py#L123-L128)）；普通工具才 `asyncio.gather` 并发（原因：同线程不支持并发 interrupt，混进 gather 会吞掉兄弟中断）。

### 2.2 审批门 approval_gate

[approval.py:21-84](src/runtime/app/tools/approval.py#L21-L84) 是"Agent 挂起审批 + 处理审批"的心脏：

| 阶段 | 代码 | 说明 |
|---|---|---|
| 派生 request_id | :42 | `approval:{tool_call_id}`，确定性 |
| 通知 backend | :45-46 → `_submit_approval_request`(:100-123) | POST 生成审批卡，失败进 outbox |
| 挂起 | :57-66 `interrupt({...})` | 抛 GraphInterrupt，图停住 |
| 恢复处理 | :68-84 | resume 时 `interrupt()` 返回决策 → approve 执行工具 / reject 返回拒绝文本 |

### 2.3 request_id 如何写入

- 由 `approval:{tool_call_id}` 派生（[approval.py:42](src/runtime/app/tools/approval.py#L42)），`tool_call_id` 来自 LLM 的 AI 消息 `tool_calls[i]["id"]`（**LLM 供应商生成**，非 runtime）。
- 塞进 `interrupt()` 载荷 dict → 被 langgraph 包进 `Interrupt.value`。
- 三处写入共用同一 id：backend 审批表、runtime 恢复注册表 `interrupt:{claw_id}:{request_id}`（[main.py:250-251](src/runtime/app/main.py#L250-L251)）、checkpoint。
- 为什么确定性派生：resume 重跑生成同一 id → backend 幂等、回调定位准确（修复了早期 uuid 派生导致重复审批记录的问题）。

### 2.4 挂起透传：executor_node → main.py

executor_node 捕获子图 `__interrupt__` 后**在父层重新挂起**（[graph.py:255-257](src/runtime/app/graph.py#L255-L257)）：

```python
if "__interrupt__" in chunk:
    for itr in chunk["__interrupt__"]:
        interrupt(itr.value)   # 子图挂起 → 主图层面重新挂起（同一 payload/request_id）
```

主图 `_sse_stream` 读到 `__interrupt__`，注册 config + 推 SSE（[main.py:247-254](src/runtime/app/main.py#L247-L254)）。

### 2.5 前端 [DONE] → backend → 回调恢复

`[DONE]` 是 SSE 流**无条件终止标记**（挂起与否都发，[main.py:354-361](src/runtime/app/main.py#L354-L361)）。之后审批处理分三段：

```
前端点"允许/拒绝"
  → backend ToolApprovalServiceImpl.handleByRequestId 落库(163行)
  → RuntimeCallbackServiceImpl POST /approvals/callback（异步重试 3 次）
  → runtime main.py:370 按 request_id 找 config（内存→Redis GETDEL→done 幂等）
  → astream(Command(resume=decision)) 图重放恢复(main.py:392)
  → approval_gate 的 interrupt() 返回决策(approval.py:57)
  → approve: 执行工具 / reject: 返回拒绝文本
```

关键入口：
- runtime 恢复：[/approvals/callback main.py:370-417](src/runtime/app/main.py#L370-L417)
- backend 触发：ToolApprovalServiceImpl.java:163 / ToolApprovalBatchServiceImpl.java:115
- 决策生效：approval.py:57-84

---

## 3. 子 Agent 调用与审批透传

### 3.1 两种编排方式

| | call_agent | spawn_subagent |
|---|---|---|
| 方式 | 单点子 Agent 调用 | 并行派发多个子任务 |
| 审批 | 子 Agent 内部敏感工具**逐个透传**（走完整单条审批） | 子任务敏感操作**聚合为一张批量审批卡** |
| `_approval_child` | 不置（子审批走完整流程） | 置 True（子审批跳过单独提交，由容器聚合） |
| 入口 | [call_agent.py:139-184](src/runtime/app/tools/agent/call_agent.py#L139-L184) | [spawn_subagent.py:156-191](src/runtime/app/tools/agent/spawn_subagent.py#L156-L191) |

两者都设置子线程 `thread_id = 父::child` 隔离消息与审批。

### 3.2 子 Agent 审批透传的 6 层链（_suppress_interrupt 贯穿始终）

```
子图 interrupt
  → 子图 astream 产出 __interrupt__ chunk   → call_agent._run_child break 截住（call_agent.py:200）
  → call_agent 父线程 interrupt(载荷) 重新抛（call_agent.py:156）
  → 父图 astream 产出 __interrupt__ chunk   → executor_node break 截住（graph.py:255-257）
  → executor_node interrupt(载荷) 重新抛
  → 主图 astream 抑制成 chunk（is_nested=False）→ main.py _sse_stream 读 chunk（main.py:247-254）
```

本质：**把挂起点逐层上移，直到到达能抑制异常的顶层图**。
- 子图/父图嵌套（异常会逃逸）→ 用 `astream` 读 chunk + `break` 截住，再手动上移；
- 主图顶层（`_suppress_interrupt` 自动抑制）→ 只有 chunk、无异常。

### 3.3 为什么用 astream 而不是 ainvoke

嵌套子图 `ainvoke` 会把 GraphInterrupt 作为真异常抛出（`_suppress_interrupt` 不抑制）；`astream` 则**先**产出 `__interrupt__` chunk，消费方 `break` 截住即可避开紧随的异常。**"chunk 先出、异常随后也传播"**——不是"异常根本不存在"。

### 3.4 跨 resume 会话注册表

`_tool_call_id` 在 resume 重跑时稳定（AI 消息从 checkpoint 重放），call_agent/spawn 用它定位跨 resume 的会话进度：
- call_agent：`_CALL_AGENT_SESSIONS`（[call_agent.py:30-72](src/runtime/app/tools/agent/call_agent.py#L30-L72)）
- spawn：`_SPAWN_SESSIONS` + `pending_batch`（[spawn_subagent.py:47-50](src/runtime/app/tools/agent/spawn_subagent.py#L47-L50)、[:160-170](src/runtime/app/tools/agent/spawn_subagent.py#L160-L170)）

---

## 4. SSE 流式与并发模型

### 4.1 async + await + 事件循环

- `async def chat_completions` 只是"允许 await"，不是自动非阻塞；**每条 I/O 都要显式 await**，事件循环才会在等待期间处理别的请求。
- `await aget_state` 是"异步读一次 Redis checkpoint"——不 await 拿到的是协程对象，同步 get_state 会阻塞整个事件循环。

### 4.2 事件循环与多会话并发

- 事件循环属于**单个 runtime Pod = 单个 Claw = 单个用户**（一 Claw 一 Pod，见 FabricClawProvisioner）。
- "多会话并发" = 同一 Claw 下多个 `conversation_id`（不同 thread_id）在同一事件循环上交错执行。
- 同会话内部由 `_thread_locks` 串行（[main.py:131-146](src/runtime/app/main.py#L131-L146)），避免聊天 run 与审批恢复 run 并发写同一 checkpoint。

### 4.3 astream 的 chunk 内部结构

`stream_mode=["messages", "updates", "custom"]` 下，`(mode, chunk)`：

| mode | chunk 结构 | 出现时机 |
|---|---|---|
| `messages` | `(AIMessageChunk, 元数据dict)`；元数据含 `langgraph_node`/`langgraph_step`/`langgraph_path`/… | 每次 LLM 增量输出 |
| `updates` | `{节点名: 状态写入}`；挂起时含 `__interrupt__: tuple[Interrupt]`（`Interrupt.value` = 载荷 dict，`.id` = 命名空间哈希） | 每个超步结束后 |
| `custom` | `{"type": "trace_event", "event": {...}}`（emit_event 经 writer 推的） | 每发一条过程事件 |

### 4.4 async 生成器与 interrupted 标志

`gen()` 与 `_sse_stream` 构成"三层逐级拉取"的惰性 SSE 管道：

```
FastAPI 迭代 gen()
  └─ async for sse in _sse_stream(...)        # gen 每拉一次
       └─ async for mode, chunk in claw_graph.astream(...)
```

`interrupted` 是 `list[bool]`（跨生成器传标志位的惯用写法，可变引用类型）：
- `_sse_stream` 见 `__interrupt__` 就 `append(True)`（[main.py:253](src/runtime/app/main.py#L253)）；
- `gen()` 的 `finally` 里 `if not interrupted` 决定是否 `purge_thread_cache`（[main.py:354-358](src/runtime/app/main.py#L354-L358)）；
- 只 append 不 pop——每次请求新建局部 list，用完即被 GC。

---

## 5. 幂等与去重的工程细节

### 5.1 工具结果缓存（LRU）

`_TOOL_RESULT_CACHE`（[registry.py:30-61](src/runtime/app/tools/registry.py#L30-L61)）：resume 整节点重跑时，已完成工具再次进入 `execute_tool_call`，命中缓存直接回放、不真执行（避免敏感/有副作用工具二次执行）。

生命周期三阶段：
1. **写入**：工具真实执行后入缓存（[registry.py:159-160](src/runtime/app/tools/registry.py#L159-L160)）；
2. **回放**：resume 命中 → `pop` + 重新插入（**move-to-end，LRU 保鲜**）→ 返回结果不执行（[registry.py:113-119](src/runtime/app/tools/registry.py#L113-L119)）；
3. **回收**：run 无中断完成后 `purge_thread_cache` 清空（[registry.py:54-61](src/runtime/app/tools/registry.py#L54-L61)）。

**为什么命中不能即删**：多轮审批时同一工具会被多次回放（如 `write_file → rm → 挂起 → resume → write_file 又回放`），删了下一轮就没得回放。

**LRU = Least Recently Used（最近最少使用）**，dict 保序实现：新写入/命中 move-to-end（队尾=最近使用），淘汰 `pop(next(iter(...)))`（队头=最久未用）。

### 5.2 uuid5 确定性 + seen 集

```python
event_id = str(uuid.uuid5(uuid.NAMESPACE_URL, dedup_key)) if dedup_key else str(uuid.uuid4())
```
- `uuid5(NAMESPACE_URL, dedup_key)` 是**确定性哈希**：同 dedup_key → 同 event_id → 重放命中 Redis SADD 判重集跳过落盘。
- `seen`（`trace:seen:{claw}:{conv}`）= "已见过的"判重集合，`SADD` 返回 1（首次）/ 0（重放）。
- 无 dedup_key → `uuid4()` 随机（turn 边界等天然唯一事件）。

### 5.3 emit_event 什么情况不带 dedup_key

全库只有 **`chat_start`（main.py:335）** 和 **`chat_end`（main.py:360）** 不带——它们在**请求处理器里、图节点之外**发射，不在 LangGraph 节点重跑范围内，天然每轮一次、不会重复。其余（tool/审批/子 Agent 事件）都在图节点内部、会被 resume 重跑，必须带 dedup_key。

### 5.4 同套幂等思路的另一处

`interrupt:done:{claw_id}:{request_id}`（[main.py:76](src/runtime/app/main.py#L76)）——"已处理过"标记，重复回调 200 静默成功，与 `seen`（已见过）同理，本质都是幂等。

---

## 6. 关键认知纠偏

| 常见误区 | 实际 |
|---|---|
| `tool_specs` 只用于敏感判定 | 三用：**① bind_tools 让 LLM 能调工具（核心）② 子图缓存签名（工具集变则重编译）③ 执行分派（敏感判定+审批 tool_id+工具类型）** |
| `tool_call_id` 是 runtime 生成的 | **LLM 供应商生成**（`call_00_...` / `toolu_...`），runtime 只读 `AIMessage.tool_calls[i]["id"]` 并当稳定标识符用 |
| 用 `ToolNode(tools)` 执行工具 | 本项目用**自定义 `_tool_node` + `tool_fn.ainvoke`**，因为需要审批门、重放缓存、trace 事件、interrupt 串行、id 注入、错误兜底等前后钩子 |
| 重跑的是 `approval_gate` | **重跑的是图节点 `_tool_node`**（函数不是节点）；`_tool_node` 重跑 = 该节点所有工具调用重跑，已完成靠缓存回放 |
| 嵌套子图 `astream` 中断"不会抛异常" | "chunk 先出、**异常随后也传播**"——靠 `break` 截住；只有顶层主图才完全抑制 |
| `yield` 相当于 return | return 终止，yield 暂停保留局部状态，下次从 yield 后继续；`async + yield` = 异步生成器 |

---

## 7. 本次修复背景：call_agent 子图审批不透传前端

**现象**：Agent 调子 Agent，子 Agent 执行敏感工具时，前端收不到审批，反而返回 `调用 Agent 2 失败：(Interrupt(value={'type': 'tool_approval', ...}, id=...),)`。

**根因三重叠加**：
1. call_agent 用 `subgraph.ainvoke()` 跑子 Agent → 嵌套模式 `_suppress_interrupt` 不抑制 → GraphInterrupt 作为真异常逃逸出 ainvoke；
2. call_agent 的 `except Exception` 把 GraphInterrupt 吞成错误文本；
3. `_approval_child: True` 让子审批跳过 backend 提交和事件 → 即使透传也没有审批卡。

**修复**（commit `85f8114`）：
- 改用 `astream` 捕获子图 `__interrupt__`，在父线程 `interrupt()` 透传（[call_agent.py:149-164](src/runtime/app/tools/agent/call_agent.py#L149-L164)）；
- 移除 `_approval_child`，子 Agent 内部敏感工具走完整审批；
- 新增跨 resume 会话注册表 + `Command(resume)` 恢复子图；
- 子线程 id 确定性派生 `call:{tool_call_id}`；
- `except GraphBubbleUp: raise` 不再吞中断（[call_agent.py:173](src/runtime/app/tools/agent/call_agent.py#L173)）；
- 顺带修复 spawn_subagent 同类 resume bug（`pending_batch` 先取决策再推进）。

**部署提醒**：Claw Pod 镜像固化在 provision 时，改 runtime 代码需 `kubectl -n claw-{id} set image deployment/claw-{id} runtime=<新SHA>` 重建；deploy.yml 只更新 ConfigMap + 滚动三个常驻 Deployment，不重建已存在的 Claw Deployment。

---

## 附：建议的下一步阅读顺序

1. [approval.py](src/runtime/app/tools/approval.py)（审批门本体，注释最透）
2. [graph.py](src/runtime/app/graph.py) 的 `_tool_node` + `executor_node`
3. [call_agent.py](src/runtime/app/tools/agent/call_agent.py)（单子 Agent 透传）
4. [spawn_subagent.py](src/runtime/app/tools/agent/spawn_subagent.py)（批量聚合，对照 call_agent 看差异）
5. 返回 [main.py](src/runtime/app/main.py) 验证两端（_sse_stream / /approvals/callback）如何衔接
