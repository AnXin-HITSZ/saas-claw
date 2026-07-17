# Claw 工具用户审批全量实现方案

日期：2026-07-17

## 1. 背景与产品判断

当前 PyClaw SaaS 的安全边界是：

```text
用户只能创建、修改、运行自己的 Claw
Agent 工具只能操作当前用户当前 Claw 的 sandbox workspace
用户对自己 Claw 内的操作负责
```

因此不建议继续用复杂的 `tool:grant:minimal / readonly / messaging / coding / full` 权限矩阵限制普通用户选择 Tool Profile。更合理的产品策略是：

```text
所有用户都可以选择 minimal / readonly / messaging / coding / full
Tool Profile 只表示 Agent 启用哪些工具集合
真正的安全闸门放在工具运行时审批
中高风险工具执行前必须由 Claw 所属用户本人确认
管理员不参与审批
```

核心原则：

```text
不要阻塞原始 HTTP 请求等待用户点击同意
必须采用“逻辑挂起 + 持久化状态 + resume”模型
```

错误方式：

```text
POST /chat/runs
→ Python 卡住等待用户确认
→ 前端 / Spring / Python / Ingress 可能全部超时
```

正确方式：

```text
POST /chat/runs
→ 工具调用命中审批
→ 保存审批单和挂起状态
→ 立即返回 PENDING_APPROVAL
→ 前端弹窗
→ 用户 approve/reject
→ 调 resume 接口继续 Agent loop
```

## 2. 当前项目现状

### 2.1 已有能力

Python 侧已有工具元数据：

```text
openclaw/tools/catalog.py
openclaw/tools/types.py
```

已有字段：

```python
risk: ToolRisk = "low"
```

`requires_approval` 属于旧设计，不再保留为审批依据。一步到位实现时需要删除或停止暴露该字段，审批策略只由 `risk` 和硬性安全策略决定。

当前工具风险：

```text
workspace_info: low
list_files: low
read_file: low
write_file: medium
apply_patch: medium
```

已有工具执行拦截点：

```text
openclaw/tools/executor.py
```

执行前会调用：

```python
hooks.before_tool_call(call, tool, arguments, context)
```

已有 Agent loop 工具执行位置：

```text
openclaw/agent/loop.py
```

当模型返回 tool call 后，loop 会调用：

```python
execute_tool_call_batch(...)
```

Spring 侧已有工具目录展示、风险字段透传：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/tool
pyclaw-web/src/views/ToolCatalogPage.vue
```

权限中存在历史预留：

```text
approval:resolve
```

### 2.2 当前缺失

当前没有完整审批闭环：

```text
没有 approval_requests 表
没有审批 API
没有前端审批弹窗
没有 Python pending runtime state
没有 /v1/agent/resume
没有 approve/reject 后继续 Agent loop
```

当前 SaaS `/v1/agent/run` 创建 Agent 时没有传自定义 `tool_hooks`：

```python
Agent(..., tool_hooks=None)
```

所以最终走：

```python
NoopToolHooks()
```

`NoopToolHooks.before_tool_call()` 永远放行，因此目前 `risk` 不会真正阻止或挂起工具执行。

## 3. 总体目标

实现用户本人审批制度：

```text
用户发起 Claw 对话
→ Agent 决定调用工具
→ 如果工具风险为 medium/high，则创建审批
→ Agent loop 挂起
→ 前端弹窗展示工具意图
→ 用户 approve 后继续执行原工具调用
→ 用户 reject 后把拒绝作为 tool result 反馈给模型
→ Agent loop 继续并生成最终回复
```

第一版只做用户本人审批：

```text
审批人 = Claw owner
管理员不参与审批
不做管理员审批后台
不做多级审批
```

## 4. 数据存储设计

### 4.1 MySQL 存审批单

审批单是业务记录，需要长期可查、可审计、可分页展示，应放 MySQL。

新增表：

```sql
create table tool_approval_requests (
  id varchar(64) primary key,
  owner_user_id varchar(64) not null,
  claw_id varchar(64) not null,
  claw_name varchar(255),
  session_id varchar(128) not null,
  agent_id varchar(64),
  agent_key varchar(128),
  role_key varchar(128),
  tool_call_id varchar(128) not null,
  tool_name varchar(128) not null,
  risk varchar(32) not null,
  status varchar(32) not null,
  intent_summary varchar(1024),
  arguments_preview text,
  pending_state_key varchar(255) not null,
  expires_at datetime(6) not null,
  resolved_by varchar(64),
  resolved_at datetime(6),
  reject_reason varchar(1024),
  created_at datetime(6) not null,
  updated_at datetime(6) not null,
  index idx_tool_approval_owner_created (owner_user_id, created_at),
  index idx_tool_approval_claw_created (claw_id, created_at),
  index idx_tool_approval_session (session_id),
  index idx_tool_approval_status_expires (status, expires_at)
);
```

状态枚举：

```text
PENDING
APPROVED
REJECTED
EXPIRED
CONSUMED
```

含义：

```text
PENDING  等待用户确认
APPROVED 用户已同意，等待 Python resume 消费
REJECTED 用户已拒绝
EXPIRED  超时未处理
CONSUMED Python 已消费审批结果，Agent loop 已继续
```

### 4.2 Redis 存挂起 runtime state

挂起状态是临时运行现场，不适合长期查询，应放 Redis 并设置 TTL。

重要边界：

```text
Redis pending state 不存 Secret 明文
Redis pending state 不存 Provider API Key 明文
MySQL approval_requests 不存完整工具参数正文
MySQL approval_requests 只存脱敏、截断后的 arguments_preview
resume 时由 Spring 重新解析 Agent / Provider / Secret，并临时把 api_key 传给 Python
```

Redis key：

```text
agent:pending_approval:{approvalId}
```

TTL：

```text
30 分钟
```

内容：

```json
{
  "approval_id": "approval_xxx",
  "session_id": "...",
  "claw_id": "...",
  "owner_user_id": "...",
  "agent_key": "...",
  "role_key": "...",
  "messages": [],
  "assistant_message": {},
  "tool_call": {
    "id": "...",
    "name": "write_file",
    "input": {}
  }
}
```

安全要求：

```text
不要在 Redis 保存明文 Secret
不要在 Redis 保存 Provider API Key
不要在 MySQL 保存完整工具正文参数
arguments_preview 必须脱敏、截断
pending state 超时必须不可恢复
```

推荐第一版简化：

```text
Python pending state 中只保存恢复 Agent loop 所需的 messages、assistant_message、tool_call 和非敏感上下文
TTL 30 分钟
审批完成后立即删除 pending state
Spring resume 时重新读取 ProviderConfig / AgentConfig / ToolPolicy，并临时向 Python 传入 api_key
```

## 5. 审批判断规则

一步到位实现时只保留 `risk` 作为工具审批策略来源，删除 `requires_approval` 字段及前后端响应中的同名字段。审批不是工具 schema 的额外布尔开关，而是由 catalog 中的风险等级统一推导。

基础规则：

```text
risk == "low"              -> ALLOW
risk in ("medium", "high") -> PENDING_APPROVAL
硬性安全策略命中           -> DENY
```

`ALLOW / PENDING_APPROVAL / DENY` 必须成为明确的一等决策类型，而不是由 `allowed: boolean` 临时表达。

新增执行决策类型：

```python
ToolExecutionDecisionStatus = Literal["ALLOW", "PENDING_APPROVAL", "DENY"]


@dataclass
class ToolExecutionDecision:
    status: ToolExecutionDecisionStatus = "ALLOW"
    reason: str | None = None
    denied_reason: str | None = None
    arguments: dict[str, Any] | None = None
    approval: PendingToolApproval | None = None
```

三种决策含义：

```text
ALLOW             直接执行工具。可携带规范化后的 arguments。
PENDING_APPROVAL  创建审批单和 pending state，挂起 Agent loop，返回前端等待用户确认。
DENY              策略直接拒绝，不允许用户批准，工具不执行，并把拒绝结果反馈给 Agent loop。
```

硬性安全策略命中 `DENY`，典型场景：

```text
越权访问其他用户或其他 Claw
非法路径或越出当前 Claw workspace
缺少 sandbox_base_url
参数命中绝对禁止规则
工具不属于当前 resolved tools
```

旧的 `ToolHookDecision.allowed=false` 不再作为主语义保留。需要把旧布尔拒绝迁移为 `ToolExecutionDecision(status="DENY")`，等待审批则必须使用 `ToolExecutionDecision(status="PENDING_APPROVAL")`。

决策来源与写入链路：

```text
Tool Catalog 定义 risk
→ Tool Resolver 解析当前 Agent 实际可用工具
→ ApprovalToolHooks.before_tool_call 根据 risk 和硬性策略生成 ToolExecutionDecision
→ Executor 消费 ToolExecutionDecision
→ ALLOW: 调用 tool.execute
→ PENDING_APPROVAL: 写 Redis pending state，返回 PENDING_APPROVAL 给 API 层
→ DENY: 不写审批单，不执行工具，生成 blocked tool result
```

这里的“写入”不是把 `ALLOW / DENY` 写入数据库，而是在工具执行前构造一个强类型决策对象。只有 `PENDING_APPROVAL` 会产生持久化副作用：Python 保存 Redis pending state，Spring 创建 MySQL 审批单。

相关删除点：

```text
openclaw/tools/types.py       删除 ToolMetadata.requires_approval
openclaw/tools/catalog.py     删除 ToolCatalogEntry.requires_approval
openclaw/tools/resolver.py    删除 ResolvedTool.requires_approval
openclaw/api.py               删除工具目录和运行时响应中的 requires_approval 字段
Spring DTO                    删除 PyclawToolCatalogEntry.requiresApproval
ToolCatalogPage.vue           删除或改造 requires approval 展示
```

## 6. Python Runtime 实现

### 6.1 新增 pending approval 类型

新增文件：

```text
openclaw/tools/approval.py
```

定义：

```python
@dataclass
class PendingToolApproval:
    approval_id: str
    session_id: str
    tool_call_id: str
    tool_name: str
    risk: str
    intent_summary: str
    arguments_preview: dict[str, Any]
    pending_state_key: str
    expires_at: str


class PendingToolApprovalError(Exception):
    def __init__(self, approval: PendingToolApproval) -> None:
        self.approval = approval
```

### 6.2 新增 ToolExecutionDecision 与 ApprovalToolHooks

新增或改造文件：

```text
openclaw/tools/hooks.py
openclaw/tools/approval_hooks.py
```

`hooks.py` 中需要把旧的布尔 Hook 决策替换为 `ToolExecutionDecision`：

```python
ToolExecutionDecisionStatus = Literal["ALLOW", "PENDING_APPROVAL", "DENY"]


@dataclass
class ToolExecutionDecision:
    status: ToolExecutionDecisionStatus = "ALLOW"
    reason: str | None = None
    denied_reason: str | None = None
    arguments: dict[str, Any] | None = None
    approval: PendingToolApproval | None = None
```

`before_tool_call` 返回 `ToolExecutionDecision`：

```python
class ToolHooks(Protocol):
    async def before_tool_call(self, call, tool, arguments, context) -> ToolExecutionDecision: ...


class NoopToolHooks:
    async def before_tool_call(self, call, tool, arguments, context) -> ToolExecutionDecision:
        return ToolExecutionDecision(status="ALLOW", arguments=arguments)
```

`ApprovalToolHooks` 职责：

```text
before_tool_call 中读取 tool.metadata.risk
low 返回 ALLOW
medium/high 创建 pending approval，保存 pending state，返回 PENDING_APPROVAL
硬性安全策略命中返回 DENY
```

伪代码：

```python
class ApprovalToolHooks(NoopToolHooks):
    def __init__(self, pending_store: PendingApprovalStore, request_context: ApprovalRuntimeContext):
        self.pending_store = pending_store
        self.request_context = request_context

    async def before_tool_call(self, call, tool, arguments, context):
        hard_policy_error = check_hard_policy(tool, arguments, context, self.request_context)
        if hard_policy_error:
            return ToolExecutionDecision(status="DENY", denied_reason=hard_policy_error)

        if tool.metadata.risk == "low":
            return ToolExecutionDecision(status="ALLOW", arguments=arguments)

        approval = self.pending_store.create_pending_approval(
            call=call,
            tool=tool,
            arguments=arguments,
            context=context,
            runtime_context=self.request_context,
        )
        return ToolExecutionDecision(
            status="PENDING_APPROVAL",
            reason="该工具调用需要用户确认后继续执行。",
            arguments=arguments,
            approval=approval,
        )
```

注意：

```text
第一版遇到第一个需要审批的 tool call 就挂起
不要并发创建多个审批单
```

### 6.3 修改 Executor 决策处理

当前位置：

```text
openclaw/tools/executor.py
```

执行工具前必须统一消费 `ToolExecutionDecision`：

```python
decision = await hooks.before_tool_call(call, tool, arguments, context)

if decision.status == "DENY":
    return build_denied_tool_result(call, decision.denied_reason or decision.reason)

if decision.status == "PENDING_APPROVAL":
    raise PendingToolApprovalError(decision.approval)

# ALLOW
result = await tool.execute(context, decision.arguments or arguments)
```

约束：

```text
不要继续使用旧布尔字段表达拒绝
不要用 DENY 表达等待审批
不要让 tool.execute 自己创建审批单
所有审批分支必须在 executor 真正调用 tool.execute 前发生
```

执行边界必须固定为：

```text
Hook / Guard 只负责生成 ToolExecutionDecision
Executor 负责消费 ToolExecutionDecision，并决定是否调用 tool.execute
Agent loop 不负责判断工具是否应该执行
Agent loop 只处理两类输出：普通 tool result，或 PENDING_APPROVAL 挂起信号
```

也就是说，`ALLOW`、`DENY` 都应在 executor 内闭环：

```text
ALLOW -> executor 调用 tool.execute，并返回执行结果
DENY  -> executor 不调用 tool.execute，生成 blocked tool result，并让 Agent loop 继续
```

只有 `PENDING_APPROVAL` 需要从 executor 冒泡出去：

```text
PENDING_APPROVAL -> executor 抛出 PendingToolApprovalError
Agent loop 不吞掉该异常
api.py 捕获后返回 PENDING_APPROVAL 给 Spring
```

### 6.4 PendingApprovalStore

新增文件：

```text
openclaw/tools/approval_store.py
```

第一版可用文件/Redis 二选一，但生产目标应使用 Redis。

建议使用 Redis：

```text
OPENCLAW_REDIS_URL
或复用已有 Python 依赖/配置
```

如果 Python runtime 当前没有 Redis 客户端依赖，第一版可先使用本地 JSON 文件作为开发实现，但部署方案必须改为 Redis。

推荐接口：

```python
class PendingApprovalStore:
    def save(self, approval_id: str, state: dict[str, Any], ttl_seconds: int) -> None: ...
    def load(self, approval_id: str) -> dict[str, Any] | None: ...
    def delete(self, approval_id: str) -> None: ...
```

### 6.5 修改 Agent loop 捕获 pending

当前位置：

```text
openclaw/agent/loop.py
```

当前逻辑：

```python
if assistant.stop_reason == "toolUse":
    tool_results = await execute_tool_calls(...)
```

需要让 `PendingToolApprovalError` 冒泡到 API 层，或在 loop 层转换成特殊结果。

推荐第一版：

```text
executor/hooks 抛 PendingToolApprovalError
loop 不吞掉
api.py 捕获 PendingToolApprovalError
返回 PENDING_APPROVAL
```

这样改动小。

### 6.6 修改 /v1/agent/run 响应

当前：

```text
openclaw/api.py
AgentRunResponse:
  session_id
  message
  text
```

改为：

```python
class ApprovalResponse(BaseModel):
    id: str
    tool_name: str
    risk: str
    intent: str
    arguments_preview: dict[str, Any]
    expires_at: str


class AgentRunResponse(BaseModel):
    status: Literal["COMPLETED", "PENDING_APPROVAL", "FAILED"] = "COMPLETED"
    session_id: str
    message: dict[str, Any] | None = None
    text: str = ""
    approval: ApprovalResponse | None = None
```

`run_agent` 捕获：

```python
except PendingToolApprovalError as exc:
    return AgentRunResponse(
        status="PENDING_APPROVAL",
        session_id=exc.approval.session_id,
        message=None,
        text="该工具调用需要你确认后继续执行。",
        approval=...
    )
```

### 6.7 /v1/agent/resume

新增请求：

```python
class AgentResumeRequest(BaseModel):
    approval_id: str
    decision: Literal["APPROVED", "REJECTED"]
    rejection_reason: str | None = None

    # Spring 在 resume 时重新注入运行配置；这些字段不从 Redis pending state 中读取。
    provider: ApiProviderName = "openai"
    model: str | None = None
    api_key: str | None = None
    base_url: str | None = None
    system: str | None = None
    api_mode: ApiMode = "auto"
    tool_profile: ToolProfileName = "coding"
    tools_allow: list[str] | None = None
    tools_deny: list[str] | None = None
    tools_also_allow: list[str] | None = None
    sandbox_base_url: str | None = None
```

新增响应同 `AgentRunResponse`。

流程：

```text
读取 pending state
如果不存在 -> 404/410
使用 Spring resume 请求中重新注入的 provider / model / api_key / tool policy / sandbox_base_url 创建运行时
如果 decision=APPROVED:
  重新创建 provider / policy / tools / Agent
  恢复 messages
  执行原 tool_call
  把 tool result 加回 messages
  继续 run_agent_loop
  返回 COMPLETED 或再次 PENDING_APPROVAL
如果 decision=REJECTED:
  构造 tool result: 用户拒绝执行该工具调用
  加回 messages
  继续 run_agent_loop
  返回 COMPLETED 或再次 PENDING_APPROVAL
```

重要：

```text
resume 后仍可能再次命中另一个高风险工具
此时继续返回新的 PENDING_APPROVAL
```

### 6.8 Agent 状态恢复要求

当前 `AgentSession` 和 `AgentState` 需要支持恢复 messages。

如果现有 API 不方便，第一版可新增 helper：

```python
def build_agent_from_pending_state(state: dict[str, Any]) -> Agent:
    agent = Agent(...)
    agent.state.messages = messages_from_dicts(state["messages"])
    return agent
```

需要检查：

```text
openclaw/llm/types.py
message_to_dict
是否已有 dict -> message 的反序列化函数
```

如没有，新增 `message_from_dict` / `messages_from_dicts`。

## 7. Spring Boot 实现

### 7.1 新增实体

新增包：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/approval
```

新增：

```text
ToolApprovalRequestEntity.java
ToolApprovalRequestRepository.java
ToolApprovalStatus.java
ToolApprovalResponse.java
ToolApprovalDecisionResponse.java
ToolApprovalService.java
ToolApprovalController.java
```

Entity 字段对应第 4.1 节。

### 7.2 Spring 负责审批单 MySQL

推荐职责：

```text
Python 发现需要审批并创建 pending state
Python 返回 approval payload
Spring 收到 PENDING_APPROVAL 后创建 MySQL 审批单
```

注意：必须保证 approval_id 一致。

如果 Python 已生成 approval_id，Spring 使用该 id 插入 MySQL。

`ToolApprovalService.createFromPyclaw(...)`：

```text
校验 claw owner
插入 PENDING 审批单
记录 pending_state_key
写 audit log: tool.approval.created
```

### 7.3 修改 PyclawAgentRunResponse

当前：

```text
PyclawAgentRunResponse.java
```

增加：

```java
String status;
PyclawApprovalResponse approval;
```

新增 record：

```java
public record PyclawApprovalResponse(
    String id,
    @JsonProperty("tool_name") String toolName,
    String risk,
    String intent,
    @JsonProperty("arguments_preview") Map<String, Object> argumentsPreview,
    @JsonProperty("pending_state_key") String pendingStateKey,
    @JsonProperty("expires_at") String expiresAt
) {}
```

### 7.4 修改 ClawChatRunResponse

当前：

```text
ClawChatRunResponse.java
```

增加：

```java
String status;
ToolApprovalResponse approval;
```

状态：

```text
COMPLETED
PENDING_APPROVAL
FAILED
```

### 7.5 修改 ClawChatService.run

当前成功后总是保存 assistant message。

需要改为：

```java
PyclawAgentRunResponse pyResponse = pyclawClient.runAgent(pyRequest);

if ("PENDING_APPROVAL".equals(pyResponse.status())) {
    ToolApprovalResponse approval = approvalService.createFromPyclaw(...);
    sessionService.saveMessage(..., "assistant", "该操作需要你确认后继续执行。", ...);
    return new ClawChatRunResponse(..., "PENDING_APPROVAL", approval, ...);
}

// COMPLETED:
sessionService.saveMessage(..., "assistant", pyResponse.text(), ...);
return ...
```

### 7.6 approve/reject API

推荐路径：

```http
POST /api/claws/{clawId}/chat/approvals/{approvalId}/approve
POST /api/claws/{clawId}/chat/approvals/{approvalId}/reject
```

Spring 校验：

```text
当前用户是 claw owner
approval.claw_id == clawId
approval.owner_user_id == currentUser
approval.status == PENDING
approval.expires_at > now
```

Spring 在调用 `/v1/agent/resume` 前必须重新组装运行配置：

```text
从 approval 找到 claw_id / session_id / agent_id / role_key
重新读取 AgentConfig / AgentToolPolicy / ProviderConfig
重新解密 Provider API Key
重新计算 sandbox_base_url
把 api_key 只作为本次 HTTP 请求字段传给 Python
api_key 不写 Redis、不写 MySQL、不写日志
```

approve：

```text
更新 MySQL status=APPROVED
调用 Python /v1/agent/resume decision=APPROVED
如果 Python 返回 COMPLETED:
  status=CONSUMED
  保存最终 assistant message
如果 Python 返回 PENDING_APPROVAL:
  status=CONSUMED
  创建新的审批单
  保存等待审批消息
返回 ClawChatRunResponse
```

reject：

```text
更新 MySQL status=REJECTED
调用 Python /v1/agent/resume decision=REJECTED
保存最终 assistant message
返回 ClawChatRunResponse
```

### 7.7 PyclawClient 增加 resume

新增：

```java
public PyclawAgentRunResponse resumeAgent(PyclawAgentResumeRequest request)
```

请求：

```java
public record PyclawAgentResumeRequest(
    @JsonProperty("approval_id") String approvalId,
    String decision,
    @JsonProperty("rejection_reason") String rejectionReason,
    String provider,
    String model,
    @JsonProperty("api_mode") String apiMode,
    @JsonProperty("base_url") String baseUrl,
    @JsonProperty("api_key") String apiKey,
    String system,
    @JsonProperty("tool_profile") String toolProfile,
    @JsonProperty("tools_allow") List<String> toolsAllow,
    @JsonProperty("tools_deny") List<String> toolsDeny,
    @JsonProperty("tools_also_allow") List<String> toolsAlsoAllow,
    @JsonProperty("sandbox_base_url") String sandboxBaseUrl
) {}
```

调用：

```text
POST /v1/agent/resume
```

### 7.8 审计日志

写入：

```text
tool.approval.created
tool.approval.approved
tool.approval.rejected
tool.approval.expired
tool.approval.consumed
```

审计 metadata 至少包含：

```text
approval_id
claw_id
session_id
tool_name
risk
```

不要记录 Secret 明文。

## 8. 前端实现

### 8.1 ClawChatPage 响应处理

文件：

```text
pyclaw-web/src/views/ClawChatPage.vue
```

当前发送：

```js
api.post(`/api/claws/${clawId.value}/chat/runs`, ...)
```

改为判断：

```js
if (res.status === "PENDING_APPROVAL") {
  pendingApproval.value = res.approval;
  showApprovalModal.value = true;
  messages.value.push({
    role: "assistant",
    content: res.text || "该操作需要你确认后继续执行。",
  });
  return;
}
```

### 8.2 审批弹窗

显示：

```text
工具名称
风险等级
执行意图
参数摘要
过期时间
同意
拒绝
```

参数摘要用 `<pre>` 或结构化列表展示，注意长内容折叠。

按钮：

```text
同意执行
拒绝执行
```

同意：

```js
api.post(`/api/claws/${clawId}/chat/approvals/${approval.id}/approve`)
```

拒绝：

```js
api.post(`/api/claws/${clawId}/chat/approvals/${approval.id}/reject`)
```

返回仍然按 `ClawChatRunResponse` 处理，因为 resume 也可能再次返回 `PENDING_APPROVAL`。

### 8.3 会话消息

前端不要自己伪造最终 assistant 回复。

规则：

```text
PENDING_APPROVAL: 显示等待审批消息和弹窗
COMPLETED: 显示最终 assistant text
FAILED: 显示错误
```

### 8.4 Tool Profile 全开放

删除或弱化前端按 `tool:grant:*` 过滤 profile 的逻辑。

Agent 配置页下拉展示：

```text
minimal
readonly
messaging
coding
full
```

后端 `ToolPolicyGrantValidator` 同步取消 profile 权限矩阵限制。

## 9. Tool Profile 权限矩阵调整

### 9.1 后端

文件：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/agentconfig/ToolPolicyGrantValidator.java
```

目标：

```text
所有登录用户均可保存 minimal / readonly / messaging / coding / full
不再要求 tool:grant:coding / tool:grant:full
```

保留 profile name 校验：

```text
非法 profile 仍然拒绝
```

`requireCanRouteTo` 如只用于 profile 权限，也同步弱化。

### 9.2 默认权限

可以逐步删除默认用户权限中的：

```text
tool:grant:minimal
tool:grant:readonly
tool:grant:messaging
tool:grant:coding
tool:grant:full
```

但为兼容旧数据，第一版可先保留这些 authority，不再依赖它们。

### 9.3 旧列清理

当前已有：

```text
LegacyToolPolicySchemaCleaner
```

它已清理历史 `shell_approval`。

需要扩展为同时清理：

```text
agent_tool_policies.web_access
```

避免旧字段残留影响启动或策略保存。

## 10. Python API 与 Spring 交互状态

### 10.1 /v1/agent/run

请求不变。

响应新增：

```json
{
  "status": "COMPLETED",
  "session_id": "...",
  "message": {},
  "text": "...",
  "approval": null
}
```

或：

```json
{
  "status": "PENDING_APPROVAL",
  "session_id": "...",
  "message": null,
  "text": "该工具调用需要你确认后继续执行。",
  "approval": {
    "id": "...",
    "tool_name": "write_file",
    "risk": "medium",
    "intent": "准备写入文件 a.txt",
    "arguments_preview": {},
    "pending_state_key": "agent:pending_approval:...",
    "expires_at": "..."
  }
}
```

### 10.2 /v1/agent/resume

请求：

```json
{
  "approval_id": "...",
  "decision": "APPROVED"
}
```

或：

```json
{
  "approval_id": "...",
  "decision": "REJECTED",
  "rejection_reason": "用户取消"
}
```

响应同 `/v1/agent/run`。

## 11. 安全要求

1. 审批人只能是 Claw owner。
2. approval 必须绑定 owner_user_id、claw_id、session_id。
3. approve/reject 必须幂等，重复点击不能重复执行工具。
4. resume 前必须重新校验 approval 状态。
5. pending state 必须有 TTL。
6. pending state 消费后必须删除。
7. arguments_preview 必须脱敏。
8. MySQL 不保存完整工具正文参数；如未来确需审计完整参数，必须单独设计脱敏、加密和留存策略。
9. 第一版只允许一个 Agent loop 同时挂起一个审批。
10. 如果 Redis pending state 丢失，审批单状态更新为 EXPIRED 或 FAILED，并提示用户重新发起。

## 12. 测试计划

### 12.1 Python 单测

新增：

```text
tests/test_tool_approval.py
tests/test_agent_pending_approval.py
```

覆盖：

```text
risk=low 返回 ALLOW 并执行工具
risk=medium/high 返回 PENDING_APPROVAL 且不执行工具
硬性安全策略返回 DENY，并生成 blocked tool result
执行决策对象中不再存在旧布尔字段
reject 后生成“用户拒绝执行该工具调用” tool result
approve 后执行原 tool call
pending state 缺失时报错
resume 后可能再次 PENDING_APPROVAL
```

### 12.2 Spring 单测

新增：

```text
ToolApprovalServiceTest
ClawChatApprovalControllerTest
ClawChatServiceApprovalTest
```

覆盖：

```text
PENDING_APPROVAL 响应创建审批单
非 owner 无法 approve/reject
过期审批无法 approve
重复 approve 不重复执行
approve 后调用 PyclawClient.resumeAgent
reject 后调用 PyclawClient.resumeAgent
COMPLETED 后保存 assistant message
```

### 12.3 前端测试

覆盖：

```text
收到 PENDING_APPROVAL 显示弹窗
点击同意调用 approve API
点击拒绝调用 reject API
resume 返回 COMPLETED 后显示最终回复
resume 再次 PENDING_APPROVAL 时继续显示新弹窗
```

### 12.4 手工验证

1. 创建 Agent，profile 选择 `full`。
2. 在 Claw 对话中让 Agent 读取文件：应直接执行。
3. 让 Agent 写文件：应返回审批弹窗。
4. 点击拒绝：Agent 应说明操作已被拒绝。
5. 再次写文件并点击同意：文件应写入 sandbox workspace，Agent 返回完成说明。
6. 刷新页面，历史会话应包含用户消息、等待审批消息、最终回复。

## 13. 实施顺序

### 阶段一：开放 Tool Profile

1. 修改 `ToolPolicyGrantValidator`，取消 `tool:grant:*` profile 保存限制。
2. 前端 Agent 配置页展示全部 profile。
3. 扩展 `LegacyToolPolicySchemaCleaner` 删除 `web_access` 旧列。
4. 验证普通用户可保存 `full`。

### 阶段二：Python pending approval

1. 删除 `requires_approval` 旧字段及前后端响应字段。
2. 新增 `ToolExecutionDecision`，用 `ALLOW / PENDING_APPROVAL / DENY` 替换旧的 `ToolHookDecision.allowed`。
3. 新增 pending approval 类型。
4. 新增 approval hook。
5. 新增 pending state store。
6. `/v1/agent/run` 注入 `ApprovalToolHooks`。
7. 命中 medium/high 返回 `PENDING_APPROVAL`。
8. 硬性安全策略命中 `DENY` 并返回 blocked tool result。
9. 新增 `/v1/agent/resume`。
10. Python 单测通过。

### 阶段三：Spring 审批单与 API

1. 新增 `tool_approval_requests` 实体和 repository。
2. 修改 `PyclawAgentRunResponse`。
3. 修改 `ClawChatRunResponse`。
4. 修改 `ClawChatService.run` 处理 `PENDING_APPROVAL`。
5. 新增 approve/reject API。
6. 新增 `PyclawClient.resumeAgent`。
7. 写审计日志。

### 阶段四：前端弹窗

1. `ClawChatPage.vue` 处理 `PENDING_APPROVAL`。
2. 新增审批弹窗。
3. 对接 approve/reject API。
4. 处理 resume 后的 `COMPLETED` / `PENDING_APPROVAL`。

### 阶段五：增强

1. 审批超时任务。
2. 审批历史页。
3. 参数 diff 展示。
4. 风险规则配置。
5. 会话内记住同类操作。

## 14. 最终验收标准

1. 普通用户可以选择并保存 `full` profile。
2. `write_file` / `apply_patch` 命中审批，不会立即执行。
3. 原 `/chat/runs` 请求不会长时间阻塞，能快速返回 `PENDING_APPROVAL`。
4. 前端弹窗展示工具名称、风险、意图、参数摘要。
5. 用户 approve 后原工具调用被执行，Agent loop 继续并生成最终回复。
6. 用户 reject 后工具不执行，Agent loop 收到拒绝结果并生成解释。
7. 审批单落 MySQL，pending runtime state 落 Redis 并有 TTL。
8. 只有 Claw owner 可以审批。
9. 重复 approve/reject 不会重复执行工具。
10. Python / Spring / 前端测试通过。

