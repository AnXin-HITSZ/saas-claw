# 多用户 Claw 与独立 Sandbox Runner Pod 技术设计

更新时间：2026-07-13

## 1. 背景

`pyclaw` 当前已经具备以下基础能力：

```text
1. Web 管理侧：Spring Backend + pyclaw-web。
2. Channel 入站：Feishu / WeChat webhook。
3. 异步处理：pyclaw-channel-worker 从 ingress queue 中领取消息。
4. Agent 路由：根据 channel、peer、mention、command 等 Route Binding 找到 Agent。
5. Provider 配置：Agent 可绑定独立 Provider Config。
6. 工具策略：Agent 可通过 Profile / Allow 列表控制工具暴露。
7. Host SSH 只读工具：API / Worker Pod 已支持通过 Kubernetes Secret 挂载 SSH 连接信息。
```

后续产品方向从“单实例机器人”升级为“多用户 Claw 平台”：

```text
用户登录 Web
  -> 创建自己的 Claw
  -> 在 Web 端新建会话并直接对话 / 工作
  -> 可选绑定到自己的飞书应用或群聊
  -> 不同用户之间配置、会话、文件和工具执行环境隔离
```

本文档描述一种适合个人简历项目展示、用户量较少场景下的一步到位方案：

```text
用户创建 Claw 时，平台立即为该 Claw 创建独立 Kubernetes Sandbox Runner。
```

每个 Claw 对应：

```text
PVC:        workspace-claw-{id}
Deployment: sandbox-runner-claw-{id}
Service:    sandbox-runner-claw-{id}
```

注意：前一版文档曾将带花括号占位符的资源名直接写入标题。部分 VSCode Markdown 插件会把这种标题识别成标题属性或锚点元数据，可能触发 `TypeError: Ye.replace is not a function`。本版保留代码块中的资源名示例，但不在标题和普通正文中使用花括号占位符。

## 2. 目标与非目标

### 2.1 目标

1. 用户可在 Web 端创建多个 Claw。
2. 每个 Claw 拥有独立配置、会话、Provider、Channel Binding 和 Tool Policy。
3. 每个 Claw 创建时自动分配独立 workspace PVC。
4. 每个 Claw 创建时自动启动独立 sandbox runner Pod。
5. pyclaw-api / channel-worker 可根据 `claw_id` 调用对应 runner Service。
6. Feishu 和 Web 作为不同入口，最终都可路由到同一个 Claw。
7. 普通用户 Claw 不直接使用宿主机 SSH、kubectl、helm 等高危能力。
8. 通过 Resource Limit、SecurityContext、NetworkPolicy 控制沙箱边界。

### 2.2 非目标

第一阶段不追求：

```text
1. 无限用户并发。
2. 每个租户独立一套 pyclaw 平台。
3. 用户自定义任意容器镜像。
4. 用户直接访问 Kubernetes API。
5. 普通用户开放宿主机 SSH / kubectl / helm。
6. 生产级计费、限流和配额售卖体系。
```

本方案优先服务：

```text
个人 demo
简历项目
低并发小规模 SaaS 原型
展示多租户、K8s 编排、沙箱隔离和 Agent 平台设计能力
```

## 3. 核心概念

### 3.1 Claw

Claw 是用户创建的 Agent 实例，不等同于一个 Channel，也不等同于一个 Pod。

建议抽象：

```text
Claw = 配置 + 会话 + Channel Binding + Tool Policy + Workspace + Sandbox Runner
```

一个 Claw 可以有多个入口：

```text
Web Conversation
Feishu Channel Binding
后续可能的 API / Slack / 企业微信 / 钉钉入口
```

### 3.2 Workspace

Workspace 是 Claw 的持久化工作目录。

K3s 中建议使用 PVC 表达：

```text
PVC: workspace-claw-{claw_id}
Pod mountPath: /workspace
```

Workspace 可存放：

```text
用户上传文件
代码仓库 clone
任务产物
Claw 生成的文件
知识库中间文件
局部缓存
```

### 3.3 Sandbox Runner

Sandbox Runner 是每个 Claw 的独立运行时。

它是一个受限 Pod，由 Deployment 管理：

```text
Deployment: sandbox-runner-claw-{claw_id}
Pod:        sandbox-runner-claw-{claw_id}-{replicaset_hash}-{pod_suffix}
```

Runner 负责：

```text
1. 接收 pyclaw-api / channel-worker 的任务调用。
2. 在 /workspace 中执行允许的工具。
3. 返回文件读取、命令执行、代码检查、任务结果。
4. 记录任务日志和运行状态。
```

### 3.4 Runner Service

Service 是平台访问 runner 的稳定内部地址。

```text
Service: sandbox-runner-claw-{claw_id}
URL:     http://sandbox-runner-claw-{claw_id}:8080
```

Pod 名会随重启变化，但 Service 名不变。

## 4. 总体架构

```text
用户浏览器
  |
  | Web 登录 / 创建 Claw / 新建会话 / 发送消息
  v
pyclaw-web
  |
  v
Spring Backend
  |
  | 管理用户、Claw、Provider、Channel、Route、Tool Policy
  v
MySQL: pyclaw_control


Feishu / Web Chat
  |
  v
pyclaw-api / channel-worker
  |
  | 业务路由：channel / peer / conversation -> claw_id / agent_id
  v
Claw Runtime Resolver
  |
  | 执行路由：claw_id -> sandbox-runner-claw-{id}
  v
Service: sandbox-runner-claw-{id}
  |
  v
Pod: sandbox-runner-claw-{id}
  |
  v
PVC: workspace-claw-{id} -> /workspace
```

## 5. 每个 Claw 的 Kubernetes 资源

### 5.1 PVC 工作区

PVC 是持久化存储声明，作用是为该 Claw 分配独立磁盘空间。

示例：

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: workspace-claw-claw123
  namespace: pyclaw
  labels:
    app.kubernetes.io/name: pyclaw-sandbox-runner
    app.kubernetes.io/component: sandbox-workspace
    pyclaw.io/claw-id: claw123
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

说明：

```text
ReadWriteOnce:
  同一时间通常只挂载到一个节点上的一个或多个 Pod。
  单节点 K3s demo 足够使用。

storage: 1Gi:
  2c4g ECS 上建议从 512Mi~1Gi 起步。
  不要默认给太大，否则磁盘很快被占满。
```

### 5.2 Deployment 运行时

Deployment 负责保证 runner Pod 存活。

示例：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sandbox-runner-claw-claw123
  namespace: pyclaw
  labels:
    app.kubernetes.io/name: pyclaw-sandbox-runner
    app.kubernetes.io/component: sandbox-runner
    pyclaw.io/claw-id: claw123
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: pyclaw-sandbox-runner
      pyclaw.io/claw-id: claw123
  template:
    metadata:
      labels:
        app.kubernetes.io/name: pyclaw-sandbox-runner
        app.kubernetes.io/component: sandbox-runner
        pyclaw.io/claw-id: claw123
    spec:
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
      containers:
        - name: runner
          image: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/sandbox-runner:0.1.0
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8080
          env:
            - name: CLAW_ID
              value: claw123
            - name: WORKSPACE_PATH
              value: /workspace
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop:
                - ALL
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          volumeMounts:
            - name: workspace
              mountPath: /workspace
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: workspace
          persistentVolumeClaim:
            claimName: workspace-claw-claw123
        - name: tmp
          emptyDir:
            sizeLimit: 256Mi
```

关键配置解释：

```text
automountServiceAccountToken: false
  Runner 默认拿不到 Kubernetes API token。

runAsNonRoot / runAsUser
  Runner 不能以 root 用户运行。

allowPrivilegeEscalation: false
  禁止容器内进程提权。

readOnlyRootFilesystem: true
  容器系统目录只读。
  可写目录仅通过 /workspace 和 /tmp 显式提供。

capabilities.drop: ALL
  丢弃 Linux 特权能力。

resources
  为每个 Claw 设置 CPU / 内存边界，防止单个 Claw 拖垮整台 ECS。
```

### 5.3 Service 内部访问入口

Service 为 runner 提供稳定内部访问地址。

示例：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: sandbox-runner-claw-claw123
  namespace: pyclaw
  labels:
    app.kubernetes.io/name: pyclaw-sandbox-runner
    app.kubernetes.io/component: sandbox-runner
    pyclaw.io/claw-id: claw123
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: pyclaw-sandbox-runner
    pyclaw.io/claw-id: claw123
  ports:
    - name: http
      port: 8080
      targetPort: http
```

平台内部调用：

```text
http://sandbox-runner-claw-claw123:8080
```

## 6. 创建 Claw 的完整流程

### 6.1 Web 创建流程

```text
1. 用户登录 pyclaw-web。
2. 用户点击 Create Claw。
3. 前端提交 name、description、agentProfileId、providerConfigId、toolPolicyId 等。
4. Spring Backend 校验用户权限。
5. Spring Backend 写入 claws 表，生成 claw_id。
6. Spring Backend 调用 Sandbox Orchestrator。
7. Sandbox Orchestrator 创建 PVC。
8. Sandbox Orchestrator 创建 Deployment。
9. Sandbox Orchestrator 创建 Service。
10. Spring Backend 记录 sandbox_instances。
11. 等待 runner readiness，或异步将状态从 Provisioning 更新为 Ready。
12. 前端展示 Claw Ready。
```

### 6.2 状态机

建议 Claw 状态：

```text
CREATING
PROVISIONING
READY
DEGRADED
STOPPED
FAILED
DELETING
DELETED
```

状态含义：

```text
CREATING:
  数据库记录已创建，但 K8s 资源尚未开始创建。

PROVISIONING:
  PVC / Deployment / Service 正在创建。

READY:
  Runner Pod ready，Service 有 endpoints。

DEGRADED:
  Claw 配置存在，但 runner 异常或不可访问。

STOPPED:
  后续如果支持 scale-to-zero，可表示 Deployment replicas=0。

FAILED:
  创建失败，需要用户或管理员重试。

DELETING / DELETED:
  删除流程中 / 已删除。
```

## 7. Web 会话与 Feishu 会话如何复用 Claw

### 7.1 Web 端

Web 会话由用户显式选择 Claw。

```text
POST /api/claws/{clawId}/conversations
POST /api/conversations/{conversationId}/messages
```

Web 消息天然知道：

```text
user_id
tenant_id
claw_id
conversation_id
```

因此 Web 路由较简单：

```text
conversation_id -> claw_id -> runner service
```

### 7.2 Feishu 端

Feishu 消息来自 webhook，需要通过 Channel Binding / Route Binding 找到 Claw。

流程：

```text
Feishu webhook
  -> 解密 / 验签
  -> 根据 app_id / tenant_key / open_chat_id 找 Channel Binding
  -> 根据 Route Binding 找 claw_id / agent_id
  -> 找到或创建对应 conversation
  -> 调用 runner service
```

### 7.3 当前 Route Binding 是否仍有用

有用。

需要区分两种路由：

```text
业务路由:
  channel + peer + mention + command + sender
  -> 找到 claw_id / agent_id

执行路由:
  claw_id
  -> 找到 sandbox-runner-claw-{id} Service
```

当前 Route Binding 属于业务路由，仍然负责“这条消息由哪个 Claw 处理”。

新增的 Service 查找属于执行路由，只负责“这个 Claw 的 runner 在哪里”。

完整链路：

```text
Feishu / Web 消息
  -> 业务路由得到 claw_id
  -> 执行路由得到 Service
  -> 调用 sandbox runner
```

## 8. 当前 Secret 挂载与 Workspace PVC 挂载的区别

当前已经实现的 Host SSH 能力使用 Kubernetes Secret 挂载：

```text
Secret: pyclaw-host-ssh-secret
Mount:
  /var/run/secrets/pyclaw-host-ssh/id_ed25519
  /var/run/secrets/pyclaw-host-ssh/known_hosts
```

它的用途是：

```text
给受信任的 pyclaw-api / channel-worker 提供宿主机 SSH 连接信息。
```

Sandbox Runner 使用 PVC 挂载：

```text
PVC: workspace-claw-{id}
Mount:
  /workspace
```

它的用途是：

```text
给单个 Claw 提供持久化工作目录。
```

两者都是“挂载到 Pod 内路径”，但语义完全不同：

```text
Secret:
  小型敏感配置
  通常只读
  不适合存用户文件

PVC:
  持久化文件系统
  可读写
  适合 workspace、代码、文件、产物
```

普通用户 Claw 的 sandbox runner 不应该挂载 `pyclaw-host-ssh-secret`。

## 9. Runner API 设计

Sandbox Runner 可以提供一个很小的 HTTP API。

### 9.1 健康检查

```http
GET /healthz
```

响应：

```json
{
  "status": "ok",
  "clawId": "claw123",
  "workspace": "/workspace"
}
```

### 9.2 执行任务

```http
POST /v1/run
Content-Type: application/json
```

请求：

```json
{
  "taskId": "task_001",
  "conversationId": "conv_001",
  "messageId": "msg_001",
  "tool": "workspace_read",
  "input": {
    "path": "README.md"
  },
  "limits": {
    "timeoutSeconds": 30,
    "maxOutputChars": 20000
  }
}
```

响应：

```json
{
  "taskId": "task_001",
  "status": "completed",
  "output": {
    "text": "# README\n..."
  },
  "error": null
}
```

### 9.3 工具白名单

Runner 不应该接受任意 shell 字符串。

建议第一阶段只开放明确工具：

```text
workspace_list
workspace_read
workspace_write
workspace_patch
workspace_search
command_run_allowlisted
```

其中 `command_run_allowlisted` 只允许后端下发白名单命令，例如：

```text
python -m pytest
npm test
pnpm test
git status
git diff
```

不提供：

```text
host_shell(command)
任意 SSH 命令透传
kubectl
helm
docker
containerd
任意 bash -c
```

## 10. 数据库模型建议

### 10.1 claws

```text
id
tenant_id
owner_user_id
name
description
agent_profile_id
provider_config_id
tool_policy_id
sandbox_profile_id
status
created_at
updated_at
```

### 10.2 conversations

```text
id
tenant_id
claw_id
channel
title
source_peer_kind
source_peer_id
created_by
created_at
updated_at
```

### 10.3 messages

```text
id
tenant_id
claw_id
conversation_id
role
content
channel
metadata_json
created_at
```

### 10.4 channel_bindings

```text
id
tenant_id
claw_id
channel
enabled
account_id
feishu_app_id
feishu_tenant_key
encrypted_app_secret
encrypted_verification_token
encrypted_encrypt_key
created_at
updated_at
```

### 10.5 sandbox_profiles

```text
id
name
image
cpu_request
memory_request
cpu_limit
memory_limit
workspace_size
network_policy
allowed_tools_json
created_at
updated_at
```

### 10.6 sandbox_instances

```text
id
tenant_id
claw_id
namespace
pvc_name
deployment_name
service_name
pod_name
image
status
last_ready_at
last_error
created_at
updated_at
```

### 10.7 task_runs

```text
id
tenant_id
claw_id
conversation_id
message_id
runner_service_name
tool_name
status
started_at
finished_at
exit_code
input_json
output_json
error_message
```

## 11. 多租户隔离策略

### 11.1 数据隔离

所有用户可见数据必须带：

```text
tenant_id
owner_user_id
claw_id
```

后端查询必须强制加租户条件：

```sql
where tenant_id = :currentTenantId
```

或个人版：

```sql
where owner_user_id = :currentUserId
```

前端隐藏不是安全边界，后端接口必须校验。

### 11.2 文件隔离

每个 Claw 独立 PVC：

```text
workspace-claw-a
workspace-claw-b
```

Runner Pod 只挂载自己的 PVC：

```text
claw-a runner -> workspace-claw-a
claw-b runner -> workspace-claw-b
```

### 11.3 进程隔离

每个 Claw 独立 Deployment / Pod：

```text
sandbox-runner-claw-a
sandbox-runner-claw-b
```

一个 Claw 的 runner 崩溃不影响另一个 Claw。

### 11.4 Secret 隔离

Provider API Key、Feishu App Secret、Encrypt Key 等应：

```text
1. 存在 Spring Backend 管理的 Secret 表中，并加密存储。
2. 不直接挂载给普通 runner，除非该 runner 确实需要。
3. Runner 需要调用模型时，优先由 pyclaw-api 代理调用，而不是把 Provider Key 下发给 runner。
```

## 12. NetworkPolicy 建议

第一阶段可以先在单 namespace 中实现，但建议预留 NetworkPolicy。

默认策略：

```text
Runner 不允许访问 Kubernetes API。
Runner 不允许访问 MySQL。
Runner 不允许访问宿主机 SSH。
Runner 只允许访问 pyclaw-api / 必要外部服务。
```

示例思路：

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: sandbox-runner-deny-by-default
  namespace: pyclaw
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: pyclaw-sandbox-runner
  policyTypes:
    - Ingress
    - Egress
```

后续再增加 allow policy：

```text
允许 pyclaw-api -> runner:8080
允许 runner -> pyclaw-api 内部接口
按需允许 runner -> GitHub / npm registry / pypi / 模型 API
```

注意：K3s 默认网络插件是否完整支持 NetworkPolicy 需要在 ECS 上验证。如果当前 CNI 不支持，需要后续替换或增强网络插件。

## 13. 2c4g ECS 资源约束

当前 ECS 为 2 CPU / 4 GiB 内存，适合 demo，不适合大量常驻 runner。

建议基础限制：

```text
maxActiveClawRunners: 1~2
每个 runner request: 100m / 256Mi
每个 runner limit:   500m / 512Mi
每个 workspace PVC:  512Mi~1Gi
```

如果 runner 需要运行构建、测试、依赖安装：

```text
单 runner limit 可提高到 1000m / 1Gi
但同时活跃 runner 建议限制为 1
```

建议在 Spring Backend 增加配额：

```text
每个用户最多创建 1~3 个 Claw。
全局最多 1~2 个 READY runner。
创建 Claw 前检查当前节点资源。
空闲 Claw 可手动 stop，后续支持 scale-to-zero。
```

即使坚持“创建 Claw 时创建 Pod”，也建议提供：

```text
Stop Claw: Deployment replicas=0，PVC 保留。
Start Claw: Deployment replicas=1，复用 PVC。
Delete Claw: 删除 Deployment / Service，可选择保留或删除 PVC。
```

## 14. Sandbox Orchestrator 设计

Sandbox Orchestrator 是平台内负责操作 Kubernetes 的模块。

可放置位置：

```text
优先：Spring Backend
原因：Claw 创建、用户权限、数据库事务都在 Spring Backend。

备选：pyclaw-api
原因：Python 编写 K8s client 快速，但与控制面数据事务割裂。
```

建议第一阶段放在 Spring Backend：

```text
ClawController
  -> ClawService
      -> SandboxOrchestrator
          -> Kubernetes API
```

### 14.1 权限

Spring Backend Pod 需要一个受限 ServiceAccount。

只允许管理 sandbox 相关资源：

```text
get/list/watch/create/update/delete PVC
get/list/watch/create/update/delete Deployment
get/list/watch/create/update/delete Service
get/list/watch Pod
```

不要授予：

```text
cluster-admin
hostPath
Secret 全局读取
Node 管理
Namespace 管理
```

### 14.2 幂等创建

创建 Claw 时可能出现部分成功：

```text
PVC 创建成功
Deployment 创建失败
Service 未创建
```

Orchestrator 必须幂等：

```text
createOrUpdateWorkspacePvc(claw)
createOrUpdateRunnerDeployment(claw)
createOrUpdateRunnerService(claw)
```

重试时根据 label / name 查找已有资源并修正。

### 14.3 回滚

若创建流程失败：

```text
1. claws.status = FAILED
2. sandbox_instances.last_error 写入错误
3. 保留已创建资源供排查，或按策略自动清理
```

demo 阶段建议保留资源，便于排查。

## 15. 命名规范

Kubernetes 资源名必须满足 DNS label 限制。

建议 `claw_id` 使用短 ID，例如：

```text
claw-7f3a9c
```

资源名：

```text
workspace-claw-7f3a9c
sandbox-runner-claw-7f3a9c
```

Labels：

```text
pyclaw.io/claw-id: claw-7f3a9c
pyclaw.io/tenant-id: tenant-001
pyclaw.io/owner-user-id: user-001
```

注意 label value 也有长度和字符限制，必要时使用短 ID，不放邮箱、中文名等。

## 16. 删除 Claw 流程

删除应区分软删除和资源删除。

### 16.1 软删除

```text
claws.status = DELETED
conversations/messages 保留或隐藏
K8s 资源可按策略保留一段时间
```

### 16.2 停止 Claw

```text
Deployment replicas=0
Service 保留
PVC 保留
```

适合 2c4g 资源不足时使用。

### 16.3 永久删除

```text
删除 Deployment
删除 Service
可选删除 PVC
清理 sandbox_instances
```

PVC 是否删除应由用户选择：

```text
保留 workspace: 可恢复
删除 workspace: 释放磁盘，数据不可恢复
```

## 17. 与现有 Feishu / Web Channel 的关系

### 17.1 Feishu

Feishu 只是入口之一。

```text
Feishu webhook -> Channel Binding -> Route Binding -> claw_id -> runner
```

Feishu App Secret、Verification Token、Encrypt Key 属于 Channel Binding，不属于 runner。

### 17.2 Web

Web 是主入口。

```text
Web conversation -> claw_id -> runner
```

Web 端应支持：

```text
1. 创建 Claw。
2. 查看 Claw 状态。
3. 新建会话。
4. 与 Claw 对话。
5. 查看历史会话。
6. 查看 workspace 文件。
7. 管理 Provider / Tool Policy / Channel Binding。
8. 启动 / 停止 / 删除 Claw。
```

### 17.3 WeChat

WeChat Channel 可以保留代码但默认禁用。

多用户 Claw 平台的第一阶段建议聚焦：

```text
Web + Feishu
```

原因：

```text
1. Web 是 SaaS 主入口。
2. Feishu 适合展示企业 IM 集成。
3. WeChat 被动回复、权限和白名单限制较多，容易干扰 demo。
```

## 18. 实施阶段建议

虽然目标是一步到位创建 Pod，但实现仍可按清晰模块提交。

### 18.1 第一组能力：数据模型

```text
claws
conversations
messages
channel_bindings
sandbox_profiles
sandbox_instances
task_runs
```

### 18.2 第二组能力：K8s Orchestrator

```text
Spring Backend 引入 Kubernetes client
创建 PVC
创建 Deployment
创建 Service
查询 Pod readiness
更新 sandbox_instances
```

### 18.3 第三组能力：sandbox-runner 镜像

```text
FastAPI / Spring Boot / Node 均可
提供 /healthz
提供 /v1/run
实现 workspace_list / read / write / search
实现最小 allowlisted command
```

### 18.4 第四组能力：Web UI

```text
Claw 列表
创建 Claw
Claw 状态
Web 会话列表
Web Chat 页面
Workspace 文件浏览
Feishu 绑定页面
```

### 18.5 第五组能力：Channel 整合

```text
Feishu Channel Binding 映射到 claw_id
Route Binding 支持 claw_id
Worker 调用 runner
Feishu 默认卡片回复
```

## 19. 运维验证命令

创建 Claw 后可在 ECS 上验证：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pvc \
  -l app.kubernetes.io/component=sandbox-workspace

sudo /usr/local/bin/k3s kubectl -n pyclaw get deploy,svc,pods \
  -l app.kubernetes.io/name=pyclaw-sandbox-runner

sudo /usr/local/bin/k3s kubectl -n pyclaw describe deployment sandbox-runner-claw-claw123

sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/sandbox-runner-claw-claw123 --tail=100

sudo /usr/local/bin/k3s kubectl -n pyclaw exec deployment/sandbox-runner-claw-claw123 -- \
  sh -c 'id && pwd && ls -la /workspace'
```

验证 Service：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw run curl-test \
  --image=curlimages/curl:8.8.0 \
  --rm -it --restart=Never \
  -- curl -s http://sandbox-runner-claw-claw123:8080/healthz
```

## 20. 简历表述建议

可以写：

```text
设计并实现多租户 Claw 沙箱编排能力：用户在 Web 端创建 Claw 后，平台自动在 K3s 中为其创建独立 PVC、Deployment 与 Service，提供隔离的持久化工作区、独立运行时和稳定内部访问入口；通过 Resource Limit、SecurityContext、NetworkPolicy 与受限 RBAC 控制资源和权限边界，并复用现有 Channel Route 将 Web / Feishu 消息路由到对应 Claw Runner。
```

也可以拆成两条：

```text
1. 设计多租户 Agent 工作台，支持用户创建独立 Claw、Web 会话、Feishu 绑定、Provider 配置和工具策略隔离。
2. 基于 K3s 实现 Claw Sandbox Runner 编排，自动创建 workspace PVC、runner Deployment 和 ClusterIP Service，保障用户文件、执行进程和资源配额隔离。
```

## 21. 风险与待确认事项

```text
1. 2c4g ECS 资源有限，需要限制 Claw 数量和 runner 资源。
2. K3s 当前 CNI 是否支持 NetworkPolicy 需要实际验证。
3. sandbox-runner 镜像中允许哪些语言和工具需要控制，否则镜像会变大。
4. runner 是否直接调用模型，还是统一由 pyclaw-api 代理调用，需要结合安全边界决定。
5. workspace PVC 的备份、清理和配额统计需要后续补齐。
6. 删除 Claw 时 PVC 默认保留还是删除，需要产品侧明确。
7. Feishu 多用户场景下 app_id / tenant_key / open_chat_id 到 claw_id 的匹配规则需要前端配置闭环。
```

## 22. 推荐默认配置

面向当前 2c4g ECS demo：

```yaml
sandbox:
  namespace: pyclaw
  maxClawsPerUser: 2
  maxActiveRunners: 2
  defaultWorkspaceSize: 1Gi
  runner:
    image: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/sandbox-runner:0.1.0
    port: 8080
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
    security:
      runAsNonRoot: true
      runAsUser: 10001
      allowPrivilegeEscalation: false
      readOnlyRootFilesystem: true
      dropCapabilities:
        - ALL
```

该配置可以支撑小规模演示：

```text
基础服务常驻
1~2 个 Claw runner 常驻
Web + Feishu 双入口
独立 workspace
清晰的 K8s 隔离模型
```