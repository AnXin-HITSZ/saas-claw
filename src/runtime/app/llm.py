"""LLM 接入：按 model_config 构建 ChatOpenAI（OpenAI 兼容，endpoint 可指向任意供应商）。"""
from langchain_openai import ChatOpenAI
from pydantic import SecretStr


def build_chat_model(model_config: dict) -> ChatOpenAI:
    """model_config：prepare 节点物化的 {model_name, endpoint, api_key, temperature, max_tokens}"""
    return ChatOpenAI(
        model=model_config["model_name"],
        api_key=SecretStr(model_config["api_key"]) if model_config.get("api_key") else None,
        base_url=model_config["endpoint"],
        temperature=model_config["temperature"],
        max_tokens=model_config["max_tokens"],
        timeout=60,
        streaming=True,
    )