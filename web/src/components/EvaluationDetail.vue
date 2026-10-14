<script setup>
import { ref, computed } from 'vue';
import {
  ChevronDown, ChevronUp, AlertTriangle, Check, CircleDot, X,
  BookOpen, Lightbulb, ListChecks, HelpCircle, BadgeCheck, Star, StarOff,
} from 'lucide-vue-next';
import { EVAL_DIMS } from '../utils/evaluation';

// 评测详情卡（R-04）：收起态看总分与六维，展开态看依据/遗漏/证据/要点/清单
// R-09：新增收藏（加入错题本）与默认展开（学习模式）
const props = defineProps({
  data: { type: Object, default: null },
  referencePoints: { type: Array, default: () => [] },
  hintCount: { type: Number, default: 0 },
  /** 是否展示「加入错题本」按钮（需能定位到轮次 id） */
  bookmarkable: { type: Boolean, default: false },
  /** 已收藏态：按钮变为「已在错题本」且不可重复点 */
  bookmarked: { type: Boolean, default: false },
  /** 初始展开（学习模式自动展开精讲） */
  defaultOpen: { type: Boolean, default: false },
});

const emit = defineEmits(['bookmark']);

const open = ref(props.defaultOpen);
const HIT = {
  full: { cls: 'mark-full', icon: Check },
  partial: { cls: 'mark-part', icon: CircleDot },
  none: { cls: 'mark-none', icon: X },
};
const has = (arr) => Array.isArray(arr) && arr.length > 0;
const hasDetails = computed(() => !!props.data && (
  has(props.data.basis) || has(props.data.missed) || has(props.data.issues)
  || has(props.referencePoints) || has(props.data.checklist) || props.hintCount > 0
));
</script>

<template>
  <div v-if="data" class="eval-detail">
    <div class="eval-head">
      <span class="eval-total">
        <b>{{ Math.round(data.total) }}</b><small>分</small>
      </span>
      <div class="eval-dims">
        <span
          v-for="d in EVAL_DIMS"
          :key="d.key"
          class="eval-dim"
          :title="d.label + ' ' + Math.round(data[d.key])"
        >
          <i class="eval-bar"><b :style="{ width: Math.round(data[d.key]) + '%' }"></b></i>
          <em>{{ d.label }}</em>
        </span>
      </div>
      <span v-if="data.suspectedRecital" class="eval-warn" title="答案与参考文本高度相似，准确性已下调">
        <AlertTriangle :size="12" />疑似背诵
      </span>
      <button v-if="hasDetails" class="ghost btn-ic eval-toggle" @click="open = !open">
        <span class="ic"><component :is="open ? ChevronUp : ChevronDown" :size="13" /></span>
        {{ open ? '收起' : '展开详情' }}
      </button>
      <button
        v-if="bookmarkable"
        class="ghost btn-ic eval-bookmark"
        :class="{ on: bookmarked }"
        :disabled="bookmarked"
        :title="bookmarked ? '已在错题本' : '加入错题本'"
        @click="emit('bookmark')"
      >
        <span class="ic"><component :is="bookmarked ? Star : StarOff" :size="13" /></span>
        {{ bookmarked ? '已在错题本' : '加入错题本' }}
      </button>
    </div>
    <p v-if="data.decisionReason" class="eval-reason">{{ data.decisionReason }}</p>

    <div v-if="open" class="eval-body">
      <section v-if="has(data.basis)">
        <h5><span class="ic"><BookOpen :size="13" /></span>得分依据</h5>
        <ul class="tight"><li v-for="(b, i) in data.basis" :key="i">{{ b }}</li></ul>
      </section>
      <section v-if="has(data.missed)">
        <h5><span class="ic warn"><AlertTriangle :size="13" /></span>遗漏知识点</h5>
        <div class="eval-tags">
          <span v-for="(m, i) in data.missed" :key="i" class="eval-tag">{{ m }}</span>
        </div>
      </section>
      <section v-if="has(data.issues)">
        <h5><span class="ic warm"><Lightbulb :size="13" /></span>问题与证据</h5>
        <div v-for="(x, i) in data.issues" :key="i" class="eval-issue">
          <div>{{ x }}</div>
          <blockquote v-if="data.evidence[i]">你的原话：{{ data.evidence[i] }}</blockquote>
        </div>
      </section>
      <section v-if="has(referencePoints)">
        <h5><span class="ic"><ListChecks :size="13" /></span>参考答题要点</h5>
        <ul class="tight"><li v-for="(p, i) in referencePoints" :key="i">{{ p }}</li></ul>
      </section>
      <section v-if="has(data.checklist)">
        <h5><span class="ic"><BadgeCheck :size="13" /></span>要点命中清单</h5>
        <div v-for="(c, i) in data.checklist" :key="i" class="cl-item">
          <span class="mark" :class="(HIT[c.hit] || HIT.none).cls">
            <component :is="(HIT[c.hit] || HIT.none).icon" :size="11" />
          </span>
          <div>{{ c.point }}<small v-if="c.note" class="quote">—— {{ c.note }}</small></div>
        </div>
      </section>
      <section v-if="hintCount > 0">
        <h5><span class="ic"><HelpCircle :size="13" /></span>提示使用</h5>
        <p class="mini">本题共使用 {{ hintCount }} 次提示，评测已按规则降档。</p>
      </section>
    </div>
  </div>
</template>
