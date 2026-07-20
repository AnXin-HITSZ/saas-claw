"""PyClaw Runtime API — FastAPI control plane (execution engine)."""

from fastapi import FastAPI

from app.config.logging import logging  # noqa: F401 — initialise logging
from app.api.health import router as health_router
from app.api.runs import router as runs_router
from app.api.approvals import router as approvals_router
from app.api.tools import router as tools_router

app = FastAPI(title="PyClaw Runtime API", version="0.1.0")

app.include_router(health_router)
app.include_router(runs_router)
app.include_router(approvals_router)
app.include_router(tools_router)
