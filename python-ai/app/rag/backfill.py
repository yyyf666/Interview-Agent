"""启动自动回填：题库向量缺失补偿 + 知识文档索引重建（替代手动 /reindex）

设计：AI 层直连 PostgreSQL 真源做幂等回填（仅启动时一次，后台线程执行）；
Embedding 未配置或上游失败时整体降级为 warning，不影响服务启动。
"""
from __future__ import annotations

import logging

from app.core.db import execute, query
from app.rag.indexer import embed_question, reindex_document

logger = logging.getLogger(__name__)

_QUESTION_BATCH = 200


def backfill_questions() -> int:
    """question_bank 中无对应 question_vector 的 ACTIVE 题目 → 补向量"""
    rows = query(
        """SELECT q.id, q.content, q.skill_name, q.difficulty, q.q_type
           FROM question_bank q
           LEFT JOIN question_vector v ON v.question_id = q.id
           WHERE v.id IS NULL AND q.status = 'ACTIVE'
           LIMIT %s""",
        (_QUESTION_BATCH,))
    done = 0
    for r in rows:
        try:
            embed_question(r["id"], r["content"], {
                "skill": r.get("skill_name") or "",
                "difficulty": r.get("difficulty") or 2,
                "q_type": r.get("q_type") or ""})
            done += 1
        except Exception as e:
            logger.warning("题目向量回填失败 question_id=%s: %s", r["id"], e)
            break  # embedding 服务不可用时不再空转
    return done


def backfill_documents() -> int:
    """knowledge_doc PENDING/FAILED 且源文件为纯文本类 → 重读内容重建索引"""
    rows = query(
        "SELECT id, file_path, title, doc_type, tags FROM knowledge_doc "
        "WHERE chunk_status IN ('PENDING', 'FAILED')")
    done = 0
    for r in rows:
        path = (r.get("file_path") or "").lower()
        if not path.endswith((".md", ".txt", ".markdown")):
            logger.warning("文档 %s 为非纯文本源（pdf/docx），需 Java 上传链路重建，跳过", r["id"])
            continue
        try:
            count = reindex_document(r["id"], r["file_path"])
            execute("UPDATE knowledge_doc SET chunk_status = 'INDEXED', chunk_count = %s WHERE id = %s",
                    (count, r["id"]))
            done += 1
        except FileNotFoundError:
            logger.warning("文档 %s 源文件不存在，跳过: %s", r["id"], r.get("file_path"))
        except Exception as e:
            logger.warning("文档索引回填失败 doc_id=%s: %s", r["id"], e)
            break
    return done


def run_startup_backfill() -> None:
    try:
        q = backfill_questions()
        d = backfill_documents()
        if q or d:
            logger.info("RAG 启动回填完成：题目向量 +%d，知识文档重索引 +%d", q, d)
        else:
            logger.info("RAG 启动回填：向量数据完整，无需补偿")
    except Exception as e:
        logger.warning("RAG 启动回填失败（不影响服务）: %s", e)
