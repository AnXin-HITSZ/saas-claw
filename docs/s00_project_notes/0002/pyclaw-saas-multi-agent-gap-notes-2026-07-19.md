# PyClaw SaaS 多 Agent 长期缺口记录

日期：2026-07-19

## 1. 目标

本文记录当前 SaaS 多 Agent 方案里需要继续补齐的四个关键点：

1. 前端如何让用户在同一对话中选择/切换 Agent。
2. `call_agent` 工具从 FastAPI 调 Spring 时，为什么没有携带用户认证上下文。
3. 当前项目里有哪些数据库表。
4. 嵌套 Agent 调用遇到审批时，需要和产品核对哪些流程。

## 2. 前端入口：用 `@Agent` 选择本轮回答者

你希望的交互是：

- 在当前对话页展示该 Claw 已包含的 Agent 列表。
- 用户在输入框中输入 `@`，从候选 Agent 中点选一个。
- 本轮消息由被点中的 Agent 回答。

这个方向是对的，且比单独再做一个“切换回复者”按钮更自然。

### 建议的产品语义

```text
@Agent
  表示本轮把这条消息交给哪个 Agent 处理。

默认 Agent
  未显式 @ 时使用的兜底 Agent。

会话上下文
  同一 Conversation Thread 里可以出现多个 Agent 的消息。
```

### 推荐实现方式

1. 页面顶部展示当前 Claw 已安装 Agent 列表。
2. 输入框支持 `@` 触发 Agent mention picker。
3. 发送消息时，把选中的 `agentInstanceId` 或 `roleKey` 一并发给 Spring。
4. Spring 侧通过 Orchestrator 解析本轮实际 Agent。
5. OpenClaw FastAPI 只执行这一轮已选定的 Agent，不负责路由决策。

### 需要注意

- 前端展示的 Agent 名称应来自 Claw 内的 `roleKey/displayName`，而不是底层 package key。

## 3. `call_agent` 为什么没有携带用户认证上下文

当前 `openclaw/tools/orchestration.py` 里的工具执行器是这样工作的：

```text
Agent A
  -> FastAPI call_agent 工具
  -> Spring /internal/orchestrator/agents/call
  -> Spring 校验 callingAgentInstanceId / targetAgentInstanceId / clawId / enabled / policy
  -> Spring 内部调用 ClawChatService.run(...)
  -> 返回 Agent B 的回复
```

### 关键原因

`call_agent` 工具执行时，FastAPI 侧拿到的是“Agent 运行上下文”，不是“浏览器登录态”。

也就是说：

- 前端用户的 JWT / Session 不会自动出现在 FastAPI 工具执行器里。
- FastAPI 工具执行器当前也没有把原始用户 Authorization 头转发给 Spring。
- 所以 Spring 看到的只是一个普通后端 HTTP 请求，而不是“哪个用户授权了这次 Agent 间调用”。

### 这意味着什么

如果 Spring 的 `/api/orchestrator/agents/call` 使用 `@PreAuthorize` 直接要求用户权限：

```text
hasAuthority('agent:run')
```

那么 `call_agent` 这条链路就会缺少认证主体。

### 长期正确边界

建议把权限判断拆成两层：

```text
1. 外部用户请求
   由 Spring 的登录态 / JWT 负责。

2. Agent 内部工具调用
   由 Spring 识别成“来自某个已认证的 Agent Runtime”。
```

更具体地说，Spring 和 FastAPI 之间应使用内部服务认证，而不是复用用户浏览器认证：

```text
FastAPI -> Spring
  带内部服务 token
  再显式传入 clawId / callingAgentInstanceId / targetAgentInstanceId / conversationId / caller metadata
```

### 结论

现在 `call_agent` 没带用户认证上下文，不是因为漏写了一行 header，而是因为这条链路本来就不在用户浏览器认证域里。  
它属于“Agent Runtime -> Spring Orchestrator”的服务间调用，需要单独设计服务认证和审计语义。

### `request_agent_install` 当前状态与缺口

当前已经存在 Agent A 请求安装 Agent B 的工具雏形：

```text
FastAPI 工具：
request_agent_install

Spring 入口：
POST /api/orchestrator/agents/install-requests

Spring 服务：
ConversationOrchestratorService.createInstallRequest(...)

写入表：
agent_install_approvals
```

该工具当前可以表达：

```text
Agent A 请求把某个 Agent Package Version 安装到当前 Claw。
```

但当前仍是半成品，不能算完整闭环。待完成缺口如下：

1. FastAPI 调 Spring 仍走 `/api/orchestrator/...`，应改为内部入口 `/internal/orchestrator/...`。
2. FastAPI -> Spring 需要使用内部服务认证，可复用 `PYCLAW_API_TOKEN` 的认证机制。
3. Spring 不能把内部服务 token 当作用户登录态，必须根据请求体中的 `clawId` / `callingAgentInstanceId` / `conversationId` 做业务校验。
4. `request_agent_install` 当前需要模型传 `claw_id` 和 `requesting_agent_instance_id`，长期应由 FastAPI 从运行时上下文自动注入，避免模型乱填。
5. 当前链路能创建 `agent_install_approvals`，但“用户审批通过后自动安装 Agent Instance”的闭环仍需补齐和验证。
6. Agent 安装审批与工具执行审批是两件事；安装成功后生成的 Agent Instance 应保留，后续工具审批恢复失败不应回滚安装。
7. 需要统一前端审批入口，让 `agent_install` 审批能在对话页或审批中心被处理。

## 4. 当前数据库表

按当前 Spring JPA 实体和已有迁移逻辑，现有表如下。

### 用户与权限

- `users`
- `api_tokens`
- `user_secrets`
- `audit_logs`

### Claw 与对话

- `claws`
- `claw_agents`
- `conversations`
- `conversation_messages`

### Agent 发布与安装

- `agents`
- `agent_tool_policies`
- `agent_packages`
- `agent_package_versions`
- `agent_install_approvals`

### 工具审批

- `tool_approval_requests`

### Provider / Channel / 路由 / 用量

- `provider_configs`
- `channel_configs`
- `route_bindings`
- `usage_records`

### 说明

- `claw_agents` 当前已经在向“Agent Instance”演进。
- `agents` 仍然表示基础 Agent 配置或本地 Agent 定义。
- `tool_approval_requests` 是工具审批主表。
- `agent_install_approvals` 是 Agent 安装审批表。

## 5. 嵌套 Agent 调用与审批恢复

这里的核心问题是：

```text
Agent A 调用 Agent B
Agent B 再触发工具审批
审批通过后，应该恢复哪一段上下文？
```

### 审批卡片展示

审批卡片的主语应该是实际触发工具调用的 Agent。

例如：

```text
Agent A
  -> call_agent
  -> Agent B
  -> Agent B 触发 write_file 审批
```

审批卡片不应写成：

```text
Agent A 请求执行 write_file
```

而应写成：

```text
Agent B 请求执行 write_file
来源：由 Agent A 调用
对话：当前对话
```

原因是：

- 真正决定调用工具的是 Agent B。
- 工具参数、工具风险、工具恢复上下文都属于 Agent B。
- Agent A 只是上游调用者，需要作为调用链来源展示和审计。

### Agent A / Agent B 的记忆存储

嵌套调用不会让 A 和 B 共用记忆。即使 B 是由 A 调用的，B 仍然使用自己的私有 Runtime session。

假设：

```text
conversation_id = conv-1
Agent A instance = agent-a
Agent B instance = agent-b
```

则运行时记忆应分别存储为：

```text
A 的私有记忆：
agent-memory:conv-1:agent-a

B 的私有记忆：
agent-memory:conv-1:agent-b
```

调用流程：

```text
用户 -> A
  Spring 使用 session_id = agent-memory:conv-1:agent-a 运行 A

A 调用 B
  FastAPI call_agent 工具调用 Spring Orchestrator
  Spring 使用 session_id = agent-memory:conv-1:agent-b 运行 B

B 触发工具审批
  tool_approval_requests 绑定 B 的 session_id 和 B 的 agentInstanceId

用户审批通过
  Spring 恢复 B 的 session_id
  B 继续执行并生成最终回复

B 完成后
  Orchestrator 把 B 的最终回复作为 call_agent 的工具结果返回给 A
  A 在自己的 session 里收到 call_agent 的工具结果
  A 再决定是否继续发言
```

核心原则：

```text
审批恢复谁，就恢复谁的私有 session。
```

因此，如果是 B 触发工具审批，审批恢复时必须恢复：

```text
agent-memory:conv-1:agent-b
```

不能恢复 A 的 session。

### B 的结果如何返回给 A

B 调用了很多工具时，不把 B 的完整 session 返回给 A，也不把 B 的工具轨迹全部塞进 A 的记忆。

返回给 A 的应该是 B 本轮运行完成后的最终 assistant message，也就是 B 的最终结论。

结构化返回示例：

```json
{
  "status": "COMPLETED",
  "agentInstanceId": "agent-b",
  "roleKey": "backend",
  "text": "我检查后发现问题在 Redis 连接配置。",
  "messageId": "msg-b-final"
}
```

FastAPI 再把这段结果包装成 A 的 `call_agent` 工具结果：

```text
ToolResult(call_agent):
Agent B 回复：
我检查后发现问题在 Redis 连接配置。
```

所以 A 的私有记忆保存的是：

```text
A 调用了 call_agent
call_agent 返回了 B 的最终结论
A 基于 B 的结论继续思考或回复
```

B 的私有记忆保存的是：

```text
B 收到 A 的请求
B 调用了哪些工具
B 看到的工具结果
B 生成的最终结论
```

可以近似理解为“返回 B 本轮完成后的最终 assistant 消息”，但实现上不要直接取 `session[-1]`。最后一条 session 记录可能是工具结果、审批事件、系统消息或中间事件。应该以 B 的 `/run` 或 `/resume` 返回值里的最终 `text/message` 为准。

### `message` 与 `sharedContext` 如何拼装

`call_agent` 不做复杂的多档上下文策略。长期只保留一个简单规则：

```text
call_agent 只传 message + sharedContext。
不传调用者 Agent 的私有 session。
```

其中：

```text
message
  由调用者 Agent 显式生成。
  表示 Agent A 委托给 Agent B 的具体任务。

sharedContext
  由 Spring Orchestrator 生成。
  表示当前 Conversation Thread 中允许共享给 B 的公开背景。
```

`message` 示例：

```json
{
  "targetRoleKey": "backend",
  "message": "请检查 Spring 审批状态机是否可能导致恢复失败。"
}
```

这个 `message` 是 A 对 B 的任务委托，不是系统自动从 A 的私有上下文里截取。

`sharedContext` 不是数据库里的新对象，也不是 Agent A 的私有记忆。它是 Spring Orchestrator 在调用 B 之前，临时从公开业务状态中拼出来的一段背景信息。

默认拼装内容：

```text
当前 Claw 信息
当前 Conversation Thread 标识和标题
调用来源 Agent
最近 N 条公开消息
```

示例：

```text
调用来源：
- Agent: 运维助手
- roleKey: ops

当前 Claw：
- name: PyClaw 生产环境排查
- clawId: claw-123

当前对话：
- title: 审批恢复失败排查
- conversationId: conv-456

最近公开消息：
1. 用户：审批通过后重复点击出现 APPROVAL 报错。
2. 运维助手：我会检查 Redis pending state 是否被删除。
3. 用户：重启 ECS 是否会导致 Pod 日志丢失？
4. 运维助手：Pod 重建后旧日志默认不可用，需要 --previous 查看。
```

注意：

```text
sharedContext 可以包含当前 Conversation Thread 的公开消息。
sharedContext 不包含 A 的私有 Agent Memory Session。
sharedContext 不包含 A 的完整工具调用轨迹。
sharedContext 不包含 A 的隐藏 system prompt。
sharedContext 不包含审批 pending state。
```

### B 的最终输入如何生成

当 Spring 收到：

```text
Agent A -> call_agent -> Agent B
```

这类内部调用时，Spring 知道它来自：

```text
/internal/orchestrator/agents/call
```

并且请求体里包含：

```json
{
  "callingAgentInstanceId": "agent-a",
  "targetAgentInstanceId": "agent-b",
  "conversationId": "conv-1",
  "message": "请检查审批状态机"
}
```

因此 Spring 可以为 B 生成固定包装提示词。

例如：

```text
你正在被另一个 Agent 调用。

调用来源：
- Agent: 运维助手
- roleKey: ops

上游 Agent 委托给你的任务：
请检查审批状态机。

共享上下文：
{sharedContext}
```

其中：

```text
“你正在被另一个 Agent 调用”
  由 Spring 根据本次调用入口生成。

调用来源
  由 Spring 根据 callingAgentInstanceId 查询得到。

上游 Agent 委托给你的任务
  来自 call_agent 请求体里的 message。

共享上下文
  由 Spring 从当前 Conversation Thread 的公开消息和 Claw 信息中生成。
```

这段最终输入会作为 B 本轮运行的 prompt 传给 FastAPI。B 仍然使用自己的私有 session：

```text
session_id = agent-memory:{conversation_id}:{agent_b_instance_id}
```

### 推荐的长期原则

```text
审批应该绑定到具体 agent_instance_id。
```

不要只绑定到 roleKey，也不要只绑定到 conversation_id。

原因是：

- `roleKey` 可能被改名。
- `conversation_id` 下可能有多个 Agent。
- 审批恢复必须回到准确的 Agent 私有记忆。

### 后续仍需确认的产品规则

1. 是否限制 Agent 嵌套调用深度，例如最多 2 层或 3 层。

先不要限制，先实现一个 MVP 版本。

2. `call_agent` 是否允许跨 Agent 传递全部上下文，还是只传用户消息、共享摘要和显式参数。

已确认：不传全部上下文。`call_agent` 只传调用者 Agent 显式生成的 `message`，Spring Orchestrator 再补充 `sharedContext`。`sharedContext` 只来自当前 Conversation Thread 的公开消息和 Claw 信息，不包含调用者 Agent 的私有 session。

3. B 的最终回复是否默认写入当前 Conversation Thread，还是只作为 A 的工具结果。

B 的最终回复不需要写入当前 Conversation Thread，只作为 A 的工具结果即可。

但要实现如下 UI 效果：
```text
用户：@A 帮我排查
A：我检查了一下，结论如下...
  [调用了 B：后端助手] 可展开
    B 的结果：问题在 Redis 配置
```

4. B 的工具审批是否允许在 UI 上直接从对话流中点击恢复。

允许。

5. 如果 B 是自动发现后引入的临时 Agent，审批恢复失败后是否保留该 Agent。

保留。

## 6. 建议的长期落点

长期上，建议把这四件事拆成三个系统：

```text
Frontend
  负责 @Agent 选择、Agent 列表、审批展示。

Spring Orchestrator
  负责决定本轮哪个 Agent 回答、Agent 间调用、安装审批、恢复审批、审计，并通过 /internal/orchestrator/agents/call 执行 Agent B。

OpenClaw FastAPI Runtime
  只执行已确定的 Agent turn，不负责权限归属和路由。
```

如果你后续确认这套产品语义，我建议下一步直接补：

- 对话页 `@Agent` 选择入口。
- Spring 侧 `call_agent` 的内部服务认证，以及 `/internal/orchestrator/agents/call` 的实现。
- 审批状态里增加“调用链上下文”。
- 对话消息里明确区分 `agentInstanceId` 和 `roleKey`。

## 7. 多 Agent 消息展示模型

当前 `conversation_messages` 已有：

```text
conversationId
ownerUserId
clawId
role
agentInstanceId
agentId
agentKey
roleKey
content
createdAt
```

其中：

```text
conversationId
  区分当前消息属于哪一个 Conversation Thread。

ownerUserId
  区分消息归属用户。

clawId
  区分消息归属 Claw。

role
  区分 user / assistant / system 等基础角色。

agentInstanceId
  如果是 Agent 产生的消息，表示具体哪个 Agent Instance 产生。

createdAt
  用于当前会话内排序。
```

但要实现下面这种 UI：

```text
用户：@A 帮我排查
A：我检查了一下，结论如下...
  [调用了 B：后端助手] 可展开
    B 的结果：问题在 Redis 配置
```

只靠 `role / agentInstanceId / content` 不够。需要扩展 `conversation_messages` 的展示模型。

### 建议新增字段

```text
messageType
parentMessageId
metadataJson
visibleInThread
sortOrder
```

字段含义：

```text
messageType
  区分这条记录是什么类型。
  推荐值：USER_MESSAGE / AGENT_MESSAGE / SYSTEM_EVENT / AGENT_CALL_EVENT / TOOL_RESULT_DETAIL / TOOL_APPROVAL_CARD。

parentMessageId
  表示这条记录挂在哪条主消息或事件下面。
  例如 B 的结果挂在 A 的 agent_call_event 下。

metadataJson
  存结构化展示和审计信息。
  例如 calledAgentInstanceId、calledRoleKey、toolName、approvalId、status、durationMs。

visibleInThread
  是否作为主时间线消息展示。
  false 不代表不可见，而是默认折叠在父消息详情里。

sortOrder
  同一个 parentMessageId 下的子事件排序。
  仅靠 createdAt 通常可用，但 sortOrder 更稳定。
```

### 示例数据结构

```text
msg-1
  messageType = USER_MESSAGE
  role = user
  content = @A 帮我排查
  visibleInThread = true

msg-2
  messageType = AGENT_MESSAGE
  role = assistant
  agentInstanceId = agent-a
  roleKey = ops
  content = 我检查了一下，结论如下...
  visibleInThread = true

msg-3
  messageType = AGENT_CALL_EVENT
  parentMessageId = msg-2
  agentInstanceId = agent-a
  roleKey = ops
  content = 调用了 B：后端助手
  visibleInThread = false
  metadataJson = {"targetAgentInstanceId":"agent-b","targetRoleKey":"backend","status":"COMPLETED"}

msg-4
  messageType = TOOL_RESULT_DETAIL
  parentMessageId = msg-3
  agentInstanceId = agent-b
  roleKey = backend
  content = 问题在 Redis 配置
  visibleInThread = false
  metadataJson = {"source":"call_agent","status":"COMPLETED"}
```

### 展示规则

主对话流只默认展示：

```text
visibleInThread = true
```

折叠详情按 `parentMessageId` 挂载：

```text
AGENT_CALL_EVENT
  -> TOOL_RESULT_DETAIL
  -> TOOL_APPROVAL_CARD
```

B 的最终回复不作为普通主消息写入当前 Conversation Thread，而是作为 A 的 `call_agent` 工具结果详情展示。这样用户能看到 B 参与了，但不会误以为 B 在直接和用户对话。
