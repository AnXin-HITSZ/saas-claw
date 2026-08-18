# saas-claw K8s 部署清单

单节点 K3s（已装 local-path 默认 StorageClass）。MySQL / Redis 跑在 ECS host 上，
集群内 Pod 通过 **私网 IP 172.22.12.159** 访问（不绕公网）。

## 文件与 apply 顺序

编号即 apply 顺序，逐个 `kubectl apply -f`：

| 顺序 | 文件 | 说明 |
|------|------|------|
| 00 | `00-namespace.yaml` | Namespace `saas-claw` |
| 01 | `01-secret.yaml` | **需先由模板生成，见下** |
| 02 | `02-configmap.yaml` | 非敏感共享配置 |
| 03 | `03-rbac.yaml` | backend 动态建/删 Claw 资源的 ClusterRole |
| 10 | `10-backend.yaml` | backend Deployment + ClusterIP Service |
| 11 | `11-gateway.yaml` | gateway Deployment + NodePort(30888) Service |
| 12 | `12-frontend.yaml` | frontend Deployment + NodePort(30080) Service |

## 第一步：生成真实 Secret（不入库）

`01-secret.example.yaml` 里全是 `CHANGE_ME` 占位符。复制成实名文件再填真值，
实名文件已被 `.gitignore` 排除，不会提交：

```bash
cp 01-secret.example.yaml 01-secret.yaml
# 编辑 01-secret.yaml，替换所有 CHANGE_ME：
#   JWT_SECRET            openssl rand -base64 32（backend 与 gateway 必须同一把）
#   DB_PASSWORD / REDIS_PASSWORD / OSS_* / CLAW_K8S_* 见模板注释
vim 01-secret.yaml
```

## 一键部署

```bash
kubectl apply -f 00-namespace.yaml
kubectl apply -f 01-secret.yaml
kubectl apply -f 02-configmap.yaml
kubectl apply -f 03-rbac.yaml
kubectl apply -f 10-backend.yaml
kubectl apply -f 11-gateway.yaml
kubectl apply -f 12-frontend.yaml

kubectl -n saas-claw get pods -w
```

## 访问入口

| 用途 | 地址 |
|------|------|
| 站点（浏览器） | `http://8.135.60.136:30080` |
| gateway 直连（Bearer 调 `/v1/chat/completions` 验收） | `http://8.135.60.136:30888` |

> ECS 安全组需放行 30080 / 30888。

## 端到端验收

1. 浏览器打开 `http://8.135.60.136:30080` → 注册 → 登录拿 JWT
2. 创建 Claw / Agent → backend 经 RBAC 建 `claw-{id}` namespace + runtime Pod/Service
3. 用 Claw 的 key 直连 gateway：
   ```bash
   curl -N http://8.135.60.136:30888/v1/chat/completions \
     -H "Authorization: Bearer <claw-key>" \
     -H "Content-Type: application/json" \
     -d '{"model":"...","messages":[{"role":"user","content":"hi"}],"stream":true}'
   ```
   预期收到 SSE 流式回复。

## 关键约定

- **JWT_SECRET**：backend 签发、gateway 校验，必须同一把。二者都从 `saas-claw-secret` 注入。
- **SPRING_PROFILES_ACTIVE=prod**：ConfigMap 显式覆盖，避免误加载 backend 本地 `local` profile。
- **CLAW_CALLBACK_URL_TEMPLATE**：集群内 FQDN `claw-{id}.claw-{id}.svc.cluster.local:8000`
  （裸名只在同 namespace 可达，跨 namespace 必须写全）。
- 镜像 tag 此处占位 `latest`，CI（步骤 D）按 commit 覆盖。
