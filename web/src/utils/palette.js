// 图表与人设色板单源：ECharts option 内联色统一从此处取值，
// 与 global.css :root 令牌保持同值（改配色时两处同步，避免 JS 硬编码盲区）
export const CHART = {
  accent: '#2563eb',        // = --accent
  ink2: '#64748b',          // = --text-2（轴标签/轴名）
  axis: 'rgba(15,23,42,.14)',   // = --line-strong 系（轴线）
  split: 'rgba(15,23,42,.08)',  // = --line 系（网格分割线）
  areaTop: 'rgba(37,99,235,.22)',
  areaBottom: 'rgba(37,99,235,.02)',
  gaugeTrack: 'rgba(15,23,42,.08)', // 仪表底色轨道
  gaugeHi: '#059669',       // ≥80 = --ok
  gaugeMid: '#2563eb',      // 60-79 = --accent
  gaugeLo: '#c2410c',       // <60 橙红（与 .score-lo 同档）
};

// 人设识别色（头像圈/徽章底色）：socratic 用青系，避免堆紫
export const PERSONA_COLORS = {
  standard: '#2563eb',
  friendly: '#059669',
  pressure: '#dc2626',
  socratic: '#0891b2',
  english: '#0e7490',
  unknown: '#94a3b8',
};
