<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  BookOpen, Trash2, Eye, EyeOff, RefreshCw, ChevronDown, Filter, StickyNote, ListChecks,
} from 'lucide-vue-next';
import { req } from '../api/client';
import { useUiStore } from '../stores/ui';

// 错题本（R-09）：按掌握状态/标签筛选；复习模式默认隐藏答案与参考要点，点击揭示
const router = useRouter();
const ui = useUiStore();

const MASTERED = {
  new: ['未掌握', 'weak'],
  learning: ['练习中', 'basic'],
  mastered: ['已掌握', 'ok'],
};

const items = ref([]);
const tags = ref([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = 10;
const loading = ref(false);
const loadError = ref('');
const filter = reactive({ mastered: '', tag: '' });
const revealed = ref(new Set());
const editingNote = ref({});

const hasMore = computed(() => items.value.length < total.value);

async function load(reset = true) {
  loading.value = true;
  loadError.value = '';
  if (reset) {
    pageNo.value = 1;
    items.value = [];
  }
  try {
    const q = new URLSearchParams({ pageNo: String(pageNo.value), pageSize: String(pageSize) });
    if (filter.mastered) q.set('mastered', filter.mastered);
    if (filter.tag) q.set('tag', filter.tag);
    const r = await req('/review-items?' + q.toString());
    items.value = reset ? (r.records || []) : items.value.concat(r.records || []);
    total.value = r.total || 0;
    tags.value = r.tags || [];
  } catch (e) {
    loadError.value = e.message || '加载失败';
  } finally {
    loading.value = false;
  }
}

function loadMore() {
  if (loading.value || !hasMore.value) return;
  pageNo.value += 1;
  load(false);
}

function applyFilter(key, value) {
  filter[key] = filter[key] === value ? '' : value;
  revealed.value = new Set();
  load(true);
}

function toggleReveal(id) {
  const next = new Set(revealed.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  revealed.value = next;
}

async function setMastered(item, mastered) {
  await ui.guard(async () => {
    const updated = await req('/review-items/' + item.id, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mastered }),
    });
    item.mastered = updated.mastered;
    ui.toast('复习状态已更新');
  }, 'review-update');
}

function noteDraft(item) {
  return editingNote.value[item.id] != null ? editingNote.value[item.id] : (item.note || '');
}

function onNoteInput(item, value) {
  editingNote.value = { ...editingNote.value, [item.id]: value };
}

async function saveNote(item) {
  const draft = editingNote.value[item.id];
  if (draft == null) return;
  await ui.guard(async () => {
    const updated = await req('/review-items/' + item.id, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ note: draft }),
    });
    item.note = updated.note;
    const next = { ...editingNote.value };
    delete next[item.id];
    editingNote.value = next;
    ui.toast('备注已保存');
  }, 'review-note');
}

async function remove(item) {
  const ok = await ui.ask({
    title: '从错题本删除',
    message: '确定从错题本删除这条记录吗？删除后不再参与复习筛选。',
    confirmText: '删除',
    danger: true,
  });
  if (!ok) return;
  await ui.guard(async () => {
    await req('/review-items/' + item.id, { method: 'DELETE' });
    items.value = items.value.filter((x) => x.id !== item.id);
    total.value = Math.max(0, total.value - 1);
    ui.toast('已删除');
  }, 'review-delete');
}

function goSession(item) {
  if (item.sessionId) router.push({ name: 'report', params: { id: item.sessionId } });
}

function tagList(item) {
  return Array.isArray(item.tags) ? item.tags : [];
}

onMounted(() => load(true));
</script>

<template>
  <section class="view active stagger">
    <div class="card">
      <h3>
        <span class="ic"><BookOpen :size="16" /></span>错题本
        <span class="mini" style="margin-left:auto">共 {{ total }} 条</span>
        <button class="ghost btn-ic" title="刷新" @click="load(true)">
          <span class="ic"><RefreshCw :size="13" /></span>刷新
        </button>
      </h3>

      <div class="filter-bar">
        <span class="ic"><Filter :size="13" /></span>
        <button
          v-for="(v, k) in MASTERED"
          :key="k"
          class="chip-btn filter-chip"
          :class="{ on: filter.mastered === k }"
          @click="applyFilter('mastered', k)"
        >{{ v[0] }}</button>
        <span class="filter-sep"></span>
        <button
          v-for="t in tags"
          :key="t"
          class="chip-btn filter-chip"
          :class="{ on: filter.tag === t }"
          @click="applyFilter('tag', t)"
        >{{ t }}</button>
      </div>

      <div v-if="loading && !items.length" class="report-empty">
        <div class="sk-line"></div>
        <div class="sk-line"></div>
        <div class="sk-line short"></div>
      </div>
      <div v-else-if="loadError" class="report-empty">
        <span>加载失败：{{ loadError }}</span>
        <button class="ghost btn-ic" @click="load(true)">重试</button>
      </div>
      <div v-else-if="!items.length" class="report-empty">
        <span>错题本还是空的</span>
        <span class="mini">在面试评测卡或报告逐题复盘中点击「加入错题本」即可收藏</span>
      </div>

      <div v-else class="wrong-list">
        <article v-for="item in items" :key="item.id" class="wrong-item">
          <header class="wi-head">
            <span class="verdict" :class="(MASTERED[item.mastered] || MASTERED.new)[1]">
              {{ (MASTERED[item.mastered] || MASTERED.new)[0] }}
            </span>
            <span v-for="t in tagList(item)" :key="t" class="badge">{{ t }}</span>
            <span class="mini wi-date">{{ (item.createdAt || '').slice(0, 10) }}</span>
            <button v-if="item.sessionId" class="ghost btn-ic" title="查看所属报告" @click="goSession(item)">
              报告
            </button>
            <button class="ghost btn-ic" title="删除" @click="remove(item)">
              <span class="ic"><Trash2 :size="13" /></span>
            </button>
          </header>

          <div class="wi-question">{{ item.question }}</div>

          <div class="wi-actions">
            <button class="ghost btn-ic" @click="toggleReveal(item.id)">
              <span class="ic"><component :is="revealed.has(item.id) ? EyeOff : Eye" :size="13" /></span>
              {{ revealed.has(item.id) ? '隐藏答案' : '显示答案' }}
            </button>
            <button
              v-for="(v, k) in MASTERED"
              :key="k"
              class="chip-btn filter-chip"
              :class="{ on: item.mastered === k }"
              @click="setMastered(item, k)"
            >{{ v[0] }}</button>
          </div>

          <template v-if="revealed.has(item.id)">
            <div class="kv wi-block">
              <span class="ic"><StickyNote :size="13" /></span>
              <div><b>我的回答：</b>{{ item.answer || '（未作答）' }}</div>
            </div>
            <div v-if="(item.missedPoints || []).length" class="kv wi-block">
              <span class="ic"><ListChecks :size="13" /></span>
              <div>
                <b>遗漏知识点：</b>
                <span v-for="(m, i) in item.missedPoints" :key="i" class="eval-tag">{{ m }}</span>
              </div>
            </div>
            <div v-if="(item.issues || []).length" class="kv wi-block">
              <div v-for="(x, i) in item.issues" :key="i" class="wi-issue">{{ x }}</div>
            </div>
            <div v-if="(item.referencePoints || []).length" class="kv wi-block">
              <div><b>参考要点：</b>
                <ul class="tight">
                  <li v-for="(p, i) in item.referencePoints" :key="i">{{ p }}</li>
                </ul>
              </div>
            </div>
          </template>

          <div class="wi-note">
            <textarea
              class="wi-note-input"
              rows="2"
              placeholder="记一条自己的理解或待办（可选）"
              :value="noteDraft(item)"
              @input="onNoteInput(item, $event.target.value)"
              @blur="saveNote(item)"
            ></textarea>
          </div>
        </article>

        <div v-if="hasMore" class="load-more">
          <button class="ghost btn-ic" :disabled="loading" @click="loadMore">
            <span class="ic"><ChevronDown :size="13" /></span>{{ loading ? '加载中…' : '加载更多' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.wrong-list { display: flex; flex-direction: column; gap: 10px; }
.wrong-item { border: 1px solid var(--line); border-radius: var(--radius-ctl); padding: 12px; }
.wi-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.wi-date { margin-left: auto; }
.wi-question { font-size: 13px; color: var(--text-1); margin-bottom: 8px; word-break: break-word; }
.wi-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.wi-block { font-size: 12.5px; color: var(--text-2); margin-bottom: 6px; align-items: flex-start; }
.wi-block .ic { margin-top: 2px; flex: none; color: var(--accent); }
.wi-issue { font-size: 12.5px; color: var(--text-2); margin-bottom: 4px; }
.wi-note-input {
  width: 100%; resize: vertical; font: inherit; font-size: 12.5px;
  padding: 6px 9px; border-radius: var(--radius-sm);
  border: 1px solid var(--line); background: var(--surface); color: var(--text-1);
}
.load-more { display: flex; justify-content: center; padding-top: 6px; }
</style>
