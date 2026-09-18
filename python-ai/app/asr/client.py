"""ASR 语音识别客户端：OpenAI兼容 /v1/audio/transcriptions（设计文档 §4.4）"""
from __future__ import annotations

import logging

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)


class AsrError(Exception):
    pass


def transcribe(filename: str, audio_bytes: bytes) -> str:
    """非实时语音：整段音频 -> 文字。支持 wav/mp3/m4a/webm 等网关可识别格式。"""
    s = get_settings()
    if not s.asr_key:
        raise AsrError("未配置 ASR_API_KEY，语音模式不可用（.env 中配置）")
    url = f"{s.asr_base_url.rstrip('/')}/audio/transcriptions"
    try:
        resp = httpx.post(
            url,
            headers={"Authorization": f"Bearer {s.asr_key}"},
            files={"file": (filename, audio_bytes, "application/octet-stream")},
            data={"model": s.asr_model, "language": "zh"},
            timeout=120,
        )
        resp.raise_for_status()
        text = resp.json().get("text", "")
        logger.info("ASR转写完成 %d 字符", len(text))
        return text.strip()
    except httpx.HTTPStatusError as e:
        raise AsrError(f"ASR服务返回错误 {e.response.status_code}: {e.response.text[:200]}") from e
    except Exception as e:
        raise AsrError(f"ASR调用失败: {e}") from e
