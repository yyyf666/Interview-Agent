"""面试会话 API：Java 业务后端调用入口，驱动 LangGraph 状态机"""
from __future__ import annotations

import json
import logging

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from app.agents import director, interviewer
from app.agents.graph import run_interview_graph, stream_interview_graph
from app.core.config import get_settings
from app.core.trace import get_llm_stats, reset_llm_stats
from app.models.schemas import (
    GapReq, InterviewAnswerReq, InterviewEndReq, InterviewHintReq, InterviewStartReq, ok)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/interview", tags=["interview"])


def _flatten_question(final: dict) -> dict:
    q = final.get("question") or {}
    starter_codes = q.get("starter_codes") or {}
    language = q.get("language") or "java"
    starter_code = q.get("starter_code") or starter_codes.get(language, "")
    if not starter_codes and starter_code:
        starter_codes = {language: starter_code}
    return {
        "question": q.get("question"),
        "topic": q.get("topic"),
        "skill": q.get("skill"),
        "difficulty": q.get("difficulty", 2),
        "question_type": q.get("question_type"),
        "reference_points": q.get("reference_points", []),
        "source": q.get("source", "generated"),
        "is_follow_up": bool(q.get("is_follow_up", False)),
        "starter_code": starter_code,
        "starter_codes": starter_codes,
        "language": language,
        "test_cases": q.get("test_cases", []),
    }


@router.post("/start")
def start(req: InterviewStartReq):
    """INIT -> PLAN -> QUESTION：缺口分析、面试计划、首题"""
    reset_llm_stats()
    state = {
        "mode": "start", "session_id": req.session_id,
        "resume": req.resume, "jd": req.jd, "profile": req.profile,
        "plan": req.plan or {},
        "persona": req.persona or "standard",
        "gap": req.gap or {},
        "options": req.options or {},
        "history": [],
    }
    try:
        final = run_interview_graph(state)
    except Exception as e:
        logger.exception("面试启动流程失败")
        raise HTTPException(status_code=500, detail=f"面试启动失败: {e}")

    plan = final.get("plan") or {}
    return ok({
        "plan": plan,
        "opening_remark": plan.get("opening_remark", ""),
        # 用量可观测（R-17）：本请求 LLM 调用数/耗时/模型回传 Java 落 ai_usage
        "meta": get_llm_stats(),
        **_flatten_question(final),
    })


@router.post("/gap")
def gap(req: GapReq):
    """开前缺口分析（R-03）：简历 × JD 匹配度/优势/薄弱/重点考察，供 Java 侧缓存"""
    reset_llm_stats()
    try:
        data = director.analyze_gap(req.resume or {}, req.jd or {}).model_dump()
        data["meta"] = get_llm_stats()
        return ok(data)
    except Exception as e:
        logger.exception("缺口分析失败")
        raise HTTPException(status_code=500, detail=f"缺口分析失败: {e}")


@router.post("/answer")
def answer(req: InterviewAnswerReq):
    """ANSWER -> EVALUATE -> DECIDE -> (FOLLOW_UP|QUESTION|FINAL_REPORT)"""
    reset_llm_stats()
    try:
        final = run_interview_graph(_answer_state(req))
    except Exception as e:
        logger.exception("面试流转失败")
        raise HTTPException(status_code=500, detail=f"面试流程失败: {e}")
    return ok(_build_answer_payload(final, req.context or {}))


# 流式阶段事件只带已就绪的增量字段（前端依次推进等待阶段条）
_STAGE_FIELDS = {
    "evaluate": ("evaluation",),
    "decide": ("action", "decision_reason"),
    "react": ("reaction",),
    "follow_up": ("question",),
    "question": ("question",),
    "interject": ("interjection", "question"),
    "final_report": ("report",),
}


def _answer_state(req: InterviewAnswerReq) -> dict:
    """构建 answer 链路的图输入（同步/流式共用）。"""
    ctx = req.context or {}
    return {
        "mode": "answer", "session_id": req.session_id,
        "plan": ctx.get("plan") or {},
        "current": dict(ctx.get("current") or {}),
        "history": ctx.get("history") or [],
        "answer": req.answer,
        "persona": ctx.get("persona") or "standard",
        "forced_intent": req.forced_intent or "",
        "question_spec": req.question_spec or "",
    }


def _build_answer_payload(final: dict, ctx: dict) -> dict:
    """组装与 /answer 完全一致的响应体（同步响应与流式 result 共用）。"""
    # 预算护栏：本轮 LLM 调用数回传 Java/前端，超预算告警（降本可观测）
    stats = get_llm_stats()
    if stats["llm_calls"] > get_settings().llm_budget_warn:
        logger.warning("单请求LLM调用数 %d 超过预算阈值 %d",
                       stats["llm_calls"], get_settings().llm_budget_warn)

    # 插话（元请求）：不评测、不产生评分，返回面试官回应 + 新题/重述题/报告
    inj = final.get("interjection")
    if inj:
        payload: dict = {
            "action": "INTERJECT" if final.get("action") != "FINAL_REPORT" else "FINAL_REPORT",
            "interjection": inj,
            "evaluation": None,
            "interviewer_reaction": inj.get("reply", ""),
            "meta": stats,
        }
        if final.get("decision_reason"):
            payload["decision_reason"] = final["decision_reason"]
        if final.get("question"):
            payload.update(_flatten_question(final))
        if final.get("report"):
            payload["report"] = final["report"]
        return payload

    payload = {
        "action": final.get("action"),
        "decision_reason": final.get("decision_reason"),
        "evaluation": final.get("evaluation"),
        "interviewer_reaction": final.get("reaction", ""),
        "meta": stats,
    }
    # 有界重规划发生时回传新计划（Java侧覆盖 session.plan，真源仍在Java）
    if final.get("plan") and final["plan"] != ctx.get("plan"):
        payload["plan"] = final["plan"]
    if final.get("question"):
        payload.update(_flatten_question(final))
    if final.get("report"):
        payload["report"] = final["report"]
    return payload


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _stage_payload(node: str, delta: dict) -> dict:
    """单节点阶段事件：node + 已就绪增量（question 先展平供前端直接渲染）。"""
    out: dict = {"node": node}
    for f in _STAGE_FIELDS.get(node, ()):
        if f not in delta or delta[f] is None:
            continue
        if f == "question":
            out.update(_flatten_question({"question": delta[f]}))
        elif f == "reaction":
            out["interviewer_reaction"] = delta[f]
        else:
            out[f] = delta[f]
    return out


@router.post("/answer/stream")
def answer_stream(req: InterviewAnswerReq):
    """节点级 SSE（R-16）：stage 事件推送评测/决策/出题进度，result 事件下发与 /answer 一致的终态。

    LangGraph 结构化输出无法 token 级流式，按节点粒度推进已足够驱动前端阶段条。
    """
    ctx = req.context or {}
    state = _answer_state(req)

    def gen():
        reset_llm_stats()
        final: dict = {}
        try:
            for node, delta in stream_interview_graph(state):
                final.update(delta)
                if node == "intent":
                    continue  # 意图识别属内部节点，无前端可见产物
                yield _sse("stage", _stage_payload(node, delta))
            yield _sse("result", _build_answer_payload(final, ctx))
        except Exception as e:  # 已发出的 stage 不回滚，前端收到 error 后自动回退同步接口
            logger.exception("流式面试流转失败")
            yield _sse("error", {"message": f"面试流程失败: {e}"})

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/hint")
def hint(req: InterviewHintReq):
    """分级提示：不推进状态机，仅针对当前开放题生成第N级提示（评测时会计入降档）"""
    reset_llm_stats()
    text = interviewer.generate_hint(
        question=req.question,
        reference_points=req.reference_points,
        level=req.level,
        asked_before=req.asked_before,
        topic=req.topic,
        persona=req.persona or "standard",
    )
    return ok({"hint": text, "level": req.level, "meta": get_llm_stats()})


@router.post("/end")
def end(req: InterviewEndReq):
    """提前结束：FINAL_REPORT（Coach复盘）"""
    reset_llm_stats()
    ctx = req.context or {}
    state = {
        "mode": "end", "session_id": req.session_id,
        "plan": ctx.get("plan") or {},
        "history": ctx.get("history") or [],
        # 训练计划可执行（R-10）：标签清单供 Coach 输出 suggested_tags
        "tag_catalog": ctx.get("tag_catalog") or [],
    }
    try:
        final = run_interview_graph(state)
    except Exception as e:
        logger.exception("复盘报告生成失败")
        raise HTTPException(status_code=500, detail=f"复盘报告生成失败: {e}")
    return ok({"report": final.get("report"), "action": "FINAL_REPORT",
               "meta": get_llm_stats()})
