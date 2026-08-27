"""Embedding：OpenAI兼容 /v1/embeddings，维度由 EMBEDDING_DIM 控制（默认1024，DashScope text-embedding-v3）"""
from __future__ import annotations

from functools import lru_cache

from openai import OpenAI

from app.core.config import get_settings


@lru_cache
def _client() -> OpenAI:
    s = get_settings()
    return OpenAI(base_url=s.embedding_base_url, api_key=s.embedding_key or "EMPTY")


def embed_texts(texts: list[str]) -> list[list[float]]:
    s = get_settings()
    batch_size = 16
    vectors: list[list[float]] = []
    for i in range(0, len(texts), batch_size):
        batch = [t[:4000] for t in texts[i:i + batch_size]]
        try:
            resp = _client().embeddings.create(
                model=s.embedding_model, input=batch, dimensions=s.embedding_dim)
        except Exception:
            # 部分模型不支持 dimensions 参数
            resp = _client().embeddings.create(model=s.embedding_model, input=batch)
        vectors.extend(d.embedding for d in resp.data)
    return vectors


def embed_one(text: str) -> list[float]:
    return embed_texts([text])[0]
