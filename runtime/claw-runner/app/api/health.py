"""Health check endpoint."""

import os
from fastapi import APIRouter

router = APIRouter(tags=["health"])

CLAW_ID = os.getenv("PYCLAW_CLAW_ID", "")
OWNER_USER_ID = os.getenv("PYCLAW_OWNER_USER_ID", "")


@router.get("/healthz")
def healthz():
    return {
        "status": "ok",
        "service": "claw-runner",
        "version": "0.1.0",
        "clawId": CLAW_ID,
        "ownerUserId": OWNER_USER_ID,
    }
