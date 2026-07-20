# 项目架构说明

## 项目说明

SaaS Claw 是一个 Claw SaaS 平台。用户创建 Claw（独立执行环境），每个 Claw 可运行多个 Agent。域名 `saas.claw.anxin-hitsz.com`。

## 目标目录结构

```text
saas-claw/
  CLAUDE.md
  README.md

  docs/
    architecture/
      ARCHITECTURE.md
      backend-coding-standards.md
      runtime-coding-standards.md
    plans/
      migration-plan.md

  backend/
    pom.xml                         # 父 POM（Maven 多模块）

    gateway/                         # 8080  Spring Cloud Gateway (WebFlux)
    backend-for-frontend/            # 8081  Spring Web MVC
    claw-service/                    # 8082  Spring Web MVC + JPA
    runtime-service/                 # 8083  Spring Web MVC + JPA
    agent-marketplace-service/       # 8084  Spring Web MVC + JPA + OSS
    billing-service/                 # 8085  Spring Web MVC + JPA
    skill-marketplace-service/       # 8086  Spring Web MVC + JPA + OSS

  frontend/
    package.json
    src/
    public/

  runtime/
    control-plane/                   # 8090  FastAPI 控制面（执行引擎）
      pyproject.toml
      app/
        main.py
        api/
        runtime/
        schemas/
        config/

    claw-runner/                     # 8091  FastAPI 数据面（隔离执行）
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
    helm/
      saas-claw/                     # 主 Helm chart（8 服务 + Redis）
      saas-claw-mysql/               # MySQL Helm chart

  scripts/
```

## 总体架构

```text
Frontend
  → gateway
  → backend-for-frontend
  → domain services
  → database / control-plane / external systems

control-plane
  → claw-runner
```

## 模块职责

```text
gateway
  统一入口、认证前置、限流、路由、CORS、请求追踪、健康检查

backend-for-frontend
  面向前端页面的聚合 API，负责页面级数据组装、裁剪和格式转换

claw-service
  Claw 实例管理、对话线程、消息、Agent 实例安装/配置（纯 DB CRUD，不碰 OSS）

runtime-service
  业务编排、Orchestrator、Agent run、call_agent、FastAPI 调用、审批恢复、
  Provider 管理、Secret 管理、OSS 文件下载与沙箱推送

agent-marketplace-service
  Agent 发布、版本、发现、搜索、OSS 制品上传下载

skill-marketplace-service
  Skill 发布、搜索、独立安装、OSS 制品上传下载

billing-service
  用量、额度、套餐、账单、计费规则

control-plane (Python / FastAPI)
  Runtime 控制面，执行引擎：收到 run 请求 → 调 LLM → 工具调用 → 等待审批 → 返回结果

claw-runner (Python / FastAPI)
  Runtime 数据面，单个 Claw 的隔离执行环境：workspace 操作、工具执行、命令执行
```

## 调用关系

### 用户可见链路

```
链路 1：CRUD/管理
  Frontend → gateway → BFF → claw-service → DB

链路 2：执行
  Frontend → gateway → BFF → runtime-service → control-plane → claw-runner
```

### 服务间内部协作

```
用户主动安装 Agent：
  claw-service --pushFiles()--> runtime-service
  → 从 OSS 下载 → 推 claw-runner → 返回 → claw-service 更新 DB 状态

运行时自动安装：
  runtime-service --recordInstall()--> claw-service（仅写 DB）
  → 返回 → 继续执行

无循环依赖：两条 flow 独立触发，单向调用。
```

### 调用约束

```text
允许：
  BFF → claw-service
  BFF → runtime-service
  runtime-service → control-plane
  control-plane → claw-runner
  claw-service → runtime-service（pushFiles）
  runtime-service → claw-service（recordInstall）

禁止：
  gateway / BFF → claw-runner（直连）
  domain services → claw-runner（直连）
  gateway 编排业务
```

## 服务端口

```text
gateway                    8080
backend-for-frontend       8081
claw-service               8082
runtime-service            8083
agent-marketplace-service  8084
billing-service            8085
skill-marketplace-service  8086
control-plane              8090
claw-runner                8091（动态，每 Claw 一个）
```

## Java 包结构

所有服务统一使用 `com.claw.saas.<service>` 包名：

```text
com.claw.saas.gateway
com.claw.saas.bff
com.claw.saas.claw
com.claw.saas.runtime
com.claw.saas.agentmarketplace
com.claw.saas.billing
com.claw.saas.skillmarketplace
```

每个 Spring Boot 服务内部采用标准分层：

```text
controller/      HTTP 接口适配（不写业务逻辑）
service/         业务用例接口
service/impl/    业务用例实现
repository/      持久化
domain/          领域对象、枚举、值对象
dto/             接口请求/响应对象
client/          服务间调用、Runtime API 调用
config/          Spring 配置
exception/       异常和全局异常处理
```

## 关键设计决策

- **gateway 使用 WebFlux**，不依赖 spring-boot-starter-web
- **不建 common 模块**。异常处理、加密等工具类各服务自持
- **claw-service 是"账本"**（纯 DB CRUD，不碰 OSS）
- **runtime-service 是"大脑"**（编排 + OSS 下载 + 推到沙箱）
- **OSS 使用延迟加载**：首次执行时 runtime-service 从 OSS 下载
- **audit 各服务自持**。gateway 做 HTTP 访问审计，领域服务做操作审计
- **K8s 部署在阿里云 ECS K3s 集群**，namespace: `saas-claw`
- **镜像仓库**：阿里云 ACR `crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/saas-claw/*`

## 文档分工

```text
docs/architecture/ARCHITECTURE.md
  最终目标架构（本文件）

docs/plans/migration-plan.md
  当前进度和剩余待办

docs/architecture/backend-coding-standards.md
  Java 代码规约

docs/architecture/runtime-coding-standards.md
  Python Runtime 代码规约

docs/superpowers/specs/2026-07-19-claw-saas-refactoring-design.md
  重构设计文档

docs/superpowers/plans/2026-07-19-claw-saas-skeleton-plan.md
  阶段一实现计划（已执行完毕）
```
