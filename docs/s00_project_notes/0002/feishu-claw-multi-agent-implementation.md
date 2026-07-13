# 飞书 Claw 多角色 Agent 实现记录

日期：2026-07-13

## 目标

本次实现面向当前个人 demo / 小规模 SaaS 雏形，先完成两个核心能力：

1. 一个飞书群或一个飞书应用会话绑定一个 Claw。
2. 一个 Claw 内部支持多个角色 Agent，例如前端、后端、运维、产品、算法。

前端同时新增登录后的工作台首页。用户进入控制台后先看到主要入口，包括“开启我的 Claw”、“进入 Web 对话”、“部署到飞书”、“Provider 配置”。

## 总体设计

本次没有重写 Python Channel Worker，也没有替换已有 route binding 路由体系，而是在 Spring Boot 管理层新增 Claw 概念，并复用现有运行时路由。

关系如下：

```text
Claw
  - 基础配置：名称、状态、描述、默认 Agent
  - 飞书绑定：accountId、peerKind、peerId
  - 多个角色：roleKey、displayName、agentId、mentionAliases、commandPrefixes

Claw Role
  - 同步为 Route Binding
    channel = feishu
    peerKind / peerId = Claw 的飞书绑定
    mentionAliases / commandPrefixes = 角色触发条件
    agentId = 角色绑定的 Agent
```

飞书消息进入后仍然走原来的运行链路：

```text
Feishu webhook -> ingress event -> channel-worker -> resolve_agent_route -> Agent -> Feishu card reply
```

区别是 Route Binding 不再必须由管理员逐条手动维护。Claw 保存时会由后端自动生成或更新对应路由。

## 后端改动

新增包：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/claw
```

新增主要类型：

```text
ClawEntity
ClawAgentEntity
ClawRepository
ClawAgentRepository
ClawRequest
ClawRoleRequest
ClawResponse
ClawRoleResponse
ClawService
ClawController
```

新增接口：

```text
GET    /api/claws
GET    /api/claws/{id}
POST   /api/claws
PUT    /api/claws/{id}
POST   /api/claws/{id}/sync-routes
DELETE /api/claws/{id}
```

新增权限：

```text
claw:read
claw:create
claw:update
claw:delete
```

管理员启动引导权限已加入 `BootstrapDataInitializer`。已有管理员用户在服务启动后会自动补齐缺失权限。

## 数据表

在 JPA `ddl-auto=update` 下会新增两张表：

```text
claws
claw_agents
```

`claws` 保存 Claw 基础信息和飞书绑定信息。

`claw_agents` 保存 Claw 内部角色与 Agent 的绑定关系，并记录同步生成的 `routeBindingId`。

## 飞书绑定唯一性

为了满足“一个飞书群 / 应用绑定一个 Claw”，后端在创建或更新 Claw 时增加了唯一性校验：

```text
feishuEnabled = true
feishuAccountId
feishuPeerKind
feishuPeerId
```

同一个飞书 peer 不能同时绑定到两个不同 Claw。如果发生冲突，接口返回 `409 Conflict`。

这里没有直接增加数据库唯一索引，是因为 `feishuAccountId` 可以为空，且当前仍处于 demo/演进阶段。后续如果进入正式多租户环境，可以把该约束下沉到数据库唯一索引或迁移脚本中。

## 路由同步规则

当 Claw 启用飞书绑定且 `feishuPeerId` 存在时，每个启用状态的角色都会同步成一条 `route_bindings` 记录。

同步字段规则：

```text
route.channel = feishu
route.clawId = Claw.id
route.agentId = ClawRole.agentId
route.accountId = Claw.feishuAccountId
route.peerKind = Claw.feishuPeerKind，默认 group
route.peerId = Claw.feishuPeerId
route.dmScope = per-account-channel-peer
```

默认角色使用空的 `mentionAliases` 和 `commandPrefixes`，作为该飞书群的兜底 Agent。

非默认角色使用自己的 `mentionAliases` 和 `commandPrefixes`，例如：

```text
前端 Agent: mentionAliases = ["前端"], commandPrefixes = ["/frontend", "前端"]
后端 Agent: mentionAliases = ["后端"], commandPrefixes = ["/backend", "后端"]
运维 Agent: mentionAliases = ["运维"], commandPrefixes = ["/ops", "运维"]
```

Route Binding 解析时优先命中更具体的角色路由，再回落到默认角色。

## Route Binding 扩展

Route Binding 增加了可选字段：

```text
clawId
```

涉及文件：

```text
RouteBindingEntity.java
RouteBindingRequest.java
RouteBindingResponse.java
RouteBindingService.java
```

这个字段用于追踪某条路由由哪个 Claw 管理。手工创建的旧路由可以没有 `clawId`。

## 前端改动

文件：

```text
pyclaw-web/src/App.vue
```

新增导航项：

```text
Home
Claws
```

Home 页面提供四个入口：

```text
开启我的 Claw
进入 Web 对话
部署到飞书
Provider 配置
```

Claws 页面提供：

```text
创建 Claw
编辑 Claw
配置飞书 accountId / peerKind / peerId
配置多个角色 Agent
配置角色触发词和命令前缀
同步路由
删除 Claw
```

Route Binding 表单和表格也增加了 `clawId` 字段，便于管理员排查某条路由是否由 Claw 自动管理。

## 当前边界

本次实现的是管理层和路由层的一步到位改造，但还没有实现每个 Claw 独立 Pod 沙箱。

当前状态：

```text
Claw = 配置 + 多角色 Agent + 飞书绑定 + 自动 Route Binding
```

尚未完成：

```text
Claw = 配置 + workspace + 独立 sandbox runner Pod
```

后续如果继续做“创建 Claw 时创建 Pod”，可以在 Claw 创建成功后调用 Sandbox Orchestrator 创建：

```text
Namespace: user-{userId}
PVC: workspace-claw-{clawId}
Deployment: sandbox-runner-claw-{clawId}
Service: sandbox-runner-claw-{clawId}
```

## Web 端对话边界

当前 Web 端仍复用已有 Agent Playground。它可以用来和 Agent 对话，但还不是 Claw 维度的多会话 Web Chat。

后续要做 Web 端 Claw 会话，应新增：

```text
claw_sessions
claw_messages
```

并增加接口：

```text
GET  /api/claws/{id}/sessions
POST /api/claws/{id}/sessions
POST /api/claws/{id}/sessions/{sessionId}/messages
```

这样 Web 页面、飞书 Channel、未来企业微信/钉钉 Channel 都可以复用同一个 Claw 路由模型。

## 验证记录

已执行前端构建：

```text
npm run build
```

已执行 Python Channel 测试：

```text
py -m unittest tests.test_channel_platforms tests.test_channels
```

已执行差异检查：

```text
git diff --check
```

本机没有全局 Maven，因此没有执行 `mvn test`。已使用本地 Maven 缓存中的依赖和 `javac` 对本次新增/修改的 Java 源码做编译检查。

## 部署注意事项

部署 Spring Backend 后，需要确认管理员权限已补齐：

```text
claw:read
claw:create
claw:update
claw:delete
```

如果管理员登录后仍看不到 Claws 页面，应先重新登录，确保 JWT 中包含新权限。

创建 Claw 时，需要先准备好对应角色的 Agent 配置。每个角色选择一个已有 Agent，然后填写飞书 peer 信息并保存。保存后后端会自动同步 Route Binding。