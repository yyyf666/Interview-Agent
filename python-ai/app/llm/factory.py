"""LLM 工厂：统一封装大模型调用与强制结构化输出（支持多模型切换）"""
from __future__ import annotations

import json
import logging
import re
import time
from typing import Type, TypeVar

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.trace import record_llm_call

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

_llm_cache: dict[tuple, ChatOpenAI] = {}


def get_llm(temperature: float = 0.3) -> ChatOpenAI:
    s = get_settings()
    key = (s.llm_api_base, s.llm_model, temperature)
    if key not in _llm_cache:
        _llm_cache[key] = ChatOpenAI(
            model=s.llm_model,
            base_url=s.llm_api_base,
            api_key=s.llm_api_key or "EMPTY",
            temperature=temperature,
            timeout=120,
        )
    return _llm_cache[key]


def get_fallback_llm(temperature: float = 0.3) -> ChatOpenAI | None:
    """备用模型（R-17）：base 与 model 同时配置才启用，否则返回 None。"""
    s = get_settings()
    base = getattr(s, "llm_fallback_api_base", "") or ""
    model = getattr(s, "llm_fallback_model", "") or ""
    if not (base.strip() and model.strip()):
        return None
    key = (base, model, temperature)
    if key not in _llm_cache:
        _llm_cache[key] = ChatOpenAI(
            model=model,
            base_url=base,
            api_key=(getattr(s, "llm_fallback_api_key", "") or "EMPTY"),
            temperature=temperature,
            timeout=120,
        )
    return _llm_cache[key]


def _extract_json(text: str) -> dict:
    """从模型输出中稳健提取JSON（兼容 markdown code fence）"""
    text = text.strip()
    m = re.search(r"```(?:json)?\s*(.*?)```", text, re.DOTALL)
    if m:
        text = m.group(1).strip()
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        text = text[start:end + 1]
    return json.loads(text)


def _invoke_structured(llm: ChatOpenAI, messages: list, schema: Type[T], model: str) -> T:
    """一次完整降级链：function-calling → JSON 模式，每跳都计入预算计数。"""
    try:
        t0 = time.perf_counter()
        result = llm.with_structured_output(schema).invoke(messages)
        record_llm_call((time.perf_counter() - t0) * 1000, model)
        if isinstance(result, schema):
            return result
        return schema.model_validate(result)
    except Exception as e:  # 部分兼容网关不支持tool calling，降级JSON模式
        record_llm_call(0.0, model)  # 失败的尝试同样消耗一次调用
        logger.warning("structured output降级为json模式: %s", e)

    json_llm = llm.bind(response_format={"type": "json_object"})
    t0 = time.perf_counter()
    raw = json_llm.invoke(messages)
    record_llm_call((time.perf_counter() - t0) * 1000, model)
    return schema.model_validate(_extract_json(raw.content))


def structured_call(prompt: str, schema: Type[T], system: str = "",
                    temperature: float = 0.2) -> T:
    """
    强制结构化输出（设计文档 §7.3）。
    优先使用 function-calling（with_structured_output），
    模型不支持时降级为 JSON 模式 + Pydantic 校验，保证输出稳定可解析。
    主模型整条降级链均失败且配置了备用模型时（R-17），用备用模型重试一次。
    """
    s = get_settings()
    messages = ([SystemMessage(content=system)] if system else []) + [HumanMessage(content=prompt)]

    # 预算护栏：每次真实 LLM 往返都计入请求级计数（降级链的每跳同样计费）
    try:
        return _invoke_structured(get_llm(temperature), messages, schema, s.llm_model)
    except Exception as e:
        fallback = get_fallback_llm(temperature)
        if fallback is None:
            raise
        logger.warning("fallback used: 主模型 %s 调用失败，切换备用模型 %s 重试: %s",
                       s.llm_model, getattr(s, "llm_fallback_model", ""), e)
        return _invoke_structured(fallback, messages, schema,
                                  getattr(s, "llm_fallback_model", ""))
