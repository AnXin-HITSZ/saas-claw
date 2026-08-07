# 网关（mini-gateway）组件设计

## 职责边界

网关只做**认证 + 横切关注点**，不做业务：

| 域 | 归属 | 说明 |
| :--: | :--: | :--: |
| 认证（401，你是谁）| 网关 | 解析 api_key / JWT → userId |
| 授权（403，你能不能）| 后端 | 业务权限判断 |
| 限流、并发控制 | 网关 | 横切关注点 |
| 业务逻辑 | 后端 / Claw Pod | 网关只转发 |

## 鉴权机制

| 调用方 | 凭证 | 解析 |
| :--: | :--: | :--: |
| 程序客户端 | api_key（sk-xxx）| 查 authorization 表 → userId |
| 网页端 | JWT | 解析 token → userId |

* 白名单放行：`/auth/login`、`/auth/register`（登录前无凭证）
* 鉴权通过后注入 `X-User-Id` header 透传下游

## 横切关注点

### 限流（RateLimitFilter）

* 基于 Redis + Lua 脚本实现，按 API Key / userId 维度限流
* Lua 保证"计数 + 过期"原子性

### 并发控制（ConcurrencyFilter）

* Google 并发 + Device 并发两种维度
* 释放用 compare_and_delete Lua 脚本，**原子释放**避免 GET+DEL 竞态
* **fail-open**：Redis 异常时 try-catch 放行，不因限流服务故障阻断业务

## 路由策略

| 路径 | 目标 | 说明 |
| :--: | :--: | :--: |
| /auth/login、/auth/register | 放行直通 | 白名单 |
| /api/** | 后端（backend）| 管理接口 |
| /v1/chat/completions | Claw Pod（动态）| 推理接口，需解析目标 Claw |

## 动态路由（推理链路）

推理请求必须转发到**发起调用的用户所属的 Claw Pod**，目标地址随用户而变：
1. 鉴权：api_key / JWT → userId
2. 路由解析：查 agent 表（user_id + alias）→ 得 claw_id
3. 目标 Service：claw-{id}.claw-{id}.svc.cluster.local
4. 过滤器动态改写 URI 转发

实现：GatewayFilter 内查库得 claw_id 后 mutate 请求 URI，指向对应 Claw Pod Service。

## 依赖的外部服务

| 服务 | 用途 | 调用方式 |
| :--: | :--: | :--: |
| Redis | 限流计数、并发计数 | Lua 脚本 |
| MySQL | authorization（api_key→userId）、agent（路由解析）| 直连 |
| 后端 | 管理请求转发目标 | 集群内 |
| Claw Pod | 推理请求转发目标 | 集群内 Service DNS |

## 技术要点

* **Lua 原子性**：限流（INCR+EXPIRE）、并发释放（compare_and_delete）都用 Lua 保证原子
* **fail-open**：横切组件故障放行，业务不阻断
* **Key 设计**：限流/并发 key 按 userId 或 API Key 维度隔离
* **信任边界**：下游（后端/Claw Pod）信任 `X-User-Id` header，因网关注入是唯一入口