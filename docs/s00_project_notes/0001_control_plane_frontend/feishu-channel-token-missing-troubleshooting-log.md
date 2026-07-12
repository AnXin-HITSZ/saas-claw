# 飞书 Channel 无响应与内部 Token 缺失排查记录

日期：2026-07-09

## 1. 现象

飞书机器人在群聊中偶尔可以回复一次，但后续消息没有响应。

K3s 中 Pod 状态曾出现：

```text
pyclaw-channel-worker-...   0/1   Error
```

查看 channel worker 日志：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs pyclaw-channel-worker-8489d5b79-kglln
```

报错：

```text
ValueError: OPENCLAW_CHANNEL_CONFIG_TOKEN or PYCLAW_API_TOKEN is required for Spring channel config
```

后续补齐 worker 所需 token 后，worker 恢复 Running，但飞书消息仍无响应。查看 pyclaw-api 日志出现：

```text
failed Feishu webhook: error=OPENCLAW_CHANNEL_CONFIG_TOKEN or PYCLAW_API_TOKEN is required for Spring channel config
INFO: "POST /v1/channels/feishu/webhook HTTP/1.1" 400 Bad Request
```

## 2. 背景链路

飞书消息的完整链路为：

```text
Feishu
  -> Spring Backend /api/webhooks/feishu
  -> pyclaw-api /v1/channels/feishu/webhook
  -> pyclaw-api 从 Spring Backend 读取 /api/internal/channels/feishu/runtime-config
  -> MySQL ingress_queue 入队
  -> pyclaw-channel-worker 消费队列
  -> Agent 生成回复
  -> Feishu send message API
```

其中 `pyclaw-api` 和 `pyclaw-channel-worker` 都会调用 Spring Backend 的内部 Channel 运行时配置接口：

```text
GET /api/internal/channels/{channel}/runtime-config
Authorization: Bearer <internal-token>
```

Python 侧读取 token 的优先级为：

```text
OPENCLAW_CHANNEL_CONFIG_TOKEN
OPENCLAW_INTERNAL_API_TOKEN
PYCLAW_API_TOKEN
```

任意一个有值即可。

## 3. 根因

`pyclaw-provider-secret` 中缺少内部服务 token。

当时查询结果：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get secret pyclaw-provider-secret -o jsonpath='{.data}'
```

只包含：

```json
{
  "OPENAI_API_KEY": "...",
  "OPENAI_BASE_URL": "..."
}
```

没有：

```text
PYCLAW_API_TOKEN
OPENCLAW_INTERNAL_API_TOKEN
OPENCLAW_CHANNEL_CONFIG_TOKEN
```

因此：

1. `pyclaw-channel-worker` 启动时无法读取 Spring Channel 配置，直接退出。
2. 给 Secret 补 token 后，如果只重启 worker，`deployment/pyclaw` 这个已运行的 pyclaw-api Pod 仍然没有新环境变量。
3. Kubernetes Secret 通过环境变量注入时不会热更新到已运行容器，必须重启对应 Deployment。
4. 飞书 webhook 到达 pyclaw-api 后，pyclaw-api 仍因缺 token 无法读取 Spring 里的飞书运行时配置，返回 400。

这不是 ECS 重启导致 Secret 丢失。ECS 重启只会让 Pod 重建，从而暴露当前 Secret/Deployment 配置不完整的问题。

## 4. 排查过程

### 4.1 查看 Pod 状态

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods -o wide
```

重点关注：

```text
pyclaw
pyclaw-channel-worker
pyclaw-spring-backend
pyclaw-mysql
```

### 4.2 查看 worker 日志

当前日志：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw-channel-worker --since=30m --tail=300
```

上一次崩溃日志：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs pyclaw-channel-worker-<pod-name> --previous --tail=200
```

出现过的关键错误：

```text
ValueError: OPENCLAW_CHANNEL_CONFIG_TOKEN or PYCLAW_API_TOKEN is required for Spring channel config
```

### 4.3 查看 worker 引用的 Secret

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw describe deployment pyclaw-channel-worker
```

确认：

```text
Environment Variables from:
  pyclaw-config           ConfigMap
  pyclaw-provider-secret  Secret
```

### 4.4 查看 pyclaw-provider-secret

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get secret pyclaw-provider-secret -o jsonpath='{.data}'
```

确认缺少内部 token。

### 4.5 查看 Spring Backend 使用的 Secret

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw describe deployment pyclaw-spring-backend
```

确认 Spring 使用：

```text
pyclaw-spring-backend-secret
```

查看 token：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get secret pyclaw-spring-backend-secret -o jsonpath='{.data}'
```

当时结果中：

```text
PYCLAW_API_TOKEN 有值
PYCLAW_INTERNAL_API_TOKEN 为空
```

Spring 配置逻辑为：优先使用 `PYCLAW_INTERNAL_API_TOKEN`，为空时回退到 `PYCLAW_API_TOKEN`。

### 4.6 查看 pyclaw-api 环境变量

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw -- sh -c 'printenv | grep -E "OPENCLAW_CHANNEL_CONFIG_TOKEN|OPENCLAW_INTERNAL_API_TOKEN|PYCLAW_API_TOKEN|OPENCLAW_CHANNEL_CONFIG_SOURCE"'
```

当时只看到：

```text
OPENCLAW_CHANNEL_CONFIG_SOURCE=spring
```

说明 pyclaw-api 也没有拿到 token。

### 4.7 飞书 webhook 日志判断

pyclaw-api 日志中出现：

```text
failed Feishu webhook: error=OPENCLAW_CHANNEL_CONFIG_TOKEN or PYCLAW_API_TOKEN is required for Spring channel config
body_shape={'json': True, 'keys': ['encrypt'], 'event_keys': [], 'header_keys': [], 'has_encrypt': True}
```

这说明：

1. 飞书事件已经打到 Spring Backend。
2. Spring Backend 已经转发到 pyclaw-api。
3. 问题不是公网入口，而是 pyclaw-api 缺少读取 Spring runtime config 的内部 token。
4. 飞书事件体是加密形式，后续还需要确保 Channel 配置中有 `encrypt_key`。

## 5. 解决方案

### 5.1 将 Spring Backend 的 token 同步到 pyclaw-provider-secret

从 Spring Secret 读取当前有效 token：

```bash
TOKEN=$(sudo /usr/local/bin/k3s kubectl -n pyclaw get secret pyclaw-spring-backend-secret -o jsonpath='{.data.PYCLAW_API_TOKEN}' | base64 -d)
```

转为 base64：

```bash
TOKEN_B64=$(printf '%s' "$TOKEN" | base64 -w0)
```

只 patch 一个 key，避免覆盖现有 `OPENAI_API_KEY` / `OPENAI_BASE_URL`：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw patch secret pyclaw-provider-secret \
  --type merge \
  -p "{\"data\":{\"PYCLAW_API_TOKEN\":\"$TOKEN_B64\"}}"
```

确认：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get secret pyclaw-provider-secret -o jsonpath='{.data}'
```

### 5.2 重启相关 Deployment

Secret 作为环境变量注入，不会自动热更新到已有容器，因此必须重启：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout restart deployment/pyclaw
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout status deployment/pyclaw
```

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout restart deployment/pyclaw-channel-worker
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout status deployment/pyclaw-channel-worker
```

### 5.3 验证 pyclaw-api 已拿到 token

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw -- sh -c 'printenv | grep -E "PYCLAW_API_TOKEN|OPENCLAW_CHANNEL_CONFIG_SOURCE"'
```

期望：

```text
OPENCLAW_CHANNEL_CONFIG_SOURCE=spring
PYCLAW_API_TOKEN=...
```

### 5.4 验证 worker 已拿到 token

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw-channel-worker -- sh -c 'printenv | grep -E "PYCLAW_API_TOKEN|OPENCLAW_CHANNEL_CONFIG_SOURCE|OPENCLAW_INGRESS_QUEUE_DSN"'
```

### 5.5 验证飞书 runtime config

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw-channel-worker -- sh -c '
python -c "import os,urllib.request; token=os.environ.get(\"PYCLAW_API_TOKEN\",\"\"); req=urllib.request.Request(\"http://pyclaw-spring-backend.pyclaw.svc.cluster.local:8080/api/internal/channels/feishu/runtime-config\", headers={\"Authorization\":\"Bearer \"+token}); print(urllib.request.urlopen(req, timeout=5).read().decode())"
'
```

期望：

```json
{
  "channel": "feishu",
  "enabled": true,
  "config": {}
}
```

其中 `config` 应包含飞书运行所需字段，例如：

```text
app_id / appId
app_secret / appSecret
verification_token / verificationToken
sign_secret / signSecret
encrypt_key / encryptKey
api_base_url / apiBaseUrl
```

如果飞书开放平台开启了事件加密，必须配置 `encrypt_key`。

### 5.6 观察日志

发送一条飞书消息后观察：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs -f deployment/pyclaw
```

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs -f deployment/pyclaw-channel-worker
```

worker 正常消费时会出现：

```text
claimed ingress event
completed ingress event
```

失败时会出现：

```text
failed ingress event
```

## 6. 后续防复发建议

### 6.1 将 token 固化到部署配置

不要只在线上手工 patch Secret。需要确保 Helm values 或外部 Secret 管理中长期包含：

```text
PYCLAW_API_TOKEN
```

或者更清晰地使用专用 token：

```text
OPENCLAW_CHANNEL_CONFIG_TOKEN
```

并确保以下两个 Deployment 都能读到：

```text
deployment/pyclaw
deployment/pyclaw-channel-worker
```

### 6.2 避免 create secret 覆盖已有 key

不要用下面这种方式修已有 Secret：

```bash
kubectl create secret generic pyclaw-provider-secret ... --dry-run=client -o yaml | kubectl apply -f -
```

如果没有带全量 key，可能覆盖掉已有的 `OPENAI_API_KEY` / `OPENAI_BASE_URL`。

推荐使用：

```bash
kubectl patch secret ...
```

只追加或更新目标 key。

### 6.3 重启依赖 Secret env 的 Deployment

更新 Secret 后，如果 Secret 是通过环境变量注入，必须重启相关 Deployment：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout restart deployment/pyclaw
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout restart deployment/pyclaw-channel-worker
```

### 6.4 增加启动容错

曾出现过 worker 启动时 Spring Backend 还未 ready，导致：

```text
ConnectionRefusedError: [Errno 111] Connection refused
```

可以考虑后续给 `pyclaw-channel-worker` 的启动配置加载增加重试，避免 Spring 短暂不可用时 worker 直接退出。

### 6.5 排查飞书无响应的推荐顺序

```text
1. kubectl get pods
2. 看 deployment/pyclaw-spring-backend 日志，确认飞书是否打到公网入口
3. 看 deployment/pyclaw 日志，确认 Spring 是否转发到 pyclaw-api
4. 看 deployment/pyclaw-channel-worker 日志，确认是否 claim/complete/failed
5. 查 ingress_queue 表，确认消息是否入队及状态
6. 查 Feishu runtime config 是否 enabled=true 且配置完整
```

关键命令：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw-spring-backend --since=30m --tail=300
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw --since=30m --tail=300
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw-channel-worker --since=30m --tail=300
```

队列查询：

```sql
SELECT event_id, channel, lane_key, status, attempts, owner_id, error, FROM_UNIXTIME(created_at), FROM_UNIXTIME(updated_at)
FROM ingress_queue
WHERE channel = 'feishu'
ORDER BY created_at DESC
LIMIT 20;
```
