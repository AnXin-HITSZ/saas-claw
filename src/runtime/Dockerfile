# saas-claw runtime（Claw Pod）生产镜像
# 构建上下文必须是 src/runtime（使 app/ 为顶层可导入包 -> app.main:app）
# 入口无 __main__，须由 uvicorn 外部启动；端口 8000。
FROM python:3.12-slim AS runtime

# 只装 pip 依赖，纯 Python 栈（pymysql/oss2 无需系统库）
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# 先拷依赖清单，利用层缓存
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# 再拷应用代码
COPY app/ ./app/

# 非 root 运行
RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /workspace && chown -R appuser:appuser /workspace /app
USER appuser

EXPOSE 8000

# 无 HOST/PORT 环境变量，端口只能 CLI 指定；SSE 由应用自身处理
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
