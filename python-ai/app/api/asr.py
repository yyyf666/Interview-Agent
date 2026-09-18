"""ASR API：音频转文字（非实时语音面试，设计文档 §3 文字/语音双模式）"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, UploadFile

from app.asr.client import AsrError, transcribe
from app.models.schemas import ok

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai", tags=["asr"])


@router.post("/asr")
async def asr(file: UploadFile):
    try:
        content = await file.read()
        text = transcribe(file.filename or "audio.webm", content)
        return ok(text)
    except AsrError as e:
        raise HTTPException(status_code=502, detail=str(e))
