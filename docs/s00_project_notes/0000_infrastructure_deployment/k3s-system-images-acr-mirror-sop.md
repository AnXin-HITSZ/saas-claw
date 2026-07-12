# K3s/Rancher 系统镜像同步到阿里云 ACR SOP

本文档记录如何把 K3s/Rancher 需要的系统镜像同步到阿里云 ACR 个人版，并配置 K3s/containerd 优先从 ACR 拉取镜像。

适用场景：

- ECS 位于国内网络环境，访问 Docker Hub 经常超时。
- K3s 系统 Pod 出现 `ImagePullBackOff` / `ErrImagePull`。
- 希望避免每次手动 `ctr images pull` + `ctr images tag`。
- 希望用自己的 ACR 做长期稳定的系统镜像中转仓库。

## 1. 核心思路

K3s 默认会拉取类似下面的镜像：

```text
docker.io/rancher/mirrored-coredns-coredns:1.14.4
docker.io/rancher/local-path-provisioner:v0.0.36
docker.io/rancher/klipper-lb:v0.4.17
```

国内 ECS 访问 `docker.io` / `registry-1.docker.io` 可能超时。解决思路是：

```text
1. 先把系统镜像从国内源拉到 ECS。
2. 再推送到自己的 ACR。
3. 在 K3s 的 /etc/rancher/k3s/registries.yaml 中配置 docker.io mirror。
4. 让 K3s 后续访问 docker.io 时实际走自己的 ACR。
```

为了让 mirror 规则生效，ACR 中的路径应尽量保持为：

```text
<ACR_REGISTRY>/rancher/<image-name>:<tag>
```

例如：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/rancher/mirrored-coredns-coredns:1.14.4
```

这样 K3s 请求：

```text
docker.io/rancher/mirrored-coredns-coredns:1.14.4
```

containerd 才能通过 mirror 映射到 ACR 上相同路径的镜像。

## 2. ACR 准备

本文使用的 ACR 地址：

```text
crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com
```

在 ACR 控制台创建命名空间：

```text
rancher
```

如果 ACR 个人版不允许推送时自动创建仓库，需要在 `rancher` 命名空间下手动创建以下镜像仓库：

```text
mirrored-pause
mirrored-coredns-coredns
mirrored-metrics-server
local-path-provisioner
klipper-helm
mirrored-library-busybox
klipper-lb
mirrored-library-traefik
rancher
```

仓库类型可以选择私有。私有仓库更安全，但 K3s 的 `registries.yaml` 中需要配置 ACR 认证信息。

## 3. ECS 登录 ACR

在 ECS 上执行：

```bash
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com

sudo docker login --username=AnXin_HITSZ ${ACR_REGISTRY}
```

密码填写 ACR 控制台“访问凭证”中的固定密码。

不要使用 ECS 的 `root` 密码，也不要把 ACR 固定密码写入 Git。

## 4. 同步系统镜像到 ACR

在 ECS 上创建脚本：

```bash
nano /opt/sync-k3s-images-to-acr.sh
```

写入：

```bash
#!/usr/bin/env bash
set -euo pipefail

ACR_REGISTRY="crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com"

sync_image() {
  local src="$1"
  local dst="$2"

  echo
  echo "==> Pull ${src}"
  sudo docker pull "${src}"

  echo "==> Tag ${src} -> ${ACR_REGISTRY}/${dst}"
  sudo docker tag "${src}" "${ACR_REGISTRY}/${dst}"

  echo "==> Push ${ACR_REGISTRY}/${dst}"
  sudo docker push "${ACR_REGISTRY}/${dst}"
}

sync_image "registry.cn-hangzhou.aliyuncs.com/google_containers/pause:3.6" \
  "rancher/mirrored-pause:3.6"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-coredns-coredns:1.14.4" \
  "rancher/mirrored-coredns-coredns:1.14.4"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-metrics-server:v0.8.1" \
  "rancher/mirrored-metrics-server:v0.8.1"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/local-path-provisioner:v0.0.36" \
  "rancher/local-path-provisioner:v0.0.36"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/klipper-helm:v0.11.1-build20260615" \
  "rancher/klipper-helm:v0.11.1-build20260615"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-library-busybox:1.37.0" \
  "rancher/mirrored-library-busybox:1.37.0"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/klipper-lb:v0.4.17" \
  "rancher/klipper-lb:v0.4.17"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/mirrored-library-traefik:3.7.4" \
  "rancher/mirrored-library-traefik:3.7.4"

sync_image "registry.cn-hangzhou.aliyuncs.com/rancher/rancher:v2.14.3" \
  "rancher/rancher:v2.14.3"

echo
echo "==> Done"
```

执行：

```bash
chmod +x /opt/sync-k3s-images-to-acr.sh
/opt/sync-k3s-images-to-acr.sh
```

如果推送时报仓库不存在，到 ACR 控制台在 `rancher` 命名空间下创建对应仓库后重试。

## 5. 配置 K3s 使用 ACR 作为 docker.io mirror

创建或编辑：

```bash
sudo nano /etc/rancher/k3s/registries.yaml
```

写入：

```yaml
mirrors:
  docker.io:
    endpoint:
      - "https://crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com"

configs:
  "crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com":
    auth:
      username: "AnXin_HITSZ"
      password: "你的 ACR 固定密码"
```

注意：

1. `registries.yaml` 位于 ECS，不要提交到 Git。
2. 如果使用 ACR 专有网络地址，可以把 endpoint 改为 VPC 地址，但要确认 ECS 与 ACR 在同地域、同 VPC 且网络连通。
3. 如果 ACR 仓库是公开仓库，可以不写 `configs.auth`，但不推荐公开系统镜像仓库。

重启 K3s：

```bash
sudo systemctl restart k3s
```

## 6. 验证 mirror 是否生效

用 Docker Hub 原始镜像名测试：

```bash
sudo /usr/local/bin/k3s crictl pull docker.io/rancher/mirrored-coredns-coredns:1.14.4
```

如果成功，说明 containerd 已经能通过 ACR 拉到镜像。

也可以查看 K3s containerd 镜像列表：

```bash
sudo /usr/local/bin/k3s ctr images list | grep mirrored-coredns
```

## 7. 重建失败的系统 Pod

如果此前系统 Pod 已经卡在 `ImagePullBackOff`，删除它们，让 K3s 重新创建：

```bash
sudo /usr/local/bin/k3s kubectl -n kube-system delete pod \
  $(sudo /usr/local/bin/k3s kubectl -n kube-system get pods --no-headers \
    | awk '/coredns|metrics-server|local-path-provisioner|traefik|svclb-traefik/ {print $1}')
```

观察：

```bash
sudo /usr/local/bin/k3s kubectl -n kube-system get pods -w
```

目标状态：

```text
coredns                         1/1 Running
local-path-provisioner          1/1 Running
metrics-server                  1/1 Running
traefik                         1/1 Running
svclb-traefik                   2/2 Running
helm-install-traefik-crd        Completed
helm-install-traefik            Completed
```

## 8. 后续新增镜像的维护流程

当 Rancher 或 K3s 新组件继续出现 `ImagePullBackOff` 时，先列出实际需要的镜像：

```bash
sudo /usr/local/bin/k3s kubectl -n kube-system get pods -o jsonpath='{range .items[*]}{.metadata.name}{" => "}{range .spec.containers[*]}{.image}{" "}{end}{"\n"}{end}'
```

或者查看某个 Pod 的事件：

```bash
sudo /usr/local/bin/k3s kubectl -n kube-system describe pod <pod-name>
```

拿到镜像名后，按下面模式同步：

```bash
sudo docker pull <国内源>/<namespace>/<image>:<tag>
sudo docker tag <国内源>/<namespace>/<image>:<tag> \
  crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/rancher/<image>:<tag>
sudo docker push crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/rancher/<image>:<tag>
```

然后删除失败 Pod 让它重建。

### 8.1 Rancher 主镜像示例

安装 Rancher 后，如果 `cattle-system` 中的 Rancher Pod 出现：

```text
rancher-... => rancher/rancher:v2.14.3
```

说明缺少 Rancher 主镜像。长期做法是同步到 ACR：

```bash
ACR_REGISTRY=crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com

sudo docker pull registry.cn-hangzhou.aliyuncs.com/rancher/rancher:v2.14.3
sudo docker tag registry.cn-hangzhou.aliyuncs.com/rancher/rancher:v2.14.3 \
  ${ACR_REGISTRY}/rancher/rancher:v2.14.3
sudo docker push ${ACR_REGISTRY}/rancher/rancher:v2.14.3
```

如果 push 提示仓库不存在，需要先在 ACR 控制台的 `rancher` 命名空间下创建仓库：

```text
rancher
```

同步完成后，可以验证 K3s 是否能通过 mirror 拉取 Docker Hub 原始镜像名：

```bash
sudo /usr/local/bin/k3s crictl pull docker.io/rancher/rancher:v2.14.3
```

## 9. 与手动 ctr pull + tag 的区别

临时修复方式：

```text
从国内源拉到 K3s containerd
打 tag 成 docker.io/rancher/...
只对当前 ECS 当前 containerd 有效
重装 K3s 或换机器后需要重来
```

ACR 中转仓库方式：

```text
系统镜像长期存放在自己的 ACR
K3s 通过 registries.yaml 自动从 ACR 拉取
重装 K3s 后只要恢复 registries.yaml 即可复用
更适合后续安装 Rancher 和重复部署
```

## 10. 安全注意事项

1. ACR 固定密码应视为敏感凭证，不要写入仓库。
2. `/etc/rancher/k3s/registries.yaml` 中含有 ACR 密码，应限制文件权限。
3. 不要为了拉镜像而开放 Redis、数据库、Kubernetes API Server 等敏感端口到 `0.0.0.0/0`。
4. Rancher 管理后台建议使用 HTTPS，并限制来源 IP 或放到 VPN/内网后面。

可以设置文件权限：

```bash
sudo chmod 600 /etc/rancher/k3s/registries.yaml
```
