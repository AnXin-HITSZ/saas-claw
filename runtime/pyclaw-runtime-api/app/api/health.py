"""Health check endpoint."""

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/healthz")
def healthz():
    return {"status": "ok", "service": "pyclaw-runtime-api", "version": "0.1.0"}
