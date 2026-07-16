# Sandbox-Only Tool System Implementation Log

## 目标

把 PyClaw 的用户工具链收敛为单一沙箱模型：

- 只保留面向 Claw sandbox 的工具
- 不再通过 workspace_mode / web_access 区分本地和沙箱
- prompt 由 resolved tools 动态生成
- 前后端只展示并下发 sandbox 工具

## 本次已完成

### Python 侧

- `openclaw/tools/sandbox_workspace.py`
  - 修复写文件工具的请求体，改为发送 JSON `{ "content": ... }`
  - 对路径参数进行 URL 编码，避免空格和特殊字符出错
- `openclaw/tools/catalog.py`
  - 目录中只保留 sandbox 工具：
    - `sandbox_workspace_info`
    - `sandbox_list_files`
    - `sandbox_read_file`
    - `sandbox_write_file`
    - `sandbox_apply_patch`
  - 删除 local / web 工具的用户可见目录入口
- `openclaw/tools/resolver.py`
  - 删除 `workspace_mode` / `web_access` 分支
  - 只根据 profile / allow / deny / also_allow / readonly 解析工具
  - 动态生成 sandbox prompt fragment
- `openclaw/api.py`
  - 去掉工具 resolve / agent run 请求里的旧模式字段
  - 目录与 resolve 响应改为 sandbox-only
- `openclaw/agents/runtime_config_client.py`
  - 删除对 `workspaceOnly` / `webAccess` 的解析

### Spring 侧

- `ToolCatalogService`
  - resolve 请求改为 sandbox-only
  - effective tools 只回传 sandbox catalog
- `ClawChatService`
  - 去掉 `workspaceMode` 传递
  - 只传 `sandboxBaseUrl` 和 resolved tools
- DTO / record
  - `PyclawToolResolveRequest`
  - `PyclawToolResolveResponse`
  - `PyclawToolCatalogEntry`
  - `PyclawAgentRunRequest`
  - `EffectiveToolsRequest`
  - `ToolCatalogEntryResponse`
  - `AgentToolPolicyRequest`
  - `AgentToolPolicyResponse`
  - `AgentRuntimeToolPolicyResponse`
  - 统一改成 sandbox-only 契约
- `ToolPolicyGrantValidator`
  - 去掉 web 工具 grant 分支
- `AgentToolPolicyEntity`
  - 删除 `workspaceOnly` / `webAccess` 持久化字段

### 前端

- `ToolCatalogPage.vue`
  - 移除 “允许 Web 工具” 开关
  - 只保留 profile 选择与 sandbox 工具列表
- `AgentConfigPage.vue`
  - 移除 Web 开关
  - Agent 表单只保留与当前 sandbox-only 工具策略相关的字段

## 验证

### Python 单测

已通过：

- `tests.test_tool_catalog`
- `tests.test_tool_policy`
- `tests.test_sandbox_workspace_tools`
- `tests.test_tool_executor`

### Python 语法检查

已通过：

- `openclaw/api.py`
- `openclaw/tools/catalog.py`
- `openclaw/tools/resolver.py`
- `openclaw/agents/runtime_config_client.py`
- `openclaw/tools/sandbox_workspace.py`

## 当前状态

用户可见的工具系统已经切换到 sandbox-only：

- 目录只展示 sandbox 工具
- resolve 只解析 sandbox 工具
- prompt 只注入 sandbox 工具说明
- 前端不再暴露 Web 工具开关

## 后续仍可继续收口

如果要进一步清理历史包袱，还可以继续做：

- 删除不再被 active runtime 使用的旧 local / shell / web 工具实现
- 进一步收拢与 shell approval 相关的遗留配置
- 给 Spring 后端补一轮完整编译验证