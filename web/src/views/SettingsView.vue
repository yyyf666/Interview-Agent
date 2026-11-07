<script setup>
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, Settings, KeyRound, AudioLines, ToggleLeft, Eye, EyeOff, Save, PlugZap, Loader2, CheckCircle2, XCircle } from 'lucide-vue-next';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';

// AI 配置页：全局一份配置（任何登录用户可改），保存后 Python 层热生效免重启。
// key 字段后端出参为掩码（sk-****后4位），原样提交视为未修改；清空 = 回落环境变量默认。
const router = useRouter();
const session = useSessionStore();
const ui = useUiStore();

const form = reactive({
  llm_api_base: '', llm_api_key: '', llm_model: '',
  // R-17：预算告警阈值 + 备用模型（主模型失败自动重试一次）
  llm_budget_warn: 6, llm_fallback_api_base: '', llm_fallback_api_key: '', llm_fallback_model: '',
  embedding_api_base: '', embedding_api_key: '', embedding_model: '', embedding_dim: 1024,
  asr_api_base: '', asr_api_key: '', asr_model: '', asr_stream_model: '',
  rerank_enabled: true, mcp_enabled: false, intent_rules_enabled: true,
});
const loading = ref(true);
const saving = ref(false);
// 三类连接测试各自独立结果（R-17）：{ok, kind, model, latency_ms, error, detail}
const testResults = reactive({ llm: null, embedding: null, asr: null });
const testingKind = ref('');
const reveal = reactive({
  llm_api_key: false, llm_fallback_api_key: false, embedding_api_key: false, asr_api_key: false,
});

async function applyConfig(data) {
  Object.keys(form).forEach((k) => { if (data[k] !== undefined) form[k] = data[k]; });
}

onMounted(async () => {
  try {
    await applyConfig(await session.fetchAiConfig());
  } catch (e) {
    ui.toast('AI配置加载失败：' + (e.message || e), true);
  } finally {
    loading.value = false;
  }
});

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    await ui.guard(async () => {
      await session.saveAiConfig({ ...form });
      // 保存后重拉回显：key 字段回落为最新掩码，避免明文残留在输入框
      await applyConfig(await session.fetchAiConfig());
    });
  } finally {
    saving.value = false;
  }
}

// 按类别测试连通性（R-17）：草稿配置直接测，不需先保存
const TEST_PAYLOAD = {
  llm: () => ({
    kind: 'llm', llm_api_base: form.llm_api_base,
    llm_api_key: form.llm_api_key, llm_model: form.llm_model,
  }),
  embedding: () => ({
    kind: 'embedding', embedding_api_base: form.embedding_api_base,
    embedding_api_key: form.embedding_api_key, embedding_model: form.embedding_model,
    embedding_dim: form.embedding_dim,
  }),
  asr: () => ({
    kind: 'asr', asr_api_base: form.asr_api_base,
    asr_api_key: form.asr_api_key, asr_model: form.asr_model,
  }),
};

async function test(kind) {
  if (testingKind.value) return;
  testingKind.value = kind;
  testResults[kind] = null;
  try {
    await ui.guard(async () => {
      testResults[kind] = await session.testAiConfig(TEST_PAYLOAD[kind]());
    }, 'ai-test');
  } finally {
    testingKind.value = '';
  }
}

/** 测试结果文案：成功给模型/耗时/维度，失败截断错误信息 */
function testChip(kind) {
  const r = testResults[kind];
  if (!r) return '';
  if (!r.ok) return '失败：' + (r.error || '').slice(0, 60);
  const parts = [r.model || '', r.latency_ms ? r.latency_ms + 'ms' : '', r.detail || ''];
  return parts.filter(Boolean).join(' · ') || '连接正常';
}

const toggles = [
  { key: 'rerank_enabled', label: 'RAG 重排（rerank）', hint: '本地 CrossEncoder 对召回结果二次排序' },
  { key: 'mcp_enabled', label: 'MCP 工具接入', hint: '预留能力开关；未接入外部工具时请保持关闭' },
  { key: 'intent_rules_enabled', label: '意图规则前置短路', hint: '高置信指令不走 LLM，降低调用成本' },
];
</script>

<template>
  <section class="view active">
    <div class="view-head">
      <h2><span class="ic"><Settings :size="18" /></span> AI 配置</h2>
      <button class="ghost btn-ic" @click="router.push({ name: 'setup' })">
        <span class="ic"><ArrowLeft :size="15" /></span>返回准备
      </button>
    </div>
    <p class="pd-hint">全局配置对所有用户生效；API Key 仅作掩码回显，不改动原样保存即沿用旧值，清空则回落容器环境变量默认。保存后即时热生效，无需重启服务。</p>

    <div v-if="loading" class="card"><p class="pd-hint">正在读取当前配置…</p></div>

    <template v-else>
      <div class="settings-grid">
        <div class="card">
          <h3>
            <span class="ic"><KeyRound :size="16" /></span>大模型 LLM
            <button class="ghost btn-ic card-test" :disabled="!!testingKind" @click="test('llm')">
              <span class="ic"><Loader2 v-if="testingKind === 'llm'" :size="13" class="spin" /><PlugZap v-else :size="13" /></span>测试
            </button>
          </h3>
          <span v-if="testResults.llm" class="chip card-chip" :class="testResults.llm.ok ? 'test-ok' : 'test-fail'" :title="testResults.llm.error || ''">
            <span class="ic"><CheckCircle2 v-if="testResults.llm.ok" :size="13" /><XCircle v-else :size="13" /></span>{{ testChip('llm') }}
          </span>
          <label class="pd-label">API Base</label>
          <input v-model="form.llm_api_base" placeholder="https://api.deepseek.com/v1" />
          <label class="pd-label">API Key</label>
          <div class="key-row">
            <input v-model="form.llm_api_key" :type="reveal.llm_api_key ? 'text' : 'password'" placeholder="sk-..." />
            <button class="reveal-btn" :title="reveal.llm_api_key ? '隐藏' : '显示'" @click="reveal.llm_api_key = !reveal.llm_api_key">
              <span class="ic"><EyeOff v-if="reveal.llm_api_key" :size="14" /><Eye v-else :size="14" /></span>
            </button>
          </div>
          <label class="pd-label">模型名称</label>
          <input v-model="form.llm_model" placeholder="deepseek-chat" />
          <label class="pd-label">预算告警阈值（单请求 LLM 调用数）</label>
          <input v-model.number="form.llm_budget_warn" type="number" min="1" step="1" />
          <details class="adv-block">
            <summary>备用模型（主模型失败自动重试一次）</summary>
            <label class="pd-label">API Base（留空不启用）</label>
            <input v-model="form.llm_fallback_api_base" placeholder="https://backup.example.com/v1" />
            <label class="pd-label">API Key</label>
            <div class="key-row">
              <input v-model="form.llm_fallback_api_key" :type="reveal.llm_fallback_api_key ? 'text' : 'password'" placeholder="sk-..." />
              <button class="reveal-btn" :title="reveal.llm_fallback_api_key ? '隐藏' : '显示'" @click="reveal.llm_fallback_api_key = !reveal.llm_fallback_api_key">
                <span class="ic"><EyeOff v-if="reveal.llm_fallback_api_key" :size="14" /><Eye v-else :size="14" /></span>
              </button>
            </div>
            <label class="pd-label">模型名称</label>
            <input v-model="form.llm_fallback_model" placeholder="deepseek-chat" />
            <p class="pd-hint">Base 与模型名称同时填写才生效；主模型整条降级链均失败时自动切备用模型重试。</p>
          </details>
        </div>

        <div class="card">
          <h3>
            <span class="ic"><Settings :size="16" /></span>Embedding 向量
            <button class="ghost btn-ic card-test" :disabled="!!testingKind" @click="test('embedding')">
              <span class="ic"><Loader2 v-if="testingKind === 'embedding'" :size="13" class="spin" /><PlugZap v-else :size="13" /></span>测试
            </button>
          </h3>
          <span v-if="testResults.embedding" class="chip card-chip" :class="testResults.embedding.ok ? 'test-ok' : 'test-fail'" :title="testResults.embedding.error || ''">
            <span class="ic"><CheckCircle2 v-if="testResults.embedding.ok" :size="13" /><XCircle v-else :size="13" /></span>{{ testChip('embedding') }}
          </span>
          <label class="pd-label">API Base（留空回落 LLM）</label>
          <input v-model="form.embedding_api_base" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
          <label class="pd-label">API Key</label>
          <div class="key-row">
            <input v-model="form.embedding_api_key" :type="reveal.embedding_api_key ? 'text' : 'password'" placeholder="留空回落 LLM key" />
            <button class="reveal-btn" :title="reveal.embedding_api_key ? '隐藏' : '显示'" @click="reveal.embedding_api_key = !reveal.embedding_api_key">
              <span class="ic"><EyeOff v-if="reveal.embedding_api_key" :size="14" /><Eye v-else :size="14" /></span>
            </button>
          </div>
          <label class="pd-label">模型名称</label>
          <input v-model="form.embedding_model" placeholder="text-embedding-v3" />
          <label class="pd-label">向量维度</label>
          <input v-model.number="form.embedding_dim" type="number" min="1" step="1" />
          <p class="pd-hint">修改模型/维度后已入库向量不会自动重算，请到「知识库/题库」页重建索引。</p>
        </div>

        <div class="card">
          <h3>
            <span class="ic"><AudioLines :size="16" /></span>ASR 语音
            <button class="ghost btn-ic card-test" :disabled="!!testingKind" @click="test('asr')">
              <span class="ic"><Loader2 v-if="testingKind === 'asr'" :size="13" class="spin" /><PlugZap v-else :size="13" /></span>测试
            </button>
          </h3>
          <span v-if="testResults.asr" class="chip card-chip" :class="testResults.asr.ok ? 'test-ok' : 'test-fail'" :title="testResults.asr.error || ''">
            <span class="ic"><CheckCircle2 v-if="testResults.asr.ok" :size="13" /><XCircle v-else :size="13" /></span>{{ testChip('asr') }}
          </span>
          <label class="pd-label">API Base（留空回落 LLM）</label>
          <input v-model="form.asr_api_base" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
          <label class="pd-label">API Key</label>
          <div class="key-row">
            <input v-model="form.asr_api_key" :type="reveal.asr_api_key ? 'text' : 'password'" placeholder="留空回落 LLM key" />
            <button class="reveal-btn" :title="reveal.asr_api_key ? '隐藏' : '显示'" @click="reveal.asr_api_key = !reveal.asr_api_key">
              <span class="ic"><EyeOff v-if="reveal.asr_api_key" :size="14" /><Eye v-else :size="14" /></span>
            </button>
          </div>
          <label class="pd-label">转写模型</label>
          <input v-model="form.asr_model" placeholder="whisper-1" />
          <label class="pd-label">实时流式模型（WebSocket）</label>
          <input v-model="form.asr_stream_model" placeholder="paraformer-realtime-v2" />
        </div>

        <div class="card">
          <h3><span class="ic"><ToggleLeft :size="16" /></span>能力开关</h3>
          <label v-for="t in toggles" :key="t.key" class="switch-row">
            <input type="checkbox" v-model="form[t.key]" />
            <span>
              <b>{{ t.label }}</b>
              <em class="pd-hint">{{ t.hint }}</em>
            </span>
          </label>
        </div>
      </div>

      <div class="card settings-actions">
        <button class="primary" :disabled="saving" @click="save()">
          <span class="ic"><Save :size="15" /></span>{{ saving ? '保存中…' : '保存配置' }}
        </button>
        <span class="pd-hint">每张卡片右上角的「测试」用当前表单值直接测，不需先保存。</span>
      </div>
    </template>
  </section>
</template>
