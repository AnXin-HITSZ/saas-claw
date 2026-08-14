# API 契约

## 通用约定

| 项 | 约定 |
| :--: | :--: |
| Base URL | 统一走网关（唯一对外入口）|
| 数据格式 | JSON（推理接口为 SSE 流式）|
| 认证 | JWT（人，Authorization: Bearer）或 API Key（程序，Authorization: Bearer sk-xxx）|
| 错误码 | HTTP 语义：401 未认证 / 403 无权限 / 404 不存在 / 409 冲突 / 429 限流 / 500 服务异常 |
| 分页 | 管理接口返回 `{ list, total, page, size }` |

## 认证接口（后端）

| 接口 | 说明 | 关键契约 |
| :--: | :--: | :--: |
| POST /auth/register | 用户注册 | body: `{username, password}`；409 用户名已存在 |
| POST /auth/login | 登录 | 返回 `{token}`（JWT）|
| POST /auth/api-keys | 生成 API Key | 返回 `{api_key}`（sk-xxx，仅展示一次）|

## 管理接口（后端，前缀 /api/**）

| 接口 | 说明 | 关键契约 |
| :--: | :--: | :--: |
| GET/POST /claws | Claw 列表/创建 | 创建返回 `{id, namespace}` |
| POST /claws/{id}/deploy | 部署 | 异步：返回任务状态，K8s 协调完成后可查 |
| GET/POST /model-configs | 模型配置列表/创建 | 创建 body: {name, provider, model_name, endpoint, api_key}；409 name 已存在 |
| PUT /model-configs/{id} | 更新模型配置 | endpoint / api_key / status |
| DELETE /model-configs/{id} | 删除模型配置 | 软删（status=0）|
| GET/POST /agents | Agent 列表/创建 | 创建参数含 `alias`，409 alias 已存在 |
| PUT /agents/{id} | 更新 system_prompt/参数 | |
| POST /agents/{id}/files | 上传人格文件 | multipart（AGENTS.md / IDENTITY.md / SOUL.md），写 agent_file + OSS |
| PUT /agents/{id}/files/{name} | 编辑已有人格文件 | 覆盖内容，返回 file_url |
| GET /agents/{id}/files | 查看人格文件列表 | 返回 {list} |
| GET/POST /skills | Skill 列表/创建 | |
| POST /skills/{id}/files | 上传文件 | multipart，返回 file_url |
| POST /shop/agents/{id}/publish | 上架 Agent | |
| DELETE /shop/agents/{id} | 下架 Agent | 软删 |
| GET /shop/agents | 商店浏览 Agent | 分页，返回 {list} |
| POST /shop/agents/{id}/install | 安装 Agent | query: `claw_id` |
| POST /shop/skills/{id}/publish | 上架 Skill | |
| DELETE /shop/skills/{id} | 下架 Skill | 软删 |
| GET /shop/skills | 商店浏览 Skill | 分页，返回 {list} |
| POST /shop/skills/{id}/install | 安装 Skill | |
| PUT /approvals/{requestId} | 审批回写 | body: `{action: 1/2/3, custom_message?}` |

## 推理接口（Claw Pod，OpenAI 兼容）

| 项 | 契约 |
| :--: | :--: |
| 路径 | POST /v1/chat/completions |
| 请求 | `{model: "<agent.alias>", messages: [...], stream: true}` |
| 鉴权 | Bearer JWT 或 sk-xxx（网关解析后透传 X-User-Id）|
| 响应 | SSE 流式，OpenAI 兼容 chunk 格式 |
| 会话 | 多轮对话带 `conversation_id`（网页端）|

## 内部回调接口（Claw Pod 暴露）

| 接口 | 调用方 | 说明 |
| :--: | :--: | :--: |
| POST /approvals/callback | 后端 | body: `{request_id, result}`，恢复挂起的图 |

## 关键契约决策

1. **`model` 字段 = agent.alias**：程序调用 Agent 的唯一标识，用户级唯一（uk_user_alias）
2. **推理接口不区分来源**：网页/程序同一条通道，仅凭证不同（见 runtime.md）
3. **审批回写是唯一不经 CRUD 体系的前端状态直写**：其余管理操作均走完整 CRUD
4. **429 限流**：网关限流命中统一返回 429 + Retry-After
5. **模型密钥不进前端**：model_config.api_key 创建/更新时写入，列表与详情接口返回遮蔽值（如 `sk-****`），前端永不回显明文