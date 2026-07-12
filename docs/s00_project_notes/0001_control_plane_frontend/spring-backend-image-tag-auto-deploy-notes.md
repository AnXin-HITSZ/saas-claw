# Spring Backend 镜像 tag 自动部署说明

本文记录 Spring Backend 在 GitHub Actions 中如何使用当前提交哈希作为镜像 tag，并在 Helm 部署时覆盖 `spring-values-k3s.yaml` 中的静态 `image.tag`。

## 背景

ECS 上的 `spring-values-k3s.yaml` 主要保存环境相关配置，例如：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/spring-backend
  tag: 0.1.0
```

如果手动执行下面的命令：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install spring-backend ./spring-backend/helm \
  -n pyclaw \
  --create-namespace \
  -f spring-values-k3s.yaml
```

Helm 会直接使用 `spring-values-k3s.yaml` 中的 `image.tag`。如果 ACR 中不存在 `spring-backend:0.1.0`，Pod 会进入 `ErrImagePull` 或 `ImagePullBackOff`。

## 自动部署策略

`.github/workflows/deploy-spring-backend.yml` 使用：

```yaml
IMAGE_TAG: ${{ github.sha }}
```

构建并推送镜像：

```text
<ACR_REGISTRY>/pyclaw/spring-backend:<github.sha>
```

部署到 ECS K3s 时，再通过 Helm 参数覆盖 values 文件中的镜像配置：

```bash
--set-string image.repository=<ACR_REGISTRY>/pyclaw/spring-backend
--set-string image.tag=<github.sha>
```

因此，自动部署流程不依赖 `spring-values-k3s.yaml` 中写死的 `image.tag`。

## 为什么使用 --set-string

`--set-string` 会强制把值作为字符串传给 Helm。

commit hash 本来就是字符串，用 `--set-string image.tag=...` 可以避免 Helm 对值做类型推断，也能让部署意图更清晰：

```text
本次 Spring Backend Pod 必须使用本次 GitHub Actions 刚推送的镜像 tag。
```

## 正确的自动部署流程

```text
本地提交并 push 到 main
  -> GitHub Actions 触发 Deploy Spring Backend
  -> 构建 spring-backend 镜像
  -> 推送到 ACR，tag 为 github.sha
  -> SSH 到 ECS
  -> git reset --hard 到同一个 github.sha
  -> helm upgrade --install
  -> --set-string image.tag=github.sha
```

## 手动部署注意事项

如果必须在 ECS 上手动部署，不要只运行 `-f spring-values-k3s.yaml`，而应显式覆盖镜像 tag。

示例：

```bash
cd /opt/pyclaw

export ACR_REGISTRY="crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com"
export SPRING_IMAGE_REPOSITORY="${ACR_REGISTRY}/pyclaw/spring-backend"
export SPRING_IMAGE_TAG="$(git rev-parse HEAD)"

sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install spring-backend ./spring-backend/helm \
  -n pyclaw \
  --create-namespace \
  -f spring-values-k3s.yaml \
  --set-string image.repository="${SPRING_IMAGE_REPOSITORY}" \
  --set-string image.tag="${SPRING_IMAGE_TAG}"
```

前提是 ACR 中确实存在：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/spring-backend:<git rev-parse HEAD>
```

如果不确定当前 commit 是否已有镜像，优先使用 GitHub Actions 的 `Run workflow` 重新部署。

## 验证命令

查看当前 Deployment 使用的镜像：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get deployment pyclaw-spring-backend \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

预期格式：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/spring-backend:<commit-hash>
```

查看 rollout：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout status deployment/pyclaw-spring-backend
```

查看公网健康检查：

```bash
curl -i https://api.anxin-hitsz.com/healthz
```