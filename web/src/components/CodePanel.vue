<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';
import {
  Code2, ListChecks, Play, CheckCircle2, AlertTriangle, ListChecks as RunAll,
  RotateCcw, ClipboardCopy, Maximize2, Minimize2, FileInput,
} from 'lucide-vue-next';
import CodeMirror from 'codemirror';
import 'codemirror/lib/codemirror.css';
import 'codemirror/mode/clike/clike';
import 'codemirror/mode/python/python';
import 'codemirror/mode/javascript/javascript';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';

// 代码题面板（R-13）：本地 CodeMirror 5（离线可用），textarea 降级保留
const session = useSessionStore();
const ui = useUiStore();

const CM_MODES = { java: 'text/x-java', python: 'text/x-python', javascript: 'text/javascript' };
const LANGS = [
  { v: 'java', label: 'Java' },
  { v: 'python', label: 'Python' },
  { v: 'javascript', label: 'JavaScript' },
];

const editorHost = ref(null);
const fallbackText = ref('');
const useFallback = ref(false);
const tcIndex = ref(-1);
let cm = null;

function editorCode() {
  return cm ? cm.getValue() : fallbackText.value;
}

function onTcChange() {
  const i = Number(tcIndex.value);
  session.code.stdin = i >= 0 ? (session.code.testCases[i].input || '') : '';
  session.code.output.runStatus = i >= 0 ? `期望输出：${session.code.testCases[i].expected || '-'}` : '';
}

/** 用当前题目的模板重置编辑器（进入代码题 / 切语言 / 重置模板） */
function applyEditorValue(value) {
  fallbackText.value = value || '';
  if (cm) cm.setValue(value || '');
}

function mountEditor() {
  if (!editorHost.value || window.__IA_NO_CM__) {
    useFallback.value = true;
    return;
  }
  try {
    cm = CodeMirror(editorHost.value, {
      value: session.code.starterCode || '',
      mode: CM_MODES[session.code.language] || 'text/plain',
      lineNumbers: true,
      indentUnit: 4,
      lineWrapping: true,
    });
    cm.on('change', () => session.saveCodeDraft()); // 输入即草稿（localStorage）
    useFallback.value = false;
  } catch {
    useFallback.value = true;
  }
}

// 进入代码题：active 由文本题切过来 / 代码题连出（version 自增）
watch(() => [session.code.active, session.code.version], async ([active]) => {
  if (!active) return;
  tcIndex.value = -1;
  await nextTick();
  if (!cm) mountEditor();
  if (cm) {
    cm.setOption('mode', CM_MODES[session.code.language] || 'text/plain');
    applyEditorValue(session.code.starterCode);
    setTimeout(() => cm && cm.refresh(), 0);
  } else {
    applyEditorValue(session.code.starterCode);
  }
}, { immediate: true });

function changeLanguage(e) {
  session.setCodeLanguage(e.target.value);
}

function insertSampleInput() {
  const i = Number(tcIndex.value);
  const input = i >= 0 ? (session.code.testCases[i].input || '') : '';
  if (!input) return ui.toast('请先选择一个样例', true);
  session.code.stdin = input;
  ui.toast('已填入样例输入');
}

async function copyOutput() {
  const text = session.code.output.text || '';
  if (!text) return ui.toast('暂无输出可复制', true);
  try {
    await navigator.clipboard.writeText(text);
    ui.toast('输出已复制');
  } catch {
    ui.toast('复制失败，请手动选择文本', true);
  }
}

onMounted(() => {
  session.code.readCode = editorCode;
  window.addEventListener('keydown', onEsc);
});
onBeforeUnmount(() => {
  if (session.code.readCode === editorCode) session.code.readCode = null;
  session.saveCodeDraft();
  window.removeEventListener('keydown', onEsc);
  cm = null; // DOM 随组件销毁
});

// Esc 退出全屏（R-19）：输入焦点在 CodeMirror 时同样生效
function onEsc(e) {
  if (e.key === 'Escape' && session.code.fullscreen) session.code.fullscreen = false;
}
</script>

<template>
  <!-- v-show 保留 DOM，CodeMirror 实例跨题目复用 -->
  <div v-show="session.code.active" class="code-panel" :class="{ full: session.code.fullscreen }">
    <div class="code-head">
      <span class="ic"><Code2 :size="15" /></span>
      <span>代码作答</span>
      <select class="lang-select" :value="session.code.language" title="作答语言（切换后同步套用该语言模板）" @change="changeLanguage">
        <option v-for="l in LANGS" :key="l.v" :value="l.v">{{ l.label }}</option>
      </select>
      <span v-if="session.code.testCases.length" class="mini">含 {{ session.code.testCases.length }} 个样例用例</span>
      <span class="spacer"></span>
      <button class="row-act" :aria-label="session.code.fullscreen ? '退出全屏' : '全屏编辑'" :title="session.code.fullscreen ? '退出全屏（Esc）' : '全屏编辑'" @click="session.code.fullscreen = !session.code.fullscreen">
        <component :is="session.code.fullscreen ? Minimize2 : Maximize2" :size="14" />
      </button>
    </div>
    <div class="code-body">
      <div class="code-toolbar">
        <span class="ic tc-ic"><ListChecks :size="15" /></span>
        <select v-model="tcIndex" title="选择样例测试用例作为运行输入" @change="onTcChange">
          <option :value="-1">自定义输入</option>
          <option v-for="(tc, i) in session.code.testCases" :key="i" :value="i">
            样例{{ i + 1 }}：{{ String(tc.input || '').slice(0, 24) }}
          </option>
        </select>
        <input v-model="session.code.stdin" placeholder="stdin 自定义输入（可选）" @blur="session.saveCodeDraft()" />
        <button class="btn-ic" :disabled="!session.inputEnabled" @click="session.runCode()">
          <Play :size="14" /> 运行
        </button>
        <button class="ghost btn-ic" :disabled="!session.inputEnabled" title="顺序运行全部样例并比对期望输出" @click="session.runAllCases()">
          <RunAll :size="14" /> 运行全部样例
        </button>
        <span class="mini">{{ session.code.output.runStatus }}</span>
      </div>
      <div class="code-toolbar">
        <button class="ghost btn-ic" title="重置为当前语言的初始模板" @click="session.resetCode()">
          <RotateCcw :size="13" /> 重置模板
        </button>
        <button class="ghost btn-ic" title="把所选样例的 stdin 填入运行输入" @click="insertSampleInput">
          <FileInput :size="13" /> 填入样例输入
        </button>
        <button class="ghost btn-ic" title="复制运行输出" @click="copyOutput">
          <ClipboardCopy :size="13" /> 复制输出
        </button>
        <span class="mini">草稿自动保存（刷新可恢复）</span>
      </div>
      <div v-show="!useFallback" id="code-editor" ref="editorHost"></div>
      <textarea
        v-show="useFallback"
        id="code-editor-fallback"
        v-model="fallbackText"
        spellcheck="false"
        autocomplete="off"
        placeholder="在此编写代码…（CodeMirror 加载失败时的纯文本模式）"
        @input="session.saveCodeDraft()"
      ></textarea>

      <!-- 全部样例结果表（R-13） -->
      <table v-if="session.code.results.length" class="case-table">
        <thead><tr><th style="width:44px">#</th><th>输入</th><th>期望</th><th>实际</th><th style="width:64px">结果</th></tr></thead>
        <tbody>
          <tr v-for="r in session.code.results" :key="r.index" :class="{ bad: !r.pass }">
            <td>{{ r.index }}</td>
            <td class="mono-cell">{{ r.input || '（空）' }}</td>
            <td class="mono-cell">{{ r.expected }}</td>
            <td class="mono-cell">{{ r.actual || '（无输出）' }}</td>
            <td>
              <span v-if="r.pass" class="case-ok">通过</span>
              <span v-else class="case-bad">{{ r.timed_out ? '超时' : '未通过' }}</span>
            </td>
          </tr>
        </tbody>
      </table>

      <template v-if="session.code.output.text && !session.code.results.length">
        <div class="code-status" :class="session.code.output.fail ? 'err' : 'ok'">
          <component :is="session.code.output.fail ? AlertTriangle : CheckCircle2" :size="14" />
          {{ session.code.output.fail ? '运行异常' : '运行输出' }}
        </div>
        <pre class="code-output" :class="{ 'run-fail': session.code.output.fail }">{{ session.code.output.text }}</pre>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lang-select { width: auto; margin: 0; padding: 3px 6px; font-size: 12px; }
.code-toolbar { flex-wrap: wrap; }
.case-table { width: 100%; margin-top: 10px; border-collapse: collapse; font-size: 12px; }
.case-table th, .case-table td { padding: 6px 8px; border-bottom: 1px solid var(--line); text-align: left; color: var(--text-2); vertical-align: top; }
.case-table th { font-size: 11px; color: var(--text-3); background: var(--surface); }
.case-table tr.bad td { color: #b91c1c; }
.mono-cell { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; word-break: break-all; max-width: 220px; }
.case-ok { color: #047857; font-weight: 600; }
.case-bad { color: #b91c1c; font-weight: 600; }
</style>
