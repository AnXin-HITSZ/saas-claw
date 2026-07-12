# pyclaw 云服务器 K8s 部署技术方案

## 1. 文档目标

本文档用于说明如何将 `pyclaw` 部署到云服务器对应的 Kubernetes 集群中，并帮助后续逐步积累以下能力：

- Python 服务容器化
- Kubernetes 基础对象建模
- 配置与密钥分离
- Ingress 暴露与域名访问
- 多副本与滚动发布
- 日志、探针、资源限制等云原生基础设施能力

本文档不追求一步到位做成复杂分布式平台，而是给出一条适合当前 `pyclaw` 项目状态的、可逐步落地的 K8s 路线。

---

## 2. 当前项目状态分析

根据当前仓库结构与 README，可得出以下结论：

1. `pyclaw` 当前是一个 Python agent runtime。
2. 当前主入口是 CLI：
   - `pyclaw`
   - `python -m openclaw`
3. 项目当前没有现成的 FastAPI / Flask / Django 长驻 HTTP 服务。
4. 当前 `gateway run` 仍是占位入口，尚未真正实现。
5. 当前已有本地状态目录：
   - `chatdata/`
6. 当前能力更偏“本地运行 agent / 工具执行 / transcript 持久化”，而不是“天然在线服务”。

这意味着：

**`pyclaw` 可以部署到 K8s，但在部署前最好先明确它在集群中的运行形态。**

否则会出现一个常见误区：

- 项目是交互式 CLI
- 但部署时却按传统 Web 服务方式做 Deployment
- 最后 Pod 虽然起来了，但没有稳定对外接口，也没有清晰的任务触发机制

---

## 3. 适合 `pyclaw` 的 K8s 运行形态

对于当前项目，更推荐按下面三种运行形态理解，而不是只盯着“一个 Deployment”：

### 3.1 形态 A：HTTP API 服务

为 `pyclaw` 增加一个 Web API 包装层，例如：

- `POST /runs`：提交一次 agent 任务
- `GET /runs/{id}`：查询任务状态
- `GET /transcripts/{session_id}`：读取 transcript
- `POST /tools/run`：受控执行工具

这时它就是一个典型的 K8s Web 服务，最适合练习：

- Deployment
- Service
- Ingress
- HPA
- Secret / ConfigMap

### 3.2 形态 B：异步 Worker 服务

如果你希望 `pyclaw` 负责后台执行长任务、服务器自动化编排、批量 agent 行为，那么更适合做成：

- API 服务负责接收请求
- Worker 负责真实执行任务
- Redis / 数据库负责状态与队列

这时集群里至少会有：

- `pyclaw-api`
- `pyclaw-worker`
- `redis`
- `mysql/postgres`（可选）

### 3.3 形态 C：CronJob / Job

如果某些场景是：

- 定时巡检服务器
- 周期性清理 transcript
- 定时跑 agent 审核任务

那么可以进一步引入：

- `Job`
- `CronJob`

---

## 4. 推荐的落地路线

结合“希望实践 K8s，但又不希望复杂度一开始过高”的目标，建议采用以下路线：

### 第一阶段：把 `pyclaw` 变成可部署的 API 服务

先做一个最小可用版本：

- 在当前项目中新增一个轻量 HTTP 层（推荐 FastAPI）
- HTTP 层内部调用现有 `openclaw` 能力
- 保留 CLI，不替换 CLI
- 先只支持最核心的一个接口，例如：
  - `POST /v1/agent/run`

这样做的好处是：

1. 保留已有 CLI 投资
2. 增加一个稳定的服务边界
3. 让 K8s 中的 Deployment / Service / Ingress 具有明确承载对象

### 第二阶段：把任务执行与 API 解耦

当你发现一次 agent 执行可能很慢，或需要并发处理时，再引入：

- API 服务：接收请求、写入任务
- Worker：读取任务并执行
- Redis / DB：保存状态

### 第三阶段：增加云原生增强能力

后续再补：

- HPA
- 多副本
- 指标监控
- 日志采集
- 灰度发布
- 任务幂等与分布式锁

---

## 5. 推荐的云上部署架构

### 5.1 最小可行架构

推荐先做如下架构：

- Kubernetes 集群：1 个测试集群
- Namespace：`pyclaw`
- Deployment：`pyclaw-api`
- Service：`pyclaw-api`
- Ingress：`pyclaw.example.com`
- ConfigMap：非敏感配置
- Secret：API Key、SSH 凭证、数据库密码
- PVC：持久化 transcript（如仍保留本地文件存储）

### 5.2 第二阶段增强架构

后续可以扩展为：

- `pyclaw-api`
- `pyclaw-worker`
- `redis`
- `postgres`
- `cronjob`（定时巡检/清理）
- `prometheus` / `grafana`（监控）

---

## 6. 为什么当前项目不建议直接把 CLI 原样塞进 Deployment

当前 `pyclaw` 主要入口是命令行：

- 适合一次性交互
- 适合本地开发与测试
- 不天然适合长驻 HTTP 请求处理

如果直接在 K8s Deployment 里跑：

```bash
pyclaw "你好"
```

那么 Pod 执行完一次命令就会退出，不符合长驻服务模型。

如果用：

```bash
tail -f /dev/null
```

让容器常驻，再手动 `kubectl exec` 进去跑命令，虽然能“练到 K8s”，但这不是一个好的线上服务设计。

所以更合理的方式是：

1. 保留 CLI 作为能力内核
2. 外面包一层服务入口
3. 再把这个服务入口容器化并部署到 K8s

---

## 7. 容器化建议

### 7.1 基础镜像建议

建议使用：

- `python:3.11-slim`

原因：

- 与项目 `requires-python >= 3.11` 一致
- 体积适中
- 生态成熟

### 7.2 Dockerfile 建议

推荐思路：

1. 拷贝 `pyproject.toml`
2. 安装依赖
3. 拷贝 `openclaw/` 与其他源码
4. 指定默认启动命令

如果第一阶段走 API 服务，容器启动命令建议变为：

```bash
uvicorn openclaw.api:app --host 0.0.0.0 --port 8000
```

如果暂时没有 API 层，则不建议直接部署到 K8s 对外提供服务。

### 7.3 镜像仓库建议

当前可以使用已开通的阿里云 ACR 个人版作为镜像仓库：

```text
地域: 华南 1（深圳）
公网地址: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
镜像地址格式: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/<namespace>/pyclaw-api:<tag>
```

推荐流程：

1. 在 ACR 控制台创建命名空间。
2. 创建 `pyclaw-api` 镜像仓库。
3. 设置 ACR 访问凭证固定密码。
4. 本地或 ECS 上 `docker build`。
5. `docker tag` 到 ACR 完整镜像地址。
6. `docker push` 到 ACR。
7. Helm values 中使用 ACR 镜像地址。

如果 ACR 仓库为私有仓库，K8s 侧需要创建 `docker-registry` 类型 Secret，并通过 `imagePullSecrets` 引用。

---

## 8. 推荐的 K8s 对象设计

### 8.1 Namespace

建议单独使用：

- `pyclaw`

作用：

- 与其他项目隔离
- 便于权限、资源、日志管理

### 8.2 Deployment

Deployment 负责：

- 管理 `pyclaw-api` Pod
- 副本数管理
- 滚动更新
- 自动重建故障 Pod

建议初始配置：

- `replicas: 1`
- 第二阶段改为 `2`

### 8.3 Service

Service 提供集群内稳定访问入口。

建议类型：

- `ClusterIP`

再由 Ingress 对外暴露。

### 8.4 Ingress

Ingress 提供：

- 域名访问
- HTTPS 终止
- 路由规则

例如：

- `pyclaw.example.com`

### 8.5 ConfigMap

适合存放：

- 非敏感配置
- 默认模型
- transcript 目录
- 日志级别
- feature flag

### 8.6 Secret

适合存放：

- `OPENAI_API_KEY`
- 其他模型厂商 API Key
- SSH 私钥
- 数据库密码
- Redis 密码
- 内部鉴权 token

### 8.7 PVC

如果当前 transcript 仍保存在文件系统中，例如：

- `chatdata/`

那么建议挂一个 PVC，避免 Pod 重建后文件丢失。

但从中长期看，更推荐把 transcript 元数据转移到：

- 对象存储
- 数据库
- 或至少挂载持久卷

### 8.8 ServiceAccount / RBAC

如果未来 `pyclaw` 需要直接管理 K8s 资源，例如：

- 创建 Job
- 查询 Pod
- 重启 Deployment

那么就需要：

- `ServiceAccount`
- `Role` / `ClusterRole`
- `RoleBinding` / `ClusterRoleBinding`

如果当前只是管理外部服务器，而不是管理 K8s 内部资源，则暂时可以不配复杂 RBAC。

---

## 9. Helm 建议

既然你希望积累云原生经验，推荐不要只停留在裸 YAML，而是直接学习并使用 Helm。

推荐目录结构：

```text
helm/
  pyclaw/
    Chart.yaml
    values.yaml
    templates/
      deployment.yaml
      service.yaml
      ingress.yaml
      configmap.yaml
      secret.yaml
      pvc.yaml
      serviceaccount.yaml
```

这样后续你可以非常自然地管理：

- 不同环境镜像 tag
- 不同域名
- 不同资源限制
- 不同副本数
- 不同配置项

### 9.1 values.yaml 推荐字段

建议至少包含：

- `image.repository`
- `image.tag`
- `imagePullSecrets`
- `service.port`
- `ingress.host`
- `resources.requests`
- `resources.limits`
- `env`
- `secretEnv`
- `persistence.enabled`
- `persistence.size`

---

## 10. 初版部署步骤建议

### 步骤 1：先把服务入口补出来

当前优先建议新增一个最小 API 层，例如：

- `openclaw/api.py`
- 用 FastAPI 暴露 `/healthz` 和 `/v1/agent/run`

至少要有：

- 健康检查接口
- 一个最小执行入口

### 步骤 2：编写 Dockerfile

确保本地可以执行：

- `docker build`
- `docker run`

并能访问 `/healthz`。

### 步骤 3：推送镜像到阿里云 ACR

示例：

```bash
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
ACR_NAMESPACE=<namespace>
IMAGE_TAG=0.1.0

docker login ${ACR_REGISTRY}
docker build -t pyclaw-api:dev .
docker tag pyclaw-api:dev ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
docker push ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
```

### 步骤 4：编写 Helm Chart

先准备：

- Deployment
- Service
- Ingress
- ConfigMap
- Secret

并在 values 中配置：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/<namespace>/pyclaw-api
  tag: 0.1.0

imagePullSecrets:
  - name: aliyun-acr-pull-secret
```

### 步骤 5：准备集群

如果你是第一次独立部署，推荐两种方式任选一种：

#### 方案 A：云厂商托管 K8s

例如：

- 阿里云 ACK
- 腾讯云 TKE
- 华为云 CCE
- AWS EKS
- GKE

优点：

- 少操心控制面
- 更贴近企业真实使用方式

#### 方案 B：单机 K3s / kubeadm

适合纯练手。

优点：

- 成本低
- 自己能摸到更多底层

建议：

- 如果重点是“练业务部署与云原生交付”，优先托管 K8s
- 如果重点是“理解 K8s 底层安装与节点结构”，可以试 K3s

### 步骤 6：部署 Ingress Controller

如果集群没有现成 Ingress Controller，需要先装：

- NGINX Ingress Controller

### 步骤 7：创建镜像拉取 Secret

如果 ACR 镜像仓库是私有仓库，先创建拉取凭证：

```bash
kubectl create namespace pyclaw
kubectl -n pyclaw create secret docker-registry aliyun-acr-pull-secret \
  --docker-server=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com \
  --docker-username='你的 ACR 登录用户名' \
  --docker-password='你的 ACR 固定密码' \
  --docker-email='unused@example.com'
```

### 步骤 8：部署 `pyclaw`

执行：

```bash
helm upgrade --install pyclaw ./helm/pyclaw -n pyclaw --create-namespace
```

### 步骤 9：验证

依次验证：

1. Pod 是否 Running
2. `/healthz` 是否返回 200
3. Ingress 域名是否可访问
4. 配置与 Secret 是否正确注入
5. transcript 是否正常落盘/持久化

---

## 11. 资源与探针建议

### 11.1 资源限制

`pyclaw` 未来如果会调用模型、跑工具、执行 shell、抓网页，资源波动会比普通 CRUD 服务更明显。

建议初始资源：

- requests:
  - cpu: `250m`
  - memory: `512Mi`
- limits:
  - cpu: `1000m`
  - memory: `1Gi`

后续根据实际调用量再调。

### 11.2 探针

建议至少加：

- `readinessProbe`
- `livenessProbe`

如果有 `/healthz`，可以直接基于 HTTP 探针实现。

---

## 12. 配置与状态存储建议

### 12.1 配置

当前 `.env` 中的内容不应直接作为生产部署方式延续。

在 K8s 中建议拆分为：

- ConfigMap：非敏感项
- Secret：敏感项

### 12.2 transcript 与 session 数据

当前项目已有：

- `chatdata/`
- transcript JSONL
- session 元数据

需要尽早明确：

1. 是继续以文件形式保存
2. 还是改为数据库/对象存储

#### 如果继续文件保存

需要：

- PVC
- 明确挂载目录
- 处理多副本写入一致性问题

#### 如果改为数据库/对象存储

更适合多副本扩展。

这是后续演进到真正分布式部署的关键一步。

---

## 13. 多副本与分布式注意事项

你提到希望增加“分布式和云原生经验”，那真正值得关注的不是“把单进程放进 K8s”本身，而是下面这些问题。

### 13.1 幂等性

如果同一个任务被重复提交，是否会：

- 重复执行 shell
- 重复修改远端机器
- 重复写 transcript

需要有：

- 任务 ID
- 幂等键
- 重试策略

### 13.2 多副本任务冲突

如果有多个 `pyclaw-worker` 副本，可能出现：

- 同一任务被多个副本同时抢到
- 同一服务器被重复操作

这时通常需要：

- 队列
- 分布式锁
- 任务状态表

### 13.3 状态外置

如果状态都在本地内存或本地文件里，那么：

- Pod 重启会丢状态
- 多副本之间无法共享状态

因此真正要走向分布式时，状态必须逐步外置。

---

## 14. 安全建议

`pyclaw` 属于“自动化管理服务器”的服务，安全要求会高于普通业务接口。

建议重点关注：

1. Secret 不入镜像
2. 限制可执行命令边界
3. 限制可访问网络范围
4. 审计每次工具调用
5. 关键操作保留任务日志与执行者信息
6. 如果会管理生产服务器，必须加鉴权、授权、审批或至少操作白名单

如果后续需要更进一步，可以考虑：

- NetworkPolicy
- Pod Security
- 镜像扫描
- RBAC 最小权限

---

## 15. 可观测性建议

至少建议具备：

1. 结构化日志
2. 请求 ID / 任务 ID
3. 基础 metrics
4. 健康检查接口
5. 错误率与时延监控

后续可接入：

- Prometheus
- Grafana
- Loki / ELK

---

## 16. 推荐实施顺序

推荐按下面顺序推进：

### 第一步：补服务化入口

目标：让 `pyclaw` 具备一个最小 HTTP 服务入口。

### 第二步：本地容器化

目标：本地 `docker build` 和 `docker run` 可用。

### 第三步：补 Helm Chart

目标：能在 K8s 里用 Helm 部署。

### 第四步：部署到测试集群

目标：通过域名访问并完成最小健康检查。

### 第五步：补持久化与 Secret

目标：不依赖本地 `.env` 和本地磁盘临时目录。

### 第六步：引入异步任务模型

目标：支持长任务与多副本扩展。

---

## 17. 当前阶段的最终建议

对于当前 `pyclaw`，最合理的 K8s 实践方案不是：

- 直接把交互式 CLI 命令丢进 Pod

而是：

- **先把 CLI 内核包装成最小 API 服务**
- **再用 Docker + Helm + K8s 部署**
- **先完成单服务云原生交付**
- **再逐步演进到 API + Worker + Queue 的分布式模型**

这条路径既能保证工程上合理，也最有助于你真正积累：

- K8s 资源设计经验
- 服务化经验
- 配置与密钥治理经验
- 分布式任务系统的演进经验

---

## 18. 下一步建议

建议下一步直接进入实现阶段，优先做以下交付：

1. 为 `pyclaw` 新增一个最小 FastAPI 服务入口
2. 编写 `Dockerfile`
3. 初始化 `helm/pyclaw` Chart
4. 补齐：
   - Deployment
   - Service
   - Ingress
   - ConfigMap
   - Secret
   - PVC（如保留 `chatdata` 文件持久化）

如果上述第一阶段完成，你就已经拥有一套真正可部署、可升级、可扩展的 `pyclaw` K8s 版本。
