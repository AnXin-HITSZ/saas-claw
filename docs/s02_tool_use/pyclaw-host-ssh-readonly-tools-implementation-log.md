# pyclaw Host SSH 只读工具实现记录

更新时间：2026-07-13

## 背景

本次实现基于 `pyclaw` API Pod 已经可以通过 Kubernetes Secret 挂载 SSH 私钥、`known_hosts` 和连接参数，并已在 ECS/K3s 中验证容器内可以执行：

```text
which ssh -> /usr/bin/ssh
ssh ... -- whoami -> pyclaw-ops
```

第一阶段目标不是开放任意宿主机命令，而是提供少量只读白名单工具，让 Agent 可以读取宿主机基础状态。

## 实现范围

新增三个只读工具：

```text
host_uname -> uname -a
host_df    -> df -h
host_free  -> free -h
```

明确不实现：

```text
host_shell(command)
任意 SSH 命令透传
写操作或重启类运维动作
privileged Pod
宿主机根目录挂载
Docker/containerd socket 挂载
```

## 代码改动

新增文件：

```text
openclaw/tools/host_ssh.py
tests/test_host_ssh_tools.py
docs/s02_tool_use/pyclaw-host-ssh-readonly-tools-implementation-log.md
```

修改文件：

```text
openclaw/tools/catalog.py
.gitignore
```

## HostSshClient

`HostSshClient` 从环境变量读取连接配置：

```text
HOST_SSH_HOST
HOST_SSH_PORT
HOST_SSH_USERNAME
HOST_SSH_KEY_PATH
HOST_SSH_KNOWN_HOSTS_PATH
```

调用 SSH 时使用：

```python
asyncio.create_subprocess_exec(...)
```

而不是：

```python
asyncio.create_subprocess_shell(...)
```

原因是 `create_subprocess_exec` 将程序和参数分开传递，不经过本地 shell 解释，避免本地命令注入面。远程命令也不接受用户输入，只使用工具内部写死的白名单参数。

SSH 参数固定包含：

```text
-o BatchMode=yes
-o UserKnownHostsFile=$HOST_SSH_KNOWN_HOSTS_PATH
-o StrictHostKeyChecking=yes
-i $HOST_SSH_KEY_PATH
-p $HOST_SSH_PORT
```

## ToolPolicy 接入

三个工具注册到核心 catalog：

```text
section_id=host
profiles=("full",)
tags=("host", "ssh", "runtime", "readonly")
risk=low
workspace_only=false
```

这意味着：

```text
普通 coding profile 默认不会暴露 host_* 工具
full profile 会暴露
coding profile 可通过 tools_also_allow=group:host 显式开放
```

这样可以避免 Host SSH 能力意外进入普通 Agent，同时保留通过 Agent 配置定向开启的能力。

## 输出格式

工具返回 JSON payload：

```json
{
  "command": ["uname", "-a"],
  "exit_code": 0,
  "stdout": "...",
  "stderr": "",
  "timed_out": false,
  "timeout_seconds": 30,
  "max_chars": 20000,
  "host": "8.135.60.136",
  "port": 22,
  "username": "pyclaw-ops"
}
```

不会返回私钥内容，也不会读取私钥文件内容。

## 安全处理

`.gitignore` 新增：

```text
known_hosts
pyclaw_host_ops_ed25519*
```

真实 SSH 材料应保存在 ECS 仓库外目录，例如：

```text
/opt/pyclaw-secrets/host-ssh/
```

再通过 Kubernetes Secret 挂载到 Pod。

## 测试

新增测试覆盖：

```text
HostSshClient 使用 create_subprocess_exec 且参数分离
缺少 HOST_SSH_* 环境变量时报错
host_uname 只执行固定命令 ["uname", "-a"]
coding profile 默认不暴露 host_*，tools_also_allow=group:host 后可见
```

测试通过 mock `asyncio.create_subprocess_exec`，不会真实连接 ECS。
## 前端与管理权限补充

检查前端后确认，`pyclaw-web` 的 Agents 页面已经通过 `/api/tools/catalog` 动态加载工具目录，`ToolPicker` 会自动展示新增的 `host_uname`、`host_df`、`host_free` 以及 `group:host`，因此不需要新增独立的 Host SSH 页面或专门表单。

需要补充的是授权入口和后端边界：

```text
pyclaw-web/src/App.vue
- AuthorityPicker 的工具授权组新增 tool:grant:host

spring-backend/.../ToolPolicyGrantValidator.java
- host_uname / host_df / host_free / group:host 需要 tool:grant:host

spring-backend/.../BootstrapDataInitializer.java
- bootstrap admin 默认合并 tool:grant:host，避免管理员升级后无法授予 host 工具
```

这样前端、用户/Token 权限、Agent 保存校验保持一致：有 `tool:grant:host` 的操作者才能把 host 工具加入 Agent 的 `toolsAllow` 或 `toolsAlsoAllow`。

推荐 UI 使用方式：

```text
1. Users 或 Tokens 页面给操作者增加 tool:catalog:read 与 tool:grant:host。
2. Tools 页面确认 Host 工具出现在 catalog 中。
3. Agents 页面编辑目标 Agent：
   - Profile 可保持 coding；
   - Also Allow 选择 group:host，或只选择 host_uname / host_df / host_free。
4. 保存 Agent 后通过 Route 或 Agent Playground 验证工具调用。
```
## Agents 页面 group:host 选择补充

后续验证时发现 Agents 页面只能选择单个工具，不能直接选择 `group:host`。原因有两个：

```text
1. Spring Backend 的 /api/tools/catalog 还没有同步 host_uname / host_df / host_free。
2. 前端 ToolPicker 只按 section 展示单个工具，没有把 group:<section> 作为可选项展示。
```

已补充：

```text
spring-backend/.../ToolCatalogService.java
- catalog 增加 host_uname / host_df / host_free，sectionId=host
- 现有 groups() 会自动生成 group:host

pyclaw-web/src/App.vue
- ToolPicker 在每个 section 顶部展示 group:<section>
- host section 出现后可直接勾选 group:host
```

配置运维 Agent 时，推荐在 `Also Allow` 中勾选 `group:host`；若希望更细粒度，也可以只勾选 `host_uname`、`host_df`、`host_free` 中的一部分。
