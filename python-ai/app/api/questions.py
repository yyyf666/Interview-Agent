"""题库向量 API：题目 Embedding 写入与语义检索"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from app.models.schemas import QuestionEmbedReq, QuestionSearchReq, ok
from app.rag import indexer, retriever

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/questions", tags=["questions"])


@router.post("/embed")
def embed_question(req: QuestionEmbedReq):
    try:
        indexer.embed_question(req.question_id, req.content, req.metadata)
        return ok(True)
    except Exception as e:
        logger.exception("题目向量化失败")
        raise HTTPException(status_code=500, detail=f"题目向量化失败: {e}")


@router.post("/search")
def search_questions(req: QuestionSearchReq):
    rows = retriever.search_questions(req.query, skill=req.skill,
                                      difficulty=req.difficulty, top_k=req.top_k)
    return ok(rows)
