"""请求级链路上下文：trace_id 贯穿 + 每请求 LLM 调用计数（预算护栏）

contextvar 天然协程/线程安全，FastAPI 每个请求独立上下文；
Java 侧透传 X-Trace-Id 头，缺省时由中间件生成。
"""
from __future__ import annotations

from contextvars import ContextVar, Token
from dataclasses import dataclass

trace_id_var: ContextVar[str] = ContextVar("trace_id", default="-")


@dataclass
class _LlmStats:
    """可变累加器：LangGraph 节点在 copy_context() 复制出的上下文/线程中执行，
    对 ContextVar 重新 set 不会回传请求上下文；改为持有同一可变对象并原地累加，
    复制出的上下文共享该对象引用，故节点内的计数能被外层 get 读到。"""
    calls: int = 0
    elapsed_ms: float = 0.0
    model: str = ""


llm_stats_var: ContextVar[_LlmStats | None] = ContextVar("llm_stats", default=None)


def reset_llm_stats() -> Token:
    return llm_stats_var.set(_LlmStats())


def record_llm_call(elapsed_ms: float, model: str = "") -> None:
    """计一次 LLM 往返；model 非空时记录实际使用的模型（含备用模型）。"""
    stats = llm_stats_var.get()
    if stats is None:  # 未经 answer 入口的独立调用（如 hint）懒建累加器
        stats = _LlmStats()
        llm_stats_var.set(stats)
    stats.calls += 1
    stats.elapsed_ms += elapsed_ms
    if model:
        stats.model = model


def get_llm_stats() -> dict:
    stats = llm_stats_var.get()
    model = stats.model if stats else ""
    if not model:  # 未记录到具体模型时回落当前生效配置
        from app.core.config import get_settings
        model = get_settings().llm_model
    return {
        "llm_calls": stats.calls if stats else 0,
        "llm_elapsed_ms": round(stats.elapsed_ms, 1) if stats else 0.0,
        "model": model,
        "trace_id": trace_id_var.get(),
    }
