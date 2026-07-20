# Claw SaaS 重构设计文档

**日期：** 2026-07-19（修订：2026-07-20）
**状态：** 已确认

---

## 1. 背景与动机

### 1.1 当前状态

项目 `pyclaw` 是一个 Claw SaaS 平台，当前为混合状态：

| 组件 | 当前状态 | 问题 |
|------|----------|------|
| `spring-backend/` | 单体 Spring Boot 应用，~25 个 package 混在一起 | 耦合严重，改一处牵全身 |
| `openclaw/` | 单体 FastAPI，`api.py` 37KB | 路由/编排/调度全揉在一起 |
| `sandbox-runner/` | 独立 runner | 相对干净 |
| `pyclaw-web/` | 前端应用 | 相对干净 |

### 1.2 目标

重构为 `claw-saas` 产品级 monorepo，包含 8 个 Spring Boot 微服务、2 个 FastAPI 服务、前端和部署配置。项目名从 `com.anxin.pyclaw` 改为 `com.clawsaas`。

### 1.3 约束

- 可以接受短期停工（不需要边跑边拆）
- 重构后保持 K8s 管理方式
- 引入阿里云 OSS 管理 Agent/Skill 制品包
- 移除 Channel 调度（不需要）
- 删除 token/（当前以 Web 场景为主）

---

## 2. 总体策略：骨架先行

### 2.1 分为两大阶段

```
阶段一：骨架搭建（同步进行）
  Java 后端: 建 Maven 多模块父工程 + 8 个服务空壳，全部可编译通过
  Python Runtime: 建 2 个 FastAPI 服务空壳，全部可启动验证

阶段二：业务迁移（按服务逐个进行）
  逐服务从旧代码迁移业务逻辑，拆一个验证一个
```

### 2.2 目标目录结构

```
claw-saas/
  CLAUDE.md
  README.md

  docs/
    architecture/
      ARCHITECTURE.md
      backend-coding-standards.md
      runtime-coding-standards.md
      database-schema.md
    plans/
      migration-plan.md
    superpowers/
      specs/

  backend/
    pom.xml                         # 父 POM（多模块）
    gateway/
    backend-for-frontend/
    agent-marketplace-service/
    skill-marketplace-service/
    claw-service/                   # 原 conversation-service 改名
    runtime-service/
    billing-service/

  frontend/
    package.json
    src/
    public/

  runtime/
    pyclaw-runtime-api/
      pyproject.toml
      app/
        main.py
        api/
        runtime/
        schemas/
        config/
    claw-runner/
      pyproject.toml
      app/
        main.py
        api/
        workspace/
        tools/
        sandbox/
        schemas/
        config/

  deploy/
    docker-compose.yml
    env/
    k8s/

  scripts/
```

---

## 3. Java 后端骨架（8 个服务）

### 3.1 父 POM

```xml
<groupId>com.clawsaas</groupId>
<artifactId>claw-saas-backend</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<modules>
  <module>gateway</module>
  <module>backend-for-frontend</module>
  <module>agent-marketplace-service</module>
  <module>skill-marketplace-service</module>
  <module>claw-service</module>
  <module>runtime-service</module>
  <module>billing-service</module>
</modules>
```

统一管理 Spring Boot 版本、Java 17、公共依赖版本号。

### 3.2 服务清单

| 服务 | 端口 | 关键依赖 | 职责 |
|------|------|----------|------|
| gateway | 8080 | Spring Cloud Gateway, WebFlux | 统一入口、认证、路由、用户管理 |
| backend-for-frontend | 8081 | Web | 页面聚合 |
| claw-service | 8082 | Web, JPA, Flyway | Claw 实例、对话消息、Agent 实例安装/配置（纯 DB CRUD） |
| runtime-service | 8083 | Web, JPA, HTTP Client, OSS SDK | 业务编排、审批、调 FastAPI、Provider、Secret、OSS 文件操作 |
| agent-marketplace-service | 8084 | Web, JPA, Flyway, OSS SDK | Agent 发布、版本、OSS 制品上传下载 |
| billing-service | 8085 | Web, JPA, Flyway | 用量、计费 |
| skill-marketplace-service | 8086 | Web, JPA, Flyway, OSS SDK | Skill 发布、搜索、独立安装、OSS 制品 |

### 3.3 服务职责边界

```
claw-service（账本）
  管：DB CRUD（Claw、Agent 实例、消息、工具策略、安装记录）
  不管：文件下载、沙箱操作、OSS

runtime-service（大脑）
  管：Agent 运行编排、工具审批、Provider/Secret、按需从 OSS 下载并推送到沙箱
  不管：Agent 安装元数据（安装记录通过调 claw-service 写入）
```

### 3.4 空壳结构

每个服务遵循 `backend-coding-standards.md` 的标准包结构：

```
<service>/
├── pom.xml
└── src/main/java/com/clawsaas/<service>/
    ├── <Service>Application.java
    ├── config/          # 阶段一放基础配置
    └── exception/       # 阶段一放 GlobalExceptionHandler
    # 以下目录阶段二按需创建：
    # controller/ service/ service/impl/ repository/ domain/ dto/ client/
```

阶段一仅确保 `@SpringBootApplication` + `application.yml`（服务名 + 端口）可编译通过。

### 3.5 关键设计决策

- **gateway 使用 WebFlux**，不依赖 spring-boot-starter-web
- **不建 common 模块**。异常处理、加密等工具类各服务自持
- **各服务独立数据库连接**，独立 Flyway 迁移目录
- **audit 各服务自持**。gateway 做 HTTP 访问审计，领域服务做操作审计
- **OSS 文件操作只在 runtime-service**（执行时按需下载+推送）。claw-service 不碰 OSS

---

## 4. 服务间调用关系

### 4.1 用户可见的两条主链路

```
链路 1：CRUD/管理
  Frontend → gateway → BFF → claw-service → DB
  创建 Claw、安装 Agent（仅 DB）、写消息、配工具策略、管理审批

链路 2：执行
  Frontend → gateway → BFF → runtime-service → pyclaw-runtime-api → claw-runner
  运行 Agent、执行工具、恢复审批
```

### 4.2 服务间内部协作

```
用户主动安装 Agent：
  claw-service ──pushFiles(clawId, agentPkg, skills)──→ runtime-service
    ① claw-service 写 DB（状态=PUSHING）
    ② 调 runtime.pushFiles() —— 纯文件操作，从 OSS 下载 → 推 claw-runner
    ③ push 返回 → claw 更新 DB（状态=INSTALLED）
    runtime 在此流程中不回调 claw-service ✅

运行时自动安装：
  runtime-service ──recordInstall(clawId, agentId, skillIds)──→ claw-service
    ① Agent 运行中需要 Skill-Y，runtime 从 OSS 下载 → 推 claw-runner
    ② 调 claw.recordInstall() —— 仅写 DB（来源=runtime_auto）
    ③ record 返回 → 继续执行
    claw 在此流程中不回调 runtime ✅
```

**无循环依赖风险：** 两条 flow 独立触发。pushFiles() 和 recordInstall() 都是单向调用，调完即返回，不会触发对方再回调。

### 4.3 OSS 下载策略：延迟加载（Lazy Loading）

- **用户主动安装**：claw-service 记录 DB → 调 runtime.pushFiles() → 立即下载+推送
- **运行时发现需要**：runtime-service 检查 claw-runner 沙箱 → 没有则从 OSS 下载 → 推送 → 调 claw.recordInstall()
- **首次执行**：Agent 首次 run 时，runtime 从 claw-service 查已安装列表，缺失的从 OSS 拉取

---

## 5. Python Runtime 骨架（2 个服务）

### 5.1 服务清单

| 服务 | 端口 | 职责 |
|------|------|------|
| pyclaw-runtime-api | 8090 | FastAPI 控制面，执行引擎 |
| claw-runner | 8091 | FastAPI 数据面，隔离执行 |

### 5.2 pyclaw-runtime-api（控制面/执行引擎）

```
runtime/pyclaw-runtime-api/
├── pyproject.toml
└── app/
    ├── main.py              # 创建 FastAPI app，include routers
    ├── api/
    │   ├── __init__.py
    │   ├── health.py
    │   ├── runs.py
    │   ├── approvals.py
    │   └── tools.py
    ├── runtime/              # 执行引擎（非业务编排）
    │   ├── __init__.py
    │   ├── run_executor.py
    │   ├── approval_handler.py
    │   ├── runner_client.py
    │   ├── session_factory.py
    │   ├── provider_factory.py
    │   └── policy_factory.py
    ├── schemas/
    │   ├── __init__.py
    │   ├── health.py
    │   ├── runs.py
    │   ├── approvals.py
    │   └── tools.py
    └── config/
        ├── __init__.py
        ├── settings.py
        └── logging.py
```

- 去掉 `scheduler/` 目录（调度由 Spring 侧负责，根据数据库记录路由）
- 执行引擎不做业务编排，只负责"收到 run 请求 → 调 LLM → 工具调用 → 等待审批 → 继续 → 返回结果"

### 5.3 claw-runner（数据面/隔离执行）

```
runtime/claw-runner/
├── pyproject.toml
└── app/
    ├── main.py
    ├── api/
    │   ├── __init__.py
    │   ├── health.py
    │   ├── workspace.py
    │   ├── tools.py
    │   └── commands.py
    ├── workspace/
    │   ├── __init__.py
    │   ├── path_guard.py
    │   └── file_service.py
    ├── tools/
    │   ├── __init__.py
    │   ├── registry.py
    │   ├── executor.py
    │   └── policy.py
    ├── sandbox/
    │   ├── __init__.py
    │   ├── environment.py
    │   ├── limits.py
    │   └── command_runner.py
    ├── schemas/
    │   ├── __init__.py
    │   ├── health.py
    │   ├── workspace.py
    │   ├── tools.py
    │   └── commands.py
    └── config/
        ├── __init__.py
        ├── settings.py
        └── logging.py
```

- `workspace/path_guard.py` 是安全隔离的底线，阶段二迁移时最高优先级

### 5.4 执行引擎调用关系

```
Spring runtime-service（业务编排）
  "这场对话要用哪个 Agent，审批流程怎么走"
        │
        ▼  传入已选好的 Agent + Claw + Runner 地址
Python pyclaw-runtime-api（执行引擎）
  "收到 run 请求 → 调 LLM → 工具调用 → 等待审批 → 继续 → 返回结果"
        │
        ▼
Python claw-runner（隔离执行）
  "在这个 Claw 的 workspace 里执行具体工具/命令"
```

---

## 6. OSS 制品存储

### 6.1 OSS 存储结构

```
阿里云 OSS Bucket: claw-saas-artifacts/
├── agents/
│   └── <agent-package-id>/
│       ├── v1.0.0/
│       │   ├── agent.tar.gz
│       │   └── agent.tar.gz.sha256
│       └── v2.0.0/
│           └── ...
├── skills/
│   └── <skill-id>/
│       ├── v1.0.0/
│       │   ├── skill.tar.gz
│       │   └── skill.tar.gz.sha256
│       └── v1.1.0/
│           └── ...
```

### 6.2 Agent 包结构

```
agent.tar.gz
├── manifest.json            # name, version, author, description
├── prompt/
│   └── system.md            # System Prompt 模板
├── config.json              # 默认配置、参数 schema
└── skill-refs.json          # 依赖的 Skill 列表
    [
      { "skillId": "xxx", "version": ">=1.0.0" },
      { "skillId": "yyy", "version": "^2.1.0" }
    ]
```

### 6.3 Skill 包结构

```
skill.tar.gz
├── manifest.json            # name, version, author, category, tags
├── README.md                # 使用说明
├── skill.py / skill.js      # Skill 执行逻辑
├── requirements.json        # Skill 依赖（如有）
└── examples/
```

### 6.4 OSS 客户端归属

| 服务 | 用途 |
|------|------|
| agent-marketplace-service | 上传/下载 Agent 制品包 |
| skill-marketplace-service | 上传/下载 Skill 制品包 |
| runtime-service | 执行时按需下载 Agent/Skill 包并推送到 claw-runner |

claw-service **不需要** OSS 客户端。

---

## 7. 数据库设计（阶段二预览）

### 7.1 skill-marketplace-service 数据库

```
skills                            Skill 发布信息
├── id, name, display_name
├── version, status (published/draft/revoked)
├── author_user_id, category, tags
├── oss_path                        OSS 上的 skill.tar.gz 路径
├── checksum_sha256
├── install_count, created_at, updated_at

skill_dependencies                 Skill 依赖
├── skill_id → parent
├── depends_on_skill_id → child
└── version_constraint              ">=1.0.0"
```

### 7.2 claw-service 数据库（扩展）

```
claw_agent_skills                  关联表：Claw 里的某个 Agent 安装了哪些 Skill
├── claw_agent_id                  关联 claw_agents
├── skill_id                       关联 skills（来自 skill-marketplace）
├── source                         安装来源: "user_install" | "runtime_auto" | "agent_package"
├── installed_at
└── status                         INSTALLED / PENDING / FAILED
```

前端查询 Claw 下的 Skill 列表：

```
GET /api/claws/123/agents/default/skills
  → BFF → claw-service → 查 claw_agent_skills → 返回列表
```

---

## 8. 沙箱 .claw 目录结构（阶段二细化）

```
/workspace/                    # PVC 挂载点，持久
├── .claw/                     # Claw 元数据目录
│   ├── <agent-role>/          # 每个 Agent 一个子目录
│   │   ├── skills/            # 该 Agent 的 Skills
│   │   └── config/            # 运行时配置
│   └── .clawrc                # Claw 级配置（如有）
└── user-files/                # 用户文件
```

阶段一不实现，阶段二 claw-runner 迁移时新增 `POST /v1/claw/init` 接口来初始化此结构。

---

## 9. 旧代码迁移映射

### 9.1 gateway

| 旧 package | 说明 |
|------|------|
| `user/` | 用户管理 |
| `auth/` | 认证逻辑 |
| `routebinding/` | 路由绑定 |

### 9.2 claw-service（原 conversation-service 改名）

| 旧 package | 说明 |
|------|------|
| `conversation/` | 对话、消息、会话状态 |
| `session/` | 会话解析 |
| `clawchat/` | Claw 聊天关联 |
| `claw/` | Claw 实例管理 |
| `agentinstall/` | Agent 实例安装、审批（仅 DB 操作） |
| `agentconfig/` | Agent 实例工具策略配置 |

### 9.3 runtime-service

| 旧 package | 说明 |
|------|------|
| `orchestrator/` | 对话编排 |
| `sandbox/` | 沙箱编排、工具调度、文件推送 |
| `approval/` | 工具审批 |
| `agent/` | Agent 运行入口 |
| `pyclaw/` | 调 FastAPI 的 HTTP client |
| `tool/` | 工具目录、解析 |
| `provider/` | LLM 提供商管理 |
| `secret/` | 加密凭据管理、K8s 同步 |

### 9.4 agent-marketplace-service

| 旧 package | 说明 |
|------|------|
| `agentpackage/` | Agent 发布、版本管理 |

### 9.5 billing-service

| 旧 package | 说明 |
|------|------|
| `usage/` | 用量、额度 |

### 9.6 删除

| 旧 package | 原因 |
|------|------|
| `token/` | 当前以 Web 场景为主，CLI Token 不保留 |
| `channel/` | 不需要 Channel 调度 |

### 9.7 各服务自持

| 旧 package | 说明 |
|------|------|
| `audit/` | 审计日志，每个需要审计的领域服务各持一份 |
| `common/` | 异常处理等工具，每个服务各持一份 |
| `config/SecretEncryptionService` | 加密服务，需要加密的领域服务各持一份 |

---

## 10. 被删除/变更的原 CLAUDE.md 要求

| 原要求 | 处理 |
|------|------|
| "Claw Runner 必须保持执行环境隔离" | 保留，迁移至 claw-runner 的 `sandbox/` 和 `workspace/path_guard.py` |
| 原 migration-plan.md 7 阶段 | 本设计替代原迁移计划。骨架先行，再逐服务迁移 |

---

## 11. 后续阶段预览

阶段二业务迁移建议顺序：

```
1. claw-service（Claw/Agent/对话核心 CRUD）
2. runtime-service（编排引擎，依赖 claw-service）
3. agent-marketplace-service（Agent 市场 + OSS 上传）
4. skill-marketplace-service（Skill 市场 + OSS 上传）
5. billing-service（计费）
6. gateway + backend-for-frontend（入口层，最后调整）
7. Python Runtime：pyclaw-runtime-api → claw-runner
```
