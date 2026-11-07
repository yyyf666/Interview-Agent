<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import {
  BookOpen, UploadCloud, Database, Library, RefreshCw, RotateCcw, Layers, Search,
  Trash2, Download, Eye, Plus, Pencil, FileJson, FileDown, Activity, Gauge,
  ChevronLeft, ChevronRight, X, Check, AlertTriangle,
} from 'lucide-vue-next';
import { useManageStore } from '../stores/manage';
import { useUiStore } from '../stores/ui';
import { track } from '../utils/track';
import EChart from '../components/EChart.vue';

// 管理页（R-12）：知识库 / 题库 / 索引健康 / 检索与 Skills / AI 用量
const manage = useManageStore();
const ui = useUiStore();

const TABS = [
  { id: 'knowledge', label: '知识库', icon: BookOpen },
  { id: 'questions', label: '题库', icon: Library },
  { id: 'health', label: '索引健康', icon: Activity },
  { id: 'tools', label: '检索 / Skills', icon: Search },
  { id: 'usage', label: 'AI 用量', icon: Gauge },
];
const tab = ref('knowledge');

function ensureLoaded(id) {
  if (id === 'knowledge') manage.loadKnowledge();
  if (id === 'questions') manage.loadQuestions();
  if (id === 'health') manage.loadHealth();
  if (id === 'tools') { manage.loadSkills(); manage.loadGauntletTags(); }
  if (id === 'usage') manage.loadAiUsage(usageDays.value);
}
function selectTab(id) {
  tab.value = id;
  ensureLoaded(id);
}

/* ---------------- 知识库 ---------------- */
const kbFileEl = ref(null);
const kbFiles = ref([]);
const kbTags = ref('');
const kbDetail = reactive({ open: false, loading: false, doc: null, preview: '', downloadable: false });

function onKbPick(e) {
  kbFiles.value = [...(e.target.files || [])];
}
function uploadKb() {
  const tags = kbTags.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  ui.guard(async () => {
    await manage.uploadKnowledgeFiles(kbFiles.value, tags);
    kbFiles.value = [];
    kbTags.value = '';
    if (kbFileEl.value) kbFileEl.value.value = '';
  });
}
function openKbDetail(d) {
  kbDetail.open = true;
  kbDetail.loading = true;
  kbDetail.doc = d;
  kbDetail.preview = '';
  ui.guard(async () => {
    const r = await manage.knowledgeDetail(d.id);
    kbDetail.doc = r.doc || d;
    kbDetail.preview = r.preview || '';
    kbDetail.downloadable = !!r.downloadable;
  }).finally(() => { kbDetail.loading = false; });
}
function reindexDoc(d) {
  ui.guard(() => manage.reindexKnowledge(d.id));
}
function downloadDoc(d) {
  ui.guard(() => manage.downloadKnowledge(d.id));
}
async function removeDoc(d) {
  const ok = await ui.ask({
    title: '删除知识文档',
    message: `确认删除「${d.title || d.id}」？向量分块会同步清理，不可恢复。`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  ui.guard(() => manage.deleteKnowledge(d.id));
}

/* ---------------- 题库 ---------------- */
const qEditor = reactive({
  open: false, id: null, content: '', skillName: '', difficulty: 2,
  qType: 'concept', referenceAnswer: '', tags: '', saving: false,
});
const Q_TYPES = [
  { v: 'concept', label: '概念' },
  { v: 'principle', label: '原理' },
  { v: 'scenario', label: '场景' },
  { v: 'project', label: '项目' },
  { v: 'behavior', label: '行为' },
];

function openCreate() {
  Object.assign(qEditor, {
    open: true, id: null, content: '', skillName: '', difficulty: 2,
    qType: 'concept', referenceAnswer: '', tags: '', saving: false,
  });
}
function openEdit(q) {
  Object.assign(qEditor, {
    open: true, id: q.id, content: q.content || '',
    skillName: q.skillName || q.skill_name || '',
    difficulty: q.difficulty || 2,
    qType: q.qType || q.q_type || 'concept',
    referenceAnswer: q.referenceAnswer || q.reference_answer || '',
    tags: Array.isArray(q.tags) ? q.tags.join(',') : (q.tags || ''),
    saving: false,
  });
}
function saveQuestion() {
  if (!qEditor.content.trim()) return ui.toast('题干不能为空', true);
  const payload = {
    content: qEditor.content.trim(),
    skillName: qEditor.skillName.trim(),
    difficulty: Number(qEditor.difficulty) || 2,
    qType: qEditor.qType,
    referenceAnswer: qEditor.referenceAnswer,
    tags: qEditor.tags.split(/[,，]/).map((s) => s.trim()).filter(Boolean),
  };
  qEditor.saving = true;
  ui.guard(async () => {
    if (qEditor.id) await manage.updateQuestion(qEditor.id, payload);
    else await manage.createQuestion(payload);
    qEditor.open = false;
  }).finally(() => { qEditor.saving = false; });
}
async function removeQuestion(q) {
  const ok = await ui.ask({
    title: '删除题目',
    message: `确认删除该题目？\n${(q.content || '').slice(0, 80)}`,
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  ui.guard(() => manage.deleteQuestion(q.id));
}
function searchQuestions() {
  ui.guard(() => manage.loadQuestions({ pageNo: 1 }));
}
function resetQuestionFilter() {
  ui.guard(manage.resetQuestionFilter);
}
function pageQuestion(delta) {
  const next = manage.questionPage.pageNo + delta;
  if (next < 1) return;
  const maxPage = Math.max(1, Math.ceil(manage.questionPage.total / manage.questionPage.pageSize));
  if (next > maxPage) return;
  ui.guard(() => manage.loadQuestions({ pageNo: next }));
}

/* ---------------- 题库导入 / 导出 ---------------- */
const importFileEl = ref(null);
const importState = reactive({ open: false, fileName: '', list: [], dupInBatch: 0, result: null, importing: false });

function onImportPick(e) {
  const file = (e.target.files || [])[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const parsed = JSON.parse(String(reader.result || '[]'));
      const list = Array.isArray(parsed) ? parsed : (Array.isArray(parsed.questions) ? parsed.questions : null);
      if (!list) throw new Error('JSON 需为题目数组或 {questions:[...]}');
      const seen = new Set();
      let dup = 0;
      list.forEach((q) => {
        const c = q && q.content ? String(q.content).trim() : '';
        if (!c) return;
        if (seen.has(c)) dup += 1;
        seen.add(c);
      });
      Object.assign(importState, { open: true, fileName: file.name, list, dupInBatch: dup, result: null });
    } catch (err) {
      ui.toast('JSON 解析失败：' + err.message, true);
    }
  };
  reader.readAsText(file);
  if (importFileEl.value) importFileEl.value.value = '';
}
function confirmImport() {
  importState.importing = true;
  track('admin_action', { action: 'question_import', count: importState.list.length });
  ui.guard(async () => {
    importState.result = await manage.importQuestions(importState.list);
    ui.toast(`导入完成：新增 ${importState.result.imported}，跳过 ${importState.result.skipped}`);
  }).finally(() => { importState.importing = false; });
}
function closeImport() {
  importState.open = false;
  importState.list = [];
  importState.result = null;
}

/* ---------------- 索引健康 ---------------- */
const healthCards = computed(() => {
  const h = manage.health || {};
  return [
    { label: '知识文档', value: h.docCount, sub: `已索引 ${h.docIndexed ?? 0} · 失败 ${h.docFailed ?? 0}` },
    { label: '向量分块', value: h.chunkCount, sub: 'knowledge_vector' },
    { label: '题库题目', value: h.questionCount, sub: `已索引 ${h.questionIndexed ?? 0}` },
    { label: 'Embedding', value: h.embeddingConfigured ? '已配置' : '未就绪', sub: h.embeddingModel || '—' },
  ];
});
function fmtTime(v) {
  if (!v) return '—';
  return String(v).replace('T', ' ').slice(0, 19);
}
function rebuildAll() {
  track('admin_action', { action: 'index_rebuild' });
  ui.guard(manage.rebuildAll, 'rebuild');
}

/* ---------------- 检索 / Skills ---------------- */
const skillOptions = computed(() => (manage.skills || []).map((s) => s.name));
function runSearch() {
  manage.searchKnowledge();
}
function hitText(hit) {
  return hit.content || hit.text || hit.chunk || hit.question || '';
}
function hitScore(hit) {
  const v = hit.score != null ? hit.score : (hit.similarity != null ? hit.similarity : hit.rerank_score);
  return v == null ? '' : Number(v).toFixed(4);
}
function reloadSkills() {
  track('admin_action', { action: 'skill_reload' });
  ui.guard(manage.reloadSkills);
}
const tagGroups = computed(() => manage.gauntletTags || []);

/* ---------------- AI 用量 ---------------- */
const usageDays = ref(14);
function loadUsage() {
  ui.guard(() => manage.loadAiUsage(usageDays.value));
}
const usageOption = computed(() => {
  const daily = (manage.aiUsage && manage.aiUsage.daily) || [];
  return {
    grid: { left: 46, right: 18, top: 26, bottom: 30 },
    tooltip: { trigger: 'axis' },
    legend: { show: false },
    xAxis: {
      type: 'category',
      data: daily.map((d) => String(d.day || '')),
      axisLabel: { fontSize: 11 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      type: 'bar',
      name: 'LLM 调用',
      data: daily.map((d) => Number(d.llm_calls || 0)),
      itemStyle: { color: '#2563eb', borderRadius: [4, 4, 0, 0] },
      barMaxWidth: 26,
    }],
  };
});
const usageTotal = computed(() => (manage.aiUsage && manage.aiUsage.total) || {});
const usageSessions = computed(() => (manage.aiUsage && manage.aiUsage.topSessions) || []);

onMounted(() => {
  manage.loadKnowledge();
  manage.loadHealth();
});
</script>

<template>
  <section class="view active">
    <div class="card">
      <div class="manage-head">
        <h3><span class="ic"><Database :size="16" /></span>管理中心</h3>
        <div class="seg manage-tabs" role="tablist" aria-label="管理功能分区">
          <button
            v-for="t in TABS"
            :key="t.id"
            class="seg-btn"
            :class="{ on: tab === t.id }"
            role="tab"
            :aria-selected="tab === t.id"
            @click="selectTab(t.id)"
          >
            <component :is="t.icon" :size="13" />{{ t.label }}
          </button>
        </div>
      </div>
    </div>

    <!-- ========== 知识库 ========== -->
    <div v-if="tab === 'knowledge'" class="card">
      <h3><span class="ic"><BookOpen :size="16" /></span>知识库文档</h3>
      <label class="dropzone">
        <span class="ic"><UploadCloud :size="20" /></span>
        <span>{{ kbFiles.length ? `已选 ${kbFiles.length} 篇` : '点击选择文件（md / txt / pdf / docx，可多选）' }}</span>
        <input ref="kbFileEl" type="file" multiple accept=".md,.txt,.pdf,.docx" @change="onKbPick" />
      </label>
      <input v-model="kbTags" placeholder="标签，逗号分隔（可选）" />
      <div class="kb-actions">
        <button class="btn-ic" :disabled="!kbFiles.length" @click="uploadKb">
          <UploadCloud :size="14" /> 上传入库
        </button>
        <button class="ghost btn-ic" @click="ui.guard(manage.loadKnowledge)">
          <RefreshCw :size="14" /> 刷新
        </button>
        <span v-if="manage.uploadProgress.total" class="mini">
          进度 {{ manage.uploadProgress.done }}/{{ manage.uploadProgress.total }}
          <template v-if="manage.uploadProgress.current"> · {{ manage.uploadProgress.current }}</template>
        </span>
      </div>

      <div class="mini-list tall">
        <div v-for="d in manage.knowledge" :key="d.id || d.title" class="item kb-row">
          <span class="kb-title">{{ d.title }}</span>
          <span class="badge" :class="{ ok: d.chunkStatus === 'INDEXED', warn: d.chunkStatus === 'FAILED' }">
            <span class="ic"><Database :size="11" /></span>{{ d.chunkStatus }}
          </span>
          <small><Layers :size="11" style="vertical-align:-2px" /> {{ d.chunkCount || 0 }} 块</small>
          <span class="spacer"></span>
          <button class="row-act" title="详情与原文预览" aria-label="详情" @click="openKbDetail(d)">
            <Eye :size="13" />
          </button>
          <button class="row-act" title="重建索引" aria-label="重建索引" @click="reindexDoc(d)">
            <RotateCcw :size="13" />
          </button>
          <button class="row-act" title="下载原文" aria-label="下载原文" @click="downloadDoc(d)">
            <Download :size="13" />
          </button>
          <button class="row-act del" title="删除文档" aria-label="删除文档" @click="removeDoc(d)">
            <Trash2 :size="13" />
          </button>
        </div>
        <div v-if="!manage.knowledge.length" class="kv">{{ manage.knowledgeLoading ? '加载中…' : '暂无文档' }}</div>
      </div>

      <div v-if="kbDetail.open" class="sub-panel">
        <div class="sub-head">
          <strong>{{ kbDetail.doc && kbDetail.doc.title }}</strong>
          <span class="spacer"></span>
          <button v-if="kbDetail.downloadable" class="ghost btn-ic" @click="downloadDoc(kbDetail.doc)">
            <Download :size="13" /> 下载原文
          </button>
          <button class="icon-btn" aria-label="关闭详情" @click="kbDetail.open = false"><X :size="14" /></button>
        </div>
        <div class="mini">
          分块数 {{ (kbDetail.doc && kbDetail.doc.chunkCount) || 0 }} · 状态 {{ (kbDetail.doc && kbDetail.doc.chunkStatus) || '—' }}
          · 版本 v{{ (kbDetail.doc && kbDetail.doc.version) || 1 }}
          · 更新 {{ fmtTime(kbDetail.doc && kbDetail.doc.updatedAt) }}
        </div>
        <pre v-if="kbDetail.preview" class="doc-preview">{{ kbDetail.preview }}</pre>
        <div v-else-if="kbDetail.loading" class="kv">加载中…</div>
        <div v-else class="kv">该格式不支持在线预览（PDF/Word），可下载原文查看</div>
      </div>
    </div>

    <!-- ========== 题库 ========== -->
    <div v-if="tab === 'questions'" class="card">
      <h3><span class="ic"><Library :size="16" /></span>题库</h3>
      <div class="q-toolbar">
        <input v-model="manage.questionFilter.skill" placeholder="技能（如 java）" @keyup.enter="searchQuestions" />
        <select v-model="manage.questionFilter.difficulty">
          <option value="">全部难度</option>
          <option v-for="n in 5" :key="n" :value="String(n)">难度 {{ n }}</option>
        </select>
        <select v-model="manage.questionFilter.qType">
          <option value="">全部题型</option>
          <option v-for="t in Q_TYPES" :key="t.v" :value="t.v">{{ t.label }}</option>
        </select>
        <input v-model="manage.questionFilter.kw" placeholder="题干关键词" @keyup.enter="searchQuestions" />
        <button class="ghost btn-ic" @click="searchQuestions"><Search :size="13" /> 查询</button>
        <button class="ghost btn-ic" @click="resetQuestionFilter"><RotateCcw :size="13" /> 重置</button>
      </div>
      <div class="q-toolbar">
        <button class="btn-ic" @click="openCreate"><Plus :size="14" /> 新增题目</button>
        <button class="ghost btn-ic" @click="importFileEl && importFileEl.click()">
          <FileJson :size="14" /> JSON 导入
        </button>
        <input ref="importFileEl" type="file" accept=".json" class="hidden-input" @change="onImportPick" />
        <button class="ghost btn-ic" @click="manage.exportQuestionsCsv"><FileDown :size="14" /> CSV 导出</button>
        <button class="ghost btn-ic" @click="ui.guard(manage.reindexQuestions)"><RotateCcw :size="14" /> 重建题库向量</button>
        <span class="spacer"></span>
        <span class="mini">共 {{ manage.questionPage.total }} 题</span>
      </div>

      <table class="data-table">
        <thead>
          <tr><th style="width:56px">ID</th><th>题干</th><th style="width:96px">技能</th><th style="width:64px">难度</th><th style="width:76px">题型</th><th style="width:88px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="q in manage.questions" :key="q.id">
            <td class="mono">{{ q.id }}</td>
            <td class="q-content">{{ q.content }}</td>
            <td>{{ q.skillName || q.skill_name || '—' }}</td>
            <td>{{ q.difficulty }}</td>
            <td>{{ q.qType || q.q_type || '—' }}</td>
            <td>
              <button class="row-act" title="编辑" aria-label="编辑题目" @click="openEdit(q)"><Pencil :size="13" /></button>
              <button class="row-act del" title="删除" aria-label="删除题目" @click="removeQuestion(q)"><Trash2 :size="13" /></button>
            </td>
          </tr>
          <tr v-if="!manage.questions.length"><td colspan="6" class="kv">暂无题目</td></tr>
        </tbody>
      </table>
      <div class="pager">
        <button class="ghost btn-ic" :disabled="manage.questionPage.pageNo <= 1" @click="pageQuestion(-1)">
          <ChevronLeft :size="13" /> 上一页
        </button>
        <span class="mini">第 {{ manage.questionPage.pageNo }} 页 / 共 {{ Math.max(1, Math.ceil(manage.questionPage.total / manage.questionPage.pageSize)) }} 页</span>
        <button class="ghost btn-ic" @click="pageQuestion(1)">
          下一页 <ChevronRight :size="13" />
        </button>
      </div>

      <div v-if="qEditor.open" class="sub-panel">
        <div class="sub-head">
          <strong>{{ qEditor.id ? '编辑题目' : '新增题目' }}</strong>
          <span class="spacer"></span>
          <button class="icon-btn" aria-label="关闭编辑" @click="qEditor.open = false"><X :size="14" /></button>
        </div>
        <textarea v-model="qEditor.content" rows="3" placeholder="题干（必填）"></textarea>
        <div class="q-toolbar">
          <input v-model="qEditor.skillName" placeholder="技能，如 java / mysql" />
          <select v-model="qEditor.difficulty">
            <option v-for="n in 5" :key="n" :value="n">难度 {{ n }}</option>
          </select>
          <select v-model="qEditor.qType">
            <option v-for="t in Q_TYPES" :key="t.v" :value="t.v">{{ t.label }}</option>
          </select>
          <input v-model="qEditor.tags" placeholder="标签，逗号分隔" />
        </div>
        <textarea v-model="qEditor.referenceAnswer" rows="3" placeholder="参考答案（可选，用于评测参考）"></textarea>
        <div class="q-toolbar">
          <button class="primary btn-ic" :disabled="qEditor.saving" @click="saveQuestion">
            <Check :size="14" /> 保存
          </button>
        </div>
      </div>

      <div v-if="importState.open" class="sub-panel">
        <div class="sub-head">
          <strong>导入预览 · {{ importState.fileName }}</strong>
          <span class="spacer"></span>
          <button class="icon-btn" aria-label="关闭导入" @click="closeImport"><X :size="14" /></button>
        </div>
        <div class="mini">
          共 {{ importState.list.length }} 条，批内重复 {{ importState.dupInBatch }} 条；导入时按题干去重（含库内已有题目）
        </div>
        <div v-if="!importState.result" class="q-toolbar">
          <button class="primary btn-ic" :disabled="importState.importing" @click="confirmImport">
            <UploadCloud :size="14" /> 确认导入
          </button>
          <button class="ghost btn-ic" @click="closeImport">取消</button>
        </div>
        <div v-else class="import-result">
          <span class="badge ok"><Check :size="11" /> 新增 {{ importState.result.imported }}</span>
          <span class="badge"><AlertTriangle :size="11" /> 跳过 {{ importState.result.skipped }}</span>
          <ul v-if="importState.result.errors && importState.result.errors.length" class="err-list">
            <li v-for="(e, i) in importState.result.errors" :key="i">{{ e }}</li>
          </ul>
          <button class="ghost btn-ic" @click="closeImport">完成</button>
        </div>
      </div>
    </div>

    <!-- ========== 索引健康 ========== -->
    <div v-if="tab === 'health'" class="card">
      <h3><span class="ic"><Activity :size="16" /></span>索引健康</h3>
      <div class="metric-row">
        <div v-for="c in healthCards" :key="c.label" class="metric-chip">
          <span class="mc-label">{{ c.label }}</span>
          <b>{{ c.value == null ? '—' : c.value }}</b>
          <small>{{ c.sub }}</small>
        </div>
      </div>
      <div class="mini">
        文档索引更新：{{ fmtTime(manage.health && manage.health.docVectorUpdatedAt) }}
        · 题库索引更新：{{ fmtTime(manage.health && manage.health.questionVectorUpdatedAt) }}
      </div>
      <div class="q-toolbar">
        <button class="btn-ic" :disabled="manage.rebuildProgress.running" @click="rebuildAll">
          <RotateCcw :size="14" /> 重建全部索引
        </button>
        <button class="ghost btn-ic" @click="ui.guard(manage.loadHealth)"><RefreshCw :size="14" /> 刷新指标</button>
        <span v-if="manage.rebuildProgress.running" class="mini">
          进行中 {{ manage.rebuildProgress.done }}/{{ manage.rebuildProgress.total }}
        </span>
      </div>
      <ul v-if="manage.rebuildProgress.failed.length" class="err-list">
        <li v-for="(f, i) in manage.rebuildProgress.failed" :key="i">{{ f.title }}：{{ f.reason }}</li>
      </ul>
      <div v-if="!manage.health" class="kv">{{ manage.healthLoading ? '加载中…' : '暂无指标' }}</div>
    </div>

    <!-- ========== 检索 / Skills ========== -->
    <div v-if="tab === 'tools'" class="grid2">
      <div class="card">
        <h3><span class="ic"><Search :size="16" /></span>检索预览</h3>
        <div class="q-toolbar">
          <input v-model="manage.searchState.query" placeholder="输入问题，查看 RAG 召回片段" @keyup.enter="runSearch" />
          <select v-model="manage.searchState.skill">
            <option value="">全部技能</option>
            <option v-for="s in skillOptions" :key="s" :value="s">{{ s }}</option>
          </select>
          <input v-model.number="manage.searchState.topK" type="number" min="1" max="20" class="topk" />
          <button class="primary btn-ic" :disabled="manage.searchState.loading" @click="runSearch">
            <Search :size="13" /> 检索
          </button>
        </div>
        <div v-if="manage.searchState.error" class="error-banner" role="alert">{{ manage.searchState.error }}</div>
        <div class="mini-list tall">
          <div v-for="(hit, i) in manage.searchState.results" :key="i" class="item hit-row">
            <span class="badge">#{{ i + 1 }}</span>
            <span class="hit-text">{{ hitText(hit) }}</span>
            <small v-if="hitScore(hit) !== ''">score {{ hitScore(hit) }}</small>
          </div>
          <div v-if="!manage.searchState.results.length" class="kv">
            {{ manage.searchState.loading ? '检索中…' : '暂无结果（需先配置 Embedding）' }}
          </div>
        </div>
      </div>

      <div class="card">
        <h3><span class="ic"><Layers :size="16" /></span>Skills 覆盖</h3>
        <div class="q-toolbar">
          <button class="ghost btn-ic" @click="reloadSkills"><RotateCcw :size="14" /> 重载 Skills</button>
          <button class="ghost btn-ic" @click="ui.guard(manage.loadSkills)"><RefreshCw :size="14" /> 刷新清单</button>
        </div>
        <table class="data-table">
          <thead><tr><th>Skill</th><th style="width:150px">SKILL.md 时间</th></tr></thead>
          <tbody>
            <tr v-for="s in manage.skills" :key="s.name">
              <td><span class="badge ok"><Check :size="11" />{{ s.name }}</span></td>
              <td class="mono">{{ s.mtime || '—' }}</td>
            </tr>
            <tr v-if="!manage.skills.length"><td colspan="2" class="kv">{{ manage.skillsLoading ? '加载中…' : '暂无 Skill' }}</td></tr>
          </tbody>
        </table>
        <div class="mini" style="margin-top:10px">八股标签覆盖（无专属 SKILL.md 的标签标为「通用」）</div>
        <div class="mini-list tall">
          <div v-for="g in tagGroups" :key="g.id" class="tag-group">
            <small>{{ g.label }}</small>
            <div class="tag-chips">
              <span
                v-for="t in g.children"
                :key="t.id"
                class="badge"
                :class="{ ok: t.hasSkill !== false }"
                :title="t.hasSkill !== false ? `Skill: ${t.skill}` : '无专属 Skill，走通用提示词'"
              >
                {{ t.label }}<template v-if="t.hasSkill === false"> · 通用</template>
              </span>
            </div>
          </div>
          <div v-if="!tagGroups.length" class="kv">暂无标签数据</div>
        </div>
      </div>
    </div>

    <!-- ========== AI 用量 ========== -->
    <div v-if="tab === 'usage'" class="card">
      <h3><span class="ic"><Gauge :size="16" /></span>AI 用量（近 {{ usageDays }} 天）</h3>
      <div class="q-toolbar">
        <select v-model.number="usageDays" @change="loadUsage">
          <option :value="7">近 7 天</option>
          <option :value="14">近 14 天</option>
          <option :value="30">近 30 天</option>
        </select>
        <button class="ghost btn-ic" @click="loadUsage"><RefreshCw :size="14" /> 刷新</button>
        <span class="spacer"></span>
        <span class="mini">
          总调用 {{ usageTotal.llm_calls ?? 0 }} 次 · 总耗时 {{ ((usageTotal.elapsed_ms || 0) / 1000).toFixed(1) }} s
        </span>
      </div>
      <EChart :option="usageOption" height="240px" />
      <table class="data-table" style="margin-top:10px">
        <thead><tr><th style="width:76px">会话</th><th>标题</th><th style="width:96px">LLM 调用</th><th style="width:96px">耗时(s)</th></tr></thead>
        <tbody>
          <tr v-for="s in usageSessions" :key="s.session_id || s.sessionId">
            <td class="mono">{{ s.session_id ?? s.sessionId ?? '—' }}</td>
            <td class="q-content">{{ s.title || '—' }}</td>
            <td>{{ s.llm_calls ?? 0 }}</td>
            <td>{{ ((s.elapsed_ms || 0) / 1000).toFixed(1) }}</td>
          </tr>
          <tr v-if="!usageSessions.length"><td colspan="4" class="kv">暂无用量记录（作答后自动累计）</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.manage-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.manage-head h3 { margin: 0; }
.manage-tabs { margin-left: auto; flex-wrap: wrap; }
.kb-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.kb-row { gap: 8px; }
.kb-title { font-size: 13px; flex: 1 1 180px; min-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-act.del:hover { color: var(--err); border-color: var(--err); }
.sub-panel { margin-top: 14px; padding: 12px; border: 1px solid var(--line); border-radius: var(--radius-card); background: var(--surface); }
.sub-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.doc-preview {
  margin: 8px 0 0; padding: 10px; max-height: 320px; overflow: auto; font-size: 12px; line-height: 1.6;
  white-space: pre-wrap; word-break: break-word; background: var(--bg); border: 1px solid var(--line); border-radius: var(--radius-sm);
}
.q-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.q-toolbar input, .q-toolbar select { width: auto; min-width: 130px; margin: 0; padding: 6px 8px; font-size: 12.5px; }
.q-toolbar .topk { min-width: 66px; width: 66px; }
.hidden-input { display: none; }
.q-content { max-width: 520px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pager { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 10px; }
.import-result { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
.err-list { margin: 8px 0 0; padding-left: 18px; font-size: 12px; color: var(--err); }
.hit-row { align-items: flex-start; }
.hit-text { flex: 1; font-size: 12.5px; line-height: 1.6; word-break: break-word; }
.tag-group { margin-bottom: 8px; }
.tag-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.metric-row { display: flex; gap: 10px; flex-wrap: wrap; margin: 12px 0; }
.metric-row .metric-chip { flex: 1 1 160px; }
.metric-row .mc-label { display: block; font-size: 11.5px; color: var(--text-3); }
.metric-row .metric-chip small { display: block; margin-top: 2px; color: var(--text-3); font-size: 11px; }
.error-banner {
  margin-top: 10px; padding: 8px 10px; font-size: 12.5px; color: var(--err);
  background: var(--err-soft); border: 1px solid rgba(220, 38, 38, .25); border-radius: var(--radius-sm);
}
.mono { font-variant-numeric: tabular-nums; color: var(--text-3); }
</style>
