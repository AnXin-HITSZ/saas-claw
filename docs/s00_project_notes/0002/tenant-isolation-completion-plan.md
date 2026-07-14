# PyClaw 租户隔离补全实施方案

> 日期：2026-07-14  
> 面向执行者：Claude Code  
> 目标：在现有“每用户 namespace + 每 Claw sandbox runner”的基础上，补齐生产级租户隔离所需的网络、资源、Secret、数据访问、清理、前端和验收闭环。

## 1. 当前状态

当前已经验证通过的主链路：

```text
用户注册 / 创建
  -> Spring Backend 自动创建 namespace: pyclaw-user-{userId}

创建 Claw
  -> Spring Backend 自动创建:
     Secret: aliyun-acr-pull-secret
     Deployment: sandbox-runner-{clawId}
     Service: sandbox-runner-{clawId}
     PVC: workspace-{clawId}
     Pod: sandbox-runner-{clawId}-...

sandbox-runner
  -> /healthz 正常
  -> /v1/workspace 正常
  -> 返回正确 clawId / ownerUserId / workspace
```

已验证示例：

```text
namespace: pyclaw-user-97b9b2a9-3165-46ac-942d-e47b4b352530
clawId: fa951a96-3a18-4ce6-bafe-516bf2a5503a
runner Pod: 1/1 Running
workspace: /workspace
```

现有关键代码：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/sandbox/SandboxOrchestratorService.java
spring-backend/src/main/java/com/anxin/pyclaw/backend/config/PyclawSandboxProperties.java
spring-backend/helm/templates/serviceaccount-rbac.yaml
spring-backend/helm/templates/deployment.yaml
sandbox-runner/app/main.py
```

当前还不能称为“生产级完全隔离”，因为还缺：

```text
NetworkPolicy
ResourceQuota / LimitRange
用户/Claw Secret 管理
后端数据访问隔离系统审计
sandbox-runner 访问网关
runner 命令执行安全策略
删除 / 禁用 / 回收策略
前端可视化闭环
自动化验收测试
```

## 2. 目标隔离模型

最终模型：

```text
User = Tenant

每个 User:
  namespace: pyclaw-user-{userId}
  ResourceQuota: 限制该用户总资源
  LimitRange: 限制该 namespace 内默认资源
  NetworkPolicy: 限制 namespace 内外访问
  Secret: 用户级 Secret，仅存在该 namespace

每个 Claw:
  Deployment: sandbox-runner-{clawId}
  Service: sandbox-runner-{clawId}
  PVC: workspace-{clawId}
  Secret: claw-secret-{clawId}
  ServiceAccount: sandbox-runner 或 sandbox-runner-{clawId}
```

控制面仍运行在 `pyclaw` namespace：

```text
pyclaw namespace:
  Spring Backend
  pyclaw-api
  channel-worker
  web
  MySQL
```

用户沙箱运行在用户 namespace：

```text
pyclaw-user-{userId} namespace:
  sandbox-runner-{clawId}
  workspace-{clawId}
  claw-secret-{clawId}
```

## 3. 总体实施顺序

建议按以下顺序实现。每一步都应保持幂等：重复调用不会报错，也不会破坏已有资源。

```text
1. Kubernetes 隔离资源
2. 用户 / Claw Secret 管理
3. sandbox-runner 访问网关
4. 数据访问隔离审计
5. runner 安全加固
6. 删除、禁用、回收策略
7. 前端页面补齐
8. 自动化验收
```

## 4. Kubernetes 隔离资源

### 4.1 新增配置项

修改：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/config/PyclawSandboxProperties.java
spring-backend/src/main/resources/application.yml
spring-backend/helm/values.yaml
spring-backend/helm/templates/configmap.yaml
```

新增配置：

```yaml
pyclaw:
  sandbox:
    network-policy-enabled: true
    resource-quota-enabled: true
    limit-range-enabled: true
    namespace-cpu-request-quota: "1000m"
    namespace-cpu-limit-quota: "2000m"
    namespace-memory-request-quota: "2Gi"
    namespace-memory-limit-quota: "4Gi"
    namespace-pvc-quota: "5"
    namespace-storage-quota: "5Gi"
    default-cpu-request: "50m"
    default-memory-request: "128Mi"
    default-cpu-limit: "500m"
    default-memory-limit: "512Mi"
```

Helm 环境变量：

```yaml
env:
  PYCLAW_SANDBOX_NETWORK_POLICY_ENABLED: "true"
  PYCLAW_SANDBOX_RESOURCE_QUOTA_ENABLED: "true"
  PYCLAW_SANDBOX_LIMIT_RANGE_ENABLED: "true"
  PYCLAW_SANDBOX_NAMESPACE_CPU_REQUEST_QUOTA: "1000m"
  PYCLAW_SANDBOX_NAMESPACE_CPU_LIMIT_QUOTA: "2000m"
  PYCLAW_SANDBOX_NAMESPACE_MEMORY_REQUEST_QUOTA: "2Gi"
  PYCLAW_SANDBOX_NAMESPACE_MEMORY_LIMIT_QUOTA: "4Gi"
  PYCLAW_SANDBOX_NAMESPACE_PVC_QUOTA: "5"
  PYCLAW_SANDBOX_NAMESPACE_STORAGE_QUOTA: "5Gi"
  PYCLAW_SANDBOX_DEFAULT_CPU_REQUEST: "50m"
  PYCLAW_SANDBOX_DEFAULT_MEMORY_REQUEST: "128Mi"
  PYCLAW_SANDBOX_DEFAULT_CPU_LIMIT: "500m"
  PYCLAW_SANDBOX_DEFAULT_MEMORY_LIMIT: "512Mi"
```

### 4.2 ResourceQuota

在 `SandboxOrchestratorService.ensureUserNamespace(...)` 后追加：

```text
ensureResourceQuota(namespace, userId)
```

目标资源：

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: pyclaw-user-quota
  namespace: pyclaw-user-{userId}
spec:
  hard:
    requests.cpu: "1000m"
    limits.cpu: "2000m"
    requests.memory: "2Gi"
    limits.memory: "4Gi"
    persistentvolumeclaims: "5"
    requests.storage: "5Gi"
```

Fabric8 相关类：

```text
io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
io.fabric8.kubernetes.api.model.Quantity
```

RBAC 需要补充：

```text
resourcequotas: get/list/create/patch/update
```

### 4.3 LimitRange

在 `ensureUserNamespace(...)` 后追加：

```text
ensureLimitRange(namespace, userId)
```

目标资源：

```yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: pyclaw-default-limits
  namespace: pyclaw-user-{userId}
spec:
  limits:
    - type: Container
      defaultRequest:
        cpu: "50m"
        memory: "128Mi"
      default:
        cpu: "500m"
        memory: "512Mi"
```

RBAC 需要补充：

```text
limitranges: get/list/create/patch/update
```

### 4.4 NetworkPolicy

目标策略：

```text
默认拒绝所有入站
允许同 namespace 内访问
允许 pyclaw namespace 中 Spring Backend / pyclaw-api 访问 sandbox-runner:8000
默认拒绝跨用户 namespace 访问
出站第一阶段可以允许 DNS + HTTPS；如果 runner 后续执行 npm/pip/git，需要更细粒度策略
```

在 `ensureUserNamespace(...)` 后追加：

```text
ensureNetworkPolicies(namespace, userId)
```

建议创建两个 NetworkPolicy。

第一个：默认拒绝入站。

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-ingress-by-default
spec:
  podSelector: {}
  policyTypes:
    - Ingress
```

第二个：允许控制面访问 runner。

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-control-plane-to-runner
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/component: sandbox-runner
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: pyclaw
      ports:
        - protocol: TCP
          port: 8000
```

如果 k3s 当前网络插件不执行 NetworkPolicy，需要明确记录：

```text
k3s 默认 flannel 不强制 NetworkPolicy。
如需 NetworkPolicy 真正生效，应切换到支持 NetworkPolicy 的 CNI，例如 Cilium 或 Calico。
```

因此实现时应做到：

```text
代码创建 NetworkPolicy
文档提示当前 CNI 是否强制执行
验收时标注“资源已创建”和“策略是否实际生效”是两件事
```

RBAC 需要补充：

```text
networkpolicies.networking.k8s.io: get/list/create/patch/update/delete
```

## 5. 用户 / Claw Secret 管理

### 5.1 目标

用户可以在前端维护自己的 Secret，例如：

```text
DeepSeek API Key
OpenAI API Key
Feishu App ID
Feishu App Secret
自定义环境变量
```

隔离要求：

```text
用户只能看到自己的 Secret 元信息
Secret 值只允许写入，不允许明文读出
数据库只保存密文或 Kubernetes Secret 引用
每个 Claw 只挂载自己选择的 Secret
Secret 同步到用户 namespace 内，不放在 pyclaw namespace 中共享给所有 runner
```

### 5.2 数据表

新增实体：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/secret/UserSecretEntity.java
```

字段建议：

```text
id: String UUID
ownerUserId: String
name: String
type: String
scope: String             // user | claw
clawId: String nullable
kubernetesSecretName: String
encryptedValuesJson: String
maskedValuesJson: String
enabled: boolean
createdAt: OffsetDateTime
updatedAt: OffsetDateTime
```

`encryptedValuesJson` 示例：

```json
{
  "DEEPSEEK_API_KEY": "enc:v1:...",
  "OPENAI_API_KEY": "enc:v1:..."
}
```

`maskedValuesJson` 示例：

```json
{
  "DEEPSEEK_API_KEY": "****abcd",
  "OPENAI_API_KEY": "****1234"
}
```

新增 Repository：

```text
UserSecretRepository
findByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId)
findByOwnerUserIdAndClawIdOrderByUpdatedAtDesc(String ownerUserId, String clawId)
```

### 5.3 Secret API

新增 Controller：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/secret/UserSecretController.java
```

接口：

```text
GET    /api/secrets
GET    /api/secrets?clawId={clawId}
POST   /api/secrets
PUT    /api/secrets/{id}
DELETE /api/secrets/{id}
POST   /api/secrets/{id}/sync
```

请求体：

```json
{
  "name": "DeepSeek",
  "type": "provider",
  "scope": "claw",
  "clawId": "fa951a96-3a18-4ce6-bafe-516bf2a5503a",
  "values": {
    "DEEPSEEK_API_KEY": "sk-..."
  }
}
```

响应体禁止返回明文：

```json
{
  "id": "...",
  "name": "DeepSeek",
  "type": "provider",
  "scope": "claw",
  "clawId": "...",
  "kubernetesSecretName": "claw-secret-fa951a96-...",
  "maskedValues": {
    "DEEPSEEK_API_KEY": "****abcd"
  },
  "enabled": true
}
```

权限：

```text
普通用户:
  只能 CRUD 自己 ownerUserId 的 Secret
  clawId 不为空时必须校验该 Claw 属于自己

管理员:
  可查看所有 Secret 元信息
  默认也不应读取明文
```

### 5.4 Kubernetes Secret 同步

在 `SandboxOrchestratorService` 增加：

```text
ensureClawSecret(userId, clawId, values)
deleteClawSecret(userId, clawId)
```

Secret 命名：

```text
claw-secret-{clawId}
```

创建在：

```text
namespace: pyclaw-user-{userId}
```

runner Deployment 增加：

```yaml
envFrom:
  - secretRef:
      name: claw-secret-{clawId}
      optional: true
```

安全要求：

```text
不要把用户 Secret 写入日志
不要在响应体返回明文
不要把 Secret 放入 ConfigMap
数据库必须使用 SecretEncryptionService 加密
生产必须设置 ENCRYPTION_SECRET
```

### 5.5 ProviderConfig 关系

现有 ProviderConfig 已有：

```text
ownerUserId
shared
secretRef
apiKey 加密/脱敏
```

补全方案：

```text
ProviderConfig 可以继续存 API Key 密文，供 Spring Backend / pyclaw-api 调模型。
UserSecret 用于向 runner Pod 注入环境变量。
二者不要强行合并。
```

如果用户创建 Claw 时选择 ProviderConfig：

```text
Claw -> Agent Role -> ProviderConfig
```

如果用户希望 runner 内也使用同一个 API Key：

```text
前端提供“同步到 Claw Secret”开关
后端将 ProviderConfig 的解密值写入 claw-secret-{clawId}
```

## 6. sandbox-runner 访问网关

### 6.1 问题

当前只能通过 `kubectl exec` 或集群内 Service 访问 runner：

```text
http://sandbox-runner-{clawId}.pyclaw-user-{userId}.svc.cluster.local:8000
```

前端不能直接访问该地址。需要 Spring Backend 提供代理接口。

### 6.2 新增 SandboxClient

新增：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/sandbox/SandboxClient.java
```

职责：

```text
根据 clawId 查询 Claw
校验 ownerUserId
计算 namespace 和 service DNS
通过 WebClient / RestClient 调用 runner
统一处理超时、404、连接失败
```

Service URL：

```text
http://sandbox-runner-{clawId}.pyclaw-user-{ownerUserId}.svc.cluster.local:8000
```

注意：

```text
resourceName 会截断到 63 字符，service 名必须复用 SandboxOrchestratorService 的同一套命名逻辑。
建议将命名逻辑提取为 SandboxNamingService，避免重复。
```

### 6.3 新增 SandboxController

新增：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/sandbox/SandboxController.java
```

接口：

```text
GET /api/claws/{clawId}/sandbox/healthz
GET /api/claws/{clawId}/sandbox/workspace
GET /api/claws/{clawId}/sandbox/files?path=.
GET /api/claws/{clawId}/sandbox/files/{filePath}
PUT /api/claws/{clawId}/sandbox/files/{filePath}
```

权限：

```text
所有接口必须先 requireOwned(clawId, authentication)
管理员可访问所有 Claw
普通用户只能访问自己的 Claw
```

超时：

```text
连接超时: 3s
读取超时: 10s
大文件读取限制: 1MiB 或配置项
```

### 6.4 前端使用方式

前端不要访问 runner Service。

前端访问：

```text
GET /api/claws/{clawId}/sandbox/workspace
GET /api/claws/{clawId}/sandbox/files?path=.
PUT /api/claws/{clawId}/sandbox/files/hello.txt
```

## 7. 数据访问隔离审计

### 7.1 审计范围

逐个检查以下模块，确认普通用户只能访问自己的数据：

```text
Claw
ClawAgent
ProviderConfig
AgentConfig
AgentToolPolicy
RouteBinding
ChannelConfig
Session
UsageRecord
ApiToken
AuditLog
UserSecret
```

### 7.2 规则

普通用户：

```text
只能 list/get/update/delete ownerUserId == currentUserId 的资源
对于通过 clawId 查询的资源，必须先校验 Claw ownerUserId
对于通过 agentId/providerId 查询的资源，必须校验 ownerUserId 或 shared=true
不应看到其他用户 route binding、channel config、session、usage
```

管理员：

```text
可以查看全量
敏感值仍脱敏
操作必须写 AuditLog
```

### 7.3 已知重点风险

#### AgentRuntimeConfigController

当前 runtime config 可能被 pyclaw-api / worker 调用。需要确认它返回 ProviderConfig 时不会越权。

要求：

```text
如果请求是用户上下文:
  只能返回 ownerUserId == currentUserId 或 shared=true 的 Provider

如果请求是内部服务 token:
  必须通过 clawId / routeBindingId 推导 ownerUserId
  不能简单取“最新启用 provider”
```

#### RouteBinding

当前 RouteBinding 带 `clawId`，但要补：

```text
普通用户不能手动绑定到别人的 Claw
普通用户不能看到别人的 RouteBinding
Claw 自动管理的 RouteBinding 应带 managedBy=claw 或 comment 约定
删除 Claw 时必须删除其 RouteBinding
```

建议给 `RouteBindingEntity` 增加：

```text
ownerUserId
managedBy     // manual | claw
```

这样查询和审计更直接。

#### Session

当前 Session 使用 Redis：

```text
sessions:user:{userId}
sessions:claw:{clawId}
session:{id}:meta
```

要求：

```text
GET /api/sessions?clawId=xxx 必须确认 clawId 属于当前用户
GET /api/sessions/{id} 必须确认 meta.userId == currentUserId 或管理员
deleteByClaw 只能由 Claw 删除流程调用，且应先确认 owner
```

### 7.4 测试要求

新增测试：

```text
spring-backend/src/test/java/com/anxin/pyclaw/backend/security/TenantIsolationTest.java
```

至少覆盖：

```text
用户 A 不能读取用户 B 的 Claw
用户 A 不能更新用户 B 的 ProviderConfig
用户 A 不能读取用户 B 的 Session
用户 A 不能通过 clawId 访问用户 B 的 sandbox proxy
管理员可以读取两边元信息，但看不到 Secret 明文
```

## 8. runner 安全加固

### 8.1 Pod Security Context

在 runner Deployment 增加：

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile:
    type: RuntimeDefault
```

container securityContext：

```yaml
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: false
  capabilities:
    drop:
      - ALL
```

说明：

```text
readOnlyRootFilesystem 暂时可以 false，因为 /workspace 是 PVC，但 Python/uvicorn 可能仍写临时文件。
后续可挂载 emptyDir 到 /tmp 后改 true。
```

### 8.2 ServiceAccount

当前 runner 使用默认 ServiceAccount 或配置项。

补全：

```text
在每个用户 namespace 中创建 sandbox-runner ServiceAccount
automountServiceAccountToken: false
runner Pod 使用该 ServiceAccount
不要给 runner 任何 Kubernetes API 权限
```

Deployment template：

```yaml
automountServiceAccountToken: false
serviceAccountName: sandbox-runner
```

RBAC：

```text
不创建 RoleBinding
```

### 8.3 文件 API 安全

当前 `workspace_path()` 已防止路径逃逸，这是正确的。

继续补：

```text
限制单文件读取大小
限制单文件写入大小
禁止写入 .ssh/id_rsa 等敏感模式可选
返回目录列表时不要跟随 symlink 到 workspace 外
```

建议：

```text
MAX_FILE_BYTES=1048576
MAX_LIST_ITEMS=1000
```

### 8.4 命令执行安全

如果后续给 runner 增加命令执行接口，必须先实现策略：

```text
POST /v1/commands
```

请求：

```json
{
  "command": ["python", "-m", "pytest"],
  "cwd": ".",
  "timeoutSeconds": 30
}
```

安全规则：

```text
只能使用 list command，不允许 shell=true
通过 asyncio.create_subprocess_exec 执行
cwd 必须在 /workspace 内
timeout 必须有限制
stdout/stderr 必须截断
禁止直接暴露任意 shell 给普通用户
```

## 9. 删除、禁用、回收策略

### 9.1 Claw 删除

当前删除 Claw 会删除：

```text
Service
Deployment
PVC
```

建议改为可配置：

```yaml
pyclaw:
  sandbox:
    delete-pvc-on-claw-delete: false
```

默认建议：

```text
个人 demo: true，节省资源
生产 SaaS: false，防止误删数据
```

如果保留 PVC：

```text
删除 Deployment / Service
保留 PVC
给 PVC 打 label: pyclaw.io/deleted=true
前端显示“已归档 workspace”
```

### 9.2 Claw 禁用

当 Claw status 改为 inactive：

```text
可以将 Deployment replicas 改为 0
保留 Service / PVC
```

当重新 active：

```text
Deployment replicas 恢复 1
```

### 9.3 用户禁用

用户 disabled：

```text
该用户 namespace 内所有 sandbox-runner Deployment replicas=0
保留 PVC / Secret
禁止登录
禁止飞书路由到该用户 Claw
```

### 9.4 用户删除

建议先不做物理删除，做软删除：

```text
User.status = deleted
namespace 保留
Deployment replicas=0
```

管理员确认后再提供：

```text
DELETE /api/admin/users/{id}/namespace
```

该接口才真正删除 namespace。

## 10. 前端补齐

### 10.1 Claw 详情页

修改：

```text
pyclaw-web/src/views/ClawDetailPage.vue
```

新增区块：

```text
Sandbox 状态
  namespace
  deployment
  service
  pvc
  pod phase
  runner health
  workspace path
```

调用：

```text
GET /api/claws/{clawId}/sandbox/healthz
GET /api/claws/{clawId}/sandbox/workspace
```

### 10.2 Workspace 文件页

新增：

```text
pyclaw-web/src/views/WorkspaceFilesPage.vue
```

能力：

```text
目录列表
查看文件
编辑并保存文本文件
显示文件大小
路径面包屑
错误提示：runner 未启动 / 无权限 / 文件过大
```

路由：

```text
/workspace/claws/:id/files
```

### 10.3 Secret 管理页

新增：

```text
pyclaw-web/src/views/SecretPage.vue
```

能力：

```text
列表展示 Secret 元信息
新增 Secret
更新 Secret
删除 Secret
只显示脱敏值
支持选择 scope=user 或 scope=claw
scope=claw 时选择 Claw
```

侧边栏增加：

```text
Secret 管理
```

### 10.4 管理员 Pod 状态页

现有：

```text
pyclaw-web/src/views/PodStatusPage.vue
```

补充：

```text
按 namespace 过滤
显示 pyclaw-user-* namespace
显示 quota 使用情况
显示 runner Pod 重启次数
```

## 11. Helm 与 ECS 私有 values

### 11.1 模板内应包含

以下配置应进入模板默认值：

```text
PYCLAW_SANDBOX_NETWORK_POLICY_ENABLED
PYCLAW_SANDBOX_RESOURCE_QUOTA_ENABLED
PYCLAW_SANDBOX_LIMIT_RANGE_ENABLED
PYCLAW_SANDBOX_*_QUOTA
PYCLAW_SANDBOX_DEFAULT_*_REQUEST
PYCLAW_SANDBOX_DEFAULT_*_LIMIT
```

但默认可以关闭：

```yaml
PYCLAW_SANDBOX_ENABLED: "false"
```

### 11.2 ECS 私有 values 中应设置

```yaml
env:
  PYCLAW_SANDBOX_ENABLED: "true"
  PYCLAW_SANDBOX_RUNNER_IMAGE: "crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/sandbox-runner:<tag>"
  PYCLAW_SANDBOX_IMAGE_PULL_SECRET_NAME: "aliyun-acr-pull-secret"
  PYCLAW_SANDBOX_IMAGE_PULL_SECRET_SOURCE_NAMESPACE: "pyclaw"
  PYCLAW_SANDBOX_NETWORK_POLICY_ENABLED: "true"
  PYCLAW_SANDBOX_RESOURCE_QUOTA_ENABLED: "true"
  PYCLAW_SANDBOX_LIMIT_RANGE_ENABLED: "true"
  ENCRYPTION_SECRET: "<production-secret>"
```

注意：

```text
ENCRYPTION_SECRET 必须进入 Secret，不要进入 ConfigMap。
不要提交真实 Secret 到 GitHub。
```

## 12. RBAC 补齐

修改：

```text
spring-backend/helm/templates/serviceaccount-rbac.yaml
```

补充权限：

```yaml
- apiGroups: [""]
  resources:
    - resourcequotas
    - limitranges
    - serviceaccounts
    - secrets
  verbs: ["get", "list", "create", "patch", "update", "delete"]

- apiGroups: ["networking.k8s.io"]
  resources:
    - networkpolicies
  verbs: ["get", "list", "create", "patch", "update", "delete"]
```

权限收敛建议：

```text
短期可以用 ClusterRole，因为 Spring Backend 需要创建 namespace。
长期可以拆成：
  ClusterRole: 只允许 namespace 管理
  Role/RoleBinding: 在用户 namespace 内管理具体资源
```

## 13. 自动化验收

### 13.1 后端单元 / 集成测试

新增测试：

```text
SandboxOrchestratorServiceTest
TenantIsolationTest
UserSecretServiceTest
SandboxControllerTest
```

重点：

```text
命名规则稳定
普通用户不能访问其他用户 Claw
Secret 响应不返回明文
删除 Claw 时按配置保留/删除 PVC
```

### 13.2 ECS 验收命令

创建测试用户和 Claw 后：

```bash
USER_ID=<userId>
CLAW_ID=<clawId>
NS=pyclaw-user-$USER_ID

sudo /usr/local/bin/k3s kubectl get ns $NS
sudo /usr/local/bin/k3s kubectl -n $NS get resourcequota,limitrange,networkpolicy
sudo /usr/local/bin/k3s kubectl -n $NS get secret,deploy,svc,pvc,pod
sudo /usr/local/bin/k3s kubectl -n $NS exec deploy/sandbox-runner-$CLAW_ID -- \
  python -c 'import urllib.request; print(urllib.request.urlopen("http://127.0.0.1:8000/healthz").read().decode())'
```

验证 workspace 写入：

```bash
sudo /usr/local/bin/k3s kubectl -n $NS exec deploy/sandbox-runner-$CLAW_ID -- \
  python -c 'import urllib.request,json; req=urllib.request.Request("http://127.0.0.1:8000/v1/workspace/files/hello.txt", data=json.dumps({"content":"hello"}).encode(), headers={"Content-Type":"application/json"}, method="PUT"); print(urllib.request.urlopen(req).read().decode())'
```

验证 Secret：

```bash
sudo /usr/local/bin/k3s kubectl -n $NS get secret claw-secret-$CLAW_ID
```

验证外部 API：

```bash
curl -i https://api.anxin-hitsz.com/healthz
```

### 13.3 前端验收

```text
注册新用户
登录
创建 Claw
进入 Claw 详情
看到 Sandbox 状态为 Running
进入 Workspace 文件页
创建 hello.txt
刷新后仍存在
创建 Secret
确认页面只显示脱敏值
```

## 14. 性能与资源建议

当前 2c4g 已经验证会出现：

```text
K3s API timeout
TLS handshake timeout
Spring 启动慢
Pod 探针抖动
```

建议资源：

```text
最低 demo: 2c8g
更稳 demo: 4c8g
多用户演示: 4c16g
```

runner 默认资源建议：

```yaml
requests:
  cpu: 50m
  memory: 128Mi
limits:
  cpu: 500m
  memory: 512Mi
```

2c8g 建议限制：

```text
同时 Running 的 sandbox-runner 不超过 3-5 个
inactive Claw 将 replicas 调为 0
不要在 ECS 上同时 Docker build 和 Helm upgrade
```

## 15. Definition of Done

完成标准：

```text
1. 新用户自动创建 namespace、ResourceQuota、LimitRange、NetworkPolicy。
2. 新 Claw 自动创建 Secret、Deployment、Service、PVC、Pod。
3. Spring Backend 可通过 /api/claws/{id}/sandbox/* 代理访问 runner。
4. 前端能查看 sandbox 状态和 workspace 文件。
5. 用户 Secret 可创建、更新、删除、同步到对应 namespace。
6. 普通用户不能访问其他用户的 Claw、Secret、Session、Provider、runner。
7. 管理员可查看元信息，但敏感值脱敏。
8. 删除/禁用 Claw 的资源处理符合配置。
9. ECS 上能通过 kubectl 验证资源隔离。
10. 关键逻辑有测试覆盖。
```

## 16. 推荐 commit 拆分

建议 Claude Code 按以下 commit 粒度提交：

```text
feat: 为用户沙箱补充 NetworkPolicy 与资源配额
feat: 增加用户 Secret 管理与 Claw Secret 同步
feat: 增加 sandbox runner 后端代理接口
fix: 强化多租户数据访问隔离校验
feat: 前端补充 Secret 与 workspace 管理页面
test: 增加租户隔离与沙箱验收测试
docs: 补充租户隔离生产级实施文档
```

## 17. 注意事项

```text
不要提交 ECS 私有 values 中的真实密码、API Key、ACR 凭证。
不要把 Secret 明文写日志。
不要让前端直连 runner Service。
不要让普通用户传入 namespace。
namespace 必须由 ownerUserId 推导。
service 名、deployment 名、pvc 名必须由 clawId 推导。
所有创建 Kubernetes 资源的方法必须幂等。
所有跨资源访问必须先校验 ownerUserId。
```
