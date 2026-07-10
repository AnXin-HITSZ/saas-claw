# PyClaw 网关路由、多 Agent 与工具权限技术方案

本文参考 `docs/005/openclaw_gateway_routing_005.md`，并对照原生 OpenClaw 的相关实现，给出将原生 OpenClaw 网关能力迁移到 PyClaw 的技术方案。

重点回答三个问题：

1. 如何动态配置 Agent 工具权限。
2. 如何动态配置多个 Agent。
3. SpringBoot / FastAPI / Agent Runtime 之间的权限矩阵如何设计。

---

## 1. 背景与目标

当前 PyClaw 已经具备基础 Agent 执行能力：

```text
/v1/agent/run
  -> AgentRunRequest
  -> build_policy()
  -> build_tool_registry(policy)
  -> Agent
  -> AgentSession.run_prompt()
```

当前飞书等 channel 场景也已经具备基础入口：

```text
channel webhook
  -> build_channel_agent_session()
  -> load_channel_agent_config()
  -> build_tool_registry(policy)
  -> AgentSession
```

但当前实现更接近“单 Agent + 环境变量配置”：

```text
OPENCLAW_CHANNEL_TOOL_PROFILE
OPENCLAW_CHANNEL_MODEL
OPENCLAW_CHANNEL_SYSTEM
OPENCLAW_CHANNEL_SHELL_APPROVAL
```

它尚未具备原生 OpenClaw 的这些能力：

```text
agents.list
bindings[]
resolveAgentRoute()
per-agent tools
per-agent runtime
Gateway method 权限表
运行时 conversation binding
```

本方案的目标是把 PyClaw 从：

```text
一个 channel 对应一个全局 Agent 配置
```

演进为：

```text
一个 channel / 群 / 用户 / 角色 / trigger 可动态路由到多个 Agent，
每个 Agent 有独立模型、角色、工具权限、运行时策略和审计边界。
```

---

## 2. 原生 OpenClaw 关键实现参考

### 2.1 路由解析

原生 OpenClaw 的生产路由入口是：

```text
src/routing/resolve-route.ts
```

核心类型：

```ts
ResolveAgentRouteInput
ResolvedAgentRoute
```

核心流程：

```text
输入 RouteContext
  channel
  accountId
  peer
  parentPeer
  guildId
  teamId
  memberRoleIds
    ↓
读取 cfg.bindings[]
    ↓
按 channel + accountId 过滤并建索引
    ↓
按层级匹配
    ↓
输出 ResolvedAgentRoute
```

匹配层级：

```text
binding.peer
binding.peer.parent
binding.peer.wildcard
binding.guild+roles
binding.guild
binding.team
binding.account
binding.channel
default
```

### 2.2 Agent 与 Binding 配置

原生 OpenClaw 的 Agent 配置位于：

```text
src/config/types.agents.ts
```

核心结构：

```ts
AgentsConfig = {
  defaults?: AgentDefaultsConfig;
  list?: AgentConfig[];
}
```

单个 Agent 包含：

```text
id
name
description
workspace
model
skills
identity
groupChat
subagents
sandbox
tools
runtime
```

Binding 结构：

```ts
AgentRouteBinding = {
  type?: "route";
  agentId: string;
  match: AgentBindingMatch;
  session?: {
    dmScope?: DmScope;
  };
}
```

其中 `AgentBindingMatch` 支持：

```text
channel
accountId
peer.kind / peer.id
guildId
teamId
roles
```

### 2.3 Gateway method 权限表

原生 OpenClaw 的 Gateway method 权限表位于：

```text
src/gateway/methods/core-descriptors.ts
```

典型 method：

```text
agents.list
agents.create
agents.update
agents.delete
config.get
config.patch
config.apply
sessions.list
sessions.send
tools.catalog
tools.effective
tools.invoke
channels.status
channels.start
channels.stop
```

每个 method 有 scope：

```text
operator.read
operator.write
operator.admin
operator.approvals
operator.pairing
node
dynamic
```

这说明原生 OpenClaw 把 Gateway 当作统一控制面入口：

```text
method
  -> scope
  -> authorization
  -> handler
```

PyClaw 中 SpringBoot 已经具备用户、JWT、API token 和 `@PreAuthorize`，因此更适合承接这部分控制面权限。

---

## 3. PyClaw 当前实现现状

### 3.1 工具权限已经有基础能力

当前 PyClaw 已有：

```text
openclaw/tools/policy.py
openclaw/tools/builder.py
openclaw/tools/catalog.py
```

核心流程：

```text
CORE_TOOL_CATALOG
  -> materialize_core_tools()
  -> apply_tool_policy()
  -> build_tool_registry()
  -> Agent(tools=registry)
```

当前 `ToolPolicy`：

```python
class ToolPolicy:
    allow: set[str] | None = None
    deny: set[str] = field(default_factory=set)
    profile: ToolProfile = "coding"
    also_allow: set[str] = field(default_factory=set)
    workspace_only: bool = True
    readonly: bool = False
```

当前 profile：

```text
minimal
readonly
coding
messaging
full
```

### 3.2 Channel Agent 配置仍是全局配置

当前 channel Agent 配置来自：

```text
openclaw/channels/config.py
```

主要环境变量：

```text
OPENCLAW_CHANNEL_PROVIDER
OPENCLAW_CHANNEL_MODEL
OPENCLAW_CHANNEL_SYSTEM
OPENCLAW_CHANNEL_TOOL_PROFILE
OPENCLAW_CHANNEL_SHELL_APPROVAL
```

这意味着飞书群当前更像：

```text
所有飞书消息
  -> 同一份 ChannelAgentConfig
  -> 同一套 tool_profile
  -> 同一个 Agent 创建逻辑
```

### 3.3 SpringBoot 已有控制面雏形

当前 SpringBoot 已有：

```text
用户管理
登录认证
JWT
API Token
Provider 管理
Channel 管理
审计
```

当前用户权限采用 authority 字符串：

```text
user:manage
provider:manage
channel:manage
agent:run
audit:read
token:manage_self
```

但当前还没有：

```text
Agent 配置管理
Agent ToolPolicy 管理
Route Binding 管理
Feishu 用户身份映射
按用户能力限制可授予的工具权限
```

---

## 4. 推荐总体架构

推荐把原生 OpenClaw Gateway 拆成两个面：

```text
SpringBoot = Gateway Control Plane
FastAPI    = Gateway Runtime Plane + Route Resolver
```

整体架构：

```text
前端控制台
  ↓
SpringBoot
  用户/权限/Provider/Channel/Agent/ToolPolicy/RouteBinding/审计
  ↓
数据库
  users
  providers
  channels
  agents
  agent_tool_policies
  route_bindings
  audit_logs

飞书 / Webhook / WebSocket / SDK
  ↓
FastAPI
  Channel Adapter
  RouteContext Extractor
  resolve_agent_route()
  AgentConfig Loader
  ToolPolicy Builder
  Agent Runtime
  ↓
Channel Adapter 回传
```

### 4.1 SpringBoot 职责

SpringBoot 负责控制面：

```text
用户认证
用户权限
API token
Provider 配置
Channel 配置
Agent 配置
Agent 工具权限配置
Route Binding 配置
权限矩阵校验
审计日志
```

它不直接执行 Agent，也不直接构造 Python `ToolRegistry`。

### 4.2 FastAPI 职责

FastAPI 负责运行面：

```text
接收 channel webhook
提取 RouteContext
调用 resolve_agent_route()
按 agentId 加载 AgentConfig
由 AgentConfig 构造 ToolPolicy
由 ToolPolicy 构造 ToolRegistry
创建或复用 AgentSession
执行 Agent
把结果回传 channel
```

### 4.3 为什么不把全部 Gateway 放到 SpringBoot

因为当前 Agent Runtime 和工具系统都在 Python：

```text
Agent
AgentSession
ToolPolicy
ToolRegistry
ToolDefinition
工具执行上下文
```

如果让 SpringBoot 直接承担运行时 Gateway，会出现大量跨语言反调：

```text
SpringBoot 收消息
  -> SpringBoot 路由
  -> 调 FastAPI 执行
  -> FastAPI 再构造工具
  -> FastAPI 回传给 SpringBoot
  -> SpringBoot 回传飞书
```

这样链路更长。更自然的拆法是：

```text
SpringBoot 管配置和权限。
FastAPI 收消息、路由和执行。
```

---

## 5. 动态配置 Agent 工具权限

### 5.1 设计原则

工具权限应该绑定到 Agent，而不是只绑定到全局 channel。

目标模型：

```text
AgentConfig
  -> ToolPolicyConfig
  -> ToolPolicy
  -> ToolRegistry
  -> Agent
```

也就是说，每个 Agent 都有自己的工具权限。

### 5.2 数据模型

建议新增表：`agents`。

核心字段：

```text
id
agent_key
name
description
enabled
provider_id
model
system_prompt
workspace_dir
runtime_type
created_by
created_at
updated_at
```

建议新增表：`agent_tool_policies`。

核心字段：

```text
id
agent_id
profile
tools_allow
tools_deny
tools_also_allow
workspace_only
readonly
shell_approval
web_access
created_at
updated_at
```

其中：

```text
profile:
  minimal / readonly / coding / messaging / full

tools_allow:
  JSON array，可空。
  如果非空，则进入显式 allow 模式。

tools_deny:
  JSON array。
  最终移除这些工具。

tools_also_allow:
  JSON array。
  在 profile 基础上额外允许。

shell_approval:
  deny / require / auto
```

### 5.3 FastAPI 侧转换

SpringBoot 对 FastAPI 暴露内部接口：

```text
GET /api/internal/agents/{agentKey}/runtime-config
```

返回：

```json
{
  "agentId": "ops",
  "provider": "openai",
  "model": "gpt-5",
  "system": "你是运维助手...",
  "workspaceDir": "D:/projects/personal/pyclaw",
  "toolPolicy": {
    "profile": "readonly",
    "allow": null,
    "deny": ["shell", "exec"],
    "alsoAllow": [],
    "workspaceOnly": true,
    "readonly": true,
    "shellApproval": "deny"
  }
}
```

FastAPI 转成现有 `ToolPolicy`：

```python
def build_tool_policy_from_agent(agent: AgentRuntimeConfig) -> ToolPolicy:
    profile = agent.tool_policy.profile
    return ToolPolicy(
        profile=profile,
        allow=set(agent.tool_policy.allow) if agent.tool_policy.allow is not None else None,
        deny=set(agent.tool_policy.deny or []),
        also_allow=set(agent.tool_policy.also_allow or []),
        workspace_only=agent.tool_policy.workspace_only,
        readonly=agent.tool_policy.readonly or profile == "readonly",
    )
```

然后沿用现有能力：

```python
policy = build_tool_policy_from_agent(agent_config)
tools = build_tool_registry(policy)
agent = Agent(..., tools=tools, readonly=policy.readonly)
```

### 5.4 工具权限动态生效策略

建议第一阶段采用“每次创建 AgentSession 时读取最新配置”：

```text
message
  -> resolve agentId
  -> load runtime config
  -> build ToolPolicy
  -> build ToolRegistry
```

优点：

```text
实现简单
权限变更立即对新会话生效
不需要复杂热重载
```

第二阶段再做缓存：

```text
agent runtime config cache
  key: agentId
  value: config + version
  invalidation: updatedAt/version
```

SpringBoot 在返回 runtime config 时带上：

```text
configVersion
updatedAt
```

FastAPI 缓存只在版本未变化时复用。

### 5.5 工具权限审计

每次 Agent 运行时建议记录：

```text
agentId
sessionKey
toolProfile
toolsEffective
toolsDenied
shellApproval
routeMatchedBy
actor/channel/user
```

这可以用 `apply_tool_policy_pipeline()` 的 audit 结果生成：

```text
profile stage 移除了哪些
readonly stage 移除了哪些
deny stage 移除了哪些
```

---

## 6. 动态配置多个 Agent

### 6.1 目标能力

目标是在同一个飞书群中支持多个 Agent：

```text
@ops  查一下部署状态
@dev  解释这段代码
@doc  总结这份文档
```

不同 Agent 拥有不同角色和工具权限：

```text
ops:
  role: 运维助手
  toolProfile: full 或 readonly+特定平台工具

dev:
  role: 代码助手
  toolProfile: coding
  deny: shell/exec

doc:
  role: 文档助手
  toolProfile: readonly
```

### 6.2 RouteContext 标准模型

FastAPI channel adapter 收到消息后，应统一提取：

```python
class RoutePeer(BaseModel):
    kind: Literal["direct", "group", "channel", "thread"]
    id: str


class RouteContext(BaseModel):
    channel: str
    account_id: str = "default"
    peer: RoutePeer | None = None
    parent_peer: RoutePeer | None = None
    guild_id: str | None = None
    team_id: str | None = None
    member_role_ids: list[str] = []
    sender_id: str | None = None
    sender_name: str | None = None
    text: str | None = None
    mentions: list[str] = []
    command: str | None = None
```

飞书群消息可映射为：

```text
channel: feishu
account_id: default 或 app/account id
peer.kind: group
peer.id: chat_id / open_chat_id
sender_id: open_id / union_id
mentions: 被 @ 的 bot/agent alias
command: /ops、/dev 等命令
```

### 6.3 Route Binding 数据模型

建议新增表：`route_bindings`。

核心字段：

```text
id
enabled
priority
agent_id
channel
account_id
peer_kind
peer_id
parent_peer_kind
parent_peer_id
guild_id
team_id
roles
sender_ids
mention_aliases
command_prefixes
dm_scope
comment
created_at
updated_at
```

其中 OpenClaw 原生字段是基础：

```text
channel
accountId
peer
guildId
teamId
roles
session.dmScope
```

PyClaw 可以额外加入更适合飞书的 trigger：

```text
mention_aliases
command_prefixes
sender_ids
```

原因是飞书群中多个 Agent 最常见的区分方式不是 Discord role，而是：

```text
@不同 Agent
/不同命令
不同群
不同发送人
```

### 6.4 路由配置示例

配置层可以等价表达为：

```yaml
agents:
  - agentKey: ops
    name: 运维助手
    toolProfile: readonly
    deny:
      - shell
      - exec

  - agentKey: dev
    name: 代码助手
    toolProfile: coding
    deny:
      - shell
      - exec

  - agentKey: admin
    name: 管理员助手
    toolProfile: full
    shellApproval: require

bindings:
  - agentId: ops
    priority: 100
    match:
      channel: feishu
      accountId: default
      peer:
        kind: group
        id: oc_xxx
      mention: ops

  - agentId: dev
    priority: 90
    match:
      channel: feishu
      accountId: default
      peer:
        kind: group
        id: oc_xxx
      commandPrefix: /dev

  - agentId: admin
    priority: 80
    match:
      channel: feishu
      accountId: default
      peer:
        kind: group
        id: oc_xxx
      senderIds:
        - ou_admin_xxx

  - agentId: ops
    priority: 10
    match:
      channel: feishu
      accountId: default
      peer:
        kind: group
        id: oc_xxx
```

含义：

```text
1. @ops 优先进入 ops。
2. /dev 优先进入 dev。
3. 管理员发送的特殊消息可进入 admin。
4. 同群默认进入 ops。
```

### 6.5 PyClaw 路由器匹配层级

建议继承原生 OpenClaw 的确定性匹配思想，并加入飞书 trigger 层。

推荐顺序：

```text
1. peer + mention
2. peer + command
3. peer + sender
4. peer exact
5. parent peer exact
6. peer wildcard
7. guild/team + roles
8. account
9. channel
10. default agent
```

这样既保留 OpenClaw 生产版路由层级，又支持飞书群里多个 Agent 的自然用法。

### 6.6 路由结果模型

FastAPI 中建议实现：

```python
class ResolvedAgentRoute(BaseModel):
    agent_id: str
    channel: str
    account_id: str
    session_key: str
    main_session_key: str
    dm_scope: Literal[
        "main",
        "per-peer",
        "per-channel-peer",
        "per-account-channel-peer",
    ]
    last_route_policy: Literal["main", "session"]
    matched_by: str
    binding_id: str | None = None
```

其中：

```text
agent_id:
  最终选择哪个 Agent。

session_key:
  当前消息进入哪个会话记忆。

main_session_key:
  Agent 主会话 key。

dm_scope:
  私聊/群聊的会话隔离策略。

matched_by:
  命中哪一层规则，用于日志和审计。
```

### 6.7 session key 策略

建议实现类似原生 OpenClaw 的 session key：

```text
main:
  agent:<agentId>:main

per-peer:
  agent:<agentId>:<peerKind>:<peerId>

per-channel-peer:
  agent:<agentId>:<channel>:<peerKind>:<peerId>

per-account-channel-peer:
  agent:<agentId>:<channel>:<accountId>:<peerKind>:<peerId>
```

对于飞书群：

```text
agent:ops:feishu:default:group:oc_xxx
agent:dev:feishu:default:group:oc_xxx
```

这样同一个群中不同 Agent 的上下文不会互相污染。

---

## 7. 权限矩阵

权限矩阵需要分三层：

```text
系统 API 权限
Agent 配置授权权限
Agent 运行时工具权限
```

### 7.1 系统 API 权限

系统 API 权限控制“用户能调用哪些 SpringBoot 控制面接口”。

建议 authority：

| Authority | 含义 |
| --- | --- |
| `user:manage` | 管理控制台用户 |
| `token:manage_self` | 管理自己的 API Token |
| `provider:manage` | 管理模型 Provider |
| `channel:manage` | 管理飞书/微信等 Channel |
| `agent:read` | 查看 Agent |
| `agent:create` | 创建 Agent |
| `agent:update` | 修改 Agent |
| `agent:delete` | 删除 Agent |
| `agent:run` | 运行 Agent |
| `agent:route:manage` | 管理 Agent 路由绑定 |
| `tool:catalog:read` | 查看工具目录 |
| `audit:read` | 查看审计日志 |
| `approval:resolve` | 处理高风险操作审批 |

### 7.2 工具授权权限

工具授权权限控制“用户最多能给 Agent 配到什么工具级别”。

建议 authority：

| Authority | 可授予能力 |
| --- | --- |
| `tool:grant:minimal` | 可创建 minimal Agent |
| `tool:grant:readonly` | 可创建 readonly Agent |
| `tool:grant:messaging` | 可创建 messaging Agent |
| `tool:grant:coding` | 可创建 coding Agent |
| `tool:grant:full` | 可创建 full Agent |
| `tool:grant:shell` | 可允许 shell/exec |
| `tool:grant:web` | 可允许 web_fetch/web_search |
| `tool:deny:override` | 可移除默认 deny 限制 |

创建或修改 Agent 时，SpringBoot 必须校验：

```text
用户想给 Agent 配置的 toolProfile
  <= 用户拥有的 tool:grant:* 能力
```

例如：

```text
用户只有 tool:grant:readonly
  不能创建 coding/full Agent。

用户有 tool:grant:coding
  可以创建 coding Agent，
  但如果没有 tool:grant:shell，
  仍不能 alsoAllow shell/exec。

用户有 tool:grant:full
  可以创建 full Agent，
  但 shellApproval 建议仍默认为 require。
```

### 7.3 Route Binding 管理权限

Route Binding 决定“哪个群/用户/命令会进入哪个 Agent”，属于高影响配置。

建议：

| 操作 | 需要权限 |
| --- | --- |
| 查看 route bindings | `agent:read` |
| 新增/修改/删除 route binding | `agent:route:manage` |
| 将 binding 指向 full Agent | `agent:route:manage` + `tool:grant:full` |
| 将全 channel 默认路由指向某 Agent | `agent:route:manage` + `channel:manage` |
| 将 sender/role 规则指向高权限 Agent | `agent:route:manage` + 对应 `tool:grant:*` |

### 7.4 Agent 运行权限

运行权限控制“谁能触发 Agent”。

需要区分两类 actor：

```text
控制台用户
Channel 外部用户，例如飞书用户
```

控制台用户：

```text
SpringBoot JWT / API Token
  -> agent:run
```

飞书用户：

```text
Feishu open_id / union_id
  -> channel identity mapping
  -> optional internal user
  -> route binding allowed sender/role
```

第一阶段可以不把飞书用户映射到控制台用户，只按 route binding 控制：

```text
这个群允许触发哪些 Agent
哪些 senderIds 可以触发高权限 Agent
```

第二阶段再加入：

```text
feishu_identities
  feishu_open_id
  feishu_union_id
  user_id
  display_name
```

### 7.5 Method 权限与 Tool 权限的关系

需要明确两个概念：

```text
Method 权限:
  控制客户端/用户能调用哪些系统 API。

Tool 权限:
  控制 Agent 运行时能调用哪些工具。
```

示例：

```text
用户有 agent:create
但没有 tool:grant:full
  -> 可以创建 Agent
  -> 不能把 Agent 配成 full。

Agent 被配置成 readonly
即使触发者是管理员
  -> Agent 运行时仍只能看到 readonly 工具。

用户有 tool:grant:full
但没有 agent:route:manage
  -> 可以创建 full Agent
  -> 不能把飞书群默认路由切到这个 Agent。
```

---

## 8. SpringBoot API 设计建议

### 8.1 Agent 管理 API

```text
GET    /api/agents
GET    /api/agents/{id}
POST   /api/agents
PUT    /api/agents/{id}
DELETE /api/agents/{id}
```

权限：

```text
GET    -> agent:read
POST   -> agent:create
PUT    -> agent:update
DELETE -> agent:delete
```

创建/修改时额外校验：

```text
toolProfile
toolsAllow
toolsAlsoAllow
toolsDeny
shellApproval
```

### 8.2 工具目录 API

```text
GET /api/tools/catalog
GET /api/tools/profiles
POST /api/tools/effective
```

用途：

```text
前端展示工具列表
前端展示 profile 会启用哪些工具
前端预览某个 ToolPolicy 的最终工具集合
```

`POST /api/tools/effective` 可以由 SpringBoot 调 FastAPI，复用 Python 侧 `apply_tool_policy_pipeline()`。

### 8.3 Route Binding API

```text
GET    /api/route-bindings
POST   /api/route-bindings
PUT    /api/route-bindings/{id}
DELETE /api/route-bindings/{id}
```

权限：

```text
agent:route:manage
```

建议支持冲突检测：

```text
同 channel/account/peer/mention/command 不能同时指向多个 Agent，
除非显式 priority 允许覆盖。
```

### 8.4 FastAPI 内部运行配置 API

SpringBoot 给 FastAPI 的内部接口：

```text
GET /api/internal/agents/{agentKey}/runtime-config
GET /api/internal/route-bindings/runtime
GET /api/internal/channels/{channel}/runtime-config
```

这些接口不面向普通用户，只允许内部 token：

```text
Authorization: Bearer ${OPENCLAW_INTERNAL_API_TOKEN}
```

---

## 9. FastAPI 模块设计建议

建议新增模块：

```text
openclaw/routing/models.py
openclaw/routing/session_key.py
openclaw/routing/resolve_route.py
openclaw/agents/config.py
openclaw/agents/runtime_config_client.py
```

### 9.1 models.py

负责定义：

```text
RoutePeer
RouteContext
AgentBindingMatch
AgentRouteBinding
ResolvedAgentRoute
```

### 9.2 session_key.py

负责：

```text
build_agent_main_session_key()
build_agent_peer_session_key()
normalize_agent_id()
normalize_account_id()
```

### 9.3 resolve_route.py

负责：

```text
normalize_context()
resolve_agent_route()
build_binding_index()
matches_binding_scope()
```

### 9.4 runtime_config_client.py

负责从 SpringBoot 拉取：

```text
AgentRuntimeConfig
RouteBindingRuntimeConfig
ChannelRuntimeConfig
```

第一阶段可以每次请求拉取。

第二阶段加：

```text
TTL cache
version cache
manual reload endpoint
```

---

## 10. 前端页面设计建议

当前“用户与权限”页只管理用户 authority 字符串。

建议后续拆成四个页面：

```text
用户与权限
Agent 管理
工具权限
路由绑定
```

### 10.1 用户与权限

继续管理：

```text
用户
状态
authorities
API token
```

后续可把 raw CSV 改为复选框。

### 10.2 Agent 管理

字段：

```text
Agent Key
名称
描述
Provider
Model
System Prompt
Workspace
启用状态
```

### 10.3 工具权限

字段：

```text
profile
allow
deny
alsoAllow
readonly
workspaceOnly
shellApproval
effective tools 预览
```

### 10.4 路由绑定

字段：

```text
channel
accountId
peerKind
peerId
mentionAlias
commandPrefix
senderIds
agent
priority
dmScope
enabled
```

---

## 11. 审计与安全

### 11.1 配置审计

所有控制面写操作应记录：

```text
actor
action
targetType
targetId
before
after
createdAt
```

包括：

```text
Agent 创建/修改/删除
ToolPolicy 修改
RouteBinding 修改
Channel 配置修改
Provider 配置修改
```

### 11.2 运行审计

每次 channel 消息运行应记录：

```text
channel
accountId
peer
sender
agentId
sessionKey
matchedBy
bindingId
toolProfile
effectiveTools
startTime
endTime
status
```

### 11.3 高风险工具保护

建议默认：

```text
shell / exec:
  只允许 full profile
  需要 tool:grant:shell
  shellApproval 默认 require

web_fetch / web_search:
  需要 tool:grant:web
  可按部署环境决定是否默认禁用

write / edit / apply_patch:
  需要 coding 或 full
  workspace_only 默认 true
```

---

## 12. 分阶段落地路线

### 阶段 1：Agent Registry + ToolPolicy 动态化

目标：

```text
SpringBoot 可管理多个 Agent。
每个 Agent 可配置 toolProfile / allow / deny / alsoAllow。
FastAPI 可按 agentId 加载配置并构造 ToolPolicy。
```

改动：

```text
SpringBoot:
  agents 表
  agent_tool_policies 表
  AgentController
  internal runtime config API

FastAPI:
  AgentRuntimeConfig model
  Spring runtime config client
  build_channel_agent_session() 改为按 agentId 构造 Agent
```

### 阶段 2：Route Binding + resolve_agent_route()

目标：

```text
飞书群可按群、@、命令、发送人路由到不同 Agent。
```

改动：

```text
SpringBoot:
  route_bindings 表
  RouteBindingController
  route binding conflict check

FastAPI:
  RouteContext extractor
  resolve_agent_route()
  session key builder
```

### 阶段 3：权限矩阵落地

目标：

```text
用户能配置什么 Agent、能授予什么工具权限、能修改什么路由，都受 authority 控制。
```

改动：

```text
SpringBoot:
  authority 常量化
  Agent create/update 权限校验
  RouteBinding 指向高权限 Agent 的校验
  审计日志

前端:
  用户权限复选框
  Agent 工具权限编辑器
  effective tools 预览
```

### 阶段 4：运行时 binding 与缓存

目标：

```text
会话绑定可持久化。
线程/群会话可稳定进入同一个 session。
配置可缓存并按 version 失效。
```

改动：

```text
FastAPI:
  SessionBindingService
  config cache with version
  per-session lock / queue
```

---

## 13. 最终结论

PyClaw 迁移原生 OpenClaw 网关能力时，不建议简单理解为：

```text
SpringBoot = Gateway
FastAPI = Router
```

更准确的职责划分是：

```text
SpringBoot = Gateway Control Plane
FastAPI = Gateway Runtime Plane + Route Resolver
```

动态工具权限的核心是：

```text
AgentConfig
  -> ToolPolicyConfig
  -> ToolPolicy
  -> build_tool_registry()
```

动态多 Agent 的核心是：

```text
RouteContext
  -> route_bindings
  -> resolve_agent_route()
  -> ResolvedAgentRoute.agentId
  -> AgentRuntimeConfig
```

权限矩阵的核心是分层：

```text
系统 API 权限:
  用户能调用哪些控制面接口。

工具授权权限:
  用户最多能给 Agent 授予什么工具能力。

Agent 运行时工具权限:
  Agent 实际能看到和调用哪些工具。
```

当前 PyClaw 已经具备最重要的底座：

```text
ToolPolicy
ToolRegistry
AgentRunRequest
ChannelRuntimeConfig
SpringBoot authority
```

下一步最应该补的是：

```text
agents 表
agent_tool_policies 表
route_bindings 表
FastAPI resolve_agent_route()
SpringBoot 权限矩阵校验
```

完成这些后，飞书群中引入多个 Agent、每个 Agent 拥有不同角色和工具权限，就可以形成稳定、可审计、可扩展的生产架构。

---

## 14. Provider Options 与 Agent 绑定权限补充

本次实现补充了一个脱敏 Provider 选择接口，用于支持 `agent:read` / `agent:update` 用户在 Agents 页面选择已有 Provider Config，而不需要授予 `provider:manage`。

### 14.1 新增接口

```text
GET /api/providers/options
```

权限：

```text
provider:manage 或 agent:read 或 agent:update
```

返回字段：

```json
[
  {
    "id": "provider-uuid",
    "name": "deepseek-main",
    "providerType": "openai-compatible",
    "model": "deepseek-chat",
    "apiMode": "chat_completions",
    "enabled": true
  }
]
```

该接口不返回以下敏感或管理字段：

```text
apiKey
baseUrl
secretRef
apiKeyLast4
createdAt
updatedAt
```

### 14.2 权限语义

`provider:manage` 仍然表示完整 Provider 管理权限，包括新增、修改、删除 Provider 以及更新 API Key。

`agent:update` 表示可以修改 Agent 配置，包括 `providerId` 绑定关系；但它不应自动拥有 Provider 密钥管理能力。

因此 Agents 页面使用 `/api/providers/options` 加载 Provider Config 下拉框，Providers 页面仍使用 `/api/providers` 完整管理接口。

### 14.3 前端行为

Agents 页面现在会：

```text
1. 调用 GET /api/providers/options 加载脱敏 Provider 选择列表。
2. 在 Provider Config 下拉框中显示 Provider name + model。
3. 保存 Agent 时提交 providerId。
4. 在 Agents 表格中展示 providerConfigName，便于确认 Agent 是否绑定到具体 Provider。
```

这样普通 Agent 管理员无需 `provider:manage`，也可以完成 Agent 到已有 Provider 的绑定；同时不会接触 DeepSeek/OpenAI API Key。