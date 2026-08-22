"""FastAPI 应用入口：AI能力层对外REST接口（设计文档 §4.2 / §11.1 端口8000）"""
from __future__ import annotations

import logging
import threading
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api import asr, asr_stream, config, interview, parse, questions, rag
from app.core.config import get_settings, reload_from_db
from app.core.trace import trace_id_var
from app.models.schemas import ok
from app.rag.backfill import run_startup_backfill
from app.skills.loader import list_skills, reload_skills


class TraceFormatter(logging.Formatter):
    """日志注入当前请求 trace_id（contextvar，非请求上下文为 '-'）"""

    def format(self, record: logging.LogRecord) -> str:
        record.trace_id = trace_id_var.get()
        return super().format(record)


logging.basicConfig(level=logging.INFO)
for _h in logging.getLogger().handlers:
    _h.setFormatter(TraceFormatter(
        "%(asctime)s %(levelname)s [%(trace_id)s] [%(name)s] %(message)s"))
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # DB 运行时覆盖先于 backfill 加载，保证 Embedding 用的是页面配置的最新值
    reload_from_db()
    logger.info("AI能力层启动完成，已发现Skills: %s", list_skills())
    # 向量回填放后台线程：避免 Embedding 上游慢/挂时阻塞启动与 healthcheck
    threading.Thread(target=run_startup_backfill, name="rag-backfill",
                     daemon=True).start()
    yield


app = FastAPI(
    title="智能自适应AI面试系统 - AI能力层",
    description="多Agent编排 / RAG / 评测 / Skills / MCP / 结构化输出",
    version="0.1.0",
    lifespan=lifespan,
)

# 内部调用鉴权白名单：容器健康检查与 OpenAPI 文档不需要密钥
_AUTH_EXEMPT = {"/health", "/docs", "/openapi.json", "/redoc"}
_INTERNAL_TOKEN_HEADER = "X-Internal-Token"


@app.middleware("http")
async def internal_token_middleware(request: Request, call_next):
    """内网鉴权：只允许携带共享密钥的 Java 业务层调用。

    密钥未配置时（本地单机调试）仅告警放行，便于脱离 docker 起服务；
    生产由 docker-compose.prod.yml 强制注入，Java 侧缺失密钥则启动失败。
    """
    path = request.url.path
    if request.method == "OPTIONS" or path in _AUTH_EXEMPT:
        return await call_next(request)
    expected = get_settings().internal_token
    if expected and request.headers.get(_INTERNAL_TOKEN_HEADER) != expected:
        return JSONResponse(
            status_code=401,
            content={"code": 401, "message": "内部调用鉴权失败",
                     "error_code": "INTERNAL_UNAUTHORIZED", "data": None},
        )
    return await call_next(request)


# CORS 白名单：禁止 allow_origins=["*"] 与 allow_credentials 组合
app.add_middleware(
    CORSMiddleware,
    allow_origins=get_settings().cors_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization", "X-Trace-Id", _INTERNAL_TOKEN_HEADER],
)


@app.middleware("http")
async def trace_middleware(request: Request, call_next):
    """全链路 Trace：透传/生成 X-Trace-Id，与 Java 侧同一请求串可 grep"""
    tid = request.headers.get("X-Trace-Id") or uuid.uuid4().hex[:16]
    trace_id_var.set(tid)
    response = await call_next(request)
    response.headers["X-Trace-Id"] = tid
    return response


app.include_router(parse.router)
app.include_router(interview.router)
app.include_router(rag.router)
app.include_router(questions.router)
app.include_router(asr.router)
app.include_router(asr_stream.router)
app.include_router(config.router)


@app.get("/health")
def health():
    from app.core.db import query
    try:
        query("SELECT 1")
        db = "up"
    except Exception as e:
        db = f"down: {e}"
    return ok({"status": "ok", "database": db, "skills": list_skills()})


@app.get("/ai/skills")
def skills():
    """已加载的Skills列表（模块化插拔能力清单）"""
    return ok(list_skills())


@app.get("/ai/skills/detail")
def skills_detail():
    """Skills 清单详情（R-12）：名称 / SKILL.md 文件时间，供管理页展示覆盖状态"""
    import time as _time
    from pathlib import Path
    from app.core.config import get_settings
    root = Path(get_settings().skills_dir)
    out = []
    if root.exists():
        for p in sorted(root.iterdir()):
            md = p / "SKILL.md"
            if md.exists():
                out.append({"name": p.name,
                            "mtime": _time.strftime("%Y-%m-%d %H:%M:%S",
                                                    _time.localtime(md.stat().st_mtime))})
    return ok(out)


@app.post("/ai/skills/reload")
def skills_reload():
    """强制刷新 Skills 缓存（平时靠 mtime 热加载，此端点供手动即时全量重加载）"""
    return ok({"reloaded": reload_skills()})
