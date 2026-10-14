<script setup>
import { computed } from 'vue';
import EChart from './EChart.vue';
import { CHART } from '../utils/palette';

// module_scores 雷达图：由 canvas 手绘升级为 ECharts（保留 scores prop 契约与 <3 键兜底文案）
const props = defineProps({ scores: { type: Object, default: () => ({}) } });

const keys = computed(() => Object.keys(props.scores || {}));
const enough = computed(() => keys.value.length >= 3);

const clamp = (v) => Math.max(0, Math.min(100, Number(v) || 0));

const option = computed(() => {
  if (!enough.value) return null;
  const scores = props.scores || {};
  return {
    color: [CHART.accent],
    tooltip: { trigger: 'item', confine: true },
    radar: {
      indicator: keys.value.map((k) => ({ name: `${k} ${Math.round(clamp(scores[k]))}`, max: 100 })),
      radius: '62%',
      axisName: { color: CHART.ink2, fontSize: 12 },
      axisLine: { lineStyle: { color: CHART.axis } },
      splitLine: { lineStyle: { color: CHART.split } },
      splitArea: { areaStyle: { color: ['rgba(37,99,235,.02)', 'rgba(37,99,235,.05)'] } },
    },
    series: [{
      type: 'radar',
      symbolSize: 5,
      data: [{
        value: keys.value.map((k) => clamp(scores[k])),
        name: '模块得分',
        lineStyle: { color: CHART.accent, width: 2 },
        itemStyle: { color: CHART.accent },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(37,99,235,.30)' },
              { offset: 1, color: 'rgba(37,99,235,.06)' },
            ],
          },
        },
      }],
    }],
  };
});
</script>

<template>
  <EChart v-if="enough" :option="option" height="340px" />
  <!-- R-14：模块不足 3 项时降级为横向条形，仍保留技能名与数值 -->
  <div v-else-if="keys.length" class="bar-list">
    <div v-for="k in keys" :key="k" class="bar-row">
      <span class="bar-name" :title="k">{{ k }}</span>
      <span class="bar-track">
        <span class="bar-fill" :style="{ width: clamp(scores[k]) + '%' }"></span>
      </span>
      <b class="bar-val">{{ Math.round(clamp(scores[k])) }}</b>
    </div>
    <div class="mini" style="margin-top:6px">模块不足 3 项，已降级为条形展示</div>
  </div>
  <div v-else class="chart-empty">暂无模块得分</div>
</template>

<style scoped>
.bar-list { display: flex; flex-direction: column; gap: 8px; padding: 6px 2px; }
.bar-row { display: flex; align-items: center; gap: 10px; font-size: 12.5px; }
.bar-name { flex: 0 0 96px; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { flex: 1; height: 8px; background: var(--surface); border-radius: var(--radius-pill); overflow: hidden; }
.bar-fill { display: block; height: 100%; background: var(--accent); border-radius: var(--radius-pill); }
.bar-val { flex: 0 0 34px; text-align: right; color: var(--text-1); font-variant-numeric: tabular-nums; }
</style>
