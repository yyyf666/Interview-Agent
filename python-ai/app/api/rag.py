"""RAG API：知识文档索引 / 检索（设计文档 §6.3 / §9）"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from app.models.schemas import RagIndexReq, RagReindexReq, RagSearchReq, ok
from app.rag import indexer, retriever

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/rag", tags=["rag"])


@router.post("/index")
def index_document(req: RagIndexReq):
    """文档入库：清洗 -> 语义分块 -> Embedding -> pgvector，返回块数"""
    try:
        count = indexer.index_document(req.document_id, req.content, req.metadata)
        return ok(count)
    except Exception as e:
        logger.exception("文档索引失败")
        raise HTTPException(status_code=500, detail=f"文档索引失败: {e}")


@router.post("/reindex")
def reindex_document(req: RagReindexReq):
    try:
        count = indexer.reindex_document(req.document_id, req.file_path)
        return ok(count)
    except Exception as e:
        logger.exception("重新索引失败")
        raise HTTPException(status_code=500, detail=f"重新索引失败: {e}")


@router.post("/search")
def search(req: RagSearchReq):
    """混合检索：向量+BM25融合+重排，调试与复盘关联学习资料用"""
    docs = retriever.search_knowledge(req.query, skill=req.skill, top_k=req.top_k or 3)
    return ok(docs)
