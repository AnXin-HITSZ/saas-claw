"""阿里云 OSS 访问：拉人格/Skill 文件（只读；写入一律走 backend）。"""
from urllib.parse import urlparse

import oss2

from .config import settings


def key_from_url(url: str) -> str:
    """OSS URL → object key：https://bucket.endpoint/xxx/yyy.md → xxx/yyy.md"""
    return urlparse(url).path.lstrip("/")


def read_object_text(key_or_url: str) -> str:
    """按 URL 或 key 读文件文本（人格组装 / Skill 指令注入用）"""
    obj = oss2.Bucket(
        oss2.Auth(settings.oss_access_key_id, settings.oss_access_key_secret),
        settings.oss_endpoint,
        settings.oss_bucket,
    ).get_object(key_from_url(key_or_url))
    return obj.read().decode("utf-8")