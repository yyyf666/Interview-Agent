import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { prefersReducedMotion } from '../utils/echarts';

/**
 * 数字滚动动效：sourceRef 变化时把 display 值以缓出曲线滚动到新值。
 * reduced-motion 或页面隐藏（rAF 冻结）时直接落值兜底。
 */
export function useCountUp(sourceRef, { duration = 900 } = {}) {
  const display = ref(0);
  let raf = 0;

  function animate(to) {
    const target = Math.round(Number(to) || 0);
    if (prefersReducedMotion()) { display.value = target; return; }
    cancelAnimationFrame(raf);
    const from = display.value;
    const start = performance.now();
    const step = (now) => {
      const p = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - p, 3);
      display.value = Math.round(from + (target - from) * eased);
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
  }

  watch(sourceRef, (v) => animate(v));
  onMounted(() => animate(sourceRef.value));
  onBeforeUnmount(() => cancelAnimationFrame(raf));
  return display;
}
