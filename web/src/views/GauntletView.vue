<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import {
  Swords, Play, Check, Flame, Database, Coffee, Cpu, Shuffle, Sparkles, Target, X, Boxes,
} from 'lucide-vue-next';
import { req } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';
import { PERSONAS } from '../constants/personas';
import InterviewOptions from '../components/InterviewOptions.vue';

const session = useSessionStore();
const ui = useUiStore();
const route = useRoute();

const groups = ref([]);
const loading = ref(false);
const selectedIds = ref([]);
const selectedPersonas = ref(['standard']);
const difficulty = ref(2);
// 学习模式（R-11）：出题后自动展开评测讲解
const studyMode = ref(false);
const studyOpts = computed({
  get: () => ({ study_mode: studyMode.value }),
  set: (v) => { studyMode.value = !!v.study_mode; },
});
// 来自报告「去练习」的目标任务（R-10）：source = `${sessionId}:${day}:${index}`
const practiceTarget = ref(null);

const DIFFICULTIES = [
  [2, '基础', '概念与原理'],
  [3, '进阶', '源码与参数'],
  [4, '深入', '对比与场景'],
];

// 平铺索引：id -> 叶子标签（含所属分组，便于摘要展示）
const tagIndex = computed(() => {
  const map = {};
  groups.value.forEach((g) => (g.children || []).forEach((t) => {
    map[t.id] = { ...t, group: g.label };
  }));
  return map;
});
const selectedTags = computed(() => selectedIds.value.map((id) => tagIndex.value[id]).filter(Boolean));

async function loadTags() {
  loading.value = true;
  try {
    groups.value = await req('/gauntlet/tags');
  } finally {
    loading.value = false;
  }
}

function toggle(id) {
  const i = selectedIds.value.indexOf(id);
  if (i >= 0) {
    selectedIds.value.splice(i, 1);
    return;
  }
  if (selectedIds.value.length >= 6) {
    ui.toast('最多选择 6 个标签', true);
    return;
  }
  selectedIds.value.push(id);
}

function togglePersona(v) {
  const i = selectedPersonas.value.indexOf(v);
  if (i >= 0) selectedPersonas.value.splice(i, 1);
  else selectedPersonas.value.push(v);
}

function start() {
  if (!selectedIds.value.length) {
    ui.toast('请至少选择一个八股标签', true);
    return;
  }
  if (!selectedPersonas.value.length) {
    ui.toast('至少选择一位面试官风格', true);
    return;
  }
  session.gauntletSource = practiceTarget.value ? practiceTarget.value.source : null;
  session.startGauntlet(selectedIds.value.slice(), selectedPersonas.value.slice(), difficulty.value, studyMode.value);
}

/* ---------------- 薄弱点推荐（R-11） ---------------- */
// 技能/关键词 → 主标签 id（画像 weakPoints 里的文字往往不是标签 id，需要模糊匹配）
const SKILL_RULES = [
  [/java|jvm|并发|spring|集合/i, 'java-collection'],
  [/mysql|索引|事务|sql|数据库/i, 'mysql-index'],
  [/redis|缓存|持久化/i, 'redis-cache'],
  [/mq|消息|kafka|rabbit/i, 'sd-mq'],
  [/网络|tcp|http|协议/i, 'cs-network'],
  [/操作系统|进程|线程|内存管理/i, 'cs-os'],
  [/分布式|高并发|系统设计|架构/i, 'sd-distributed'],
];
const FALLBACK_TAGS = ['java-collection', 'mysql-index', 'redis-cache'];

const recommended = computed(() => {
  const ids = [];
  const prof = (session.profiles || []).find((p) => Number(p.id) === Number(session.profileId))
    || (session.profiles || [])[0];
  (Array.isArray(prof?.weakPoints) ? prof.weakPoints : []).forEach((w) => {
    const text = String(typeof w === 'string' ? w : (w && (w.skill || w.detail)) || '');
    const hit = SKILL_RULES.find(([re]) => re.test(text));
    if (hit && tagIndex.value[hit[1]] && !ids.includes(hit[1])) ids.push(hit[1]);
  });
  return ids.length ? ids.slice(0, 4) : FALLBACK_TAGS;
});

function applyRecommendation() {
  selectedIds.value = recommended.value.filter((id) => tagIndex.value[id]).slice(0, 6);
}

function randomThree() {
  const all = Object.keys(tagIndex.value);
  for (let i = all.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [all[i], all[j]] = [all[j], all[i]];
  }
  selectedIds.value = all.slice(0, 3);
}

/** 报告「去练习」带入的预选与目标（R-10） */
function consumeQuery() {
  const q = route.query;
  const tags = String(q.tags || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (tags.length) selectedIds.value = tags.filter((id) => tagIndex.value[id]).slice(0, 6);
  const d = Number(q.difficulty);
  if (d >= 1 && d <= 5) difficulty.value = Math.min(4, Math.max(2, d));
  if (q.source) {
    const [sid, day, idx] = String(q.source).split(':');
    practiceTarget.value = {
      source: String(q.source), sessionId: Number(sid), day: Number(day), index: Number(idx),
      focus: String(q.focus || ''),
    };
  }
}

// 分组图标：JAVA 咖啡杯 / 数据类数据库 / 其余芯片；纯视觉辅助
function groupIcon(id) {
  if (id === 'java') return Coffee;
  if (id === 'mysql' || id === 'redis') return Database;
  return Cpu;
}

onMounted(() => ui.guard(async () => {
  await loadTags();
  consumeQuery();
}));
</script>

<template>
  <section class="view active stagger">
    <div class="card gauntlet-hero">
      <div class="gh-main">
        <span class="gh-ic"><Swords :size="22" /></span>
        <div>
          <h3><span class="ic"><Swords :size="16" /></span>八股拷打</h3>
          <p class="hint">无需简历与 JD，选标签即可开始。AI 连续出题、逐题评分，随时可结束并生成复盘。</p>
        </div>
      </div>
      <div class="gh-facts">
        <span class="metric-chip"><Check :size="14" /> 无 JD / 简历</span>
        <span class="metric-chip"><Flame :size="14" /> 多标签轮转</span>
        <span class="metric-chip"><Swords :size="14" /> 30 题软上限</span>
      </div>
    </div>

    <!-- 报告「去练习」带入的目标（R-10） -->
    <div v-if="practiceTarget" class="card practice-banner">
      <span class="ic"><Target :size="15" /></span>
      <span>
        本次目标：Day {{ practiceTarget.day }} · 任务 {{ practiceTarget.index + 1 }}
        <template v-if="practiceTarget.focus"> · {{ practiceTarget.focus }}</template>
        <small class="mini">（完成后回到报告查看完成态）</small>
      </span>
      <button class="ghost btn-ic" @click="practiceTarget = null">
        <span class="ic"><X :size="13" /></span>忽略目标
      </button>
    </div>

    <div class="card">
      <h3>
        <span class="ic"><Boxes :size="16" /></span>选择拷打标签
        <small class="tag-count">{{ selectedIds.length }} / 6</small>
      </h3>
      <p class="hint">按选择顺序轮流出题；每个标签映射到对应技术域，只考概念、原理与场景，不含手写代码。</p>

      <!-- 薄弱点推荐（R-11）：命中画像 weakPoints，否则回退常见三件套 -->
      <div class="reco-row">
        <span class="reco-title"><Sparkles :size="13" />基于你的薄弱点推荐</span>
        <button v-for="id in recommended" :key="id" class="chip-btn reco-chip" @click="toggle(id)">
          {{ (tagIndex[id] && tagIndex[id].label) || id }}
        </button>
        <button class="ghost btn-ic" @click="applyRecommendation">全部选中</button>
        <button class="ghost btn-ic" @click="randomThree">
          <span class="ic"><Shuffle :size="13" /></span>随机 3 个
        </button>
      </div>

      <div v-if="loading" class="kv">标签加载中…</div>
      <div v-else-if="!groups.length" class="kv">暂无可用标签，请检查服务状态后重试。</div>
      <div v-else class="tag-groups">
        <div v-for="g in groups" :key="g.id" class="tag-group">
          <div class="tag-group-title">
            <span class="ic"><component :is="groupIcon(g.id)" :size="14" /></span>{{ g.label }}
          </div>
          <div class="tag-chips">
            <button
              v-for="t in g.children"
              :key="t.id"
              class="tag-chip"
              :class="{ on: selectedIds.includes(t.id) }"
              @click="toggle(t.id)"
            >
              <Check v-if="selectedIds.includes(t.id)" :size="13" />{{ t.label }}
              <small v-if="t.hasSkill === false" class="tag-generic" title="该标签暂无专属 Skill 文档，问答走通用提示词">通用</small>
            </button>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3><span class="ic"><Swords :size="16" /></span>拷打设置</h3>
      <label>面试官风格（可多选，按轮次轮流发言）</label>
      <div class="persona-chips">
        <div
          v-for="[v, l, d] in PERSONAS"
          :key="v"
          class="pchip"
          :class="{ on: selectedPersonas.includes(v) }"
          @click="togglePersona(v)"
        >
          <span class="pa" :class="'pa-' + v">{{ l.slice(0, 1) }}</span>
          <div><div class="pchip-t">{{ l }}</div><div class="pchip-d">{{ d }}</div></div>
        </div>
      </div>
      <label>起始难度</label>
      <div class="seg">
        <button
          v-for="[v, l, d] in DIFFICULTIES"
          :key="v"
          :class="{ on: difficulty === v }"
          @click="difficulty = v"
        >
          {{ l }} <small>{{ d }}</small>
        </button>
      </div>
      <label>练习模式</label>
      <InterviewOptions v-model="studyOpts" variant="gauntlet" />
      <div class="summary-bar">
        <span class="sum-ok" :class="{ done: selectedIds.length > 0 }">
          <span class="ic"><Check :size="14" /></span>{{ selectedIds.length }} 个标签
        </span>
        <span class="sum-ok" :class="{ done: selectedPersonas.length > 0 }">
          <span class="ic"><Swords :size="14" /></span>{{ selectedPersonas.length }} 位面试官
        </span>
        <span class="grow"></span>
        <button class="primary btn-ic" @click="start">
          <span class="ic"><Play :size="15" /></span>开始八股拷打
        </button>
      </div>
      <p v-if="selectedTags.length" class="hint">
        已选：{{ selectedTags.map((t) => t.label).join(' → ') }}
      </p>
    </div>
  </section>
</template>

<style scoped>
.gauntlet-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}
.gh-main { display: flex; align-items: flex-start; gap: 14px; }
.gh-ic {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  background: var(--accent-soft);
  color: var(--accent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.gh-main h3 { margin-bottom: 6px; }
.gh-main .hint { margin: 0; }
.gh-facts { display: flex; flex-wrap: wrap; gap: 8px; }
.tag-count { margin-left: 6px; font-size: 12px; color: var(--accent); }
.tag-groups { display: flex; flex-direction: column; gap: 16px; margin-top: 4px; }
.tag-group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}
.tag-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.tag-generic {
  margin-left: 5px; padding: 0 6px; border-radius: var(--radius-pill);
  background: var(--surface-2); color: var(--text-3); font-size: 10px;
}
.practice-banner { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; color: var(--text-1); }
.practice-banner .ic { color: var(--accent); }
.practice-banner .ghost { margin-left: auto; }
.reco-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin: 8px 0 12px; }
.reco-title { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--text-2); }
.reco-title .ic { color: var(--accent); }
.reco-chip { font-size: 11.5px; padding: 2px 9px; }
.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  transition: border-color .2s ease, background .2s ease, color .2s ease;
}
.tag-chip:hover { border-color: var(--line-strong); color: var(--text-1); }
.tag-chip.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.seg small { margin-left: 4px; opacity: .66; }
@media (max-width: 640px) {
  .gauntlet-hero { align-items: flex-start; }
}
</style>
