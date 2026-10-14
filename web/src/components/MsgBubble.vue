<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import {
  Sparkles, User, Info, Gauge, ClipboardList, Lightbulb, AlertCircle, Check,
  CircleDot, X, Compass, Megaphone, CheckCircle2, RotateCw,
} from 'lucide-vue-next';
import PersonaBadge from './PersonaBadge.vue';
import EvaluationDetail from './EvaluationDetail.vue';
import { PERSONA_CN } from '../stores/session';

const props = defineProps({ msg: { type: Object, required: true } });
defineEmits(['retry', 'bookmark']);

// 要点命中图标（与旧版 ✅/◐/✖ 语义一致）
const HIT = {
  full: { cls: 'mark-full', icon: Check },
  partial: { cls: 'mark-part', icon: CircleDot },
  none: { cls: 'mark-none', icon: X },
};

/* 等待反馈（R-07）：本地计时 + 阶段推断，不伪造百分比 */
const elapsed = ref(props.msg.startedAt ? Math.floor((Date.now() - props.msg.startedAt) / 1000) : 0);
let timer = null;
onMounted(() => {
  if (props.msg.kind !== 'loading') return;
  timer = setInterval(() => {
    elapsed.value = props.msg.startedAt
      ? Math.floor((Date.now() - props.msg.startedAt) / 1000)
      : elapsed.value + 1;
  }, 1000);
});
onUnmounted(() => clearInterval(timer));

const stageText = computed(() => {
  // SSE 阶段事件（R-16）优先：服务端推进到哪个节点就显示哪一步
  if (props.msg.stageText) return props.msg.stageText;
  const list = props.msg.stages || [];
  if (!list.length) return '';
  // 每 5s 推进一档，最后一档停留（真实时长不可预知，只做方向性提示）
  return list[Math.min(list.length - 1, Math.floor(elapsed.value / 5))];
});

function sysIcon(m) {
  if (m.kind === 'eval' || m.kind === 'eval_detail') return Gauge;
  if (m.kind === 'checklist') return ClipboardList;
  if (m.kind === 'issues') return Lightbulb;
  if (m.kind === 'loading') return Info;
  // 其余 system 消息按清扫后的文本前缀匹配语义图标（与 session.js 展示文案成对）
  const t = m.text || '';
  if (t.startsWith('Director')) return Compass;
  if (t.includes('疑似背诵')) return AlertCircle;
  if (t.startsWith('面试已结束') || t.startsWith('面试提前结束')) return CheckCircle2;
  if (t.startsWith('方向提示') || t.startsWith('关键点提示') || t.startsWith('参考思路揭晓')) return Lightbulb;
  if (t.startsWith('识别到') || t.startsWith('题意澄清') || t.startsWith('请求超出')) return Megaphone;
  return Info;
}
function noticeCls(m) {
  if (m.kind === 'eval' || m.kind === 'eval_detail') return 'eval';
  const t = m.text || '';
  if (t.startsWith('Director')) return 'dir';
  if (t.startsWith('识别到')) return 'dir';
  if (t.includes('疑似背诵')) return 'warn';
  if (t.startsWith('面试已结束') || t.startsWith('面试提前结束') || t.startsWith('复盘报告')) return 'done';
  return '';
}
// 头像配色：Panel 人设各有一色，未知/无发言人回退 AI 渐变
const KNOWN = Object.keys(PERSONA_CN);
function avatarCls(m) {
  if (m.role === 'candidate') return 'me';
  return m.badge && KNOWN.includes(m.badge) ? 'p-' + m.badge : 'ai';
}
</script>

<template>
  <!-- system：居中通知条（按 kind/内容配图标） -->
  <div v-if="msg.role === 'system'" class="notice" :class="noticeCls(msg)">
    <span class="ic"><component :is="sysIcon(msg)" :size="15" /></span>
    <div class="notice-body">
      <!-- 评测详情（R-04）：一条消息承载总分/依据/遗漏/证据/要点/清单 -->
      <EvaluationDetail
        v-if="msg.kind === 'eval_detail'"
        :data="msg.evaluation"
        :reference-points="msg.referencePoints || []"
        :hint-count="msg.hintCount || 0"
        :bookmarkable="!!msg.turnId && !msg.interjection"
        :bookmarked="!!msg.bookmarked"
        :default-open="!!msg.defaultOpen"
        @bookmark="$emit('bookmark', msg)"
      />

      <!-- 要点命中清单 -->
      <template v-else-if="msg.kind === 'checklist'">
        <div v-for="(c, i) in msg.items" :key="i" class="cl-item">
          <span class="mark" :class="(HIT[c.hit] || HIT.none).cls">
            <component :is="(HIT[c.hit] || HIT.none).icon" :size="11" />
          </span>
          <div>{{ c.point }}<small v-if="c.note" class="quote">—— {{ c.note }}</small></div>
        </div>
      </template>

      <!-- 问题清单 + 证据原话 -->
      <template v-else-if="msg.kind === 'issues'">
        <div v-for="(x, i) in msg.items" :key="i" class="cl-item">
          <span class="mark mark-part"><AlertCircle :size="11" /></span>
          <div>{{ x.issue }}<small v-if="x.quote" class="quote">你的原话：「{{ x.quote }}」</small></div>
        </div>
      </template>

      <!-- 评测/推理等待：计时 + 阶段 + 20s 后可离开 -->
      <template v-else-if="msg.kind === 'loading'">
        <span class="loading-dots" aria-live="polite">
          {{ msg.text }}<template v-if="elapsed >= 1"> · {{ elapsed }}s</template>
          <template v-if="stageText"> · {{ stageText }}</template>
        </span>
        <small v-if="elapsed >= 20" class="leave-hint">
          可以离开此页，稍后在历史中查看，或点上方「同步状态」。
        </small>
      </template>

      <template v-else>{{ msg.text }}</template>
      <span v-if="msg.meta" class="meta">{{ msg.meta }}</span>
    </div>
  </div>

  <!-- 面试官 / 候选人：头像圈 + 气泡 -->
  <div v-else class="msg-row" :class="{ me: msg.role === 'candidate' }">
    <span class="msg-avatar" :class="avatarCls(msg)">
      <component :is="msg.role === 'candidate' ? User : Sparkles" :size="15" />
    </span>
    <div class="msg" :class="msg.role">
      <PersonaBadge v-if="msg.badge" :persona="msg.badge" />
      <template v-if="msg.kind === 'code'">
        <pre class="sent-code">{{ msg.code.shown }}</pre>{{ msg.code.exe }}
      </template>
      <!-- 首题生成骨架（R-07）：避免空白等待 -->
      <template v-else-if="msg.kind === 'skeleton'">
        <div class="sk-line"></div>
        <div class="sk-line"></div>
        <div class="sk-line short"></div>
      </template>
      <template v-else>{{ msg.text }}</template>
      <span v-if="msg.meta" class="meta">{{ msg.meta }}</span>
      <!-- 提交态（R-01）：发送中 / 未送达 + 重试 -->
      <span v-if="msg.role === 'candidate' && msg.status === 'pending'" class="send-state">发送中…</span>
      <span v-else-if="msg.role === 'candidate' && msg.status === 'failed'" class="send-state fail">
        未送达
        <button class="ghost btn-ic" @click="$emit('retry')">
          <span class="ic"><RotateCw :size="12" /></span>重试
        </button>
      </span>
    </div>
  </div>
</template>
