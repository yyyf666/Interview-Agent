<script setup>
import { computed } from 'vue';
import { AlertTriangle, Briefcase, FileText, Target } from 'lucide-vue-next';

// 解析结果预览（R-05）：直接吃列表接口已返回的 parsed，不额外请求
const props = defineProps({
  kind: { type: String, default: 'resume' }, // resume | jd
  parsed: { type: Object, default: null },
  rawText: { type: String, default: '' },
});

const p = computed(() => props.parsed || {});
const skills = computed(() => (props.kind === 'resume'
  ? p.value.skills || []
  : p.value.required_skills || []));
const projects = computed(() => (p.value.projects || []).slice(0, 3));
const focusPoints = computed(() => p.value.focus_points || []);
// 解析明显残缺时提示重新解析（技能为空 / 年限缺失或为 0）
const incomplete = computed(() => props.kind === 'resume'
  && (!skills.value.length || !Number(p.value.years_of_experience)));

// skill_weights 按最大值归一成相对条，保留原始数值展示（不伪造百分比口径）
const weights = computed(() => {
  const entries = Object.entries(p.value.skill_weights || {})
    .filter(([, v]) => Number.isFinite(Number(v)));
  const max = entries.reduce((m, [, v]) => Math.max(m, Number(v)), 0) || 1;
  return entries.map(([k, v]) => ({ key: k, raw: Number(v), pct: Math.round((Number(v) / max) * 100) }));
});
</script>

<template>
  <div class="parsed-preview">
    <!-- 简历 -->
    <template v-if="kind === 'resume'">
      <div class="pp-head">
        <span class="ic"><FileText :size="13" /></span>
        <b>{{ p.name || '未识别姓名' }}</b>
        <span class="pp-meta">工作年限：{{ p.years_of_experience != null ? p.years_of_experience + ' 年' : '未识别' }}</span>
      </div>
      <div v-if="incomplete" class="pp-warn">
        <AlertTriangle :size="12" />解析可能不完整，建议重新解析
      </div>
      <div v-if="skills.length" class="pp-skills">
        <span v-for="(s, i) in skills" :key="i" class="pp-skill" :title="s.evidence || ''">
          {{ s.skill || s }}<em v-if="s.level">L{{ s.level }}</em>
        </span>
      </div>
      <div v-else class="kv">未识别到技能项</div>
      <div v-if="projects.length" class="pp-projects">
        <div v-for="(pr, i) in projects" :key="i" class="pp-proj">
          <b>{{ pr.name || pr.title || '项目' }}</b>
          <small v-if="pr.role || pr.period">{{ [pr.role, pr.period].filter(Boolean).join(' · ') }}</small>
          <div v-if="pr.description" class="mini">{{ pr.description }}</div>
          <div v-if="(pr.highlights || []).length" class="mini">
            {{ (pr.highlights || []).slice(0, 3).join('；') }}
          </div>
        </div>
      </div>
      <div v-if="p.summary" class="pp-summary">{{ p.summary }}</div>
    </template>

    <!-- JD -->
    <template v-else>
      <div class="pp-head">
        <span class="ic"><Briefcase :size="13" /></span>
        <b>{{ p.title || '未命名岗位' }}</b>
        <span v-if="p.company" class="pp-meta">{{ p.company }}</span>
      </div>
      <div v-if="skills.length" class="pp-skills">
        <span v-for="(s, i) in skills" :key="i" class="pp-skill">
          {{ typeof s === 'string' ? s : (s.skill || s.name || '') }}
          <em v-if="s && s.priority">{{ s.priority }}</em>
        </span>
      </div>
      <div v-if="weights.length" class="pp-weights">
        <div v-for="w in weights" :key="w.key" class="pp-weight">
          <span class="pw-name">{{ w.key }}</span>
          <i class="pw-bar"><b :style="{ width: w.pct + '%' }"></b></i>
          <span class="pw-val">{{ w.raw }}</span>
        </div>
      </div>
      <div v-if="focusPoints.length" class="pp-focus">
        <span class="pp-lbl"><Target :size="12" />考察重点</span>
        <span v-for="(f, i) in focusPoints" :key="i" class="pp-focus-item">{{ typeof f === 'string' ? f : (f.point || f.name || '') }}</span>
      </div>
      <details v-if="rawText" class="pp-raw">
        <summary>原始文本</summary>
        <pre>{{ rawText }}</pre>
      </details>
    </template>
  </div>
</template>
