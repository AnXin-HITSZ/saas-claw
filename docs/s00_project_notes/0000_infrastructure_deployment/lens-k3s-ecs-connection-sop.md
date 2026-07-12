# Lens 连接 ECS 上 K3s 集群 SOP

本文档记录如何在 Windows 本机使用 Lens 连接部署在阿里云 ECS 上的 K3s 集群。

适用场景：

- ECS 上已经安装 K3s。
- ECS 上可以通过 `k3s kubectl` 管理集群。
- Windows 本机需要使用 Lens 图形化查看和管理 K3s。
- 不希望或暂时不能把 ECS 的 Kubernetes API Server `6443` 端口直接暴露到公网。

本文示例使用：

```text
ECS 公网 IP: 8.135.60.136
ECS 登录用户: root
Windows 用户目录: C:\Users\anxin
本机 kubeconfig 文件: C:\Users\anxin\.kube\ecs-k3s.yaml
Lens 中显示的集群名: default
```

如果 IP、用户名或文件路径不同，请替换为自己的实际值。

## 1. 背景说明

K3s 默认会生成 kubeconfig 文件：

```bash
/etc/rancher/k3s/k3s.yaml
```

这个文件中包含连接 Kubernetes API Server 所需的：

- cluster 地址
- CA 证书
- client 证书
- client key
- context
- user

Lens 本质上也是读取 kubeconfig，然后连接 kubeconfig 中配置的 API Server。

因此，连接流程可以概括为：

```text
1. 在 ECS 上确认 K3s 正常。
2. 把 /etc/rancher/k3s/k3s.yaml 复制到 Windows 本机。
3. 修改 kubeconfig 中的 server 地址。
4. 在 Lens 中导入 kubeconfig。
5. 根据连接方式选择：
   - SSH 隧道连接，推荐。
   - 公网 6443 端口直连，不推荐对全网开放。
```

## 2. 在 ECS 上确认 K3s 正常

SSH 登录 ECS：

```powershell
ssh root@8.135.60.136
```

在 ECS 上执行：

```bash
k3s kubectl get nodes
```

如果输出类似下面内容，说明 K3s 集群正常：

```text
NAME              STATUS   ROLES                  AGE   VERSION
your-hostname     Ready    control-plane,master   1d    v1.xx.x+k3s1
```

注意：K3s 自带的是 `k3s kubectl`。如果直接执行：

```bash
kubectl get nodes
```

可能会出现：

```text
Command 'kubectl' not found
```

这是正常的，不代表 K3s 没安装。

如果希望在 ECS 上直接使用 `kubectl`，可以配置 alias：

```bash
alias kubectl='k3s kubectl'
```

永久生效：

```bash
echo "alias kubectl='k3s kubectl'" >> ~/.bashrc
source ~/.bashrc
```

## 3. 复制 K3s kubeconfig 到 Windows 本机

这一步在 Windows 本机 PowerShell 执行，不是在 ECS 服务器里执行。

先确认本机 `.kube` 目录存在：

```powershell
mkdir C:\Users\anxin\.kube
```

如果目录已存在，PowerShell 可能提示已存在，可以忽略。

然后复制 ECS 上的 K3s kubeconfig：

```powershell
scp root@8.135.60.136:/etc/rancher/k3s/k3s.yaml C:\Users\anxin\.kube\ecs-k3s.yaml
```

如果使用 SSH 私钥登录 ECS，可以加 `-i`：

```powershell
scp -i C:\Users\anxin\.ssh\your-private-key root@8.135.60.136:/etc/rancher/k3s/k3s.yaml C:\Users\anxin\.kube\ecs-k3s.yaml
```

常见错误：

```text
open local "C:/Users/anxin/.kube/ecs-k3s.yaml": No such file or directory
```

原因是本机目标目录不存在。先执行：

```powershell
mkdir C:\Users\anxin\.kube
```

再重新执行 `scp`。

## 4. 修改 kubeconfig 中的 server 地址

打开本机 kubeconfig：

```powershell
notepad C:\Users\anxin\.kube\ecs-k3s.yaml
```

找到 `clusters.cluster.server` 对应的地址，通常类似：

```yaml
server: https://127.0.0.1:6443
```

或已经被改成：

```yaml
server: https://8.135.60.136:6443
```

后续根据连接方式修改。

## 5. 推荐方式：SSH 隧道连接

推荐使用 SSH 隧道连接 K3s API Server。

优点：

1. 不需要开放 ECS 的 `6443` 端口到公网。
2. 避免 K3s 证书不包含公网 IP 导致的 `x509` 错误。
3. 只依赖 SSH 登录能力，安全边界更清晰。

### 5.1 启动 SSH 隧道

在 Windows 本机新开一个 PowerShell 窗口，执行：

```powershell
ssh -L 16443:127.0.0.1:6443 root@8.135.60.136
```

含义：

```text
本机 127.0.0.1:16443
  -> 通过 SSH 隧道
  -> ECS 上的 127.0.0.1:6443
  -> K3s API Server
```

输入密码登录成功后，这个 PowerShell 窗口保持不关。

只要这个窗口关闭，隧道就会断开，Lens 也会连接失败。

### 5.2 修改 kubeconfig

打开：

```powershell
notepad C:\Users\anxin\.kube\ecs-k3s.yaml
```

把 `server` 改成：

```yaml
server: https://127.0.0.1:16443
```

保存文件。

### 5.3 在 Lens 中连接

回到 Lens，重新连接导入的集群。

如果 Lens 中已经导入过该 kubeconfig，左侧可能显示为：

```text
KUBERNETES CLUSTERS
  Local Kubeconfigs
    default
```

点击 `default` 即可连接。

如果之前连接失败，可以点击刷新或重新选择该集群。

## 6. Lens 中导入 kubeconfig

Lens 中导入 kubeconfig 的入口可能因版本不同略有差异。

常见方式：

```text
File -> Add Cluster
```

或：

```text
File -> Add Cluster from Kubeconfig
```

选择文件：

```text
C:\Users\anxin\.kube\ecs-k3s.yaml
```

也可以在左侧：

```text
KUBERNETES CLUSTERS -> Local Kubeconfigs
```

附近查找 `+`、`...` 或右键菜单，选择添加 kubeconfig。

导入成功后，Lens 左侧会出现一个新的集群条目。K3s 默认 kubeconfig 中的 context 名称可能是 `default`，所以 Lens 中也可能显示为：

```text
default
```

这是正常现象。后续可以在 Lens 中改成更容易识别的名称，例如：

```text
ecs-k3s
```

## 7. 公网 6443 端口直连方式

如果不使用 SSH 隧道，也可以让 Lens 直接访问：

```text
https://8.135.60.136:6443
```

此时 kubeconfig 中需要配置：

```yaml
server: https://8.135.60.136:6443
```

但是这种方式需要额外开放 ECS 的 `6443` 端口，并且可能遇到证书问题。

### 7.1 开放阿里云 ECS 安全组

进入阿里云控制台：

```text
ECS 控制台
  -> 实例
  -> 找到目标 ECS
  -> 安全组
  -> 配置规则
  -> 入方向
  -> 手动添加
```

添加规则：

```text
协议类型: 自定义 TCP
端口范围: 6443/6443
授权对象: 你的本机公网 IP/32
```

示例：

```text
授权对象: 11.22.33.44/32
```

不建议使用：

```text
0.0.0.0/0
```

因为 Kubernetes API Server 是集群控制面入口，对全网开放风险很高。

### 7.2 开放 ECS 系统防火墙

如果 ECS 使用 Ubuntu + ufw：

```bash
sudo ufw allow 6443/tcp
sudo ufw status
```

如果 ECS 使用 CentOS / Alibaba Cloud Linux + firewalld：

```bash
sudo firewall-cmd --permanent --add-port=6443/tcp
sudo firewall-cmd --reload
sudo firewall-cmd --list-ports
```

如果系统防火墙没有启用，这一步可能不需要。

### 7.3 确认 K3s 监听 6443

在 ECS 上执行：

```bash
sudo ss -lntp | grep 6443
```

如果看到类似：

```text
0.0.0.0:6443
```

或：

```text
:::6443
```

说明 K3s API Server 对外监听。

如果只看到：

```text
127.0.0.1:6443
```

则公网无法直连，即使安全组开放了也不行。

### 7.4 在 Windows 本机测试端口

PowerShell 执行：

```powershell
Test-NetConnection 8.135.60.136 -Port 6443
```

如果输出：

```text
TcpTestSucceeded : True
```

说明网络层已经连通。

如果是：

```text
TcpTestSucceeded : False
```

说明仍然存在安全组、防火墙、监听地址或网络访问问题。

### 7.5 证书问题

公网端口通了以后，Lens 仍可能报类似错误：

```text
x509: certificate is valid for 127.0.0.1, not 8.135.60.136
```

原因是 K3s API Server 证书没有包含 ECS 公网 IP。

解决方式：

1. 推荐：改回 SSH 隧道方式。
2. 进阶：重新配置 K3s 的 TLS SAN，让证书包含公网 IP。

对于个人开发和调试，SSH 隧道方式通常更省事。

## 8. 常见 Lens 错误与处理

### 8.1 连接 8.135.60.136:6443 超时

Lens 中可能出现：

```text
Error while proxying request: dial tcp 8.135.60.136:6443: connectex:
A connection attempt failed because the connected party did not properly respond
```

原因：

```text
Lens 正在直连 ECS 公网 IP 的 6443 端口，但该端口不可达。
```

处理：

1. 如果使用 SSH 隧道，把 kubeconfig 改为：

   ```yaml
   server: https://127.0.0.1:16443
   ```

   并启动隧道：

   ```powershell
   ssh -L 16443:127.0.0.1:6443 root@8.135.60.136
   ```

2. 如果坚持公网直连，检查安全组、防火墙和 K3s 监听地址。

### 8.2 Lens 显示集群名为 default

这是 K3s 默认 kubeconfig 的 context 名称导致的，不影响使用。

可以在 Lens 中修改显示名称，或手动修改 kubeconfig 中的 context 名称。

### 8.3 `kubectl` 命令不存在

在 ECS 上直接执行 `kubectl get nodes` 可能失败：

```text
Command 'kubectl' not found
```

K3s 自带命令是：

```bash
k3s kubectl get nodes
```

### 8.4 scp 提示本机文件路径不存在

错误：

```text
open local "C:/Users/anxin/.kube/ecs-k3s.yaml": No such file or directory
```

处理：

```powershell
mkdir C:\Users\anxin\.kube
```

然后重新执行：

```powershell
scp root@8.135.60.136:/etc/rancher/k3s/k3s.yaml C:\Users\anxin\.kube\ecs-k3s.yaml
```

## 9. 推荐日常使用流程

每次需要用 Lens 管理 ECS 上的 K3s 时：

1. 打开 Windows PowerShell。
2. 启动 SSH 隧道：

   ```powershell
   ssh -L 16443:127.0.0.1:6443 root@8.135.60.136
   ```

3. 保持该窗口不关闭。
4. 确认 `C:\Users\anxin\.kube\ecs-k3s.yaml` 中配置为：

   ```yaml
   server: https://127.0.0.1:16443
   ```

5. 打开 Lens。
6. 点击左侧 `Local Kubeconfigs` 下的 `default` 集群。

## 10. 最终推荐配置

推荐 kubeconfig 中使用：

```yaml
server: https://127.0.0.1:16443
```

推荐连接命令：

```powershell
ssh -L 16443:127.0.0.1:6443 root@8.135.60.136
```

推荐安全策略：

```text
不开放 ECS 6443 到公网。
仅通过 SSH 隧道访问 K3s API Server。
```

只有在明确需要外部系统长期直连 Kubernetes API Server 时，才考虑开放 `6443`，并且应限制来源 IP，不应对 `0.0.0.0/0` 开放。
