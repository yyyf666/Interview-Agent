<script setup>
import { Target } from 'lucide-vue-next';

// 品牌标：渐变方块内的靶心图标（顶栏/登录门禁复用）
defineProps({ iconSize: { type: Number, default: 17 } });
</script>

<template>
  <span class="brand-mark ic"><Target :size="iconSize" /></span>
</template>
