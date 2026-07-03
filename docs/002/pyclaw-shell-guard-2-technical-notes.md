# pyclaw Shell Guard 2.0 技术记录

## 背景

上一版 shell guard 主要依赖：

```text
readonly 上下文直接拒绝 shell
空命令 / NUL / 超长命令检查
危险命令正则
超时限制
输出限制
```

这能挡住明显危险命令，但还不具备系统化的命令理解能力。本次继续实现以下方向：

```text
命令 token 化
命令 AST / shell parser
分平台策略：cmd / PowerShell / bash
只读命令识别
mutation 命令识别
用户审批机制
真实 OS sandbox 抽象
```

本次目标不是承诺“正则就能安全执行任意 shell”，而是把 shell 安全层拆成可测试、可扩展的多层结构。

## 文件结构

新增文件：

```text
openclaw/tools/shell/parser.py
openclaw/tools/shell/guard.py
openclaw/tools/shell/approval.py
openclaw/tools/shell/sandbox.py
```

改造文件：

```text
openclaw/tools/shell/exec.py
openclaw/tools/results.py
```

新增测试：

```text
tests/test_shell_parser.py
tests/test_shell_tool.py
```

## 1. 命令 token 化

文件：

```text
openclaw/tools/shell/parser.py
```

核心入口：

```python
def tokenize_command(command: str, dialect: ShellDialect) -> tuple[str, ...]:
    ...
```

支持的 dialect：

```python
ShellDialect = Literal["cmd", "powershell", "bash"]
```

### bash

bash 使用 Python 标准库 `shlex.split(..., posix=True)`：

```python
tokenize_command('rg "hello world" docs', "bash")
# -> ("rg", "hello world", "docs")
```

### cmd / PowerShell

Windows shell 规则和 bash 不同，所以使用轻量 tokenizer：

```python
def tokenize_windows_like(command: str, dialect: ShellDialect) -> list[str]:
    ...
```

它处理：

```text
空白分隔
单引号 / 双引号
cmd 转义符 ^
PowerShell 转义符 `
未闭合 quote 报错
```

当前不是完整 PowerShell AST，只是安全分类需要的 token 层。

## 2. 命令 AST / shell parser

文件：

```text
openclaw/tools/shell/parser.py
```

核心数据结构：

```python
@dataclass(frozen=True)
class ShellCommandSegment:
    raw: str
    argv: tuple[str, ...]
    redirects: tuple[str, ...] = ()
    connector_after: str | None = None

@dataclass(frozen=True)
class ShellCommandAst:
    raw: str
    dialect: ShellDialect
    segments: tuple[ShellCommandSegment, ...]
    parse_errors: tuple[str, ...] = ()
```

解析入口：

```python
def parse_shell_command(command: str, *, dialect: ShellDialect | None = None) -> ShellCommandAst:
    ...
```

解析示例：

```python
parse_shell_command('git status && rg "hello"', dialect="bash")
```

得到的 AST 语义：

```text
raw: git status && rg "hello"
dialect: bash
segments:
  1. argv=("git", "status"), connector_after="&&"
  2. argv=("rg", "hello"), connector_after=None
```

当前识别控制符：

```text
&&
||
|
;
```

当前识别 redirect token：

```text
>
>>
1>
2>
<
```

如果一个本来只读的命令带 redirect，例如：

```cmd
echo hello > out.txt
```

分类时会升级为 mutation，因为 redirect 可能写文件。

## 3. 分平台策略

文件：

```text
openclaw/tools/shell/parser.py
```

入口：

```python
def detect_shell_dialect(command: str = "", *, explicit: str | None = None, platform: str | None = None) -> ShellDialect:
    ...
```

规则：

1. 如果参数显式传入 `shell`，优先使用：

```json
{"shell": "powershell"}
```

2. 如果命令以明显 shell 前缀开头，自动识别：

```text
powershell ... -> powershell
pwsh ...       -> powershell
bash ...       -> bash
sh ...         -> bash
wsl ...        -> bash
```

3. 否则根据平台推断：

```text
Windows -> cmd
其他系统 -> bash
```

`exec` / `shell` 工具 schema 新增：

```json
"shell": {"type": "string", "enum": ["cmd", "powershell", "bash"]}
```

注意：这个字段是 parser dialect，不等于强制切换系统 shell。当前实际执行仍然使用 `subprocess.run(..., shell=True)`，parser dialect 用于安全分类。

## 4. 只读 / mutation / dangerous / unknown 分类

文件：

```text
openclaw/tools/shell/guard.py
```

核心类型：

```python
CommandSafety = Literal["readonly", "mutation", "dangerous", "unknown"]

@dataclass(frozen=True)
class ShellCommandClassification:
    safety: CommandSafety
    reasons: tuple[str, ...]
    ast: ShellCommandAst
    requires_approval: bool
```

入口：

```python
def classify_shell_command(command: str, *, dialect: str | None = None) -> ShellCommandClassification:
    ...
```

### readonly

示例：

```text
echo hello
git status
git diff
rg pattern
ls
pwd
where python
```

这些命令不会被认为会修改外部状态。

### mutation

示例：

```text
git add .
git commit
mkdir dir
copy a b
mv a b
set-content file text
echo hello > out.txt
```

这些命令可能修改文件、依赖、仓库状态等。

### dangerous

示例：

```text
rm -rf .
git reset --hard
git clean -fd
Remove-Item -Recurse -Force
format C:
shutdown
reg delete ...
```

这些命令默认直接拒绝，不进入用户审批。

### unknown

示例：

```text
py -m pip install demo
python -c "..."
未知可执行文件
```

unknown 不是说一定危险，而是当前 catalog 无法稳定判断。默认 auto 模式下 unknown 需要显式审批，否则拒绝。

## 5. execute_shell 的新流程

文件：

```text
openclaw/tools/shell/exec.py
```

新执行顺序：

```text
1. 读取 command
2. detect_shell_dialect
3. classify_shell_command
4. validate_shell_command
5. readonly context 检查
6. approval 检查
7. workspace cwd 解析
8. sandbox 策略检查
9. timeout / max_chars 规范化
10. subprocess.run
```

关键变化：

上一版：

```text
readonly=True -> 所有 shell 都拒绝
```

新版：

```text
readonly=True + readonly command -> 允许
readonly=True + mutation/unknown/dangerous -> 拒绝
```

例如：

```cmd
pyclaw --tool-profile readonly "执行 echo hello"
```

如果 Agent 调用 `exec(command="echo hello")`，现在可以通过，因为它被分类为 readonly。

但是：

```cmd
exec(command="git add .")
```

会被 readonly 上下文拒绝。

## 6. 用户审批机制

文件：

```text
openclaw/tools/shell/approval.py
```

核心类型：

```python
ShellApprovalMode = Literal["auto", "require", "deny"]

@dataclass(frozen=True)
class ShellApprovalRequest:
    command: str
    safety: str
    reasons: tuple[str, ...]
    tool_name: str
    session_id: str | None = None

@dataclass(frozen=True)
class ShellApprovalDecision:
    approved: bool
    reason: str
    mode: ShellApprovalMode
```

入口：

```python
def resolve_shell_approval(...) -> ShellApprovalDecision:
    ...
```

审批配置来自：

```python
ToolExecutionContext.metadata
```

支持字段：

```python
metadata={
    "shell_approval_mode": "auto" | "require" | "deny",
    "approved_shell_commands": {"git add ."},
    "shell_approval_callback": callable,
}
```

### auto 模式

默认模式。

```text
readonly  -> 自动允许
mutation  -> 自动允许
unknown   -> 拒绝，要求显式审批
dangerous -> 拒绝，不进入审批
```

### require 模式

```text
readonly  -> 自动允许
mutation  -> 需要 approved_shell_commands 或 callback
unknown   -> 需要 approved_shell_commands 或 callback
dangerous -> 拒绝，不进入审批
```

### deny 模式

```text
readonly  -> 自动允许
其他      -> 拒绝
```

### callback

调用方可以传入：

```python
def approve(request: ShellApprovalRequest) -> bool:
    return request.command == "git add ."
```

然后：

```python
make_base_context(
    metadata={
        "shell_approval_mode": "require",
        "shell_approval_callback": approve,
    }
)
```

这就是后续接 CLI 交互确认或 GUI 审批弹窗的位置。

## 7. 真实 OS sandbox 抽象

文件：

```text
openclaw/tools/shell/sandbox.py
```

核心类型：

```python
ShellSandboxMode = Literal["none", "workspace", "real_os"]

@dataclass(frozen=True)
class ShellSandboxDecision:
    allowed: bool
    mode: ShellSandboxMode
    reason: str
    cwd: Path
```

入口：

```python
def resolve_shell_sandbox(*, cwd: Path, workspace_dir: Path, metadata: dict[str, Any]) -> ShellSandboxDecision:
    ...
```

支持 metadata：

```python
metadata={
    "shell_sandbox": "none" | "workspace" | "real_os",
    "real_os_sandbox_available": True | False,
}
```

当前行为：

```text
none      -> 不增加额外 OS sandbox，仅使用 subprocess
workspace -> 默认模式，使用 workspace cwd/path guard 作为边界
real_os   -> 如果没有外部 sandbox 标记，fail-closed 拒绝执行
```

为什么 `real_os` 默认拒绝？

因为真正 OS sandbox 需要依赖平台能力，例如：

```text
Windows Job Object / AppContainer / 低权限 token
Linux namespace / seccomp / cgroup / chroot / bubblewrap
macOS sandbox-exec / seatbelt profile
Docker / container runtime
```

当前 pyclaw 还没有接这些运行时。为了避免“看起来有 sandbox，实际上没有”的假安全感，`real_os` 模式默认 fail-closed：

```text
请求 real_os sandbox
  -> 当前 runtime 不支持
  -> 拒绝执行
```

后续如果接入真实 sandbox runner，可以在这个抽象层接入。

## 8. ToolResult details

shell 执行结果现在会带上结构化安全信息：

```json
{
  "classification": {
    "safety": "readonly",
    "reasons": ["echo is recognized as readonly"],
    "requiresApproval": false,
    "ast": {
      "dialect": "cmd",
      "segments": [
        {"argv": ["echo", "hello"], "connectorAfter": null}
      ]
    }
  },
  "approval": {
    "approved": true,
    "reason": "readonly command",
    "mode": "auto"
  },
  "sandbox": {
    "allowed": true,
    "mode": "workspace",
    "reason": "workspace cwd boundary",
    "cwd": "..."
  }
}
```

这对调试和 transcript detail 很重要：以后可以解释“为什么某个 shell 命令被允许或拒绝”。

## 9. blocked_result 扩展

文件：

```text
openclaw/tools/results.py
```

`blocked_result()` 新增可选 `details` 参数：

```python
def blocked_result(
    reason: str,
    *,
    denied_reason: str | None = None,
    details: dict[str, Any] | None = None,
) -> ToolResult:
    ...
```

旧调用方式不受影响：

```python
blocked_result("blocked", denied_reason="readonly")
```

新调用方式可以附加结构化信息：

```python
blocked_result(
    "unknown command requires approval",
    denied_reason="approval_required",
    details={"classification": ...},
)
```

## 10. 测试覆盖

新增 / 更新测试：

```text
tests/test_shell_parser.py
tests/test_shell_tool.py
```

覆盖内容：

```text
显式 dialect 检测
bash quote tokenization
compound command AST
readonly / mutation / dangerous / unknown 分类
redirect 使 readonly 命令升级为 mutation
readonly context 允许 readonly shell
readonly context 拒绝 mutation shell
危险命令拒绝
unknown 命令默认需要审批
require 审批模式阻止未批准 mutation
real_os sandbox 不可用时 fail-closed
```

验证命令：

```cmd
py -m compileall openclaw tests
py -m unittest discover -s tests
```

当前结果：

```text
Ran 85 tests
OK
```

## 当前边界

当前实现已经有：

```text
命令 token 化
轻量 AST
cmd / PowerShell / bash dialect
只读命令识别
mutation 命令识别
危险命令识别
审批接口
sandbox 策略抽象
real_os sandbox fail-closed
```

但还没有完整实现：

```text
完整 PowerShell AST
完整 cmd.exe 语义
完整 bash 语义
真实 OS 级隔离运行器
跨平台低权限用户执行
容器化执行
命令前用户交互确认 UI
```

后续建议：

1. 增加 CLI 参数：`--shell-approval require|auto|deny`。
2. 增加 CLI 交互审批 callback。
3. 增加真实 sandbox runner 接口。
4. Windows 优先接 Job Object / 低权限 token；Linux 优先接 Docker 或 bubblewrap。
5. 将 `classification` 写入 transcript detail，便于审计。
## 11. 命令前用户交互确认 UI

### 目标

命令前用户交互确认 UI 的目标是：当 Agent 准备执行非只读 shell 命令时，CLI 在真正执行前把命令展示给用户，并等待用户确认。

它不是写在 shell parser 或 classifier 里，而是接在审批 callback 上：

```text
Shell parser / classifier
  -> resolve_shell_approval()
  -> shell_approval_callback
  -> CLI 交互确认
  -> approved / rejected
```

这样做的好处是：

```text
底层工具系统不依赖具体 UI
CLI 可以用终端交互
未来 Web UI 可以换成弹窗
未来 API server 可以换成审批接口
```

### CLI 参数

新增参数：

```cmd
--shell-approval auto|require|deny
--yes
```

含义：

```text
--shell-approval auto
  默认模式。readonly 自动允许，mutation 自动允许，unknown 拒绝，dangerous 拒绝。

--shell-approval require
  readonly 自动允许，mutation / unknown 需要用户确认，dangerous 拒绝。

--shell-approval deny
  readonly 自动允许，mutation / unknown / dangerous 拒绝。

--yes
  对需要确认的 shell 命令自动批准。这个参数有风险，主要用于脚本化或测试环境。
```

### metadata 注入路径

CLI 构造工具上下文 metadata：

```python
def build_tool_context_metadata(args: argparse.Namespace) -> dict[str, Any]:
    metadata: dict[str, Any] = {
        "shell_approval_mode": args.shell_approval,
    }
    if args.yes:
        metadata["shell_approval_callback"] = approve_shell_without_prompt
    elif args.shell_approval == "require":
        metadata["shell_approval_callback"] = prompt_shell_approval
    return metadata
```

这份 metadata 会进入：

```text
CLI
  -> Agent(tool_metadata=...)
  -> LoopConfig.tool_metadata
  -> make_base_context(metadata=...)
  -> ToolExecutionContext.metadata
  -> execute_shell()
  -> resolve_shell_approval()
```

手动工具运行也走同一套：

```text
pyclaw --shell-approval require tools run exec '{"command":"git add ."}'
```

### 交互 UI

CLI callback：

```python
def prompt_shell_approval(request: ShellApprovalRequest) -> bool:
    print_shell_approval_summary(request)
    if not sys.stdin.isatty():
        print("pyclaw: shell command rejected because stdin is not interactive.", file=sys.stderr)
        return False
    try:
        print("Approve shell command? [y/N] ", end="", file=sys.stderr, flush=True)
        answer = input().strip().lower()
    except EOFError:
        print("pyclaw: shell command rejected because approval input ended.", file=sys.stderr)
        return False
    return answer in {"y", "yes"}
```

展示内容：

```text
pyclaw shell approval required
tool: exec
session: demo
safety: mutation
command:
git add .
reasons:
- git add mutates repository state
Approve shell command? [y/N]
```

用户输入：

```text
y / yes -> 批准执行
其它输入 -> 拒绝执行
EOF      -> 拒绝执行
非交互 stdin -> 拒绝执行
```

### stdout / stderr 设计

确认 UI 全部输出到 `stderr`。

原因：`stdout` 可能要输出机器可读 JSON，例如：

```cmd
pyclaw --json tools run exec '{"command":"git add ."}'
```

如果确认提示写到 stdout，会污染 JSON 输出。因此：

```text
stderr -> 人看的审批提示
stdout -> 命令结果 / JSON 结果
```

### Agent 对话中的效果

当用户运行：

```cmd
pyclaw --tool-profile full --shell-approval require "请执行 git add ."
```

如果模型调用：

```json
{"name":"exec","input":{"command":"git add ."}}
```

执行过程是：

```text
exec tool
  -> classify_shell_command("git add .")
  -> safety=mutation
  -> shell_approval_mode=require
  -> prompt_shell_approval
  -> 用户输入 y 才继续 subprocess.run
```

如果用户不批准，工具返回：

```json
{
  "status": "blocked",
  "deniedReason": "approval_required"
}
```

### 非交互环境

如果 pyclaw 在 CI、后台服务、Docker daemon 模式、无 TTY 环境中运行：

```python
sys.stdin.isatty() == False
```

则 require 模式会 fail-closed：

```text
需要审批，但没有交互终端
  -> 拒绝执行
```

这是为了避免后台环境中命令卡死等待输入。

如果确实要非交互批准，可以使用：

```cmd
--yes
```

但这会自动批准需要确认的 mutation / unknown 命令，风险较高。

### 测试覆盖

新增测试：

```text
tests/test_cli.py::CliTests.test_tools_run_shell_requires_interactive_approval
```

覆盖点：

```text
--shell-approval require
exec command=git add .
分类为 mutation
CLI 输出审批摘要到 stderr
非交互 stdin 下拒绝执行
stdout 仍保持 JSON 可解析
返回 deniedReason=approval_required
```

验证命令：

```cmd
py -m compileall openclaw tests
py -m unittest discover -s tests
```

当前结果：

```text
Ran 86 tests
OK
```
