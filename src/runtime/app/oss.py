"""阿里云 OSS 访问：人格/Skill 文件读写。

读：人格组装 / Skill 指令注入（read_object_text）。
写：runtime 人格直写（write_object_text），仅由 update_persona 工具经白名单 + 归属校验后调用，
    key 按 agent/{agent_id}/{file_name} 构造。其余写入仍走 backend。
"""
from urllib.parse import urlparse

import oss2

from .config import settings


def key_from_url(url: str) -> str:
    """OSS URL → object key：https://bucket.endpoint/xxx/yyy.md → xxx/yyy.md"""
    return urlparse(url).path.lstrip("/")


def url_from_key(key: str) -> str:
    """object key → 公开读 URL：xxx/yyy.md → https://bucket.endpoint/xxx/yyy.md"""
    return f"https://{settings.oss_bucket}.{settings.oss_endpoint}/{key}"


def read_object_text(key_or_url: str) -> str:
    """按 URL 或 key 读文件文本（人格组装 / Skill 指令注入用）"""
    obj = oss2.Bucket(
        oss2.Auth(settings.oss_access_key_id, settings.oss_access_key_secret),
        settings.oss_endpoint,
        settings.oss_bucket,
    ).get_object(key_from_url(key_or_url))
    return obj.read().decode("utf-8")


def write_object_text(key: str, content: str, content_type: str = "text/markdown; charset=utf-8") -> None:
    """按 key 写文件文本（runtime 人格直写，覆盖语义）。

    key 由调用方构造（agent/{agent_id}/{file_name}），本函数不做路径约束；
    写权限面由调用工具的白名单（SOUL/IDENTITY/AGENTS.md）+ 归属校验收口。
    """
    oss2.Bucket(
        oss2.Auth(settings.oss_access_key_id, settings.oss_access_key_secret),
        settings.oss_endpoint,
        settings.oss_bucket,
    ).put_object(key, content.encode("utf-8"), headers={"Content-Type": content_type})