import { ref, reactive } from 'vue';
import { defineStore } from 'pinia';
import { req, postJson, getToken } from '../api/client';
import { useUiStore } from './ui';

// 管理页（R-12）：知识库 / 题库 / 索引健康 / 检索与 Skills / AI 用量
export const useManageStore = defineStore('manage', () => {
  const ui = useUiStore();

  /* ---------------- 知识库 ---------------- */
  const knowledge = ref([]);
  const knowledgeLoading = ref(false);
  const uploadProgress = ref({ total: 0, done: 0, current: '' });

  async function loadKnowledge() {
    knowledgeLoading.value = true;
    try {
      knowledge.value = await req('/knowledge');
    } finally {
      knowledgeLoading.value = false;
    }
  }

  /** 单文件上传（保留旧签名，供 bootstrap 之外的老调用点使用） */
  async function uploadKnowledge(file, tags) {
    await uploadKnowledgeFiles([file], tags);
  }

  /** 批量上传：逐个提交并回报进度，任一失败不中断其余文件 */
  async function uploadKnowledgeFiles(files, tags = [], onProgress) {
    const list = [...(files || [])].filter(Boolean);
    if (!list.length) return ui.toast('请选择文档', true);
    uploadProgress.value = { total: list.length, done: 0, current: '' };
    let okCount = 0;
    for (const f of list) {
      uploadProgress.value.current = f.name;
      const fd = new FormData();
      fd.append('file', f);
      (tags || []).forEach((t) => fd.append('tags', String(t).trim()));
      try {
        await req('/knowledge?docType=article', { method: 'POST', body: fd, timeout: 300000 });
        okCount += 1;
      } catch (e) {
        ui.toast(`${f.name} 上传失败：${e.message}`, true);
      }
      uploadProgress.value.done += 1;
      if (onProgress) onProgress(uploadProgress.value);
    }
    await loadKnowledge();
    await loadHealth();
    ui.toast(`已完成 ${okCount}/${list.length} 篇上传与索引`);
    return okCount;
  }

  async function knowledgeDetail(id) {
    return req(`/knowledge/${id}`);
  }

  async function reindexKnowledge(id) {
    const doc = await postJson(`/knowledge/${id}/reindex`, {});
    await loadKnowledge();
    ui.toast(`已重建索引：${doc.chunkCount || 0} 块`);
    return doc;
  }

  async function deleteKnowledge(id) {
    await req(`/knowledge/${id}`, { method: 'DELETE' });
    await loadKnowledge();
    await loadHealth();
    ui.toast('文档已删除（向量同步清理）');
  }

  /** 原文下载：带 Authorization 头取二进制，再触发浏览器保存 */
  async function downloadKnowledge(id) {
    const resp = await fetch(`/api/knowledge/${id}/file`, {
      headers: getToken() ? { Authorization: 'Bearer ' + getToken() } : {},
    });
    if (!resp.ok) throw new Error(`下载失败（HTTP ${resp.status}）`);
    const blob = await resp.blob();
    const cd = resp.headers.get('Content-Disposition') || '';
    let name = `knowledge-${id}`;
    const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
    if (m && m[1]) name = decodeURIComponent(m[1]);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
  }

  /* ---------------- 题库 ---------------- */
  const questions = ref([]);
  const questionPage = reactive({ pageNo: 1, pageSize: 10, total: 0 });
  const questionFilter = reactive({ skill: '', difficulty: '', qType: '', kw: '' });

  async function loadQuestions(overrides = {}) {
    Object.assign(questionPage, overrides);
    const p = new URLSearchParams();
    p.set('pageNo', String(questionPage.pageNo));
    p.set('pageSize', String(questionPage.pageSize));
    if (questionFilter.skill) p.set('skill', questionFilter.skill);
    if (questionFilter.difficulty) p.set('difficulty', String(questionFilter.difficulty));
    if (questionFilter.qType) p.set('qType', questionFilter.qType);
    if (questionFilter.kw) p.set('kw', questionFilter.kw);
    const page = await req(`/questions?${p.toString()}`);
    questions.value = page.records || [];
    questionPage.total = page.total || 0;
  }

  function resetQuestionFilter() {
    questionFilter.skill = '';
    questionFilter.difficulty = '';
    questionFilter.qType = '';
    questionFilter.kw = '';
    return loadQuestions({ pageNo: 1 });
  }

  async function createQuestion(q) {
    const r = await postJson('/questions', q);
    await loadQuestions();
    await loadHealth();
    ui.toast('题目已新增');
    return r;
  }

  async function updateQuestion(id, q) {
    const r = await req(`/questions/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(q),
    });
    await loadQuestions();
    ui.toast('题目已保存');
    return r;
  }

  async function deleteQuestion(id) {
    await req(`/questions/${id}`, { method: 'DELETE' });
    await loadQuestions();
    await loadHealth();
    ui.toast('题目已删除');
  }

  async function reindexQuestions() {
    const r = await postJson('/questions/reindex', {});
    await loadHealth();
    ui.toast(`已重建 ${r.indexed ?? 0} 道题的向量索引（依赖 Embedding 配置）`);
    return r;
  }

  /** JSON 导入：仅内容去重，返回 {total, imported, skipped, errors} */
  async function importQuestions(list) {
    const r = await postJson('/questions/import', list, { timeout: 300000 });
    await loadQuestions({ pageNo: 1 });
    await loadHealth();
    return r;
  }

  /** CSV 导出（前端生成）：当前筛选下已加载页，含表头，Excel 可直接打开 */
  function exportQuestionsCsv() {
    const head = ['id', 'content', 'skill_name', 'difficulty', 'q_type', 'tags', 'status'];
    const esc = (v) => `"${String(v == null ? '' : v).replace(/"/g, '""')}"`;
    const lines = [head.join(',')];
    questions.value.forEach((q) => {
      const tags = Array.isArray(q.tags) ? q.tags.join('|') : (q.tags || '');
      lines.push([q.id, q.content, q.skillName || q.skill_name, q.difficulty, q.qType || q.q_type, tags, q.status]
        .map(esc).join(','));
    });
    const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `questions-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    ui.toast(`已导出 ${questions.value.length} 条（当前页）`);
  }

  /* ---------------- 索引健康 ---------------- */
  const health = ref(null);
  const healthLoading = ref(false);

  async function loadHealth() {
    healthLoading.value = true;
    try {
      health.value = await req('/admin/index-health');
    } catch {
      health.value = null;
    } finally {
      healthLoading.value = false;
    }
  }

  /** 重建全部索引：逐篇文档重建 + 题库全量重建 */
  const rebuildProgress = reactive({ running: false, total: 0, done: 0, failed: [] });

  async function rebuildAll() {
    if (rebuildProgress.running) return ui.toast('重建任务正在进行');
    rebuildProgress.running = true;
    rebuildProgress.total = knowledge.value.length;
    rebuildProgress.done = 0;
    rebuildProgress.failed = [];
    try {
      for (const doc of knowledge.value) {
        try {
          await postJson(`/knowledge/${doc.id}/reindex`, {});
        } catch (e) {
          rebuildProgress.failed.push({ title: doc.title, reason: e.message });
        }
        rebuildProgress.done += 1;
      }
      try {
        await reindexQuestions();
      } catch (e) {
        rebuildProgress.failed.push({ title: '题库向量', reason: e.message });
      }
      await loadKnowledge();
      await loadHealth();
      ui.toast(`重建完成：${rebuildProgress.done} 篇文档${rebuildProgress.failed.length ? `，${rebuildProgress.failed.length} 项失败` : ''}`);
    } finally {
      rebuildProgress.running = false;
    }
  }

  /* ---------------- Skills ---------------- */
  const skills = ref([]);
  const skillsLoading = ref(false);

  async function loadSkills() {
    skillsLoading.value = true;
    try {
      skills.value = await req('/admin/skills');
    } catch {
      skills.value = [];
    } finally {
      skillsLoading.value = false;
    }
  }

  async function reloadSkills() {
    const r = await postJson('/admin/skills/reload', {});
    await loadSkills();
    await loadGauntletTags();
    ui.toast(`Skills 已重载（${(r && r.reloaded ? r.reloaded.length : skills.value.length)} 个）`);
    return r;
  }

  /** 八股标签覆盖状态（R-11/R-12）：15 个标签与 Skill 文件比对 */
  const gauntletTags = ref([]);

  async function loadGauntletTags() {
    try {
      gauntletTags.value = await req('/gauntlet/tags');
    } catch {
      gauntletTags.value = [];
    }
  }

  /* ---------------- 检索预览 ---------------- */
  const searchState = reactive({ loading: false, query: '', skill: '', topK: 5, results: [], error: '' });

  async function searchKnowledge() {
    if (!searchState.query.trim()) return ui.toast('请先输入检索内容', true);
    searchState.loading = true;
    searchState.error = '';
    try {
      const r = await postJson('/knowledge/search', {
        query: searchState.query.trim(),
        skill: searchState.skill || undefined,
        topK: Number(searchState.topK) || 5,
      });
      searchState.results = Array.isArray(r) ? r : [];
    } catch (e) {
      searchState.results = [];
      searchState.error = e.message;
    } finally {
      searchState.loading = false;
    }
  }

  /* ---------------- AI 用量（R-17） ---------------- */
  const aiUsage = ref(null);
  const aiUsageLoading = ref(false);

  async function loadAiUsage(days = 14) {
    aiUsageLoading.value = true;
    try {
      aiUsage.value = await req(`/admin/ai-usage?days=${days}`);
    } catch {
      aiUsage.value = null;
    } finally {
      aiUsageLoading.value = false;
    }
  }

  return {
    knowledge, knowledgeLoading, uploadProgress,
    loadKnowledge, uploadKnowledge, uploadKnowledgeFiles, knowledgeDetail,
    reindexKnowledge, deleteKnowledge, downloadKnowledge,
    questions, questionPage, questionFilter,
    loadQuestions, resetQuestionFilter, createQuestion, updateQuestion, deleteQuestion,
    reindexQuestions, importQuestions, exportQuestionsCsv,
    health, healthLoading, rebuildProgress, loadHealth, rebuildAll,
    skills, skillsLoading, loadSkills, reloadSkills, gauntletTags, loadGauntletTags,
    searchState, searchKnowledge,
    aiUsage, aiUsageLoading, loadAiUsage,
    reindex: reindexQuestions,
  };
});
