# Agent 发布发现与多 Agent 编排实现计划

> **For agentic workers:** REQUIRED REFERENCE: 先阅读 `ARCHITECTURE.md`。实现时按本文任务逐项推进，每个任务完成后更新 checkbox，并优先补测试。不得绕过 `ARCHITECTURE.md` 中的边界约束。

**Goal:** 一步到位实现 SaaS PyClaw 的 Agent Package、Agent Instance、多 Agent Conversation Orchestrator、Agent 私有记忆隔离、运行时发现与审批引入。

**Architecture:** Spring Backend 负责 SaaS 业务状态和编排；OpenClaw FastAPI Runtime 只执行一个确定 Agent；FastAPI 不直接安装 Agent、不直接访问市场、不决定 Claw 内本轮回答者。

**Source Of Truth:** `ARCHITECTURE.md`

**Tech Stack:** Spring Boot 3 + JPA + MySQL + Redis；OpenClaw FastAPI + Python；Vue 3 + Vite。

## Global Constraints

- `claw_agents.id` 必须作为稳定 `agent_instance_id`。
- Runtime `session_id` 必须使用 `agent-memory:{conversation_id}:{agent_instance_id}`。
- 不得使用 `role_key` 作为记忆隔离主键。
- Agent Package Version 发布后不可变，重新发布必须生成新版本。
- Agent 自动发现后不得静默安装，必须创建 `agent_install` 审批。
- `tools_allow` / `tools_also_allow` 不作为普通 SaaS 用户配置项。
- FastAPI 只运行 Spring 指定的 Agent，不做 SaaS 业务路由。

---

### Task 1: 数据库模型与迁移

**Files:**
- Add/Modify: `spring-backend/src/main/java/com/anxin/pyclaw/backend/**`
- Add/Modify: Spring JPA Entity / Repository / migration cleaner as needed

**Produces:**
- `agent_packages`
- `agent_package_versions`
- 扩展后的 `claw_agents`
- `conversations`
- `conversation_messages`
- `agent_install_approvals`

- [x] **Step 1: 新增 AgentPackageEntity / Repository**

字段按 `ARCHITECTURE.md`：

```text
id, packageKey, ownerUserId, name, summary, description, visibility,
latestVersionId, installCount, createdAt, updatedAt
```

约束：

```text
unique(ownerUserId, packageKey)
index(visibility, updatedAt)
index(ownerUserId, updatedAt)
```

- [x] **Step 2: 新增 AgentPackageVersionEntity / Repository**

字段按 `ARCHITECTURE.md`：

```text
id, packageId, version, status, manifestJson, systemPromptSnapshot,
personaFilesJson, skillFilesJson, defaultProfile, requiredProfile,
capabilitiesJson, inputContractJson, outputContractJson, changelog, createdAt
```

约束：

```text
unique(packageId, version)
index(packageId, status, createdAt)
index(status, createdAt)
```

- [x] **Step 3: 扩展 ClawAgentEntity 为 Agent Instance**

补充字段：

```text
sourceType, sourceAgentId, packageId, packageVersionId,
localSystemPromptOverride, localProfile, installedBy, installedAt
```

保留：

```text
roleKey, displayName, defaultRole, enabled, sortOrder
```

约束：

```text
unique(clawId, roleKey)
index(clawId, enabled, sortOrder)
index(clawId, packageVersionId)
index(clawId, sourceAgentId)
```

- [x] **Step 4: 新增 Conversation 模型**

新增 `ConversationEntity`：

```text
id, ownerUserId, clawId, title, status, createdAt, updatedAt
```

新增 `ConversationMessageEntity`：

```text
id, conversationId, ownerUserId, clawId, agentInstanceId,
agentId, agentKey, roleKey, provider, model, role, content,
createdAt
```

- [x] **Step 5: 新增 AgentInstallApproval**

字段：

```text
id, approvalType=agent_install, clawId, ownerUserId,
requestingAgentInstanceId, packageId, packageVersionId,
reason, status, expiresAt, createdAt, resolvedAt
```

状态：

```text
PENDING, APPROVED, REJECTED, EXPIRED, CONSUMED
```

---

### Task 2: Agent 发布包服务

**Files:**
- Add: `spring-backend/.../agentpackage/*`
- Modify: `agentconfig` service/controller as needed

**Produces:**
- 用户可发布自己的 Agent
- 市场可查询公开 Agent Package
- 已发布版本不可变

- [x] **Step 1: 实现 AgentPackageService.publish**

流程：

```text
校验 Agent 归属
校验必填字段
生成/更新 agent_packages
创建 agent_package_versions
快照 systemPrompt/persona/skills/capabilities/contracts/profile
更新 latestVersionId
写 audit log
```

- [x] **Step 2: 实现发布 API**

接口：

```text
POST /api/agents/{agentId}/publish
GET  /api/agent-packages
GET  /api/agent-packages/{packageId}
GET  /api/agent-packages/{packageId}/versions
```

- [x] **Step 3: 补测试**

覆盖：

```text
非 owner 不可发布
同一 package version 不可重复发布
published version 不可原地修改
public package 可被查询
private package 只 owner 可见
```

---

### Task 3: Agent 安装与 Instance 管理

**Files:**
- Add: `spring-backend/.../agentinstall/*`
- Modify: `spring-backend/.../claw/*`

**Produces:**
- Agent Package Version 可安装到 Claw
- 每次安装生成独立 Agent Instance ID

- [x] **Step 1: 实现 AgentInstallService.install**

流程：

```text
校验 Claw 归属
校验 Package Version 可见且 published
生成 claw_agents.id
生成不冲突 roleKey
写 claw_agents sourceType=package
设置 packageVersionId/localProfile/installedBy/installedAt
写 audit log
返回 Agent Instance
```

- [x] **Step 2: 实现 Agent Instance API**

接口：

```text
POST   /api/claws/{clawId}/agents/install
GET    /api/claws/{clawId}/agents
PATCH  /api/claws/{clawId}/agents/{agentInstanceId}
DELETE /api/claws/{clawId}/agents/{agentInstanceId}
```

- [x] **Step 3: 调整现有 Claw 角色语义**

`roleKey` 保留为 UI 选择器和 @ 标识，但所有服务层主键使用 `agentInstanceId`。

- [x] **Step 4: 补测试**

覆盖：

```text
不能安装不可见 package
不能安装 revoked version
roleKey 冲突会被拒绝或自动生成后缀
删除/禁用 Agent Instance 后不可被调用
```

---

### Task 4: Conversation 与记忆隔离

**Files:**
- Add/Modify: `spring-backend/.../conversation/*`
- Modify: `spring-backend/.../session/*`
- Modify: `spring-backend/.../clawchat/ClawChatService.java`

**Produces:**
- 用户可见 Conversation Thread
- 每个 Agent Instance 独立 Runtime memory session

- [ ] **Step 1: 实现 ConversationService**

职责：

```text
创建 conversation_id
保存 conversation_messages
按 claw/user 查询 conversation
按 conversation_id 拉取用户可见消息
```

- [ ] **Step 2: 实现 AgentMemorySessionResolver**

规则：

```text
session_id = agent-memory:{conversation_id}:{agent_instance_id}
```

不得回退到 `roleKey`。

- [ ] **Step 3: 改造 ClawChatRunRequest / Response**

请求至少支持：

```text
conversationId
agentInstanceId
roleKey
prompt
```

如果只传 `roleKey`，Spring 必须解析到 `agentInstanceId` 后再进入 Orchestrator。

- [ ] **Step 4: 改造消息保存**

用户可见消息写入 `conversation_messages`。

OpenClaw Runtime 私有上下文由 `session_id=agent-memory:{conversation_id}:{agent_instance_id}` 隔离。

- [ ] **Step 5: 补测试**

覆盖：

```text
同一 conversation 下 Agent A/B session_id 不同
Agent A 的审批恢复仍使用 Agent A 的 session_id
roleKey 改名不影响已有 Agent Memory Session
```

---

### Task 5: Conversation Orchestrator

**Files:**
- Add: `spring-backend/.../orchestrator/*`
- Modify: `spring-backend/.../clawchat/ClawChatService.java`

**Produces:**
- 本轮 Agent 选择统一由 Spring Orchestrator 决定

- [ ] **Step 1: 实现 ConversationOrchestratorService.resolveTurnAgent**

优先级：

```text
agentInstanceId 显式指定
roleKey 显式指定
当前 conversation 活跃 Agent
defaultRole
自动路由
```

- [ ] **Step 2: 将 ClawChatService 改为调用 Orchestrator**

`ClawChatService` 不直接解析 role，而是请求 Orchestrator 返回：

```text
conversationId
agentInstanceId
agentConfig/packageVersion
roleKey
displayName
toolProfile
runtimeSessionId
```

- [ ] **Step 3: 补测试**

覆盖：

```text
显式 agentInstanceId 优先
roleKey 可解析为 agentInstanceId
disabled Agent Instance 不可被选中
默认 Agent 正常回退
```

---

### Task 6: FastAPI Runtime 契约扩展

**Files:**
- Modify: `openclaw/api.py`
- Modify: `spring-backend/.../pyclaw/PyclawAgentRunRequest.java`
- Modify: `spring-backend/.../pyclaw/PyclawAgentResumeRequest.java`

**Produces:**
- FastAPI 日志、审批上下文和请求模型携带 `conversation_id` / `agent_instance_id`

- [ ] **Step 1: 扩展 AgentRunRequest / AgentResumeRequest**

新增字段：

```text
conversation_id
agent_instance_id
```

- [ ] **Step 2: 扩展 ApprovalRuntimeContext**

审批 pending state 必须保存：

```text
conversation_id
agent_instance_id
```

- [ ] **Step 3: 扩展诊断日志**

日志必须包含：

```text
conversation_id
agent_instance_id
agent_key
role_key
resolved_tools
denied_tools
```

- [ ] **Step 4: 补测试**

覆盖：

```text
run 请求日志包含 conversation_id/agent_instance_id
pending approval state 保存 conversation_id/agent_instance_id
resume 使用同一个 agent memory session
```

---

### Task 7: Agent 发现、安装审批与 call_agent 工具

**Files:**
- Add/Modify: `openclaw/tools/*`
- Add: `spring-backend/.../orchestrator/*`
- Modify: approval UI/API

**Produces:**
- Agent 可运行时发现候选 Agent
- 自动引入必须审批
- Agent 可调用同一 Claw 内已启用 Agent Instance

- [ ] **Step 1: Spring 实现 Orchestrator 内部 API**

接口：

```text
POST /api/orchestrator/agents/discover
POST /api/orchestrator/agents/install-requests
POST /api/orchestrator/agents/call
```

- [ ] **Step 2: FastAPI 新增工具**

工具：

```text
discover_agents
request_agent_install
call_agent
```

工具 executor 只回调 Spring，不直接读写市场或 Claw 安装关系。

- [ ] **Step 3: 实现 agent_install 审批**

统一审批类型：

```text
tool_execution
agent_install
agent_call
```

自动发现后的安装请求必须产生 `agent_install` 审批。

- [ ] **Step 4: 实现 call_agent**

规则：

```text
Spring 校验目标 Agent Instance 属于同一 Claw
目标 Agent Instance enabled=true
生成目标 Agent 的私有 Runtime session_id
调用 FastAPI /v1/agent/run
写入 Conversation Thread
返回结果给发起 Agent
```

- [ ] **Step 5: 补测试**

覆盖：

```text
discover_agents 只返回当前用户可见 package
request_agent_install 不会立即创建 Agent Instance
审批通过后才创建 Agent Instance
call_agent 不能调用未安装 Agent
call_agent 不能调用其他 Claw 的 Agent
```

---

### Task 8: 前端发布市场、安装与多 Agent 对话 UI

**Files:**
- Modify/Add: `pyclaw-web/src/views/*`
- Modify/Add: `pyclaw-web/src/api/*`
- Modify/Add: shared UI components as needed

**Produces:**
- 用户可发布 Agent
- 用户可浏览市场并安装 Agent 到 Claw
- Claw 对话支持 Agent 选择和审批展示

- [ ] **Step 1: Agent 配置页增加发布入口**

显示当前 Agent 的发布状态、最新版本、发布按钮。

- [ ] **Step 2: 新增 Agent 市场页面**

能力：

```text
搜索
筛选 visibility/profile/capability
查看详情
安装到 Claw
```

- [ ] **Step 3: Claw Agent 列表改用 Agent Instance**

显示：

```text
displayName
roleKey
sourceType
package version
localProfile
enabled/defaultRole
```

- [ ] **Step 4: Claw 对话增加 Agent 选择**

同一 conversation 中可选择本轮回答 Agent；消息气泡显示 Agent Instance/roleKey。

- [ ] **Step 5: 审批 UI 支持 agent_install**

审批卡片区分：

```text
工具执行审批
Agent 引入审批
Agent 调用审批
```

- [ ] **Step 6: 构建验证**

运行：

```bash
cd pyclaw-web
npm run build
```

---

### Task 9: 端到端验证

- [ ] **Step 1: 发布 Agent**

用户 A 创建 Agent 并发布 `1.0.0`。

- [ ] **Step 2: 安装 Agent**

用户 A 将该 Agent Package Version 安装到自己的 Claw，生成 Agent Instance。

- [ ] **Step 3: 多 Agent 对话**

同一 Conversation Thread 中选择 Agent A 和 Agent B 分别回答，确认 Runtime `session_id` 不同。

- [ ] **Step 4: 运行时发现**

Agent A 调用 `discover_agents` 返回候选 Agent Package。

- [ ] **Step 5: 审批引入**

Agent A 调用 `request_agent_install` 后产生 `agent_install` 审批；审批通过前不得创建 Agent Instance。

- [ ] **Step 6: Agent 互相调用**

审批安装后，Agent A 通过 `call_agent` 调用 Agent B，消息写入同一 Conversation Thread，Agent B 使用独立 memory session。

- [ ] **Step 7: K3s 日志验证**

OpenClaw 日志必须可 grep：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs \
  -l 'app.kubernetes.io/instance=pyclaw,!app.kubernetes.io/component' \
  --tail=1000 | grep 'Resolved Agent runtime tools'
```

日志中必须包含：

```text
conversation_id
agent_instance_id
agent_key
role_key
resolved_tools
```
