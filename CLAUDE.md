# Claude Code 工作规约

## 项目概述

SaaS Claw 是 Claw SaaS 平台的 monorepo。用户创建 Claw（独立执行环境），每个 Claw 可运行多个 Agent。

- 域名：`saas.claw.anxin-hitsz.com`
- Maven groupId：`com.claw.saas`
- K8s namespace：`saas-claw`
- Docker 镜像前缀：`saas-claw/*`

## 目录结构

```text
backend/     Java / Spring Boot 微服务（7 个）
frontend/    前端应用
runtime/     Python / FastAPI Runtime（控制面 + 数据面）
deploy/      本地与生产部署配置（Helm charts）
scripts/     工程脚本
docs/        架构、设计、计划文档
```

## 关键文档

重构和开发前，先阅读：

```text
docs/architecture/ARCHITECTURE.md                          最终目标架构
docs/architecture/backend-coding-standards.md               Java 编码规约
docs/architecture/runtime-coding-standards.md               Python 编码规约
docs/plans/migration-plan.md                                当前进度 + 剩余待办（必读）
docs/superpowers/specs/2026-07-19-claw-saas-refactoring-design.md  重构设计
docs/superpowers/plans/2026-07-19-claw-saas-skeleton-plan.md       阶段一计划（已执行）
```

## 工作要求

1. Spring Boot Controller 不写业务逻辑，业务逻辑进入 service/impl。
2. FastAPI router 不写业务编排，编排在 Spring runtime-service。
3. Claw Runner 必须保持执行环境隔离（path_guard, sandbox limits）。
4. 不直接把 Entity 暴露给前端或跨服务接口。
5. 不建 common 共享模块，各服务自持工具类（异常处理、加密等）。
6. claw-service 是"账本"（纯 DB CRUD，不碰 OSS）。
7. runtime-service 是"大脑"（编排 + OSS 下载 + 推到沙箱）。
8. 改动后运行对应模块的校验或测试。

## 服务速查

| 服务 | 端口 | 技术 | 包名 |
|------|------|------|------|
| gateway | 8080 | Spring Cloud Gateway (WebFlux) | com.claw.saas.gateway |
| backend-for-frontend | 8081 | Spring Web MVC | com.claw.saas.bff |
| claw-service | 8082 | Spring Web MVC + JPA | com.claw.saas.claw |
| runtime-service | 8083 | Spring Web MVC + JPA | com.claw.saas.runtime |
| agent-marketplace-service | 8084 | Spring Web MVC + JPA + OSS | com.claw.saas.agentmarketplace |
| billing-service | 8085 | Spring Web MVC + JPA | com.claw.saas.billing |
| skill-marketplace-service | 8086 | Spring Web MVC + JPA + OSS | com.claw.saas.skillmarketplace |
| control-plane | 8090 | FastAPI（执行引擎） | — |
| claw-runner | 8091 | FastAPI（每 Claw 一个） | — |

## 部署

- **ECS：** 8.135.60.136（/opt/saas-claw）
- **ACR 仓库：** crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/saas-claw/*
- **K8s：** K3s 集群，namespace `saas-claw`，kubeconfig `/etc/rancher/k3s/k3s.yaml`
- **GitHub Actions：** `git push main` → 自动构建 8 个镜像 + SSH 部署
- **敏感信息：** 全部在 ECS 上的 K8s Secrets 中，不在 Git 仓库
- **MySQL：** Service `saas-claw-mysql:3306`，用户 `saas_claw`，数据库 `claw_saas_control` / `claw_saas_runtime`
- **Redis：** Service `redis-master:6379`（集群内）

## 当前重构状态

核心重构（阶段一+二）已完成。详见 [`docs/plans/migration-plan.md`](docs/plans/migration-plan.md)。

**剩余工作：**
1. `git push origin main` — 触发部署
2. BFF 实现（当前空壳，Gateway 路由到 claw-service 绕过了 BFF）
3. skill-marketplace-service 实现（当前空壳）
4. Skill 数据库表（claw_agent_skills, skills, skill_dependencies）
5. claw-runner 的 `.claw/` 沙箱目录初始化
6. InternalServiceAuthFilter 清理（服务间走 ClusterIP，不需要）
7. 前端适配新 API 路径
