# 多 Agent 对话展示与内部 Orchestrator 补齐实现计划

> **For agentic workers:** REQUIRED REFERENCE: 先阅读 `ARCHITECTURE.md`、`docs/s00_project_notes/0002/pyclaw-saas-multi-agent-gap-notes-2026-07-19.md`、`docs/superpowers/plans/2026-07-18-agent-marketplace-orchestration.md`。实现时按本文任务逐项推进，每个任务完成后更新 checkbox，并优先补测试。不得绕过 Spring Orchestrator 直接让 FastAPI 调普通用户 run 接口。

**Goal:** 补齐当前多 Agent 对话的剩余缺口：`@Agent` 选择、内部 Orchestrator API、FastAPI 服务间认证、`request_agent_install` 闭环、嵌套 `call_agent` 上下文拼装、审批恢复、以及多 Agent 消息折叠展示模型。

**Architecture:** Spring Backend 负责 SaaS 业务状态、权限、Conversation、Agent 间调用、安装审批和消息展示模型；OpenClaw FastAPI Runtime 只执行一个已确定 Agent turn；前端负责 `@Agent` 选择、主时间线和折叠详情展示。

**Source Of Truth:**
- `ARCHITECTURE.md`
- `docs/s00_project_notes/0002/pyclaw-saas-multi-agent-gap-notes-2026-07-19.md`

**Tech Stack:** Spring Boot 3 + JPA + MySQL + Redis；OpenClaw FastAPI + Python；Vue 3 + Vite。

## Global Constraints

- `claw_agents.id` 必须作为稳定 `agent_instance_id`。
- Runtime `session_id` 必须使用 `agent-memory:{conversation_id}:{agent_instance_id}`。
- 不得使用 `roleKey` 作为记忆隔离主键。
- FastAPI 不得直接安装 Agent，不得直接访问市场，不得决定本轮回答者。
- FastAPI 的 `call_agent` / `request_agent_install` 必须调用 Spring `/internal/orchestrator/...`。
- FastAPI -> Spring 可复用 `PYCLAW_API_TOKEN` 的认证机制，但不能把内部 token 当作用户登录态。
- `call_agent` 不传调用者 Agent 的私有 session，只传 `message`，由 Spring 拼装 `sharedContext`。
- B 的最终回复默认不作为主 Conversation Thread 消息展示，只作为 A 的 `call_agent` 折叠详情。
- B 触发工具审批时，审批卡片主语必须是 B，A 只作为调用来源。
- Agent 安装审批通过后生成的 Agent Instance 应保留；后续工具审批恢复失败不得回滚安装。

---

### Task 1: 扩展 Conversation Message 展示模型

**Files:**
- Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/conversation/ConversationMessageEntity.java`
- Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/conversation/ConversationService.java`
- Modify/Add: conversation response DTO / tests

**Produces:**
- `conversation_messages` 支持主时间线和折叠详情。

- [ ] **Step 1: 新增字段**

在 `ConversationMessageEntity` 增加：

```text
messageType
parentMessageId
metadataJson
visibleInThread
sortOrder
```

默认兼容旧数据：

```text
messageType = role=user ? USER_MESSAGE : AGENT_MESSAGE
visibleInThread = true
sortOrder = 0
```

- [ ] **Step 2: 定义 messageType 常量或 enum**

至少支持：

```text
USER_MESSAGE
AGENT_MESSAGE
SYSTEM_EVENT
AGENT_CALL_EVENT
TOOL_RESULT_DETAIL
TOOL_APPROVAL_CARD
```

- [ ] **Step 3: 扩展保存接口**

`ConversationService.saveMessage(...)` 需要支持新字段；保留旧签名或提供兼容重载，避免一次性改坏现有调用。

- [ ] **Step 4: 扩展消息查询响应**

前端拉取 conversation messages 时必须返回：

```text
id, conversationId, parentMessageId, messageType, visibleInThread,
sortOrder, role, agentInstanceId, agentKey, roleKey, content,
metadataJson, createdAt
```

- [ ] **Step 5: 补测试**

覆盖：

```text
旧消息默认 visibleInThread=true
按 conversationId + ownerUserId 查询当前会话消息
parentMessageId 可保存折叠子事件
metadataJson 可保存 Agent 调用详情
```

---

### Task 2: Spring 内部 Orchestrator API 与服务认证

**Files:**
- Add/Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/orchestrator/*`
- Add/Modify: Spring security config / properties as needed
- Modify: `helm/pyclaw/values.yaml` and examples only if required

**Produces:**
- FastAPI 可通过内部服务 token 调用 Spring Orchestrator。

- [ ] **Step 1: 新增内部入口**

新增接口：

```text
POST /internal/orchestrator/agents/discover
POST /internal/orchestrator/agents/install-requests
POST /internal/orchestrator/agents/call
```

不要删除已有 `/api/orchestrator/...`，用户侧或兼容路径可保留。

- [ ] **Step 2: 实现内部 token 校验**

复用 `PYCLAW_API_TOKEN` 的认证机制或映射到 Spring 配置项。

要求：

```text
Authorization: Bearer ${PYCLAW_API_TOKEN}
```

内部接口校验通过后，不把该 token 当作用户 JWT。

- [ ] **Step 3: 业务授权校验**

内部接口必须根据请求体校验：

```text
clawId 存在
callingAgentInstanceId 属于 clawId
targetAgentInstanceId 或 targetRoleKey 属于同一 clawId
目标 Agent enabled=true
conversationId 属于 clawId
Agent Package Version 可见且 published
```

- [ ] **Step 4: 审计**

写入 audit log：

```text
agent.discover
agent_install.request
agent.call
```

审计中记录：

```text
clawId
conversationId
callingAgentInstanceId
targetAgentInstanceId
packageVersionId
status
```

- [ ] **Step 5: 补测试**

覆盖：

```text
无内部 token 拒绝
错误 token 拒绝
正确 token 但 claw/agent 不匹配拒绝
disabled target agent 拒绝
内部接口不依赖用户 JWT principal
```

---

### Task 3: FastAPI Orchestration 工具改造

**Files:**
- Modify: `openclaw/tools/orchestration.py`
- Modify: `openclaw/tools/catalog.py`
- Modify: `openclaw/api.py` / runtime metadata assembly if needed
- Add/Modify: Python tests

**Produces:**
- `discover_agents` / `request_agent_install` / `call_agent` 自动使用运行时上下文和内部 token。

- [ ] **Step 1: 调 Spring 内部入口**

把工具 executor 调用路径改为：

```text
/internal/orchestrator/agents/discover
/internal/orchestrator/agents/install-requests
/internal/orchestrator/agents/call
```

- [ ] **Step 2: 加内部 Authorization**

从环境变量读取 token：

```text
PYCLAW_API_TOKEN
```

请求头：

```text
Authorization: Bearer ${PYCLAW_API_TOKEN}
```

- [ ] **Step 3: 从 ToolExecutionContext 注入运行时字段**

不要让模型手写 `claw_id`、`calling_agent_instance_id`、`conversation_id`。

从 `context.metadata` 自动读取：

```text
claw_id
conversation_id
agent_instance_id
role_key
agent_key
owner_user_id
```

工具 schema 中移除或弱化这些上下文字段，保留模型只需要决定的字段：

```text
discover_agents(query)
request_agent_install(package_version_id, reason)
call_agent(target_agent_instance_id | target_role_key, message)
```

- [ ] **Step 4: 返回结构化结果**

`call_agent` 返回：

```json
{
  "status": "COMPLETED",
  "agent_instance_id": "...",
  "role_key": "...",
  "text": "...",
  "message_id": "..."
}
```

- [ ] **Step 5: 补测试**

覆盖：

```text
工具请求带内部 Authorization
工具自动注入 clawId/conversationId/callingAgentInstanceId
模型传入伪造 claw_id 不生效
call_agent 返回文本可作为 ToolResult 注入 A 的 session
```

---

### Task 4: Agent 安装审批闭环

**Files:**
- Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/agentinstall/*`
- Modify: approval controller/service/UI as needed
- Add/Modify: tests

**Produces:**
- `request_agent_install` 创建审批后，用户批准才安装 Agent Instance。

- [ ] **Step 1: 创建安装审批**

内部 `request_agent_install` 只创建：

```text
agent_install_approvals(status=PENDING)
```

不得立即创建 `claw_agents`。

- [ ] **Step 2: 审批通过执行安装**

用户批准后：

```text
校验 approval owner/claw/status
调用 AgentInstallService.install(...)
创建 claw_agents Agent Instance
approval status -> CONSUMED
resolvedAt = now
写 audit log
```

- [ ] **Step 3: 审批拒绝**

用户拒绝后：

```text
approval status -> REJECTED
不得创建 Agent Instance
写 audit log
```

- [ ] **Step 4: 安装后保留规则**

安装成功后，后续 B 的工具审批恢复失败不得删除该 Agent Instance。

- [ ] **Step 5: 补测试**

覆盖：

```text
审批通过前不创建 Agent Instance
审批通过后创建 Agent Instance
审批拒绝不创建 Agent Instance
重复审批不可重复安装
工具审批失败不回滚已安装 Agent
```

---

### Task 5: `call_agent` 嵌套调用与 sharedContext

**Files:**
- Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/orchestrator/ConversationOrchestratorService.java`
- Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/clawchat/ClawChatService.java`
- Modify: conversation services / DTOs
- Add/Modify: tests

**Produces:**
- Agent A 调用 Agent B 时，B 使用独立 session，并收到 Spring 组装的任务包装 prompt。

- [ ] **Step 1: 构造 sharedContext**

Spring 根据 `conversationId` 读取当前 Conversation Thread 的公开消息，拼装：

```text
当前 Claw 信息
当前 Conversation Thread 标识和标题
调用来源 Agent
最近 N 条公开消息
```

默认 N 可取 8 或 12。

- [ ] **Step 2: 构造 B 的 prompt**

格式：

```text
你正在被另一个 Agent 调用。

调用来源：
- Agent: {callerDisplayName}
- roleKey: {callerRoleKey}

上游 Agent 委托给你的任务：
{message}

共享上下文：
{sharedContext}
```

- [ ] **Step 3: 使用 B 的私有 session**

调用 B 时必须使用：

```text
session_id = agent-memory:{conversation_id}:{agent_b_instance_id}
```

- [ ] **Step 4: 保存折叠事件**

当 A 调用 B 并得到 B 的最终回复后，保存：

```text
AGENT_CALL_EVENT(parent=A最终消息或当前turn占位)
TOOL_RESULT_DETAIL(parent=AGENT_CALL_EVENT, agentInstanceId=B, visibleInThread=false)
```

B 的最终回复不作为 `visibleInThread=true` 的主消息。

- [ ] **Step 5: 补测试**

覆盖：

```text
A/B runtime session_id 不同
sharedContext 不包含 A 私有 session
B 最终回复作为 TOOL_RESULT_DETAIL 保存
B 回复不作为主时间线消息展示
Agent call event metadataJson 包含 targetAgentInstanceId/targetRoleKey/status
```

---

### Task 6: 工具审批与嵌套调用链上下文

**Files:**
- Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/approval/*`
- Modify: `openclaw/tools/approval*`
- Modify: `openclaw/api.py`
- Add/Modify: tests

**Produces:**
- B 触发审批时，审批卡片主语是 B，并能显示来源 A。

- [ ] **Step 1: 扩展审批上下文**

审批 pending state 和 Spring 表记录需要能表达：

```text
executingAgentInstanceId
executingRoleKey
callingAgentInstanceId
callingRoleKey
conversationId
```

如果暂不加新列，可先放入 `metadataJson`；但最终响应给前端必须结构化。

- [ ] **Step 2: 审批卡片文案数据**

审批响应必须能支持：

```text
Agent B 请求执行 write_file
来源：由 Agent A 调用
```

- [ ] **Step 3: 恢复正确 session**

如果 B 触发审批，批准后恢复：

```text
agent-memory:{conversation_id}:{agent_b_instance_id}
```

不得恢复 A 的 session。

- [ ] **Step 4: 对话流内审批恢复**

前端可在当前对话流里点击批准/拒绝；审批中心可保留，但不是唯一入口。

- [ ] **Step 5: 补测试**

覆盖：

```text
B 的工具审批记录绑定 B 的 agentInstanceId
审批响应显示调用来源 A
批准后恢复 B session
拒绝后 A 的 call_agent 得到拒绝结果
resume 失败不删除 Redis pending state
```

---

### Task 7: 前端 `@Agent` 与多 Agent 折叠展示

**Files:**
- Modify/Add: `pyclaw-web/src/views/*`
- Modify/Add: `pyclaw-web/src/components/*`
- Modify/Add: `pyclaw-web/src/api/*`

**Produces:**
- 用户可在同一对话中 `@Agent` 指定回答者，并查看 Agent 调用详情。

- [ ] **Step 1: 对话页加载 Claw Agent 列表**

展示：

```text
displayName
roleKey
agentInstanceId
enabled
defaultRole
```

- [ ] **Step 2: 输入框支持 `@Agent`**

输入 `@` 弹出 Agent picker；发送时提交：

```text
agentInstanceId 或 roleKey
conversationId
prompt
```

- [ ] **Step 3: 主时间线展示**

只默认展示：

```text
visibleInThread=true
```

- [ ] **Step 4: 折叠详情展示**

对主消息下的子记录按 `parentMessageId + sortOrder` 展示：

```text
AGENT_CALL_EVENT
TOOL_RESULT_DETAIL
TOOL_APPROVAL_CARD
SYSTEM_EVENT
```

目标 UI：

```text
用户：@A 帮我排查
A：我检查了一下，结论如下...
  [调用了 B：后端助手] 可展开
    B 的结果：问题在 Redis 配置
```

- [ ] **Step 5: 对话流内审批卡**

审批卡文案：

```text
Agent B 请求执行 {toolName}
来源：由 Agent A 调用
```

按钮：

```text
批准
拒绝
```

- [ ] **Step 6: 构建验证**

运行：

```bash
cd pyclaw-web
npm run build
```

---

### Task 8: 端到端验收

- [ ] **Step 1: @Agent 选择**

同一 Conversation Thread 中，用户分别 `@A` 和 `@B`，确认两者都能回答。

- [ ] **Step 2: 记忆隔离**

确认 OpenClaw 日志中：

```text
agent-memory:{conversation_id}:{agent_a_instance_id}
agent-memory:{conversation_id}:{agent_b_instance_id}
```

互不相同。

- [ ] **Step 3: Agent A 调用 Agent B**

A 使用 `call_agent` 调 B；B 的最终回复不作为主消息出现，而是出现在 A 消息的折叠详情里。

- [ ] **Step 4: B 触发审批**

B 触发 `write_file` 或 `apply_patch` 审批，前端卡片显示：

```text
Agent B 请求执行 ...
来源：由 Agent A 调用
```

- [ ] **Step 5: 审批恢复**

批准后恢复 B 的 session，B 完成后结果返回给 A。

- [ ] **Step 6: Agent 安装审批**

A 调用 `discover_agents` 和 `request_agent_install`，审批通过前不得创建 Agent Instance；审批通过后创建并保留。

- [ ] **Step 7: 测试命令**

运行：

```bash
python -m unittest tests.test_tool_catalog tests.test_api tests.test_tool_approval tests.test_agent_pending_approval
cd spring-backend && mvn -DskipTests compile
cd spring-backend && mvn "-Dnet.bytebuddy.experimental=true" test
cd pyclaw-web && npm run build
```
