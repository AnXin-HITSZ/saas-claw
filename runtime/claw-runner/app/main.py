"""Claw Runner — FastAPI data plane (isolated execution environment)."""

from fastapi import FastAPI

from app.api.health import router as health_router

app = FastAPI(title="Claw Runner", version="0.1.0")

app.include_router(health_router)

# Phase 2 will include:
# from app.api.workspace import router as workspace_router
# from app.api.tools import router as tools_router
# from app.api.commands import router as commands_router
