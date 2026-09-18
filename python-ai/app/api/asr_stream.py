"""实时流式 ASR WebSocket 桥接（阶段5 实时语音面试）

链路：浏览器 →(Java /api/ws/asr 透传)→ 本端点 →(DashScope paraformer-realtime WSS)→ 上游。

协议（DashScope run-task / duplex）：
- 连接后立即向上游 run-task（format=pcm, sample_rate=16000），等 task-started；
- 下游（Java）转发来的二进制帧按 PCM16 直接送入上游；收到文本 "finish" 发 finish-task；
- 上游 result-generated 事件解析 sentence.text / sentence_end，向下游回推
  {"type":"partial"|"final","text"}；task-failed/异常回推 {"type":"error","text"} 并保持下游连接。
"""
from __future__ import annotations

import asyncio
import json
import logging
import uuid

import websockets
from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.core.config import get_settings

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/asr", tags=["asr-stream"])

_SAMPLE_RATE = 16000


def _run_task(task_id: str, model: str) -> dict:
    return {
        "header": {"action": "run-task", "task_id": task_id, "streaming": "duplex"},
        "payload": {
            "task_group": "audio", "task": "asr", "function": "recognition",
            "model": model,
            "parameters": {"format": "pcm", "sample_rate": _SAMPLE_RATE},
            "input": {},
        },
    }


def _finish_task(task_id: str) -> dict:
    return {
        "header": {"action": "finish-task", "task_id": task_id, "streaming": "duplex"},
        "payload": {"input": {}},
    }


def _extract_sentence(raw: dict) -> tuple[str, bool] | None:
    """从 result-generated 事件取当前句文本与是否句末；无句返回 None"""
    output = (raw or {}).get("payload", {}).get("output", {}) or {}
    sentence = output.get("sentence") or output.get("translations") or {}
    if isinstance(sentence, list):
        sentence = sentence[0] if sentence else {}
    text = sentence.get("text")
    if not text:
        return None
    return text, bool(sentence.get("sentence_end", False))


@router.websocket("/stream")
async def asr_stream(ws: WebSocket):
    # 内网鉴权：HTTP 中间件拦不到 websocket scope，这里按 query 参数补一道（Java 中继侧拼上）
    expected = get_settings().internal_token
    if expected and ws.query_params.get("internal_token") != expected:
        await ws.close(code=1008)  # 未 accept 前 close 会回 403
        return
    await ws.accept()
    s = get_settings()
    key = s.asr_key
    if not key:
        await ws.send_json({"type": "error", "text": "实时语音未配置 ASR API Key"})
        await ws.close()
        return

    task_id = uuid.uuid4().hex
    headers = {"Authorization": f"bearer {key}"}
    try:
        upstream = await websockets.connect(
            s.asr_stream_base_url, additional_headers=headers, max_size=8 * 1024 * 1024)
    except Exception as e:
        logger.warning("上游实时ASR连接失败: %s", e)
        await ws.send_json({"type": "error", "text": f"语音服务连接失败: {e}"})
        await ws.close()
        return

    stopped = asyncio.Event()

    async def forward() -> None:
        """下游(Java/浏览器)二进制帧 → 上游；文本 finish 结束任务"""
        try:
            while not stopped.is_set():
                msg = await ws.receive()
                if msg.get("bytes") is not None:
                    await upstream.send(msg["bytes"])
                elif msg.get("text") is not None:
                    if msg["text"].strip().lower() == "finish":
                        await upstream.send(json.dumps(_finish_task(task_id)))
                        break
        except (WebSocketDisconnect, RuntimeError):
            pass
        except Exception as e:
            logger.warning("转发音频帧异常: %s", e)
        finally:
            stopped.set()

    async def relay() -> None:
        """上游事件 → 下游 JSON（partial/final/error）"""
        try:
            await upstream.send(json.dumps(_run_task(task_id, s.asr_stream_model)))
            async for raw in upstream:
                if stopped.is_set():
                    break
                try:
                    evt = json.loads(raw)
                except Exception:
                    continue
                event = evt.get("header", {}).get("event")
                if event == "task-started":
                    continue
                if event == "result-generated":
                    sent = _extract_sentence(evt)
                    if sent:
                        text, is_end = sent
                        await ws.send_json({"type": "final" if is_end else "partial", "text": text})
                elif event == "task-finished":
                    break
                elif event == "task-failed":
                    err = evt.get("header", {}).get("error_message", "上游任务失败")
                    await ws.send_json({"type": "error", "text": err})
                    break
        except Exception as e:
            logger.warning("实时ASR上游异常: %s", e)
            try:
                await ws.send_json({"type": "error", "text": f"识别中断: {e}"})
            except Exception:
                pass
        finally:
            stopped.set()

    try:
        await asyncio.gather(forward(), relay())
    finally:
        try:
            await upstream.close()
        except Exception:
            pass
        try:
            await ws.close()
        except Exception:
            pass
