<script setup>
import { computed } from 'vue';
import { PERSONA_CN } from '../stores/session';

const props = defineProps({ persona: { type: String, default: null } });

// 与旧版 personaBadge() 一致：未知人设落到 persona-unknown 灰色徽章
// 头像圈已由 MsgBubble 承担，这里只保留人设名（去掉"面试官·"前缀）
const cls = computed(() => {
  const known = Object.prototype.hasOwnProperty.call(PERSONA_CN, props.persona);
  return 'persona-badge persona-' + (known ? props.persona : 'unknown');
});
const label = computed(() => PERSONA_CN[props.persona] || props.persona);
</script>

<template>
  <span v-if="persona" :class="cls">{{ label }}</span>
</template>
