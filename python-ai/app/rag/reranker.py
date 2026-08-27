"""RAG 重排：本地 CrossEncoder 或 DashScope 文本重排 API

provider 语义（app.core.config.Settings.rerank_provider）：
- auto：本地模型优先，不可用时回退 DashScope API；
- local：仅本地模型；api：仅 DashScope；none：关闭重排。
两者都不可用时**显式**记录降级日志（此前为静默透传，上游无感知）。
"""
from __future__ import annotations

import logging
import os

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_cross_encoder = None
_local_failed = False
_api_failed = False
_degrade_logged = False


def _local_model():
    global _cross_encoder, _local_failed
    if _cross_encoder is not None or _local_failed:
        return _cross_encoder
    try:
        # 国内网络下载 bge-reranker-base 走镜像，可用外部 HF_ENDPOINT 覆盖
        os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
        from sentence_transformers import CrossEncoder
        _cross_encoder = CrossEncoder(get_settings().rerank_model)
    except Exception as e:
        logger.warning("本地重排模型不可用（sentence-transformers 未安装或模型下载失败）: %s", e)
        _local_failed = True
    return _cross_encoder


def _local_rerank(query: str, docs: list[dict], top_k: int):
    global _local_failed
    model = _local_model()
    if model is None:
        return None
    try:
        scores = model.predict([(query, d["content"]) for d in docs])
        ranked = sorted(zip(docs, scores), key=lambda x: float(x[1]), reverse=True)
        return [(d, float(sc), "local") for d, sc in ranked[:top_k]]
    except Exception as e:
        logger.warning("本地重排推理失败，本次降级: %s", e)
        _local_failed = True
        return None


def _api_rerank(query: str, docs: list[dict], top_k: int):
    """DashScope text-rerank（gte-rerank-v2）：input.documents 保序，结果用 index 回指"""
    global _api_failed
    s = get_settings()
    if _api_failed:
        return None
    key = s.rerank_key
    if not key:
        return None
    try:
        resp = httpx.post(
            s.rerank_api_base,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={
                "model": s.rerank_api_model,
                "input": {"query": query, "documents": [d["content"] for d in docs]},
                "parameters": {"return_documents": False, "top_n": max(1, min(top_k, len(docs)))},
            },
            timeout=15.0,
        )
        resp.raise_for_status()
        results = ((resp.json().get("output") or {}).get("results")) or []
        picked = []
        for r in results:
            idx = int(r.get("index", -1))
            if 0 <= idx < len(docs):
                picked.append((docs[idx], float(r.get("relevance_score") or 0.0), "api"))
        return picked or None
    except Exception as e:
        logger.warning("DashScope 重排调用失败，后续请求不再尝试 API: %s", e)
        _api_failed = True
        return None


def reset_state() -> None:
    """配置热重载后重置降级标记：允许换 key/换 provider 后重新尝试"""
    global _local_failed, _api_failed, _degrade_logged
    _local_failed = False
    _api_failed = False
    _degrade_logged = False


def rerank(query: str, docs: list[dict], top_k: int) -> list[dict]:
    """docs: [{content, score, ...}]；按 provider 依次尝试，全部不可用时透传并显式告警"""
    global _degrade_logged
    s = get_settings()
    if not s.rerank_enabled or not docs:
        return docs[:top_k]

    provider = (s.rerank_provider or "auto").strip().lower()
    if provider == "none":
        return docs[:top_k]

    ranked = None
    if provider in ("auto", "local"):
        ranked = _local_rerank(query, docs, top_k)
    if ranked is None and provider in ("auto", "api"):
        ranked = _api_rerank(query, docs, top_k)
    if ranked is None:
        if not _degrade_logged:
            logger.warning("重排降级：初排结果直接透传（provider=%s，本地模型不可用且 DashScope key 未配置）",
                           provider)
            _degrade_logged = True
        return docs[:top_k]

    out = []
    for d, sc, source in ranked:
        item = dict(d)
        item["rerank_score"] = sc
        item["rerank_source"] = source
        out.append(item)
    return out
