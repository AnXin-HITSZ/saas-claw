# VS Code Remote SSH 访问阿里云 ECS SOP

## 1. 文档目标

本文档用于说明如何通过 VS Code 访问阿里云 ECS 云服务器，适用于日常远程开发、代码调试、服务器文件编辑和命令行操作。

本文档可以提交到 GitHub。所有涉及真实环境的信息都必须使用占位符，不得写入真实公网 IP、私钥、密码、AccessKey、Token 或服务器内网地址。

## 2. 适用范围

适用于以下场景：

- 本地电脑使用 Windows、macOS 或 Linux。
- 远程服务器为阿里云 ECS Linux 实例。
- 使用 VS Code 的 Remote - SSH 扩展连接远程服务器。
- 推荐使用 SSH 密钥登录，不推荐长期使用密码登录。

## 3. 敏感信息处理原则

提交到 GitHub 前，必须确认文档中只保留以下占位符：

| 占位符 | 含义 |
| --- | --- |
| `<ECS_PUBLIC_IP>` | ECS 公网 IP |
| `<REMOTE_USER>` | 远程登录用户，例如 `root`、`ubuntu`、`ecs-user` 或 `dev` |
| `<SSH_PORT>` | SSH 端口，默认通常是 `22` |
| `<KEY_NAME>` | 本地 SSH 私钥文件名 |
| `<LOCAL_PUBLIC_IP>` | 本地出口公网 IP，用于安全组白名单 |
| `<REMOTE_PROJECT_DIR>` | 远程服务器上的项目目录 |

不得提交以下内容：

1. 真实 ECS 公网 IP、内网 IP、域名或主机名。
2. SSH 私钥内容，例如 `id_ed25519`、`id_rsa`。
3. 服务器密码、数据库密码、API Key、Token。
4. 阿里云 AccessKey ID / AccessKey Secret。
5. 生产环境目录、账号、堡垒机地址等内部信息。

## 4. 前置条件

本地需要准备：

1. 已安装 VS Code。
2. 已安装 VS Code 扩展：Remote - SSH。
3. 本地终端可使用 `ssh` 命令。
4. 已拥有 ECS 登录权限。

阿里云侧需要准备：

1. ECS 实例已经运行。
2. ECS 有公网访问入口，或可通过 VPN / 堡垒机 / 跳板机访问。
3. 安全组允许本地访问 SSH 端口。
4. 远程 Linux 系统已启动 SSH 服务。

## 5. 阿里云安全组配置

进入阿里云控制台，找到对应 ECS 实例的安全组，添加入方向规则。

推荐规则：

| 协议 | 端口 | 来源 | 说明 |
| --- | --- | --- | --- |
| TCP | `<SSH_PORT>` | `<LOCAL_PUBLIC_IP>/32` | 只允许本机公网 IP 访问 SSH |

不推荐长期使用：

```text
0.0.0.0/0
```

如临时开放给所有来源，使用完后应立即收紧到固定 IP 白名单。

## 6. 本地生成 SSH 密钥

在本地终端执行：

```powershell
ssh-keygen -t ed25519 -C "aliyun-vscode"
```

推荐保存到：

```text
~/.ssh/<KEY_NAME>
```

示例占位写法：

```text
~/.ssh/aliyun_vscode_ed25519
```

注意：

- 私钥文件 `<KEY_NAME>` 不得提交到 GitHub。
- 公钥文件 `<KEY_NAME>.pub` 可复制到服务器的 `authorized_keys` 中。
- 如果本机已有可用密钥，也可以复用，但应确认权限边界清晰。

## 7. 将公钥添加到远程服务器

查看本地公钥：

```powershell
Get-Content $env:USERPROFILE\.ssh\<KEY_NAME>.pub
```

将输出的公钥内容添加到远程服务器目标用户的 `authorized_keys`。

登录远程服务器后执行：

```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh
echo "<PUBLIC_KEY_CONTENT>" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

注意：

- `<PUBLIC_KEY_CONTENT>` 只应替换为公钥内容，不要写入私钥。
- 如果使用非 `root` 用户，应确认是在该用户的 home 目录下操作。

## 8. 本地 SSH 连通性测试

在本地终端执行：

```powershell
ssh -i $env:USERPROFILE\.ssh\<KEY_NAME> -p <SSH_PORT> <REMOTE_USER>@<ECS_PUBLIC_IP>
```

首次连接时，终端可能提示确认主机指纹。确认 IP 和来源无误后输入：

```text
yes
```

连接成功后，说明本地到 ECS 的 SSH 通道可用。

## 9. 配置本地 SSH Config

编辑本地 SSH 配置文件：

```text
~/.ssh/config
```

Windows 常见路径：

```text
C:\Users\<LOCAL_USER>\.ssh\config
```

加入如下配置：

```sshconfig
Host aliyun-ecs-dev
  HostName <ECS_PUBLIC_IP>
  User <REMOTE_USER>
  Port <SSH_PORT>
  IdentityFile ~/.ssh/<KEY_NAME>
  IdentitiesOnly yes
```

配置完成后测试：

```powershell
ssh aliyun-ecs-dev
```

如果可以直接登录，则 VS Code 也可以使用该 Host 配置。

## 10. 使用 VS Code Remote - SSH 连接

在 VS Code 中执行：

1. 安装扩展：Remote - SSH。
2. 按 `Ctrl + Shift + P`。
3. 输入并选择：`Remote-SSH: Connect to Host...`。
4. 选择：`aliyun-ecs-dev`。
5. 首次连接时，VS Code 会在服务器上安装 VS Code Server。
6. 连接成功后，选择远程目录打开。

示例远程目录占位写法：

```text
<REMOTE_PROJECT_DIR>
```

例如：

```text
/home/<REMOTE_USER>/project
```

## 11. 推荐使用普通用户开发

不建议长期使用 `root` 作为日常开发用户。推荐创建普通用户，例如：

```bash
sudo adduser <REMOTE_USER>
sudo usermod -aG sudo <REMOTE_USER>
```

然后将 SSH 公钥添加到该用户的：

```text
/home/<REMOTE_USER>/.ssh/authorized_keys
```

对应 SSH Config：

```sshconfig
Host aliyun-ecs-dev
  HostName <ECS_PUBLIC_IP>
  User <REMOTE_USER>
  Port <SSH_PORT>
  IdentityFile ~/.ssh/<KEY_NAME>
  IdentitiesOnly yes
```

## 12. 常见问题排查

### 12.1 连接超时

检查：

1. ECS 是否运行。
2. 公网 IP 是否正确。
3. 安全组是否放行 `<SSH_PORT>`。
4. 本地出口公网 IP 是否在安全组白名单内。
5. 服务器防火墙是否允许 SSH。

### 12.2 Permission denied

检查：

1. `<REMOTE_USER>` 是否正确。
2. `IdentityFile` 是否指向正确私钥。
3. 公钥是否已加入远程用户的 `authorized_keys`。
4. 远程 `.ssh` 目录权限是否正确。
5. 是否误用了另一个用户的 `authorized_keys`。

远程权限建议：

```bash
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
```

### 12.3 VS Code 连接失败但命令行 SSH 成功

检查：

1. VS Code Remote - SSH 是否读取了正确的 SSH config。
2. `Host` 名称是否与 VS Code 选择的一致。
3. 服务器磁盘空间是否充足。
4. 远程 home 目录是否可写。
5. 删除远程旧的 VS Code Server 后重试。

远程可检查目录：

```bash
ls -la ~/.vscode-server
```

如确认需要清理，可在远程服务器执行：

```bash
rm -rf ~/.vscode-server
```

执行删除前应确认当前用户和目录无误。

## 13. 安全建议

1. 优先使用 SSH 密钥登录。
2. 安全组只开放本地公网 IP。
3. 避免将 SSH 端口长期暴露给所有来源。
4. 不要将私钥、密码、Token 写入仓库。
5. 日常开发使用普通用户，必要时再使用 `sudo`。
6. 定期检查 `~/.ssh/authorized_keys`，移除不再使用的公钥。
7. 生产环境建议配合堡垒机、VPN、MFA 或访问审计。

## 14. GitHub 提交前检查清单

提交前执行人工检查：

1. 文档中没有真实公网 IP。
2. 文档中没有真实用户名和生产主机名。
3. 文档中没有 SSH 私钥内容。
4. 文档中没有密码、Token、AccessKey。
5. 文档中所有环境相关内容均使用 `<...>` 占位符。

可使用如下命令辅助检查：

```powershell
git diff -- docs/000/vscode-remote-ssh-aliyun-ecs-sop.md
```

确认只包含 SOP 文档和占位符后，再提交到 GitHub。

