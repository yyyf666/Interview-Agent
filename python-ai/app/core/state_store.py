"""Redis：面试会话状态镜像（与 Java 层共享，设计文档 §2.3）"""
from __future__ import annotations

import json
from typing import Any

import redis

from app.core.config import get_settings

_client: redis.Redis | None = None


def get_redis() -> redis.Redis | None:
    global _client
    if _client is None:
        try:
            _client = redis.Redis.from_url(get_settings().redis_url, decode_responses=True)
            _client.ping()
        except Exception:  # Redis不可用时AI层可降级运行
            _client = None
    return _client


def save_agent_state(session_id: int, state: dict) -> None:
    r = get_redis()
    if r is None:
        return
    try:
        r.set(f"interview:agent:{session_id}",
              json.dumps(state, ensure_ascii=False, default=str), ex=8 * 3600)
    except Exception:
        pass


def load_agent_state(session_id: int) -> dict[str, Any] | None:
    r = get_redis()
    if r is None:
        return None
    try:
        raw = r.get(f"interview:agent:{session_id}")
        return json.loads(raw) if raw else None
    except Exception:
        return None
