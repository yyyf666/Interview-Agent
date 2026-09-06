"""Interviewer（面试官Agent）：生成具体问题、执行追问、主导对话（设计文档 §3.3-2）

出题链路（设计文档 §6.3 增强生成）：
1. 加载当前主题 Skill 上下文（考察点清单/难度标准）
2. 题库语义检索（相似高分题直接复用）+ 知识库RAG背景检索
3. LLM 结构化生成问题与参考答题要点
"""
from __future__ import annotations

import json
import logging

from app.agents import director, intent_rules
from app.core.config import get_settings
from app.llm.factory import structured_call
from app.models.schemas import HintOut, InterjectOut, QuestionOut, ReactionOut
from app.prompts import templates
from app.rag import retriever
from app.skills.loader import load_skill

logger = logging.getLogger(__name__)

_REUSE_THRESHOLD = 0.85
_CODING_KEYWORDS = ("代码", "手写", "coding", "算法题", "编程", "实现一个")


def _is_coding_topic(topic_entry: dict) -> bool:
    """coding 标志兼容 LLM 漏置：计划层显式标记或主题/依据含编码关键词均视为代码题"""
    if topic_entry.get("coding"):
        return True
    text = f"{topic_entry.get('topic', '')}{topic_entry.get('reason', '')}".lower()
    return any(k in text for k in _CODING_KEYWORDS)


def _rag_context(query_text: str, skill: str) -> str:
    """提问前检索知识背景，保证问题专业性（严格控制上下文长度）"""
    try:
        docs = retriever.search_knowledge(query_text, skill=skill, top_k=2)
        return "\n".join(f"- {d['content'][:200]}" for d in docs)
    except Exception as e:
        logger.warning("知识背景检索失败(降级无背景): %s", e)
        return ""


def _bank_candidates(topic: str, skill: str, difficulty: int) -> list[dict]:
    try:
        return retriever.search_questions(f"{topic} {skill}", skill=skill, top_k=5)
    except Exception as e:
        logger.warning("题库检索失败: %s", e)
        return []


def ask_next(plan: dict, history: list, prev_action: str = "",
             evaluation: dict | None = None, persona: str = "standard") -> QuestionOut:
    """QUESTION / NEXT_TOPIC / LOWER_DIFFICULTY 动作下的出题（人格风格注入 + 代码题分支）"""
    topic_entry, _ = director.current_topic_entry(plan, history)
    if topic_entry is None:
        topic_entry = {"topic": "自由交流", "skill": "general",
                       "question_count": 1, "base_difficulty": 2}
    topic = topic_entry["topic"]
    skill = topic_entry.get("skill", "")
    difficulty = director.adjusted_difficulty(topic_entry, history, topic)
    if prev_action == "LOWER_DIFFICULTY":
        difficulty = max(1, difficulty - 1)
    elif prev_action == "NEXT_QUESTION":
        difficulty = min(5, difficulty + 1)

    gauntlet = (plan or {}).get("mode") == "GAUNTLET"
    skill_ctx = load_skill(skill)
    asked = {h.get("question") for h in history}
    # 八股模式只考知识题，避免「并发编程」等标签被误判为代码题
    coding = _is_coding_topic(topic_entry) and not gauntlet

    # 1) 题库高相似题直接复用
    for q in _bank_candidates(topic, skill, difficulty):
        if (q.get("content") not in asked and float(q.get("score") or 0) >= _REUSE_THRESHOLD
                and abs(int(q.get("difficulty") or 2) - difficulty) <= 1):
            points = q.get("scoring_points") or []
            if isinstance(points, str):
                points = json.loads(points)
            return QuestionOut(
                question=q["content"], topic=topic, skill=skill,
                difficulty=int(q.get("difficulty") or 2),
                question_type=q.get("q_type") or "concept",
                reference_points=list(points)[:6], source="question_bank")

    # 2) LLM 生成（注入 Skill上下文 + RAG背景 + 人格风格，避开已问过的题）
    knowledge = _rag_context(f"{topic} 面试题", skill)
    prompt = (
        f"当前考察主题：{topic}（技能：{skill}，目标难度：{difficulty}/5）\n"
        f"面试计划依据：{topic_entry.get('reason', '')}\n"
        f"{skill_ctx.as_prompt_block()}\n"
        f"相关专业知识背景：\n{knowledge or '（无）'}\n"
        f"本场已问过的问题（禁止重复）：{json.dumps(list(asked), ensure_ascii=False)}\n"
        f"候选人水平参考：{json.dumps((evaluation or {}).get('skill_tags', []), ensure_ascii=False)}"
    )
    if gauntlet:
        base = templates.GAUNTLET_INTERVIEWER_SYSTEM
    elif coding:
        base = templates.CODE_INTERVIEWER_SYSTEM
    else:
        base = templates.INTERVIEWER_SYSTEM
    system = base + templates.persona_block(persona)
    out = structured_call(prompt, QuestionOut, system=system)
    out.topic = out.topic or topic
    out.skill = out.skill or skill
    out.difficulty = out.difficulty or difficulty
    if coding:
        out.question_type = "code"
    return out


def _skill_of_topic(plan: dict, topic: str) -> str:
    for t in (plan or {}).get("topics") or []:
        if t.get("topic") == topic:
            return t.get("skill") or "general"
    return "general"


def ask_follow_up(current: dict, evaluation: dict, plan: dict | None = None,
                  persona: str = "standard") -> QuestionOut:
    """FOLLOW_UP 动作：针对回答漏洞生成追问（设计文档 §3.2 动态追问机制）"""
    issues = evaluation.get("issues") or []
    direction = evaluation.get("follow_up_direction") or (issues[0] if issues else "回答不完整之处")
    topic = current.get("topic") or ""
    skill = _skill_of_topic(plan or {}, topic)
    difficulty = min(5, int(current.get("difficulty") or 2))

    system = templates.FOLLOW_UP_SYSTEM.format(
        direction=direction, issues="；".join(issues[:3]) or "细节缺失",
        topic=topic, skill=skill, difficulty=difficulty) + templates.persona_block(persona)
    prompt = (
        f"上一题：{current.get('question', '')}\n"
        f"候选人回答：{current.get('answer', '')}\n"
        f"参考答题要点：{json.dumps(current.get('reference_points', []), ensure_ascii=False)}\n"
        f"{load_skill(skill).as_prompt_block()}"
    )
    out = structured_call(prompt, QuestionOut, system=system)
    out.topic = topic
    out.skill = skill
    out.difficulty = difficulty
    out.source = "generated"
    return out


# 决策动作 -> 回应的衔接要求
_ACTION_HINTS = {
    "FOLLOW_UP": "简短点出存疑之处，然后表示要再深挖一点",
    "NEXT_QUESTION": "简评本题后表示继续下一题",
    "NEXT_TOPIC": "收尾当前话题，表示换个新方向考察",
    "LOWER_DIFFICULTY": "表示本题答得不够理想，先换道更基础的题",
    "FINAL_REPORT": "表示感谢，面试问答环节进入收尾",
}


def react_to_answer(current: dict, answer: str, evaluation: dict,
                    action: str, persona: str = "standard") -> str:
    """面试官现场回应：以人格化的第一人称点评刚完成的回答并衔接下一步"""
    if not (current.get("question") and answer):
        return ""
    system = templates.REACTION_SYSTEM.format(
        action_hint=_ACTION_HINTS.get(action, "自然衔接到下一步考察"),
        persona_style=templates.PERSONA_STYLES.get(persona, templates.PERSONA_STYLES["standard"]))
    prompt = (
        f"面试官提问：{current.get('question', '')}\n"
        f"候选人回答：{answer[:800]}\n"
        f"评分总分：{float(evaluation.get('total') or 0):.0f}\n"
        f"回答中的问题点：{'；'.join(evaluation.get('issues') or [])[:200] or '无明显问题'}"
    )
    try:
        out = structured_call(prompt, ReactionOut, system=system, temperature=0.6)
        if out.reply:
            return out.reply.strip()[:120]
    except Exception as e:
        logger.warning("面试官现场回应生成失败(降级通用回复): %s", e)
    return "嗯，了解了，我们继续。"


# 分级提示的代码侧降级文案（LLM不可用时仍可用）
_HINT_FALLBACK = {
    1: "建议先从该概念的定义和设计动机入手，想想它在什么场景下会被触发。",
    2: "关键点：至少应覆盖它的核心机制与一个典型应用场景。",
    3: "参考思路：{points}",
}


def generate_hint(question: str, reference_points: list[str], level: int,
                  asked_before: int = 0, topic: str = "",
                  persona: str = "standard") -> str:
    """分级提示：1=方向 2=关键点 3=揭晓参考思路（从考核变教练）"""
    system = templates.HINT_SYSTEM.format(
        level=level, asked_before=asked_before) + templates.persona_block(persona)
    prompt = (
        f"当前主题：{topic}\n题目：{question}\n"
        f"参考答题要点（你绝不能越级透露第{level}级以外的内容）："
        f"{json.dumps(reference_points, ensure_ascii=False)}"
    )
    try:
        out = structured_call(prompt, HintOut, system=system, temperature=0.4)
        if out.hint:
            return out.hint.strip()[:200]
    except Exception as e:
        logger.warning("提示生成失败，降级静态提示: %s", e)
    fallback = _HINT_FALLBACK.get(level, _HINT_FALLBACK[1])
    return fallback.format(points="；".join((reference_points or [])[:2]) or "参见题目相关概念")


# ============================================================ 插话意图识别与响应

def classify_and_interject(current: dict, text: str, history: list,
                           persona: str = "standard") -> InterjectOut:
    """判定候选人输入是实质回答还是元请求（插话）。

    降本：先过高置信规则层（intent_rules），命中即短路不进 LLM。
    fail-open 设计：任何异常/不确定都按 answer 处理，保证主评测链路不受损。
    """
    if get_settings().intent_rules_enabled:
        hit = intent_rules.rule_classify(text)
        if hit is not None:
            logger.info("intent规则短路: %s", hit.intent)
            return hit
    system = templates.INTENT_SYSTEM.format(
        persona_style=templates.PERSONA_STYLES.get(persona, templates.PERSONA_STYLES["standard"]))
    recent = [f"第{h.get('turn_no')}轮({h.get('topic')}): {(h.get('question') or '')[:40]}"
              for h in (history or [])[-3:]]
    prompt = (
        f"近期面试进程：{'；'.join(recent) or '（本场第一题）'}\n"
        f"面试官当前的问题：{current.get('question', '')}\n"
        f"候选人这条输入：{text}"
    )
    try:
        out = structured_call(prompt, InterjectOut, system=system, temperature=0.0)
        if out.intent == "answer":
            return InterjectOut(intent="answer")  # 丢弃偶然输出的reply，防污染
        if not out.reply:
            out.reply = "好的，我听到你的想法了，我们按你说的来。"
        logger.info("意图识别: %s | spec=%s", out.intent, out.question_spec[:40])
        return out
    except Exception as e:
        logger.warning("意图识别失败，按正常回答处理: %s", e)
        return InterjectOut(intent="answer")


_SPEC_LANGS = ("java", "python", "javascript", "go", "c++")


def ask_per_spec(plan: dict, spec: str, current: dict,
                 persona: str = "standard") -> QuestionOut:
    """intent=request_question：按候选人指定的题目要求出题（仍锚定在计划技能域内优先）。"""
    spec = (spec or "").strip() or "与当前考察主题相关的、换一种角度的新题"
    spec_lower = spec.lower()
    gauntlet = (plan or {}).get("mode") == "GAUNTLET"
    coding = any(k in spec_lower for k in _CODING_KEYWORDS) and not gauntlet
    skill = _skill_of_topic(plan, current.get("topic") or "")
    for lang in _SPEC_LANGS:
        if lang in spec_lower:
            skill = lang if coding or lang in ("java", "python") else skill
            break
    topic = f"{current.get('topic') or '候选人指定题目'}（换题请求）"
    difficulty = int(current.get("difficulty") or 2)
    knowledge = _rag_context(spec, skill)
    prompt = (
        f"候选人主动要求更换题目，其原话诉求：{spec}\n"
        f"请据此出一道符合其要求的新题（尽量贴合，同时保持在合理考察范围内）。\n"
        f"当前主题域：{topic}（技能：{skill}，建议难度：{difficulty}/5）\n"
        f"{load_skill(skill).as_prompt_block()}\n"
        f"相关专业知识背景：\n{knowledge or '（无）'}\n"
        f"本场已问过的问题（禁止重复）：{json.dumps([current.get('question', '')], ensure_ascii=False)}"
    )
    if gauntlet:
        base = templates.GAUNTLET_INTERVIEWER_SYSTEM
    elif coding:
        base = templates.CODE_INTERVIEWER_SYSTEM
    else:
        base = templates.INTERVIEWER_SYSTEM
    out = structured_call(prompt, QuestionOut, system=base + templates.persona_block(persona))
    out.topic = out.topic or topic
    out.skill = out.skill or skill
    out.difficulty = out.difficulty or difficulty
    if coding:
        out.question_type = "code"
    out.source = "generated"
    return out


def explain_question(current: dict, ask_text: str,
                     persona: str = "standard") -> str:
    """clarify：解释题意后重述原题（不泄露参考要点）。"""
    system = ("你是面试官，候选人对你出的题目提出了疑问。请用一句话澄清题目到底在问什么、"
              "期望从哪些角度作答，但严禁透露答案要点。保持人设风格，50字以内。"
              + templates.persona_block(persona)
              + '输出JSON：{"reply": "题意澄清原文"}')
    prompt = f"题目：{current.get('question', '')}\n候选人的疑问：{ask_text[:200]}"
    try:
        out = structured_call(prompt, ReactionOut, system=system, temperature=0.3)
        if out.reply:
            return out.reply.strip()[:120]
    except Exception as e:
        logger.warning("题意澄清失败: %s", e)
    return "这道题是想了解你对该知识点的理解深度，按你熟悉的方面展开讲就可以。"
