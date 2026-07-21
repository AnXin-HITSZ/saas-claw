# 工具审批风险阈值可配置

> 状态：待实现 | 日期：2026-07-21

## 现状

control-plane 中工具审批的决策逻辑硬编码在 `runtime/openclaw/tools/approval_hooks.py`：

```python
risk = getattr(tool.metadata, "risk", "low") or "low"
if risk == "low":
    return ToolExecutionDecision(status="ALLOW")
# risk != low → PENDING_APPROVAL → 回叫 Spring
```

所有 `risk="low"` 的工具在 Python 侧直接放行，不走 Spring。其余 risk 等级挂起等待用户审批。

## 问题

用户无法按自身需求调整审批严格程度。有的用户想审核所有文件写入，有的用户只需审核 call_agent 这类跨 Agent 操作。

## 方案

将硬编码比较改为可配置的阈值，URL 路径模式匹配：

### 1. 风险等级量化

| 等级 | 数值 | 典型工具 |
|------|------|------|
| `low` | 1 | workspace_info, list_files, read_file, write_file, apply_patch |
| `medium` | 2 | call_agent, request_agent_install, discover_agents |
| `high` | 3 | （暂无，预留给 destroy_workspace 等危险操作） |

### 2. 判断逻辑

```
tool_risk_value >= threshold_value → 回叫 Spring（可能需要审批）
tool_risk_value <  threshold_value → Python 本地直接执行（0ms）
```

阈值默认 `medium`，保持现有行为不变。

### 3. 阈值来源

请求链路中传递：

```
前端 Claw 设置页 → Spring AgentServiceImpl → SaasClawAgentRunRequest
    → control-plane ApprovalRuntimeContext → ApprovalToolHooks.before_tool_call()
```

## 改动范围

| 层 | 文件 | 改动 |
|------|------|------|
| Python | `tools/approval_hooks.py` | 硬编码 `risk == "low"` → 读 `request_context.approval_threshold` |
| Python | `tools/approval.py` | `ApprovalRuntimeContext` 加 `approval_threshold: str` 字段 |
| Spring | `dto/SaasClawAgentRunRequest.java` | 加 `approvalThreshold` 字段 |
| Spring | `service/impl/AgentServiceImpl.java` | 从 Claw 配置读取阈值传入请求 |
| 前端 | Claw 设置页 | 加下拉框：低 / 中（默认）/ 高 |

## 为什么不是"全部推到 Spring 判断"

低风险工具（如 read_file）在每一轮 Agent 对话中被频繁调用（3-5 次/轮）。如果每次都要经过 Spring HTTP 往返（ClusterIP 约 5-20ms），10 轮对话累计增加 ~400ms，且安全收益为零——沙箱隔离（K8s namespace + path_guard + filesystem limits）才是真正的执行安全层，Spring 再审一遍不会得出不同结论。

用户选择 `threshold=low` 时主动承担这份延迟，换更细粒度的审批控制。默认 `medium` 不增加任何延迟。

## 数据库变更

`claw_config` 表（或等价表）加一列：

```sql
ALTER TABLE claw_config ADD COLUMN approval_threshold VARCHAR(10) DEFAULT 'medium';
```
