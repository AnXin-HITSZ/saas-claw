"""Application settings."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_prefix": "PYCLAW_", "case_sensitive": False}

    host: str = "0.0.0.0"
    port: int = 8091
    log_level: str = "INFO"


settings = Settings()
