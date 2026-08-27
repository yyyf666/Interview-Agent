"""混合检索：向量召回 + BM25 关键词 + 加权融合 + 重排（设计文档 §9.2）"""
from __future__ import annotations

import logging

from app.core.config import get_settings
from app.core.db import query
from app.rag import cache as rag_cache
from app.rag.embedder import embed_one
from app.rag.reranker import rerank

logger = logging.getLogger(__name__)

_vector_weight = 0.7
_bm25_weight = 0.3


def _tokenize(text: str) -> list[str]:
    try:
        import jieba
        return list(jieba.cut_for_search(text))
    except Exception:
        return text.split()


def _vec_search_knowledge(embedding: list[float], k: int, skill: str | None) -> list[dict]:
    if skill:
        sql = """SELECT id, document_id, content, metadata,
                        1 - (embedding <=> %s::vector) AS score
                 FROM knowledge_vector
                 WHERE metadata->>'skill' = %s OR metadata->'tags' ? %s
                 ORDER BY embedding <=> %s::vector LIMIT %s"""
        return query(sql, (embedding, skill, skill, embedding, k))
    sql = """SELECT id, document_id, content, metadata,
                    1 - (embedding <=> %s::vector) AS score
             FROM knowledge_vector
             ORDER BY embedding <=> %s::vector LIMIT %s"""
    return query(sql, (embedding, embedding, k))


def _kw_search_knowledge(keywords: str, k: int) -> list[dict]:
    """关键词召回：ILIKE 命中集由 trgm GIN 索引收敛，再按 similarity() 相关性排序

    旧实现 ORDER BY id DESC 无相关度语义；similarity() 仅对命中行计算，代价可控。
    pg_trgm 缺失等异常时降级为原时间倒序，不阻断检索。
    """
    like = f"%{keywords}%"
    try:
        return query(
            """SELECT id, document_id, content, metadata, similarity(content, %s) AS score
               FROM knowledge_vector
               WHERE content ILIKE %s
               ORDER BY score DESC LIMIT %s""",
            (keywords, like, k),
        )
    except Exception as e:
        logger.warning("关键词召回相似度排序失败，降级时间倒序: %s", e)
        return query(
            """SELECT id, document_id, content, metadata, 0.5 AS score
               FROM knowledge_vector
               WHERE content ILIKE %s
               ORDER BY id DESC LIMIT %s""",
            (like, k),
        )


def _hybrid(vec_rows: list[dict], kw_rows: list[dict], query_text: str) -> list[dict]:
    """融合打分：0.7*向量分(归一) + 0.3*BM25分(归一)"""
    from rank_bm25 import BM25Okapi

    merged: dict[int, dict] = {}
    for r in vec_rows:
        merged[r["id"]] = {**r, "vec_score": float(r.get("score") or 0), "kw_score": 0.0}
    for r in kw_rows:
        item = merged.setdefault(r["id"], {**r, "vec_score": 0.0, "kw_score": 0.0})
        item["kw_score"] = max(item["kw_score"], float(r.get("score") or 0))

    docs = list(merged.values())
    if docs:
        corpus = [_tokenize(d["content"]) for d in docs]
        try:
            bm25 = BM25Okapi(corpus)
            scores = bm25.get_scores(_tokenize(query_text))
            max_s = max(scores) or 1.0
            for d, sc in zip(docs, scores):
                d["kw_score"] = max(d["kw_score"], float(sc) / max_s)
        except Exception as e:
            logger.warning("BM25计算失败，仅用向量分: %s", e)

    for d in docs:
        d["score"] = _vector_weight * d["vec_score"] + _bm25_weight * d["kw_score"]
    return sorted(docs, key=lambda x: x["score"], reverse=True)


def search_knowledge(query_text: str, skill: str | None = None,
                     top_k: int | None = None) -> list[dict]:
    """知识库RAG检索主入口：缓存 -> 召回Top10 -> 融合 -> 精排 -> Top3"""
    s = get_settings()
    top_k = top_k or s.final_top_k
    cached = rag_cache.get_cached(query_text, skill, top_k)
    if cached is not None:
        return cached
    embedding = embed_one(query_text)
    vec_rows = _vec_search_knowledge(embedding, s.recall_top_k, skill)
    kw_rows = _kw_search_knowledge(query_text[:20], s.recall_top_k // 2)
    fused = _hybrid(vec_rows, kw_rows, query_text)
    docs = rerank(query_text, fused, top_k)
    rag_cache.put_cached(query_text, skill, top_k, docs)
    return docs


def search_questions(query_text: str, skill: str | None = None,
                     difficulty: int | None = None, top_k: int = 5) -> list[dict]:
    """题库语义检索：向量相似 + 元数据过滤"""
    embedding = embed_one(query_text)
    sql = """SELECT q.id, q.content, q.skill_name, q.difficulty, q.q_type, q.tags,
                    q.reference_answer, q.scoring_points,
                    1 - (qv.embedding <=> %s::vector) AS score
             FROM question_vector qv
             JOIN question_bank q ON q.id = qv.question_id
             WHERE q.status = 'ACTIVE'
               AND (%s::text IS NULL OR q.skill_name = %s)
               AND (%s::int IS NULL OR q.difficulty = %s)
             ORDER BY qv.embedding <=> %s::vector
             LIMIT %s"""
    try:
        return query(sql, (embedding, skill, skill, difficulty, difficulty, embedding, top_k))
    except Exception as e:
        logger.warning("题库向量检索失败（可能未建索引），降级ILIKE: %s", e)
        return query(
            """SELECT id, content, skill_name, difficulty, q_type, tags,
                      reference_answer, scoring_points, 0.5 AS score
               FROM question_bank
               WHERE status = 'ACTIVE' AND (%s IS NULL OR skill_name = %s)
                 AND content ILIKE %s
               LIMIT %s""",
            (skill, skill, f"%{query_text[:10]}%", top_k),
        )
