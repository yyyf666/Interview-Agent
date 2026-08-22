"""AI 配置 API：查询/保存运行时配置 + 测试连接（前端经 Java /api/ai-config 转发）"""
from __future__ import annotations

import logging
import time

from fastapi import APIRouter, HTTPException

from app.core import ai_config_store
from app.core.config import get_settings, reload_from_db
from app.models.schemas import ok

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/config", tags=["config"])


def _mask(value: str) -> str:
    if not value:
        return ""
    return "sk-****" + value[-4:] if len(value) > 8 else "sk-****"


def _current_view() -> dict:
    """当前生效配置（env 默认 + DB 覆盖合并后的真值），secret 字段掩码"""
    s = get_settings()
    out = {k: getattr(s, k) for k in ai_config_store.ALLOWED_KEYS}
    for k in ai_config_store.SECRET_KEYS:
        out[k] = _mask(out[k] or "")
    return out


@router.get("")
def get_config():
    return ok(_current_view())


@router.put("")
def put_config(body: dict):
    """全量保存四组字段。掩码占位（含****）的 secret 字段视为未修改，沿用已存值；
    空字符串 = 回落 env 默认（不进覆盖项）。"""
    old = ai_config_store.load_payload()
    payload = {}
    for k in ai_config_store.ALLOWED_KEYS:
        if k not in body:
            continue
        v = body[k]
        if k in ai_config_store.SECRET_KEYS:
            if isinstance(v, str) and "****" in v:  # 未改动的掩码回显
                if k in old:
                    payload[k] = old[k]
                continue
        payload[k] = v
    ai_config_store.save_payload(payload)
    reload_from_db()
    return ok(_current_view())


@router.post("/test")
def test_connection(body: dict):
    """用草稿配置做一次最小连接测试（R-17），不落库。

    kind: llm | embedding | asr（默认 llm）；统一返回 {ok, kind, model, latency_ms, error}
    """
    kind = str(body.get("kind") or "llm").lower()
    if kind == "embedding":
        return ok(_test_embedding(body))
    if kind == "asr":
        return ok(_test_asr(body))
    return ok(_test_llm(body))


def _draft_key(body: dict, name: str, effective: str) -> str:
    """草稿 key 优先；掩码回显（含 ****）时用当前生效 key。"""
    v = body.get(name)
    if isinstance(v, str) and "****" in v:
        return effective
    return (v or effective or "")


def _test_llm(body: dict) -> dict:
    s = get_settings()
    base = body.get("llm_api_base") or s.llm_api_base
    model = body.get("llm_model") or s.llm_model
    key = _draft_key(body, "llm_api_key", s.llm_api_key)
    if not base:
        raise HTTPException(status_code=400, detail="缺少 API Base")
    try:
        from langchain_openai import ChatOpenAI
        llm = ChatOpenAI(model=model, base_url=base, api_key=key or "EMPTY",
                         temperature=0, timeout=30, max_tokens=1)
        t0 = time.perf_counter()
        llm.invoke("ping")
        latency = round((time.perf_counter() - t0) * 1000)
        return {"ok": True, "kind": "llm", "model": model, "latency_ms": latency, "error": ""}
    except Exception as e:
        logger.warning("AI配置测试失败 kind=llm base=%s model=%s: %s", base, model, e)
        return {"ok": False, "kind": "llm", "model": model, "latency_ms": 0,
                "error": str(e)[:300]}


def _test_embedding(body: dict) -> dict:
    """Embedding 连通性：embed_query("ping")，返回向量维度与耗时"""
    s = get_settings()
    base = body.get("embedding_api_base") or s.embedding_base_url
    model = body.get("embedding_model") or s.embedding_model
    key = _draft_key(body, "embedding_api_key", s.embedding_key)
    dim = body.get("embedding_dim") or s.embedding_dim
    if not base:
        return {"ok": False, "kind": "embedding", "model": model, "latency_ms": 0,
                "error": "缺少 Embedding API Base（未配置且无 LLM Base 可回落）"}
    try:
        from openai import OpenAI
        client = OpenAI(base_url=base, api_key=key or "EMPTY")
        t0 = time.perf_counter()
        try:
            resp = client.embeddings.create(model=model, input=["ping"], dimensions=int(dim))
        except Exception:  # 部分模型不支持 dimensions 参数
            resp = client.embeddings.create(model=model, input=["ping"])
        vec = resp.data[0].embedding
        latency = round((time.perf_counter() - t0) * 1000)
        extra = f"维度 {len(vec)}"
        if dim and len(vec) != int(dim):
            extra += f"（与配置维度 {dim} 不一致）"
        return {"ok": True, "kind": "embedding", "model": model, "latency_ms": latency,
                "error": "", "detail": extra}
    except Exception as e:
        logger.warning("AI配置测试失败 kind=embedding base=%s model=%s: %s", base, model, e)
        return {"ok": False, "kind": "embedding", "model": model, "latency_ms": 0,
                "error": str(e)[:300]}


def _test_asr(body: dict) -> dict:
    """ASR 仅校验配置完整性（不发起真实音频上传），返回就绪状态"""
    s = get_settings()
    base = body.get("asr_api_base") or s.asr_base_url
    model = body.get("asr_model") or s.asr_model
    key = _draft_key(body, "asr_api_key", s.asr_key)
    missing = [name for name, v in (("base", base), ("key", key), ("model", model)) if not v]
    if missing:
        return {"ok": False, "kind": "asr", "model": model, "latency_ms": 0,
                "error": "缺少配置：" + "、".join(missing)}
    return {"ok": True, "kind": "asr", "model": model, "latency_ms": 0, "error": "",
            "detail": "配置完整（保存后生效，首次转写才会真实请求）"}
