"""Interview Director（面试主管Agent）：全局流程控制与决策（设计文档 §3.3-1 / §6.2）

- PLAN 阶段：技能缺口分析 + 面试计划生成（LLM，输出可解释依据）
- DECIDE 阶段：确定性规则决策（保证评分-动作映射稳定，不依赖LLM）
"""
from __future__ import annotations

import json
import logging

from app.llm.factory import structured_call
from app.models.schemas import GapAnalysis, InterviewPlan, ReplanOut
from app.prompts import templates

logger = logging.getLogger(__name__)

# 决策阈值（设计文档 §6.2）
HIGH_SCORE = 85.0
LOW_SCORE = 60.0
MAX_FOLLOW_UPS = 2


def analyze_gap(resume: dict, jd: dict) -> GapAnalysis:
    prompt = (
        f"候选人简历解析结果：\n{json.dumps(resume.get('parsed', {}), ensure_ascii=False)}\n\n"
        f"目标岗位能力模型：\n{json.dumps(jd.get('parsed', {}), ensure_ascii=False)}"
    )
    return structured_call(prompt, GapAnalysis, system=templates.GAP_ANALYSIS_SYSTEM)


def make_plan(resume: dict, jd: dict, profile: dict, persona: str = "standard",
              gap: dict | None = None, options: dict | None = None) -> InterviewPlan:
    """PLAN状态节点：缺口分析 -> 初始面试大纲（主题顺序/题量/难度权重）

    gap 非空（前端已做开前缺口分析并确认）时直接复用，跳过二次 LLM 调用。
    options（R-08）为候选人高级配置，作为额外约束段追加到计划提示词。
    """
    gap_data = gap if gap else analyze_gap(resume, jd).model_dump()
    weak_history = profile.get("weak_points") or []
    prompt = (
        f"缺口分析：{json.dumps(gap_data, ensure_ascii=False)}\n"
        f"候选人画像（技能评级）：{json.dumps(profile.get('skill_ratings', {}), ensure_ascii=False)}\n"
        f"历史薄弱点：{json.dumps(weak_history, ensure_ascii=False)}\n"
        f"工作年限：{resume.get('parsed', {}).get('years_of_experience', '未知')}"
    )
    plan = structured_call(prompt, InterviewPlan,
                           system=templates.plan_system(options) + templates.persona_block(persona),
                           temperature=0.4)
    # gap 由代码注入，不要求 LLM 输出，保证与前端展示的分析快照一致
    plan.gap = gap_data
    # options 同理：以 Java 归一化结果为准，保证刷新后前端 chip 可复现
    plan.options = options or {}
    if not plan.topics:  # 兜底：保证流程可继续
        plan.topics = [
            {"topic": "技术基础", "skill": "java", "question_count": 3,
             "base_difficulty": 2, "reason": "默认计划"}]
    return plan


def max_follow_ups(plan: dict | None) -> int:
    """单题最大追问次数（R-08）：按 plan.options.followup_level 映射，缺省标准=2。"""
    level = ((plan or {}).get("options") or {}).get("followup_level") or "standard"
    return templates.FOLLOWUP_LIMITS.get(level, MAX_FOLLOW_UPS)


# ============================================================ 进度计算

# 消耗全局题量预算的插话意图（防止无限换题/跳过循环；hint/clarify/off_topic 不消耗）
_BUDGET_CONSUMING_INTERJECTIONS = ("skip", "request_question")


def is_real_answer(h: dict) -> bool:
    """非追问且非插话的条目才计入主题进度与 replan 统计"""
    return not h.get("is_follow_up") and not h.get("interjection")


def topic_progress(plan: dict, history: list) -> dict[str, int]:
    """统计各主题已出题数（不含追问与插话轮）"""
    asked: dict[str, int] = {}
    for h in history:
        if is_real_answer(h):
            topic = h.get("topic") or ""
            asked[topic] = asked.get(topic, 0) + 1
    return asked


def current_topic_entry(plan: dict, history: list) -> tuple[dict | None, bool]:
    """返回(当前应考察的主题计划, 是否所有主题已完成)

    八股拷打：所选标签按已答真实题数轮转，不按单主题题量耗尽后收尾；
    收尾只由 total_question_budget（30题软上限）或候选人主动结束触发。
    """
    topics = (plan or {}).get("topics") or []
    if (plan or {}).get("mode") == "GAUNTLET" and topics:
        asked_total = sum(1 for h in history if is_real_answer(h))
        return topics[asked_total % len(topics)], False
    asked = topic_progress(plan, history)
    for t in topics:
        if asked.get(t["topic"], 0) < int(t.get("question_count", 2)):
            return t, False
    return (topics[-1] if topics else None), True


def adjusted_difficulty(topic_entry: dict, history: list, topic: str) -> int:
    """难度自适应：依据该主题近期表现 ±1，限幅1-5"""
    base = int(topic_entry.get("base_difficulty", 2))
    recent = [h.get("total_score") for h in history
              if h.get("topic") == topic and h.get("total_score") is not None][-2:]
    if recent:
        avg = sum(float(s) for s in recent) / len(recent)
        if avg >= HIGH_SCORE:
            base += 1
        elif avg < LOW_SCORE:
            base -= 1
    return max(1, min(5, base))


# ============================================================ 有界重规划

REPLAN_EVERY = 3  # 每完成3道主题内标题触发一次 re-plan


def need_replan(history: list) -> bool:
    asked = sum(1 for h in (history or []) if is_real_answer(h))
    return asked > 0 and asked % REPLAN_EVERY == 0


def replan(plan: dict, history: list, persona: str = "standard") -> dict:
    """静态计划->有界重规划：仅调整未消费完的剩余主题，已完成主题锁定不动。

    有界性保障（防计划震荡）：
    - 已完成主题不变更不删；新主题最多+2；单主题题量限幅1-6；总预算不变
    - LLM失败时返回原计划（重规划是锦上添花，不是必经路径）
    """
    topics = (plan or {}).get("topics") or []
    asked = topic_progress(plan, history)
    done = [t for t in topics if asked.get(t["topic"], 0) >= int(t.get("question_count", 2))]
    remaining = [t for t in topics if t not in done]
    if len(remaining) < 2:
        return plan  # 剩余主题不足两个，无重规划价值
    score_rows = [
        {"turn_no": h.get("turn_no"), "topic": h.get("topic"), "score": h.get("total_score"),
         "issues": (h.get("issues") or [])[:1]}
        for h in history if not h.get("is_follow_up")]
    prompt = (
        f"已完成主题及其得分表现：{json.dumps(score_rows, ensure_ascii=False)}\n"
        f"剩余未完成任务：{json.dumps(remaining, ensure_ascii=False)}"
    )
    try:
        out = structured_call(
            prompt, ReplanOut,
            system=templates.REPLAN_SYSTEM.format(
                persona_style=templates.PERSONA_STYLES.get(persona, templates.PERSONA_STYLES["standard"])),
            temperature=0.3)
        adjusted = out.topics[:len(remaining) + 2]  # 硬限幅：最多新增2个
        if not adjusted:
            return plan
        new_plan = dict(plan)
        new_plan["topics"] = done + [t.model_dump() for t in adjusted]
        logger.info("session re-plan：剩余%s个主题 -> %s（%s）",
                    len(remaining), [t.topic for t in adjusted], out.reason)
        return new_plan
    except Exception as e:
        logger.warning("重规划失败，沿用原计划: %s", e)
        return plan


# ============================================================ DECIDE：规则决策

def decide(state: dict) -> tuple[str, str]:
    """
    Director读取当前状态并输出(动作, 决策依据)，规则见设计文档 §6.2：
    - 得分>=85且无明显漏洞 -> 提升难度继续/切换下一主题
    - 60<=得分<85且有局部漏洞且追问<2 -> 针对漏洞追问
    - 得分<60 -> 降低难度换基础题
    - 主题题量完成/追问超限 -> 下一主题；全部完成 -> 收尾
    """
    evaluation = state.get("evaluation") or {}
    plan = state.get("plan") or {}
    history = list(state.get("history") or [])
    current = state.get("current") or {}
    total = float(evaluation.get("total") or 0)
    issues = evaluation.get("issues") or []
    topic = current.get("topic") or ""

    # 注：Java侧buildContext已将刚回答的当前轮并入history，此处直接统计
    # 插话轮不计主题进度；skip/换题类插话消耗全局题量预算（防无限挑题）
    asked_here = sum(1 for h in history if h.get("topic") == topic and is_real_answer(h))
    topic_entry, all_done = current_topic_entry(plan, history)
    budget = int(plan.get("total_question_budget", 10))
    asked_total = sum(1 for h in history if not h.get("is_follow_up") and (
        is_real_answer(h) or h.get("interjection_intent") in _BUDGET_CONSUMING_INTERJECTIONS))

    if all_done or asked_total >= budget:
        return "FINAL_REPORT", f"全部计划主题完成或达到题量预算({asked_total})，进入复盘"

    # 当前主题的追问次数
    follow_ups = sum(1 for h in history if h.get("is_follow_up") and h.get("topic") == topic)
    follow_up_limit = max_follow_ups(plan)

    if total < LOW_SCORE:
        if follow_ups >= follow_up_limit or asked_here >= int((topic_entry or {}).get("question_count", 2)):
            return "NEXT_TOPIC", f"得分{total:.0f}低于60且主题已探索{follow_ups}次追问，记录薄弱点并切换主题"
        return "LOWER_DIFFICULTY", f"得分{total:.0f}低于60，基础薄弱，降低难度换基础题巩固"

    if total < HIGH_SCORE and issues and follow_ups < follow_up_limit:
        return "FOLLOW_UP", f"得分{total:.0f}且存在局部漏洞({len(issues)}项)，针对『{evaluation.get('follow_up_direction') or issues[0]}』追问第{follow_ups + 1}次"

    if asked_here >= int((topic_entry or {}).get("question_count", 2)):
        return "NEXT_TOPIC", f"得分{total:.0f}表现良好且主题题量完成，切换下一主题"

    return "NEXT_QUESTION", f"得分{total:.0f}且无明显漏洞，主题内提升难度继续考察"
