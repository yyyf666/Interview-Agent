"""索引构建：文档 -> 清洗 -> 分块 -> Embedding -> pgvector（设计文档 §6.3-1）"""
from __future__ import annotations

import json
import logging
from pathlib import Path

from app.core.db import execute, query
from app.rag import cache as rag_cache
from app.rag.chunker import clean_text, split_text
from app.rag.embedder import embed_texts

logger = logging.getLogger(__name__)


def index_document(document_id: int, content: str, metadata: dict) -> int:
    """为指定知识文档构建向量索引（幂等：先清旧块）"""
    from app.core.config import get_settings
    s = get_settings()

    execute("DELETE FROM knowledge_vector WHERE document_id = %s", (document_id,))
    # 检索缓存整体失效：这里位于唯一写入口，backfill/重索引同样经过
    rag_cache.bump_version()
    text = clean_text(content)
    chunks = split_text(text, s.chunk_size, s.chunk_overlap)
    if not chunks:
        return 0

    vectors = embed_texts(chunks)
    meta = json.dumps(metadata or {}, ensure_ascii=False)
    for i, (chunk, vec) in enumerate(zip(chunks, vectors)):
        execute(
            """INSERT INTO knowledge_vector (document_id, chunk_index, content, embedding, metadata)
               VALUES (%s, %s, %s, %s, %s::jsonb)""",
            (document_id, i, chunk, vec, meta),
        )
    logger.info("文档 %s 索引完成，共 %d 块", document_id, len(chunks))
    return len(chunks)


def reindex_document(document_id: int, file_path: str) -> int:
    """按文件路径重读内容再索引（Markdown/TXT直接读，其他格式要求Java传文本）"""
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"文件不存在: {file_path}")
    suffix = path.suffix.lower()
    if suffix in {".md", ".txt", ".markdown"}:
        content = path.read_text(encoding="utf-8", errors="ignore")
    else:
        raise ValueError(f"AI层仅支持纯文本类文件重索引，pdf/docx请通过Java上传链路: {file_path}")
    row = query("SELECT title, doc_type, tags FROM knowledge_doc WHERE id = %s", (document_id,))
    meta = {}
    if row:
        meta = {"title": row[0]["title"], "doc_type": row[0]["doc_type"], "tags": row[0]["tags"]}
    return index_document(document_id, content, meta)


def embed_question(question_id: int, content: str, metadata: dict) -> None:
    """题目向量写入（幂等 upsert）"""
    vec = embed_texts([content])[0]
    execute(
        """INSERT INTO question_vector (question_id, content, embedding, metadata)
           VALUES (%s, %s, %s, %s::jsonb)
           ON CONFLICT (question_id) DO UPDATE
           SET content = EXCLUDED.content, embedding = EXCLUDED.embedding,
               metadata = EXCLUDED.metadata""",
        (question_id, content, vec, json.dumps(metadata or {}, ensure_ascii=False)),
    )
