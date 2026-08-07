# Claw 服务（runtime）组件设计

## 职责边界

Claw 服务提供 Agent 运行时的一切能力，镜像集中开发、按 Claw 实例部署：

| 域 | 归属 | 说明 |
| :--: | :--: | :--: |
| Agent 推理、路由、工具调用、Skill 调用 | Claw Pod | 本组件核心职责 |
| 敏感工具审批（挂起/恢复） | Claw Pod | interrupt + 回调恢复 |
| 不可信脚本执行 | 沙箱 Job | Claw Pod 调度，同 namespace |
| 管理域（CRUD/商店/部署协调） | 后端 | Claw Pod 不参与 |

## 部署形态

* **一份镜像，按 Claw 实例化**：用户创建 Claw → 后端用 runtime 镜像在 `claw-{id}` namespace 部署常驻 Claw Pod
* **Claw Pod 承载该 Claw 的所有 Agent**：路由、工具、Skill、审批协调都在 Pod 内完成，“同一 Claw 多 Agent 互相感知”即在此发生
* **沙箱 Job 与 Claw Pod 分离**：不可信脚本只进一次性沙箱 Job（跑完即删），Claw Pod 只跑可信编排代码

## 调用入口（双凭证，单推理通道）

网页端和程序调用共用同一条推理通道，仅凭证不同：

| 来源 | 凭证 | 定位 Agent | 会话 |
| :--: | :--: | :--: | :--: |
| 程序客户端 | api_key | model=alias | 无状态 |
| 网页端 | JWT | agentId / alias | conversation_id |

网关完成鉴权后透传 `X-User-Id`，Claw Pod 不关心调用来源，只管推理。

## 内部架构（LangGraph）

### 人格组装器

Claw Pod 构建 system message 时读 agent_file 表，从 OSS 拉取文件按序注入：
1. 读 agent_file → 拿人格文件 OSS URL
2. 拉取 SOUL.md → IDENTITY.md → AGENTS.md
3. 最后拼接 agent.system_prompt
4. 合并为 system message 注入 LLM

注入顺序与优先级：
```text
SOUL.md（底线）→ IDENTITY.md（身份）→ AGENTS.md（行为）→ system_prompt（即时设定）
     └──────── 越靠后，行为约束力越强 ────────┘
```

### 人格自动沉淀（update_persona 工具）

Agent 运行中若 LLM 判断自身人格需要更新（用户纠正了行为方式、沉淀了新的工作风格），可调用内置工具 update_persona 自动写入人格文件。

**工具定义**

| **项** | **说明** |
| :--: | :--: |
| 工具名 | update_persona |
| 入参 | file_name（SOUL.md / IDENTITY.md / AGENTS.md）、new_content、mode（append 默认 / replace）、reason（沉淀理由，审计用） |
| 归属 | 所有 Agent 内置，平台赋予，runtime 硬编码，不占用工具库表（tool/agent_tool） |

执行流程（固定图内置逻辑直接执行，不经过工具库工具管线）：
1. 认出 tool_call：校验 file_name 白名单，否则返回错误
2. mode=append → 追加到文件末尾；mode=replace → 整体替换
3. 大小校验：单文件 ≤ 16KB，超限返回错误，提示 LLM 读全文合并去重后用 replace 重写
4. 写 OSS 新对象 → 得新 file_url → 写 agent_file（有则覆盖 file_url/file_hash/file_size，无则插入）
5. 返回沉淀成功，Agent 继续

**生效机制**

* 人格组装器作为每轮 invoke 的首节点执行：每轮从 OSS 拉最新人格文件组装 system message
* update_persona 执行完成后同步更新本轮 State 中的 system message，同轮后续推理即用新人格；上一轮沉淀的内容由下一轮首节点自然带入
* 用 file_hash 缓存：文件未变则复用，避免每轮打 OSS
* system_prompt 不受 update_persona 影响，仅用户手动编辑

**关键约定**

* 写入机制：追加（append）是默认写入方式，replace 用于纠正改写；上限 16KB 兜底防止无限膨胀
* 与网页编辑同一写路径：PUT /agents/{id}/files/{name} 与 update_persona 都走"写 OSS + 写 agent_file"；update_persona 写前先读当前内容，降低互相覆盖概率
* 写库权限：Claw Pod 直接写 agent_file 表，与 tool_approval 同模式（不经过后端），符合既有信任边界

### 固定图 + 动态子图

```text
固定图：router → executor
│
└─ 按需调用 AGENT_REGISTRY 中注册的 Agent 子图
```

- **固定图结构不变**，Agent 作为动态子图通过注册表（AGENT_REGISTRY dict）注册
- 图是 Python 对象，`build_agent_subgraph(manifest)` 可在运行时构建，无编译期限制
- 子图通过 `subgraph.invoke(state)` 与主图**共享 State**
- 工具（无状态函数）与子图（共享 State）的差别：Agent 互感知依赖子图机制

### Agent 路由

- 用户未指定 Agent 时，router 节点读取本 Claw 所有 Agent 的 description，作为**动态工具列表**注入路由 LLM，由其选择目标 Agent；路由 LLM 返回的 tool_call.name 即目标 Agent，router 节点拦截该调用（不真正执行工具），将 State 交给对应 Agent 的子图
- 用户手动指定（alias/agentId）则跳过路由

## 关键流程

### 1. 敏感工具审批（interrupt + 回调恢复）

1. 调敏感工具前 interrupt() 挂起，保存 request_id → 图 State
2. 写 tool_approval（status=0, expires_at）
3. 后端推送前端 → 用户点击
4. 后端回写 tool_approval + HTTP 回调 Claw Pod
5. 收到回调 → Command(resume=结果) → Agent 继续执行

兜底：expires_at 超时 → Agent 恢复并告知审批超时。

### 2. Skill 调用

1. 读 agent_skill → 定位 skill_id
2. 从 OSS 拉 SKILL.md → 注入 LLM 指令
3. Skill 含脚本 → 调度沙箱 Job 执行（见流程 3）

### 3. 沙箱执行

1. Claw Pod 调 K8s API：创建同 namespace 的一次性沙箱 Job
2. 挂载用户 PVC 子目录（subPath: task-{request_id}）
3. 限制：CPU/内存 limits、activeDeadlineSeconds 超时、默认禁网、只读 rootfs、非 root 用户
4. 执行完 → 结果回传 Claw Pod → Job 销毁

## 对外接口（Claw Pod 暴露）

| 接口 | 调用方 | 说明 |
| :--: | :--: | :--: |
| POST /v1/chat/completions | 网关（程序/网页）| OpenAI 兼容推理入口，SSE 流式 |
| POST /approvals/callback | 后端 | 审批结果回调，恢复挂起的图 |

## 依赖的外部服务

| 服务 | 用途 | 调用方式 |
| :--: | :--: | :--: |
| MySQL | 读 Agent/Skill/Tool 配置、写 tool_approval | 直连 |
| 阿里云 OSS | 拉 Skill 文件、存 Agent 产物 | SDK |
| K3s API | 调度沙箱 Job | fabric8 / 官方 client |
| 后端 | 审批推送与回写（经由 tool_approval + WebSocket）| 共享 MySQL |

## 技术要点

- **会话状态**：LangGraph checkpointer 按 conversation_id 保存多轮对话 State
- **审批挂起**：interrupt/Command(resume) 是 LangGraph 官方 human-in-the-loop 机制，全程无轮询
- **沙箱安全**：禁网 + 只读 rootfs + 非 root + 超时 + 资源限制，五重防线
- **信任边界**：X-User-Id 由网关注入，Claw Pod 不信任任何外部直连（集群内仅网关可到达）