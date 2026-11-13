// 报告导出（R-14）：把会话 + 报告 + 逐题数据拼成 Markdown，纯前端生成，便于本地存档与分享。
// 不依赖任何下载后端；turns/evals 由调用方懒加载后传入，缺失时自动降级为只导出报告主体。
import { EVAL_DIMS, normalizeEvaluation } from './evaluation';

const text = (v) => (v == null ? '' : String(v).trim());
const num = (v) => (v == null || Number.isNaN(Number(v)) ? null : Number(v));

function fmtScore(v) {
  const n = num(v);
  return n == null ? '—' : Number.isInteger(n) ? String(n) : n.toFixed(1);
}

function fmtWhen(v) {
  if (!v) return '—';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return String(v);
  const p = (x) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 表格单元格转义：竖线与换行会破坏 Markdown 表格结构 */
const cell = (v) => text(v).replace(/\|/g, '\\|').replace(/\s*\n+\s*/g, ' ');

/** 长回答截断，避免 Markdown 过长 */
function clip(v, max = 900) {
  const s = text(v);
  return s.length > max ? `${s.slice(0, max)}…（已截断）` : s;
}

function answerBlock(answer) {
  const s = text(answer);
  if (!s) return '（未作答）\n';
  // 含换行（多为代码）时用围栏块保留缩进
  return s.includes('\n') ? `\n\`\`\`\n${clip(s, 1600)}\n\`\`\`\n` : `${clip(s)}\n`;
}

function moduleScoreRows(report) {
  const scores = report?.module_scores || {};
  return Object.keys(scores).map((k) => `| ${cell(k)} | ${fmtScore(scores[k])} |`);
}

/**
 * 生成完整 Markdown。
 * @param {{session?: object, report?: object, turns?: Array, evals?: Array}} payload
 *   session 可携带 id/title/scene/createdAt/status/isGauntlet
 */
export function buildMarkdown({ session, report, turns = [], evals = [] } = {}) {
  const s = session || {};
  const r = report || {};
  const gauntlet = !!s.isGauntlet || s.plan?.mode === 'GAUNTLET';
  const lines = [];

  lines.push(`# 面试复盘报告 · ${text(s.title) || `会话 #${s.id ?? '—'}`}`, '');

  lines.push('## 基本信息', '');
  lines.push(`- 会话：#${s.id ?? '—'}`);
  lines.push(`- 场景：${gauntlet ? '八股拷打' : '正式面试'}`);
  lines.push(`- 时间：${fmtWhen(s.finishedAt || s.createdAt)}`);
  lines.push(`- 状态：${text(s.status) || '—'}`);
  lines.push(`- 总评：${fmtScore(r.overall_score ?? s.overallScore)} / 100`);
  if (text(r.summary)) lines.push(`- 总评摘要：${cell(r.summary)}`);
  lines.push('');

  if (text(r.summary)) {
    lines.push('## 一、总评', '', text(r.summary), '');
    const strengths = r.strengths || [];
    if (strengths.length) {
      lines.push('### 本场亮点', '');
      strengths.forEach((x) => lines.push(`- ${text(x)}`));
      lines.push('');
    }
  }

  const scoreRows = moduleScoreRows(r);
  if (scoreRows.length) {
    lines.push('## 二、模块得分', '', '| 模块 | 得分 |', '| --- | --- |', ...scoreRows, '');
  }

  const weaknesses = r.weaknesses || [];
  if (weaknesses.length) {
    lines.push('## 三、短板诊断', '');
    weaknesses.forEach((w, i) => {
      lines.push(`### ${i + 1}. ${text(w.skill) || '短板'}`);
      if (text(w.root_cause)) lines.push(`- **根因**：${cell(w.root_cause)}`);
      if (text(w.detail)) lines.push(`- **说明**：${cell(w.detail)}`);
      lines.push('');
    });
  }

  const plan = r.training_plan || [];
  if (plan.length) {
    lines.push('## 四、训练计划', '');
    plan.forEach((d) => {
      const tags = (d.suggested_tags || []).filter(Boolean);
      const meta = [text(d.skill) && `技能：${cell(d.skill)}`, tags.length && `标签：${tags.map(cell).join('、')}`]
        .filter(Boolean).join(' · ');
      lines.push(`### Day ${d.day ?? '—'} · ${text(d.focus) || '训练'}${meta ? `（${meta}）` : ''}`);
      (d.tasks || []).forEach((t, i) => lines.push(`${i + 1}. ${text(t)}`));
      const res = (d.resources || []).filter(Boolean);
      if (res.length) lines.push('', `资料：${res.map(text).join('、')}`);
      lines.push('');
    });
  }

  const matches = r.requirement_match || [];
  if (!gauntlet && matches.length) {
    lines.push('## 五、岗位 JD 匹配', '', '| 要求 | 结论 | 证据 |', '| --- | --- | --- |');
    const verdictText = {
      well_matched: '充分匹配', basic: '基本满足', weak: '薄弱', missing: '未考察/未覆盖',
    };
    matches.forEach((m) => lines.push(
      `| ${cell(m.requirement)} | ${cell(verdictText[m.verdict] || m.verdict || '—')} | ${cell(m.evidence) || '—'} |`,
    ));
    lines.push('');
  }

  const answered = (turns || [])
    .filter((t) => t && t.answer != null)
    .sort((a, b) => (a.turnNo ?? 0) - (b.turnNo ?? 0));
  if (answered.length) {
    lines.push('## 六、逐题摘要', '');
    const evalByTurn = new Map();
    (evals || []).forEach((e) => { if (e && e.turnId != null) evalByTurn.set(e.turnId, e); });
    answered.forEach((t, i) => {
      const ev = normalizeEvaluation(evalByTurn.get(t.id));
      const tags = Array.isArray(t.skillTags) ? t.skillTags : [];
      const head = [`Q${i + 1}`, ev ? `得分 ${fmtScore(ev.total)}` : '未评测', tags.length ? tags.join('/') : '']
        .filter(Boolean).join(' · ');
      lines.push(`### ${head}`, '');
      lines.push(`**题目**：${cell(t.content ?? t.question ?? '')}`, '');
      lines.push('**我的回答**', '', answerBlock(t.answer));
      if (t.answerType === 'CODE') {
        const lang = t.coding && typeof t.coding === 'object' ? t.coding.language : '';
        lines.push(`- 题型：代码题${lang ? `（${cell(lang)}）` : ''}`);
      }
      if (Number(t.hintCount || 0) > 0) lines.push(`- 提示次数：${Number(t.hintCount)}`);
      if (ev) {
        lines.push(`- 六维：${EVAL_DIMS.map((d) => `${d.label} ${fmtScore(ev[d.key])}`).join(' / ')}`);
        if ((ev.missed || []).length) lines.push(`- 遗漏：${ev.missed.map(cell).join('；')}`);
        if ((ev.issues || []).length) lines.push(`- 问题：${ev.issues.map(cell).join('；')}`);
      }
      lines.push('');
    });
  }

  lines.push('---', '', `> 由 Interview Agent 导出 · ${fmtWhen(new Date())}`, '');
  return lines.join('\n');
}

/** 触发浏览器下载（Blob + 临时 a 标签） */
export function downloadMd(filename, content) {
  const blob = new Blob([String(content ?? '')], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'interview-report.md';
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 让浏览器有机会开始下载后再释放
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 文件名：报告标题 + 会话号，去掉文件系统非法字符 */
export function exportFilename(session, fallbackId) {
  const base = text(session?.title) || `面试报告-${session?.id ?? fallbackId ?? ''}`;
  const safe = base.replace(/[\\/:*?"<>|\n\r]+/g, '_').slice(0, 80);
  return `${safe || 'interview-report'}.md`;
}
