# saas-claw 架构文档

## 平台简介

SaaS Claw——**创建**、**使用**、**扩展**你的专属 Claw。

用户通过登录进入平台，可以创建自己的 Claw，每个 Claw 被部署在独立 namespace（claw-{id}）下，通过 K3s Pod 进行隔离。

用户可以在自己的 Claw 中创建 Agent，同一 Claw 中的多个 Agent 可以互相感知，通过路由或用户手动指定进行 Agent 选择。

Agent 运行时可以使用工具，涉及到敏感工具时，需要弹窗给用户进行手动确认（允许、拒绝或用户自定义消息），工具审批记录需要留痕。

平台提供 Agent 商店与 Skill 商店，用户可以手动安装心仪的 Agent 或 Skill，Agent 在运行过程中遇到自身能力难以解决的问题时也可以自动搜索相关商店进行引入安装。

未来预计实现组织功能——多人加入同一组织，同一组织内的用户共享 Claw/Agent 资源。

## 组件构成
| 组件 | 技术栈 | 职责 | 详细设计 |
|------|--------|------|---------|
| 网关 gateway | Spring Cloud Gateway | 认证鉴权、限流、并发控制、转发 | [components/gateway.md](components/gateway.md) |
| 后端 backend | SpringBoot + MyBatis-Plus | 用户/组织/Claw/Agent/Skill 管理、商店 | [components/backend.md](components/backend.md) |
| Claw 服务 runtime | FastAPI + LangGraph | Agent 运行时、工具调用、审批、路由 | [components/runtime.md](components/runtime.md) |
| 前端 frontend | Vue3 | 用户交互 UI | [components/frontend.md](components/frontend.md) |

## 租户模型（当前 MVP）
- 当前采用**用户级隔离**：每个用户可创建多个 Claw，每个 Claw 独立 namespace（claw-{id}），租户间数据天然隔离
- organization 表已预留，未来支持团队共享时激活

## 文档导航
- **总览**：[系统总览](overview/system-overview.md) / [技术选型](overview/tech-stack.md) / [API 契约](overview/api-contract.md)
- **数据库**：[表结构设计](database/schema.md)

## 阅读顺序建议
1. 先读系统总览（了解平台全貌）
2. 再读数据库设计（理解数据模型）
3. 按需深入各组件文档