"""Redis 持久化 checkpointer：主图/子图共享同一 AsyncRedisSaver 实例（app 启动后注入）。

langgraph-checkpoint-redis>=0.5 的两个 saver 分工：
- RedisSaver（同步）的 aget_tuple 等 async 方法只继承基类桩实现（raise NotImplementedError），
  仅适用于 sync 执行（invoke/stream）；本应用用 astream 流式执行，必须用 AsyncRedisSaver。
- AsyncRedisSaver.from_conn_string 是 @asynccontextmanager：async with 打开时 __aenter__
  自动执行 async setup() 建索引，退出时关闭连接。

因此本文件提供：
  (a) open_saver_cm() —— 异步上下文管理器，async with 后产出可用 AsyncRedisSaver；
  (b) saver 占位属性 —— 由 main.lifespan 在 with 块内赋值，graph 子图编译时动态读取。
"""
from langgraph.checkpoint.redis import AsyncRedisSaver

from .config import settings


def open_saver_cm():
    """返回 AsyncRedisSaver 的异步上下文管理器（进入即建索引，退出即关连接）。"""
    return AsyncRedisSaver.from_conn_string(settings.redis_url)


# 启动时由 main.lifespan 赋值（open_saver_cm 的 async with 块内）；graph 子图编译读取本属性。
saver = None