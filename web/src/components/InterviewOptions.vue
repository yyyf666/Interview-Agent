<script setup>
import { ref, watch, computed } from 'vue';
import { SlidersHorizontal, Zap, Gauge, Flame, GraduationCap } from 'lucide-vue-next';

/**
 * 面试高级配置（R-08）：三个预设 + 折叠高级设置。
 * 纯受控组件：值由 store 持有（localStorage 记忆），这里只负责编辑与预设套用。
 */
const props = defineProps({
  modelValue: { type: Object, required: true },
  /** full=完整配置 / gauntlet=八股页（只开放学习模式） */
  variant: { type: String, default: 'full' },
});
const emit = defineEmits(['update:modelValue']);

const PRESETS = [
  { id: 'quick', label: '快速练手', desc: '6 题 · 20 分钟', icon: Zap,
    patch: { question_budget: 6, duration_min: 20, followup_level: 'standard' } },
  { id: 'standard', label: '标准面试', desc: '10 题 · 40 分钟', icon: Gauge,
    patch: { question_budget: 10, duration_min: 40, followup_level: 'standard' } },
  { id: 'stress', label: '压力面', desc: '14 题 · 60 分钟 · 强追问', icon: Flame,
    patch: { question_budget: 14, duration_min: 60, followup_level: 'high' } },
];

const CODE_MODES = [['auto', '自动'], ['include', '必须含代码题'], ['exclude', '不含代码题']];
const LANGUAGES = [['java', 'Java'], ['python', 'Python'], ['javascript', 'JavaScript']];
const FOLLOWUPS = [['low', '少追问'], ['standard', '标准'], ['high', '强追问']];
const INTERVIEW_LANGS = [['zh', '中文'], ['en', '英文'], ['mixed', '中英混合']];

const local = ref({ ...props.modelValue });
watch(() => props.modelValue, (v) => { local.value = { ...v }; }, { deep: true });

function update(patch) {
  local.value = { ...local.value, ...patch };
  emit('update:modelValue', local.value);
}

function applyPreset(p) {
  update({ ...p.patch, preset: p.id });
}

/** 手工改动任一字段即视为自定义配置（预设高亮取消） */
function setField(key, value) {
  update({ [key]: value, preset: 'custom' });
}

const focusText = computed({
  get: () => (local.value.focus_skills || []).join('，'),
  set: (v) => setField('focus_skills', v.split(/[,，、;；\s]+/).map((s) => s.trim()).filter(Boolean).slice(0, 8)),
});

const studyMode = computed({
  get: () => !!local.value.study_mode,
  set: (v) => update({ study_mode: v }),
});

const budgetLabel = computed(() => local.value.question_budget || '自动');
</script>

<template>
  <!-- 八股页：只开放学习模式（难度/题量由页面自身控件负责） -->
  <div v-if="variant === 'gauntlet'" class="study-row">
    <button class="seg-btn" :class="{ on: !studyMode }" @click="studyMode = false">考试模式</button>
    <button class="seg-btn" :class="{ on: studyMode }" @click="studyMode = true">
      <GraduationCap :size="13" />学习模式
    </button>
    <span class="mini">学习模式下每题自动展开评测讲解</span>
  </div>

  <div v-else class="opt-block">
    <div class="opt-head">
      <span class="opt-title"><SlidersHorizontal :size="14" />面试配置</span>
      <span class="mini">未配置项由系统自动决定</span>
    </div>
    <div class="preset-row">
      <button
        v-for="p in PRESETS"
        :key="p.id"
        class="preset"
        :class="{ on: local.preset === p.id }"
        type="button"
        @click="applyPreset(p)"
      >
        <span class="ic"><component :is="p.icon" :size="14" /></span>
        <b>{{ p.label }}</b>
        <small>{{ p.desc }}</small>
      </button>
    </div>

    <details class="opt-more">
      <summary>高级设置（题量 {{ budgetLabel }} · {{ local.interview_language === 'en' ? '英文' : local.interview_language === 'mixed' ? '中英混合' : '中文' }} · 追问{{ local.followup_level === 'high' ? '强' : local.followup_level === 'low' ? '少' : '标准' }}）</summary>
      <div class="opt-grid">
        <label>
          <span>题量预算</span>
          <select :value="local.question_budget ?? ''" @change="setField('question_budget', $event.target.value ? Number($event.target.value) : null)">
            <option value="">自动</option>
            <option :value="6">6 题</option>
            <option :value="10">10 题</option>
            <option :value="14">14 题</option>
          </select>
        </label>
        <label>
          <span>面试时长</span>
          <select :value="local.duration_min ?? ''" @change="setField('duration_min', $event.target.value ? Number($event.target.value) : null)">
            <option value="">自动</option>
            <option :value="20">20 分钟</option>
            <option :value="40">40 分钟</option>
            <option :value="60">60 分钟</option>
          </select>
        </label>
        <label>
          <span>起始难度</span>
          <select :value="local.difficulty ?? ''" @change="setField('difficulty', $event.target.value ? Number($event.target.value) : null)">
            <option value="">自动</option>
            <option v-for="d in 5" :key="d" :value="d">{{ d }} 级</option>
          </select>
        </label>
        <label>
          <span>代码题</span>
          <select :value="local.code_mode" @change="setField('code_mode', $event.target.value)">
            <option v-for="[v, l] in CODE_MODES" :key="v" :value="v">{{ l }}</option>
          </select>
        </label>
        <label>
          <span>代码语言</span>
          <select :value="local.code_language" @change="setField('code_language', $event.target.value)">
            <option v-for="[v, l] in LANGUAGES" :key="v" :value="v">{{ l }}</option>
          </select>
        </label>
        <label>
          <span>追问强度</span>
          <select :value="local.followup_level" @change="setField('followup_level', $event.target.value)">
            <option v-for="[v, l] in FOLLOWUPS" :key="v" :value="v">{{ l }}</option>
          </select>
        </label>
        <label>
          <span>面试语言</span>
          <select :value="local.interview_language" @change="setField('interview_language', $event.target.value)">
            <option v-for="[v, l] in INTERVIEW_LANGS" :key="v" :value="v">{{ l }}</option>
          </select>
        </label>
        <label class="opt-wide">
          <span>重点技能（逗号分隔，最多 8 项）</span>
          <input v-model="focusText" placeholder="如：java 并发、redis 持久化、mysql 索引" />
        </label>
      </div>
    </details>
  </div>
</template>

<style scoped>
.opt-block { margin: 4px 0 14px; }
.opt-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.opt-title { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--text-1); }
.preset-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.preset {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  padding: 9px 11px; border: 1px solid var(--line); border-radius: var(--radius-ctl);
  background: var(--surface-2); color: var(--text-2); cursor: pointer; text-align: left;
  transition: border-color .15s var(--ease), background .15s var(--ease);
}
.preset b { font-size: 13px; color: var(--text-1); display: inline-flex; align-items: center; gap: 5px; }
.preset small { font-size: 11px; color: var(--text-3); }
.preset:hover { border-color: var(--line-strong); }
.preset.on { border-color: var(--accent); background: var(--accent-soft); }
.preset.on b { color: var(--accent); }
.opt-more { margin-top: 8px; }
.opt-more summary {
  cursor: pointer; font-size: 12px; color: var(--text-2); padding: 4px 0;
  list-style: none;
}
.opt-more summary::-webkit-details-marker { display: none; }
.opt-more summary::before { content: '▸ '; color: var(--text-3); }
.opt-more[open] summary::before { content: '▾ '; }
.opt-grid {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px 10px; margin-top: 8px;
}
.opt-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-2); }
.opt-grid select, .opt-grid input {
  padding: 6px 8px; border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--input-bg); color: var(--text-1); font-size: 12px;
}
.opt-wide { grid-column: 1 / -1; }
.study-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.seg-btn {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 6px 12px; border: 1px solid var(--line); background: var(--surface-2);
  color: var(--text-2); font-size: 12px; cursor: pointer;
}
.seg-btn:first-of-type { border-radius: var(--radius-pill) 0 0 var(--radius-pill); }
.seg-btn:nth-of-type(2) { border-radius: 0 var(--radius-pill) var(--radius-pill) 0; border-left: none; }
.seg-btn.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
</style>
