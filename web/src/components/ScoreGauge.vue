<script setup>
import { computed } from 'vue';
import EChart from './EChart.vue';
import { useCountUp } from '../composables/useCountUp';
import { CHART } from '../utils/palette';

// 环形分数仪表：0-100 色阶分段（≥80 绿 / 60-79 蓝 / <60 橙红），中心数字 count-up
const props = defineProps({
  score: { type: [Number, String], default: 0 },
  size: { type: Number, default: 170 },
});

const num = computed(() => Math.max(0, Math.min(100, Math.round(Number(props.score) || 0))));
const display = useCountUp(num);
const color = computed(() => (num.value >= 80 ? CHART.gaugeHi : num.value >= 60 ? CHART.gaugeMid : CHART.gaugeLo));

const option = computed(() => ({
  series: [{
    type: 'gauge',
    startAngle: 220, endAngle: -40, min: 0, max: 100,
    radius: '88%', center: ['50%', '54%'],
    progress: { show: true, width: 10, roundCap: true, itemStyle: { color: color.value } },
    axisLine: { lineStyle: { width: 10, color: [[1, CHART.gaugeTrack]] } },
    axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
    pointer: { show: false }, anchor: { show: false }, title: { show: false }, detail: { show: false },
    data: [{ value: num.value }],
  }],
}));
</script>

<template>
  <div class="gauge-wrap" :style="{ width: size + 'px', height: size + 'px' }">
    <EChart :option="option" :height="size + 'px'" />
    <div class="gauge-center">
      <div class="gc-num">{{ display }}</div>
      <div class="gc-lbl">/ 100</div>
    </div>
  </div>
</template>
