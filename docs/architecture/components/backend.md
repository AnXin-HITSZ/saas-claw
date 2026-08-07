# 后端（backend）组件设计

## 职责边界

后端只承担**管理域**，不参与 Agent 运行时：

| 域 | 归属 | 说明 |
| :--: | :--: | :--: |
| 用户/Claw/Agent/Skill/工具/商店管理 | 后端 | CRUD + 商店安装 |
| 程序调用 Agent | **不经过后端** | 网关 → Claw Pod 直达（见流 1）|
| Agent 运行时执行 | Claw Pod | 后端不插手中途执行 |

## 模块划分

| 模块 | 核心表 | 职责 |
| :--: | :--: | :--: |
| 用户模块 | user | 注册、登录、JWT 签发 |
| 鉴权模块 | authorization | API Key 生成/吊销（sk-xxx ↔ userId）|
| Claw 模块 | claw | CRUD + **K8s 部署协调** |
| Agent 模块 | agent / agent_file | CRUD、alias 唯一性校验 |
| Skill 模块 | skill / skill_file | 创建、文件上传 OSS |
| 工具模块 | tool | 工具注册表维护 |
| 商店模块 | agent_shop / skill_shop / agent_installation / skill_installation | 上架、下架、安装 |
| 审批模块 | tool_approval | 回写、回调 runtime、推送前端 |
| 模型配置模块 | model_config | 模型接入配置 |

## 关键流程

### 1. 创建 Claw（后端是协调者）

后端的核心复杂度在**写库 + 调 K8s 两步**：
1. 写 claw 表（拿到 id）
2. 调 K3s API：创建 namespace claw-{id}
3. 创建 PVC（claw-data）
4. 部署常驻 Claw Pod（runtime 镜像）
5. 失败回滚：任一步失败 → 清理已创建资源 + 删除 claw 记录

### 2. 敏感工具审批（回调 + 推送）

1. 接收 Claw Pod 写的 tool_approval（实际是 Claw Pod 直接写库）
2. 推送前端（WebSocket/SSE）→ 弹窗
3. 用户点击 → 回写 status/action/handled_at
4. HTTP 回调 Claw Pod（request_id + 结果）→ runtime resume
5. 兜底：expires_at 超时 job 置过期

### 3. 商店安装（多表事务）

任一步失败 → 整体回滚，不留半成品。

#### Agent

发布 Agent：
1. 校验归属（只能发布自己的 Agent）
2. 写 agent_shop 记录（agent_id 指向原 Agent）—— 不复制任何文件，agent_shop 只记指针，文件在安装时复制

安装商店 Agent：
1. 读 agent_shop → 原 Agent（agent_id）
2. 复制 agent 记录（source='shop'）→ local_agent_id
3. 复制人格文件：原 Agent 的 agent_file 记录 → 副本的 agent_file（文件拷贝到新 OSS URL，保证副本独立可编辑）
4. 同时安装其依赖的私有 Skill
5. 部署进用户指定 Claw

#### Skill

安装 Skill 事务边界：skill 副本 + skill_file 副本 + skill_installation。

## 对外接口（REST 概览）

| 接口 | 参数 | 功能描述 |
| :--: | :--: | :--: |
| POST /auth/register | - | 用户注册 |
| POST /auth/login | - | 登录（JWT） |
| POST /auth/api-keys | - | 生成 API Key |
| GET/POST /claws | - | Claw 管理 |
| POST /claws/{id}/deploy | - | 部署（K8s 协调） |
| GET/POST /agents | - | Agent 管理 |
| POST /agents/{id}/files | 上传人格文件（multipart）→ OSS + 写 agent_file |
| PUT /agents/{id}/files/{name} | 编辑已有人格文件（覆盖内容） |
| GET /agents/{id}/files | 查看人格文件列表 |
| GET/POST /skills | - | Skill 管理（含文件上传） |
| POST /skills/{id}/files | - | 上传 SKILL.md / 脚本 → OSS |
| POST /shop/agents/{id}/publish | - | Agent 发布上架（写 agent_shop 表） |
| DELETE /shop/agents/{id} | - | Agent 下架（status=0） |
| POST /shop/skills/{id}/publish | - | Skill 发布上架（写 skill_shop 表） |
| DELETE /shop/skills/{id} | - | Skill 下架（status=0） |
| GET /shop/agents | - | 商店浏览 Agent |
| GET /shop/skills | - | 商店浏览 Skill |
| POST /shop/agents/{id}/install | claw_id | 安装 Agent |
| POST /shop/skills/{id}/install | - | 安装 Skill |
| PUT /approvals/{requestId} | - | 审批回写（前端调用） |

## 依赖的外部服务

| 服务 | 用途 | 调用方式 |
| :--: | :--: | :--: |
| MySQL | 全部业务数据 | MyBatis-Plus |
| 阿里云 OSS | Skill 文件、Agent 产物 | SDK |
| K3s API | namespace/PVC/Deployment 管理 | fabric8 / 官方 client |
| Claw Pod（runtime）| 审批回调 | HTTP（集群内 Service DNS）|

## 技术要点

- **软删除**：所有业务表 status 字段，删除 = 置 0
- **事务边界**：安装/创建 Claw 等跨表操作用 @Transactional；K8s 调用不可回滚，需补偿清理
- **API Key 生成**：`sk-` + 随机串，入库前哈希（与用户密码同策略）
- **JWT 与 API Key 双通道**：JWT 给人（前端），API Key 给程序（authorization 表）