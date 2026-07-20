# Claude Code 工作规约

## 项目概述

claw-saas 是 Claw SaaS 平台的 monorepo。用户创建 Claw（独立执行环境），每个 Claw 可运行多个 Agent。

## 目录结构

```text
backend/     Java / Spring Boot 微服务（8 个）
frontend/    前端应用
runtime/     Python / FastAPI Runtime（控制面 + 数据面）
deploy/      本地与生产部署配置
scripts/     工程脚本
docs/        架构、设计、计划文档
```

## 关键文档

重构和开发前，先阅读：

```text
docs/architecture/ARCHITECTURE.md          最终目标架构
docs/architecture/backend-coding-standards.md  Java 编码规约
docs/architecture/runtime-coding-standards.md  Python 编码规约
docs/superpowers/specs/2026-07-19-claw-saas-refactoring-design.md  重构设计
docs/superpowers/plans/2026-07-19-claw-saas-skeleton-plan.md       阶段一计划
```

## 工作要求

1. 当前阶段：**阶段一（骨架搭建）** 或 **阶段二（业务迁移）**，以实施计划为准。
2. Spring Boot Controller 不写业务逻辑，业务逻辑进入 service/impl。
3. FastAPI router 不写业务编排，编排在 Spring runtime-service。
4. Claw Runner 必须保持执行环境隔离（path_guard, sandbox limits）。
5. 不直接把 Entity 暴露给前端或跨服务接口。
6. 不建 common 共享模块，各服务自持工具类（异常处理、加密等）。
7. 先拆服务边界，再迁移业务代码。每次只迁移一个服务。
8. 改动后运行对应模块的校验或测试。

## 服务速查

| 服务 | 端口 | 技术 |
|------|------|------|
| gateway | 8080 | Spring Cloud Gateway (WebFlux) |
| backend-for-frontend | 8081 | Spring Web MVC |
| claw-service | 8082 | Spring Web MVC + JPA |
| runtime-service | 8083 | Spring Web MVC + JPA |
| agent-marketplace-service | 8084 | Spring Web MVC + JPA + OSS |
| billing-service | 8085 | Spring Web MVC + JPA |
| skill-marketplace-service | 8086 | Spring Web MVC + JPA + OSS |
| pyclaw-runtime-api | 8090 | FastAPI (控制面) |
| claw-runner | 8091 | FastAPI (数据面) |

## 当前状态

阶段一骨架已搭建完毕。阶段二按以下顺序迁移业务代码：

```text
1. claw-service
2. runtime-service
3. agent-marketplace-service
4. skill-marketplace-service
5. billing-service
6. gateway + backend-for-frontend
7. Python Runtime: pyclaw-runtime-api → claw-runner
```
