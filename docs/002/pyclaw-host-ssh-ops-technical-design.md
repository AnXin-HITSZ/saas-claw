# pyclaw Host SSH Ops Technical Design

本文记录 `pyclaw` 通过 SSH 受控感知和运维 ECS 宿主机的技术方案。该方案对应“方案一：SSH 到宿主机”，目标是在不把 `pyclaw-api` 容器直接提升为宿主机 root 权限的前提下，让 Agent 可以查看宿主机状态、分析日志，并在审批和白名单保护下执行有限运维操作。

## 1. 背景

当前 `pyclaw-api` 运行在 K3s Pod 中。Agent 的工作目录来自容器进程，例如：

```text
/app
```

这意味着 Agent 默认只能看到容器内文件系统，不能天然感知 ECS 宿主机上的：

```text
/opt/pyclaw
/etc/systemd
/var/log
/var/lib/rancher/k3s
宿主机 systemctl / journalctl / docker / k3s / helm 环境
```

如果直接给 Pod 配置 `privileged: true`、挂载 `/`、挂载 Docker socket，Agent 会接近宿主机 root 权限。这虽然能力强，但风险过高，不适合作为第一阶段方案。

因此推荐采用 SSH 运维通道：

```text
pyclaw-web
  -> spring-backend
  -> pyclaw-api
  -> Host SSH Ops Tool
  -> ECS 宿主机上的 pyclaw-ops 用户
```

Agent 仍然运行在容器内，但通过一个受控工具连接宿主机。所有宿主机操作都经过工具白名单、安全策略、审计和必要的人类审批。

## 2. 目标

本方案要实现：

```text
1. Agent 可以读取宿主机基础状态。
2. Agent 可以查看 K3s、Docker、systemd、日志等运维信息。
3. Agent 可以在白名单内执行少量低风险命令。
4. 高风险命令必须进入审批流程。
5. 所有宿主机操作都可审计、可追踪、可回放。
6. 不把 pyclaw-api Pod 直接变成宿主机 root。
```

首期建议只实现只读观测能力：

```text
host_whoami
host_pwd
host_uname
host_uptime
host_df
host_free
host_systemctl_status
host_journalctl_tail
host_kubectl_get
host_kubectl_logs
host_helm_status
```

第二阶段再开放受控写操作：

```text
host_systemctl_restart
host_kubectl_rollout_restart
host_helm_upgrade
```

## 3. 非目标

第一阶段不做：

```text
1. 不开放任意 ssh shell。
2. 不允许 Agent 自由输入任意宿主机命令。
3. 不挂载宿主机根目录 `/` 到 pyclaw-api Pod。
4. 不挂载 `/var/run/docker.sock`。
5. 不给 pyclaw-api Pod 配置 privileged。
6. 不使用宿主机 root 作为 SSH 登录用户。
```

这些能力后续可以作为单独方案评估，但不能作为默认能力。

## 4. 核心架构

### 4.1 运行链路

```text
用户在前端发起 Agent 请求
  -> spring-backend 校验 JWT 或 API Token
  -> spring-backend 调用 pyclaw-api
  -> pyclaw-api 根据 toolProfile 构造 ToolRegistry
  -> Agent 选择 host_xxx 工具
  -> Host SSH Ops Tool 根据工具名组装受控命令
  -> 通过 SSH 连接 ECS 宿主机 pyclaw-ops 用户
  -> 宿主机执行白名单命令
  -> 返回 stdout/stderr/exit_code
  -> 写入 transcript 与审计日志
```

### 4.2 信任边界

```text
浏览器用户
  只拥有 Spring Backend 授予的业务权限。

spring-backend
  负责用户身份、权限和 API 入口审计。

pyclaw-api
  负责 Agent 编排、工具选择、工具策略执行。

Host SSH Ops Tool
  负责把高层工具调用转换为宿主机命令。

ECS 宿主机 pyclaw-ops 用户
  负责执行被允许的命令。
```

关键原则：

```text
Agent 不能直接拿到裸 SSH 私钥。
Agent 不能直接拼接任意 shell 命令。
Agent 只能调用已注册的 host_xxx 工具。
host_xxx 工具只能执行预定义命令模板。
```

## 5. 宿主机用户设计

在 ECS 宿主机创建专用运维用户：

```bash
sudo adduser --disabled-password --gecos "" pyclaw-ops
sudo mkdir -p /home/pyclaw-ops/.ssh
sudo chown -R pyclaw-ops:pyclaw-ops /home/pyclaw-ops/.ssh
sudo chmod 700 /home/pyclaw-ops/.ssh
```

不要使用：

```text
root
ubuntu
admin
```

原因：

```text
root 权限过大。
普通登录用户通常还承担人工运维职责，不适合交给 Agent。
专用用户便于隔离、审计、禁用和轮换密钥。
```

## 6. SSH 密钥设计

在安全的本地机器或 CI 环境生成专用密钥：

```bash
ssh-keygen -t ed25519 -C "pyclaw-host-ops" -f pyclaw_host_ops_ed25519
```

生成两个文件：

```text
pyclaw_host_ops_ed25519      私钥，放入 K8s Secret
pyclaw_host_ops_ed25519.pub  公钥，放入宿主机 authorized_keys
```

把公钥加入宿主机：

```bash
sudo tee -a /home/pyclaw-ops/.ssh/authorized_keys < pyclaw_host_ops_ed25519.pub
sudo chown pyclaw-ops:pyclaw-ops /home/pyclaw-ops/.ssh/authorized_keys
sudo chmod 600 /home/pyclaw-ops/.ssh/authorized_keys
```

建议在 `authorized_keys` 中加入限制项。示例：

```text
no-agent-forwarding,no-X11-forwarding,no-port-forwarding ssh-ed25519 AAAA... pyclaw-host-ops
```

如果后续实现强制命令入口，也可以使用：

```text
command="/usr/local/bin/pyclaw-host-command-gateway",no-agent-forwarding,no-X11-forwarding,no-port-forwarding ssh-ed25519 AAAA... pyclaw-host-ops
```

第一阶段可先不使用 `command=...`，由 pyclaw 的工具层做命令白名单；生产阶段建议补充宿主机侧 command gateway，形成双层保护。

## 7. K8s Secret 设计

SSH 私钥不进入 Git 仓库，不写入 values 文件。

创建 Secret：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw create secret generic pyclaw-host-ssh-secret \
  --from-file=id_ed25519=./pyclaw_host_ops_ed25519 \
  --from-literal=host=127.0.0.1 \
  --from-literal=port=22 \
  --from-literal=username=pyclaw-ops
```

如果 pyclaw-api Pod 访问宿主机，`host` 不一定是 `127.0.0.1`。在 K3s 中可选：

```text
1. 宿主机内网 IP，例如 172.22.x.x。
2. ECS 私网 IP。
3. 为宿主机 SSH 暴露的固定地址。
```

如果 Pod 使用 `hostNetwork: true`，才可能把 `127.0.0.1` 视为宿主机网络命名空间。但不建议第一阶段这么做。

建议显式配置：

```text
HOST_SSH_HOST=<ECS_PRIVATE_IP>
HOST_SSH_PORT=22
HOST_SSH_USERNAME=pyclaw-ops
HOST_SSH_KEY_PATH=/var/run/secrets/pyclaw-host-ssh/id_ed25519
```

## 8. pyclaw-api Helm 配置

后续可在 `pyclaw-api` Helm chart 中增加：

```yaml
hostSsh:
  enabled: false
  existingSecret: pyclaw-host-ssh-secret
  mountPath: /var/run/secrets/pyclaw-host-ssh
```

Deployment 增加 Secret volume：

```yaml
volumes:
  - name: host-ssh-secret
    secret:
      secretName: pyclaw-host-ssh-secret
      defaultMode: 0400

containers:
  - name: pyclaw-api
    volumeMounts:
      - name: host-ssh-secret
        mountPath: /var/run/secrets/pyclaw-host-ssh
        readOnly: true
    env:
      - name: HOST_SSH_KEY_PATH
        value: /var/run/secrets/pyclaw-host-ssh/id_ed25519
      - name: HOST_SSH_HOST
        valueFrom:
          secretKeyRef:
            name: pyclaw-host-ssh-secret
            key: host
      - name: HOST_SSH_PORT
        valueFrom:
          secretKeyRef:
            name: pyclaw-host-ssh-secret
            key: port
      - name: HOST_SSH_USERNAME
        valueFrom:
          secretKeyRef:
            name: pyclaw-host-ssh-secret
            key: username
```

注意：

```text
Secret 只挂给 pyclaw-api。
spring-backend 和 pyclaw-web 不应拿到宿主机 SSH 私钥。
```

## 9. Python 依赖选择

有两条路线：

```text
1. 使用系统 ssh 命令
2. 使用 Python SSH 库
```

### 9.1 系统 ssh 命令

优点：

```text
OpenSSH 行为稳定。
容易复现人工命令。
不需要 Python 额外依赖。
```

缺点：

```text
需要容器镜像安装 openssh-client。
命令参数必须严格使用 list argv，不能拼接 shell 字符串。
```

示例：

```python
args = [
    "ssh",
    "-i", key_path,
    "-o", "BatchMode=yes",
    "-o", "StrictHostKeyChecking=yes",
    "-p", str(port),
    f"{username}@{host}",
    "--",
    "uname",
    "-a",
]
```

### 9.2 Python SSH 库

可选库：

```text
asyncssh
paramiko
```

优点：

```text
更容易做超时、连接池、结构化错误。
不依赖容器内 ssh 二进制。
```

缺点：

```text
需要额外依赖。
需要认真处理 known_hosts、主机指纹校验和密钥权限。
```

第一阶段建议使用系统 `ssh` 命令，但必须遵守：

```text
不经过 shell=True。
不把用户输入拼接进单个命令字符串。
所有参数使用 list[str]。
```

## 10. 工具接口设计

不要提供一个通用的：

```text
host_shell(command: string)
```

而是提供语义化工具：

```text
host_uname()
host_uptime()
host_df()
host_free()
host_systemctl_status(service: string)
host_journalctl_tail(unit: string, lines: int)
host_kubectl_get(resource: string, namespace?: string)
host_kubectl_logs(namespace: string, selector_or_pod: string, lines: int)
host_helm_status(release: string, namespace: string)
```

这样工具层可以清楚知道“这次操作是什么”，更容易做白名单和审计。

## 11. 命令模板

### 11.1 只读命令

```text
host_whoami
  -> whoami

host_pwd
  -> pwd

host_uname
  -> uname -a

host_uptime
  -> uptime

host_df
  -> df -h

host_free
  -> free -h

host_systemctl_status(service)
  -> systemctl status <service> --no-pager

host_journalctl_tail(unit, lines)
  -> journalctl -u <unit> -n <lines> --no-pager

host_kubectl_get(resource, namespace)
  -> /usr/local/bin/k3s kubectl -n <namespace> get <resource>

host_kubectl_logs(namespace, pod, lines)
  -> /usr/local/bin/k3s kubectl -n <namespace> logs <pod> --tail=<lines>

host_helm_status(release, namespace)
  -> helm -n <namespace> status <release>
```

### 11.2 受控写命令

这些命令默认不开启，需要审批：

```text
host_systemctl_restart(service)
  -> sudo systemctl restart <service>

host_kubectl_rollout_restart(namespace, deployment)
  -> /usr/local/bin/k3s kubectl -n <namespace> rollout restart deployment <deployment>

host_helm_upgrade(release, chart, namespace, values)
  -> helm upgrade --install <release> <chart> -n <namespace> -f <values>
```

## 12. 参数白名单

任何来自 Agent 的参数都必须校验。

### 12.1 service / unit

允许：

```text
k3s
docker
containerd
nginx
```

拒绝：

```text
../../xxx
k3s; rm -rf /
$(curl ...)
任意空格拼接参数
```

建议正则：

```text
^[a-zA-Z0-9_.@-]+$
```

并且再叠加枚举白名单。

### 12.2 namespace

允许：

```text
pyclaw
kube-system
default
```

生产环境建议只允许：

```text
pyclaw
```

### 12.3 resource

允许：

```text
pods
svc
deployments
ingress
configmap
secret
```

Secret 默认不要直接读取内容，只允许：

```text
kubectl get secret
```

不允许：

```text
kubectl get secret xxx -o yaml
kubectl describe secret xxx
```

除非进入专门审批流程。

### 12.4 lines

日志行数限制：

```text
1 <= lines <= 500
```

避免 Agent 一次拉取过多日志导致上下文爆炸或接口超时。

## 13. sudoers 设计

第一阶段只读工具尽量不需要 sudo。

第二阶段如果要允许重启服务，可以配置最小 sudoers：

```bash
sudo visudo -f /etc/sudoers.d/pyclaw-ops
```

示例：

```text
pyclaw-ops ALL=(root) NOPASSWD: /bin/systemctl restart k3s
pyclaw-ops ALL=(root) NOPASSWD: /bin/systemctl status k3s
pyclaw-ops ALL=(root) NOPASSWD: /usr/bin/journalctl -u k3s *
```

注意：

```text
不要写成 NOPASSWD: ALL。
不要允许任意 systemctl restart *。
不要允许编辑 sudoers、passwd、ssh、iptables 等系统关键配置。
```

## 14. 工具 Profile 设计

建议新增或扩展工具 profile：

```text
minimal
  不包含宿主机工具。

readonly
  不默认包含宿主机工具，避免普通文件读取和宿主机读取混淆。

ops_readonly
  包含只读 host_xxx 工具。

ops
  包含只读 host_xxx 工具和少量需要审批的写操作。

full
  是否包含宿主机工具必须由部署参数显式开启。
```

也可以保留现有 profile，另加显式开关：

```json
{
  "toolProfile": "readonly",
  "toolsAlsoAllow": [
    "host_uname",
    "host_df",
    "host_kubectl_get"
  ]
}
```

更推荐新增 `ops_readonly`，因为它表达更清晰。

## 15. 审批机制

所有写操作需要审批，例如：

```text
systemctl restart
kubectl rollout restart
helm upgrade
删除文件
修改配置
读取 Secret 明文
```

审批事件结构建议：

```json
{
  "type": "tool_approval_required",
  "toolName": "host_systemctl_restart",
  "arguments": {
    "service": "k3s"
  },
  "risk": "high",
  "summary": "Restart systemd service k3s on ECS host",
  "commandPreview": "sudo systemctl restart k3s"
}
```

审批通过后才执行。审批拒绝时，工具返回：

```json
{
  "ok": false,
  "error": "user denied host operation"
}
```

当前前端可以先只支持只读宿主机工具；写操作审批 UI 后续再接。

## 16. 审计字段

每次宿主机工具调用都应记录：

```text
session_id
user_id / actor_id
tool_name
arguments
host
username
command_template_id
command_preview
exit_code
stdout_truncated
stderr_truncated
duration_ms
success
approval_id
created_at
```

注意：

```text
不要记录 SSH 私钥。
不要记录完整 Secret 明文。
stdout/stderr 需要截断。
```

## 17. 返回结果限制

宿主机命令输出必须限制：

```text
stdout 最大 32 KiB
stderr 最大 16 KiB
命令超时默认 10 秒
日志 tail 默认 100 行，最大 500 行
```

超限时返回：

```json
{
  "truncated": true,
  "stdout": "...",
  "stderr": "..."
}
```

## 18. Host Key 校验

生产环境必须校验宿主机指纹，避免中间人攻击。

不要长期使用：

```text
StrictHostKeyChecking=no
```

建议在 Secret 或 ConfigMap 中提供 known_hosts：

```bash
ssh-keyscan -H <ECS_PRIVATE_IP> > known_hosts
```

挂载到：

```text
/var/run/secrets/pyclaw-host-ssh/known_hosts
```

SSH 参数：

```text
-o UserKnownHostsFile=/var/run/secrets/pyclaw-host-ssh/known_hosts
-o StrictHostKeyChecking=yes
```

## 19. Python 实现草图

### 19.1 SSH Client

```python
@dataclass
class HostSshConfig:
    host: str
    port: int
    username: str
    key_path: Path
    known_hosts_path: Path
    timeout_seconds: float = 10.0


class HostSshClient:
    async def run(self, argv: list[str], *, timeout_seconds: float | None = None) -> HostCommandResult:
        ssh_argv = [
            "ssh",
            "-i", str(self.config.key_path),
            "-o", "BatchMode=yes",
            "-o", f"UserKnownHostsFile={self.config.known_hosts_path}",
            "-o", "StrictHostKeyChecking=yes",
            "-p", str(self.config.port),
            f"{self.config.username}@{self.config.host}",
            "--",
            *argv,
        ]
        ...
```

### 19.2 工具实现

```python
async def host_uname(arguments: dict[str, Any], context: ToolContext) -> ToolResult:
    result = await context.host_ssh.run(["uname", "-a"])
    return host_result_to_tool_result(result)


async def host_kubectl_get(arguments: dict[str, Any], context: ToolContext) -> ToolResult:
    namespace = validate_namespace(arguments.get("namespace") or "pyclaw")
    resource = validate_k8s_resource(arguments["resource"])
    result = await context.host_ssh.run([
        "/usr/local/bin/k3s",
        "kubectl",
        "-n",
        namespace,
        "get",
        resource,
    ])
    return host_result_to_tool_result(result)
```

### 19.3 禁止通用 shell

不要实现：

```python
async def host_shell(command: str) -> ToolResult:
    ...
```

如果未来确实需要，必须：

```text
1. 接入 Shell Guard。
2. 命令 AST 解析。
3. 审批。
4. 宿主机侧 command gateway。
5. 默认禁用。
```

## 20. 与现有 ToolPolicy 的关系

宿主机 SSH 工具应该接入当前工具系统：

```text
ToolCatalog
  注册 host_xxx 工具元数据。

ToolPolicy
  根据 profile / allow / deny 决定是否暴露工具。

ToolExecutor
  执行前再次检查工具是否存在、是否允许、是否需要审批。

Sandbox Guard
  对 host_xxx 工具参数做边界校验。
```

建议给宿主机工具增加标签：

```text
tags:
  - host
  - ops
  - readonly
```

写操作增加：

```text
tags:
  - host
  - ops
  - mutation
  - approval_required
```

## 21. 与前端的关系

前端 Agent 页面后续可增加：

```text
Tool Profile:
  minimal
  readonly
  coding
  ops_readonly
  ops
```

或者增加高级参数：

```text
Host Ops: off / readonly / approval-required
```

第一阶段不建议默认显示给普通用户。只有拥有专门权限的用户可见：

```text
host:read
host:operate
```

## 22. Spring Backend 权限建议

新增权限点：

```text
host:read
  可调用只读宿主机工具。

host:operate
  可发起需要审批的宿主机操作。

host:approve
  可审批高风险宿主机操作。
```

调用 Agent 时，Spring Backend 可以根据当前用户权限限制可传入的 `toolProfile`：

```text
没有 host:read
  不允许 toolProfile=ops_readonly / ops

没有 host:operate
  不允许 ops 写操作

没有 host:approve
  不能审批高风险工具调用
```

不要只依赖前端隐藏按钮，后端必须校验。

## 23. 部署步骤草案

### 23.1 宿主机准备

```bash
sudo adduser --disabled-password --gecos "" pyclaw-ops
sudo mkdir -p /home/pyclaw-ops/.ssh
sudo chmod 700 /home/pyclaw-ops/.ssh
sudo chown -R pyclaw-ops:pyclaw-ops /home/pyclaw-ops/.ssh
```

### 23.2 生成密钥

```bash
ssh-keygen -t ed25519 -C "pyclaw-host-ops" -f pyclaw_host_ops_ed25519
```

### 23.3 安装公钥

```bash
sudo tee -a /home/pyclaw-ops/.ssh/authorized_keys < pyclaw_host_ops_ed25519.pub
sudo chmod 600 /home/pyclaw-ops/.ssh/authorized_keys
sudo chown pyclaw-ops:pyclaw-ops /home/pyclaw-ops/.ssh/authorized_keys
```

### 23.4 生成 known_hosts

```bash
ssh-keyscan -H <ECS_PRIVATE_IP> > known_hosts
```

### 23.5 创建 K8s Secret

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw create secret generic pyclaw-host-ssh-secret \
  --from-file=id_ed25519=./pyclaw_host_ops_ed25519 \
  --from-file=known_hosts=./known_hosts \
  --from-literal=host=<ECS_PRIVATE_IP> \
  --from-literal=port=22 \
  --from-literal=username=pyclaw-ops
```

### 23.6 部署 pyclaw-api

```bash
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw ./helm/pyclaw \
  -n pyclaw \
  -f pyclaw-values-k3s.yaml \
  --set hostSsh.enabled=true \
  --set hostSsh.existingSecret=pyclaw-host-ssh-secret
```

### 23.7 验证

只读验证：

```text
Prompt: 请使用宿主机工具查看宿主机 uname 和磁盘使用情况。
Tool Profile: ops_readonly
```

预期：

```text
Agent 调用 host_uname 和 host_df。
返回宿主机内核、系统信息和磁盘使用情况。
不是 pyclaw-api 容器内的 /app 信息。
```

## 24. 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| 私钥泄露 | Secret 被读取后可 SSH 到宿主机 | 限制 Secret 权限、专用用户、轮换密钥 |
| 命令注入 | Agent 参数拼接成危险 shell | 禁止 shell=True、argv 参数化、白名单 |
| 权限过大 | pyclaw-ops 可执行过多命令 | sudoers 最小化 |
| 日志泄密 | journalctl / kubectl logs 可能包含密钥 | 输出截断、敏感信息脱敏 |
| 误操作 | Agent 重启服务或升级失败 | 审批、dry-run、回滚方案 |
| 横向移动 | SSH 用户访问其他机器 | 网络安全组限制、authorized_keys 限制 |
| 历史污染 | Agent 历史中残留高权限工具信息 | toolProfile 降级提示、上下文过滤 |

## 25. 推荐实施顺序

```text
1. 新增文档和设计评审。
2. 实现 HostSshClient，但不注册工具。
3. 新增 host_uname / host_df / host_free 三个只读工具。
4. 新增 ops_readonly profile。
5. 接入 K8s Secret 挂载。
6. 在测试环境验证 SSH 到宿主机。
7. 增加审计记录。
8. 增加 host_kubectl_get / host_kubectl_logs。
9. 增加前端 profile 选项和权限控制。
10. 设计审批 UI。
11. 最后再开放受控写操作。
```

## 26. 结论

通过 SSH 到宿主机是当前最适合 `pyclaw` 的宿主机运维方案：

```text
能力足够：可以看宿主机、查日志、管 K3s。
边界清晰：Agent 不直接获得宿主机 root。
可审计：每次 host_xxx 工具调用都能记录。
可渐进：先只读，再审批写操作。
可回收：禁用 pyclaw-ops 用户或移除 authorized_keys 即可切断能力。
```

因此后续宿主机运维能力应优先沿该方案实现，而不是直接使用 privileged Pod 或挂载宿主机根目录。
