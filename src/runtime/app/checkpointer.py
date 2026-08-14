"""Redis 持久化 checkpointer：主图/子图共享同一 RedisSaver 单例。"""
from langgraph.checkpoint.redis import RedisSaver

from .config import settings


saver = RedisSaver.from_conn_string(settings.redis_url)