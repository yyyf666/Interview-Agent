<script setup>
import { watch, computed } from 'vue';
import { Tag, Gauge, Hash, Flag, Mic, Swords, RefreshCw, SlidersHorizontal } from 'lucide-vue-next';
import ChatFlow from '../components/ChatFlow.vue';
import CodePanel from '../components/CodePanel.vue';
import InputBar from '../components/InputBar.vue';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';

const session = useSessionStore();
const ui = useUiStore();

// 顶栏「同步状态」：请求失败统一走 guard 的 toast，避免未捕获的 Promise 拒绝
function syncNow() {
  ui.guard(() => session.syncState(), 'sync');
}

// 修复：store 里 status.difficulty 形如 "3 / 5"，旧实现 Number() 得到 NaN，
// 这里用 parseInt 取真实难度数字驱动 5 段仪表（纯展示）
const diffNum = computed(() => {
  const n = parseInt(session.status.difficulty, 10);
  return Number.isFinite(n) ? n : 0;
});

// 对应旧版 syncRealtimeUI()：切出 REALTIME 模式即停止实时语音
watch(() => session.mode, (m) => {
  if (m !== 'REALTIME') session.rt.stop();
});

// 本场配置 chip（R-08）：预算 10 题 · 含代码 · 中文 · 追问标准
const CODE_MODE_LABEL = { include: '含代码', exclude: '不含代码', auto: '代码题自适应' };
const FOLLOWUP_LABEL = { low: '少追问', standard: '追问标准', high: '强追问' };
const LANG_LABEL = { zh: '中文', en: '英文', mixed: '中英混合' };

const metaText = computed(() => {
  const m = session.planMeta || {};
  const parts = [];
  if (m.question_budget) parts.push(`预算 ${m.question_budget} 题`);
  if (m.duration_min) parts.push(`${m.duration_min} 分钟`);
  if (m.code_mode) parts.push(CODE_MODE_LABEL[m.code_mode] || '');
  if (m.interview_language) parts.push(LANG_LABEL[m.interview_language] || '');
  if (m.followup_level) parts.push(FOLLOWUP_LABEL[m.followup_level] || '');
  if (m.study_mode) parts.push('学习模式');
  return parts.filter(Boolean).join(' · ');
});
</script>

<template>
  <section class="view active">
    <div class="chips" style="margin-bottom:14px">
      <span v-if="session.scene === 'gauntlet'" class="metric-chip">
        <span class="ic"><Swords :size="14" /></span>八股拷打
      </span>
      <span class="metric-chip">
        <span class="ic"><Tag :size="14" /></span>主题 <b>{{ session.status.topic }}</b>
      </span>
      <span class="metric-chip">
        <span class="ic"><Gauge :size="14" /></span>难度 <b>{{ diffNum || '-' }}</b>
        <span class="gauge-seg" title="1–5 级难度">
          <i v-for="d in 5" :key="d" :class="{ on: d <= diffNum }"></i>
        </span>
      </span>
      <span class="metric-chip">
        <span class="ic"><Hash :size="14" /></span>轮次 <b>{{ session.turnNo }}</b>
      </span>
      <span v-if="metaText" class="metric-chip" :title="'本场面试配置：' + metaText">
        <span class="ic"><SlidersHorizontal :size="14" /></span>{{ metaText }}
      </span>
      <span v-show="session.recording" class="rec metric-chip">
        <span class="ic"><Mic :size="14" /></span>录音中
      </span>
      <button class="ghost btn-ic" style="margin-left:auto" title="从服务端拉取最新轮次与评测（等待过久或断网后使用）" @click="syncNow">
        <span class="ic"><RefreshCw :size="14" /></span>同步状态
      </button>
      <button class="ghost btn-ic" @click="session.finishInterview()">
        <span class="ic"><Flag :size="14" /></span>
        {{ session.scene === 'gauntlet' ? '结束拷打并复盘' : '结束并生成报告' }}
      </button>
    </div>
    <ChatFlow />
    <CodePanel />
    <InputBar />
  </section>
</template>
