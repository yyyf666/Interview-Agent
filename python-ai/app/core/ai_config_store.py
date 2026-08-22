"""AI 配置存储：ai_config 单行 JSONB 表，存对 env 默认的运行时覆盖项。
表自愈（CREATE TABLE IF NOT EXISTS）保证存量库无需人工迁移。"""
from __future__ import annotations

import json
import logging

from app.core.db import get_conn, query_one

logger = logging.getLogger(__name__)

# 允许页面配置的字段白名单（基础设施连接参数不在其中，DB/Redis 只能走 env）
ALLOWED_KEYS = [
    "llm_api_base", "llm_api_key", "llm_model",
    # 预算告警阈值与备用模型（R-17）：主模型不可用时自动切换
    "llm_budget_warn", "llm_fallback_api_base", "llm_fallback_api_key", "llm_fallback_model",
    "embedding_api_base", "embedding_api_key", "embedding_model", "embedding_dim",
    "asr_api_base", "asr_api_key", "asr_model", "asr_stream_model",
    "rerank_enabled", "mcp_enabled", "intent_rules_enabled",
]
SECRET_KEYS = {"llm_api_key", "llm_fallback_api_key", "embedding_api_key", "asr_api_key"}


def ensure_table() -> None:
    with get_conn() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS ai_config (
                id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                payload    JSONB NOT NULL DEFAULT '{}',
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
        """)
        conn.execute("INSERT INTO ai_config (id, payload) VALUES (1, '{}'::jsonb) ON CONFLICT (id) DO NOTHING")


def load_payload() -> dict:
    row = query_one("SELECT payload FROM ai_config WHERE id = 1")
    if not row:
        return {}
    payload = row["payload"]
    if isinstance(payload, str):  # row_factory 已解 jsonb，此处仅防御
        payload = json.loads(payload)
    return {k: v for k, v in (payload or {}).items() if k in ALLOWED_KEYS}


def save_payload(payload: dict) -> dict:
    """整体替换覆盖项（调用方负责合并语义），返回落库后的净 payload"""
    clean = {k: v for k, v in payload.items() if k in ALLOWED_KEYS}
    with get_conn() as conn:
        conn.execute(
            """UPDATE ai_config SET payload = %s::jsonb, updated_at = now() WHERE id = 1""",
            [json.dumps(clean, ensure_ascii=False)])
    logger.info("AI配置已更新，覆盖项 %d 个: %s", len(clean), sorted(clean))
    return clean
