# Spring Backend K3s 部署 SOP

本文档记录如何把 `spring-backend` 部署到已有 K3s 集群，并让公网入口从直接访问 `pyclaw-api` 演进为：

```text
公网用户 -> Spring Backend 鉴权/审计/代理 -> pyclaw-api 内部 ClusterIP -> 模型服务
```

当前状态：

```text
spring-backend 代码、Dockerfile、Helm Chart 已实现。
spring-backend 尚未部署到 K3s Pod。
pyclaw-api 已支持 PYCLAW_API_TOKEN。
pyclaw-api Service 应保持 ClusterIP。
```

## 1. 前置条件

ECS 上已经具备：

```text
1. K3s 正常运行。
2. Helm 可用。
3. namespace pyclaw 已存在，或 Helm 部署时自动创建。
4. pyclaw-api 已能在 K3s 中运行。
5. ACR 私有镜像仓库已创建。
6. pyclaw namespace 中已存在 ACR 拉取 Secret：aliyun-acr-pull-secret。
```

检查：

```bash
sudo systemctl status k3s --no-pager
sudo /usr/local/bin/k3s kubectl get nodes
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
sudo /usr/local/bin/k3s kubectl -n pyclaw get secret aliyun-acr-pull-secret
```

如果 `aliyun-acr-pull-secret` 不存在，先创建：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw create secret docker-registry aliyun-acr-pull-secret \
  --docker-server=<你的 ACR Registry 地址> \
  --docker-username='<你的 ACR 用户名>' \
  --docker-password='<你的 ACR 固定密码>' \
  --docker-email='unused@example.com'
```

示例 Registry 地址格式：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
```

## 2. 关键 Secret 关系

本次部署会涉及三类 Secret。

### 2.1 aliyun-acr-pull-secret

用途：

```text
K3s/containerd 拉取 ACR 私有镜像。
```

被谁使用：

```text
Pod spec.imagePullSecrets
```

它不是应用运行时配置。

### 2.2 pyclaw-provider-secret

用途：

```text
pyclaw-api 调用模型服务。
pyclaw-api 校验 Spring -> pyclaw 的内部服务 token。
```

典型字段：

```text
OPENAI_API_KEY
OPENAI_BASE_URL
PYCLAW_API_TOKEN
```

### 2.3 spring-backend-secret

用途：

```text
Spring Backend 自身运行。
Spring Backend 签发 JWT。
Spring Backend 调用 pyclaw-api。
```

典型字段：

```text
SPRING_DATASOURCE_PASSWORD
JWT_SIGNING_SECRET
BOOTSTRAP_ADMIN_PASSWORD
PYCLAW_API_TOKEN
```

注意：

```text
pyclaw-provider-secret.PYCLAW_API_TOKEN
spring-backend-secret.PYCLAW_API_TOKEN
必须是同一个值。
```

## 3. 生成内部服务 Token

在 ECS 上生成一个随机 token：

```bash
openssl rand -hex 32
```

记为：

```text
<INTERNAL_PYCLAW_API_TOKEN>
```

这个 token 的作用：

```text
Spring Backend 调用 pyclaw-api 时携带：
Authorization: Bearer <INTERNAL_PYCLAW_API_TOKEN>

pyclaw-api 收到请求后用 PYCLAW_API_TOKEN 校验。
```

## 4. 更新 pyclaw-api 内部 Token

进入项目目录：

```bash
cd /opt/pyclaw
```

创建或更新 `pyclaw-values-k3s.yaml` 中的 pyclaw secret 配置，确保包含：

```yaml
secret:
  create: true
  values:
    OPENAI_API_KEY: "<你的模型 API Key>"
    OPENAI_BASE_URL: "https://api.deepseek.com"
    PYCLAW_API_TOKEN: "<INTERNAL_PYCLAW_API_TOKEN>"
```

同时确认 pyclaw 不直接暴露公网：

```yaml
service:
  type: ClusterIP

ingress:
  enabled: false
```

升级 pyclaw：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-values-k3s.yaml
```

验证 pyclaw Pod 正常：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
sudo /usr/local/bin/k3s kubectl -n pyclaw get svc
```

预期：

```text
pyclaw Pod READY 1/1
pyclaw Service TYPE 为 ClusterIP
```

## 5. 构建 Spring Backend 镜像

在 ECS 的 `/opt/pyclaw` 执行：

```bash
cd /opt/pyclaw
```

设置镜像变量：

```bash
export ACR_REGISTRY="<你的 ACR Registry 地址>"
export SPRING_IMAGE="${ACR_REGISTRY}/pyclaw/spring-backend"
export SPRING_TAG="0.1.0"
```

示例：

```bash
export ACR_REGISTRY="crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com"
export SPRING_IMAGE="${ACR_REGISTRY}/pyclaw/spring-backend"
export SPRING_TAG="0.1.0"
```

构建镜像：

```bash
sudo docker build -t "${SPRING_IMAGE}:${SPRING_TAG}" ./spring-backend
```

登录 ACR：

```bash
sudo docker login "${ACR_REGISTRY}" \
  -u '<你的 ACR 用户名>' \
  -p '<你的 ACR 固定密码>'
```

推送镜像：

```bash
sudo docker push "${SPRING_IMAGE}:${SPRING_TAG}"
```

## 6. 准备 Spring Helm values

在 `/opt/pyclaw` 创建 `spring-values-k3s.yaml`：

```yaml
replicaCount: 1

image:
  repository: <你的 ACR Registry 地址>/pyclaw/spring-backend
  tag: "0.1.0"
  pullPolicy: IfNotPresent

imagePullSecrets:
  - name: aliyun-acr-pull-secret

service:
  type: ClusterIP
  port: 8080

ingress:
  enabled: true
  className: traefik
  annotations: {}
  hosts:
    - host: api.anxin-hitsz.com
      paths:
        - path: /
          pathType: Prefix
  tls: []

env:
  SPRING_DATASOURCE_URL: "jdbc:h2:file:/data/pyclaw-backend;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
  SPRING_DATASOURCE_USERNAME: "sa"
  SPRING_JPA_DDL_AUTO: "update"
  PYCLAW_BASE_URL: "http://pyclaw.pyclaw.svc.cluster.local:8000"
  BOOTSTRAP_ADMIN_USERNAME: "admin"

secret:
  create: true
  values:
    SPRING_DATASOURCE_PASSWORD: ""
    JWT_SIGNING_SECRET: "<随机 JWT 签名密钥>"
    BOOTSTRAP_ADMIN_PASSWORD: "<后台 admin 初始密码>"
    PYCLAW_API_TOKEN: "<INTERNAL_PYCLAW_API_TOKEN>"

persistence:
  enabled: true
  storageClassName: ""
  size: 2Gi
  mountPath: /data

resources:
  requests:
    cpu: 200m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi
```

生成 JWT 签名密钥：

```bash
openssl rand -hex 32
```

注意：

```text
JWT_SIGNING_SECRET 不要使用默认值。
BOOTSTRAP_ADMIN_PASSWORD 不要使用默认值。
PYCLAW_API_TOKEN 必须与 pyclaw-api 使用的 PYCLAW_API_TOKEN 一致。
```

## 7. 部署 Spring Backend

执行：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install spring-backend ./spring-backend/helm \
  -n pyclaw \
  --create-namespace \
  -f spring-values-k3s.yaml
```

查看资源：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
sudo /usr/local/bin/k3s kubectl -n pyclaw get svc
sudo /usr/local/bin/k3s kubectl -n pyclaw get ingress
```

预期：

```text
Pod: spring-backend-xxx READY 1/1
Service: spring-backend TYPE ClusterIP PORT 8080
Ingress: spring-backend HOST api.anxin-hitsz.com
```

如果 Pod 没有 Ready，查看：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw describe pod <spring-backend-pod-name>
sudo /usr/local/bin/k3s kubectl -n pyclaw logs <spring-backend-pod-name> --tail=200
```

## 8. 集群内验证

先做端口转发：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw port-forward svc/spring-backend 8080:8080
```

另开一个 tmux pane 或 SSH 窗口，执行：

```bash
curl http://127.0.0.1:8080/healthz
```

预期：

```json
{"status":"ok","service":"pyclaw-spring-backend"}
```

登录：

```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<后台 admin 初始密码>"}'
```

响应中应有：

```text
accessToken
```

保存 JWT：

```bash
export JWT="<上一步返回的 accessToken>"
```

调用 Agent：

```bash
curl -X POST http://127.0.0.1:8080/api/agent/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT}" \
  -d '{"prompt":"你好，请用一句话介绍 pyclaw。","provider":"openai","sessionId":"spring-k3s-test","toolProfile":"minimal"}'
```

预期：

```text
返回 sessionId、message、text、latencyMs。
```

## 9. 公网验证

确认 DNS：

```text
api.anxin-hitsz.com -> ECS 公网 IP
```

访问健康检查：

```bash
curl http://api.anxin-hitsz.com/healthz
```

登录：

```bash
curl -X POST http://api.anxin-hitsz.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<后台 admin 初始密码>"}'
```

公网 Agent 调用必须走 Spring：

```bash
curl -X POST http://api.anxin-hitsz.com/api/agent/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT>" \
  -d '{"prompt":"hello","provider":"openai","sessionId":"public-test","toolProfile":"minimal"}'
```

未带 JWT 时应失败：

```bash
curl -i -X POST http://api.anxin-hitsz.com/api/agent/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"hello"}'
```

预期：

```text
403 或 401。
```

## 10. 确认 pyclaw 不再公网裸露

确认 pyclaw Ingress 已关闭：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get ingress
```

预期：

```text
只看到 spring-backend 的 Ingress。
不要看到 pyclaw 直连 Ingress。
```

确认 pyclaw Service 是 ClusterIP：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get svc pyclaw
```

预期：

```text
TYPE 为 ClusterIP。
```

## 11. 常见问题

### 11.1 Spring Pod ImagePullBackOff

查看：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw describe pod <spring-backend-pod-name>
```

常见原因：

```text
1. image.repository 写错。
2. image.tag 写错。
3. ACR 镜像未 push。
4. aliyun-acr-pull-secret 不存在或密码错误。
5. imagePullSecrets 没有配置 aliyun-acr-pull-secret。
```

### 11.2 Spring 启动失败

查看日志：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs <spring-backend-pod-name> --tail=200
```

常见原因：

```text
1. JWT_SIGNING_SECRET 为空或仍为默认值。
2. H2 数据目录权限异常。
3. PVC 未绑定。
4. Java 应用端口未正常启动。
```

### 11.3 Agent 调用返回 502

含义：

```text
Spring Backend 调 pyclaw-api 失败。
```

检查：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get svc pyclaw
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
sudo /usr/local/bin/k3s kubectl -n pyclaw logs <spring-backend-pod-name> --tail=200
sudo /usr/local/bin/k3s kubectl -n pyclaw logs <pyclaw-pod-name> --tail=200
```

重点确认：

```text
1. PYCLAW_BASE_URL 是否为 http://pyclaw.pyclaw.svc.cluster.local:8000。
2. Spring 的 PYCLAW_API_TOKEN 是否与 pyclaw 的 PYCLAW_API_TOKEN 一致。
3. pyclaw-provider-secret 中 OPENAI_API_KEY / OPENAI_BASE_URL 是否正确。
```

### 11.4 登录失败

检查：

```text
1. BOOTSTRAP_ADMIN_USERNAME 是否为 admin。
2. BOOTSTRAP_ADMIN_PASSWORD 是否是首次创建 admin 时的密码。
```

注意：

```text
Bootstrap 管理员只在用户不存在时创建。
如果 admin 已经存在，修改 Helm Secret 中 BOOTSTRAP_ADMIN_PASSWORD 不会自动改旧用户密码。
```

## 12. 回滚

如果 Spring 部署失败，先关闭 Spring Ingress：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install spring-backend ./spring-backend/helm \
  -n pyclaw \
  -f spring-values-k3s.yaml \
  --set ingress.enabled=false
```

或直接卸载 Spring：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm uninstall spring-backend -n pyclaw
```

确认：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
sudo /usr/local/bin/k3s kubectl -n pyclaw get ingress
```

如果需要临时恢复 pyclaw 直连公网，可重新开启 pyclaw Ingress，但必须保留 `PYCLAW_API_TOKEN`：

```text
不建议长期恢复 pyclaw 直连公网。
```

## 13. 部署完成标准

满足以下条件才算完成：

```text
1. spring-backend Pod READY 1/1。
2. spring-backend Service 为 ClusterIP。
3. api.anxin-hitsz.com Ingress 指向 spring-backend。
4. GET /healthz 公网返回 ok。
5. POST /api/auth/login 可以登录。
6. POST /api/agent/run 不带 JWT 会失败。
7. POST /api/agent/run 带 JWT 可以成功调用 Agent。
8. pyclaw Service 为 ClusterIP。
9. pyclaw 没有直连公网 Ingress。
```
