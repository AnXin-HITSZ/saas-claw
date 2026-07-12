# pyclaw 学习与落地路线图：OpenClaw 核心能力 + 自研推理引擎

## 1. 项目定位

pyclaw 可以定位为：

> Python 版 OpenClaw 核心能力 + 内置自研高性能推理子系统。

OpenClaw 的核心价值在于 Agent 编排：Gateway 网关、Agent Runtime、工具/技能系统、会话与记忆管理。pyclaw 在此基础上进一步加入本地推理基础设施，把外部 LLM API 调用升级为可本地运行、可调度、可观测、可扩展的推理系统。

最终目标不是单独复刻一个 Agent 框架，也不是孤立做推理优化，而是形成一个完整闭环：

- Gateway 统一接入用户、渠道、会话和推理请求。
- Agent Runtime 负责 ReAct/CoT 推理循环、工具调用和记忆读写。
- 技能/工具系统以插件化方式扩展 Agent 能力。
- 推理层提供 vLLM、llama.cpp、本地高性能后端和云端 API 的统一抽象。
- 调度、缓存、模型路由、监控、端侧部署都围绕真实 Agent 场景展开。

## 2. 总体学习目标

完成本路线后，应具备以下能力：

1. 能用 Python 实现一个 OpenClaw 风格的 Agent 编排最小闭环。
2. 能理解并接入 FastAPI、WebSocket、SQLite、本地向量记忆、插件加载和沙箱执行。
3. 能将 vLLM、llama-cpp-python 等推理后端封装为统一接口。
4. 能理解 KV Cache、PagedAttention、请求调度、前缀缓存、流式生成等推理系统核心机制。
5. 能设计多租户、多会话、多渠道优先级下的推理调度策略。
6. 能构建多模型推理服务平台，包含模型路由、限流、熔断、降级和可观测性。
7. 能进行端侧 Agent 裁剪部署，理解量化、内存限制和滑动窗口推理。
8. 能完成一份可放入简历和项目仓库的性能报告、架构文档和演示案例。

## 3. 前置基础学习

### 3.1 Python 服务端基础

目标：

- 熟悉 FastAPI 的路由、依赖注入、中间件、异常处理和 OpenAPI 文档。
- 熟悉 WebSocket 的连接管理、心跳、断线重连和消息广播。
- 熟悉 asyncio、async generator、队列、任务取消和超时控制。

学习内容：

- FastAPI + Uvicorn 服务启动。
- REST API 与 WebSocket 混合架构。
- Pydantic 数据模型与请求校验。
- asyncio.Queue、asyncio.Task、asyncio.Semaphore。
- 流式响应：Server-Sent Events 与 WebSocket token streaming。

练习目标：

- 实现 `/chat` HTTP 接口。
- 实现 `/ws/sessions/{session_id}` WebSocket 会话接口。
- 支持一个请求在后端异步生成 token，并实时推送到前端或 CLI。

### 3.2 Agent 编排基础

目标：

- 理解 Agent Runtime、ReAct、CoT、工具调用、记忆系统的关系。
- 能实现一个最小可运行的 Agent loop。

学习内容：

- ReAct：Thought、Action、Observation、Final Answer。
- 工具调用协议：工具 schema、参数校验、执行结果回填。
- 短期记忆：当前会话消息历史。
- 长期记忆：SQLite 存储与向量检索。
- 系统提示词、工具提示词、会话上下文拼装。

练习目标：

- 实现一个支持工具调用的 Agent。
- 至少内置 3 个工具：计算器、文件读取、Shell 只读命令。
- 支持将工具执行结果回填给模型，继续下一轮推理。

### 3.3 LLM 推理基础

目标：

- 理解 Transformer 推理时的 Prefill、Decode、KV Cache、Batching 和 Streaming。
- 理解为什么 Agent 场景对推理调度有特殊要求。

学习内容：

- Tokenizer、prompt template、采样参数。
- Prefill 与 Decode 的计算差异。
- KV Cache 的作用、显存占用和生命周期。
- Continuous batching 与动态 batch。
- 前缀缓存、系统提示词缓存、多轮对话缓存。
- vLLM 的 PagedAttention 基本思想。
- llama.cpp 的量化模型、GGUF、CPU/GPU offload。

练习目标：

- 用一个本地开源模型跑通同步生成和流式生成。
- 对比相同 prompt 下 API 调用、本地 vLLM、llama.cpp 的延迟差异。

## 4. 阶段 1：pyclaw 核心最小闭环

周期：2 周。

目标：

完成 Gateway + Agent Runtime + 工具系统 + 本地存储，让 pyclaw 可以通过外部 LLM API 跑通完整 Agent 流程。

### 4.1 Gateway 网关层

学习内容：

- FastAPI 项目结构。
- 会话创建、会话恢复、消息路由。
- WebSocket 长连接管理。
- 多渠道抽象：CLI、HTTP、WebSocket 先作为最小实现。
- 请求上下文：user_id、channel_id、session_id、priority、trace_id。

实现目标：

- `POST /sessions` 创建会话。
- `POST /sessions/{session_id}/messages` 发送消息。
- `GET /sessions/{session_id}/messages` 查询历史。
- `WS /ws/sessions/{session_id}` 实时收发消息。
- 所有请求统一进入 Agent Runtime。

验收标准：

- 一个用户可以创建多个会话。
- 同一会话消息按顺序持久化。
- WebSocket 可以收到流式 token 或阶段性事件。

### 4.2 Agent Runtime

学习内容：

- Agent 状态机设计。
- ReAct loop 的停止条件、最大步数、错误恢复。
- 工具调用和模型调用的统一事件流。
- 模型返回解析：普通文本、工具调用 JSON、最终回答。

实现目标：

- `AgentRuntime.run(session, message)`。
- 支持 `thinking`、`tool_call`、`tool_result`、`final` 等事件。
- 支持最大推理步数和超时控制。
- 支持工具失败后的错误信息回填。

验收标准：

- 用户提问后，Agent 可以决定是否调用工具。
- 工具结果能被加入上下文并继续推理。
- 所有中间事件可被 Gateway 流式推送。

### 4.3 工具/技能系统

学习内容：

- 插件发现与动态加载。
- 工具 schema：name、description、parameters、permissions。
- 沙箱执行与权限分级。
- 技能与模型绑定策略。

实现目标：

- `ToolRegistry` 负责注册和查询工具。
- 每个工具暴露统一 `invoke(args, context)` 接口。
- 支持从本地目录加载工具插件。
- 工具元数据可被注入模型 prompt。

验收标准：

- 新增一个工具不需要修改 Agent Runtime 主流程。
- 工具权限能区分只读、写入、危险操作。
- 工具执行日志可追踪。

### 4.4 本地存储与记忆

学习内容：

- SQLite 表结构设计。
- 会话、消息、工具调用、运行日志持久化。
- 本地向量记忆：embedding、chunk、检索、重排。

实现目标：

- SQLite 存储用户、会话、消息、工具调用。
- 实现简单向量记忆接口：`add_memory`、`search_memory`。
- Agent 每次运行前可检索相关历史记忆。

验收标准：

- 重启服务后会话仍可恢复。
- 多轮对话能读取历史消息。
- 长期记忆能影响回答内容。

阶段交付物：

- pyclaw 最小核心代码。
- API 使用示例。
- 一份 `core-architecture.md`。
- 一个端到端演示：用户提问、Agent 调工具、返回最终答案。

## 5. 阶段 2：项目 1，多租户 KV Cache 调度引擎

周期：3 周。

目标：

接入 vLLM 作为 pyclaw 默认推理后端，完成单机多用户、多会话、多优先级下的推理调度实验。

### 5.1 vLLM 后端封装

学习内容：

- vLLM Python API。
- SamplingParams。
- 同步生成与流式生成。
- vLLM engine、scheduler、block manager 的基本结构。

实现目标：

- 定义统一推理接口 `InferenceBackend`。
- 实现 `VLLMBackend`。
- Agent Runtime 不直接依赖 vLLM，只依赖抽象接口。

验收标准：

- 同一 Agent 可以在外部 API 和 vLLM 后端之间切换。
- vLLM 支持流式 token 输出。
- 请求上下文中的 session_id、user_id 能传入推理层。

### 5.2 多租户上下文建模

学习内容：

- 多租户隔离模型。
- KV Cache 生命周期。
- Agent 会话上下文与模型 prompt 的映射。

实现目标：

- 为每个推理请求附加 tenant metadata：user_id、channel_id、session_id、priority。
- 设计 KV Cache 隔离策略。
- 区分全局共享缓存、用户级缓存、会话级缓存。

建议策略：

- 系统提示词和公共工具说明可做全局前缀缓存。
- 同一用户的长期偏好可做用户级缓存。
- 多轮对话上下文使用会话级缓存。
- 不同用户之间禁止复用私有上下文缓存。

验收标准：

- 能说明哪些缓存可以共享，哪些不能共享。
- 能用测试验证不同用户不会发生上下文串扰。

### 5.3 优先级调度

学习内容：

- 推理请求队列。
- 抢占式调度与非抢占式调度。
- 即时消息、后台任务、定时技能的优先级差异。

实现目标：

- 在 Gateway 请求上下文中加入 priority。
- 调度队列支持高优先级请求插队。
- 低优先级后台任务在高负载时可延迟或取消。

验收标准：

- 即时消息优先级高于后台定时任务。
- 高优先级请求的平均等待时间低于低优先级请求。
- 调度行为有日志和指标可观察。

### 5.4 性能报告

学习内容：

- 压测设计。
- 延迟指标：TTFT、TPOT、P50、P95、P99。
- 吞吐指标：requests/s、tokens/s。
- 显存指标：KV Cache 使用量、峰值显存、碎片率。

实现目标：

- 构造多租户并发测试脚本。
- 对比原生 vLLM 与 pyclaw 定制调度策略。
- 输出图表和结论。

阶段交付物：

- `VLLMBackend`。
- 多租户请求上下文与缓存隔离设计文档。
- 优先级调度实现。
- 性能报告：显存利用率、平均延迟、吞吐率。

## 6. 阶段 3：项目 4，单模型极致性能优化套件

周期：3 周。

目标：

围绕 1-2 个主流开源模型，做量化、算子、调度三个方向的优化，并封装为 pyclaw 可切换的高性能后端。

### 6.1 模型选择与基线建立

建议模型：

- Qwen 系列 7B 级别模型。
- Llama 系列 8B 级别模型。

学习内容：

- 模型结构、上下文长度、显存需求。
- vLLM 与 llama.cpp 的基准测试方法。
- Agent 场景基准：长上下文工具调用、代码生成、多轮对话。

实现目标：

- 建立原生 vLLM 基线。
- 建立 llama.cpp 或其他轻量后端基线。
- 固定测试 prompt、并发数、采样参数和硬件环境。

验收标准：

- 能得到可重复的基线数据。
- 能记录硬件、模型、参数和版本。

### 6.2 量化优化

学习内容：

- INT4、INT5、INT8、FP8。
- AWQ、GPTQ 的基本思想。
- 量化对困惑度、任务准确率和生成质量的影响。

实现目标：

- 准备至少一种 AWQ 或 GPTQ 量化模型。
- 对比不同量化格式下的 token/s、显存和回答质量。
- 在 pyclaw 模型配置中支持 `balanced` 与 `performance` 模式。

验收标准：

- 能一键切换原始模型和量化模型。
- 报告中包含性能收益和质量损失分析。

### 6.3 算子与 Attention 优化

学习内容：

- FlashAttention 基本原理。
- 长上下文 attention 的显存与计算瓶颈。
- CUDA profiling 基础。
- Nsight Systems、Nsight Compute 或 PyTorch profiler。

实现目标：

- 研究并接入已有高性能 attention 实现。
- 针对长上下文场景做分块策略实验。
- 输出算子级 profiling 结果。

验收标准：

- 能指出主要性能瓶颈位于 prefill、decode 还是 attention。
- 能说明每个优化点带来的收益占比。

### 6.4 调度侧优化

学习内容：

- 前缀缓存命中率。
- 系统提示词复用。
- 多轮对话增量解码。
- Agent 工具调用场景下的上下文重复结构。

实现目标：

- 优化 pyclaw 中 prompt 构造方式，提升共享前缀比例。
- 记录 prefix cache hit rate。
- 对长系统提示词、工具说明做缓存复用。

验收标准：

- Agent 多轮对话 TTFT 明显下降。
- 高频工具场景下重复 prompt 成本降低。

阶段交付物：

- `HighPerformanceBackend`。
- 模型性能模式配置。
- 优化报告：端到端延迟、单卡吞吐、显存占用。
- profiling 分析文档。

## 7. 阶段 4：项目 2，多模型推理服务平台

周期：2-3 周。

目标：

把 pyclaw 从单机推理升级为多模型、多实例、可观测、可降级的服务化平台。

### 7.1 ModelRouter

学习内容：

- 多模型注册表。
- 任务分类与模型选择策略。
- 技能/任务类型到模型的绑定。

实现目标：

- 新增 `ModelRouter` 模块。
- 支持注册 7B、14B、70B、本地模型、云端模型。
- 根据任务类型、技能、上下文长度、负载选择模型。

路由示例：

- 代码生成：DeepSeek-Coder 或代码模型。
- 通用对话：Qwen 通用模型。
- 低延迟任务：本地小模型。
- 高复杂度任务：云端大模型或 70B 实例。

验收标准：

- Agent Runtime 发起请求时不直接指定底层实例。
- 模型选择过程可解释、可记录、可回放。

### 7.2 服务化与弹性扩缩容

学习内容：

- Docker 镜像构建。
- Kubernetes Deployment、Service、HPA。
- GPU 资源隔离。
- 模型实例健康检查。

实现目标：

- 每个模型实例独立容器化。
- 通过 Kubernetes 部署模型服务。
- 支持基于 GPU 利用率、队列长度或请求延迟扩缩容。

验收标准：

- 模型实例异常时流量可转移。
- 扩容后新实例可被 ModelRouter 发现。

### 7.3 限流、熔断、降级

学习内容：

- Rate limiting。
- Circuit breaker。
- Retry、timeout、fallback。
- 灰度发布。

实现目标：

- Gateway 层实现用户级、渠道级限流。
- ModelRouter 对过载模型进行熔断。
- 大模型过载时自动降级到小模型。
- 支持按比例灰度新模型。

验收标准：

- 高并发下系统不会整体雪崩。
- 降级结果会明确记录在 trace 中。

### 7.4 可观测性

学习内容：

- Prometheus metrics。
- Grafana dashboard。
- OpenTelemetry trace。
- 日志关联 trace_id。

实现目标：

- 暴露 `/metrics`。
- 监控每个模型实例的延迟、吞吐、错误率、队列长度、显存占用。
- 建立 Gateway -> Agent Runtime -> ModelRouter -> Backend 的链路追踪。

验收标准：

- 能从 Grafana 看出瓶颈位于入口、Agent、路由还是模型实例。
- 压测报告能引用监控数据。

阶段交付物：

- `ModelRouter`。
- Docker/Kubernetes 部署清单。
- Prometheus + Grafana 监控面板。
- 1000+ 并发会话压测报告。

## 8. 阶段 5：项目 3，端侧全栈 Agent 部署

周期：2 周。

目标：

裁剪 pyclaw，使其可以在资源受限设备上运行完整 Agent + 推理 + 工具执行闭环。

### 8.1 llama-cpp-python 后端

学习内容：

- GGUF 模型格式。
- llama-cpp-python 使用方式。
- CPU 推理、GPU offload、上下文长度配置。
- INT4、INT5 量化模型。

实现目标：

- 实现 `LlamaCppBackend`。
- 支持本地 GGUF 模型加载。
- 支持流式生成。

验收标准：

- 不依赖云端 API 即可完成基本对话。
- 模型路径、上下文长度、线程数可配置。

### 8.2 pyclaw 端侧裁剪

学习内容：

- 依赖裁剪。
- 轻量 CLI。
- 本地 Web UI 最小化。
- 技能白名单。

实现目标：

- Gateway 简化为 CLI + 本地 Web。
- 默认关闭重型插件、远程服务和集群模块。
- 工具系统只保留端侧常用工具：文件管理、系统信息、只读 Shell、简单脚本执行。

验收标准：

- 端侧发行版安装步骤清晰。
- 启动速度和内存占用可接受。

### 8.3 端侧内存优化

学习内容：

- KV Cache 动态裁剪。
- 滑动窗口推理。
- 上下文压缩。
- 低内存设备上的模型加载策略。

实现目标：

- 支持最大上下文窗口配置。
- 长会话自动摘要或裁剪旧消息。
- 对工具调用结果做摘要压缩。

验收标准：

- 长时间对话不会无限增长内存。
- 端侧设备能稳定完成多轮工具调用。

### 8.4 部署验证

目标设备：

- 树莓派 5 或同等级边缘设备。

测试场景：

- 本地文件检索。
- Shell 只读命令。
- 简单脚本辅助。
- 多轮上下文问答。

报告指标：

- token/s。
- 首 token 延迟。
- 峰值内存。
- CPU/GPU 占用。
- 功耗。
- 不同量化位宽下的质量与速度差异。

阶段交付物：

- `LlamaCppBackend`。
- pyclaw edge profile。
- 树莓派部署文档。
- 端侧性能基准报告。

## 9. 11 周综合时间表

| 周期 | 主题 | 主要目标 | 核心交付物 |
| --- | --- | --- | --- |
| 第 1 周 | Gateway 与会话 | FastAPI、WebSocket、SQLite 会话 | Gateway API、会话存储 |
| 第 2 周 | Agent Runtime 与工具 | ReAct loop、工具调用、记忆 | 最小 Agent 闭环 |
| 第 3 周 | vLLM 接入 | 统一推理接口、VLLMBackend | 本地推理后端 |
| 第 4 周 | KV Cache 隔离 | 多租户 metadata、缓存边界 | 缓存隔离设计与测试 |
| 第 5 周 | 优先级调度 | 高低优先级队列、抢占策略 | 调度实现与初步压测 |
| 第 6 周 | 性能基线 | vLLM 原生对比、指标采集 | 项目 1 性能报告 |
| 第 7 周 | 单模型量化 | AWQ/GPTQ/FP8 对比 | 量化实验报告 |
| 第 8 周 | 算子与调度优化 | attention/profiling/prefix cache | HighPerformanceBackend |
| 第 9 周 | 多模型路由 | ModelRouter、降级策略 | 多模型路由模块 |
| 第 10 周 | 集群与监控 | Docker、K8s、Prometheus、Grafana | 集群部署与仪表盘 |
| 第 11 周 | 端侧与总联调 | llama.cpp、裁剪、文档 | 端侧部署与完整项目报告 |

## 10. 每周学习方法

每周按以下节奏推进：

1. 周初明确本周目标和验收标准。
2. 前 2 天集中学习关键原理和源码。
3. 中间 3 天写最小实现，避免一开始追求完美抽象。
4. 最后 1-2 天补测试、记录实验数据、写文档。
5. 每周结束时输出一份小结：本周做了什么、遇到什么问题、下一步怎么做。

建议每个模块都保留三类材料：

- `design.md`：设计思路、接口、关键权衡。
- `benchmark.md`：测试环境、测试方法、指标结果。
- `implementation-notes.md`：踩坑、调试记录、后续 TODO。

## 11. 推荐学习顺序

优先顺序如下：

1. 先完成 pyclaw Agent 最小闭环，不要一开始就陷入 vLLM 源码。
2. 再接入 vLLM，建立可运行的本地推理后端。
3. 接着做多租户 KV Cache 和优先级调度，因为这是最贴合 Agent 场景的核心亮点。
4. 然后做单模型优化，形成推理性能深度。
5. 再做多模型服务平台，把单机能力扩展为工程化部署能力。
6. 最后做端侧裁剪，补齐 OpenClaw 本地优先和边缘部署故事。

这样安排的好处是每一步都能依赖前一阶段产物，最终形成一个连贯项目，而不是四个互不相关的实验。

## 12. 简历表达建议

项目名称可以写为：

> pyclaw：面向多租户 Agent 的 Python 编排框架与高性能本地推理系统。

可强调的技术点：

- 基于 FastAPI、WebSocket、SQLite 构建本地优先的 Agent Gateway 和会话系统。
- 实现 ReAct Agent Runtime、插件化工具系统、本地记忆和统一推理后端抽象。
- 基于 vLLM 定制多租户 KV Cache 隔离和多渠道优先级调度，提升多会话推理吞吐与缓存命中率。
- 构建 ModelRouter，实现技能/任务类型到多模型实例的自动路由、限流、熔断和降级。
- 集成 Prometheus、Grafana、OpenTelemetry，实现推理服务全链路可观测。
- 基于 llama-cpp-python 裁剪端侧发行版，实现 Agent 编排、推理、工具执行的边缘端闭环。
- 针对长上下文工具调用和代码生成场景，完成量化、attention、前缀缓存等性能优化实验。

## 13. 最终项目验收清单

功能验收：

- Gateway 支持 HTTP 与 WebSocket。
- Agent Runtime 支持 ReAct loop 和工具调用。
- 工具系统支持插件化加载和权限控制。
- SQLite 支持会话、消息、工具调用持久化。
- 本地向量记忆可检索并注入上下文。
- 推理后端支持外部 API、vLLM、llama.cpp 至少三种模式。
- ModelRouter 支持多模型注册、路由、降级。
- 端侧 profile 可独立运行。

性能验收：

- 有 vLLM 原生基线。
- 有多租户调度对比数据。
- 有 TTFT、TPOT、P50、P95、P99。
- 有 tokens/s、requests/s、显存占用。
- 有量化前后对比。
- 有端侧 token/s、内存、功耗数据。

文档验收：

- 核心架构文档。
- 推理后端接口文档。
- KV Cache 调度设计文档。
- ModelRouter 设计文档。
- K8s 部署文档。
- 端侧部署文档。
- 完整性能报告。

## 14. 风险与取舍

主要风险：

- vLLM 源码复杂，直接深改调度层成本较高。
- CUDA 算子优化学习曲线陡峭。
- 多模型集群部署依赖 GPU 资源。
- 端侧设备性能有限，完整 Agent 体验需要控制预期。

建议取舍：

- 第一版先通过 vLLM Python API 和外层调度包装实现可演示能力，再逐步深入 vLLM 内部 scheduler。
- 算子优化优先接入成熟实现和 profiling 分析，不必一开始手写完整 CUDA kernel。
- 集群平台可先用 Docker Compose 模拟多实例，再迁移到 Kubernetes。
- 端侧版本优先保证闭环完整，再优化速度。

## 15. 最小可展示 Demo

最终建议准备一个贯穿式 Demo：

1. 用户通过 WebSocket 进入一个 pyclaw 会话。
2. Gateway 记录 user_id、channel_id、session_id、priority。
3. Agent Runtime 根据问题选择工具或模型。
4. ModelRouter 根据任务类型选择本地 vLLM 模型。
5. 推理后端复用系统提示词和会话前缀缓存。
6. 如果请求为高优先级，即时消息优先被调度。
7. Agent 调用本地工具，工具结果回填上下文。
8. 最终回答流式返回用户。
9. Prometheus/Grafana 展示本次请求的延迟、tokens/s、模型实例、缓存命中和工具调用记录。
10. 同一套 Agent 在 edge profile 下可切换到 llama.cpp 本地量化模型运行。

这个 Demo 能同时体现 Agent 编排、推理调度、多模型路由、可观测和端侧能力，是 pyclaw 项目的核心展示路径。
