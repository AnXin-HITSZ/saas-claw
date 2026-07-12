# Spring Boot 后端网站与鉴权一次性实现记录

本文记录根据 `docs/000/0001/spring-backend-auth-technical-design.md` 完成的一次性落地实现。实现目标不是先做临时 token 再分阶段迁移，而是直接形成：

```text
Spring Boot 对公网承担认证、授权、配置管理、审计、用量统计和 Agent 代理。
pyclaw-api 保留内部 Bearer Token，作为 Spring -> pyclaw 的服务间保护。
K8s 中长期只暴露 Spring Boot，pyclaw 保持 ClusterIP。
```

## 1. 本次新增/修改范围

### 1.1 pyclaw-api 内部鉴权

修改：

```text
openclaw/api.py
tests/test_api.py
helm/pyclaw/values.yaml
```

新增环境变量：

```text
PYCLAW_API_TOKEN
```

行为：

```text
GET /healthz 不需要鉴权。
POST /v1/agent/run 在 PYCLAW_API_TOKEN 为空时保持原有行为，便于本地开发。
POST /v1/agent/run 在 PYCLAW_API_TOKEN 非空时必须带 Authorization: Bearer <token>。
```

失败响应：

```text
401 Missing API token
401 Invalid API token
```

设计原因：

```text
即使未来 pyclaw 不直接暴露公网，也保留内部服务 token。
如果 Ingress 或 Service 被误暴露，未携带内部 token 的请求仍无法调用模型。
```

### 1.2 Spring Boot 后端

新增目录：

```text
spring-backend/
```

核心能力：

```text
1. Spring Boot 3.x 应用。
2. Spring Security 无状态鉴权。
3. JWT 登录。
4. API Token 创建、识别、撤销。
5. 用户管理。
6. Provider 配置管理。
7. 微信/飞书 Channel 配置管理。
8. Agent 代理调用 pyclaw。
9. 审计日志。
10. 用量记录。
11. 静态管理页入口。
12. Dockerfile。
13. Helm Chart。
```

## 2. 目录结构

```text
spring-backend/
  pom.xml
  Dockerfile
  helm/
    Chart.yaml
    values.yaml
    templates/
  src/main/java/com/anxin/pyclaw/backend/
    PyclawBackendApplication.java
    config/
    auth/
    user/
    token/
    agent/
    pyclaw/
    provider/
    channel/
    audit/
    usage/
    common/
  src/main/resources/
    application.yml
    static/index.html
```

## 3. Spring 启动配置

`application.yml` 支持环境变量覆盖：

```text
SERVER_PORT
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
SPRING_JPA_DDL_AUTO
JWT_SIGNING_SECRET
JWT_TTL_SECONDS
BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_PASSWORD
PYCLAW_BASE_URL
PYCLAW_API_TOKEN
PYCLAW_CONNECT_TIMEOUT_SECONDS
PYCLAW_READ_TIMEOUT_SECONDS
```

默认数据库为 H2 文件模式：

```text
jdbc:h2:file:./data/pyclaw-backend;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
```

这样本地无需先部署 PostgreSQL 即可运行。生产环境可切换为 PostgreSQL/RDS。

## 4. 用户与权限

启动时 `BootstrapDataInitializer` 会检查管理员用户是否存在。若不存在，会用以下配置创建：

```text
BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_PASSWORD
```

默认管理员权限：

```text
user:manage
provider:manage
channel:manage
agent:run
audit:read
token:manage_self
```

用户实体 `UserEntity` 保存：

```text
username
passwordHash
displayName
status
authorities
createdAt
updatedAt
```

当前实现用逗号分隔权限字符串，后续可以升级为独立 role/permission 表。

## 5. JWT 登录

接口：

```http
POST /api/auth/login
```

请求：

```json
{
  "username": "admin",
  "password": "ChangeMe123!"
}
```

响应：

```json
{
  "accessToken": "...",
  "expiresIn": 3600
}
```

JWT 实现：

```text
HS256
Java 标准 Mac/HmacSHA256
不引入额外 JWT 第三方依赖
```

JWT payload 包含：

```text
sub
username
authorities
iat
exp
```

## 6. API Token

接口：

```http
POST /api/tokens
GET /api/tokens
DELETE /api/tokens/{id}
```

Token 格式：

```text
pcat_<random>
```

数据库只保存：

```text
SHA-256(token)
```

不会保存 token 明文。创建接口只返回一次明文 token。

Spring Security filter 会识别：

```text
Authorization: Bearer pcat_xxx
```

并把 token scopes 注入为 Spring Security authorities。

## 7. Spring Security

`SecurityConfig` 配置：

```text
CSRF disabled
SessionCreationPolicy.STATELESS
/healthz 放行
/actuator/health 放行
/api/auth/login 放行
静态首页放行
其他接口需要认证
```

Bearer 支持两种：

```text
普通 JWT
pcat_ API Token
```

方法级鉴权使用：

```java
@PreAuthorize("hasAuthority('agent:run')")
```

## 8. Spring 调用 pyclaw

`PyclawClient` 使用 Java `HttpClient` 调用：

```text
POST ${PYCLAW_BASE_URL}/v1/agent/run
Authorization: Bearer ${PYCLAW_API_TOKEN}
```

默认：

```text
PYCLAW_BASE_URL=http://localhost:8000
```

K8s 内应设置为：

```text
PYCLAW_BASE_URL=http://pyclaw.pyclaw.svc.cluster.local:8000
```

请求映射：

```text
Spring /api/agent/run
  sessionId -> pyclaw session_id
  toolProfile -> pyclaw tool_profile
```

## 9. Agent 代理接口

接口：

```http
POST /api/agent/run
Authorization: Bearer <jwt or api-token>
```

请求：

```json
{
  "prompt": "你好",
  "provider": "openai",
  "sessionId": "spring-web-demo",
  "toolProfile": "minimal"
}
```

Spring 做：

```text
1. 校验 agent:run 权限。
2. 调用 pyclaw。
3. 记录 audit log。
4. 记录 usage record。
5. 返回 pyclaw 响应和 latencyMs。
```

## 10. Provider 配置管理

接口：

```http
GET /api/providers
POST /api/providers
PUT /api/providers/{id}
DELETE /api/providers/{id}
```

权限：

```text
provider:manage
```

当前 Provider 配置用于管理后台展示和后续切换模型：

```text
name
providerType
baseUrl
model
apiMode
secretRef
enabled
```

注意：API Key 不在这里明文保存，使用 `secretRef` 指向 K8s Secret 或外部密钥系统。

## 11. Channel 配置管理

通用接口：

```http
GET /api/channels
POST /api/channels
PUT /api/channels/{id}
DELETE /api/channels/{id}
```

快捷接口：

```http
POST /api/channels/wechat/config
POST /api/channels/feishu/config
```

配置以 JSON 存储：

```text
channelType = wechat / feishu
configJson = 平台配置
secretRef = 敏感信息引用
```

这与当前只保留微信和飞书的 Channel 实现范围一致。

## 12. 审计与用量

审计表记录：

```text
actorType
actorId
action
resourceType
resourceId
success
errorMessage
createdAt
```

用量表记录：

```text
userId
sessionId
provider
model
promptTokens
completionTokens
totalTokens
success
latencyMs
createdAt
```

`/api/agent/run` 每次调用都会写入审计和用量。

## 13. Docker

`spring-backend/Dockerfile` 使用两阶段构建：

```text
maven:3.9.9-eclipse-temurin-17 构建 jar
eclipse-temurin:17-jre 运行 jar
```

运行用户：

```text
spring
uid 10001
```

## 14. Helm

新增：

```text
spring-backend/helm/
```

资源：

```text
Deployment
Service
Ingress
ConfigMap
Secret
PVC
```

关键配置：

```text
PYCLAW_BASE_URL=http://pyclaw.pyclaw.svc.cluster.local:8000
PYCLAW_API_TOKEN=<internal token>
JWT_SIGNING_SECRET=<jwt signing secret>
BOOTSTRAP_ADMIN_PASSWORD=<admin password>
```

长期部署时：

```text
api.anxin-hitsz.com -> spring-backend Ingress
pyclaw Ingress disabled
pyclaw Service remains ClusterIP
```

## 15. 本地运行方式

先启动 pyclaw-api：

```bash
PYCLAW_API_TOKEN=internal-token uvicorn openclaw.api:app --host 0.0.0.0 --port 8000
```

再启动 Spring：

```bash
cd spring-backend
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-DPYCLAW_API_TOKEN=internal-token"
```

登录：

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"ChangeMe123!"}'
```

调用 Agent：

```bash
curl -X POST http://localhost:8080/api/agent/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt>" \
  -d '{"prompt":"你好","provider":"openai","sessionId":"spring-demo","toolProfile":"minimal"}'
```

## 16. K3s 部署建议

1. 给 pyclaw-provider-secret 增加：

```text
PYCLAW_API_TOKEN=<internal-token>
```

2. 关闭 pyclaw 直连 Ingress：

```yaml
ingress:
  enabled: false
```

3. 部署 Spring：

```bash
helm upgrade --install spring-backend ./spring-backend/helm \
  -n pyclaw \
  -f spring-values.yaml
```

4. Spring Ingress 接管：

```text
api.anxin-hitsz.com
```

## 17. 验证清单

```text
GET /healthz -> 200
POST /api/auth/login -> 200
GET /api/auth/me with JWT -> 200
POST /api/tokens with JWT -> 200
POST /api/agent/run without token -> 403/401
POST /api/agent/run with JWT -> 200
POST /api/agent/run with API token -> 200
GET /api/audit-logs -> contains agent:run
GET /api/usage-records -> contains usage
pyclaw /v1/agent/run without internal token -> 401 when PYCLAW_API_TOKEN is set
```

## 18. 已执行验证

本地已执行：

```powershell
cd D:\projects\personal\pyclaw\spring-backend
mvn -s .mvn\settings.xml -gs .mvn\settings.xml -q test
```

结果：

```text
通过。
```

覆盖内容：

```text
1. Spring Boot 应用上下文可启动。
2. /healthz 不需要登录即可访问。
3. /api/auth/me 未登录时不可访问。
4. 默认 bootstrap admin 可以登录。
5. 登录后携带 JWT 可以访问 /api/auth/me。
```

本地 Maven 环境说明：

```text
当前机器全局 Maven settings.xml 将 localRepository 指向 Maven 安装目录，普通进程写入会触发 AccessDeniedException。
因此 spring-backend/.mvn/settings.xml 明确把本项目 Maven 仓库放到 spring-backend/maven-repository。
spring-backend/maven-repository 已加入 .gitignore，不提交依赖缓存。
```

Python 侧已执行：

```powershell
cd D:\projects\personal\pyclaw
py -m compileall openclaw tests
```

结果：

```text
通过。
```

格式检查已执行：

```powershell
git diff --check
```

结果：

```text
通过。仅有 Git CRLF 换行提示，不是空白错误。
```

## 19. 当前限制

本次实现已经形成完整骨架，但仍有可 review 后继续加强的点：

```text
1. JWT secret 默认值仅用于本地，生产必须覆盖。
2. H2 默认仅用于开发，生产建议 PostgreSQL/RDS。
3. API Token 当前使用 SHA-256 hash，生产可增加 pepper 或 KMS。
4. Provider/Channel secretRef 还未对接 K8s Secret API。
5. 静态管理页只是最小调试页，不是完整 UI。
6. 还未实现 HTTPS ClusterIssuer。
7. 还未实现微信/飞书真实 webhook controller。
8. 还未实现请求限流。
```

## 20. 结论

本次实现后，pyclaw 的公网安全模型可以从：

```text
公网 -> pyclaw-api -> DeepSeek
```

演进为：

```text
公网 -> Spring Boot 鉴权/审计/管理 -> pyclaw-api 内部 token -> DeepSeek
```

Spring Boot 成为控制面和安全入口，pyclaw 保持 Agent Runtime 的职责边界。
