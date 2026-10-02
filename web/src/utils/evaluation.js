// 评测数据归一化（R-04）：AI 实时返回为 snake_case，DB 回放实体为 camelCase。
// 统一成一套读法，EvaluationDetail / MsgBubble / ReplayView 共用。
const num = (v) => (v == null || Number.isNaN(Number(v)) ? 0 : Number(v));

/** 六维评分顺序（与评测卡迷你条一致） */
export const EVAL_DIMS = [
  { key: 'accuracy', label: '准确' },
  { key: 'completeness', label: '完整' },
  { key: 'depth', label: '深度' },
  { key: 'logic', label: '逻辑' },
  { key: 'expression', label: '表达' },
  { key: 'tradeoff', label: '权衡' },
];

export function normalizeEvaluation(e) {
  if (!e) return null;
  // 三个来源的字段名：AI 实时 snake_case / 历史旧字段 scoreXxx / 实体 camelCase
  const pick = (keys) => {
    for (const k of keys) if (e[k] != null) return e[k];
    return 0;
  };
  return {
    total: num(pick(['total', 'totalScore'])),
    accuracy: num(pick(['accuracy', 'scoreAccuracy'])),
    completeness: num(pick(['completeness', 'scoreCompleteness'])),
    depth: num(pick(['depth', 'scoreDepth'])),
    logic: num(pick(['logic', 'scoreLogic'])),
    expression: num(pick(['expression', 'scoreExpression'])),
    tradeoff: num(pick(['tradeoff', 'scoreTradeoff'])),
    basis: e.basis || [],
    issues: e.issues || [],
    evidence: e.evidence || [],
    // 遗漏知识点：实时响应 snake_case / DB 实体 camelCase
    missed: e.missed_knowledge_points || e.missedKnowledgePoints || [],
    checklist: (e.checklist || []).map((c) => ({
      point: c.point || '',
      hit: c.hit || 'none',
      note: c.note || '',
    })),
    suspectedRecital: !!(e.suspected_recital != null ? e.suspected_recital : e.suspectedRecital),
    decisionReason: e.decision_reason || e.decisionReason || '',
  };
}
