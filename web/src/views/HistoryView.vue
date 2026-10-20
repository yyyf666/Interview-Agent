<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  History, Gauge, Target, TrendingUp, RefreshCw, ChevronDown,
  Search, X, MoreVertical, FileText, Play, Link2, Pencil, Trash2, Check, BookOpen,
} from 'lucide-vue-next';
import GrowthTrend from '../components/GrowthTrend.vue';
import ReplayView from '../components/ReplayView.vue';
import { req } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';
import { useCountUp } from '../composables/useCountUp';

const session = useSessionStore();
const ui = useUiStore();
const router = useRouter();

const sessions = ref([]);
const trendScores = ref([]);
const profileText = ref('');
const replayId = ref(null);
const replayTitle = ref('');
const replayTurns = ref([]);
const replayEvals = ref([]);

const PAGE_SIZE = 20;
const pageNo = ref(1);
const total = ref(0);
const hasMore = ref(false);

/* ---------------- 搜索与筛选（R-06） ---------------- */
const keyword = ref('');      // 输入框原始值
const kwApplied = ref('');    // 300ms 防抖后真正下发的值
const scope = ref('all');     // all | formal | gauntlet
const statusFilter = ref(''); // '' | RUNNING | COMPLETED

let kwTimer = null;
watch(keyword, (v) => {
  clearTimeout(kwTimer);
  kwTimer = setTimeout(() => {
    kwApplied.value = v.trim();
    reload();
  }, 300);
});

function setScope(v) {
  if (scope.value === v) return;
  scope.value = v;
  reload();
}

watch(statusFilter, () => reload());

// 顶部统计卡：累计场次 / 均分 / 高频薄弱点数
// 场次、均分与趋势由服务端 stats 聚合（与服务端分页解耦，不再全量派生）
const statTotal = ref(0);
const statAvg10 = ref(0); // 均分×10 取整滚动，保留一位小数精度
const statWeak = ref(0);
const dispTotal = useCountUp(statTotal);
const dispAvg10 = useCountUp(statAvg10);
const dispWeak = useCountUp(statWeak);
const avgShown = computed(() => (statAvg10.value ? (dispAvg10.value / 10).toFixed(1) : '-'));

// 八股练习由摘要的 planMode 标记；历史保留展示，但不计入正式面试统计
const isGauntlet = (s) => s.planMode === 'GAUNTLET';

const STATUS_CN = { INIT: '未开始', RUNNING: '进行中', COMPLETED: '已完成', ABORTED: '已中止' };
const statusCn = (v) => STATUS_CN[v] || v || '-';

function pageQuery(page) {
  const qs = new URLSearchParams({
    pageNo: String(page), pageSize: String(PAGE_SIZE), scope: scope.value,
  });
  if (session.profileId) qs.set('candidateId', String(session.profileId));
  if (kwApplied.value) qs.set('keyword', kwApplied.value);
  if (statusFilter.value) qs.set('status', statusFilter.value);
  return req('/interviews/page?' + qs.toString());
}

async function loadHistory() {
  // 服务端过滤与分页：八股记录全部保留，正式面试按当前候选人过滤
  const data = await pageQuery(1);
  sessions.value = data.records || [];
  pageNo.value = data.pageNo || 1;
  total.value = data.total || 0;
  hasMore.value = !!data.hasMore;
  const stats = data.stats || {};
  statTotal.value = stats.formalCount || 0;
  statAvg10.value = Math.round((stats.avgScore || 0) * 10);
  trendScores.value = stats.trendScores || [];
  await loadProfileSummary();
}

async function loadMore() {
  if (!hasMore.value) return;
  const data = await pageQuery(pageNo.value + 1);
  sessions.value = sessions.value.concat(data.records || []);
  pageNo.value = data.pageNo || pageNo.value + 1;
  total.value = data.total || total.value;
  hasMore.value = !!data.hasMore;
}

async function loadProfileSummary() {
  if (!session.profileId) return;
  const profiles = await req('/profiles');
  const p = profiles.find((x) => x.id === session.profileId);
  if (p) {
    const weak = (p.weakPoints || []).slice(0, 6)
      .map((w) => (typeof w === 'string' ? w : (w.skill || ''))).filter(Boolean);
    statWeak.value = weak.length;
    profileText.value = `长期画像：累计 ${p.totalInterviews || 0} 场，均分 ${p.avgScore ?? '-'}；`
      + (weak.length ? `高频薄弱点：${weak.join('、')}（下场面试 Director 会自动降难加量）` : '暂无沉淀薄弱点');
  }
}

function reload() {
  pageNo.value = 1;
  return ui.guard(loadHistory);
}

function refresh() {
  ui.guard(loadHistory);
}

function goWrongBook() {
  router.push({ name: 'wrong-book' });
}

function loadMoreClick() {
  ui.guard(loadMore);
}

/* ---------------- 行操作 ---------------- */

const emptyText = computed(() => {
  if (kwApplied.value) return `没有匹配「${kwApplied.value}」的会话`;
  if (scope.value !== 'all' || statusFilter.value) return '当前筛选条件下暂无会话';
  return '暂无面试会话';
});

const primaryLabel = (s) => (s.status === 'COMPLETED' ? '查看报告'
  : s.status === 'RUNNING' ? '继续面试' : '回放');

/** 主操作按状态分流：已完成看报告 / 进行中继续面试 / 其他回放 */
function primaryAction(s) {
  if (s.status === 'COMPLETED') {
    router.push({ name: 'report', params: { id: s.id } });
    return;
  }
  if (s.status === 'RUNNING') {
    ui.guard(async () => {
      const ok = await session.restoreSession(s.id);
      if (!ok) await loadHistory();
    }, 'restore');
    return;
  }
  replayClick(s);
}

async function replay(s) {
  const [turns, evals] = await Promise.all([
    req(`/interviews/${s.id}/turns`), req(`/interviews/${s.id}/evaluations`)]);
  replayId.value = s.id;
  replayTitle.value = '#' + s.id + ' ' + (s.title || '未命名');
  replayTurns.value = turns;
  replayEvals.value = evals;
}

function replayClick(s) {
  ui.guard(() => replay(s));
}

async function copyLink(s) {
  const url = `${location.origin}${location.pathname}#/report/${s.id}`;
  try {
    await navigator.clipboard.writeText(url);
    ui.toast('报告链接已复制');
  } catch {
    ui.toast('复制失败，请手动复制：' + url, true);
  }
}

/* 行内重命名 */
const renameId = ref(null);
const renameText = ref('');

function startRename(s) {
  renameId.value = s.id;
  renameText.value = s.title || '';
}

function cancelRename() {
  renameId.value = null;
  renameText.value = '';
}

async function commitRename(s) {
  const t = renameText.value.trim();
  if (!t) return ui.toast('标题不能为空', true);
  if (t === (s.title || '')) return cancelRename();
  const updated = await ui.guard(() => session.renameSession(s.id, t), 'rename');
  if (updated) {
    const item = sessions.value.find((x) => x.id === s.id);
    if (item) item.title = updated.title || t;
    if (replayId.value === s.id) replayTitle.value = '#' + s.id + ' ' + (item ? item.title : t);
  }
  cancelRename();
}

async function removeSession(s) {
  const label = s.title ? `「${s.title}」` : '';
  const ok = await ui.ask({
    title: '删除会话',
    message: `确认删除会话 #${s.id}${label}？该会话的轮次与评测将一并删除，且不可恢复。`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  const done = await ui.guard(async () => {
    await session.deleteSession(s.id);
    return true;
  }, 'delete');
  if (!done) return;
  if (replayId.value === s.id) {
    replayId.value = null;
    replayTitle.value = '';
    replayTurns.value = [];
    replayEvals.value = [];
  }
  await loadHistory();
}

// 分数色阶（纯视觉辅助）：≥80 绿 / <60 橙红 / 其余默认深灰
const scoreCls = (v) => (v == null ? '' : Number(v) >= 80 ? 'score-hi' : Number(v) < 60 ? 'score-lo' : '');

onUnmounted(() => clearTimeout(kwTimer));

// 与旧版一致：切到历史 tab 时自动加载
onMounted(refresh);
</script>

<template>
  <section class="view active">
    <div class="stat-grid stagger">
      <div class="stat-card">
        <span class="sc-ic"><History :size="17" /></span>
        <div class="sc-num">{{ dispTotal }}</div>
        <div class="sc-lbl">累计面试场次</div>
      </div>
      <div class="stat-card">
        <span class="sc-ic"><Gauge :size="17" /></span>
        <div class="sc-num">{{ avgShown }}</div>
        <div class="sc-lbl">平均总分</div>
      </div>
      <div class="stat-card">
        <span class="sc-ic"><Target :size="17" /></span>
        <div class="sc-num">{{ dispWeak }}</div>
        <div class="sc-lbl">高频薄弱点</div>
      </div>
    </div>
    <div class="grid2">
      <div class="card">
        <div class="hist-head">
          <h3><span class="ic"><History :size="16" /></span>历史面试 <small>共 {{ total }} 条</small></h3>
          <button class="ghost btn-ic" @click="goWrongBook"><BookOpen :size="14" /> 错题本</button>
          <button class="ghost btn-ic" @click="refresh"><RefreshCw :size="14" /> 刷新</button>
        </div>
        <div class="hist-toolbar">
          <label class="hist-search">
            <Search :size="14" />
            <input v-model="keyword" type="text" placeholder="搜索标题，或输入会话号（如 12）" />
            <button v-if="keyword" class="hist-clear" type="button" aria-label="清空搜索" @click="keyword = ''">
              <X :size="13" />
            </button>
          </label>
          <div class="seg hist-seg">
            <button type="button" :class="{ on: scope === 'all' }" @click="setScope('all')">全部</button>
            <button type="button" :class="{ on: scope === 'formal' }" @click="setScope('formal')">正式面试</button>
            <button type="button" :class="{ on: scope === 'gauntlet' }" @click="setScope('gauntlet')">八股练习</button>
          </div>
          <select v-model="statusFilter" class="hist-status">
            <option value="">全部状态</option>
            <option value="RUNNING">进行中</option>
            <option value="COMPLETED">已完成</option>
          </select>
        </div>
        <p class="hint">统计卡与趋势仅含正式面试；八股练习单独标记，不计入画像。</p>
        <div class="mini-list tall hist-list">
          <div
            v-for="s in sessions"
            :key="s.id"
            class="item hist-row"
            :class="{ active: replayId === s.id }"
            @click="primaryAction(s)"
          >
            <template v-if="renameId === s.id">
              <input
                v-model="renameText"
                class="row-rename"
                type="text"
                maxlength="256"
                :ref="(el) => el && el.focus()"
                @click.stop
                @keyup.enter="commitRename(s)"
                @keyup.esc="cancelRename"
              />
              <button class="row-act" type="button" title="确认" aria-label="确认重命名" @click.stop="commitRename(s)"><Check :size="13" /></button>
              <button class="row-act" type="button" title="取消" aria-label="取消重命名" @click.stop="cancelRename"><X :size="13" /></button>
            </template>
            <template v-else>
              <span class="hr-id">#{{ s.id }}</span>
              <span class="hr-title">{{ s.title || '未命名' }}</span>
              <span v-if="isGauntlet(s)" class="badge gauntlet">八股</span>
              <span class="badge" :class="{ ok: s.status === 'COMPLETED', muted: s.status === 'INIT' || s.status === 'ABORTED' }">
                {{ statusCn(s.status) }}
              </span>
              <!-- R-16：后台报告生成中 / 失败（进入报告页可看进度与重试） -->
              <span v-if="s.reportStatus === 'RUNNING'" class="badge gen">报告生成中</span>
              <span v-else-if="s.reportStatus === 'FAILED'" class="badge gen fail">报告失败</span>
              <small :class="scoreCls(s.overallScore)">
                {{ s.overallScore != null ? s.overallScore + '分' : '未评分' }} ·
                {{ (s.createdAt || '').slice(0, 10) }} · {{ s.persona || 'standard' }}
              </small>
              <span class="hr-go">{{ primaryLabel(s) }}</span>
              <details class="row-menu" @click.stop>
                <summary class="row-menu-btn" title="更多操作" aria-label="更多操作"><MoreVertical :size="14" /></summary>
                <div class="row-menu-panel">
                  <button class="menu-item" type="button" @click="primaryAction(s)">
                    <FileText :size="13" />{{ primaryLabel(s) }}
                  </button>
                  <button class="menu-item" type="button" @click="replayClick(s)">
                    <Play :size="13" />回放
                  </button>
                  <button class="menu-item" type="button" @click="copyLink(s)">
                    <Link2 :size="13" />复制链接
                  </button>
                  <button class="menu-item" type="button" @click="startRename(s)">
                    <Pencil :size="13" />重命名
                  </button>
                  <div class="menu-sep"></div>
                  <button class="menu-item danger" type="button" @click="removeSession(s)">
                    <Trash2 :size="13" />删除
                  </button>
                </div>
              </details>
            </template>
          </div>
          <div v-if="!sessions.length" class="kv hist-empty">{{ emptyText }}</div>
        </div>
        <button v-if="hasMore" class="ghost btn-ic" @click="loadMoreClick">
          <ChevronDown :size="14" /> 加载更多（共 {{ total }} 场）
        </button>
      </div>
      <div class="card">
        <h3><span class="ic"><TrendingUp :size="16" /></span>总分趋势</h3>
        <GrowthTrend :scores="trendScores" />
        <div class="kv">{{ profileText }}</div>
      </div>
    </div>
    <div class="card">
      <h3>会话回放 <small style="color:var(--muted)">{{ replayTitle }}</small></h3>
      <div class="chat readonly">
        <ReplayView :turns="replayTurns" :evals="replayEvals" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.badge.gauntlet {
  background: var(--accent-soft);
  color: var(--accent);
}
/* R-16：报告后台生成中 / 失败 */
.badge.gen {
  background: var(--warn-soft, #fef3c7);
  color: #b45309;
}
.badge.gen.fail {
  background: var(--err-soft);
  color: #b91c1c;
}
</style>
