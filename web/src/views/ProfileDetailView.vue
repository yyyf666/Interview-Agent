<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  ArrowLeft, UserRound, Pencil, Trash2, X, Plus, Save, Briefcase, Radar,
  Gauge, CalendarClock, Layers, AlertTriangle, TrendingUp, Target,
  Download, Upload, Eraser, ChevronDown, CircleCheck, Clock,
} from 'lucide-vue-next';
import RadarChart from '../components/RadarChart.vue';
import GrowthTrend from '../components/GrowthTrend.vue';
import { req, postJson } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';

// 候选人画像详情：雷达图/得分表/薄弱点/成长趋势可视化 + 全字段编辑（PUT /profiles/{id}）
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const ui = useUiStore();

const p = ref(null);          // 后端画像（读态直接引用）
const growth = ref([]);       // /profiles/{id}/growth
// 画像洞察（R-18）：薄弱点下钻 + 技能掌握度（只读派生，失败不影响主体）
const insights = ref(null);
const selectedWeak = ref('');
const notFound = ref(false);
const editing = ref(false);
let snapshot = null;          // 进入编辑前的 JSON 快照，取消时恢复
const form = ref(null);       // 编辑态工作副本

async function load() {
  notFound.value = false;
  p.value = await session.fetchProfile(route.params.id);
  growth.value = await session.loadProfileGrowth(route.params.id);
  insights.value = await req('/profiles/' + route.params.id + '/insights').catch(() => null);
}
async function init() {
  try {
    await load();
  } catch (e) {
    notFound.value = true;
    ui.toast(e.message, true);
  }
}
onMounted(init);

// ---- 读态派生：skillRatings {skill:{score,level}} -> 行数组；RadarChart 需要 {skill:number}
const ratingRows = (prof) => Object.entries(prof.skillRatings || {}).map(([skill, v]) => ({
  skill,
  score: Number((v && v.score) != null ? v.score : v) || 0,
  level: (v && v.level) || levelOf(Number((v && v.score) != null ? v.score : v) || 0),
}));
const radarScores = computed(() => {
  const out = {};
  Object.entries(p.value?.skillRatings || {}).forEach(([k, v]) => {
    out[k] = Number(v && v.score != null ? v.score : v) || 0;
  });
  return out;
});
const trendScores = computed(() => growth.value.map((g) => Number(g.overall_score) || 0));
// skillTags/weakPoints 可能是 AI 解析出的 {skill, level, evidence} 对象数组，展示与编辑统一为技能文本
const tagText = (x) => (typeof x === 'string' ? x : (x && (x.skill || x.name)) || JSON.stringify(x));
const weakList = computed(() => (Array.isArray(p.value?.weakPoints) ? p.value.weakPoints : [])
  .map(tagText).filter(Boolean));
const levelOf = (s) => (s >= 85 ? 'S' : s >= 70 ? 'A' : s >= 55 ? 'B' : s >= 40 ? 'C' : 'D');

// ---- 洞察派生（R-18）
const masteryOf = (skill) => (insights.value?.skills || [])
  .find((s) => String(s.skill).toLowerCase() === String(skill).toLowerCase()) || null;
const weakPointList = computed(() => insights.value?.weakPoints || []);
const activeWeak = computed(() => weakPointList.value
  .find((w) => String(w.skill).toLowerCase() === selectedWeak.value.toLowerCase()) || null);
function toggleWeak(skill) {
  selectedWeak.value = selectedWeak.value === skill ? '' : skill;
}

// ---- 编辑
const LEVELS = ['S', 'A', 'B', 'C', 'D', 'E'];
function startEdit() {
  snapshot = JSON.stringify(p.value);
  form.value = {
    id: p.value.id,
    nickname: p.value.nickname || '',
    targetRole: p.value.targetRole || '',
    yearsOfExperience: Number(p.value.yearsOfExperience || 0),
    // 对象型标签归一化为字符串并去重（保存时以文本数组覆盖）
    skillTags: [...new Set((Array.isArray(p.value.skillTags) ? p.value.skillTags : []).map(tagText))].filter(Boolean),
    ratings: ratingRows(p.value).map((r) => ({ ...r })),
    weakPoints: [...weakList.value],
  };
  editing.value = true;
}
function cancelEdit() {
  editing.value = false;
  form.value = null;
}
async function saveEdit() {
  // payload 构造也纳入 guard：异常可见（toast）而非裸抛
  ui.guard(async () => {
    const f = form.value;
    const ratings = {};
    f.ratings.forEach((r) => {
      const name = (r.skill || '').trim();
      if (!name) return;
      const score = Math.max(0, Math.min(100, Number(r.score) || 0));
      ratings[name] = { score, level: r.level || levelOf(score) };
    });
    const payload = {
      id: f.id,
      nickname: (f.nickname || '').trim(),
      targetRole: (f.targetRole || '').trim(),
      yearsOfExperience: Number(f.yearsOfExperience) || 0,
      skillTags: f.skillTags.map((t) => t.trim()).filter(Boolean),
      skillRatings: ratings,
      weakPoints: f.weakPoints.map((w) => w.trim()).filter(Boolean),
    };
    await session.saveProfile(payload);
    editing.value = false;
    form.value = null;
    await load(); // store 已重拉 profiles，这里刷新本地引用与趋势
  });
}

// ---- 标签编辑器（技能标签 / 薄弱点共用）
const tagDraft = ref({ skillTags: '', weakPoints: '' });
function addTag(key) {
  const v = (tagDraft.value[key] || '').trim();
  if (!v) return;
  if (!form.value[key].includes(v)) form.value[key].push(v);
  tagDraft.value[key] = '';
}

// ---- 删除候选人（R-19 统一确认弹窗，回列表页）
async function delCandidate() {
  if (!p.value) return;
  const ok = await ui.ask({
    title: '删除候选人',
    message: `确定删除「${p.value.nickname || '未命名'}」？其名下简历将一并删除，历史面试记录会保留。`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  ui.guard(async () => {
    await session.deleteProfile(p.value.id);
    router.push({ name: 'profiles' });
  });
}

/* ---------------- 数据资产（R-18）：导出 / 导入 / 清空 ---------------- */
const importEl = ref(null);

function downloadJson(name, data) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

function exportProfile() {
  if (!p.value) return;
  ui.guard(async () => {
    const data = await req('/profiles/' + p.value.id + '/export');
    const day = new Date().toISOString().slice(0, 10);
    downloadJson(`profile-${p.value.id}-${day}.json`, data);
    ui.toast('已导出 JSON 备份');
  }, 'profile-export');
}

function pickImport() {
  if (importEl.value) importEl.value.click();
}

async function onImportFile(e) {
  const file = (e.target.files || [])[0];
  e.target.value = '';
  if (!file) return;
  let body;
  try {
    body = JSON.parse(await file.text());
  } catch {
    ui.toast('文件不是合法 JSON', true);
    return;
  }
  ui.guard(async () => {
    const r = await postJson('/profiles/import', body);
    ui.toast(`已导入画像 #${r.profileId}（简历 ${r.resumes} 份 · JD ${r.jds} 条）`);
    router.push({ name: 'profile-detail', params: { id: r.profileId } });
  }, 'profile-import');
}

async function purgeAll() {
  const ok = await ui.ask({
    title: '清空我的全部数据',
    message: '将删除全部面试会话、错题本、训练进度、AI 用量、简历、JD 与画像（账号保留）。此操作不可恢复，请输入 DELETE 确认。',
    confirmText: '清空',
    danger: true,
    requireText: 'DELETE',
  });
  if (!ok) return;
  ui.guard(async () => {
    const r = await postJson('/account/purge', { confirm: 'DELETE' });
    ui.toast(`已清空：会话 ${r.sessions} · 简历 ${r.resumes} · JD ${r.jds} · 画像 ${r.profiles}`);
    router.push({ name: 'profiles' });
  }, 'profile-purge');
}

function goBack() {
  router.push({ name: 'profiles' });
}
</script>

<template>
  <section class="view active">
    <div class="view-head">
      <h2><span class="ic"><UserRound :size="18" /></span> 候选人画像</h2>
      <div class="vh-actions" v-if="p && !notFound">
        <button v-if="!editing" class="ghost btn-ic" @click="ui.guard(startEdit)">
          <span class="ic"><Pencil :size="14" /></span>编辑画像
        </button>
        <template v-else>
          <button class="primary btn-ic" @click="saveEdit"><span class="ic"><Save :size="14" /></span>保存</button>
          <button class="ghost btn-ic" @click="cancelEdit"><span class="ic"><X :size="14" /></span>取消编辑</button>
        </template>
        <!-- 数据资产（R-18）：导出 / 导入 / 清空 -->
        <button v-if="!editing" class="ghost btn-ic" @click="exportProfile">
          <span class="ic"><Download :size="14" /></span>导出 JSON
        </button>
        <button v-if="!editing" class="ghost btn-ic" @click="pickImport">
          <span class="ic"><Upload :size="14" /></span>导入 JSON
        </button>
        <button v-if="!editing" class="ghost btn-ic" @click="purgeAll">
          <span class="ic"><Eraser :size="14" /></span>清空我的数据
        </button>
        <button v-if="!editing" class="ghost btn-ic del-cur" @click="delCandidate">
          <span class="ic"><Trash2 :size="14" /></span>删除
        </button>
        <input ref="importEl" type="file" accept="application/json,.json" class="hidden-input" @change="onImportFile" />
      </div>
      <button class="ghost btn-ic" @click="goBack"><span class="ic"><ArrowLeft :size="14" /></span>返回列表</button>
    </div>

    <div v-if="notFound" class="card empty-card">
      <div class="empty-ic"><span class="ic"><AlertTriangle :size="22" /></span></div>
      <p>候选人不存在或已被删除。</p>
      <button class="primary" @click="goBack">返回画像列表</button>
    </div>

    <template v-else-if="p && form">
      <!-- 编辑态：基础信息 -->
      <div class="grid2 pd-grid">
        <div class="card">
          <h3><span class="ic"><Target :size="16" /></span>基础信息</h3>
          <label class="pd-label">花名</label>
          <input v-model="form.nickname" placeholder="候选人花名" />
          <label class="pd-label">目标岗位</label>
          <input v-model="form.targetRole" placeholder="如：Java 后端工程师" />
          <label class="pd-label">工作年限</label>
          <input v-model="form.yearsOfExperience" type="number" min="0" step="0.5" />
          <label class="pd-label">技能标签</label>
          <div class="tag-editor">
            <span v-for="t in form.skillTags" :key="t" class="chip tag-del" :title="'点击移除 ' + t" @click="form.skillTags.splice(form.skillTags.indexOf(t), 1)">
              {{ t }} <span class="ic"><X :size="11" /></span>
            </span>
            <input v-model="tagDraft.skillTags" class="tag-input" placeholder="输入技能，回车添加"
              @keydown.enter.prevent="addTag('skillTags')" />
          </div>
        </div>

        <!-- 编辑态：技能评分表（行级增删改） -->
        <div class="card">
          <h3><span class="ic"><Layers :size="16" /></span>技能评分</h3>
          <div class="rating-edit">
            <div class="re-head"><span>技能</span><span>得分</span><span>等级</span><span></span></div>
            <div v-for="(r, i) in form.ratings" :key="i" class="re-row">
              <input v-model="r.skill" placeholder="如 Java" />
              <input v-model.number="r.score" type="number" min="0" max="100" class="re-score" />
              <select v-model="r.level" class="re-level">
                <option v-for="lv in LEVELS" :key="lv" :value="lv">{{ lv }}</option>
              </select>
              <button class="re-del" title="移除该技能" @click="form.ratings.splice(form.ratings.indexOf(r), 1)">
                <span class="ic"><X :size="13" /></span>
              </button>
            </div>
            <div v-if="!form.ratings.length" class="kv">暂无评分，点下方按钮添加</div>
            <button class="ghost btn-ic" @click="form.ratings.push({ skill: '', score: 60, level: 'B' })">
              <span class="ic"><Plus :size="14" /></span>添加技能评分
            </button>
          </div>
        </div>

        <!-- 编辑态：薄弱点 -->
        <div class="card">
          <h3><span class="ic"><AlertTriangle :size="16" /></span>薄弱点</h3>
          <div class="tag-editor">
            <span v-for="w in form.weakPoints" :key="w" class="chip weak tag-del" :title="'点击移除 ' + w" @click="form.weakPoints.splice(form.weakPoints.indexOf(w), 1)">
              {{ w }} <span class="ic"><X :size="11" /></span>
            </span>
            <input v-model="tagDraft.weakPoints" class="tag-input" placeholder="输入薄弱点，回车添加"
              @keydown.enter.prevent="addTag('weakPoints')" />
          </div>
        </div>

        <!-- 编辑态：统计数据为只读（R-18：由系统派生） -->
        <div class="card">
          <h3><span class="ic"><Gauge :size="16" /></span>面试统计
            <span class="badge" style="margin-left:auto">系统统计</span>
          </h3>
          <div class="kv">累计面试 <b>{{ p.totalInterviews || 0 }}</b> 场 · 均分 <b>{{ p.avgScore ?? '-' }}</b></div>
          <p class="pd-hint">统计项由已结束的正式面试自动派生（不含八股拷打），不可手动修改。</p>
        </div>
      </div>
    </template>

    <template v-else-if="p">
      <!-- 读态 -->
      <div class="pd-hero card">
        <span class="avatar lg">{{ (p.nickname || '?').slice(0, 1).toUpperCase() }}</span>
        <div class="pd-hero-txt">
          <div class="pd-name">{{ p.nickname || '未命名候选人' }}</div>
          <div class="p-role"><span class="ic"><Briefcase :size="13" /></span>{{ p.targetRole || '未设置目标岗位' }}
            · {{ Number(p.yearsOfExperience || 0) }} 年经验</div>
        </div>
        <div class="pd-stats">
          <span class="badge sys">系统统计</span>
          <span class="p-num"><span class="ic"><CalendarClock :size="14" /></span>{{ p.totalInterviews || 0 }} 场面试</span>
          <span class="p-num">均分 <b :class="Number(p.avgScore) >= 80 ? 'score-hi' : Number(p.avgScore) < 60 ? 'score-lo' : ''">{{ p.avgScore ?? '-' }}</b></span>
        </div>
      </div>

      <div class="grid2 pd-grid stagger">
        <div class="card">
          <h3><span class="ic"><Radar :size="16" /></span>技能画像</h3>
          <RadarChart :scores="radarScores" />
          <div class="rating-table" v-if="ratingRows(p).length">
            <div v-for="r in ratingRows(p)" :key="r.skill" class="rating-row">
              <span class="rr-name">{{ r.skill }}</span>
              <span class="rr-bar"><i :style="{ width: Math.max(0, Math.min(100, r.score)) + '%' }"></i></span>
              <span class="rr-score">{{ Math.round(r.score) }}</span>
              <span class="level-chip" :class="'lv-' + r.level">{{ r.level }}</span>
              <!-- 掌握度与 30 天未练提示（R-18） -->
              <span v-if="masteryOf(r.skill)" class="mastery-chip" :class="{
                ok: masteryOf(r.skill).mastery === '已掌握',
                mid: masteryOf(r.skill).mastery === '练习中',
                low: masteryOf(r.skill).mastery === '未掌握',
                stale: masteryOf(r.skill).stale,
              }" :title="masteryOf(r.skill).lastPracticed ? '最近练习：' + masteryOf(r.skill).lastPracticed.slice(0, 10) : '尚无正式面试命中该技能'">
                <span class="ic"><CircleCheck v-if="masteryOf(r.skill).mastery === '已掌握'" :size="11" /><Clock v-else :size="11" /></span>{{ masteryOf(r.skill).mastery }}
                <template v-if="masteryOf(r.skill).stale"> · {{ masteryOf(r.skill).staleDays }} 天未练</template>
              </span>
            </div>
          </div>
        </div>

        <div class="card">
          <h3><span class="ic"><TrendingUp :size="16" /></span>历次成绩趋势</h3>
          <GrowthTrend :scores="trendScores" />
        </div>

        <div class="card">
          <h3><span class="ic"><Layers :size="16" /></span>技能标签</h3>
          <div class="p-tags" v-if="(p.skillTags || []).length">
            <span v-for="(t, ti) in p.skillTags" :key="ti" class="chip">{{ tagText(t) }}</span>
          </div>
          <div v-else class="kv">暂无技能标签，点「编辑画像」补录</div>
        </div>

        <div class="card weak-card">
          <h3><span class="ic"><AlertTriangle :size="16" /></span>高频薄弱点
            <span class="mini" style="margin-left:auto">点标签看证据与关联场次</span>
          </h3>
          <div class="p-tags" v-if="weakList.length">
            <button v-for="w in weakList" :key="w" class="chip weak weak-btn"
              :class="{ on: selectedWeak.toLowerCase() === w.toLowerCase() }" @click="toggleWeak(w)">
              {{ w }}<span class="ic"><ChevronDown :size="11" /></span>
            </button>
          </div>
          <div v-else class="kv">暂无沉淀薄弱点（完成面试后由系统累积，也可手动补录）</div>

          <!-- 薄弱点下钻（R-18）：关联场次 + 证据原话 + 最近出现 -->
          <div v-if="activeWeak" class="weak-drill">
            <div class="wd-head">
              <b>{{ activeWeak.skill }}</b>
              <span class="mini" v-if="activeWeak.lastSeen">最近出现 {{ activeWeak.lastSeen.slice(0, 10) }}</span>
              <span class="mini">关联 {{ activeWeak.sessionCount }} 场</span>
            </div>
            <div v-if="activeWeak.rootCause" class="kv">{{ activeWeak.rootCause }}</div>
            <div class="mini" style="margin-top:8px">关联场次</div>
            <div class="wd-sessions">
              <router-link v-for="s in activeWeak.sessions" :key="s.id" class="wd-session"
                :to="{ name: 'report', params: { id: s.id } }">
                #{{ s.id }} {{ s.title || '未命名' }}
                <small>{{ (s.date || '').slice(0, 10) }} · {{ s.score ?? '—' }}分</small>
              </router-link>
              <span v-if="!activeWeak.sessions.length" class="kv">暂无关联场次</span>
            </div>
            <div class="mini" style="margin-top:8px">证据原话</div>
            <div v-for="(e, ei) in activeWeak.evidence" :key="ei" class="wd-ev">
              <div class="wd-q">{{ e.question }}</div>
              <div v-if="e.quote" class="wd-quote">你的原话：「{{ e.quote }}」</div>
              <div v-if="e.missed" class="wd-missed">遗漏：{{ e.missed }}</div>
            </div>
            <div v-if="!activeWeak.evidence.length" class="kv">暂无评测证据（该弱点可能来自手动补录）</div>
          </div>
          <div v-else-if="!weakPointList.length && weakList.length" class="kv">
            该弱点尚未在正式面试中命中，完成一场相关面试后会自动补全证据
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
