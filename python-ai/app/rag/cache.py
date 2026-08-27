"""RAG 结果缓存：Redis 短时缓存检索结果，索引更新时按版本号整体失效

- key 形如 rag:kb:{ver}:{skill}:{sha1(query|top_k)[:16]}，ver 由索引写入方 INCR 自增；
- Redis 不可用、序列化失败、旧版本残留一律降级为"不缓存"，不影响主链路。
"""
from __future__ import annotations

import hashlib
import json
import logging

from app.core.config import get_settings
from app.core.state_store import get_redis

logger = logging.getLogger(__name__)

_VERSION_KEY = "rag:kb:ver"


def _version() -> str:
    r = get_redis()
    if r is None:
        return "0"
    try:
        return r.get(_VERSION_KEY) or "0"
    except Exception:
        return "0"


def _key(query: str, skill: str | None, top_k: int) -> str:
    digest = hashlib.sha1(f"{query}|{top_k}".encode("utf-8")).hexdigest()[:16]
    return f"rag:kb:{_version()}:{skill or '_'}:{digest}"


def get_cached(query: str, skill: str | None, top_k: int) -> list[dict] | None:
    """命中返回检索结果副本，未命中/未启用/Redis 异常返回 None"""
    s = get_settings()
    if not s.rag_cache_enabled:
        return None
    r = get_redis()
    if r is None:
        return None
    try:
        raw = r.get(_key(query, skill, top_k))
        if not raw:
            return None
        rows = json.loads(raw)
        if not isinstance(rows, list):
            return None
        logger.debug("RAG缓存命中 skill=%s top_k=%s", skill, top_k)
        return rows
    except Exception as e:
        logger.debug("RAG缓存读取失败(降级直查): %s", e)
        return None


def put_cached(query: str, skill: str | None, top_k: int, rows: list[dict]) -> None:
    s = get_settings()
    if not s.rag_cache_enabled or not rows:
        return
    r = get_redis()
    if r is None:
        return
    try:
        r.set(_key(query, skill, top_k),
              json.dumps(rows, ensure_ascii=False, default=str),
              ex=max(1, s.rag_cache_ttl))
    except Exception as e:
        logger.debug("RAG缓存写入失败(忽略): %s", e)


def bump_version() -> None:
    """索引变更后整体失效：key 前缀版本号自增，旧键随 TTL 自然过期"""
    r = get_redis()
    if r is None:
        return
    try:
        r.incr(_VERSION_KEY)
        logger.info("RAG缓存版本已自增，旧检索结果失效")
    except Exception as e:
        logger.debug("RAG缓存版本自增失败(忽略): %s", e)
