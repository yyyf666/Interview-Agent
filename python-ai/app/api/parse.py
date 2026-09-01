"""解析类 API：简历 / JD 结构化解析（设计文档 §3.1）"""
from __future__ import annotations

import logging

from fastapi import APIRouter

from app.llm.factory import structured_call
from app.models.schemas import (
    JdParseReq, JdParsed, ResumeParseReq, ResumeParsed, ok)
from app.prompts import templates

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/parse", tags=["parse"])


@router.post("/resume")
def parse_resume(req: ResumeParseReq):
    """简历 -> 结构化技能画像"""
    parsed = structured_call(
        f"简历原文：\n{req.rawText[:8000]}", ResumeParsed,
        system=templates.RESUME_PARSE_SYSTEM)
    return ok(parsed.model_dump())


@router.post("/jd")
def parse_jd(req: JdParseReq):
    """JD -> 岗位能力模型（技能权重归一化校验）"""
    parsed = structured_call(
        f"JD原文：\n{req.rawText[:8000]}", JdParsed,
        system=templates.JD_PARSE_SYSTEM)
    data = parsed.model_dump()
    weights = data.get("skill_weights") or {}
    total = sum(weights.values())
    if total > 0:  # 归一化兜底
        data["skill_weights"] = {k: round(v / total, 3) for k, v in weights.items()}
    return ok(data)
