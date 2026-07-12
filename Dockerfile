FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    OPENCLAW_CHATDATA_DIR=/app/chatdata

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client \
    && rm -rf /var/lib/apt/lists/*

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
