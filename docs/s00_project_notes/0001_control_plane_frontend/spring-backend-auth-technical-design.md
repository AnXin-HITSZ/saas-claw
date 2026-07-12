# Spring Boot 后端网站与 pyclaw 鉴权技术方案

本文档说明如何使用 Spring Boot 实现 pyclaw 的后端网站、管理后台与统一鉴权层。目标是让 pyclaw 从“公网可直接调用的 Agent API”演进为“受控、安全、可管理、可审计的 Agent 平台”。

## 1. 背景

当前 pyclaw 已经通过 K3s + Helm 部署，并通过 Ingress 暴露了公网 HTTP 入口：

```text
http://api.anxin-hitsz.com/healthz
http://api.anxin-hitsz.com/v1/agent/run
```

公网访问已打通后，`/v1/agent/run` 如果不加鉴权，任何知道 URL 的人都可以向 pyclaw 发起 Agent 调用。外部调用者虽然拿不到 `OPENAI_API_KEY`，但 pyclaw 会使用集群 Secret 中配置的 Key 调用 DeepSeek / OpenAI-compatible 服务，最终消耗的是你的模型额度。

因此需要增加鉴权层。

## 2. ClusterIP 是什么

Kubernetes Service 有多种类型。当前建议 pyclaw-api 使用：

```text
Service type: ClusterIP
```

含义是：

```text
pyclaw-api 只在 K8s 集群内部暴露一个虚拟 IP。
集群外部用户不能直接访问这个 Service。
只有同一个 K8s 集群内的 Pod，例如 Spring Boot 后端，才能通过 Service 名访问它。
```

例如：

```text
K3s 集群内部：
  http://pyclaw.pyclaw.svc.cluster.local:8000/v1/agent/run

公网外部：
  不能直接访问 ClusterIP
```

这和 Ingress 的关系是：

```text
Ingress: 决定哪些服务暴露给公网
ClusterIP Service: 给集群内部服务互相访问
```

长期推荐：

```text
公网只暴露 Spring Boot
pyclaw-api 保持 ClusterIP，只允许集群内访问
```

这样外部请求必须先经过 Spring Boot 的登录、鉴权、限流和审计，再由 Spring Boot 调用 pyclaw。

## 3. 推荐总体架构

```text
Browser / Admin UI / Client / WeChat / Feishu
        |
        | HTTPS
        v
Spring Boot Backend
  - 登录
  - 用户管理
  - JWT / Session
  - RBAC 权限
  - API Token 管理
  - LLM Provider 配置
  - 微信 / 飞书渠道配置
  - 用量统计
  - 审计日志
  - 请求限流
        |
        | internal HTTP + service token
        v
pyclaw-api
  - Agent 执行
  - LLM Provider 调用
  - Tool 调用
  - Session / Transcript
  - Channel Runtime
        |
        v
DeepSeek / OpenAI-compatible / Tools
```

K8s 中建议部署为：

```text
namespace: pyclaw

  Deployment: spring-backend
  Service: spring-backend
  Ingress: api.anxin-hitsz.com

  Deployment: pyclaw
  Service: pyclaw
    type: ClusterIP

  Secret: spring-backend-secret
  Secret: pyclaw-provider-secret
  Secret: pyclaw-internal-api-secret

  ConfigMap: spring-backend-config
  ConfigMap: pyclaw-config

  PostgreSQL / MySQL / external RDS
  Redis optional
```

## 4. 职责边界

### 4.1 Spring Boot 负责

```text
用户登录
用户注册 / 禁用 / 重置密码
JWT / Session 管理
角色和权限
API Token 生成与撤销
LLM Provider 配置管理
模型选择
微信 / 飞书连接配置
Agent 调用入口
请求限流
调用审计
用量统计
后台页面 API
对外公网入口
```

### 4.2 pyclaw 负责

```text
Agent 执行
LLM provider 调用
Tool profile 选择
工具调用
Session 上下文
Transcript 持久化
Channel runtime
微信 / 飞书底层消息适配能力
```

### 4.3 不建议的边界

不建议让 Spring Boot 直接实现 Agent loop、tool calling、transcript 解析和 LLM provider 细节。这样会导致 pyclaw 和 Spring 逻辑重复。

不建议让 pyclaw 直接承担完整用户系统、后台页面、RBAC、用量统计和运营管理。这些更适合 Spring Boot。

## 5. 鉴权模型

建议分三层鉴权。

### 5.1 用户到 Spring Boot

用于浏览器、后台页面、普通 API 调用：

```text
Authorization: Bearer <user-jwt>
```

或者后台页面使用：

```text
HttpOnly Secure Cookie Session
```

推荐：

```text
前后端分离 API：JWT
传统服务端页面：Session Cookie
```

### 5.2 外部系统到 Spring Boot

用于程序化调用：

```text
Authorization: Bearer <api-token>
```

或：

```text
X-API-Key: <api-token>
```

API Token 应存哈希，不存明文。

### 5.3 Spring Boot 到 pyclaw

即使 pyclaw 不直接暴露公网，也建议保留内部服务 token：

```text
Authorization: Bearer <internal-service-token>
```

对应 pyclaw 环境变量：

```text
PYCLAW_API_TOKEN=<internal-service-token>
```

Spring Boot 环境变量：

```text
PYCLAW_BASE_URL=http://pyclaw.pyclaw.svc.cluster.local:8000
PYCLAW_API_TOKEN=<internal-service-token>
```

这样即使 pyclaw Ingress 被误开，未带内部 token 的请求也会被拒绝。

## 6. 推荐安全策略

短期：

```text
1. pyclaw-api 自身增加 PYCLAW_API_TOKEN。
2. /healthz 不鉴权。
3. /v1/agent/run 需要 Bearer Token。
4. Ingress 暂时仍可直连 pyclaw，但必须带 token。
```

中期：

```text
1. 新增 Spring Boot 后端。
2. 公网 Ingress 指向 Spring Boot。
3. pyclaw Ingress 关闭。
4. pyclaw Service 保持 ClusterIP。
5. Spring Boot 使用内部 token 调用 pyclaw。
```

长期：

```text
1. Spring Boot 支持用户、组织、角色、配额。
2. 每个用户 / 渠道 / Agent 独立权限。
3. 所有调用有审计日志。
4. 敏感配置使用 K8s Secret 或外部密钥管理。
5. 微信 / 飞书 webhook 使用平台签名校验。
```

## 7. Spring Boot 模块设计

推荐包结构：

```text
com.anxin.pyclaw.backend
  config
    SecurityConfig
    WebConfig
    PyclawClientConfig
  auth
    AuthController
    AuthService
    JwtService
    PasswordService
    CurrentUser
  user
    UserController
    UserService
    UserEntity
    RoleEntity
  token
    ApiTokenController
    ApiTokenService
    ApiTokenEntity
  agent
    AgentController
    AgentService
    AgentRunRequest
    AgentRunResponse
  pyclaw
    PyclawClient
    PyclawAgentRunRequest
    PyclawAgentRunResponse
  provider
    ProviderController
    ProviderConfigService
    ProviderConfigEntity
  channel
    ChannelController
    WechatConfigEntity
    FeishuConfigEntity
  audit
    AuditLogEntity
    AuditLogService
  usage
    UsageRecordEntity
    UsageService
  common
    ApiResponse
    ErrorCode
    GlobalExceptionHandler
```

## 8. 关键接口设计

### 8.1 登录

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "..."
}
```

响应：

```json
{
  "accessToken": "...",
  "expiresIn": 3600
}
```

### 8.2 获取当前用户

```http
GET /api/auth/me
Authorization: Bearer <jwt>
```

### 8.3 创建 API Token

```http
POST /api/tokens
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "name": "local-test",
  "expiresAt": "2026-12-31T23:59:59+08:00",
  "scopes": ["agent:run"]
}
```

响应只返回一次 token 明文：

```json
{
  "token": "pcat_xxx",
  "tokenId": "..."
}
```

数据库只保存 token hash。

### 8.4 调用 Agent

```http
POST /api/agent/run
Authorization: Bearer <jwt or api-token>
Content-Type: application/json

{
  "prompt": "你好",
  "provider": "openai",
  "sessionId": "web-demo",
  "toolProfile": "minimal"
}
```

Spring Boot 校验权限后调用 pyclaw：

```http
POST http://pyclaw.pyclaw.svc.cluster.local:8000/v1/agent/run
Authorization: Bearer <internal-service-token>
Content-Type: application/json

{
  "prompt": "你好",
  "provider": "openai",
  "session_id": "web-demo",
  "tool_profile": "minimal"
}
```

### 8.5 Provider 配置

```http
GET /api/providers
POST /api/providers
PUT /api/providers/{id}
DELETE /api/providers/{id}
```

配置内容：

```json
{
  "name": "deepseek-prod",
  "providerType": "openai-compatible",
  "baseUrl": "https://api.deepseek.com",
  "model": "deepseek-v4-flash",
  "apiMode": "chat_completions",
  "enabled": true
}
```

API Key 不建议明文回显。可以写入 K8s Secret、外部 KMS，或数据库加密字段。

### 8.6 渠道配置

微信：

```http
POST /api/channels/wechat/config
```

```json
{
  "name": "wechat-official-account",
  "appId": "...",
  "token": "...",
  "aesKey": "...",
  "enabled": true
}
```

飞书：

```http
POST /api/channels/feishu/config
```

```json
{
  "name": "feishu-bot",
  "appId": "...",
  "verificationToken": "...",
  "encryptKey": "...",
  "enabled": true
}
```

## 9. 数据库表设计

### 9.1 users

```sql
create table users (
  id varchar(64) primary key,
  username varchar(128) not null unique,
  password_hash varchar(255) not null,
  display_name varchar(128),
  status varchar(32) not null,
  created_at timestamp not null,
  updated_at timestamp not null
);
```

### 9.2 roles

```sql
create table roles (
  id varchar(64) primary key,
  name varchar(128) not null unique,
  description varchar(255)
);
```

### 9.3 user_roles

```sql
create table user_roles (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  primary key (user_id, role_id)
);
```

### 9.4 api_tokens

```sql
create table api_tokens (
  id varchar(64) primary key,
  user_id varchar(64) not null,
  name varchar(128) not null,
  token_hash varchar(255) not null,
  scopes text not null,
  expires_at timestamp,
  revoked_at timestamp,
  created_at timestamp not null,
  last_used_at timestamp
);
```

### 9.5 provider_configs

```sql
create table provider_configs (
  id varchar(64) primary key,
  name varchar(128) not null,
  provider_type varchar(64) not null,
  base_url varchar(512),
  model varchar(128) not null,
  api_mode varchar(64) not null,
  secret_ref varchar(255),
  enabled boolean not null,
  created_at timestamp not null,
  updated_at timestamp not null
);
```

### 9.6 channel_configs

```sql
create table channel_configs (
  id varchar(64) primary key,
  channel_type varchar(64) not null,
  name varchar(128) not null,
  config_json text not null,
  secret_ref varchar(255),
  enabled boolean not null,
  created_at timestamp not null,
  updated_at timestamp not null
);
```

### 9.7 audit_logs

```sql
create table audit_logs (
  id varchar(64) primary key,
  actor_type varchar(32) not null,
  actor_id varchar(64),
  action varchar(128) not null,
  resource_type varchar(64),
  resource_id varchar(64),
  request_id varchar(128),
  ip_address varchar(64),
  user_agent varchar(512),
  success boolean not null,
  error_message text,
  created_at timestamp not null
);
```

### 9.8 usage_records

```sql
create table usage_records (
  id varchar(64) primary key,
  user_id varchar(64),
  session_id varchar(128),
  provider varchar(64),
  model varchar(128),
  prompt_tokens bigint,
  completion_tokens bigint,
  total_tokens bigint,
  success boolean not null,
  latency_ms bigint,
  created_at timestamp not null
);
```

## 10. Spring Security 设计

建议使用：

```text
Spring Boot 3.x
Spring Security 6.x
JWT
BCrypt / Argon2 password hash
Method Security
```

权限模型：

```text
ROLE_ADMIN
  - user:manage
  - provider:manage
  - channel:manage
  - agent:run
  - audit:read

ROLE_USER
  - agent:run
  - token:manage_self

API Token scopes
  - agent:run
  - channel:webhook:send
```

建议所有管理接口加权限：

```java
@PreAuthorize("hasAuthority('provider:manage')")
```

Agent 调用接口：

```java
@PreAuthorize("hasAuthority('agent:run')")
```

## 11. Spring 调用 pyclaw 的 Client 设计

Spring Boot 内部封装 `PyclawClient`：

```text
PyclawClient.runAgent(request)
  -> 设置 Authorization: Bearer <internal-service-token>
  -> POST /v1/agent/run
  -> 处理 4xx / 5xx
  -> 记录 latency
  -> 返回统一 AgentRunResponse
```

需要配置超时：

```text
connect timeout: 3s
read timeout: 60s 或更高
```

如果后续支持流式回复，可以新增：

```text
POST /api/agent/run/stream
```

并使用 WebFlux 或 SSE。

## 12. K8s 部署设计

### 12.1 Spring Backend Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: spring-backend
  namespace: pyclaw
spec:
  replicas: 1
  selector:
    matchLabels:
      app: spring-backend
  template:
    metadata:
      labels:
        app: spring-backend
    spec:
      containers:
        - name: spring-backend
          image: <acr>/pyclaw/spring-backend:<tag>
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: spring-backend-config
            - secretRef:
                name: spring-backend-secret
```

### 12.2 Spring Backend Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: spring-backend
  namespace: pyclaw
spec:
  type: ClusterIP
  selector:
    app: spring-backend
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

### 12.3 Spring Ingress

公网入口指向 Spring：

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: spring-backend
  namespace: pyclaw
spec:
  ingressClassName: traefik
  rules:
    - host: api.anxin-hitsz.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: spring-backend
                port:
                  number: 8080
```

### 12.4 pyclaw Service

pyclaw 只保留 ClusterIP：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: pyclaw
  namespace: pyclaw
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: pyclaw
  ports:
    - name: http
      port: 8000
      targetPort: 8000
```

### 12.5 pyclaw Ingress

长期应删除或关闭 pyclaw 直连 Ingress：

```yaml
ingress:
  enabled: false
```

## 13. Secret 设计

### 13.1 pyclaw-provider-secret

给 pyclaw 调模型用：

```text
OPENAI_API_KEY
OPENAI_BASE_URL
PYCLAW_API_TOKEN
```

### 13.2 aliyun-acr-pull-secret

给 K3s 拉 ACR 私有镜像用：

```text
type: kubernetes.io/dockerconfigjson
```

### 13.3 spring-backend-secret

给 Spring Boot 用：

```text
SPRING_DATASOURCE_PASSWORD
JWT_SIGNING_SECRET
PYCLAW_API_TOKEN
```

其中：

```text
pyclaw-provider-secret.PYCLAW_API_TOKEN
spring-backend-secret.PYCLAW_API_TOKEN
```

应为同一个值，表示 Spring 调用 pyclaw 的内部服务凭证。

## 14. HTTPS 方案

公网正式入口应使用 HTTPS：

```text
https://api.anxin-hitsz.com
```

推荐使用 cert-manager + Let's Encrypt：

```text
ClusterIssuer: letsencrypt-http01
Ingress TLS secret: api-anxin-hitsz-com-tls
```

微信 / 飞书回调也应使用 HTTPS URL。

## 15. 微信 / 飞书接入时的鉴权

### 15.1 微信

微信回调不适合只依赖用户 JWT。应校验微信签名：

```text
signature
timestamp
nonce
echostr
```

校验通过后才处理消息。

### 15.2 飞书

飞书事件回调需要处理：

```text
challenge
verification token
encrypt key
event_id 去重
tenant_key
```

### 15.3 与用户鉴权的关系

```text
后台页面 / 用户 API：JWT / API Token
微信 webhook：微信签名
飞书 webhook：飞书签名 / token / challenge
Spring -> pyclaw：内部服务 token
```

## 16. 一次性落地范围

本方案按一次性完整落地设计，不拆临时实现路线。目标状态应同时具备：

```text
1. pyclaw 增加 PYCLAW_API_TOKEN。
2. /v1/agent/run 强制 Bearer Token。
3. /healthz 保持公开。
4. 创建 Spring Boot 后端项目。
5. 实现登录。
6. 实现 JWT。
7. 实现 API Token 创建、识别、撤销。
8. 实现 /api/agent/run。
9. Spring 使用内部 token 调 pyclaw。
10. 实现用户管理。
11. 实现 Provider 配置管理。
12. 实现微信 / 飞书 Channel 配置管理。
13. 实现审计日志。
14. 实现用量记录。
15. api.anxin-hitsz.com Ingress 指向 Spring Boot。
16. pyclaw Ingress 关闭。
17. pyclaw Service 仅 ClusterIP。
18. 验证外部只能通过 Spring 访问 Agent。
19. 预留微信 / 飞书 webhook 的平台签名校验入口。
```

## 17. 取舍建议

如果目标是立刻防止滥用：

```text
优先在 pyclaw 加 PYCLAW_API_TOKEN。
```

如果目标是做完整产品化后端：

```text
建设 Spring Boot 后端，并让公网入口迁移到 Spring。
```

最终推荐组合：

```text
Spring Boot 做公网入口、鉴权、管理后台。
pyclaw 做 Agent Runtime。
pyclaw 自身仍保留内部 token 作为最后一道保护。
```

## 18. 当前落地状态

本设计已经按“一步到位”的目标落地到代码：

```text
1. pyclaw-api 已支持 PYCLAW_API_TOKEN。
2. /healthz 保持公开。
3. /v1/agent/run 在配置 PYCLAW_API_TOKEN 后要求 Bearer Token。
4. spring-backend 已实现 Spring Boot 3.x 后端。
5. Spring 已实现 JWT 登录、API Token、用户管理、Provider 管理、微信 / 飞书 Channel 管理。
6. Spring 已实现 /api/agent/run，并使用内部 PYCLAW_API_TOKEN 调用 pyclaw。
7. Spring 已实现审计日志与用量记录。
8. Spring 已提供 Dockerfile 与 Helm Chart。
9. 已增加基础 Spring 安全冒烟测试。
```

代码位置：

```text
spring-backend/
openclaw/api.py
helm/pyclaw/values.yaml
tests/test_api.py
```

实现记录：

```text
docs/000/0001/spring-backend-auth-implementation-log.md
```

## 19. 部署切换建议

代码已经具备完整后端入口能力，但 K3s 上线时仍需要执行部署切换：

```text
1. 生成一个新的内部服务 token。
2. 将同一个 PYCLAW_API_TOKEN 写入 pyclaw-provider-secret 和 spring-backend-secret。
3. 重新升级 pyclaw Helm release，使 pyclaw-api 开始校验内部 token。
4. 构建并推送 spring-backend 镜像到 ACR。
5. 用 spring-backend/helm 部署 Spring Boot。
6. 将 api.anxin-hitsz.com Ingress 指向 spring-backend。
7. 关闭 pyclaw 直连 Ingress，保留 pyclaw Service 为 ClusterIP。
8. 验证公网只能访问 Spring，不能直接调用 pyclaw /v1/agent/run。
```

注意：如果你已经在聊天或终端截图中暴露过真实模型 API Key，建议在服务商控制台旋转旧 Key，再把新 Key 写入 K8s Secret。
