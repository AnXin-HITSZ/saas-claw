# pyclaw MySQL K3s 部署 SOP

本文记录在单机 K3s 中为 `pyclaw` 部署 MySQL 的步骤。当前定位是学习环境和生产形态预演：先让 MySQL 作为 K3s 内部服务运行，再让 pyclaw-api 通过 `OPENCLAW_INGRESS_QUEUE_DSN` 使用 MySQL Durable Ingress Queue。

## 1. 当前结论

当前可以把 MySQL 部署到 K3s 中，推荐采用独立 Helm release：

```text
pyclaw-mysql      MySQL StatefulSet + Service + PVC
pyclaw            pyclaw-api Deployment
```

两者通过 K8s 内部 DNS 通信：

```text
pyclaw-api Pod
  -> pyclaw-mysql:3306
  -> database pyclaw
  -> table ingress_queue
```

本轮新增文件：

```text
helm/pyclaw-mysql/Chart.yaml
helm/pyclaw-mysql/values.yaml
helm/pyclaw-mysql/templates/_helpers.tpl
helm/pyclaw-mysql/templates/configmap.yaml
helm/pyclaw-mysql/templates/secret.yaml
helm/pyclaw-mysql/templates/service.yaml
helm/pyclaw-mysql/templates/statefulset.yaml
helm/pyclaw-mysql/templates/NOTES.txt
pyclaw-mysql-values-k3s.yaml
pyclaw-api-ingressqueue-mysql-values-k3s.yaml
```

同时更新了现有 pyclaw chart：

```text
helm/pyclaw/values.yaml
helm/pyclaw/templates/configmap.yaml
helm/pyclaw/templates/deployment.yaml
```

让 pyclaw-api 可以通过 `ingressQueue` values 块注入：

```text
OPENCLAW_INGRESS_QUEUE_BACKEND=mysql
OPENCLAW_INGRESS_QUEUE_STALE_AFTER_SECONDS=300
OPENCLAW_INGRESS_QUEUE_DSN=<from Secret>
```

## 2. 前提

ECS 上已经安装：

```text
K3s
kubectl，即 /usr/local/bin/k3s kubectl
Helm
Docker / containerd 可拉取 mysql 镜像
```

代码目录假设为：

```bash
cd /opt/pyclaw
```

命名空间继续使用：

```text
pyclaw
```

## 3. 创建 MySQL Secret

MySQL 官方镜像启动时会读取以下环境变量：

```text
MYSQL_ROOT_PASSWORD
MYSQL_DATABASE
MYSQL_USER
MYSQL_PASSWORD
```

建议学习环境先使用字母数字密码，避免 DSN 中出现特殊字符后还要 URL encode。

在 ECS 上执行：

```bash
cd /opt/pyclaw

MYSQL_ROOT_PASSWORD="替换为强 root 密码"
MYSQL_PASSWORD="替换为强 pyclaw 用户密码"

sudo /usr/local/bin/k3s kubectl -n pyclaw create secret generic pyclaw-mysql-secret \
  --from-literal=MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  --from-literal=MYSQL_DATABASE="pyclaw" \
  --from-literal=MYSQL_USER="pyclaw" \
  --from-literal=MYSQL_PASSWORD="$MYSQL_PASSWORD" \
  --dry-run=client -o yaml | sudo /usr/local/bin/k3s kubectl apply -f -
```

如果命名空间还不存在，先执行：

```bash
sudo /usr/local/bin/k3s kubectl create namespace pyclaw --dry-run=client -o yaml | sudo /usr/local/bin/k3s kubectl apply -f -
```

## 4. 安装 MySQL Helm Release

执行：

```bash
cd /opt/pyclaw

sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw-mysql ./helm/pyclaw-mysql \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-mysql-values-k3s.yaml
```

查看资源：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get statefulset,pod,svc,pvc
```

期望看到类似：

```text
statefulset.apps/pyclaw-mysql   1/1
pod/pyclaw-mysql-0              1/1 Running
service/pyclaw-mysql            ClusterIP 3306/TCP
persistentvolumeclaim/data-pyclaw-mysql-0 Bound
```

## 5. 验证 MySQL 可用

查看日志：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs statefulset/pyclaw-mysql --tail=100
```

在 MySQL Pod 内执行 ping：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec statefulset/pyclaw-mysql -- \
  sh -c 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

期望输出：

```text
mysqld is alive
```

验证数据库：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec statefulset/pyclaw-mysql -- \
  sh -c 'mysql -h 127.0.0.1 -upyclaw -p"$MYSQL_PASSWORD" -e "show databases;"'
```

期望包含：

```text
pyclaw
```

## 6. 创建 pyclaw 队列 DSN Secret

pyclaw-api 不直接读取 MySQL 四个启动变量，而是读取一个 DSN：

```text
OPENCLAW_INGRESS_QUEUE_DSN=mysql+pymysql://pyclaw:<password>@pyclaw-mysql:3306/pyclaw?charset=utf8mb4&table=ingress_queue
```

创建单独 Secret，避免污染已有 `pyclaw-provider-secret`：

```bash
DSN="mysql+pymysql://pyclaw:${MYSQL_PASSWORD}@pyclaw-mysql:3306/pyclaw?charset=utf8mb4&table=ingress_queue"

sudo /usr/local/bin/k3s kubectl -n pyclaw create secret generic pyclaw-ingress-queue-secret \
  --from-literal=OPENCLAW_INGRESS_QUEUE_DSN="$DSN" \
  --dry-run=client -o yaml | sudo /usr/local/bin/k3s kubectl apply -f -
```

查看 Secret 是否存在：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get secret pyclaw-ingress-queue-secret
```

不要用 `kubectl get secret -o yaml` 公开输出生产密码。

## 7. 让 pyclaw-api 切换到 MySQL 队列

本轮新增 overlay：

```text
pyclaw-api-ingressqueue-mysql-values-k3s.yaml
```

内容为：

```yaml
ingressQueue:
  enabled: true
  backend: mysql
  staleAfterSeconds: 300
  mysql:
    dsnSecretName: pyclaw-ingress-queue-secret
    dsnSecretKey: OPENCLAW_INGRESS_QUEUE_DSN
```

如果 ECS 上已有 pyclaw-api 的主 values 文件，例如：

```text
pyclaw-values-k3s.yaml
```

则使用多 values 文件叠加：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-values-k3s.yaml \
  -f pyclaw-api-ingressqueue-mysql-values-k3s.yaml
```

如果当前 ECS 上没有单独的 `pyclaw-values-k3s.yaml`，就把原本部署 pyclaw-api 时使用的 values 文件放在前面，再把该 overlay 放在最后。

## 8. 验证 pyclaw-api 已读取 MySQL 队列配置

重启并等待 rollout：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout restart deployment pyclaw
sudo /usr/local/bin/k3s kubectl -n pyclaw rollout status deployment pyclaw
```

查看环境变量：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw -- \
  sh -c 'printenv | grep OPENCLAW_INGRESS_QUEUE'
```

期望看到：

```text
OPENCLAW_INGRESS_QUEUE_BACKEND=mysql
OPENCLAW_INGRESS_QUEUE_STALE_AFTER_SECONDS=300
OPENCLAW_INGRESS_QUEUE_DSN=mysql+pymysql://...
```

如果不想把 DSN 打到终端，可以只检查是否存在：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw -- \
  sh -c 'test -n "$OPENCLAW_INGRESS_QUEUE_DSN" && echo HAS_DSN || echo NO_DSN'
```

## 9. 验证 pyclaw 能创建 ingress_queue 表

当前 `MySQLIngressQueue` 在初始化时会执行：

```text
create table if not exists ingress_queue (...)
create index ...
```

后续当 pyclaw-api 路径实际调用 `create_ingress_queue_from_env()` 后，表会自动创建。

也可以进入 pyclaw-api Pod 中做一次 Python 级验证：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/pyclaw -- \
  python -c "from openclaw.channels.message.ingress_queue import create_ingress_queue_from_env; q=create_ingress_queue_from_env(); print(type(q).__name__)"
```

期望输出：

```text
MySQLIngressQueue
```

然后检查 MySQL 表：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw exec statefulset/pyclaw-mysql -- \
  sh -c 'mysql -h 127.0.0.1 -upyclaw -p"$MYSQL_PASSWORD" pyclaw -e "show tables;"'
```

期望看到：

```text
ingress_queue
```

## 10. 回滚到 SQLite

如果 MySQL 未准备好，可以暂时回滚到 SQLite。方法是不要叠加 `pyclaw-api-ingressqueue-mysql-values-k3s.yaml`，或在 values 中设置：

```yaml
ingressQueue:
  enabled: true
  backend: sqlite
  sqlitePath: /app/chatdata/ingress_queue.db
```

然后重新执行 pyclaw-api 的 Helm upgrade。

## 11. 卸载 MySQL

如果只是学习测试，卸载 Helm release：

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm uninstall pyclaw-mysql -n pyclaw
```

注意：StatefulSet 的 PVC 通常不会自动删除。确认不需要数据后，再手动删除 PVC：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pvc
```

确认 PVC 名称后再删除。不要在不确认数据价值时直接删除 PVC。

## 12. 后续建议

1. 先完成 MySQL Pod 与 Service 部署。
2. 再创建 `pyclaw-ingress-queue-secret`。
3. 最后让 pyclaw-api 叠加 MySQL ingress queue overlay。
4. 等 Channel worker / webhook route 完成后，再验证真实微信 / 飞书消息是否写入 MySQL `ingress_queue` 表。
5. 如果进入生产用途，优先考虑阿里云 RDS MySQL；K3s 内 MySQL 更适合学习、单机预演和低成本验证。

## 13. 本地验证说明

本机 Windows 环境没有安装 `helm` 命令，因此本轮无法在本地执行：

```bash
helm template pyclaw-mysql ./helm/pyclaw-mysql -n pyclaw -f pyclaw-mysql-values-k3s.yaml
helm template pyclaw ./helm/pyclaw -n pyclaw -f pyclaw-api-ingressqueue-mysql-values-k3s.yaml
```

请在 ECS 上部署前先执行上述两条 `helm template` 命令。如果能正常输出 YAML，再执行 `helm upgrade --install`。
