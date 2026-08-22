"""Pydantic 数据模型：API出入参 + LLM强制结构化输出（设计文档 §7.3 / §10.2）"""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


# ========================================================= 通用响应

class ApiResp(BaseModel):
    code: int = 0
    data: Any = None
    message: str = "ok"


def ok(data: Any = None) -> dict:
    return {"code": 0, "message": "ok", "data": data}


# ========================================================= 简历 / JD 解析

class SkillLevel(BaseModel):
    skill: str = Field(description="技能名，小写英文标识，如 java/redis/mysql")
    level: int = Field(ge=1, le=5, description="掌握深度 1-5")
    evidence: str = Field(default="", description="来自简历的依据")


class ResumeParsed(BaseModel):
    """简历结构化解析结果"""
    name: str = ""
    years_of_experience: float = 0
    skills: list[SkillLevel] = Field(default_factory=list)
    projects: list[str] = Field(default_factory=list, description="项目经历要点")
    summary: str = ""


class RequiredSkill(BaseModel):
    skill: str
    priority: int = Field(ge=1, le=5, description="岗位要求优先级 1-5")
    description: str = ""


class JdParsed(BaseModel):
    """JD岗位能力模型"""
    title: str = ""
    company: str = ""
    required_skills: list[RequiredSkill] = Field(default_factory=list)
    skill_weights: dict[str, float] = Field(default_factory=dict, description="技能权重，总和约1")
    focus_points: list[str] = Field(default_factory=list, description="考察重点")


class GapAnalysis(BaseModel):
    """技能缺口分析"""
    match_score: float = Field(ge=0, le=100, description="整体匹配度")
    strengths: list[str] = Field(default_factory=list, description="优势项")
    weaknesses: list[str] = Field(default_factory=list, description="薄弱项/缺口")
    key_inspection_points: list[str] = Field(default_factory=list, description="重点考察项")


# ========================================================= 面试计划

class CodeTestCase(BaseModel):
    input: str = Field(default="", description="运行样例的 stdin / 函数调用参数")
    expected: str = Field(default="", description="期望输出")


class TopicPlan(BaseModel):
    topic: str = Field(description="考察主题，如 java并发 / redis持久化")
    skill: str = Field(description="对应技能标识")
    question_count: int = Field(ge=1, le=6, default=2)
    base_difficulty: int = Field(ge=1, le=5, default=2)
    reason: str = Field(default="", description="安排该主题与难度的依据（可解释性）")
    coding: bool = Field(default=False, description="该主题为手写代码题（现场编码考察）")


class InterviewPlan(BaseModel):
    topics: list[TopicPlan]
    opening_remark: str = Field(default="", description="面试官开场语")
    total_question_budget: int = Field(default=10, description="整场面试题目预算")
    gap: dict = Field(default_factory=dict, description="开前缺口分析快照")
    options: dict = Field(default_factory=dict,
                          description="候选人高级配置（R-08）：预算/时长/难度/代码题/追问强度/语言/重点技能")


class ReplanOut(BaseModel):
    """有界重规划：只输出调整后的剩余主题（已完成主题由代码侧原样保留）"""
    topics: list[TopicPlan] = Field(default_factory=list,
                                    description="调整后的剩余主题（可重排/改题量难度/新增最多2个）")
    reason: str = Field(default="", description="调整依据（一句话）")


# ========================================================= 出题 / 评测

class QuestionOut(BaseModel):
    """Interviewer 结构化输出"""
    question: str
    topic: str
    skill: str
    difficulty: int = Field(ge=1, le=5, default=2)
    question_type: Literal["concept", "principle", "scenario", "project", "behavior", "code"] = "concept"
    reference_points: list[str] = Field(default_factory=list, description="参考答题要点")
    source: Literal["question_bank", "rag", "generated"] = "generated"
    starter_code: str = Field(default="", description="代码题初始模板（默认语言）")
    starter_codes: dict[str, str] = Field(default_factory=dict,
                                          description="代码题多语言模版 {java/python/javascript}")
    language: str = Field(default="java", description="代码题默认作答语言 java|python|javascript")
    test_cases: list[CodeTestCase] = Field(default_factory=list, description="代码题可运行样例")


class ChecklistItem(BaseModel):
    point: str = Field(description="参考答题要点原文")
    hit: Literal["full", "partial", "none"] = Field(description="命中程度")
    note: str = Field(default="", description="判定依据（一句话）")


class ChecklistOut(BaseModel):
    """清单聚合评测：逐要点判定命中（分数可解释、可复现）"""
    items: list[ChecklistItem] = Field(default_factory=list)


class EvaluationOut(BaseModel):
    """Evaluator 六维度结构化评分（设计文档 §10.1 / §10.2）"""
    accuracy: float = Field(ge=0, le=100, description="技术准确性 权重30%")
    completeness: float = Field(ge=0, le=100, description="内容完整度 权重20%")
    depth: float = Field(ge=0, le=100, description="技术深度 权重20%")
    logic: float = Field(ge=0, le=100, description="逻辑推理 权重15%")
    expression: float = Field(ge=0, le=100, description="表达结构 权重10%")
    tradeoff: float = Field(ge=0, le=100, description="选型权衡 权重5%")
    total: float = Field(ge=0, le=100, description="加权总分")
    basis: list[str] = Field(default_factory=list, description="得分依据（关键正确点）")
    issues: list[str] = Field(default_factory=list, description="错误/遗漏点")
    evidence: list[str] = Field(default_factory=list,
                                description="与 issues 一一对应的候选人原文片段（可空串）")
    missed_knowledge_points: list[str] = Field(default_factory=list, description="遗漏知识点")
    should_follow_up: bool = False
    follow_up_direction: str = Field(default="", description="建议追问方向")
    skill_tags: list[str] = Field(default_factory=list)
    suspected_recital: bool = Field(default=False, description="疑似背诵/复述参考答案")
    checklist: list[ChecklistItem] = Field(default_factory=list, description="要点命中清单")


DecisionAction = Literal["NEXT_QUESTION", "FOLLOW_UP", "NEXT_TOPIC", "LOWER_DIFFICULTY", "FINAL_REPORT"]


class ReactionOut(BaseModel):
    """面试官针对刚回答的现场简短回应"""
    reply: str = Field(default="", description="第一人称口语化回应，50字以内")


class HintOut(BaseModel):
    """分级提示输出"""
    hint: str = Field(default="", description="当前等级的提示内容")


InterjectIntent = Literal["answer", "request_question", "skip", "hint",
                          "clarify", "end", "off_topic"]


class InterjectOut(BaseModel):
    """对话意图识别：实质回答 vs 关于面试如何进行的元请求（插话）"""
    intent: InterjectIntent = Field(default="answer", description="识别出的意图，默认按回答处理")
    acceptable: bool = Field(default=True, description="该请求是否可遵从（超范围可婉拒）")
    reply: str = Field(default="", description="面试官人格化的口头回应（intent=answer 时留空）")
    question_spec: str = Field(default="", description="intent=request_question 时候选人想要的题目描述")


# ========================================================= 复盘报告

class WeaknessAnalysis(BaseModel):
    skill: str
    root_cause: Literal["knowledge_missing", "expression", "depth", "logic"] = Field(
        description="问题根源：知识缺失/表达不足/深度不够/逻辑不清")
    detail: str = ""


class DailyTask(BaseModel):
    day: int = Field(ge=1, le=30)
    focus: str = Field(description="当日训练重点技能")
    skill: str = Field(default="", description="与 focus 对应的技能标识（用于标签映射）")
    suggested_tags: list[str] = Field(default_factory=list,
                                      description="建议练习的八股标签 id（只能取传入的 tag_catalog）")
    tasks: list[str] = Field(default_factory=list)
    resources: list[str] = Field(default_factory=list, description="推荐学习资料/题目")


class RequirementMatch(BaseModel):
    """JD 逐项匹配（招聘决策导向）"""
    requirement: str = Field(description="JD 中的岗位要求原文或提炼")
    verdict: Literal["well_matched", "basic", "weak", "missing"] = Field(
        description="充分匹配/基本满足/薄弱/未考察或未满足")
    evidence: str = Field(default="", description="面试中的证据（哪轮回答、说了什么）")


class FinalReport(BaseModel):
    """Coach 复盘总报告"""
    overall_score: float = Field(ge=0, le=100)
    module_scores: dict[str, float] = Field(default_factory=dict, description="各技能模块得分（雷达图数据）")
    strengths: list[str] = Field(default_factory=list)
    weaknesses: list[WeaknessAnalysis] = Field(default_factory=list)
    training_plan: list[DailyTask] = Field(default_factory=list)
    requirement_match: list[RequirementMatch] = Field(default_factory=list,
                                                      description="JD要求逐项匹配表")
    summary: str = Field(default="", description="总体评价与面试排名感知")


# ========================================================= API 请求体

class ResumeParseReq(BaseModel):
    rawText: str


class JdParseReq(BaseModel):
    rawText: str


class InterviewStartReq(BaseModel):
    session_id: int
    resume: dict = Field(default_factory=dict)
    jd: dict = Field(default_factory=dict)
    profile: dict = Field(default_factory=dict)
    mode: str = "TEXT"
    persona: str = Field(default="standard", description="面试官人格 standard/friendly/pressure/socratic")
    plan: dict = Field(default_factory=dict,
                       description="外部预置计划（八股拷打）：非空时跳过PLAN节点，直接出首题")
    gap: dict = Field(default_factory=dict, description="开前缺口分析快照，非空时 PLAN 直接复用")
    options: dict = Field(default_factory=dict, description="候选人高级配置（R-08），已由 Java 白名单归一化")


class GapReq(BaseModel):
    """开前缺口分析请求：resume/jd 含 parsed 结构"""
    resume: dict = Field(default_factory=dict)
    jd: dict = Field(default_factory=dict)


class InterviewAnswerReq(BaseModel):
    session_id: int
    answer: str
    context: dict = Field(default_factory=dict, description="plan/current/history，Java为数据真源")
    forced_intent: str | None = Field(
        default=None, description="强制意图（R-11）：skip/request_question 时跳过 LLM 意图识别直接遵从")
    question_spec: str = Field(default="", description="forced_intent=request_question 时的题目要求")


class InterviewEndReq(BaseModel):
    session_id: int
    context: dict = Field(default_factory=dict)


class InterviewHintReq(BaseModel):
    session_id: int
    question: str
    reference_points: list[str] = Field(default_factory=list)
    level: int = Field(ge=1, le=3, description="1=方向提示 2=关键点 3=参考思路揭晓")
    topic: str = ""
    asked_before: int = Field(default=0, ge=0, description="本题已请求提示次数（Java侧传入）")
    persona: str = "standard"


class RagIndexReq(BaseModel):
    document_id: int
    content: str
    metadata: dict = Field(default_factory=dict)


class RagReindexReq(BaseModel):
    document_id: int
    file_path: str


class RagSearchReq(BaseModel):
    query: str
    skill: str | None = None
    top_k: int | None = None


class QuestionEmbedReq(BaseModel):
    question_id: int
    content: str
    metadata: dict = Field(default_factory=dict)


class QuestionSearchReq(BaseModel):
    query: str
    skill: str | None = None
    difficulty: int | None = None
    top_k: int = 5
