"""Application settings for pyclaw-runtime-api control plane."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_prefix": "PYCLAW_", "case_sensitive": False}

    host: str = "0.0.0.0"
    port: int = 8090
    log_level: str = "INFO"

    # claw-runner connection
    runner_base_url: str = "http://localhost:8091"
    runner_timeout_seconds: int = 300

    # Internal auth token for cross-service calls
    internal_api_token: str = ""


settings = Settings()
