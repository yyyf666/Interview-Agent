"""意图规则前置层：高置信流程指令短路判定，命中则跳过 LLM 意图调用（降本）

设计边界：
- 规则只处理"短指令"（≤50字）：插话天然是简短的流程性表达；
- 长文本（≥100字）且不含任何插话线索词 → 直接判 answer（最常见场景省1次调用）；
- 其余（长度居中/长短文本含线索词）返回 None 交给 LLM 精判；
- off_topic 无语义边界，不做规则化，始终走 LLM；
- fail-open 原则不变：规则层只在高置信时介入，判定错也由 INTERJECTION
  零评测机制兜底（不污染评分数据）。
"""
from __future__ import annotations

from app.models.schemas import InterjectOut

_DIRECTIVE_MAX_LEN = 50    # 短指令上限：超过则不做插话短路
_ANSWER_MIN_LEN = 100      # 长文本下限：无线索词时直接判回答

# 按优先级匹配（子串命中即短路）：end > skip > hint > request_question > clarify
_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("end", ("结束面试", "终止面试", "结束这次面试", "今天就到这", "就到这儿吧",
             "不想面了", "不再面了", "面试到此为止", "结束吧", "先结束吧")),
    ("skip", ("跳过", "下一题", "换一题", "换个题", "换主题", "跳过吧", "先略过",
              "这题不答", "这题先过", "不会，跳过", "不会跳过")),
    ("hint", ("提示", "给点思路", "给个思路", "没思路", "没头绪", "没什么方向",
              "不会做", "给答案", "告诉我答案", "揭晓")),
    ("request_question", ("出道", "来道", "换道", "做一道", "我想写", "想练",
                          "代码题", "编程题", "算法题", "手写", "写段代码",
                          "换个题型", "换种考法", "出个题", "来道题")),
    ("clarify", ("题意", "题目意思是", "没理解这道题", "没看懂题", "什么意思",
                 "解释一下题", "再解释下问题", "没听清问题")),
]

_ALL_CUES = tuple(p for _, patterns in _RULES for p in patterns)

# 规则短路的固定回应（intent -> 面试官口头承接；node_interject 会按意图继续处理）
_REPLY = {
    "end": "好的，今天的面试就到这里，我来给你做一份复盘总结。",
    "skip": "行，我们跳过这题，看下一个问题。",
    "hint": "没问题，给你一点方向。",
    "request_question": "可以，那按你的要求换一道题。",
    "clarify": "我来把这道题的问法再拆一下。",
}


def rule_classify(text: str) -> InterjectOut | None:
    """高置信规则判定；返回 None 表示不确定，需 LLM 精判。"""
    t = (text or "").strip().lower()
    if not t:
        return None
    if len(t) > _DIRECTIVE_MAX_LEN:
        # 长文本且不含插话线索词：几乎必然是实质回答
        if len(t) >= _ANSWER_MIN_LEN and not any(c in t for c in _ALL_CUES):
            return InterjectOut(intent="answer")
        return None
    for intent, patterns in _RULES:
        if any(p in t for p in patterns):
            return InterjectOut(
                intent=intent,
                reply=_REPLY.get(intent, "好的，我们按你说的来。"),
                question_spec=text.strip() if intent == "request_question" else "")
    return None
