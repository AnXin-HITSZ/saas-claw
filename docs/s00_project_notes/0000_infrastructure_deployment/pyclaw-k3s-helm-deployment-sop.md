# pyclaw K3s + Helm 单机部署技术文档

本文档记录如何在一台阿里云 ECS 上使用 K3s 搭建单机 Kubernetes 环境，并使用 Helm 部署 `pyclaw-api`。

本文档面向当前项目状态：

- `pyclaw` 源码位于 ECS 的 `/opt/pyclaw`
- Docker 镜像 `pyclaw-api:dev` 已经可以在 ECS 上构建和运行
- 已开通阿里云 ACR 个人版实例，推荐将镜像推送到 ACR 后再由 K3s 拉取
- `pyclaw-api` 已经通过 Docker 验证：
  - `GET /healthz`
  - `POST /v1/agent/run`
  - mock provider
  - 真实 OpenAI provider
  - transcript 持久化挂载目录
- 项目中已经存在 Helm Chart：
  - `/opt/pyclaw/helm/pyclaw`

## 1. 整体目标

当前目标不是直接做生产级高可用 Kubernetes，而是在单台 ECS 上完成从 Docker 到 Kubernetes 的过渡：

```text
ECS
  Docker
    pyclaw-api:dev 镜像
  阿里云 ACR 个人版
    crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/<namespace>/pyclaw-api:<tag>
  K3s
    Deployment
    Service
    ConfigMap
    Secret
    PVC
    Helm Release
      pyclaw-api Pod
```

完成后，`pyclaw` 将不再通过 `docker run` 手动启动，而是由 Kubernetes 管理。

## 2. 为什么选择 K3s

K3s 是轻量级 Kubernetes 发行版。它仍然是 Kubernetes，但把很多安装和维护细节做了封装。

对当前阶段来说，K3s 的价值是：

1. 可以在单台 ECS 上运行。
2. 资源占用比完整 kubeadm 集群更低。
3. 默认自带 containerd、CoreDNS、Ingress Controller、local-path-provisioner 等基础组件。
4. 仍然可以学习标准 Kubernetes 对象：
   - Pod
   - Deployment
   - Service
   - ConfigMap
   - Secret
   - PVC
   - Ingress
   - Namespace
   - Helm

因此，K3s 适合作为 pyclaw 从 Docker 进入 Kubernetes 的第一步。

## 3. 部署前置条件

### 3.1 ECS 基础条件

建议 ECS 至少具备：

```text
CPU: 2 vCPU 或以上
Memory: 4 GiB 或以上更舒适
OS: Ubuntu 22.04 / Debian 系列
Disk: 40 GiB 或以上
```

如果只有 1 GiB 或 2 GiB 内存，也可以实验，但后续如果再安装 Rancher 会比较吃紧。

### 3.2 已完成内容

确认 Docker 可用：

```bash
sudo docker run hello-world
```

确认项目目录存在：

```bash
ls -la /opt/pyclaw
```

确认镜像存在：

```bash
sudo docker images | grep pyclaw-api
```

预期能看到：

```text
pyclaw-api   dev   ...
```

确认 ACR 个人版实例可用：

```text
地域: 华南 1（深圳）
公网地址: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
专有网络地址: crpi-li78f6lp5zheaj11-vpc.cn-shenzhen.personal.cr.aliyuncs.com
```

还需要在 ACR 控制台完成：

1. 创建命名空间，例如 `pyclaw`。
2. 创建镜像仓库，例如 `pyclaw-api`。
3. 在“访问凭证”中设置固定密码。

本文命令默认使用公网地址。若 ECS 与 ACR 在同地域、同 VPC，并且网络连通，也可以改用专有网络地址。

### 3.3 ACR 当前仓库为空时的创建流程

如果 ACR 控制台的“镜像仓库”页面显示“没有数据”，说明当前还没有可接收镜像推送的仓库。需要先在控制台创建命名空间和镜像仓库。

#### 3.3.1 创建命名空间

在 ACR 控制台左侧进入：

```text
仓库管理 -> 命名空间
```

点击“创建命名空间”，建议填写：

```text
命名空间: pyclaw
```

命名空间会成为镜像地址中的中间一段：

```text
<registry>/<namespace>/<repository>:<tag>
```

例如：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-api:0.1.0
```

#### 3.3.2 创建镜像仓库

在 ACR 控制台左侧进入：

```text
仓库管理 -> 镜像仓库
```

点击“创建镜像仓库”，建议填写：

```text
命名空间: pyclaw
仓库名称: pyclaw-api
仓库类型: 私有
摘要/描述: pyclaw API service
代码源: 本地仓库
```

这里的“私有”表示拉取镜像时需要认证。后续 K3s 需要通过 `imagePullSecrets` 使用 ACR 凭证拉取镜像。

创建完成后，镜像仓库页面应能看到 `pyclaw-api`。进入仓库详情页后，通常也能看到类似下面的推送地址：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-api
```

#### 3.3.3 设置访问凭证

在 ACR 控制台左侧进入：

```text
访问凭证
```

点击“设置固定密码”。后续命令中：

```text
Username: AnXin_HITSZ
Password: 这里填写 ACR 固定密码
```

不要使用 ECS 的 `root` 密码，也不要把 ACR 固定密码写入 Git。

#### 3.3.4 ECS 上对应的变量

完成命名空间和镜像仓库创建后，ECS 上可以使用：

```bash
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
ACR_NAMESPACE=pyclaw
IMAGE_TAG=0.1.0
```

完整镜像地址会是：

```bash
${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
```

确认 Helm Chart 存在：

```bash
ls -la /opt/pyclaw/helm/pyclaw
```

预期能看到：

```text
Chart.yaml
values.yaml
templates/
```

## 4. 安装 K3s

### 4.1 官方安装方式

在 ECS 上执行：

```bash
curl -sfL https://get.k3s.io | sh -
```

该脚本会安装并启动 `k3s` systemd 服务。

### 4.2 国内网络备用安装方式

如果官方安装脚本或 GitHub 下载不稳定，可以先用官方脚本下载 installer，再用 Rancher 国内镜像站下载 `k3s` 二进制。

```bash
curl -fL https://get.k3s.io -o /tmp/k3s-install.sh
```

如果脚本下载成功，但安装时卡在 GitHub 下载二进制，可以手动下载二进制：

```bash
export K3S_VERSION=v1.36.2-k3s1
curl -fL "https://rancher-mirror.rancher.cn/k3s/${K3S_VERSION}/k3s" -o /usr/local/bin/k3s
chmod +x /usr/local/bin/k3s
/usr/local/bin/k3s --version
```

然后跳过二进制下载，只执行安装和 systemd 配置：

```bash
INSTALL_K3S_SKIP_DOWNLOAD=true INSTALL_K3S_MIRROR=cn sh -x /tmp/k3s-install.sh
```

注意：

1. 旧的 `https://rancher-mirror.oss-cn-beijing.aliyuncs.com/k3s/k3s-install.sh` 可能返回 `403`。
2. Rancher 国内镜像站的路径使用 `v1.36.2-k3s1`，而不是 GitHub release 中显示的 `v1.36.2+k3s1`。
3. 如果 `sudo k3s` 找不到命令，可以先使用完整路径：`sudo /usr/local/bin/k3s ...`。

安装成功后应能看到：

```bash
sudo systemctl status k3s --no-pager
sudo /usr/local/bin/k3s kubectl get nodes
```

### 4.3 检查 K3s 服务

```bash
sudo systemctl status k3s --no-pager
```

如果服务正常，应看到：

```text
active (running)
```

### 4.4 检查节点

```bash
sudo k3s kubectl get nodes
```

预期类似：

```text
NAME                      STATUS   ROLES                  AGE   VERSION
iZwz9fiujj747aj9bulp8qZ   Ready    control-plane,master   1m    v1.xx.x+k3s...
```

`STATUS=Ready` 表示单机 K3s 集群已经可用。

### 4.5 检查系统 Pod

```bash
sudo k3s kubectl get pods -A
```

常见系统组件包括：

```text
kube-system   coredns-...
kube-system   local-path-provisioner-...
kube-system   metrics-server-...
kube-system   traefik-...
```

其中：

- `coredns` 负责集群 DNS。
- `local-path-provisioner` 负责单机本地 PVC。
- `metrics-server` 提供基础资源指标。
- `traefik` 是 K3s 默认安装的 Ingress Controller。

### 4.6 国内环境下修复系统镜像拉取失败

在国内 ECS 上，即使使用了 `INSTALL_K3S_MIRROR=cn`，K3s 运行时仍可能尝试从 Docker Hub 拉取系统镜像，导致系统 Pod 卡在 `ContainerCreating` / `ImagePullBackOff`。

查看系统事件：

```bash
sudo /usr/local/bin/k3s kubectl -n kube-system get events --sort-by=.lastTimestamp
```

如果看到类似：

```text
failed to pull image "rancher/mirrored-pause:3.6"
failed to pull image "rancher/mirrored-coredns-coredns:1.14.4"
failed to pull image "rancher/mirrored-metrics-server:v0.8.1"
failed to pull image "rancher/local-path-provisioner:v0.0.36"
failed to pull image "rancher/klipper-helm:v0.11.1-build20260615"
```

说明 containerd 仍在访问 Docker Hub。可以先从国内源拉取同内容镜像，再打成 K3s 期望的 `docker.io/rancher/...` tag。

先补 Pod sandbox 必需的 `pause` 镜像：

```bash
sudo /usr/local/bin/k3s ctr images pull registry.cn-hangzhou.aliyuncs.com/google_containers/pause:3.6
sudo /usr/local/bin/k3s ctr images tag \
  registry.cn-hangzhou.aliyuncs.com/google_containers/pause:3.6 \
  docker.io/rancher/mirrored-pause:3.6
```

再按事件中实际缺失的镜像补齐：

```bash
sudo /usr/local/bin/k3s ctr images pull registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-coredns-coredns:1.14.4
sudo /usr/local/bin/k3s ctr images tag \
  registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-coredns-coredns:1.14.4 \
  docker.io/rancher/mirrored-coredns-coredns:1.14.4

sudo /usr/local/bin/k3s ctr images pull registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-metrics-server:v0.8.1
sudo /usr/local/bin/k3s ctr images tag \
  registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-metrics-server:v0.8.1 \
  docker.io/rancher/mirrored-metrics-server:v0.8.1

sudo /usr/local/bin/k3s ctr images pull registry.cn-hangzhou.aliyuncs.com/rancher/local-path-provisioner:v0.0.36
sudo /usr/local/bin/k3s ctr images tag \
  registry.cn-hangzhou.aliyuncs.com/rancher/local-path-provisioner:v0.0.36 \
  docker.io/rancher/local-path-provisioner:v0.0.36

sudo /usr/local/bin/k3s ctr images pull registry.cn-hangzhou.aliyuncs.com/rancher/klipper-helm:v0.11.1-build20260615
sudo /usr/local/bin/k3s ctr images tag \
  registry.cn-hangzhou.aliyuncs.com/rancher/klipper-helm:v0.11.1-build20260615 \
  docker.io/rancher/klipper-helm:v0.11.1-build20260615
```

`local-path-provisioner` 创建 PVC 时还会临时创建 helper pod。如果 PVC 一直 Pending，并且事件中出现：

```text
failed to pull image "rancher/mirrored-library-busybox:1.37.0"
```

继续补：

```bash
sudo /usr/local/bin/k3s ctr images pull registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-library-busybox:1.37.0
sudo /usr/local/bin/k3s ctr images tag \
  registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-library-busybox:1.37.0 \
  docker.io/rancher/mirrored-library-busybox:1.37.0
```

确认镜像存在：

```bash
sudo /usr/local/bin/k3s ctr images list | grep -E 'pause|coredns|metrics-server|local-path|klipper-helm|busybox'
```

然后重启 K3s：

```bash
sudo systemctl restart k3s
```

再次检查：

```bash
sudo /usr/local/bin/k3s kubectl -n kube-system get pods
```

`coredns`、`local-path-provisioner`、`metrics-server` 应为 `Running`，`helm-install-traefik-*` 应为 `Completed`。`traefik` 若暂时卡住，不影响先通过 `port-forward` 验证 `pyclaw`。

## 5. kubectl 使用方式

K3s 自带 kubectl，可以直接使用：

```bash
sudo k3s kubectl get nodes
```

为了少打几个字，也可以配置一个别名：

```bash
echo "alias kubectl='sudo k3s kubectl'" >> ~/.bashrc
source ~/.bashrc
```

之后可以使用：

```bash
kubectl get nodes
kubectl get pods -A
```

本文后续命令会优先写 `sudo k3s kubectl`，避免依赖别名。

## 6. 安装 Helm

Helm 是 Kubernetes 的应用打包和部署工具。当前项目已经准备好 Helm Chart，因此需要在 ECS 上安装 Helm。

### 6.1 官方安装方式

```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

### 6.2 检查 Helm

```bash
helm version
```

预期能看到 Helm 版本信息。

### 6.3 如果 GitHub 下载不稳定

可以改用系统包或手动下载方式。Ubuntu 上可以先尝试：

```bash
sudo snap install helm --classic
```

如果服务器没有 snap，建议后续再根据实际网络情况选择 Helm 二进制包安装。

## 7. 让 Helm 连接 K3s

K3s 的 kubeconfig 默认在：

```text
/etc/rancher/k3s/k3s.yaml
```

由于该文件通常需要 root 权限，Helm 默认可能读不到。

推荐在当前 shell 中设置：

```bash
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
```

如果当前用户不是 root，可能仍需使用 sudo：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm list -A
```

为了简单，本文部署命令优先使用：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm ...
```

## 8. 准备 K3s 可拉取的镜像

这是最容易踩坑的地方。K3s 默认使用 **containerd**，不是 Docker daemon。

你之前用 Docker 构建了：

```bash
sudo docker build -t pyclaw-api:dev .
```

这意味着：

```text
Docker 能看到 pyclaw-api:dev
不代表 K3s/containerd 能看到 pyclaw-api:dev
```

如果直接部署，Pod 可能出现：

```text
ImagePullBackOff
ErrImagePull
```

因为 K3s 会尝试拉取 `pyclaw-api:dev`，但本地 containerd 镜像仓库里没有它。

### 8.1 推荐方案：推送镜像到阿里云 ACR

ACR 个人版可以用于当前单机 K3s 部署。它比 `docker save` + `k3s ctr images import` 更接近真实发布流程，也方便后续升级镜像。

下面命令使用前面创建的 `pyclaw` 命名空间，`0.1.0` 可以替换为实际版本号：

```bash
cd /opt/pyclaw

ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
ACR_NAMESPACE=pyclaw
IMAGE_TAG=0.1.0

sudo docker login ${ACR_REGISTRY}
sudo docker build -t pyclaw-api:dev .
sudo docker tag pyclaw-api:dev ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
sudo docker push ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
```

注意：

1. `docker login` 使用 ACR 控制台“访问凭证”中的账号和固定密码。
2. 不要把固定密码写入 Git。
3. 推荐使用递增版本 tag，例如 `0.1.0`、`0.1.1`，避免同一个 `dev` tag 被缓存后不更新。

如果 ACR 仓库是私有仓库，需要在 K3s 中创建拉取镜像用的 Secret：

```bash
sudo k3s kubectl create namespace pyclaw
sudo k3s kubectl -n pyclaw create secret docker-registry aliyun-acr-pull-secret \
  --docker-server=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com \
  --docker-username='你的 ACR 登录用户名' \
  --docker-password='你的 ACR 固定密码' \
  --docker-email='unused@example.com'
```

如果 namespace 已经存在，`create namespace` 会提示 `AlreadyExists`，可以忽略。

### 8.2 备用方案：导入 Docker 镜像到 K3s containerd

在 `/opt/pyclaw` 下执行：

```bash
cd /opt/pyclaw
sudo docker save pyclaw-api:dev -o pyclaw-api-dev.tar
sudo k3s ctr images import pyclaw-api-dev.tar
```

检查 K3s containerd 是否能看到镜像：

```bash
sudo k3s ctr images list | grep pyclaw-api
```

如果能看到 `pyclaw-api:dev`，说明导入成功。

这种方式适合临时实验；后续升级时每次都要重新导入镜像。

### 8.3 Helm 中使用 ACR 镜像

Helm values 中使用 ACR 镜像地址：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-api
  tag: 0.1.0
  pullPolicy: IfNotPresent

imagePullSecrets:
  - name: aliyun-acr-pull-secret
```

## 9. 创建 Namespace

建议单独创建 `pyclaw` 命名空间：

```bash
sudo k3s kubectl create namespace pyclaw
```

如果已经存在，会提示：

```text
Error from server (AlreadyExists)
```

这不是严重问题。

检查：

```bash
sudo k3s kubectl get ns
```

## 10. 创建 OpenAI Secret

不要把真实 API Key 写入 Git 或镜像。

K3s 部署时不要依赖 ECS 宿主机上的 `/opt/pyclaw/.env`：

1. `.env` 已被 `.dockerignore` 排除，不会进入镜像。
2. Pod 默认看不到宿主机 `/opt/pyclaw/.env`。
3. Helm 部署时，敏感配置应通过 Kubernetes Secret 注入，普通配置通过 ConfigMap 注入。

在 K3s 中创建 Secret：

```bash
sudo k3s kubectl -n pyclaw create secret generic pyclaw-provider-secret \
  --from-literal=OPENAI_API_KEY='你的真实_api_key'
```

如果你使用 OpenAI 官方 Responses API，通常只需要：

```text
OPENAI_API_KEY
```

如果你使用 OpenAI-compatible 服务，还需要 `OPENAI_BASE_URL`：

```bash
sudo k3s kubectl -n pyclaw create secret generic pyclaw-provider-secret \
  --from-literal=OPENAI_API_KEY='你的真实_api_key' \
  --from-literal=OPENAI_BASE_URL='https://你的_base_url'
```

例如 DeepSeek-compatible 服务应确认供应商要求的 base URL，并写入 `OPENAI_BASE_URL`。如果只设置：

```text
OPENAI_MODEL=deepseek-v4-flash
OPENAI_API_MODE=chat_completions
```

但没有设置 `OPENAI_BASE_URL`，OpenAI SDK 可能仍访问默认 OpenAI 官方地址，导致请求超时或模型不匹配。

注意：如果 Secret 已存在，重新创建会失败。可以先删除再创建：

```bash
sudo k3s kubectl -n pyclaw delete secret pyclaw-provider-secret
```

然后重新执行 create。

检查 Secret 是否存在：

```bash
sudo k3s kubectl -n pyclaw get secret
```

不要直接打印 Secret 内容。

检查 Pod 中实际生效的环境变量：

```bash
sudo k3s kubectl -n pyclaw exec deploy/pyclaw -- env | grep OPENAI
```

预期 OpenAI-compatible 服务至少包含：

```text
OPENAI_API_KEY=...
OPENAI_BASE_URL=...
OPENAI_API_MODE=chat_completions
OPENAI_MODEL=...
```

其中 `OPENAI_API_KEY` / `OPENAI_BASE_URL` 来自 Secret；`OPENAI_API_MODE` / `OPENAI_MODEL` 通常来自 `pyclaw-values-k3s.yaml` 渲染出的 ConfigMap。

## 11. 准备 K3s 部署 values 文件

不要直接修改 `helm/pyclaw/values.yaml` 存放真实环境配置。

在 `/opt/pyclaw` 下创建一个本地 values 文件：

```bash
cd /opt/pyclaw
nano pyclaw-values-k3s.yaml
```

OpenAI 官方 Responses API 示例：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-api
  tag: 0.1.0
  pullPolicy: IfNotPresent

imagePullSecrets:
  - name: aliyun-acr-pull-secret

secret:
  create: false
  existingSecret: pyclaw-provider-secret

env:
  OPENCLAW_CHATDATA_DIR: /app/chatdata
  OPENAI_MODEL: gpt-4.1-mini
  OPENAI_API_MODE: responses

ingress:
  enabled: false

persistence:
  enabled: true
  size: 5Gi
```

OpenAI-compatible 服务示例：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-api
  tag: 0.1.0
  pullPolicy: IfNotPresent

imagePullSecrets:
  - name: aliyun-acr-pull-secret

secret:
  create: false
  existingSecret: pyclaw-provider-secret

env:
  OPENCLAW_CHATDATA_DIR: /app/chatdata
  OPENAI_MODEL: 你的模型名
  OPENAI_API_MODE: chat_completions

ingress:
  enabled: false

persistence:
  enabled: true
  size: 5Gi
```

这里的 `env` 会被 Helm 渲染为 ConfigMap，适合放 `OPENAI_MODEL`、`OPENAI_API_MODE` 等非敏感配置；`secret.existingSecret` 指向 Kubernetes Secret，适合放 `OPENAI_API_KEY`、`OPENAI_BASE_URL` 等敏感或连接配置。

建议把 `pyclaw-values-k3s.yaml` 加入 `.gitignore`，避免误提交真实环境配置。

## 12. 渲染 Helm Chart

部署前先只渲染，不实际创建资源：

```bash
cd /opt/pyclaw
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm template pyclaw ./helm/pyclaw -f pyclaw-values-k3s.yaml
```

这一步用于检查 Helm 模板语法。

如果输出一大段 Kubernetes YAML，说明 Helm 模板基本可用。

如果报错，需要根据报错定位到具体模板文件。

## 13. 使用 Helm 部署 pyclaw

执行：

```bash
cd /opt/pyclaw
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-values-k3s.yaml
```

命令含义：

- `helm upgrade --install`：如果 release 不存在就安装，存在就升级。
- `pyclaw`：release 名称。
- `./helm/pyclaw`：Chart 路径。
- `-n pyclaw`：部署到 `pyclaw` namespace。
- `--create-namespace`：namespace 不存在时自动创建。
- `-f pyclaw-values-k3s.yaml`：使用当前环境的配置覆盖默认 values。

查看 release：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm list -n pyclaw
```

## 14. 查看 Kubernetes 资源

查看 Pod：

```bash
sudo k3s kubectl -n pyclaw get pods
```

查看 Deployment：

```bash
sudo k3s kubectl -n pyclaw get deploy
```

查看 Service：

```bash
sudo k3s kubectl -n pyclaw get svc
```

查看 PVC：

```bash
sudo k3s kubectl -n pyclaw get pvc
```

查看全部资源：

```bash
sudo k3s kubectl -n pyclaw get all
```

## 15. 验证服务

### 15.1 查看 Pod 是否 Running

```bash
sudo k3s kubectl -n pyclaw get pods
```

预期：

```text
NAME                          READY   STATUS    RESTARTS   AGE
pyclaw-xxxx                   1/1     Running   0          ...
```

### 15.2 查看日志

```bash
sudo k3s kubectl -n pyclaw logs deploy/pyclaw
```

预期看到 Uvicorn 启动日志：

```text
Uvicorn running on http://0.0.0.0:8000
```

### 15.3 端口转发

为了先验证服务，不急着配置 Ingress。

执行：

```bash
sudo k3s kubectl -n pyclaw port-forward svc/pyclaw 8000:8000
```

保持该命令运行。

另开一个 SSH 终端，执行：

```bash
curl http://localhost:8000/healthz
```

预期：

```json
{"status":"ok","service":"pyclaw-api"}
```

### 15.4 测试真实模型调用

```bash
curl -X POST http://localhost:8000/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"你好，请用一句话介绍你自己。","provider":"openai","session_id":"k3s-real-demo","tool_profile":"minimal"}'
```

预期返回 JSON，其中 `text` 字段包含模型回复。

## 16. 验证 transcript 持久化

查看 PVC：

```bash
sudo k3s kubectl -n pyclaw get pvc
```

进入 Pod 查看 `/app/chatdata`：

```bash
POD=$(sudo k3s kubectl -n pyclaw get pods -l app.kubernetes.io/name=pyclaw -o jsonpath='{.items[0].metadata.name}')
sudo k3s kubectl -n pyclaw exec -it "$POD" -- ls -la /app/chatdata
```

查看 transcript：

```bash
sudo k3s kubectl -n pyclaw exec -it "$POD" -- cat /app/chatdata/k3s-real-demo.jsonl
```

如果能看到 JSONL 内容，说明 session transcript 已经写入 PVC。

## 17. 常见问题排查

### 17.1 Pod ImagePullBackOff

查看 Pod：

```bash
sudo k3s kubectl -n pyclaw get pods
```

如果看到：

```text
ImagePullBackOff
ErrImagePull
```

通常是 K3s containerd 没有 `pyclaw-api:dev` 镜像。

如果使用 ACR，优先检查：

```bash
sudo k3s kubectl -n pyclaw describe pod <pod-name>
sudo k3s kubectl -n pyclaw get secret aliyun-acr-pull-secret
```

常见原因：

1. `pyclaw-values-k3s.yaml` 中的 ACR 地址、命名空间、仓库名或 tag 写错。
2. ACR 仓库是私有仓库，但没有创建 `aliyun-acr-pull-secret`。
3. `imagePullSecrets` 没有写入 values。
4. ACR 固定密码已变更，但 Kubernetes Secret 还是旧密码。

如果使用本地镜像导入方案，再执行：

```bash
cd /opt/pyclaw
sudo docker save pyclaw-api:dev -o pyclaw-api-dev.tar
sudo k3s ctr images import pyclaw-api-dev.tar
sudo k3s ctr images list | grep pyclaw-api
```

然后重启 Deployment：

```bash
sudo k3s kubectl -n pyclaw rollout restart deploy/pyclaw
```

### 17.2 Pod CrashLoopBackOff

查看日志：

```bash
sudo k3s kubectl -n pyclaw logs deploy/pyclaw
```

查看详细事件：

```bash
sudo k3s kubectl -n pyclaw describe pod <pod-name>
```

常见原因：

- 环境变量错误。
- Secret 不存在。
- PVC 挂载失败。
- 应用启动异常。

### 17.3 API 返回 500

查看日志：

```bash
sudo k3s kubectl -n pyclaw logs deploy/pyclaw --tail=200
```

可能原因：

- `OPENAI_API_KEY` 未注入。
- `OPENAI_MODEL` 不正确。
- `OPENAI_API_MODE` 与服务不匹配。
- OpenAI-compatible 服务需要 `OPENAI_BASE_URL`。
- transcript 目录权限异常。

### 17.4 模型请求超时

如果 `/healthz` 正常，但 `/v1/agent/run` 返回：

```text
APITimeoutError('Request timed out.')
```

说明 `pyclaw-api` 已收到请求，但调用 LLM 服务超时。优先检查 Pod 内实际环境变量：

```bash
sudo k3s kubectl -n pyclaw exec deploy/pyclaw -- env | grep OPENAI
```

常见原因：

1. `OPENAI_BASE_URL` 没有注入 Pod。
2. `OPENAI_MODEL` 与 `OPENAI_BASE_URL` 对应服务不匹配。
3. `OPENAI_API_MODE` 错误，例如 compatible 服务应使用 `chat_completions`。
4. Pod 内 DNS 或出站网络异常。

Pod 内可用 Python 简单测试 DNS：

```bash
sudo k3s kubectl -n pyclaw exec deploy/pyclaw -- sh -c 'python - <<EOF
import socket
print(socket.gethostbyname("api.openai.com"))
EOF'
```

如果使用的是自定义 `OPENAI_BASE_URL`，把域名替换为对应服务域名。

### 17.5 Chat Completions 返回 invalid assistant message

如果接口返回：

```text
Invalid assistant message: content or tool_calls must be set
```

并且当前使用的是 `OPENAI_API_MODE=chat_completions`，常见原因是历史 transcript 中存在错误 assistant message，例如此前请求超时写入了：

```json
{"role":"assistant","content":[],"stopReason":"error","errorMessage":"Request timed out."}
```

下一轮使用同一个 `session_id` 时，pyclaw 会把历史上下文带给模型。OpenAI-compatible Chat Completions 服务可能拒绝 `content=[]` 的 assistant 消息。

最快验证方式：换一个新的 `session_id`：

```bash
curl -X POST http://localhost:8000/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"你好，请用一句话介绍你自己。","provider":"openai","session_id":"k3s-demo-2","tool_profile":"minimal"}'
```

如果新 session 成功，说明旧 transcript 污染了上下文。

查看 transcript：

```bash
sudo k3s kubectl -n pyclaw exec deploy/pyclaw -- ls -la /app/chatdata
sudo k3s kubectl -n pyclaw exec deploy/pyclaw -- tail -n 20 /app/chatdata/k3s-demo.jsonl
```

删除坏的 session transcript：

```bash
sudo k3s kubectl -n pyclaw exec deploy/pyclaw -- rm -f /app/chatdata/k3s-demo.jsonl
```

长期应在代码中处理：不要把 `stopReason=error` 且 `content=[]` 的 assistant message 原样带入下一轮 Chat Completions 上下文。

### 17.6 Secret 未生效

检查 Secret：

```bash
sudo k3s kubectl -n pyclaw get secret pyclaw-provider-secret
```

检查 Deployment 是否引用了正确 Secret：

```bash
sudo k3s kubectl -n pyclaw describe deploy pyclaw
```

如果修改了 Secret，建议重启 Deployment：

```bash
sudo k3s kubectl -n pyclaw rollout restart deploy/pyclaw
```

### 17.7 PVC Pending

查看 PVC：

```bash
sudo k3s kubectl -n pyclaw get pvc
```

如果 PVC 一直是 `Pending`，查看 StorageClass：

```bash
sudo k3s kubectl get storageclass
```

K3s 默认通常有：

```text
local-path
```

如果没有，需要检查 `local-path-provisioner` 是否正常：

```bash
sudo k3s kubectl -n kube-system get pods | grep local-path
```

如果 `local-path-provisioner` 正常，但 PVC 仍然 Pending，查看 PVC 事件：

```bash
sudo k3s kubectl -n pyclaw describe pvc pyclaw-chatdata
```

如果看到 helper pod 或 busybox 拉取失败，参考 `4.6 国内环境下修复系统镜像拉取失败` 中的 `rancher/mirrored-library-busybox:1.37.0` 处理。

## 18. 升级 pyclaw

如果你修改了 pyclaw 源码，需要重新构建镜像：

```bash
cd /opt/pyclaw
sudo docker build -t pyclaw-api:dev .
```

推荐推送新版本到 ACR：

```bash
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
ACR_NAMESPACE=pyclaw
IMAGE_TAG=0.1.1

sudo docker tag pyclaw-api:dev ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
sudo docker push ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
```

然后把 `pyclaw-values-k3s.yaml` 中的 `image.tag` 改成新版本，并执行：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  -f pyclaw-values-k3s.yaml
```

重启 Deployment：

```bash
sudo k3s kubectl -n pyclaw rollout restart deploy/pyclaw
```

查看 rollout 状态：

```bash
sudo k3s kubectl -n pyclaw rollout status deploy/pyclaw
```

## 19. 卸载 pyclaw

卸载 Helm release：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm uninstall pyclaw -n pyclaw
```

注意：默认情况下，PVC 可能不会随 Helm release 自动删除，避免误删数据。

查看 PVC：

```bash
sudo k3s kubectl -n pyclaw get pvc
```

如果确认不再需要数据，可以手动删除 PVC：

```bash
sudo k3s kubectl -n pyclaw delete pvc <pvc-name>
```

## 20. 卸载 K3s

如果只是卸载 pyclaw，不需要卸载 K3s。

如果确认要删除整个 K3s：

```bash
sudo /usr/local/bin/k3s-uninstall.sh
```

注意：这会删除 K3s 集群和相关数据。执行前确认不再需要其中的资源。

## 21. 与 Docker 单容器运行的区别

Docker 单容器模式：

```text
你手动 docker run
容器退出后需要自己处理
目录挂载由 -v 指定
环境变量由 --env-file 指定
端口由 -p 指定
```

K3s + Helm 模式：

```text
Deployment 负责维持 Pod 运行
Service 提供稳定访问入口
Secret 注入敏感配置
ConfigMap 注入普通配置
PVC 提供持久化存储
Helm 管理整套资源版本
```

这就是从“单容器运行”进入“云原生部署”的关键变化。

## 22. 后续演进建议

当前阶段建议先完成：

1. K3s 节点 Ready。
2. Helm Chart 能正常渲染。
3. pyclaw Pod Running。
4. `/healthz` 正常。
5. 真实模型调用正常。
6. transcript 能写入 PVC。

完成后再考虑：

1. 配置 Ingress，通过域名访问 pyclaw。
2. 安装 Rancher，用 UI 管理 K3s。
3. 使用阿里云 ACR 的版本 tag 和镜像清理规则管理镜像。
4. 为 API 增加鉴权。
5. 引入异步任务队列。
6. 将 transcript / session 存储迁移到数据库或对象存储。

### 22.1 Rancher 部署暂停点记录

截至 2026-07-05，Rancher 部署已推进到“Helm release 已安装，但 Rancher Pod 未 Ready”的阶段。由于 Rancher 运行时 CPU 占用较高，当前建议先暂停 Rancher Deployment，保留 Helm release、namespace、Secret、cert-manager 等资源，后续再继续排查。

已完成：

```text
1. K3s 已重新安装并启动，节点 Ready。
2. kube-system 基础组件已恢复，包括 CoreDNS、local-path-provisioner、metrics-server、Traefik。
3. 已创建 cattle-system namespace。
4. 已安装 cert-manager，并启用 CRD。
5. 已安装 Rancher Helm release。
6. Rancher hostname 使用 rancher.anxin-hitsz.com。
7. Rancher replicas 配置为 1。
8. Rancher 主镜像 rancher/rancher:v2.14.3 已成功拉取。
9. Rancher Deployment / Pod 已创建。
```

关键安装命令记录：

```bash
sudo /usr/local/bin/k3s kubectl create namespace cattle-system

sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --set crds.enabled=true

sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm install rancher rancher-latest/rancher \
  --namespace cattle-system \
  --set hostname=rancher.anxin-hitsz.com \
  --set replicas=1 \
  --set bootstrapPassword='你的 Rancher 初始密码'
```

曾遇到的问题：

```text
1. 使用 K3s v1.36.2+k3s1 时，Rancher chart 报 kubeVersion 不兼容：
   chart requires kubeVersion: < 1.36.0-0

2. 未安装 cert-manager 时，Rancher chart 报缺少 Issuer CRD：
   no matches for kind "Issuer" in version "cert-manager.io/v1"

3. Rancher 主镜像缺失时，cattle-system 中 Rancher Pod 出现 ErrImagePull。

4. 镜像拉取成功后，Rancher Pod 进入 Running，但 Ready=False。
   describe pod 中 startup probe 访问 /healthz 失败：
   Startup probe failed: Get "http://<pod-ip>:80/healthz": connect: connection refused

5. Pod 多次重启，状态曾出现 CrashLoopBackOff。
```

当前暂停前的状态可记录为：

```text
Rancher Helm Chart 已安装。
Rancher 镜像已拉取成功。
Rancher Deployment / Pod 已创建。
Rancher Pod 未 Ready，启动探针失败，尚未进入 Web UI 可访问阶段。
```

为了降低 CPU 占用，可以暂停 Rancher：

```bash
sudo /usr/local/bin/k3s kubectl -n cattle-system scale deployment rancher --replicas=0
sudo /usr/local/bin/k3s kubectl -n cattle-system get pods
```

后续恢复 Rancher：

```bash
sudo /usr/local/bin/k3s kubectl -n cattle-system scale deployment rancher --replicas=1
sudo /usr/local/bin/k3s kubectl -n cattle-system get pods -w
```

恢复后优先排查：

```bash
sudo /usr/local/bin/k3s kubectl -n cattle-system get pods
sudo /usr/local/bin/k3s kubectl -n cattle-system describe pod <rancher-pod>
sudo /usr/local/bin/k3s kubectl -n cattle-system logs <rancher-pod> --tail=200
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm status rancher -n cattle-system
```

尚未完成：

```text
1. Rancher Pod Ready。
2. rancher.anxin-hitsz.com DNS / Ingress / HTTPS 访问验证。
3. Rancher bootstrap 登录验证。
4. Rancher 证书策略确认。
5. Rancher 资源占用优化。
6. Rancher 系统镜像同步到 ACR 的长期方案验证。
```

## 23. 最小执行清单

如果只看最短路径，可以按下面命令执行：

```bash
# 1. 安装 K3s
curl -sfL https://get.k3s.io | sh -

# 2. 检查节点
sudo k3s kubectl get nodes

# 3. 安装 Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version

# 4. 推送 pyclaw 镜像到阿里云 ACR
cd /opt/pyclaw
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
ACR_NAMESPACE=pyclaw
IMAGE_TAG=0.1.0
sudo docker login ${ACR_REGISTRY}
sudo docker build -t pyclaw-api:dev .
sudo docker tag pyclaw-api:dev ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}
sudo docker push ${ACR_REGISTRY}/${ACR_NAMESPACE}/pyclaw-api:${IMAGE_TAG}

# 5. 创建 namespace 和 secret
sudo k3s kubectl create namespace pyclaw
sudo k3s kubectl -n pyclaw create secret docker-registry aliyun-acr-pull-secret \
  --docker-server=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com \
  --docker-username='你的 ACR 登录用户名' \
  --docker-password='你的 ACR 固定密码' \
  --docker-email='unused@example.com'
sudo k3s kubectl -n pyclaw create secret generic pyclaw-provider-secret \
  --from-literal=OPENAI_API_KEY='你的真实_api_key'

# 如果使用 OpenAI-compatible 服务，创建 provider secret 时还需要加：
#   --from-literal=OPENAI_BASE_URL='https://你的_base_url'

# 6. 创建 pyclaw-values-k3s.yaml
nano pyclaw-values-k3s.yaml

# 7. 渲染检查
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm template pyclaw ./helm/pyclaw -f pyclaw-values-k3s.yaml

# 8. 部署
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-values-k3s.yaml

# 9. 查看 Pod
sudo k3s kubectl -n pyclaw get pods

# 10. 端口转发验证
sudo k3s kubectl -n pyclaw port-forward svc/pyclaw 8000:8000
```

另开终端测试：

```bash
curl http://localhost:8000/healthz

curl -X POST http://localhost:8000/v1/agent/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"你好，请用一句话介绍你自己。","provider":"openai","session_id":"k3s-real-demo","tool_profile":"minimal"}'
```

