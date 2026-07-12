# pyclaw Spring Backend API Contract

本文档面向前端页面生成与联调，描述 `spring-backend` 当前已经实现的 HTTP API、鉴权方式、权限点、请求/响应结构和推荐页面。

当前前端状态：

```text
已有：spring-backend/src/main/resources/static/index.html
性质：最小调试页
能力：登录、保存 JWT 到 localStorage、调用 /api/agent/run
不足：不是完整管理后台，没有用户、Token、Provider、Channel、审计、用量等交互页面
```

因此后续需要生成一个正式前端管理台时，应以本文档为接口依据。

## 1. 基础信息

本地默认地址：

```text
http://localhost:8080
```

K3s 公网入口预期：

```text
https://api.anxin-hitsz.com
```

统一 JSON 请求头：

```http
Content-Type: application/json
```

除公开接口外，统一鉴权头：

```http
Authorization: Bearer <jwt-or-api-token>
```

Bearer Token 支持两类：

```text
1. 用户登录后返回的 JWT。
2. /api/tokens 创建的 API Token，格式为 pcat_xxx。
```

## 2. 权限点

当前系统使用字符串权限：

```text
user:manage          用户管理
provider:manage      Provider 配置管理
channel:manage       微信 / 飞书 Channel 配置管理
agent:run            调用 Agent
audit:read           查看审计日志和用量记录
token:manage_self    管理 API Token
```

默认 bootstrap 管理员权限：

```text
user:manage,provider:manage,channel:manage,agent:run,audit:read,token:manage_self
```

普通用户建议权限：

```text
agent:run,token:manage_self
```

## 3. 错误响应

业务异常、校验异常和未处理异常统一返回 JSON。

示例：

```json
{
  "timestamp": "2026-07-05T17:30:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "message": "username must not be blank"
}
```

常见状态码：

```text
400 请求参数错误
401 用户名密码错误、Token 无效、pyclaw 内部 token 错误
403 未登录或权限不足
404 资源不存在
409 用户名冲突
502 Spring 调用 pyclaw 失败
500 未处理服务端错误
```

前端建议：

```text
401/403：跳转登录页或展示无权限提示。
400/409：把 message 展示在表单附近。
502：提示 Agent Runtime 暂不可用。
500：展示通用错误，并保留 message 供调试。
```

## 4. 公开接口

### 4.1 健康检查

```http
GET /healthz
```

鉴权：

```text
不需要
```

响应：

```json
{
  "status": "ok",
  "service": "pyclaw-spring-backend"
}
```

前端用途：

```text
部署状态页、运维探活、登录页启动检查。
```

## 5. Auth

### 5.1 登录

```http
POST /api/auth/login
```

鉴权：

```text
不需要
```

请求：

```json
{
  "username": "admin",
  "password": "ChangeMe123!"
}
```

字段：

```text
username 必填
password 必填
```

响应：

```json
{
  "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 3600
}
```

前端处理：

```text
1. 保存 accessToken。
2. 后续请求设置 Authorization: Bearer <accessToken>。
3. expiresIn 当前固定由后端返回 3600 秒。
```

### 5.2 当前用户

```http
GET /api/auth/me
```

鉴权：

```text
需要 JWT 或 API Token
```

响应：

```json
{
  "userId": "uuid",
  "username": "admin",
  "actorType": "USER",
  "authorities": [
    "user:manage",
    "provider:manage",
    "channel:manage",
    "agent:run",
    "audit:read",
    "token:manage_self"
  ]
}
```

前端用途：

```text
1. 初始化当前登录用户信息。
2. 根据 authorities 控制菜单和按钮可见性。
```

## 6. Agent

### 6.1 调用 Agent

```http
POST /api/agent/run
```

权限：

```text
agent:run
```

请求：

```json
{
  "prompt": "你好，请介绍一下 pyclaw。",
  "provider": "openai",
  "sessionId": "web-demo",
  "toolProfile": "minimal",
  "model": "deepseek-chat"
}
```

字段：

```text
prompt      必填，用户输入内容
provider    可选，默认 openai
sessionId   可选，用于连续会话
toolProfile 可选，默认 minimal
model       可选，不填时由 pyclaw 环境变量决定
```

常用 `toolProfile`：

```text
minimal
readonly
coding
messaging
full
```

响应：

```json
{
  "sessionId": "web-demo",
  "message": {
    "provider": "openai",
    "model": "deepseek-chat",
    "content": [
      {
        "type": "text",
        "text": "你好，我是 pyclaw..."
      }
    ],
    "usage": {
      "prompt_tokens": 10,
      "completion_tokens": 20,
      "total_tokens": 30
    }
  },
  "text": "你好，我是 pyclaw...",
  "latencyMs": 1234
}
```

前端页面建议：

```text
页面：Agent Playground
控件：prompt textarea、provider select、model input、toolProfile select、sessionId input、发送按钮
展示：text 主结果、latencyMs、sessionId、message 原始 JSON 折叠面板
```

## 7. API Token

### 7.1 Token 列表

```http
GET /api/tokens
```

权限：

```text
token:manage_self 或 user:manage
```

响应：

```json
[
  {
    "id": "uuid",
    "userId": "uuid",
    "name": "local-test",
    "tokenHash": "hash-value",
    "scopes": "agent:run",
    "expiresAt": "2026-12-31T23:59:59+08:00",
    "revokedAt": null,
    "createdAt": "2026-07-05T17:30:00+08:00",
    "lastUsedAt": null
  }
]
```

注意：

```text
当前接口会返回 tokenHash，这是后端现状。
正式前端不应展示 tokenHash，可隐藏该字段。
```

### 7.2 创建 Token

```http
POST /api/tokens
```

权限：

```text
token:manage_self 或 user:manage
```

请求：

```json
{
  "name": "frontend-test",
  "expiresAt": "2026-12-31T23:59:59+08:00",
  "scopes": [
    "agent:run"
  ]
}
```

字段：

```text
name      必填
expiresAt 可选，ISO-8601 时间
scopes    必填，非空数组
```

响应：

```json
{
  "tokenId": "uuid",
  "token": "pcat_xxx"
}
```

前端处理：

```text
token 明文只返回一次。
创建成功后必须弹窗展示，并提示用户立即保存。
关闭弹窗后不能再查看明文 token。
```

### 7.3 撤销 Token

```http
DELETE /api/tokens/{id}
```

权限：

```text
token:manage_self 或 user:manage
```

响应：

```text
204/200 空响应
```

前端处理：

```text
撤销前二次确认。
撤销后刷新列表。
```

## 8. 用户管理

### 8.1 用户列表

```http
GET /api/users
```

权限：

```text
user:manage
```

响应：

```json
[
  {
    "id": "uuid",
    "username": "admin",
    "passwordHash": "$2a$10$...",
    "displayName": "Administrator",
    "status": "ACTIVE",
    "authorities": "user:manage,provider:manage,channel:manage,agent:run,audit:read,token:manage_self",
    "createdAt": "2026-07-05T17:30:00+08:00",
    "updatedAt": "2026-07-05T17:30:00+08:00"
  }
]
```

注意：

```text
当前接口会返回 passwordHash，这是后端现状。
正式前端必须隐藏 passwordHash。
```

### 8.2 创建用户

```http
POST /api/users
```

权限：

```text
user:manage
```

请求：

```json
{
  "username": "alice",
  "password": "ChangeMe123!",
  "displayName": "Alice",
  "authorities": "agent:run,token:manage_self"
}
```

字段：

```text
username    必填
password    必填
displayName 可选
authorities 可选，不填默认 agent:run,token:manage_self
```

响应：

```json
{
  "id": "uuid",
  "username": "alice",
  "passwordHash": "$2a$10$...",
  "displayName": "Alice",
  "status": "ACTIVE",
  "authorities": "agent:run,token:manage_self",
  "createdAt": "2026-07-05T17:30:00+08:00",
  "updatedAt": "2026-07-05T17:30:00+08:00"
}
```

### 8.3 禁用用户

```http
PUT /api/users/{id}/disable
```

权限：

```text
user:manage
```

响应：

```json
{
  "id": "uuid",
  "username": "alice",
  "status": "DISABLED",
  "authorities": "agent:run,token:manage_self"
}
```

前端页面建议：

```text
页面：Users
列表列：username、displayName、status、authorities、createdAt、updatedAt、操作
操作：创建、禁用
```

## 9. Provider 配置

### 9.1 Provider 列表

```http
GET /api/providers
```

权限：

```text
provider:manage 或 agent:run
```

响应：

```json
[
  {
    "id": "uuid",
    "name": "deepseek-prod",
    "providerType": "openai-compatible",
    "baseUrl": "https://api.deepseek.com",
    "model": "deepseek-chat",
    "apiMode": "chat_completions",
    "secretRef": "pyclaw-provider-secret",
    "enabled": true,
    "createdAt": "2026-07-05T17:30:00+08:00",
    "updatedAt": "2026-07-05T17:30:00+08:00"
  }
]
```

### 9.2 创建 Provider

```http
POST /api/providers
```

权限：

```text
provider:manage
```

请求：

```json
{
  "name": "deepseek-prod",
  "providerType": "openai-compatible",
  "baseUrl": "https://api.deepseek.com",
  "model": "deepseek-chat",
  "apiMode": "chat_completions",
  "secretRef": "pyclaw-provider-secret",
  "enabled": true
}
```

字段：

```text
name         必填
providerType 必填
baseUrl      可选
model        必填
apiMode      必填
secretRef    可选，指向 K8s Secret 或外部密钥引用
enabled      必填 boolean
```

响应：

```text
Provider 对象
```

### 9.3 更新 Provider

```http
PUT /api/providers/{id}
```

权限：

```text
provider:manage
```

请求：

```text
同创建 Provider
```

响应：

```text
Provider 对象
```

### 9.4 删除 Provider

```http
DELETE /api/providers/{id}
```

权限：

```text
provider:manage
```

响应：

```text
204/200 空响应
```

前端页面建议：

```text
页面：Providers
控件：创建/编辑弹窗、启用开关、删除确认
注意：不要在 Provider 表单中直接输入真实 API Key，使用 secretRef。
```

## 10. Channel 配置

当前只保留微信和飞书。

### 10.1 Channel 列表

```http
GET /api/channels
```

权限：

```text
channel:manage
```

响应：

```json
[
  {
    "id": "uuid",
    "channelType": "wechat",
    "name": "wechat-official-account",
    "configJson": "{\"appId\":\"xxx\"}",
    "secretRef": null,
    "enabled": true,
    "createdAt": "2026-07-05T17:30:00+08:00",
    "updatedAt": "2026-07-05T17:30:00+08:00"
  }
]
```

注意：

```text
configJson 当前是字符串形式 JSON。
前端展示时可 JSON.parse 后渲染。
保存时使用 /api/channels 的 config 对象字段。
```

### 10.2 创建通用 Channel

```http
POST /api/channels
```

权限：

```text
channel:manage
```

请求：

```json
{
  "channelType": "wechat",
  "name": "wechat-official-account",
  "config": {
    "appId": "wx...",
    "callbackPath": "/api/webhooks/wechat"
  },
  "secretRef": "wechat-secret",
  "enabled": true
}
```

响应：

```text
Channel 对象
```

### 10.3 微信快捷配置

```http
POST /api/channels/wechat/config
```

权限：

```text
channel:manage
```

请求：

```json
{
  "name": "wechat-official-account",
  "appId": "wx...",
  "callbackPath": "/api/webhooks/wechat",
  "secretRef": "wechat-secret"
}
```

后端行为：

```text
channelType 固定为 wechat。
name 不传时默认为 wechat。
enabled 固定为 true。
secretRef 当前快捷接口不会自动提升为实体 secretRef，会进入 configJson。
```

### 10.4 飞书快捷配置

```http
POST /api/channels/feishu/config
```

权限：

```text
channel:manage
```

请求：

```json
{
  "name": "feishu-bot",
  "appId": "cli_xxx",
  "callbackPath": "/api/webhooks/feishu",
  "secretRef": "feishu-secret"
}
```

后端行为：

```text
channelType 固定为 feishu。
name 不传时默认为 feishu。
enabled 固定为 true。
secretRef 当前快捷接口不会自动提升为实体 secretRef，会进入 configJson。
```

### 10.5 更新 Channel

```http
PUT /api/channels/{id}
```

权限：

```text
channel:manage
```

请求：

```text
同创建通用 Channel
```

响应：

```text
Channel 对象
```

### 10.6 删除 Channel

```http
DELETE /api/channels/{id}
```

权限：

```text
channel:manage
```

响应：

```text
204/200 空响应
```

前端页面建议：

```text
页面：Channels
Tab：微信、飞书
控件：启用开关、配置 JSON 高级编辑、secretRef 输入
注意：真实 token/aesKey/encryptKey 不建议明文存在 configJson，优先写入 Secret 后用 secretRef 引用。
```

## 11. 审计日志

### 11.1 审计日志列表

```http
GET /api/audit-logs
```

权限：

```text
audit:read
```

响应：

```json
[
  {
    "id": "uuid",
    "actorType": "USER",
    "actorId": "uuid",
    "action": "agent:run",
    "resourceType": "session",
    "resourceId": "web-demo",
    "requestId": null,
    "ipAddress": null,
    "userAgent": null,
    "success": true,
    "errorMessage": null,
    "createdAt": "2026-07-05T17:30:00+08:00"
  }
]
```

前端页面建议：

```text
页面：Audit Logs
列表列：createdAt、actorType、actorId、action、resourceType、resourceId、success、errorMessage
当前后端未提供分页，前端先按全量列表处理。
```

## 12. 用量记录

### 12.1 用量记录列表

```http
GET /api/usage-records
```

权限：

```text
audit:read
```

响应：

```json
[
  {
    "id": "uuid",
    "userId": "uuid",
    "sessionId": "web-demo",
    "provider": "openai",
    "model": "deepseek-chat",
    "promptTokens": 10,
    "completionTokens": 20,
    "totalTokens": 30,
    "success": true,
    "latencyMs": 1234,
    "createdAt": "2026-07-05T17:30:00+08:00"
  }
]
```

前端页面建议：

```text
页面：Usage
指标：总调用次数、成功率、总 token、平均 latencyMs
列表列：createdAt、userId、sessionId、provider、model、totalTokens、success、latencyMs
```

## 13. 推荐前端页面结构

建议生成一个管理后台，而不是继续扩展最小调试页。

```text
Login
  - 用户名
  - 密码

Dashboard
  - 后端健康状态
  - 当前用户
  - 今日调用数
  - 最近错误

Agent Playground
  - prompt
  - provider
  - model
  - toolProfile
  - sessionId
  - 输出结果

API Tokens
  - token 列表
  - 创建 token
  - 一次性展示明文 token
  - 撤销 token

Users
  - 用户列表
  - 创建用户
  - 禁用用户
  - 权限编辑输入

Providers
  - Provider 列表
  - 创建/编辑 Provider
  - 启用/停用

Channels
  - 微信配置
  - 飞书配置
  - secretRef 管理提示

Audit Logs
  - 审计日志列表

Usage
  - 用量统计
  - 调用记录列表
```

## 14. 前端鉴权流程

```text
1. 进入页面，检查本地是否有 accessToken。
2. 没有 token：跳转 Login。
3. 有 token：调用 GET /api/auth/me。
4. /api/auth/me 成功：保存当前用户和 authorities。
5. /api/auth/me 失败：清理 token，跳转 Login。
6. 所有 API 请求自动带 Authorization: Bearer <accessToken>。
7. 任意请求返回 401/403：提示登录过期或无权限。
```

## 15. 当前后端限制与前端规避

当前后端为第一版完整骨架，前端生成时应注意：

```text
1. 用户列表会返回 passwordHash：前端必须隐藏。
2. Token 列表会返回 tokenHash：前端必须隐藏。
3. 列表接口暂未分页：前端先按全量列表处理。
4. Channel 的 configJson 是字符串：前端需要 JSON.parse。
5. 快捷微信/飞书接口不会把 secretRef 单独写入实体 secretRef 字段。
6. 当前没有 refresh token：JWT 过期后重新登录。
7. 当前没有文件上传接口。
8. 当前没有真实微信/飞书 webhook 接收接口。
9. 当前没有限流接口和配额接口。
```

## 16. TypeScript 类型建议

```ts
export type Authority =
  | "user:manage"
  | "provider:manage"
  | "channel:manage"
  | "agent:run"
  | "audit:read"
  | "token:manage_self";

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresIn: number;
}

export interface MeResponse {
  userId: string;
  username: string;
  actorType: "USER" | "API_TOKEN";
  authorities: Authority[];
}

export interface AgentRunRequest {
  prompt: string;
  provider?: string;
  sessionId?: string;
  toolProfile?: "minimal" | "readonly" | "coding" | "messaging" | "full";
  model?: string;
}

export interface AgentRunResponse {
  sessionId: string;
  message: Record<string, unknown>;
  text: string;
  latencyMs: number;
}

export interface ApiToken {
  id: string;
  userId: string;
  name: string;
  scopes: string;
  expiresAt?: string | null;
  revokedAt?: string | null;
  createdAt: string;
  lastUsedAt?: string | null;
}

export interface ProviderConfig {
  id: string;
  name: string;
  providerType: string;
  baseUrl?: string | null;
  model: string;
  apiMode: string;
  secretRef?: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelConfig {
  id: string;
  channelType: "wechat" | "feishu" | string;
  name: string;
  configJson: string;
  secretRef?: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}
```

---

## Provider Options 补充接口

### 脱敏 Provider 选择列表

```http
GET /api/providers/options
```

权限：

```text
provider:manage 或 agent:read 或 agent:update
```

用途：

```text
供 Agents 页面 Provider Config 下拉框使用。
该接口只用于选择已有 Provider，不用于管理 Provider 密钥。
```

响应：

```json
[
  {
    "id": "uuid",
    "name": "deepseek-main",
    "providerType": "openai-compatible",
    "model": "deepseek-chat",
    "apiMode": "chat_completions",
    "enabled": true
  }
]
```

安全约束：

```text
不返回 apiKey、baseUrl、secretRef、apiKeyLast4。
provider:manage 用户仍通过 GET/POST/PUT/DELETE /api/providers 管理完整 Provider 配置。
agent:update 用户只通过 providerId 修改 Agent 与 Provider 的绑定关系。
```