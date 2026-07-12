# pyclaw Helm Chart 实现记录

本文档记录 `pyclaw` 从单机 Docker 运行进入 Kubernetes / Helm 部署形态时的第一版实现。

## 1. 本阶段目标

前一阶段已经验证：

- `pyclaw-api:dev` 镜像可以构建。
- 容器可以通过 `uvicorn openclaw.api:app --host 0.0.0.0 --port 8000` 启动。
- `/healthz` 可以返回健康状态。
- `/v1/agent/run` 可以调用 mock provider。
- 注入真实 `.env` 后，可以调用真实 OpenAI provider。
- 通过宿主机目录挂载，可以把 transcript 持久化到容器外部。

本阶段目标是把下面这条单机 Docker 命令：

```bash
sudo docker run -d \
  --name pyclaw-api-real \
  --restart unless-stopped \
  -p 8000:8000 \
  --env-file /opt/pyclaw/.env \
  -v /opt/pyclaw/chatdata-docker:/app/chatdata \
  pyclaw-api:dev
```

转换为 Kubernetes 中的资源模型，并用 Helm 统一管理。

## 2. 新增目录

新增目录：

```text
helm/
  pyclaw/
    Chart.yaml
    values.yaml
    templates/
      _helpers.tpl
      configmap.yaml
      deployment.yaml
      ingress.yaml
      NOTES.txt
      pvc.yaml
      secret.yaml
      service.yaml
      serviceaccount.yaml
```

## 3. Docker 参数到 Kubernetes 对象的对应关系

| Docker 参数 | Kubernetes / Helm 对象 | 说明 |
| --- | --- | --- |
| `pyclaw-api:dev` | `image.repository` + `image.tag` | 镜像仓库和镜像 tag。 |
| `-p 8000:8000` | `Service` / `Ingress` | Service 负责集群内访问，Ingress 负责域名入口。 |
| `--env-file /opt/pyclaw/.env` | `ConfigMap` + `Secret` | 非敏感配置进入 ConfigMap，API key 等敏感配置进入 Secret。 |
| `-v /opt/pyclaw/chatdata-docker:/app/chatdata` | `PersistentVolumeClaim` + `volumeMounts` | transcript/session 数据通过 PVC 持久化。 |
| `--restart unless-stopped` | `Deployment` | Pod 异常退出后由 Deployment 自动拉起。 |
| `--name pyclaw-api-real` | Helm release name | 例如 `helm upgrade --install pyclaw ./helm/pyclaw` 中的 `pyclaw`。 |

## 4. Chart.yaml

`Chart.yaml` 定义 Helm Chart 的基本元数据：

```yaml
apiVersion: v2
name: pyclaw
type: application
version: 0.1.0
appVersion: "0.1.0"
```

其中：

- `version` 是 Chart 自身版本。
- `appVersion` 是被部署应用的版本。
- 两者可以相同，也可以独立演进。

## 5. values.yaml 设计

`values.yaml` 是 Helm Chart 的默认配置入口。

### 5.1 镜像配置

```yaml
image:
  repository: pyclaw-api
  pullPolicy: IfNotPresent
  tag: dev
```

本地测试时可以继续用：

```text
pyclaw-api:dev
```

后续推送到镜像仓库后，可以改为：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/<namespace>/pyclaw-api
  tag: 0.1.0

imagePullSecrets:
  - name: aliyun-acr-pull-secret
```

当前项目可以使用已开通的阿里云 ACR 个人版实例：

```text
公网地址: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
地域: 华南 1（深圳）
```

如果 ACR 仓库是私有仓库，需要先在目标 namespace 中创建 `docker-registry` 类型 Secret，并在 values 中通过 `imagePullSecrets` 引用。

### 5.2 服务配置

```yaml
service:
  type: ClusterIP
  port: 8000
  targetPort: 8000
```

`ClusterIP` 表示服务只在集群内部暴露。外部访问建议通过 Ingress。

### 5.3 环境变量

非敏感配置放在：

```yaml
env:
  OPENCLAW_CHATDATA_DIR: /app/chatdata
  OPENAI_MODEL: gpt-4.1-mini
  OPENAI_API_MODE: responses
```

敏感配置放在：

```yaml
secret:
  values:
    OPENAI_API_KEY: ""
    OPENAI_BASE_URL: ""
    OPENAI_ORGANIZATION: ""
    OPENAI_PROJECT: ""
```

注意：`OPENAI_BASE_URL` 只有在使用 OpenAI-compatible 服务时才建议设置。如果使用 OpenAI 官方 Responses API，可以不设置。

### 5.4 持久化配置

```yaml
persistence:
  enabled: true
  size: 5Gi
  mountPath: /app/chatdata
```

它对应单机 Docker 中的：

```bash
-v /opt/pyclaw/chatdata-docker:/app/chatdata
```

区别是：

- Docker bind mount 直接使用宿主机目录。
- Kubernetes PVC 使用集群存储，由 StorageClass 或管理员提供底层卷。

### 5.5 探针配置

```yaml
readinessProbe:
  path: /healthz

livenessProbe:
  path: /healthz
```

`readinessProbe` 判断 Pod 是否可以接收流量。

`livenessProbe` 判断 Pod 是否仍然健康；如果探测失败，Kubernetes 可以重启容器。

## 6. ConfigMap

`templates/configmap.yaml` 根据 `values.yaml` 中的 `env` 生成 ConfigMap：

```yaml
data:
  OPENCLAW_CHATDATA_DIR: "/app/chatdata"
  OPENAI_MODEL: "gpt-4.1-mini"
  OPENAI_API_MODE: "responses"
```

Deployment 中通过：

```yaml
envFrom:
  - configMapRef:
      name: ...
```

把这些配置注入容器环境变量。

## 7. Secret

`templates/secret.yaml` 生成 Opaque Secret。

实现时只写入非空 secret 值，避免把空字符串形式的 `OPENAI_BASE_URL` 注入容器。原因是当前 Python provider 会读取环境变量，如果环境变量存在但为空字符串，可能被误认为显式设置了 base URL。

生产环境中更推荐使用：

```yaml
secret:
  create: false
  existingSecret: pyclaw-provider-secret
```

然后在集群中提前创建 Secret：

```bash
kubectl -n pyclaw create secret generic pyclaw-provider-secret \
  --from-literal=OPENAI_API_KEY='你的_key'
```

## 8. Deployment

`templates/deployment.yaml` 负责运行 `pyclaw-api` Pod。

核心内容包括：

- `replicas`
- 容器镜像
- 容器端口 `8000`
- `envFrom` 注入 ConfigMap / Secret
- `/app/chatdata` volumeMount
- readiness / liveness probe
- resource requests / limits
- container securityContext

默认安全设置：

```yaml
runAsNonRoot: true
runAsUser: 1000
runAsGroup: 1000
allowPrivilegeEscalation: false
capabilities:
  drop:
    - ALL
```

这与 Dockerfile 中的 `USER pyclaw` 对齐，目标是让服务默认不以 root 身份运行。

## 9. Service

`templates/service.yaml` 生成 ClusterIP Service：

```yaml
ports:
  - port: 8000
    targetPort: http
```

Deployment 中容器端口命名为 `http`，Service 使用命名端口转发。

## 10. Ingress

Ingress 默认关闭：

```yaml
ingress:
  enabled: false
```

启用示例：

```yaml
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: pyclaw.example.com
      paths:
        - path: /
          pathType: Prefix
```

如果需要 HTTPS，可以配置：

```yaml
ingress:
  tls:
    - secretName: pyclaw-tls
      hosts:
        - pyclaw.example.com
```

## 11. PVC

`templates/pvc.yaml` 在 `persistence.enabled=true` 且未指定 `existingClaim` 时创建 PVC。

默认：

```yaml
accessModes:
  - ReadWriteOnce
size: 5Gi
```

如果集群已有 PVC，可以配置：

```yaml
persistence:
  enabled: true
  existingClaim: pyclaw-chatdata
```

## 12. ServiceAccount

当前 Chart 创建独立 ServiceAccount，但不绑定额外 RBAC 权限。

原因是当前 `pyclaw-api` 只是对外提供 Agent API，并没有直接管理 Kubernetes 资源。

后续如果需要让 pyclaw 在集群内创建 Job、读取 Pod 或操作 Deployment，再单独补：

- Role
- RoleBinding
- ClusterRole
- ClusterRoleBinding

## 13. 本地模板渲染验证

如果本机或云服务器安装了 Helm，可以先执行：

```bash
helm template pyclaw ./helm/pyclaw
```

如果需要传入真实配置，不建议把 key 写入命令历史。更推荐创建一个不提交 Git 的 values 文件，例如：

```yaml
# values-prod.yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/<namespace>/pyclaw-api
  tag: 0.1.0

imagePullSecrets:
  - name: aliyun-acr-pull-secret

secret:
  values:
    OPENAI_API_KEY: "你的_key"
```

然后执行：

```bash
helm template pyclaw ./helm/pyclaw -f values-prod.yaml
```

## 14. 部署命令

创建 namespace 并部署：

```bash
helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  --create-namespace
```

如果镜像没有推送到远程仓库，而是在单机 K8s 节点上本地构建，需要确保节点能看到 `pyclaw-api:dev` 镜像。

当前推荐把镜像推送到阿里云 ACR 个人版：

```bash
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
ACR_NAMESPACE=<namespace>
IMAGE_TAG=0.1.0

docker login ${ACR_REGISTRY}
docker build -t pyclaw-api:dev .
docker tag pyclaw-api:dev ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
docker push ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
```

## 15. 验证命令

查看资源：

```bash
kubectl -n pyclaw get pods
kubectl -n pyclaw get svc
kubectl -n pyclaw get pvc
```

查看日志：

```bash
kubectl -n pyclaw logs deploy/pyclaw
```

端口转发：

```bash
kubectl -n pyclaw port-forward svc/pyclaw 8000:8000
```

健康检查：

```bash
curl http://localhost:8000/healthz
```

真实调用：

```bash
curl -X POST http://localhost:8000/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"你好，请用一句话介绍你自己。","provider":"openai","session_id":"k8s-real-demo","tool_profile":"minimal"}'
```

## 16. 当前边界

本阶段只是完成 Helm Chart 第一版，不包含：

- 镜像推送到远程仓库。
- 安装 K8s / K3s。
- 安装 Ingress Controller。
- 配置 HTTPS 证书。
- API 鉴权。
- 异步任务队列。
- 多副本 transcript 一致性处理。

## 17. 下一步

建议下一步根据你的云服务器情况选择：

1. 单机学习路线：安装 K3s，在当前 ECS 上用 Helm 部署。
2. 托管集群路线：创建阿里云 ACK 集群，推送镜像到镜像仓库，再用 Helm 部署。

如果目标是快速理解 Kubernetes 对象，建议先走 K3s 单机路线；如果目标是更贴近生产云原生交付，建议走 ACK 路线。
