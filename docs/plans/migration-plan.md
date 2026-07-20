# 重构进度

> 最后更新：2026-07-20

## 已完成

### 阶段一：骨架搭建 ✅

8 个 Spring Boot 微服务 + 2 个 FastAPI 服务空壳。全部可编译/启动。

### 阶段二：业务迁移 ✅

| 服务 | 源 package | 文件数 | 测试 |
|------|------|------|------|
| gateway | user, auth, routebinding | 45 | 编译通过 |
| claw-service | conversation, session, clawchat, claw, agentinstall, agentconfig | 78 | 23 |
| runtime-service | orchestrator, sandbox, approval, agent, saasclaw, tool, provider, secret | 76 | 11 |
| agent-marketplace-service | agentpackage | 22 | 5 |
| billing-service | usage | 10 | 编译通过 |
| skill-marketplace-service | 无（新服务） | 占位 | — |
| backend-for-frontend | 无（新服务） | 占位 | — |
| control-plane | openclaw/api.py | 31 | import OK |
| claw-runner | sandbox-runner/app/main.py | 31 | import OK |

### 命名统一 ✅

- Maven groupId: `com.claw.saas`
- Docker 镜像: `saas-claw/*`
- K8s namespace: `saas-claw`
- Helm chart: `saas-claw`
- 域名: `saas.claw.anxin-hitsz.com`
- 旧代码 `pyclaw` 引用全部清除

### 删除 ✅

- `spring-backend/`、`openclaw/`、`sandbox-runner/`（单体时代）
- `tests/`（旧测试，引用已删除的代码）
- `chatdata/`
- Channel 调度（worker-deployment、WeChat/Feishu 配置）
- `token/`（CLI Token）

### 部署配置 ✅

- Helm chart：`deploy/helm/saas-claw/`（8 服务 + Redis + PVC）
- MySQL chart：`deploy/helm/saas-claw-mysql/`
- Dockerfiles：`backend/Dockerfile`（多模块构建）+ Python 各一个
- GitHub Actions：自动构建 8 个镜像 → 推 ACR → SSH ECS → Helm 部署
- ECS 路径：`/opt/saas-claw`

---

## 待部署（阻塞中）

ECS 上已准备好：
- 4 个 Secret：`saas-claw-secret`、`saas-claw-mysql-secret`、`aliyun-acr-pull-secret`、`saas-claw-tls`
- 生产 values：`saas-claw-values-k3s.yaml`、`saas-claw-mysql-values-k3s.yaml`
- MySQL/Redis 镜像已推送到 ACR

**待行动：** `git push origin main` 触发 CI 自动部署。

---

## 尚未完成

### 1. BFF 实现

`backend/backend-for-frontend/` 当前只有 HealthController。BFF 需要实现页面级数据聚合：

- 调用 claw-service 和 runtime-service 的 HTTP client
- 聚合对话 + Agent + Skill 数据为前端需要的形状
- 当前临时方案：Gateway 直接路由 `/api/**` 到 claw-service 绕过 BFF

### 2. skill-marketplace-service 实现

`backend/skill-marketplace-service/` 仅占位空壳。需要实现：

- Skill 发布、版本管理（引用 OSS 制品包）
- Skill 搜索和发现
- Skill 安装 API
- DB 表：`skills`、`skill_dependencies`

### 3. Skill 数据库表

阶段二预览的设计，尚未建表：

```
claw-service:
  claw_agent_skills    关联表（Claw Agent ←→ Skill）

skill-marketplace-service:
  skills               Skill 发布信息
  skill_dependencies   Skill 依赖关系
```

### 4. .claw 沙箱目录

claw-runner 当前 workspace 平铺，缺少 `.claw/` 元数据目录：

```
期望：
/workspace/.claw/<agent-role>/skills/
/workspace/.claw/<agent-role>/config/
```

需要：`POST /v1/claw/init` 接口 + SandboxOrchestrator 调用。

### 5. InternalServiceAuthFilter 清理

Gateway 中的 `InternalServiceAuthFilter.java` 已失效（服务间走 ClusterIP 直连，不经过 Gateway）。应删除或重构。

### 6. 前端适配

前端 `frontend/` 已从 `pyclaw-web/` 迁移，但需适配新 API 路径。

### 7. 告警

`spring-backend/src/main/resources/db/migration/V1__agent_marketplace_base.sql` 已被删除。原 migration SQL 已合并到 `agent-marketplace-service` 的 Flyway 目录中。部署时需确认数据库迁移正常执行。

---

## 关键配置速查

| 配置项 | 值 |
|------|------|
| ECS IP | 8.135.60.136 |
| ACR Registry | crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com |
| ACR 镜像前缀 | saas-claw/* |
| K8s Namespace | saas-claw |
| K3s kubeconfig | /etc/rancher/k3s/k3s.yaml |
| 部署路径 | /opt/saas-claw |
| 域名 | saas.claw.anxin-hitsz.com |
| DB 用户 | saas_claw |
| DB 库 | claw_saas_control, claw_saas_runtime |
| MySQL 服务名 | saas-claw-mysql:3306 |
| Redis 服务名 | redis-master:6379 |
