# pyclaw 第二阶段 Docker 容器化实现记录

本文记录 `pyclaw` 在完成最小 FastAPI 服务入口之后的第二阶段工作：新增 `Dockerfile` 与 `.dockerignore`，让 `pyclaw-api` 可以作为容器镜像运行，并为后续 Kubernetes Deployment / Helm Chart 做准备。

## 1. 本阶段目标

第一阶段已经新增：

- `openclaw/api.py`
- `GET /healthz`
- `POST /v1/agent/run`
- `uvicorn openclaw.api:app --host 0.0.0.0 --port 8000`

第二阶段目标是把这个长驻 HTTP 服务封装进 Docker 镜像：

```bash
docker build -t pyclaw-api:dev .
docker run --rm -p 8000:8000 pyclaw-api:dev
```

验证：

```bash
curl http://localhost:8000/healthz
```

预期返回：

```json
{"status":"ok","service":"pyclaw-api"}
```

## 2. 本次新增文件

| 文件 | 作用 |
| --- | --- |
| `Dockerfile` | 定义 pyclaw API 服务镜像构建流程和默认启动命令。 |
| `.dockerignore` | 控制 Docker build context，避免把虚拟环境、chatdata、.env、测试缓存等内容复制进镜像。 |
| `docs/000/pyclaw-docker-containerization-implementation-notes.md` | 本阶段实现记录。 |

## 3. Dockerfile 设计

当前 Dockerfile：

```dockerfile
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    OPENCLAW_CHATDATA_DIR=/app/chatdata

WORKDIR /app

RUN useradd --create-home --shell /usr/sbin/nologin pyclaw

COPY pyproject.toml README.md ./
COPY openclaw ./openclaw

RUN python -m pip install --upgrade pip \
    && python -m pip install ".[all]" \
    && mkdir -p /app/chatdata \
    && chown -R pyclaw:pyclaw /app

USER pyclaw

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3).read()"

CMD ["uvicorn", "openclaw.api:app", "--host", "0.0.0.0", "--port", "8000"]
```

### 3.1 基础镜像

使用：

```dockerfile
FROM python:3.11-slim
```

原因：

1. 项目 `pyproject.toml` 要求 `requires-python >= 3.11`。
2. `slim` 镜像体积比完整 Python 镜像更小。
3. Debian slim 生态成熟，后续如果需要安装系统依赖也比较方便。

### 3.2 Python 运行环境变量

```dockerfile
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    OPENCLAW_CHATDATA_DIR=/app/chatdata
```

含义：

| 变量 | 作用 |
| --- | --- |
| `PYTHONDONTWRITEBYTECODE=1` | 不生成 `.pyc` 文件，减少容器运行时文件写入。 |
| `PYTHONUNBUFFERED=1` | Python 日志直接输出到 stdout/stderr，方便 Docker / K8s 收集日志。 |
| `PIP_NO_CACHE_DIR=1` | pip 不保留下载缓存，减小镜像层体积。 |
| `OPENCLAW_CHATDATA_DIR=/app/chatdata` | 默认 transcript/session 数据目录，后续 K8s 可以把 PVC 挂载到这里。 |

### 3.3 非 root 用户

```dockerfile
RUN useradd --create-home --shell /usr/sbin/nologin pyclaw
...
USER pyclaw
```

这样容器里的 API 服务不会以 root 身份运行。

这对 `pyclaw` 很重要，因为它未来可能具备：

- 文件操作能力
- shell 工具能力
- 网络访问能力
- 远程服务器自动化能力

非 root 并不能替代工具 guard 或 K8s 安全策略，但它是基础安全层。

### 3.4 复制源码与安装依赖

```dockerfile
COPY pyproject.toml README.md ./
COPY openclaw ./openclaw

RUN python -m pip install --upgrade pip \
    && python -m pip install ".[all]"
```

当前镜像安装 `.[all]`，包含：

- OpenAI provider 依赖
- FastAPI / Uvicorn API 服务依赖
- httpx 测试/客户端相关依赖

后续如果希望进一步减小生产镜像，可以改为：

```dockerfile
RUN python -m pip install ".[openai,api]"
```

或者在 `pyproject.toml` 中拆出更细的 `server` extra。

### 3.5 chatdata 目录

```dockerfile
RUN ... \
    && mkdir -p /app/chatdata \
    && chown -R pyclaw:pyclaw /app
```

`/app/chatdata` 是默认 transcript/session 存储目录。

在 K8s 中，如果继续使用文件存储，建议把 PVC 挂载到：

```text
/app/chatdata
```

这样 Pod 重启后 transcript 不会丢失。

### 3.6 端口

```dockerfile
EXPOSE 8000
```

`EXPOSE` 不是实际发布端口，它只是镜像元数据。

本地运行时仍需要：

```bash
docker run -p 8000:8000 pyclaw-api:dev
```

K8s 中则由 Service 把 8000 端口暴露给集群内访问。

### 3.7 健康检查

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3).read()"
```

这里使用 Python 标准库 `urllib.request`，没有额外安装 `curl`。

在 Docker 中，这会成为镜像级健康检查。

在 K8s 中，仍建议用 Kubernetes 原生探针：

```yaml
readinessProbe:
  httpGet:
    path: /healthz
    port: 8000
livenessProbe:
  httpGet:
    path: /healthz
    port: 8000
```

### 3.8 默认启动命令

```dockerfile
CMD ["uvicorn", "openclaw.api:app", "--host", "0.0.0.0", "--port", "8000"]
```

含义：

| 片段 | 说明 |
| --- | --- |
| `uvicorn` | ASGI Web 服务器。 |
| `openclaw.api:app` | 导入 `openclaw/api.py` 中的 FastAPI `app` 对象。 |
| `--host 0.0.0.0` | 监听容器内所有网络地址，容器外才能访问。 |
| `--port 8000` | 服务监听端口。 |

## 4. .dockerignore 设计

当前 `.dockerignore`：

```text
.git
.gitignore
.venv
__pycache__
*.py[cod]
*.pyo
*.pyd
.pytest_cache
.mypy_cache
.ruff_cache
.coverage
htmlcov
build
dist
*.egg-info
chatdata
.env
.env.*
!.env.example
docs
tests
*.log
```

重点说明：

| 条目 | 原因 |
| --- | --- |
| `.venv` | 本地虚拟环境很大，且不能直接用于 Linux 容器。 |
| `chatdata` | transcript/session 本地数据不应进入镜像，应挂 PVC 或运行时生成。 |
| `.env` / `.env.*` | 避免 API key、私钥等敏感信息进入镜像。 |
| `!.env.example` | 保留示例文件，但当前 Dockerfile 没有复制它。 |
| `docs` / `tests` | 生产镜像不需要文档和测试，减少 build context。 |
| `*.egg-info` | 本地 editable install 产物，不应进入镜像。 |

## 5. 本地构建与运行

在 `D:\project\pyclaw` 下执行：

```cmd
docker build -t pyclaw-api:dev .
```

运行 mock provider 测试服务：

```cmd
docker run --rm -p 8000:8000 pyclaw-api:dev
```

验证健康检查：

```cmd
curl http://localhost:8000/healthz
```

调用 mock provider：

```cmd
curl -X POST http://localhost:8000/v1/agent/run ^
  -H "Content-Type: application/json" ^
  -d "{\"prompt\":\"你好\",\"provider\":\"mock\",\"session_id\":\"demo\"}"
```

## 6. 使用 OpenAI provider 运行

本地 Docker 运行时可以通过环境变量注入：

```cmd
docker run --rm -p 8000:8000 ^
  -e OPENAI_API_KEY=你的_key ^
  -e OPENAI_MODEL=gpt-4.1-mini ^
  pyclaw-api:dev
```

然后调用：

```cmd
curl -X POST http://localhost:8000/v1/agent/run ^
  -H "Content-Type: application/json" ^
  -d "{\"prompt\":\"你好\",\"provider\":\"openai\",\"session_id\":\"demo\"}"
```

生产环境不要把 key 写入镜像，也不要写入 Dockerfile。K8s 中应使用 Secret 注入。

## 7. 持久化 chatdata

如果希望本地 Docker 运行时保留 transcript：

```cmd
mkdir chatdata-docker

docker run --rm -p 8000:8000 ^
  -v %cd%\chatdata-docker:/app/chatdata ^
  pyclaw-api:dev
```

Linux / macOS 写法：

```bash
mkdir -p chatdata-docker

docker run --rm -p 8000:8000 \
  -v "$PWD/chatdata-docker:/app/chatdata" \
  pyclaw-api:dev
```

K8s 阶段对应的就是 PVC：

```text
PVC -> mountPath: /app/chatdata
```

## 8. 与 Kubernetes 的衔接

有了镜像后，K8s Deployment 只需要围绕以下信息建模：

| 项 | 值 |
| --- | --- |
| containerPort | `8000` |
| command | 使用镜像默认 `CMD` 即可 |
| health path | `/healthz` |
| config env | `OPENAI_MODEL`、`OPENCLAW_CHATDATA_DIR` 等 |
| secret env | `OPENAI_API_KEY` |
| volume mount | `/app/chatdata` |

后续 Helm Chart 的 `values.yaml` 可以抽象出：

```yaml
image:
  repository: pyclaw-api
  tag: dev

service:
  port: 8000

env:
  OPENAI_MODEL: gpt-4.1-mini
  OPENCLAW_CHATDATA_DIR: /app/chatdata

secretEnv:
  OPENAI_API_KEY: ""

persistence:
  enabled: true
  mountPath: /app/chatdata
  size: 5Gi
```

## 9. 安全注意事项

当前镜像做了基础安全处理：

1. 非 root 用户运行。
2. `.env` 不进入镜像。
3. `chatdata` 不进入镜像。
4. API 默认 `shell_approval=deny`。

但这还不等于生产安全。部署到公网或生产环境前还需要：

1. API 鉴权。
2. Ingress TLS。
3. NetworkPolicy。
4. K8s Secret 管理。
5. 工具调用审计。
6. shell/file/network guard 策略复核。
7. Pod Security Context，例如 `runAsNonRoot`、`readOnlyRootFilesystem` 等。

## 10. 当前边界

本阶段只完成容器化文件，不包含：

1. 实际 Docker build 验证。
2. Docker Compose。
3. Helm Chart。
4. K8s YAML。
5. 镜像推送到镜像仓库。

如果当前开发机没有 Docker Desktop 或网络无法拉取 `python:3.11-slim`，可以先只提交 Dockerfile，等具备 Docker 环境后再执行 build 验证。

## 11. 验证

代码级验证：

```powershell
py -m compileall openclaw tests
py -m unittest discover -s tests
```

容器级验证建议：

```cmd
docker build -t pyclaw-api:dev .
docker run --rm -p 8000:8000 pyclaw-api:dev
curl http://localhost:8000/healthz
```

## 12. 下一步

下一步建议进入 Helm Chart 初始化：

```text
helm/
  pyclaw/
    Chart.yaml
    values.yaml
    templates/
      deployment.yaml
      service.yaml
      ingress.yaml
      configmap.yaml
      secret.yaml
      pvc.yaml
```

也可以先做一次本地 Docker build/run 验证，再进入 Helm。
