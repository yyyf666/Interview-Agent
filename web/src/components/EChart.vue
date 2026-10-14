<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import echarts, { prefersReducedMotion } from '../utils/echarts';

// ECharts 通用封装：option 响应式重绘 + 容器自适应 + 销毁兜底
const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' },
});
const el = ref(null);
let chart = null;
let ro = null;

function render() {
  if (!chart) return;
  const option = { animation: !prefersReducedMotion(), ...props.option };
  chart.setOption(option, true);
}

onMounted(() => {
  chart = echarts.init(el.value);
  render();
  if (window.ResizeObserver) {
    ro = new ResizeObserver(() => chart && chart.resize());
    ro.observe(el.value);
  }
});
watch(() => props.option, render, { deep: true });
onBeforeUnmount(() => {
  if (ro) ro.disconnect();
  if (chart) chart.dispose();
  chart = null;
});
</script>

<template>
  <div ref="el" class="echart" :style="{ height }"></div>
</template>
