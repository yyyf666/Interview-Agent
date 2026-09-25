"""Coach（训练教练Agent）：复盘报告 + 训练计划 + 长期记忆回写（设计文档 §3.5）"""
from __future__ import annotations

import json
import logging

from app.core.db import query
from app.llm.factory import structured_call
from app.models.schemas import FinalReport
from app.prompts import templates
from app.tools import registry as tools

logger = logging.getLogger(__name__)


def _level(score: float) -> str:
    return ("A" if score >= 85 else "B" if score >= 75 else
            "C" if score >= 60 else "D" if score >= 40 else "E")


def generate_report(session_id: int, plan: dict, history: list,
                    persona_csv: str = "standard",
                    tag_catalog: list | None = None) -> FinalReport:
    """FINAL_REPORT状态节点：汇总评测 -> 总报告/短板诊断/训练计划

    tag_catalog（R-10）：八股标签清单 [{id,label,skill}]，供训练任务按 id 引用
    （前端据此「去练习」预选标签）；不传时训练任务只给文字。
    """
    compact_history = [
        {
            "turn_no": h.get("turn_no"), "topic": h.get("topic"),
            "question": (h.get("question") or "")[:80],
            "score": h.get("total_score"), "issues": h.get("issues"),
            "skill_tags": h.get("skill_tags"),
            "interjection": bool(h.get("interjection")),
            "interjection_intent": h.get("interjection_intent"),
            "candidate_text": (h.get("answer") or "")[:60] if h.get("interjection") else None,
        } for h in history
    ]
    profile_row = query(
        """SELECT s.candidate_id, jp.focus_points, jp.parsed AS jd_parsed
           FROM interview_session s LEFT JOIN job_description jp ON jp.id = s.jd_id
           WHERE s.id = %s""", (session_id,))
    gauntlet = (plan or {}).get("mode") == "GAUNTLET"
    # 八股练习不写长期画像：即使未来带候选人，也不污染正式面试统计
    candidate_id = profile_row[0]["candidate_id"] if profile_row and not gauntlet else None
    focus = profile_row[0].get("focus_points") if profile_row else None

    # JD逐项要求清单（驱动 requirement_match 匹配表）
    jd_parsed = (profile_row[0].get("jd_parsed") or {}) if profile_row else {}
    if isinstance(jd_parsed, str):
        try:
            jd_parsed = json.loads(jd_parsed)
        except Exception:
            jd_parsed = {}
    requirements = [
        f"{r.get('skill', '')}：{r.get('description', '') or ''}".strip()
        for r in (jd_parsed.get("required_skills") or [])]

    n_personas = len(templates.parse_personas(persona_csv))
    panel_note = (f"本场考官团：{n_personas}位不同人格面试官轮流发言。\n"
                  if n_personas > 1 else "")
    catalog_note = ""
    if tag_catalog:
        catalog_note = (
            "可用八股标签清单（training_plan 每天的 suggested_tags 只能从中取 id，最多 2 个）："
            f"{json.dumps(tag_catalog, ensure_ascii=False)}\n"
            "每天同时给出 skill（与标签的 skill 字段一致）；"
            "无法对应到标签时 skill 留空、suggested_tags 留空数组，不要编造 id。\n"
        )
    prompt = (
        f"面试计划：{json.dumps(plan, ensure_ascii=False)}\n"
        + ("" if gauntlet else f"目标岗位考察重点：{json.dumps(focus, ensure_ascii=False)}\n")
        + ("" if gauntlet else
           (f"岗位JD要求清单（逐条输出 requirement_match）：{json.dumps(requirements, ensure_ascii=False)}\n"
            if requirements else ""))
        + f"各轮问答评测明细：\n{json.dumps(compact_history, ensure_ascii=False, default=str)}\n"
        + panel_note
        + catalog_note
        + ("本场为八股拷打练习（无JD/简历）：module_scores 按所选标签聚合，"
           "requirement_match 必须为空数组，训练计划聚焦所选标签的薄弱点。\n" if gauntlet else "")
    )
    report = structured_call(prompt, FinalReport, system=templates.COACH_SYSTEM, temperature=0.3)

    # 兜底：LLM未给出模块分时按技能聚合
    if not report.module_scores:
        agg: dict[str, list[float]] = {}
        for h in history:
            for tag in (h.get("skill_tags") or [h.get("topic") or "general"]):
                agg.setdefault(str(tag), []).append(float(h.get("total_score") or 0))
        report.module_scores = {k: round(sum(v) / len(v), 1) for k, v in agg.items() if v}
    if not report.overall_score and report.module_scores:
        report.overall_score = round(sum(report.module_scores.values())
                                     / len(report.module_scores), 1)

    _update_long_term_memory(candidate_id, report, history)
    return report


def _update_long_term_memory(candidate_id: int | None, report: FinalReport, history: list):
    """回写候选人长期画像：技能评级 + 薄弱点追踪（update_skill_rating 工具）"""
    if not candidate_id:
        return
    skill_ratings = {
        skill: {"score": score, "level": _level(score)}
        for skill, score in (report.module_scores or {}).items()
    }
    weak_points = [
        {"skill": w.skill, "root_cause": w.root_cause, "detail": w.detail}
        for w in report.weaknesses
    ]
    try:
        tools.update_skill_rating(candidate_id, skill_ratings, weak_points)
    except Exception as e:
        logger.error("长期记忆回写失败: %s", e)
