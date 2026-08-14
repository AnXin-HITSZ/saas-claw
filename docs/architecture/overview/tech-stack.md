# 技术选型

## 网关（gateway）

| 项 | 选型 |
|----|------|
| 框架 | Spring Cloud Gateway（Spring Boot 3.x + WebFlux）|
| 语言 | Java 17+ |
| 职责 | 认证鉴权、限流控制、并发控制、按路径转发 |

**选型理由**
* WebFlux 非阻塞 IO，高并发下的网关转发开销低，符合网关的吞吐要求
* 内置限流/过滤器链路，与平台"认证 + 横切关注点"职责天然匹配
* 与后端同属 Spring 生态，团队技术栈统一、复用成本低

## 后端（backend）

| 项 | 选型 |
|----|------|
| 框架 | SpringBoot 3.x + MyBatis-Plus |
| 数据库 | MySQL 8.x |
| 缓存 | Redis |
| 职责 | 用户/Claw/Agent/Skill 管理、商店、审批回写 |

**选型理由**
* MyBatis-Plus 的 CRUD 生成效率高，适合一人全栈快速迭代（沿用既有习惯）
* 全表不设外键，由应用层保证完整性（已在 schema.md 声明）
* Redis：会话/缓存

## Claw 服务（runtime）

| 项 | 选型 |
|----|------|
| 框架 | FastAPI + LangChain/LangGraph |
| 语言 | Python 3.11+ |
| 职责 | Agent 运行时、路由、工具调用、审批挂起、沙箱调度 |

**选型理由**
* LangGraph 的图编排是 Agent 运行时的天然骨架：固定图（router → executor）+ Agent 动态子图
* interrupt / Command(resume) 原生支持"审批挂起 → 回调恢复"，正是敏感工具审批流程需要的（流 2）
* 子图机制让“同一 Claw 多个 Agent 互相感知、共享 State”得以实现
* Python 是 LLM 生态的第一语言，模型接入最顺

## 前端（frontend）

| 项 | 选型 |
|----|------|
| 框架 | Vue3 + Vite |
| UI 组件库 | Element Plus（或同类）|
| 通信 | HTTP + WebSocket/SSE |
| 职责 | 用户交互、审批弹窗 |

**选型理由**
* Vue3 生态成熟、上手快，适合一人全栈最后攻坚
* WebSocket/SSE 承接审批推送（流 2 的第 ③ 步），避免前端轮询

## 基础设施

| 组件 | 选型 |
|------|------|
| 集群 | K3s（个人项目选 K8s 轻量版）|
| 对象存储 | 阿里云 OSS（Skill 文件、Agent 产物）|
| 容器 | Docker + 私有镜像仓库 |

## 整体选型原则

1. **共享核心栈**：网关与后端同属 Spring 生态，减少跨栈认知负担
2. **各用所长**：Agent 运行时必须用 Python（LLM 生态），管理侧用 Java 保开发效率
3. **轻量落地**：基础设施选 K3s 轻量版 + 自建私有仓库，一人运维成本可控，个人项目够用