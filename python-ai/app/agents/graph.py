"""LangGraph 多Agent状态机编排（设计文档 §7.1）

状态节点：
  INIT/PLAN ──> QUESTION ──(等待回答，由Java侧持久化后下一请求恢复)──>
  ANSWER ──> EVALUATE ──> DECIDE ──> REACT(面试官现场回应) ──┬──> FOLLOW_UP ──> (回到等待回答)
                                                              ├──> NEXT_TOPIC / NEXT_QUESTION / LOWER_DIFFICULTY ──> QUESTION
                                                              └──> FINAL_REPORT ──> END

会话数据真源在 Java，每次请求携带完整上下文，图按 mode 从对应入口执行一段，
天然支持中断恢复（设计文档 §3.7 状态持久化）。
"""
from __future__ import annotations

import logging
from functools import lru_cache

from langgraph.graph import END, StateGraph

from app.agents import coach, director, evaluator, interviewer
from app.agents.state import InterviewState
from app.core.db import query
from app.core.state_store import save_agent_state
from app.prompts import templates

logger = logging.getLogger(__name__)


# ============================================================ Panel 轮转辅助
# persona 字段可为逗号分隔 CSV（多面试官团）；以下辅助根据轮次解析当轮发言人设。

def _persona_csv(state: InterviewState) -> str:
    return state.get("persona") or "standard"


def _lead(state: InterviewState) -> str:
    """首席面试官（团长）：Director/计划类固定使用，保证开场与规划风格稳定"""
    return templates.parse_personas(_persona_csv(state))[0]


def _next_speaker(state: InterviewState) -> str:
    """即将出的新题所属轮次的发言人（与 Java saveTurnQuestion 同一公式）"""
    next_turn = len(state.get("history") or []) + 1
    return templates.select_persona(_persona_csv(state), next_turn)


def _cur_speaker(state: InterviewState) -> str:
    """当前开放题（react/意图/提示针对本轮）的发言人"""
    cur = state.get("current") or {}
    turn_no = cur.get("turn_no")
    if turn_no is None:
        turn_no = len(state.get("history") or []) + 1
    return templates.select_persona(_persona_csv(state), int(turn_no))


# ============================================================ 节点实现

def node_plan(state: InterviewState) -> dict:
    """INIT + PLAN：加载简历/JD/画像，生成面试计划（Director）"""
    plan = director.make_plan(
        state.get("resume") or {}, state.get("jd") or {}, state.get("profile") or {},
        persona=_lead(state), gap=state.get("gap") or None,
        options=state.get("options") or None)
    plan_dict = plan.model_dump()
    logger.info("session=%s 生成面试计划，主题：%s", state.get("session_id"),
                [t["topic"] for t in plan_dict["topics"]])
    return {"plan": plan_dict}


def node_question(state: InterviewState) -> dict:
    """QUESTION：Interviewer 生成当前问题（含代码题分支；题量节点触发有界重规划）"""
    plan = state.get("plan") or {}
    history = state.get("history") or []
    update: dict = {}
    if (state.get("mode") == "answer" and history
            and state.get("action") in ("NEXT_QUESTION", "NEXT_TOPIC", "LOWER_DIFFICULTY")
            and plan.get("mode") != "GAUNTLET"  # 八股只考所选标签，禁止重规划引入新主题
            and director.need_replan(history)):
        plan = director.replan(plan, history, persona=_lead(state))
        update["plan"] = plan
    q = interviewer.ask_next(
        plan=plan,
        history=history,
        prev_action=state.get("action") or "",
        persona=_next_speaker(state))
    out = q.model_dump()
    out["opening_remark"] = plan.get("opening_remark", "")
    return {**update, "question": out}


def node_intent(state: InterviewState) -> dict:
    """INTENT：先判定本次输入是实质回答还是插话（元请求），fail-open 默认按回答走原链路。

    forced_intent（R-11 八股跳过/换题按钮）非空时不调 LLM，直接按指定意图构造插话结果。
    """
    forced = (state.get("forced_intent") or "").strip()
    if forced in ("skip", "request_question"):
        return {"interjection": {
            "intent": forced, "acceptable": True, "reply": "",
            "question_spec": state.get("question_spec") or "",
        }}
    out = interviewer.classify_and_interject(
        current=state.get("current") or {},
        text=state.get("answer") or "",
        history=state.get("history") or [],
        persona=_cur_speaker(state))
    if out.intent == "answer":
        return {}
    return {"interjection": out.model_dump()}


def node_interject(state: InterviewState) -> dict:
    """INTERJECT：遵从候选人的流程性请求——换题/跳过/给提示/澄清题意/结束/婉拒闲聊。

    不评测、不产生评分记录；插话轮由 Java 侧标记 INTERJECTION。
    产出 question（新题或重述原题）供前端继续，或路由 final_report。
    """
    inj = state.get("interjection") or {}
    intent = inj.get("intent")
    current = dict(state.get("current") or {})
    plan = state.get("plan") or {}
    cur_speaker = _cur_speaker(state)
    next_speaker = _next_speaker(state)
    update: dict = {}

    if intent == "end":
        update["action"] = "FINAL_REPORT"
        update["decision_reason"] = "候选人主动请求结束面试，直接进入复盘"
        return update

    if intent == "request_question" and inj.get("acceptable", True):
        q = interviewer.ask_per_spec(plan, inj.get("question_spec", ""),
                                     current, persona=next_speaker)
        update["question"] = q.model_dump()
    elif intent == "skip" and inj.get("acceptable", True):
        # 跳过本题：出计划内下一题（history 不含被跳题得分，主题进度不推进）
        q = interviewer.ask_next(plan=plan, history=state.get("history") or [],
                                 prev_action="NEXT_QUESTION", persona=next_speaker)
        update["question"] = q.model_dump()
    elif intent == "hint":
        hint = interviewer.generate_hint(
            question=current.get("question") or "",
            reference_points=current.get("reference_points") or [],
            level=3, asked_before=int(current.get("hint_count") or 0),
            topic=current.get("topic") or "", persona=cur_speaker)
        inj["reply"] = (inj.get("reply") or "") + hint
        update["question"] = _restate(current)
    elif intent == "clarify":
        inj["reply"] = (inj.get("reply") or "") + interviewer.explain_question(
            current, state.get("answer") or "", persona=cur_speaker)
        update["question"] = _restate(current)
    else:  # off_topic / acceptable=false：婉拒并回到原题
        update["question"] = _restate(current)

    update["interjection"] = inj
    update["action"] = "INTERJECT"
    return update


def _restate(current: dict) -> dict:
    """重述原题（作为新一轮呈现，题面/参考要点不变）"""
    return {
        "question": current.get("question"), "topic": current.get("topic"),
        "skill": current.get("skill"), "difficulty": current.get("difficulty") or 2,
        "question_type": current.get("question_type"),
        "reference_points": current.get("reference_points") or [],
        "source": current.get("source", "generated"), "is_follow_up": False,
        "starter_code": (current.get("coding") or {}).get("starter_code", "")
        if isinstance(current.get("coding"), dict) else "",
        "starter_codes": (current.get("coding") or {}).get("starter_codes", {})
        if isinstance(current.get("coding"), dict) else {},
        "language": (current.get("coding") or {}).get("language", "java")
        if isinstance(current.get("coding"), dict) else "java",
        "test_cases": (current.get("coding") or {}).get("test_cases", [])
        if isinstance(current.get("coding"), dict) else [],
    }


def node_evaluate(state: InterviewState) -> dict:
    """ANSWER + EVALUATE：预处理回答并执行六维度评测（Evaluator）"""
    current = state.get("current") or {}
    plan = state.get("plan") or {}
    skill = interviewer._skill_of_topic(plan, current.get("topic") or "")

    # 上一轮同维度评分（用于漂移钳制，一致性保障 §10.3）
    prev_scores = None
    rows = query(
        """SELECT score_accuracy, score_completeness, score_depth, score_logic,
                  score_expression, score_tradeoff
           FROM evaluation WHERE session_id = %s ORDER BY id DESC LIMIT 1""",
        (state.get("session_id"),))
    if rows:
        r = rows[0]
        prev_scores = {"accuracy": r["score_accuracy"], "completeness": r["score_completeness"],
                       "depth": r["score_depth"], "logic": r["score_logic"],
                       "expression": r["score_expression"], "tradeoff": r["score_tradeoff"]}

    ev = evaluator.evaluate(
        question=current.get("question") or "",
        answer=state.get("answer") or "",
        reference_points=current.get("reference_points") or [],
        skill=skill, topic=current.get("topic") or "",
        difficulty=int(current.get("difficulty") or 2),
        prev_scores=prev_scores,
        hint_used=int(current.get("hint_count") or 0))
    return {"evaluation": ev.model_dump()}


def node_decide(state: InterviewState) -> dict:
    """DECIDE：Director 读取状态机决策下一步（规则见 §6.2）"""
    action, reason = director.decide(state)
    logger.info("session=%s Director决策: %s | %s", state.get("session_id"), action, reason)
    return {"action": action, "decision_reason": reason}


def node_react(state: InterviewState) -> dict:
    """REACT：面试官以人格化口吻对刚完成的回答给出简短现场回应"""
    reaction = interviewer.react_to_answer(
        current=state.get("current") or {},
        answer=state.get("answer") or "",
        evaluation=state.get("evaluation") or {},
        action=state.get("action") or "",
        persona=_cur_speaker(state))
    return {"reaction": reaction}


def node_follow_up(state: InterviewState) -> dict:
    """FOLLOW_UP：针对漏洞生成追问（Interviewer）"""
    current = dict(state.get("current") or {})
    current["answer"] = state.get("answer")
    q = interviewer.ask_follow_up(current, state.get("evaluation") or {},
                                  state.get("plan") or {},
                                  persona=_next_speaker(state))
    out = q.model_dump()
    out["is_follow_up"] = True
    return {"question": out}


def node_final_report(state: InterviewState) -> dict:
    """FINAL_REPORT：Coach 生成复盘报告并回写长期记忆"""
    history = list(state.get("history") or [])
    current = state.get("current") or {}
    ev = state.get("evaluation") or {}
    # 触发收尾的本轮问答尚未入库到history，并入后再出报告
    if current.get("question") and ev:
        history.append({
            "turn_no": current.get("turn_no"), "topic": current.get("topic"),
            "question": current.get("question"), "answer": state.get("answer"),
            "total_score": ev.get("total"), "issues": ev.get("issues"),
            "skill_tags": ev.get("skill_tags"), "is_follow_up": current.get("is_follow_up"),
        })
    report = coach.generate_report(
        session_id=int(state.get("session_id") or 0),
        plan=state.get("plan") or {},
        history=history,
        persona_csv=_persona_csv(state),
        tag_catalog=state.get("tag_catalog") or [])
    return {"report": report.model_dump(), "action": "FINAL_REPORT"}


# ============================================================ 图构建

def _route_entry(state: InterviewState) -> str:
    mode = state.get("mode") or "start"
    # 八股拷打：Java 已构造好计划，跳过 PLAN（缺口分析）直接出首题
    if mode == "start" and state.get("plan"):
        return "question"
    return {"start": "plan", "answer": "intent", "end": "final_report"}.get(mode, "plan")


def _route_after_intent(state: InterviewState) -> str:
    """插话（元请求）短路到 INTERJECT，实质回答走原评测链路"""
    return "interject" if state.get("interjection") else "evaluate"


def _route_after_interject(state: InterviewState) -> str:
    return "final_report" if state.get("action") == "FINAL_REPORT" else "interject_end"


def _route_after_decide(state: InterviewState) -> str:
    action = state.get("action")
    if action == "FOLLOW_UP":
        return "follow_up"
    if action == "FINAL_REPORT":
        return "final_report"
    return "question"  # NEXT_QUESTION / NEXT_TOPIC / LOWER_DIFFICULTY


def build_graph():
    g = StateGraph(InterviewState)
    g.add_node("plan", node_plan)
    g.add_node("question", node_question)
    g.add_node("intent", node_intent)
    g.add_node("interject", node_interject)
    g.add_node("evaluate", node_evaluate)
    g.add_node("decide", node_decide)
    g.add_node("react", node_react)
    g.add_node("follow_up", node_follow_up)
    g.add_node("final_report", node_final_report)

    g.set_conditional_entry_point(_route_entry,
                                  {"plan": "plan", "question": "question",
                                   "intent": "intent", "final_report": "final_report"})
    g.add_edge("plan", "question")
    g.add_edge("question", END)
    g.add_conditional_edges("intent", _route_after_intent,
                            {"evaluate": "evaluate", "interject": "interject"})
    g.add_conditional_edges("interject", _route_after_interject,
                            {"final_report": "final_report", "interject_end": END})
    g.add_edge("evaluate", "decide")
    g.add_edge("decide", "react")
    g.add_conditional_edges("react", _route_after_decide,
                            {"follow_up": "follow_up", "question": "question",
                             "final_report": "final_report"})
    g.add_edge("follow_up", END)
    g.add_edge("final_report", END)
    return g.compile()


@lru_cache
def get_graph():
    return build_graph()


def run_interview_graph(state: InterviewState) -> dict:
    """执行一段状态机并返回终态"""
    graph = get_graph()
    final = graph.invoke(state)
    save_agent_state(int(state.get("session_id") or 0), {
        "plan": final.get("plan"), "action": final.get("action"),
        "question": final.get("question")})
    return final


def stream_interview_graph(state: InterviewState):
    """节点级流式执行（R-16）：逐个 yield (node, delta)，结束后与同步路径一样落 agent 状态。"""
    graph = get_graph()
    final: dict = {}
    for update in graph.stream(state, stream_mode="updates"):
        for node, delta in (update or {}).items():
            if not isinstance(delta, dict):
                continue
            final.update(delta)
            yield node, delta
    save_agent_state(int(state.get("session_id") or 0), {
        "plan": final.get("plan"), "action": final.get("action"),
        "question": final.get("question")})
