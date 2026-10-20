<script setup>
import { computed } from 'vue';
import PersonaBadge from './PersonaBadge.vue';
import EvaluationDetail from './EvaluationDetail.vue';
import { normalizeEvaluation } from '../utils/evaluation';

// 会话回放：逐段搬迁旧版 replaySession() 的现场重建渲染；评测改用可展开的详情卡（R-04）
const props = defineProps({
  turns: { type: Array, default: () => [] },
  evals: { type: Array, default: () => [] },
});

const evalByTurn = computed(() => {
  const map = {};
  props.evals.forEach((e) => (map[e.turnId] = e));
  return map;
});

function header(t) {
  const parts = [];
  parts.push(`Q${t.turnNo}${t.isFollowUp ? '（追问）' : ''}`);
  if (t.answerType === 'INTERJECTION') parts.push(`（插话·${t.interjectionIntent || ''}·不计分）`);
  return parts.join('') + `｜${t.topic || ''}`;
}
</script>

<template>
  <template v-if="turns.length">
    <template v-for="t in turns" :key="t.id">
      <div class="msg interviewer">
        <PersonaBadge :persona="t.speakerPersona" /><b>{{ header(t) }}</b><br>{{ t.question || '' }}
        <template v-if="t.interviewerReaction"><br><small>{{ t.interviewerReaction }}</small></template>
      </div>
      <div class="msg candidate">
        <pre v-if="t.answerType === 'CODE'" class="sent-code">{{ String(t.answer || '').slice(0, 800) }}</pre>
        <template v-else>{{ t.answer || '（未回答）' }}</template>
      </div>
      <div v-if="evalByTurn[t.id]" class="notice eval" style="align-self:center;max-width:92%">
        <div style="width:100%">
          <EvaluationDetail
            :data="normalizeEvaluation(evalByTurn[t.id])"
            :reference-points="t.referencePoints || []"
            :hint-count="t.hintCount || 0"
          />
        </div>
      </div>
    </template>
  </template>
  <div v-else class="kv">无问答记录</div>
</template>
