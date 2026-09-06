"""Agent 共享状态定义（设计文档 §7.3：所有Agent共享统一 InterviewState，通过状态传递信息）

会话数据真源在 Java（PostgreSQL/Redis），每次请求由 Java 携带上下文，
Python 侧无状态重建状态机 —— 天然支持中断恢复与服务重启。
"""
from __future__ import annotations

from typing import TypedDict


class InterviewState(TypedDict, total=False):
    # 运行模式：start=首次会话(PLAN+QUESTION) / answer=回答后流转 / end=强制收尾
    mode: str
    session_id: int
    persona: str      # 面试官人格 standard/friendly/pressure/socratic

    # INIT：初始化上下文（start请求携带）
    resume: dict
    jd: dict
    profile: dict
    gap: dict        # 开前缺口分析快照（前端已确认时直接复用，跳过二次 LLM）
    options: dict    # 候选人高级配置（R-08）：题量/时长/难度/代码题/追问强度/语言/重点技能

    # PLAN：面试计划
    plan: dict

    # 强制意图（R-11）：skip/request_question 时跳过 LLM 意图识别直接遵从
    forced_intent: str
    question_spec: str

    # 当前轮与历史（Java构建）
    current: dict     # {turn_no, topic, difficulty, question, is_follow_up}
    history: list     # [{turn_no, topic, question, answer, total_score, issues, skill_tags, is_follow_up}]
    answer: str       # 本轮候选人回答

    # EVALUATE：Evaluator结构化评分
    evaluation: dict
    # DECIDE：Director决策
    action: str
    decision_reason: str

    # QUESTION：Interviewer产出的下一题
    question: dict

    # REACT：面试官对刚回答的现场回应
    reaction: str

    # INTENT/INTERJECT：候选人插话（元请求）识别结果与处理产物
    interjection: dict

    # FINAL_REPORT：Coach复盘
    report: dict

    # 训练计划可执行（R-10）：八股标签清单 [{id,label,skill}]，Coach 据此输出 suggested_tags
    tag_catalog: list
