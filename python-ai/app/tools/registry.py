"""MCP 工具层实现（设计文档 §8.1）

8个标准化工具的规范实现，直接访问 PostgreSQL 实现数据与能力解耦。
默认由 Agent 进程内直调（MCP_ENABLED=false）；
MCP_ENABLED=true 时经 app/mcp/server.py 以 MCP 协议对外暴露，实现同一套函数。
所有调用留痕日志（入参出参摘要）便于调试。
"""
from __future__ import annotations

import json
import logging

from app.core.db import execute, query

logger = logging.getLogger("mcp.tools")


def _log_call(name: str, args: dict, result_summary: str) -> None:
    logger.info("[TOOL] %s args=%s -> %s", name,
                json.dumps(args, ensure_ascii=False, default=str)[:200], result_summary)


# -------------------------------------------------------- get_resume

def get_resume(resume_id: int) -> dict:
    rows = query(
        "SELECT id, candidate_id, file_name, parsed, created_at FROM resume WHERE id = %s",
        (resume_id,))
    result = rows[0] if rows else {}
    _log_call("get_resume", {"resume_id": resume_id}, f"hit={bool(result)}")
    return result


# -------------------------------------------------------- get_job_description

def get_job_description(jd_id: int) -> dict:
    rows = query(
        "SELECT id, title, company, parsed, skill_weights, focus_points FROM job_description WHERE id = %s",
        (jd_id,))
    result = rows[0] if rows else {}
    _log_call("get_job_description", {"jd_id": jd_id}, f"hit={bool(result)}")
    return result


# -------------------------------------------------------- get_candidate_profile

def get_candidate_profile(candidate_id: int) -> dict:
    rows = query(
        """SELECT id, nickname, target_role, years_of_experience, skill_tags,
                  skill_ratings, total_interviews, avg_score, weak_points
           FROM candidate_profile WHERE id = %s""",
        (candidate_id,))
    result = rows[0] if rows else {}
    _log_call("get_candidate_profile", {"candidate_id": candidate_id}, f"hit={bool(result)}")
    return result


# -------------------------------------------------------- search_question_bank

def search_question_bank(skill: str = "", difficulty: int = 0, tags: str = "",
                         limit: int = 10) -> list[dict]:
    sql = """SELECT id, content, skill_name, difficulty, q_type, tags,
                    reference_answer, scoring_points
             FROM question_bank
             WHERE status = 'ACTIVE'
               AND (%s = '' OR skill_name = %s)
               AND (%s = 0 OR difficulty = %s)
             ORDER BY id LIMIT %s"""
    d = int(difficulty or 0)
    rows = query(sql, (skill or "", skill or "", d, d, limit))
    _log_call("search_question_bank",
              {"skill": skill, "difficulty": difficulty}, f"count={len(rows)}")
    return rows


# -------------------------------------------------------- search_knowledge_base

def search_knowledge_base(query_text: str, skill: str = "", top_k: int = 3) -> list[dict]:
    from app.rag.retriever import search_knowledge  # 延迟导入避免环

    rows = search_knowledge(query_text, skill or None, top_k)
    _log_call("search_knowledge_base", {"q": query_text[:30], "skill": skill},
              f"count={len(rows)}")
    return [{"content": r["content"], "score": r.get("score"),
             "document_id": r.get("document_id")} for r in rows]


# -------------------------------------------------------- save_evaluation

def save_evaluation(session_id: int, turn_id: int, evaluation: dict) -> int:
    """保存单轮评测结果（默认链路评测由Java回写，此工具供MCP模式Agent直存）"""
    rows = query(
        """INSERT INTO evaluation (session_id, turn_id, score_accuracy, score_completeness,
                score_depth, score_logic, score_expression, score_tradeoff, total_score,
                basis, issues, should_follow_up, follow_up_direction, skill_tags, decision_reason)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb,%s,%s,%s::jsonb,%s)
           RETURNING id""",
        (session_id, turn_id,
         evaluation.get("accuracy"), evaluation.get("completeness"), evaluation.get("depth"),
         evaluation.get("logic"), evaluation.get("expression"), evaluation.get("tradeoff"),
         evaluation.get("total"),
         json.dumps(evaluation.get("basis", []), ensure_ascii=False),
         json.dumps(evaluation.get("issues", []), ensure_ascii=False),
         bool(evaluation.get("should_follow_up")),
         evaluation.get("follow_up_direction", ""),
         json.dumps(evaluation.get("skill_tags", []), ensure_ascii=False),
         evaluation.get("decision_reason", "")))
    new_id = rows[0]["id"] if rows else 0
    _log_call("save_evaluation", {"session": session_id, "turn": turn_id}, f"id={new_id}")
    return new_id


# -------------------------------------------------------- search_interview_history

def search_interview_history(candidate_id: int, limit: int = 5) -> list[dict]:
    rows = query(
        """SELECT s.id, s.title, s.status, s.overall_score, s.started_at, s.finished_at,
                  s.current_topic
           FROM interview_session s
           WHERE s.candidate_id = %s AND s.status IN ('COMPLETED','RUNNING')
           ORDER BY s.id DESC LIMIT %s""",
        (candidate_id, limit))
    _log_call("search_interview_history", {"candidate_id": candidate_id}, f"count={len(rows)}")
    return rows


# -------------------------------------------------------- update_skill_rating

def update_skill_rating(candidate_id: int, skill_ratings: dict,
                        weak_points: list | None = None) -> bool:
    """更新候选人技能评级与薄弱点（Coach 复盘回写长期记忆）"""
    if skill_ratings:
        execute(
            """UPDATE candidate_profile
               SET skill_ratings = COALESCE(skill_ratings, '{}'::jsonb) || %s::jsonb
               WHERE id = %s""",
            (json.dumps(skill_ratings, ensure_ascii=False), candidate_id))
    if weak_points is not None:
        execute(
            "UPDATE candidate_profile SET weak_points = %s::jsonb WHERE id = %s",
            (json.dumps(weak_points, ensure_ascii=False), candidate_id))
    _log_call("update_skill_rating",
              {"candidate_id": candidate_id, "skills": list(skill_ratings or {})}, "ok")
    return True


# ======================================================== 工具清单（供MCP注册/文档）

TOOL_REGISTRY = {
    "get_resume": get_resume,
    "get_job_description": get_job_description,
    "get_candidate_profile": get_candidate_profile,
    "search_question_bank": search_question_bank,
    "search_knowledge_base": search_knowledge_base,
    "save_evaluation": save_evaluation,
    "search_interview_history": search_interview_history,
    "update_skill_rating": update_skill_rating,
}


def call_tool(name: str, **kwargs):
    """统一工具调用入口（MCP_ENABLED=false 时进程内直调）"""
    fn = TOOL_REGISTRY.get(name)
    if fn is None:
        raise KeyError(f"未知工具: {name}")
    return fn(**kwargs)
