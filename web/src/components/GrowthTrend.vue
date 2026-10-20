<script setup>
import { computed } from 'vue';
import EChart from './EChart.vue';
import { CHART } from '../utils/palette';

// 跨会话总分趋势：由 canvas 手绘升级为 ECharts（保留 scores prop 契约与 <2 场兜底文案）
const props = defineProps({ scores: { type: Array, default: () => [] } });

const enough = computed(() => (props.scores || []).length >= 2);

const option = computed(() => {
  const s = (props.scores || []).map((v) => Math.max(0, Math.min(100, Number(v) || 0)));
  return {
    color: [CHART.accent],
    tooltip: { trigger: 'axis', confine: true },
    grid: { left: 38, right: 18, top: 26, bottom: 28 },
    xAxis: {
      type: 'category',
      data: s.map((_, i) => `第${i + 1}场`),
      axisLine: { lineStyle: { color: CHART.axis } },
      axisTick: { show: false },
      axisLabel: { color: CHART.ink2, fontSize: 11 },
    },
    yAxis: {
      type: 'value', min: 0, max: 100,
      splitLine: { lineStyle: { color: CHART.split } },
      axisLabel: { color: CHART.ink2, fontSize: 11 },
    },
    series: [{
      type: 'line', data: s, smooth: true,
      symbol: 'circle', symbolSize: 7,
      lineStyle: { width: 2 },
      itemStyle: { color: CHART.accent },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: CHART.areaTop },
            { offset: 1, color: CHART.areaBottom },
          ],
        },
      },
    }],
  };
});
</script>

<template>
  <EChart v-if="enough" :option="option" height="240px" />
  <div v-else class="chart-empty">完成两场及以上面试后展示总分趋势曲线</div>
</template>
