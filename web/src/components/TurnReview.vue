<script setup>
import { computed, ref } from 'vue';
import {
  ChevronDown, ChevronUp, Code2, MessageSquare, Star, StarOff, HelpCircle,
} from 'lucide-vue-next';
import EvaluationDetail from './EvaluationDetail.vue';
import { normalizeEvaluation } from '../utils/evaluation';

// 逐题精讲卡（R-09）：题目全文 / 我的回答（代码可折叠）/ 六维与依据 / 遗漏点 / 证据 / 参考要点 / 提示次数 / 收藏
const props = defineProps({
  /** { turn, evaluation }：来自 GET /interviews/{id}/turns 与 /evaluations 的按 turnId 合并结果 */
  item: { type: Object, required: true },
  /** 报告序号（第 N 题） */
  index: { type: Number, default: 0 },
  bookmarked: { type: Boolean, default: false },
});
const emit = defineEmits(['bookmark']);

const turn = computed(() => props.item.turn || {});
const evalNorm = computed(() => normalizeEvaluation(props.item.evaluation));
const isCode = computed(() => turn.value.answerType === 'CODE');
const answer = computed(() => String(turn.value.answer == null ? '' : turn.value.answer));
const answerExpanded = ref(false);
const ANSWER_PREVIEW = 320;
const shownAnswer = computed(() => {
  if (answerExpanded.value || answer.value.length <= ANSWER_PREVIEW) return answer.value;
  return answer.value.slice(0, ANSWER_PREVIEW);
});
</script>

<template>
  <div class="turn-review">
    <div class="tr-head">
      <span class="tr-no">第 {{ index }} 题</span>
      <span v-if="turn.topic" class="badge">{{ turn.topic }}</span>
      <span v-if="turn.difficulty" class="badge">难度 {{ turn.difficulty }}/5</span>
      <span v-if="turn.isFollowUp" class="badge">追问</span>
      <span v-if="turn.answerType === 'INTERJECTION'" class="badge">跳过/换题（不评分）</span>
      <button
        class="ghost btn-ic tr-bookmark"
        :class="{ on: bookmarked }"
        :disabled="bookmarked"
        @click="emit('bookmark', item)"
      >
        <span class="ic"><component :is="bookmarked ? Star : StarOff" :size="13" /></span>
        {{ bookmarked ? '已在错题本' : '加入错题本' }}
      </button>
    </div>

    <div class="tr-question">
      <span class="ic"><MessageSquare :size="13" /></span>
      <div>{{ turn.question }}</div>
    </div>

    <div v-if="answer" class="tr-answer">
      <span class="ic"><component :is="isCode ? Code2 : MessageSquare" :size="13" /></span>
      <div>
        <pre v-if="isCode" class="tr-code">{{ shownAnswer }}</pre>
        <div v-else class="tr-plain">{{ shownAnswer }}</div>
        <button
          v-if="answer.length > ANSWER_PREVIEW"
          class="ghost btn-ic tr-more"
          @click="answerExpanded = !answerExpanded"
        >
          <span class="ic"><component :is="answerExpanded ? ChevronUp : ChevronDown" :size="12" /></span>
          {{ answerExpanded ? '收起回答' : '展开全部回答' }}
        </button>
      </div>
    </div>

    <EvaluationDetail
      v-if="evalNorm"
      :data="evalNorm"
      :reference-points="turn.referencePoints || []"
      :hint-count="turn.hintCount || 0"
      bookmarkable
      :bookmarked="bookmarked"
      @bookmark="emit('bookmark', item)"
    />
    <div v-else class="kv">
      <span class="ic"><HelpCircle :size="13" /></span>
      本题无评测记录（跳过/换题或未完成评测）
    </div>
  </div>
</template>

<style scoped>
.turn-review { border-top: 1px dashed var(--line); padding: 12px 0; }
.turn-review:first-child { border-top: none; padding-top: 0; }
.tr-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.tr-no { font-size: 12.5px; font-weight: 600; color: var(--text-1); }
.tr-bookmark { margin-left: auto; font-size: 12px; }
.tr-bookmark.on { color: var(--warn); background: var(--warn-soft); opacity: .85; }
.tr-bookmark.on:disabled { cursor: default; }
.tr-question, .tr-answer { display: flex; gap: 8px; align-items: flex-start; font-size: 12.5px; color: var(--text-2); margin-bottom: 8px; }
.tr-question .ic, .tr-answer .ic { color: var(--accent); margin-top: 2px; flex: none; }
.tr-answer .ic { color: var(--text-3); }
.tr-plain { white-space: pre-wrap; word-break: break-word; }
.tr-code {
  margin: 0; padding: 8px 10px; border-radius: var(--radius-sm);
  background: var(--surface); font-size: 12px; overflow-x: auto;
  white-space: pre-wrap; word-break: break-word; max-height: 320px; overflow-y: auto;
}
.tr-more { font-size: 11.5px; margin-top: 4px; }
</style>
