"""Redis 持久化 checkpointer：主图/子图共享同一 RedisSaver 实例（app 启动后注入）。

langgraph-checkpoint-redis>=0.5 的 from_conn_string(...) 是装饰了 @contextmanager 的同步
上下文管理器（直接调用得到 _GeneratorContextManager，并非 saver 实例，compile 的类型校验会
拒绝它），且打开后不会自动 setup() 建索引。因此本文件提供：
  (a) open_saver() —— 同步上下文管理器：with 打开 → setup() 建索引 → 产出可用 saver；
  (b) saver 占位属性 —— 由 main.lifespan 在 with 块内赋值，graph 子图编译时动态读取。
"""
from contextlib import contextmanager

from langgraph.checkpoint.redis import RedisSaver

from .config import settings


@contextmanager
def open_saver():
    """打开 RedisSaver：with 进入 → 建索引，退出 → 关闭客户端连接。"""
    with RedisSaver.from_conn_string(settings.redis_url) as saver:
        saver.setup()
        yield saver


# 启动时由 main.lifespan 赋值（open_saver 的 with 块内）；graph 子图编译读取本属性。
saver = None