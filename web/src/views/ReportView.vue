<script setup>
import { computed, ref, watch, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  FileText, Sparkles, Gauge, AlertTriangle, ListChecks, GraduationCap,
  Crosshair, CheckCircle2, CircleCheck, Circle, CircleAlert, CircleDashed,
  Play, Flag, History, Scale, Code2, Lightbulb, ChevronDown,
  Download, Printer, GitCompare, ArrowUpRight, ArrowDownRight, Minus,
  Loader2, RotateCw,
} from 'lucide-vue-next';
import RadarChart from '../components/RadarChart.vue';
import ScoreGauge from '../components/ScoreGauge.vue';
import TurnReview from '../components/TurnReview.vue';
import { req, postJson } from '../api/client';
import { EVAL_DIMS } from '../utils/evaluation';
import { buildMarkdown, downloadMd, exportFilename } from '../utils/reportExport';
import { track } from '../utils/track';
import { useSessionStore, LAST_REPORT_KEY } from '../stores/session';
import { useUiStore } from '../stores/ui';

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const ui = useUiStore();

// 报告深链（R-02）：优先路由 id → 内存会话 id → 最近一次报告 id
const report = ref(null);
const sessionData = ref(null);
const loading = ref(false);
const loadError = ref('');
// 与上一场对比（R-14）：增强信息，失败不影响报告主体
const compare = ref(null);

/* ---------------- 异步报告状态（R-16）：RUNNING 轮询 / FAILED 重试 ---------------- */
const reportStatus = ref('NONE');
const reportError = ref('');
const retrying = ref(false);
let pollTimer = null;
let pollDeadline = 0;

function stopReportPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

/** 每 3s 轮询一次报告状态，就绪后重载报告；最长 180s 判为超时 */
function scheduleReportPoll(id) {
  stopReportPoll();
  if (!id || reportStatus.value !== 'RUNNING') return;
  if (!pollDeadline) pollDeadline = Date.now() + 180000;
  pollTimer = setTimeout(async () => {
    pollTimer = null;
    if (Date.now() > pollDeadline) {
      reportStatus.value = 'FAILED';
      reportError.value = '报告生成超时，请稍后重试';
      return;
    }
    try {
      const st = await req(`/interviews/${id}/report-status`, { timeout: 20000 });
      reportStatus.value = st.reportStatus || 'NONE';
      reportError.value = st.reportError || '';
      if (st.reportStatus === 'READY') {
        pollDeadline = 0;
        await loadReport(id);
        return;
      }
      if (st.reportStatus === 'FAILED' || st.reportStatus === 'NONE') {
        pollDeadline = 0;
        return;
      }
    } catch {
      /* 网络抖动：下一轮继续 */
    }
    scheduleReportPoll(id);
  }, 3000);
}

onUnmounted(stopReportPoll);

const targetId = computed(() => {
  const fromRoute = route.params.id;
  if (fromRoute) return Number(fromRoute);
  if (session.sessionId) return Number(session.sessionId);
  const saved = localStorage.getItem(LAST_REPORT_KEY);
  return saved && Number(saved) > 0 ? Number(saved) : null;
});

async function loadReport(id) {
  if (!id) {
    report.value = null;
    sessionData.value = null;
    loadError.value = '';
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const s = await req('/interviews/' + id);
    sessionData.value = s;
    report.value = s.report && Object.keys(s.report).length ? s.report : null;
    reportStatus.value = s.reportStatus || (report.value ? 'READY' : 'NONE');
    reportError.value = s.reportError || '';
    if (report.value) track('report_view', { id: Number(id), score: s.report.overall_score });
    if (reportStatus.value === 'RUNNING') {
      scheduleReportPoll(id);
    } else {
      pollDeadline = 0;
      stopReportPoll();
    }
    session.lastReport = report.value; // 维持既有内存态，其他视图读取一致
    loadTagLabels();
    loadTrainingProgress();
    loadCompare();
  } catch (e) {
    report.value = null;
    sessionData.value = null;
    loadError.value = e.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

watch(targetId, (id) => loadReport(id), { immediate: true });

// 旧场次可能只存了 overall_score：降级展示总分并提示不完整
const incomplete = computed(() => !report.value
  && !!sessionData.value && sessionData.value.overallScore != null);
const shownReport = computed(() => report.value
  || (incomplete.value ? { overall_score: sessionData.value.overallScore } : null));

// 场景以服务端 plan.mode 为准（深链刷新后内存 scene 不可靠）
const isGauntlet = computed(() => sessionData.value?.plan?.mode === 'GAUNTLET'
  || session.scene === 'gauntlet');
const notFinished = computed(() => !loading.value && !shownReport.value
  && sessionData.value && sessionData.value.status === 'RUNNING'
  && reportStatus.value !== 'RUNNING');
// 本场 AI 用量（R-17）：会话级累计，来自 Python meta 回写
const aiMetaText = computed(() => {
  const m = sessionData.value?.aiMeta;
  const calls = Number((m && m.llm_calls) || 0);
  if (!calls) return '';
  const sec = Math.round(Number(m.elapsed_ms || 0) / 100) / 10;
  return `本场 AI 调用 ${calls} 次 · 耗时 ${sec}s`;
});
// 后台报告生成中：骨架 + 轮询，完成后自动出结果（R-16）
const reportRunning = computed(() => !loading.value && !shownReport.value
  && !notFinished.value && reportStatus.value === 'RUNNING');
const reportFailed = computed(() => !loading.value && !shownReport.value
  && reportStatus.value === 'FAILED');

async function retryReport() {
  if (!targetId.value) return;
  retrying.value = true;
  try {
    const res = await session.finishSession(targetId.value);
    if (res && res.report_status === 'READY') {
      await loadReport(targetId.value);
    } else {
      reportStatus.value = 'RUNNING';
      reportError.value = '';
      pollDeadline = 0;
      scheduleReportPoll(targetId.value);
    }
  } finally {
    retrying.value = false;
  }
}

function resume() {
  if (!targetId.value) return;
  ui.guard(() => session.restoreSession(targetId.value), 'restore');
}
async function finish() {
  if (!targetId.value) return;
  const res = await session.finishSession(targetId.value);
  if (!res) return;
  if (res.report_status === 'READY') {
    await loadReport(targetId.value);
    return;
  }
  reportStatus.value = 'RUNNING';
  reportError.value = '';
  pollDeadline = 0;
  scheduleReportPoll(targetId.value);
}
function goHistory() {
  router.push({ name: 'history' });
}

// JD 匹配四档标记（与旧版 verdicts 语义一致，emoji 换 lucide 状态徽标）
const VERDICTS = {
  well_matched: ['充分匹配', 'ok', CircleCheck],
  basic: ['基本满足', 'basic', Circle],
  weak: ['薄弱', 'weak', CircleAlert],
  missing: ['未考察/未覆盖', 'muted', CircleDashed],
};
function verdict(v) {
  return VERDICTS[v] || [v || '', 'muted', Circle];
}

// 总评等级（纯视觉派生，绿色彩分段与 ScoreGauge 一致）
const grade = computed(() => {
  const s = Number(report.value?.overall_score != null
    ? report.value.overall_score : shownReport.value?.overall_score);
  if (!Number.isFinite(s)) return ['待定档', 'basic', Circle];
  if (s >= 80) return ['优秀', 'ok', CircleCheck];
  if (s >= 60) return ['良好', 'basic', Circle];
  return ['待提升', 'weak', CircleAlert];
});

/* ---------------- 逐题复盘（R-09）：懒加载 turns/evaluations 并按 turnId 合并 ---------------- */
const rawTurns = ref([]);
const rawEvals = ref([]);
const reviewLoaded = ref(false);
const reviewLoading = ref(false);
const reviewError = ref('');
const reviewFilter = ref('all');
const bookmarkedIds = ref(new Set());

const REVIEW_FILTERS = [
  { key: 'all', label: '全部', icon: ListChecks },
  { key: 'low', label: '低分 <60', icon: AlertTriangle },
  { key: 'missed', label: '有遗漏', icon: CircleAlert },
  { key: 'hint', label: '用过提示', icon: Lightbulb },
  { key: 'code', label: '代码题', icon: Code2 },
];

const reviewRows = computed(() => rawTurns.value
  .filter((t) => t.answer != null)
  .map((t) => ({ turn: t, evaluation: rawEvals.value.find((e) => e.turnId === t.id) || null })));

const filteredRows = computed(() => reviewRows.value.filter((row) => {
  const f = reviewFilter.value;
  if (f === 'all') return true;
  if (f === 'low') return row.evaluation != null && Number(row.evaluation.totalScore) < 60;
  if (f === 'missed') return (row.evaluation?.missedKnowledgePoints || []).length > 0;
  if (f === 'hint') return Number(row.turn.hintCount || 0) > 0;
  if (f === 'code') return row.turn.answerType === 'CODE';
  return true;
}));

async function loadReview() {
  if (!targetId.value) return;
  reviewLoading.value = true;
  reviewError.value = '';
  try {
    const [turns, evals, reviews] = await Promise.all([
      req('/interviews/' + targetId.value + '/turns'),
      req('/interviews/' + targetId.value + '/evaluations'),
      req('/review-items?pageNo=1&pageSize=100').catch(() => ({ records: [] })),
    ]);
    rawTurns.value = turns || [];
    rawEvals.value = evals || [];
    const ids = (reviews.records || [])
      .filter((x) => Number(x.sessionId) === Number(targetId.value))
      .map((x) => x.turnId);
    bookmarkedIds.value = new Set(ids);
    reviewLoaded.value = true;
  } catch (e) {
    reviewError.value = e.message || '加载失败';
  } finally {
    reviewLoading.value = false;
  }
}

async function bookmarkRow(row) {
  if (!targetId.value || !row.turn) return;
  await ui.guard(async () => {
    await postJson('/review-items', { sessionId: targetId.value, turnId: row.turn.id });
    bookmarkedIds.value = new Set([...bookmarkedIds.value, row.turn.id]);
    ui.toast('已加入错题本');
  }, 'review');
}

// 切换会话时清空复盘缓存（深链跳转/重新加载）
watch(targetId, () => {
  stopReportPoll();
  pollDeadline = 0;
  reportStatus.value = 'NONE';
  reportError.value = '';
  rawTurns.value = [];
  rawEvals.value = [];
  reviewLoaded.value = false;
  reviewError.value = '';
  reviewFilter.value = 'all';
  bookmarkedIds.value = new Set();
  compare.value = null;
});

/* ---------------- 与上一场对比（R-14） ---------------- */
async function loadCompare() {
  if (!targetId.value) return;
  try {
    compare.value = await req('/interviews/' + targetId.value + '/compare');
  } catch {
    compare.value = null; // 对比为增强信息，失败不阻塞报告主体
  }
}

const numOrNull = (v) => (v == null || Number.isNaN(Number(v)) ? null : Number(v));
const fmtVal = (v) => (v == null ? '—' : Number.isInteger(v) ? String(v) : Number(v).toFixed(1));
const deltaText = (d) => (d == null ? '—' : `${d > 0 ? '+' : ''}${d}`);
const deltaClass = (d) => (d == null || d === 0 ? 'flat' : d > 0 ? 'up' : 'down');
const deltaIcon = (d) => (d == null || d === 0 ? Minus : d > 0 ? ArrowUpRight : ArrowDownRight);

/** 总分 + 六维均值两场对照（当前值优先取报告，六维取服务端评测均值） */
const compareRows = computed(() => {
  const c = compare.value;
  if (!c || !c.previous) return [];
  const rows = [{
    key: 'overall',
    label: '总分',
    cur: numOrNull(shownReport.value?.overall_score) ?? numOrNull(c.current?.overallScore),
    prev: numOrNull(c.previous.overallScore) ?? numOrNull(c.previous.report?.overall_score),
  }];
  EVAL_DIMS.forEach((d) => rows.push({
    key: d.key,
    label: `${d.label}均值`,
    cur: numOrNull(c.current?.dimensions?.[d.key]),
    prev: numOrNull(c.previous.dimensions?.[d.key]),
  }));
  return rows.map((r) => ({
    ...r,
    delta: r.cur != null && r.prev != null ? Number((r.cur - r.prev).toFixed(1)) : null,
  }));
});

/** 模块分变化：取两场 module_scores 并集，按变化幅度排序 */
const compareModules = computed(() => {
  const c = compare.value;
  if (!c || !c.previous) return [];
  const cur = shownReport.value?.module_scores || {};
  const prev = c.previous.report?.module_scores || {};
  const keys = [...new Set([...Object.keys(cur), ...Object.keys(prev)])];
  return keys.map((k) => {
    const a = numOrNull(cur[k]);
    const b = numOrNull(prev[k]);
    return { key: k, cur: a, prev: b, delta: a != null && b != null ? Number((a - b).toFixed(1)) : null };
  }).sort((x, y) => Math.abs(y.delta ?? 0) - Math.abs(x.delta ?? 0));
});

const compareSummary = computed(() => {
  const rows = compareRows.value.filter((r) => r.delta != null);
  return {
    up: rows.filter((r) => r.delta > 0).length,
    down: rows.filter((r) => r.delta < 0).length,
  };
});

/* ---------------- 报告导出与打印（R-14） ---------------- */
const exporting = ref(false);

async function exportMarkdown() {
  if (!shownReport.value) return;
  exporting.value = true;
  try {
    // 逐题摘要需要 turns/evaluations，复用联的懒加载
    if (!reviewLoaded.value) await loadReview();
    const md = buildMarkdown({
      session: {
        ...(sessionData.value || {}),
        id: targetId.value,
        isGauntlet: isGauntlet.value,
      },
      report: shownReport.value,
      turns: rawTurns.value,
      evals: rawEvals.value,
    });
    downloadMd(exportFilename(sessionData.value, targetId.value), md);
    ui.toast('已导出 Markdown');
  } catch (e) {
    ui.toast(e.message || '导出失败', true);
  } finally {
    exporting.value = false;
  }
}

function printReport() {
  window.print();
}

/* ---------------- 训练任务可执行（R-10） ---------------- */
const tagLabels = ref({});
const trainingDone = ref(new Set()); // `${day}:${index}`

async function loadTagLabels() {
  try {
    const groups = await req('/gauntlet/tags');
    const map = {};
    (groups || []).forEach((g) => (g.children || []).forEach((t) => { map[t.id] = t.label; }));
    tagLabels.value = map;
  } catch {
    /* 未登录/离线：仅展示标签 id */
  }
}

async function loadTrainingProgress() {
  if (!targetId.value) return;
  try {
    const r = await req('/training-progress?sourceSessionId=' + targetId.value);
    trainingDone.value = new Set((r.records || []).map((x) => `${x.planDay}:${x.taskIndex}`));
  } catch {
    /* 静默：不影响报告主体 */
  }
}

const trainingTotal = computed(() => (shownReport.value?.training_plan || [])
  .reduce((n, t) => n + ((t.tasks || []).length), 0));

function isTaskDone(day, idx) {
  return trainingDone.value.has(`${day}:${idx}`);
}

/** 训练任务「去练习」：预选标签 + 起始难度，并回传 source 供完成回写 */
function goPractice(dayPlan, idx) {
  const tags = (dayPlan.suggested_tags || []).filter(Boolean);
  router.push({
    name: 'gauntlet',
    query: {
      tags: tags.join(','), difficulty: '2', count: '3',
      source: `${targetId.value}:${dayPlan.day}:${idx}`, focus: dayPlan.focus || '',
    },
  });
}

/* ---------------- 精练清单（R-10）：按短板技能检索题库 ---------------- */
const drills = ref({}); // skill -> [{id, content, difficulty}] | 'empty' | 'loading'

async function loadDrills() {
  const skills = (shownReport.value?.weaknesses || []).map((w) => w.skill).filter(Boolean);
  for (const skill of skills) {
    if (drills.value[skill]) continue;
    drills.value = { ...drills.value, [skill]: 'loading' };
    try {
      const rows = await postJson('/questions/search', { query: skill, skill, top_k: 5 });
      drills.value = { ...drills.value, [skill]: (rows || []).length ? rows : 'empty' };
    } catch {
      drills.value = { ...drills.value, [skill]: 'empty' };
    }
  }
}
</script>

<template>
  <section class="view active stagger">
    <!-- Hero：环形总分 + 评级 / 总评 -->
    <div class="card">
      <h3>
        <span class="ic"><Gauge :size="16" /></span>面试总览
        <!-- R-14：导出 Markdown / 打印（仅报告就绪后可用） -->
        <span v-if="shownReport" class="hero-tools">
          <button class="ghost btn-ic" :disabled="exporting" @click="exportMarkdown">
            <span class="ic"><Download :size="13" /></span>{{ exporting ? '导出中…' : '导出 Markdown' }}
          </button>
          <button class="ghost btn-ic" @click="printReport">
            <span class="ic"><Printer :size="13" /></span>打印
          </button>
        </span>
      </h3>

      <!-- 深链加载骨架 -->
      <div v-if="loading" class="report-empty">
        <div class="sk-line"></div>
        <div class="sk-line"></div>
        <div class="sk-line short"></div>
      </div>

      <!-- 后台报告生成中（R-16）：骨架 + 自动轮询，完成后自动出结果 -->
      <div v-else-if="reportRunning" class="report-empty">
        <span class="ic"><Loader2 :size="26" class="spin" /></span>
        <span>报告生成中…</span>
        <span class="mini">后台正在汇总本场表现，通常需要 20~60 秒，完成后自动刷新</span>
        <div class="sk-line" style="width:min(420px,80%)"></div>
        <div class="sk-line short"></div>
      </div>

      <!-- 报告生成失败（R-16）：展示原因并可重试 -->
      <div v-else-if="reportFailed" class="report-empty">
        <span class="ic"><CircleAlert :size="26" /></span>
        <span>报告生成失败</span>
        <span class="mini">{{ reportError || 'AI 服务暂时不可用，请稍后重试' }}</span>
        <div class="row" style="justify-content:center;gap:10px;margin-top:6px">
          <button class="primary btn-ic" :disabled="retrying" @click="retryReport">
            <span class="ic"><RotateCw :size="13" /></span>{{ retrying ? '重新生成中…' : '重新生成报告' }}
          </button>
          <button class="ghost btn-ic" @click="goHistory">
            <span class="ic"><History :size="13" /></span>前往历史
          </button>
        </div>
      </div>

      <!-- 未结束：给出继续/结束入口，而不是空白报告 -->
      <div v-else-if="notFinished" class="report-empty">
        <span class="ic"><FileText :size="26" /></span>
        <span>本场尚未结束，报告还没生成</span>
        <span class="mini">会话 #{{ targetId }} 仍在进行中，可继续作答，或直接结束并生成报告</span>
        <div class="row" style="justify-content:center;gap:10px;margin-top:6px">
          <button class="primary btn-ic" @click="resume">
            <span class="ic"><Play :size="13" /></span>继续面试
          </button>
          <button class="ghost btn-ic" @click="finish">
            <span class="ic"><Flag :size="13" /></span>结束并生成报告
          </button>
          <button class="ghost btn-ic" @click="goHistory">
            <span class="ic"><History :size="13" /></span>前往历史
          </button>
        </div>
      </div>

      <!-- 空态：无 id / 无报告 / 加载失败 -->
      <div v-else-if="!shownReport" class="report-empty">
        <span class="ic"><FileText :size="26" /></span>
        <span>{{ loadError ? '报告加载失败：' + loadError : '尚无报告，请先完成一场正式面试或八股拷打' }}</span>
        <span class="mini">
          在「准备」页开始正式面试，或在「八股拷打」页选标签开始练习；也可以从历史中打开任意已完成会话
        </span>
        <div style="margin-top:6px">
          <button class="ghost btn-ic" @click="goHistory">
            <span class="ic"><History :size="13" /></span>前往历史
          </button>
        </div>
      </div>

      <div v-else class="hero">
        <div class="gauge-wrap">
          <ScoreGauge :score="shownReport.overall_score" />
        </div>
        <div class="hero-info">
          <span class="eyebrow">本场总评</span>
          <div class="hero-grade">
            <span class="verdict" :class="grade[1]">
              <component :is="grade[2]" :size="13" />{{ grade[0] }}
            </span>
          </div>
          <div class="hero-sum">{{ shownReport.summary || '' }}</div>
          <div v-if="aiMetaText" class="mini hero-ai">{{ aiMetaText }}</div>
          <div v-if="(shownReport.strengths || []).length" class="hero-str">
            <div v-for="(x, i) in shownReport.strengths" :key="i" class="sr">
              <span class="ic"><CheckCircle2 :size="14" /></span>{{ x }}
            </div>
          </div>
        </div>
      </div>

      <div v-if="incomplete" class="kv" style="margin-top:12px">
        <span class="ic"><AlertTriangle :size="14" /></span>
        报告数据不完整：本场仅保存了总分，详细诊断与训练计划未生成
      </div>

      <RadarChart v-if="shownReport" :scores="shownReport.module_scores || {}" />
    </div>

    <!-- 与上一场对比（R-14）：同候选人+同JD的上一次已完成面试；无则空态 -->
    <div v-if="shownReport && compare" class="card compare-card">
      <h3>
        <span class="ic"><GitCompare :size="16" /></span>与上一场对比
        <span v-if="compare.previous" class="mini" style="margin-left:auto">
          上一场：#{{ compare.previous.id }} · {{ compare.previous.title || '未命名' }}
        </span>
      </h3>
      <template v-if="compare.previous">
        <div class="cmp-grid">
          <div v-for="row in compareRows" :key="row.key" class="cmp-row">
            <span class="cmp-name">{{ row.label }}</span>
            <span class="cmp-val">{{ fmtVal(row.prev) }} → <b>{{ fmtVal(row.cur) }}</b></span>
            <span class="cmp-delta" :class="deltaClass(row.delta)">
              <component :is="deltaIcon(row.delta)" :size="12" />{{ deltaText(row.delta) }}
            </span>
          </div>
        </div>
        <template v-if="compareModules.length">
          <div class="mini" style="margin-top:12px">模块分变化</div>
          <div class="cmp-grid">
            <div v-for="m in compareModules.slice(0, 8)" :key="m.key" class="cmp-row">
              <span class="cmp-name" :title="m.key">{{ m.key }}</span>
              <span class="cmp-val">{{ fmtVal(m.prev) }} → <b>{{ fmtVal(m.cur) }}</b></span>
              <span class="cmp-delta" :class="deltaClass(m.delta)">
                <component :is="deltaIcon(m.delta)" :size="12" />{{ deltaText(m.delta) }}
              </span>
            </div>
          </div>
        </template>
        <div class="mini" style="margin-top:10px">
          共 {{ compareSummary.up }} 项提升 · {{ compareSummary.down }} 项下降
        </div>
      </template>
      <div v-else class="kv">
        <span class="ic"><History :size="14" /></span>
        暂无上一场可对比：同候选人 + 同 JD 的已完成正式面试才会进入对比
      </div>
    </div>

    <div class="grid2">
      <div class="card">
        <h3><span class="ic"><AlertTriangle :size="16" /></span>短板诊断</h3>
        <template v-if="shownReport">
          <div v-for="(w, i) in shownReport.weaknesses || []" :key="i" class="weak-item">
            <div class="wk-head">
              <span class="ic"><AlertTriangle :size="14" /></span><b>{{ w.skill }}</b>
            </div>
            <div class="kv">根因：{{ w.root_cause }}</div>
            <div>{{ w.detail }}</div>
          </div>
          <div v-if="!(shownReport.weaknesses || []).length" class="kv sr-line">
            <span class="ic"><Sparkles :size="14" /></span>无明显短板
          </div>
        </template>
      </div>
      <div class="card">
        <h3>
          <span class="ic"><GraduationCap :size="16" /></span>训练计划
          <span v-if="trainingTotal" class="mini" style="margin-left:auto">已完成 {{ trainingDone.size }}/{{ trainingTotal }}</span>
        </h3>
        <template v-if="shownReport">
          <div v-for="(t, i) in shownReport.training_plan || []" :key="i" class="task-item">
            <span class="day">Day {{ t.day }} · {{ t.focus }}</span>
            <span v-if="t.skill" class="badge">{{ t.skill }}</span>
            <span v-for="tid in t.suggested_tags || []" :key="tid" class="badge muted">
              {{ tagLabels[tid] || tid }}
            </span>
            <ul class="tight">
              <li v-for="(x, j) in t.tasks || []" :key="j">
                <span class="task-line">{{ x }}</span>
                <span v-if="isTaskDone(t.day, j)" class="badge ok task-done">已完成</span>
                <button class="ghost btn-ic task-go" @click="goPractice(t, j)">去练习</button>
              </li>
              <li v-if="(t.resources || []).length" style="color:var(--muted)">资料：{{ (t.resources || []).join('、') }}</li>
            </ul>
          </div>
          <div v-if="!(shownReport.training_plan || []).length" class="kv">暂无计划</div>

          <!-- 精练清单（R-10）：按短板技能搜题库 -->
          <div v-if="(shownReport.weaknesses || []).length" class="drill-block">
            <button class="ghost btn-ic" @click="loadDrills">按短板技能找题（题库检索）</button>
            <div v-for="w in shownReport.weaknesses || []" :key="w.skill" class="drill-skill">
              <b>{{ w.skill }}</b>
              <template v-if="drills[w.skill] === 'loading'"><span class="mini">检索中…</span></template>
              <template v-else-if="drills[w.skill] === 'empty'">
                <span class="mini">题库暂无，去八股练习</span>
              </template>
              <ul v-else-if="Array.isArray(drills[w.skill])" class="tight">
                <li v-for="q in drills[w.skill]" :key="q.id">
                  {{ q.content }} <small class="mini">难度 {{ q.difficulty }}</small>
                </li>
              </ul>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 逐题复盘（R-09）：懒加载 + 筛选（低分/遗漏/提示/代码题）+ 收藏错题 -->
    <div v-if="shownReport" class="card">
      <h3>
        <span class="ic"><ListChecks :size="16" /></span>逐题复盘
        <button v-if="!reviewLoaded" class="ghost btn-ic review-load" :disabled="reviewLoading" @click="loadReview">
          <span class="ic"><ChevronDown :size="13" /></span>{{ reviewLoading ? '加载中…' : '加载逐题复盘' }}
        </button>
      </h3>

      <div v-if="reviewLoaded" class="filter-bar">
        <button
          v-for="f in REVIEW_FILTERS"
          :key="f.key"
          class="chip-btn filter-chip"
          :class="{ on: reviewFilter === f.key }"
          @click="reviewFilter = f.key"
        >
          <span class="ic"><component :is="f.icon" :size="12" /></span>{{ f.label }}
        </button>
        <span class="mini">{{ filteredRows.length }} / {{ reviewRows.length }} 题</span>
      </div>

      <div v-if="reviewError" class="report-empty">
        <span>复盘加载失败：{{ reviewError }}</span>
        <button class="ghost btn-ic" @click="loadReview">重试</button>
      </div>
      <div v-else-if="!reviewLoaded" class="kv">
        逐题讲解为懒加载（题目/回答/六维/遗漏/参考要点），点击上方按钮展开
      </div>
      <div v-else-if="!filteredRows.length" class="kv">当前筛选下没有题目</div>
      <div v-else class="review-list">
        <TurnReview
          v-for="(row, i) in filteredRows"
          :key="row.turn.id"
          :item="row"
          :index="i + 1"
          :bookmarked="bookmarkedIds.has(row.turn.id)"
          @bookmark="bookmarkRow"
        />
      </div>
    </div>

    <!-- 岗位 JD 匹配仅在正式面试下展示（八股拷打无 JD；场景由 plan.mode 派生） -->
    <div v-if="!isGauntlet" class="card">
      <h3><span class="ic"><Crosshair :size="16" /></span>岗位 JD 逐项匹配</h3>
      <template v-if="shownReport">
        <div v-for="(m, i) in shownReport.requirement_match || []" :key="i" class="match-item">
          <b>{{ m.requirement }}</b>
          <span class="verdict" :class="verdict(m.verdict)[1]">
            <component :is="verdict(m.verdict)[2]" :size="13" />{{ verdict(m.verdict)[0] }}
          </span>
          <small v-if="m.evidence" class="quote">{{ m.evidence }}</small>
        </div>
        <div v-if="!(shownReport.requirement_match || []).length" class="kv">
          <span class="ic"><ListChecks :size="14" /></span>
          本场未关联到JD要求清单（需上传并解析JD后开始面试）
        </div>
      </template>
    </div>
  </section>
</template>
