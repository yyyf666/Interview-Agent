// 进入视口淡入：路由切换后为主容器内卡片状区块挂 .reveal 初始态，
// IntersectionObserver 触发一次即解除观察；首屏可见元素交给既有 rise/stagger，
// 避免"先隐藏再淡入"的闪跳与 rAF 冻结导致的 opacity:0 卡死（历史坑）。
import { nextTick } from 'vue';

const TARGETS = '.card, .stat-card, .p-card, .view-head, .stepper, .status-bar';

let io = null;

function reducedMotion() {
  return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function getObserver() {
  if (io) return io;
  io = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      e.target.classList.add('revealed');
      io.unobserve(e.target);
    }
  }, { threshold: 0.08 });
  return io;
}

export function scanReveal() {
  const main = document.querySelector('main');
  if (!main || !('IntersectionObserver' in window) || reducedMotion()) return;
  main.querySelectorAll(TARGETS).forEach((el) => {
    if (el.dataset.revealDone) return;
    el.dataset.revealDone = '1';
    // 已进入视口的不再隐藏（首帧即时呈现）
    if (el.getBoundingClientRect().top < window.innerHeight) return;
    el.classList.add('reveal');
    getObserver().observe(el);
  });
}

// fade 过渡为 200ms opacity，扫描错峰等异步组件挂载完成后再执行
export function setupReveal(router) {
  const scheduleScan = (delay) => setTimeout(() => nextTick(scanReveal), delay);
  router.afterEach(() => scheduleScan(300));
  // 兜底：视图内后续异步追加的卡片也获得淡入（防抖合并高频变更）
  let timer = null;
  const main = document.querySelector('main');
  if (main && 'MutationObserver' in window) {
    new MutationObserver(() => {
      clearTimeout(timer);
      timer = scheduleScan(200);
    }).observe(main, { childList: true, subtree: true });
  }
  scanReveal();
}
