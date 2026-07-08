# pyclaw Channel 独立 Worker 架构与部署记录

本文记录将 Channel 消息处理从 webhook 请求内同步处理，升级为 K3s 中独立 worker 消费 MySQL ingress queue 的实现与部署过程。

## 背景

当前 Channel 链路为：

```text
微信/飞书
  -> api.anxin-hitsz.com
  -> spring-backend webhook proxy
  -> pyclaw-api /v1/channels/<platform>/webhook
  -> MySQL ingress_queue
  -> Agent
  -> 微信/飞书发送接口
```

之前 `pyclaw-api` 中已有 `IngressQueueWorker`，但没有单独的 K8s Deployment 运行它。

当 `OPENCLAW_CHANNEL_WEBHOOK_SYNC=false` 时，webhook 只负责校验、入队、返回成功。消息会停留在 MySQL `ingress_queue` 表中，直到有 worker 消费。

当 `OPENCLAW_CHANNEL_WEBHOOK_SYNC=true` 时，webhook 请求会在当前 HTTP 请求内调用一次 `process_one()`，可以快速闭环测试，但 Agent 处理慢时可能导致微信/飞书回调超时。

本次实现采用独立 worker：

```text
pyclaw-api Deployment
  - 负责 HTTP webhook
  - 快速写入 MySQL 队列
  - 快速返回平台 success

pyclaw-channel-worker Deployment
  - 复用同一个 pyclaw-api 镜像
  - 启动命令为 python -m openclaw.channels.worker_app
  - 持续消费 MySQL ingress_queue
  - 调用 Agent
  - 调用平台发送接口回复用户
```

## 本次实现内容

### 1. 新增 worker 启动入口

新增文件：

```text
openclaw/channels/worker_app.py
```

启动方式：

```bash
python -m openclaw.channels.worker_app
```

支持配置项：

```text
OPENCLAW_CHANNEL_WORKER_CHANNELS              默认 wechat,feishu
OPENCLAW_CHANNEL_WORKER_POLL_INTERVAL_SECONDS 默认 1
OPENCLAW_CHANNEL_WORKER_OWNER_ID              默认 pyclaw-channel-worker
OPENCLAW_CHANNEL_WORKER_LOG_LEVEL             默认 INFO
```

也支持命令行参数：

```bash
python -m openclaw.channels.worker_app \
  --channels wechat,feishu \
  --poll-interval-seconds 0.5 \
  --owner-id pyclaw-channel-worker
```

### 2. 增加 worker 处理日志

修改文件：

```text
openclaw/channels/worker.py
```

新增日志包括：

```text
claimed ingress event
completed ingress event
failed ingress event
```

这样可以通过 K8s 日志直接判断消息是否被 worker 消费、是否发送成功、失败原因是什么。

### 3. 增加命令行脚本

修改文件：

```text
pyproject.toml
```

新增脚本：

```text
pyclaw-channel-worker = openclaw.channels.worker_app:main
```

容器中仍优先使用 `python -m openclaw.channels.worker_app`，脚本主要方便本地调试。

### 4. 增加 Helm worker Deployment

新增文件：

```text
helm/pyclaw/templates/worker-deployment.yaml
```

该 Deployment：

```text
名称：<release-fullname>-channel-worker
镜像：与 pyclaw-api 相同
启动命令：python -m openclaw.channels.worker_app
配置来源：同 pyclaw-api 的 ConfigMap / Secret
队列 DSN：同 OPENCLAW_INGRESS_QUEUE_DSN Secret
数据目录：同 chatdata PVC
```

新增 values：

```yaml
worker:
  enabled: false
  replicaCount: 1
  channels: wechat,feishu
  pollIntervalSeconds: 1
  ownerId: pyclaw-channel-worker
  logLevel: INFO
  extraEnv: []
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      cpu: 1000m
      memory: 1Gi
```

### 5. 在 MySQL ingress queue overlay 中启用 worker

修改文件：

```text
pyclaw-api-ingressqueue-mysql-values-k3s.yaml
```

启用：

```yaml
worker:
  enabled: true
  replicaCount: 1
  channels: wechat,feishu
  pollIntervalSeconds: 0.5
  ownerId: pyclaw-channel-worker
  logLevel: INFO
```

只要部署命令加载该 overlay，就会创建独立 worker。

## 部署步骤

### 1. 提交并推送代码

本地执行：

```powershell
cd D:\project\pyclaw

git status
git add openclaw/channels/worker_app.py openclaw/channels/worker.py pyproject.toml helm/pyclaw/values.yaml helm/pyclaw/templates/worker-deployment.yaml pyclaw-api-ingressqueue-mysql-values-k3s.yaml pyclaw-values-k3s.example.yaml docs/004/pyclaw-channel-worker-k3s-deployment-log.md
git commit -m "feat: 增加 Channel 独立 Worker 部署"
git push origin main
```

推送后 `Deploy pyclaw API` GitHub Actions 会构建新镜像，并通过 Helm 部署到 ECS K3s。

### 2. ECS 手动部署命令

如果不等 GitHub Actions，也可以在 ECS 上手动部署。需要确保镜像 tag 已经存在于 ACR。

```bash
cd /opt/pyclaw

git fetch origin main
git reset --hard origin/main

export PYCLAW_IMAGE_REPOSITORY="crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-api"
export PYCLAW_IMAGE_TAG="$(git rev-parse HEAD)"

sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-values-k3s.yaml \
  -f pyclaw-api-ingressqueue-mysql-values-k3s.yaml \
  --set-string image.repository="${PYCLAW_IMAGE_REPOSITORY}" \
  --set-string image.tag="${PYCLAW_IMAGE_TAG}"
```

如果当前 commit 对应镜像尚未被 GitHub Actions 推送到 ACR，手动部署会出现 `ErrImagePull`。

## 验证命令

### 1. 查看 Pod

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
```

期望看到类似：

```text
pyclaw-xxxxx                         1/1 Running
pyclaw-channel-worker-xxxxx          1/1 Running
pyclaw-spring-backend-xxxxx          1/1 Running
pyclaw-web-xxxxx                     1/1 Running
pyclaw-mysql-0                       1/1 Running
```

实际名称前缀取决于 Helm release fullname。

### 2. 查看 Deployment

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get deployment
```

期望存在：

```text
pyclaw
pyclaw-channel-worker
```

### 3. 查看 worker 日志

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw-channel-worker --since=10m -f
```

发送微信消息后，期望看到：

```text
claimed ingress event
completed ingress event
```

如果失败，会看到：

```text
failed ingress event
```

并附带异常堆栈。

### 4. 查看 pyclaw-api webhook 日志

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw --since=10m -f
```

发送微信消息后，期望看到：

```text
POST /v1/channels/wechat/webhook ... 200 OK
```

这表示平台消息已经进入 pyclaw-api。

### 5. 查看队列表状态

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec statefulset/pyclaw-mysql -- sh -c \
'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "select event_id, channel, status, attempts, left(error, 200) as error, from_unixtime(created_at) as created_at, from_unixtime(updated_at) as updated_at from ingress_queue order by created_at desc limit 10;"'
```

状态含义：

```text
pending   已入队，等待 worker 消费
claimed   已被 worker 领取，正在处理
completed 已处理完成
failed    处理失败，error 字段有原因
```

## 关于消息及时性

独立 worker 当前采用短轮询：

```text
worker 尝试 claim_next
  -> 有 pending 消息：立即处理，不 sleep
  -> 没有 pending 消息：sleep pollIntervalSeconds
  -> 继续下一轮
```

因此消息延迟上限大约是：

```text
pollIntervalSeconds + 当前排队等待时间 + Agent 处理时间 + 平台发送时间
```

当前 K3s overlay 配置：

```yaml
worker:
  pollIntervalSeconds: 0.5
```

也就是说，在没有积压时，消息通常会在 0 到 0.5 秒内被 worker 发现。真正耗时主要来自 Agent 推理和平台发送接口。

### 如何保证更及时

1. 降低轮询间隔

```yaml
worker:
  pollIntervalSeconds: 0.2
```

优点是更及时，缺点是空轮询更多，会增加 MySQL 查询频率。

2. 增加 worker 副本

```yaml
worker:
  replicaCount: 2
```

MySQL 队列使用 `claim_next` 和状态流转，多个 worker 可以并行处理不同 lane 的消息。需要注意 ECS 资源较小时不要盲目增加副本。

3. 使用 lane_key 保证同一会话串行

队列中有 `lane_key`，用于避免同一用户/同一会话的消息被多个 worker 并发打乱顺序。不同用户的消息可以并行处理。

4. 后续升级为通知式队列

如果后续消息量明显上升，可以从短轮询升级为：

```text
Redis Stream
RabbitMQ
Kafka
MySQL + NOTIFY 类旁路机制
```

当前阶段使用 MySQL 短轮询足够简单，也便于在单机 K3s 上部署和排障。

## 推荐运行模式

生产建议：

```text
OPENCLAW_CHANNEL_WEBHOOK_SYNC=false
worker.enabled=true
```

原因：

```text
webhook 快速返回平台 success
Agent 慢处理不阻塞平台回调
失败可通过 MySQL 队列表和 worker 日志排查
后续可通过增加 worker 副本扩展处理能力
```

临时调试可以使用：

```text
OPENCLAW_CHANNEL_WEBHOOK_SYNC=true
worker.enabled=false
```

但不建议长期使用，因为平台 webhook 请求会等待 Agent 完整处理。
