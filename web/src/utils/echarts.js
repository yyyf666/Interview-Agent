// ECharts 按需注册：雷达/折线/仪表 + 画布渲染器，控制包体
import * as echarts from 'echarts/core';
import { RadarChart, LineChart, GaugeChart } from 'echarts/charts';
import {
  TooltipComponent, LegendComponent, RadarComponent, GridComponent, MarkLineComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([
  RadarChart, LineChart, GaugeChart,
  TooltipComponent, LegendComponent, RadarComponent, GridComponent, MarkLineComponent,
  CanvasRenderer,
]);

export const prefersReducedMotion = () =>
  !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

export default echarts;
