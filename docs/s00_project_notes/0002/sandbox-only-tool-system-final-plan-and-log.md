# PyClaw 全量 Sandbox 工具系统改造方案与实施记录

> 日期：2026-07-16
> 目标：将用户侧工具系统彻底收口为 Claw sandbox 专用工具，不再保留 local / host / 管理员内部工具通道，不保留旧名兼容，不保留多工作区模式分支。

## 1. 改造目标

最终只保留一条用户工具链路：

```text
LLM -> 工具 schema -> executor -> ToolExecutionContext.metadata -> sandbox-runner API -> Claw PVC workspace
```

核心要求：

- 所有用户工具都必须服务于当前 Claw 的 sandbox
- 不再区分 `local` / `sandbox_runner`
- 不再保留 `SANDBOX_MODE_DENY_TOOLS` / `SANDBOX_MODE_ALLOW_TOOLS`
- 不再保留本地文件系统工具、shell 工具、web 工具、管理员内部通道
- 不保留旧名兼容，所有用户工具名称直接更新为最终形态
- Prompt 不依赖工具名前缀，按 resolved tools 动态生成

## 2. 最终架构

### 2.1 工具层

工具实现统一放在 `openclaw/tools/*`，但只保留 sandbox 相关能力：

- `workspace_info`
- `list_files`
- `read_file`
- `write_file`
- `apply_patch`
- 未来可扩展：`search_files`、`find_files`、`run_command`、`web_fetch`、`web_search`

工具命名不再依赖 `sandbox_` 前缀，真正边界由 catalog 的能力描述和 `execution_scope` 决定。

### 2.2 执行层

统一执行签名：

```python
execute(context: ToolExecutionContext, arguments: dict[str, Any])
```

其中：

- `context.metadata["sandbox_base_url"]` 指向当前 Claw 的 sandbox-runner
- `arguments` 只承载 LLM 传入的工具参数

### 2.3 运行时层

`openclaw/api.py` 仅作为 Python 侧入口：

- `/v1/tools/catalog`
- `/v1/tools/resolve`
- `/v1/agent/run`

它负责把当前 Claw 的运行上下文注入到 Agent，并将请求转发到 sandbox-runner。

### 2.4 服务层

`sandbox-runner` 是每个 Claw 的独立执行 Pod：

- 挂载该 Claw 自己的 PVC
- 提供 workspace 读写、patch 等 API
- 未来可扩展命令执行、搜索、网络访问等能力

## 3. 需要删除或收口的内容

### 3.1 Python 工具目录

删除或移出用户链路的工具：

- `read`
- `list_dir`
- `ls`
- `grep`
- `find`
- `write`
- `edit`
- `apply_patch`（旧本地实现）
- `shell`
- `exec`
- `web_fetch`
- `web_search`

### 3.2 策略层

删除：

- `workspace_mode`
- `web_access`
- `SANDBOX_MODE_DENY_TOOLS`
- `SANDBOX_MODE_ALLOW_TOOLS`
- local / sandbox 混合过滤逻辑

### 3.3 前后端协议字段

删除或替换：

- `workspaceMode`
- `webAccess`
- `workspaceOnly`
- 旧式 `workspace_modes`

改为只保留 sandbox 语义和能力描述。

## 4. 统一的能力模型

建议新增或收敛为以下字段：

- `executionScope = claw_sandbox`
- `profiles = (minimal, readonly, coding, messaging, full)` 中的精细子集
- `readonly`
- `requiresApproval`
- `promptHint`
- `userVisible`

不再依赖工具名前缀判断是否属于 sandbox。

## 5. profiles 细化建议

推荐按工具能力精细分配：

- `workspace_info`: `minimal`, `readonly`, `coding`, `messaging`, `full`
- `list_files`: `minimal`, `readonly`, `coding`, `messaging`, `full`
- `read_file`: `readonly`, `coding`, `full`
- `write_file`: `coding`, `full`
- `apply_patch`: `coding`, `full`

如果后续加入新 sandbox 工具，再按能力分配 profile，而不是简单全量开放。

## 6. Prompt 生成原则

Prompt Composer 不再硬编码“只能用 sandbox_* 工具”。

应改为根据当前 resolved tools 动态生成：

```text
当前工作区是当前 Claw 的专属沙箱。
你只能使用当前可用工具访问、修改或运行这个沙箱中的资源。
当前可用工具如下：
- list_files: ...
- read_file: ...
- write_file: ...
- apply_patch: ...
```

这样后续新增非 `sandbox_` 前缀工具时也不会失效。

## 7. 实施顺序

### 阶段 A：工具收口

1. 改 `openclaw/tools/catalog.py`
2. 只保留 sandbox 能力
3. 删除本地 / shell / web 工具目录或移出注册链路
4. 调整 `user_visible_catalog()` 仅返回 sandbox 工具

### 阶段 B：策略简化

1. 改 `openclaw/tools/policy.py`
2. 删除 local / sandbox 混合分支
3. 改 `openclaw/tools/resolver.py`
4. 按 capability + profile + readonly + allow/deny 过滤
5. 由 resolved tools 动态生成 prompt fragments

### 阶段 C：Python API 收口

1. 改 `openclaw/api.py`
2. 删除 `workspace_mode` / `web_access`
3. 删除 `SANDBOX_MODE_*`
4. 仅保留 `sandbox_base_url` 和 Claw 上下文字段

### 阶段 D：Spring Boot 收口

1. 改 `PyclawAgentRunRequest`
2. 改 `PyclawToolResolveRequest` / `PyclawToolResolveResponse`
3. 改 `EffectiveToolsRequest` / `EffectiveToolsResponse`
4. 改 `ToolCatalogEntryResponse`
5. 改 `ClawChatService`、`ToolCatalogService`、`AgentConfigService` 相关传参

### 阶段 E：前端收口

1. 工具目录页去掉 Web 开关
2. 只展示 sandbox 工具
3. Agent 配置页只允许选择 sandbox 工具
4. Prompt / 状态文案只保留 sandbox 语义

### 阶段 F：测试与部署

1. Python 单测补齐
2. Spring 测试更新
3. 前端构建通过
4. 重新构建镜像并部署到 K3s

## 8. 当前已完成的工作

### 8.1 sandbox 工具执行签名已修正

已将 `openclaw/tools/sandbox_workspace.py` 中的 sandbox 工具统一改为：

```python
execute(context, arguments)
```

并从 `context.metadata["sandbox_base_url"]` 读取当前 Claw 的 sandbox 地址。

### 8.2 已新增 sandbox 工具测试

已新增：

- `tests/test_sandbox_workspace_tools.py`

覆盖内容：

- sandbox 工具能从运行时上下文读取 `sandbox_base_url`
- `sandbox_apply_patch` 使用 `arguments` 和 `context.metadata` 的组合
- 缺少 runtime context 时明确报错

### 8.3 已验证

当前相关测试已通过：

- `tests.test_sandbox_workspace_tools`
- `tests.test_tool_executor`
- `tests.test_tool_catalog`
- `tests.test_tool_schema`

## 9. 当前仍需继续删除 / 改造的地方

### Python 侧

仍需收口：

- `openclaw/tools/catalog.py`
- `openclaw/tools/policy.py`
- `openclaw/tools/resolver.py`
- `openclaw/api.py`
- `openclaw/tools/fs/*`
- `openclaw/tools/shell/*`
- `openclaw/tools/web/*`
- 相关测试用例

### Spring 侧

仍需收口：

- `PyclawAgentRunRequest`
- `PyclawToolResolveRequest`
- `PyclawToolResolveResponse`
- `EffectiveToolsRequest`
- `ToolCatalogEntryResponse`
- `ClawChatService`
- `ToolCatalogService`
- `AgentConfigService`
- 相关实体 / 响应对象中的 `workspaceOnly`、`webAccess`、`workspaceMode`

### 前端侧

仍需收口：

- `ToolCatalogPage.vue`
- `AgentConfigPage.vue`
- 任何展示 Web 开关或 workspaceMode 的位置

## 10. 验收标准

最终应满足：

1. 前端工具目录里只看到 sandbox 工具
2. LLM 只能拿到 sandbox 工具 schema
3. `sandbox_base_url` 缺失时，Agent run 直接失败
4. 不再存在 local / host / shell / web 工具进入用户链路
5. 不再存在 `workspaceMode` / `webAccess` / `SANDBOX_MODE_*`
6. Prompt 动态根据 resolved tools 生成
7. 所有工具调用都只指向当前 Claw 的 sandbox-runner

## 11. 实施备注

- 这次不保留旧名兼容
- 这次不保留管理员内部通道
- 这次不增加浏览器里的受控终端
- 所有用户工具最终都必须回到 Claw sandbox

## 12. 结论

这次改造的本质不是“把工具前面统一加上 sandbox_ 前缀”，而是：

```text
删除 local 运行模型
收口所有工具到 Claw sandbox
用 catalog / capability / prompt fragments 统一驱动工具系统
```

后续任何新增工具，都必须先回答两个问题：

1. 它是否属于当前 Claw sandbox 能力？
2. 它是否应进入 LLM 可见的 tool catalog？

只有答案都明确，才允许进入注册链路。