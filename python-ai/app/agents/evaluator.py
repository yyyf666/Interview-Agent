"""Evaluator（评测官Agent）：实时六维度结构化评分（设计文档 §3.4 / §10）

一致性保障（§10.3）：
1. 统一Prompt + Few-Shot刻度校准（见 prompts/templates.py）
2. 总分强制按权重重算，不信任LLM算术
3. 极端分数（<15 或 >95）自动二次校验
4. 同一场面试同维度分数波动限制在20分内（漂移钳制）
增强的可解释性：
5. 清单聚合：逐参考要点判定命中，命中分混合进 accuracy/completeness（分数可复现）
6. 决策边界双评委：总分落在追问/难度决策阈值附近（60/85±5）时二次独立评分取均值
7. 防背书：答案与参考要点 n-gram 相似度过高时标记 suspected_recital 并压低准确性
8. 降本：主评与清单判定并发（线程池 + 独立 copy_context 保住 trace 计数），
   双评委按 eval_double_judge_ratio 抽样，极端分复核由 eval_extreme_recheck 控制
"""
from __future__ import annotations

import contextvars
import hashlib
import json
import logging
from concurrent.futures import ThreadPoolExecutor

from app.core.config import get_settings
from app.llm.factory import structured_call
from app.models.schemas import ChecklistOut, EvaluationOut
from app.prompts import templates
from app.rag import retriever
from app.skills.loader import load_skill

logger = logging.getLogger(__name__)

WEIGHTS = {
    "accuracy": 0.30, "completeness": 0.20, "depth": 0.20,
    "logic": 0.15, "expression": 0.10, "tradeoff": 0.05,
}
DRIFT_LIMIT = 20.0
EXTREME_LOW, EXTREME_HIGH = 15.0, 95.0
BOUNDARY_BANDS = [(55.0, 65.0), (80.0, 90.0)]  # 追问(≥60)/升级(≥85)决策阈值附近
CHECKLIST_BLEND = 0.4   # 清单命中分在 accuracy/completeness 中的混入权重
RECITAL_SIMILARITY = 0.55
RECITAL_MIN_LEN = 120

_CALIBRATION_SYSTEM = """你是面试评测质量校验员。原始评测给出了极端分数，请复核回答是否确实值得该分数。
若回答确实为空/完全错误可维持低分；若存在低估/高估请修正。输出与原始评测相同结构的JSON，只输出JSON。
注意：evidence 字段必须逐字摘录候选人回答原文。"""

_SECOND_REVIEWER_SYSTEM = """你是另一位独立面试评测官，与第一位评测官互不知晓对方的评分。
请独立给出六维度评分，宁可严格不要宽松。输出与下述结构相同的JSON，只输出JSON。"""

_pool: ThreadPoolExecutor | None = None


def _executor() -> ThreadPoolExecutor:
    """评测子调用线程池（LLM 往返为 IO 密集，并发度按 eval_pool_size 配置）"""
    global _pool
    if _pool is None:
        _pool = ThreadPoolExecutor(max_workers=max(2, get_settings().eval_pool_size),
                                   thread_name_prefix="eval")
    return _pool


def _submit(fn, *args):
    """提交子调用：独立复制上下文，使 _LlmStats 的可变累加器在子线程内原地累加后仍可被外层读到。"""
    ctx = contextvars.copy_context()
    return _executor().submit(ctx.run, fn, *args)


def _sample_hit(question: str, answer: str, ratio: float) -> bool:
    """按内容哈希确定抽样：同题重跑结果稳定，便于回归比对"""
    if ratio <= 0:
        return False
    if ratio >= 1:
        return True
    digest = hashlib.sha1(f"{question}\n{answer}".encode("utf-8")).digest()
    return digest[0] / 255.0 < ratio


def _weighted_total(ev: EvaluationOut) -> float:
    return round(sum(getattr(ev, k) * w for k, w in WEIGHTS.items()), 2)


def _clamp(ev: EvaluationOut) -> EvaluationOut:
    for k in WEIGHTS:
        setattr(ev, k, max(0.0, min(100.0, float(getattr(ev, k)))))
    ev.total = _weighted_total(ev)
    return ev


def _drift_control(ev: EvaluationOut, prev_scores: dict | None) -> tuple[EvaluationOut, bool]:
    """同一场面试同维度波动不超过20分"""
    if not prev_scores:
        return ev, False
    adjusted = False
    for k in WEIGHTS:
        prev = prev_scores.get(k)
        if prev is None:
            continue
        cur = float(getattr(ev, k))
        if abs(cur - float(prev)) > DRIFT_LIMIT:
            setattr(ev, k, float(prev) + DRIFT_LIMIT if cur > float(prev)
                    else float(prev) - DRIFT_LIMIT)
            adjusted = True
    if adjusted:
        ev.total = _weighted_total(ev)
    return ev, adjusted


def _knowledge_basis(question: str, skill: str) -> str:
    """评测基准：检索标准答案要点作为打分依据（设计文档 §9.3-2）"""
    try:
        docs = retriever.search_knowledge(question, skill=skill or None, top_k=2)
        lines = [d["content"][:250] for d in docs]
        return "\n".join(f"- {l}" for l in lines)
    except Exception as e:
        logger.warning("评测知识基准检索失败: %s", e)
        return ""


def _checklist_score(reference_points: list[str], question: str, answer: str,
                     skill_block: str, knowledge: str) -> tuple[float | None, ChecklistOut | None]:
    """清单聚合：逐要点判定命中 → 可复现的覆盖率分"""
    points = [p for p in reference_points if p and p.strip()]
    if not points or not answer or not answer.strip():
        return 0.0 if points else None, None
    try:
        cl = structured_call(
            f"问题：{question}\n参考答题要点：{json.dumps(points, ensure_ascii=False)}\n"
            f"{skill_block}\n候选人回答：{answer}",
            ChecklistOut, system=templates.CHECKLIST_SYSTEM, temperature=0.0)
        n = len(cl.items) or len(points)
        hit = sum(1.0 if i.hit == "full" else 0.5 if i.hit == "partial" else 0.0
                  for i in cl.items)
        return round(hit / max(n, 1) * 100, 1), cl
    except Exception as e:
        logger.warning("清单聚合评测失败，降级为纯LLM评分: %s", e)
        return None, None


def _ngrams(text: str, n: int = 3) -> set:
    text = "".join(text.split()).lower()
    return {text[i:i + n] for i in range(max(len(text) - n + 1, 0))}


def _recital_check(answer: str, reference_points: list[str]) -> bool:
    """防背书：答案与参考要点字符3-gram Jaccard相似度过高 → 疑似背诵"""
    ref = "".join(reference_points or [])
    if len(answer) < RECITAL_MIN_LEN or not ref:
        return False
    a, b = _ngrams(answer), _ngrams(ref)
    if not a or not b:
        return False
    sim = len(a & b) / len(a | b)
    logger.info("背诵相似度=%.3f", sim)
    return sim > RECITAL_SIMILARITY


def _blend_checklist(ev: EvaluationOut, ck_score: float | None) -> EvaluationOut:
    """清单命中分混入 accuracy/completeness，并写入可解释依据"""
    if ck_score is None or not ev.checklist:
        return ev
    ev.accuracy = round(ev.accuracy * (1 - CHECKLIST_BLEND) + ck_score * CHECKLIST_BLEND, 1)
    ev.completeness = round(ev.completeness * (1 - CHECKLIST_BLEND) + ck_score * CHECKLIST_BLEND, 1)
    full = sum(1 for i in ev.checklist if i.hit == "full")
    partial = sum(1 for i in ev.checklist if i.hit == "partial")
    ev.basis.append(f"[清单] 要点命中 {full}/{len(ev.checklist)} 完整、{partial} 部分，"
                    f"覆盖率分 {ck_score}（按{CHECKLIST_BLEND:.0%}混入准确性/完整度）")
    return ev


def _align_evidence(ev: EvaluationOut) -> EvaluationOut:
    """evidence 与 issues 长度对齐（多截少补）"""
    if len(ev.evidence) != len(ev.issues):
        ev.evidence = (ev.evidence + [""] * len(ev.issues))[:len(ev.issues)]
    return ev


def _near_boundary(total: float) -> bool:
    return any(lo <= total <= hi for lo, hi in BOUNDARY_BANDS)


def evaluate(question: str, answer: str, reference_points: list[str],
             skill: str, topic: str, difficulty: int,
             prev_scores: dict | None = None, hint_used: int = 0) -> EvaluationOut:
    skill_block = load_skill(skill).as_prompt_block()
    knowledge = _knowledge_basis(question, skill)
    prompt = (
        f"考察主题：{topic}（技能：{skill}，题目难度：{difficulty}/5）\n"
        f"问题：{question}\n"
        f"参考答题要点：{json.dumps(reference_points, ensure_ascii=False)}\n"
        f"打分知识基准：\n{knowledge or '（无检索结果，按通用标准）'}\n"
        f"{skill_block}\n"
        + (f"候选人本轮使用提示次数：{hint_used}（请按刻度校准下调深度/完整度预期）\n"
           if hint_used else "")
        + f"候选人回答：{answer or '（未回答）'}"
    )

    # 1) 主评与清单判定并发：两次LLM往返从串行改为并行
    s = get_settings()
    ev_fut = _submit(
        lambda: structured_call(prompt, EvaluationOut, system=templates.EVALUATOR_SYSTEM,
                                temperature=0.1))
    ck_fut = _submit(_checklist_score, reference_points, question, answer,
                     skill_block, knowledge)
    ev = ev_fut.result()
    try:
        ck_score, checklist = ck_fut.result()
    except Exception as e:  # 清单判定任何异常都降级为纯LLM评分，不拖垮主评
        logger.warning("清单聚合评测异常，降级为纯LLM评分: %s", e)
        ck_score, checklist = None, None
    if checklist:
        ev.checklist = checklist.items
    ev = _blend_checklist(ev, ck_score)
    ev = _clamp(ev)

    # 2) 决策边界双评委仲裁（高权重轮次多花算力，普通轮次省算力；按比例抽样）
    if _near_boundary(ev.total) and _sample_hit(question, answer or "",
                                                 s.eval_double_judge_ratio):
        try:
            second = _clamp(structured_call(
                prompt, EvaluationOut, system=_SECOND_REVIEWER_SYSTEM, temperature=0.3))
            for k in WEIGHTS:
                setattr(ev, k, round((float(getattr(ev, k)) + float(getattr(second, k))) / 2, 1))
            ev.total = _weighted_total(ev)
            ev.basis.append("[系统] 总分处于决策阈值边界（60/85附近），触发双评委仲裁取均值")
        except Exception as e:
            logger.warning("边界双评委仲裁失败，保留单评委结果: %s", e)

    # 3) 极端分数二次校验（可配置关闭）
    if s.eval_extreme_recheck and (ev.total <= EXTREME_LOW or ev.total >= EXTREME_HIGH):
        try:
            check = structured_call(
                prompt + f"\n\n原始评测：{ev.model_dump_json()}",
                EvaluationOut, system=_CALIBRATION_SYSTEM, temperature=0.0)
            check = _clamp(check)
            # 二次校验差异超过30分视为漂移，采用二次结果
            if abs(check.total - ev.total) > 30:
                ev = check
        except Exception as e:
            logger.warning("极端分数二次校验失败，保留原值: %s", e)

    # 4) 防背书（代码侧确定性检测，不依赖LLM自觉）
    if _recital_check(answer or "", reference_points):
        ev.suspected_recital = True
        ev.accuracy = round(ev.accuracy * 0.8, 1)
        ev.total = _weighted_total(ev)
        ev.issues.append("答案与参考答案文本高度相似，疑似背诵而非理解（准确性分已下调20%）")
        ev.evidence.append("")

    ev = _align_evidence(ev)
    ev, adjusted = _drift_control(ev, prev_scores)
    if adjusted:
        ev.basis.append("[系统] 触发一致性漂移钳制（同场同维度波动≤20分）")
    return ev
