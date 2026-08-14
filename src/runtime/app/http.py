"""backend 程序通道 HTTP 客户端（httpx）：审批 / 人格文件 / 定时任务等 Agent 行为走此通道。"""
import httpx

from .config import settings


async def post_json(
    path: str,
    payload: dict,
    *,
    api_key: str = settings.backend_api_key,
) -> dict:
    """POST JSON 到 backend；返回 JSON 响应体。失败抛异常由调用方兜底。"""
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.post(f"{settings.backend_base_url}{path}", json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json() if resp.content else {}


async def post_multipart(
    path: str,
    fields: dict,
    files: dict,
    *,
    api_key: str = settings.backend_api_key,
) -> dict:
    """POST multipart（文件上传）到 backend。"""
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{settings.backend_base_url}{path}", data=fields, files=files, headers=headers)
        resp.raise_for_status()
        return resp.json() if resp.content else {}