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

### 5. InternalServiceAuthFilter 审查

Gateway 中的 `InternalServiceAuthFilter.java` 保留。三个 `/api/internal/` 端点中有两个目前走 ClusterIP 直连（不经过 Gateway），但 Filter 层集中管控 `/api/internal/*` 是正确架构——后续新加端点不会因忘记加校验而裸奔。

### 6. 前端适配

前端 `frontend/` 已从 `pyclaw-web/` 迁移，但需适配新 API 路径。

### 7. 告警

`spring-backend/src/main/resources/db/migration/V1__agent_marketplace_base.sql` 已被删除。原 migration SQL 已合并到 `agent-marketplace-service` 的 Flyway 目录中。部署时需确认数据库迁移正常执行。

### 8. 工具审批风险阈值可配置

control-plane 中工具审批决策硬编码 `risk == "low"`。设计方案：改为可配置阈值，用户可从前端选择严格程度。详见 [tool-approval-threshold-design.md](tool-approval-threshold-design.md)。

---

## ECS 初始部署步骤

新 ECS 或格式化后，按以下顺序执行：

```bash
# 1. 安装 K3s
curl -sfL https://get.k3s.io | sh -

# 2. 配置 kubectl 别名
echo 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml' >> ~/.bashrc
echo 'alias kubectl="sudo /usr/local/bin/k3s kubectl"' >> ~/.bashrc
source ~/.bashrc

# 3. Clone 仓库
mkdir -p /opt/saas-claw
cd /opt/saas-claw
git clone git@github.com:AnXin-HITSZ/saas-claw.git .
# 或 HTTPS: git clone https://github.com/AnXin-HITSZ/saas-claw.git .

# 4. 创建 namespace
kubectl create ns saas-claw

# 5. 创建 ACR pull secret（拉镜像鉴权）
kubectl create secret docker-registry aliyun-acr-pull-secret -n saas-claw \
  --docker-server=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com \
  --docker-username=AnXin_HITSZ \
  --docker-password='<ACR密码>'

# 6. 创建 MySQL Secret
kubectl create secret generic saas-claw-mysql-secret -n saas-claw \
  --from-literal=MYSQL_ROOT_PASSWORD='<强密码>' \
  --from-literal=MYSQL_PASSWORD='<强密码>'

# 7. 创建应用 Secret
kubectl create secret generic saas-claw-secret -n saas-claw \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<同上>'

# 8. 创建 TLS Secret（证书文件需先上传到 ECS）
kubectl create secret tls saas-claw-tls -n saas-claw \
  --cert=/opt/saas-claw/certs/fullchain.pem \
  --key=/opt/saas-claw/certs/privkey.pem

# 9. 创建生产 values 文件（从 example 复制并填入 ACR 路径）
cp saas-claw-values-k3s.example.yaml saas-claw-values-k3s.yaml
cp saas-claw-mysql-values-k3s.example.yaml saas-claw-mysql-values-k3s.yaml
# 编辑 saas-claw-values-k3s.yaml，将 <ACR_REGISTRY> 替换为实际 ACR 地址

# 10. 推送 docker.io 镜像到 ACR（ECS 无法直接拉 Docker Hub）
docker pull mysql:8.4
docker pull redis:7-alpine
docker tag mysql:8.4 crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/saas-claw/mysql:8.4
docker tag redis:7-alpine crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/saas-claw/redis:7-alpine
docker login --username=AnXin_HITSZ crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
docker push crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/saas-claw/mysql:8.4
docker push crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/saas-claw/redis:7-alpine

# 11. 手动部署 MySQL（首次）
sudo helm --kubeconfig /etc/rancher/k3s/k3s.yaml upgrade --install saas-claw-mysql \
  ./deploy/helm/saas-claw-mysql -n saas-claw \
  -f saas-claw-mysql-values-k3s.yaml --timeout 10m

# 12. 此后 git push main → GitHub Actions 自动构建 + 部署
```

## 部署故障排查

### ImagePullBackOff

常见原因：
1. **镜像路径缺少 ACR 前缀**：helm 部署必须加 `-f saas-claw-values-k3s.yaml`，否则 image.repository 用 chart 默认短名 `saas-claw/gateway`，K3s 会去 Docker Hub 找。
2. **tag 不存在**：CI 构建的镜像 tag 是 git SHA，如果 `--set image.tag` 传错 tag 就拉不到。
3. **pull secret 失效**：检查 `kubectl get secret aliyun-acr-pull-secret -n saas-claw`

调试命令：
```bash
kubectl describe pod <pod-name> -n saas-claw | tail -20  # 看 Events
kubectl get deployment -n saas-claw -o yaml | grep -A3 image  # 看镜像路径
```

### Insufficient memory

2C4G ECS 跑 8 个 Java 服务 + MySQL + Redis 较紧。资源限制已调为：
- Java 服务：50m CPU / 128Mi request, 200m CPU / 256Mi limit
- Redis: 64Mi request / 256Mi limit
- MySQL: 250m CPU / 512Mi request, 1C / 1Gi limit

如仍不够，考虑先部署核心服务（gateway + claw-service + runtime-service），其他暂不部署。

### SSH 超时断开

GitHub Actions SSH 到 ECS 执行 helm install 时，如果镜像拉取慢会超时断开。MySQL/Redis 回滚后不影响 PVC 数据，重试即可。后续优化方案：为 GitHub Actions runner 和 ECS 之间配置长连接。

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
| DB 库 | saas_claw_control, saas_claw_runtime |
| MySQL 服务名 | saas-claw-mysql:3306 |
| Redis 服务名 | redis-master:6379 |
