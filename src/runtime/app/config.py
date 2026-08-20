from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Claw Pod 运行配置：MySQL / OSS / backend 审批通道 / 本 Pod 身份。"""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # ---- Redis（checkpointer 持久化；与 backend 同一实例）----
    # 连接串格式：redis://[:password@]host:port/db
    redis_url: str = "redis://localhost:6379/0"

    # ---- MySQL（与 backend 同库，只读业务配置 + 不写审批，审批走 HTTP）----
    mysql_host: str = "127.0.0.1"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = ""
    mysql_database: str = "saas_claw"

    # ---- 阿里云 OSS ----
    oss_endpoint: str = ""
    oss_access_key_id: str = ""
    oss_access_key_secret: str = ""
    oss_bucket: str = ""

    # ---- backend 审批通道（程序通道 API Key，同 authorization 表那把 sk-xxx）----
    backend_base_url: str = "http://backend:8080/api"  # 集群内 Service DNS（含 context-path /api）
    backend_api_key: str = ""  # Bearer sk-xxx

    # ---- 本 Pod 身份（K8s Deployment 注入）----
    claw_id: int = 0
    namespace: str = "claw-0"

    # ---- 沙箱工作区根（K8s Deployment 注入，须与 K8sProperties.workspaceRoot 一致）----
    # 沙箱工具只允许在此目录内操作；越界路径一律拒绝。
    workspace_root: str = "/workspace"

    # 模型默认值（被 model_config 表按 agent 覆盖）
    default_model: str = "deepseek-v4-flash"

    # ---- 路由模型（LLM 路由独立配置，与业务 Agent 解耦）----
    # 固定查 model_config 表 name=router_model 的记录（后台创建 name='router' 即可）
    router_model: str = "router"


settings = Settings()