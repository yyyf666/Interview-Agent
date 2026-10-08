<script setup>
import { ref, computed } from 'vue';
import {
  Play, Check, Users, Type, Mic, Radio, RefreshCw, ChartNoAxesColumn, AlertTriangle, Target,
} from 'lucide-vue-next';
import { useSessionStore } from '../stores/session';
import { PERSONAS } from '../constants/personas';
import InterviewOptions from './InterviewOptions.vue';

const session = useSessionStore();

// 默认仅勾选「标准专业」，与旧版 checkbox 初始态一致
const selected = ref(['standard']);

function toggle(v) {
  const i = selected.value.indexOf(v);
  if (i >= 0) selected.value.splice(i, 1);
  else selected.value.push(v);
}

const MODES = [
  ['TEXT', '文字回答', Type],
  ['AUDIO', '语音（非实时）', Mic],
  ['REALTIME', '语音（实时流式）', Radio],
];

// 开前缺口分析（R-03）：简历与 JD 都选中后才可分析
const canAnalyze = computed(() => !!session.resumeId && !!session.jdId);
const gap = computed(() => session.gap);
const gapScore = computed(() => (gap.value ? Math.max(0, Math.min(100, Math.round(gap.value.match_score || 0))) : 0));

// 底注文案随高级配置变化（R-08），避免与实际计划不符
const budgetTip = computed(() => {
  const b = session.interviewOptions.question_budget;
  return b ? `本场约 ${b} 题` : '题量由系统自适应（标准场次约 8-14 题）';
});
const codeTip = computed(() => {
  const m = session.interviewOptions.code_mode;
  if (m === 'exclude') return '本场不含代码题';
  if (m === 'include') return '本场必含 1 道代码题';
  return '技术岗会包含手写代码题';
});
</script>

<template>
  <div class="card">
    <h3><span class="ic"><Users :size="16" /></span>开始面试</h3>
    <label>面试官风格（可多选组成考官团 Panel，按轮次轮流发言）</label>
    <div class="persona-chips">
      <div
        v-for="[v, l, d] in PERSONAS"
        :key="v"
        class="pchip"
        :class="{ on: selected.includes(v) }"
        @click="toggle(v)"
      >
        <span class="pa" :class="'pa-' + v">{{ l.slice(0, 1) }}</span>
        <div><div class="pchip-t">{{ l }}</div><div class="pchip-d">{{ d }}</div></div>
      </div>
    </div>
    <label>输入方式</label>
    <div class="seg">
      <button
        v-for="[v, l, ic] in MODES"
        :key="v"
        :class="{ on: session.mode === v }"
        @click="session.mode = v"
      ><span class="ic"><component :is="ic" :size="14" /></span>{{ l }}</button>
    </div>

    <!-- 面试高级配置（R-08）：预设 + 折叠高级设置，localStorage 记忆 -->
    <InterviewOptions v-model="session.interviewOptions" />

    <!-- 开前缺口分析（R-03）：失败不阻塞开始面试 -->
    <div v-if="canAnalyze" class="gap-head">
      <button
        class="ghost btn-ic"
        :disabled="session.gapLoading"
        @click="session.analyzeGap(!!gap)"
      >
        <span class="ic"><component :is="gap ? RefreshCw : ChartNoAxesColumn" :size="14" /></span>
        {{ session.gapLoading ? '正在分析岗位匹配度…' : (gap ? '重新分析岗位匹配度' : '分析岗位匹配度') }}
      </button>
      <span v-if="session.gapLoading" class="mini">首次分析通常需要 3-10 秒</span>
    </div>
    <p v-if="session.gapError" class="gap-err">
      <AlertTriangle :size="13" />本次跳过缺口预览，可直接开始面试（{{ session.gapError }}）
    </p>

    <div v-if="gap" class="gap-card">
      <div class="gap-score-row">
        <span class="gap-score">{{ gapScore }}<small> / 100 匹配度</small></span>
        <i class="gap-bar"><b :style="{ width: gapScore + '%' }"></b></i>
      </div>
      <div class="gap-cols">
        <div class="gap-col ok">
          <span class="gap-lbl"><Check :size="12" />优势</span>
          <ul>
            <li v-for="(x, i) in (gap.strengths || []).slice(0, 4)" :key="i">{{ x }}</li>
            <li v-if="!(gap.strengths || []).length" class="mini">未识别到明显优势</li>
          </ul>
        </div>
        <div class="gap-col warn">
          <span class="gap-lbl"><AlertTriangle :size="12" />薄弱 / 缺口</span>
          <ul>
            <li v-for="(x, i) in (gap.weaknesses || []).slice(0, 4)" :key="i">{{ x }}</li>
            <li v-if="!(gap.weaknesses || []).length" class="mini">未识别到明显缺口</li>
          </ul>
        </div>
        <div class="gap-col info">
          <span class="gap-lbl"><Target :size="12" />重点考察</span>
          <ul>
            <li v-for="(x, i) in (gap.key_inspection_points || []).slice(0, 4)" :key="i">{{ x }}</li>
            <li v-if="!(gap.key_inspection_points || []).length" class="mini">暂无</li>
          </ul>
        </div>
      </div>
      <p class="mini gap-tip">开始面试将按此分析生成计划，{{ budgetTip }}。</p>
    </div>

    <div class="summary-bar">
      <span class="sum-ok" :class="{ done: session.resumeId }">
        <span class="ic"><Check :size="14" /></span>简历
      </span>
      <span class="sum-ok" :class="{ done: session.jdId }">
        <span class="ic"><Check :size="14" /></span>JD
      </span>
      <span class="sum-ok" :class="{ done: selected.length > 0 }">
        <span class="ic"><Users :size="14" /></span>{{ selected.length }} 位面试官
      </span>
      <span class="grow"></span>
      <button
        class="primary btn-ic"
        @click="session.startInterview(selected.slice(), session.gap, session.interviewOptions)"
      >
        <span class="ic"><Play :size="15" /></span>{{ gap ? '按此计划开始面试' : '开始自适应面试' }}
      </button>
    </div>
    <p class="hint">系统将根据简历-JD缺口分析自动规划主题、题量与难度；{{ budgetTip }}，{{ codeTip }}。</p>
  </div>
</template>
