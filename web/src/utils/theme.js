import { ref } from 'vue';

// 外观主题（R-19）：dark / light 两档，localStorage 记忆；未手动选择时跟随系统
const KEY = 'ia_theme';

/** 当前生效主题（响应式，供顶栏切换按钮展示图标） */
export const theme = ref('light');

function stored() {
  const v = localStorage.getItem(KEY);
  return v === 'dark' || v === 'light' ? v : '';
}

function systemTheme() {
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** 写入 data-theme 并持久化；返回生效值 */
export function applyTheme(t) {
  const next = t === 'dark' ? 'dark' : 'light';
  document.documentElement.dataset.theme = next;
  localStorage.setItem(KEY, next);
  theme.value = next;
  return next;
}

/** 应用启动时调用：优先本地记忆，否则跟随系统；未手动选择时监听系统切换 */
export function initTheme() {
  const t = stored() || systemTheme();
  document.documentElement.dataset.theme = t;
  theme.value = t;
  if (!window.matchMedia) return t;
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const onChange = (e) => {
    if (stored()) return; // 用户已手动选定，不再跟随系统
    const next = e.matches ? 'dark' : 'light';
    document.documentElement.dataset.theme = next;
    theme.value = next;
  };
  if (mq.addEventListener) mq.addEventListener('change', onChange);
  else if (mq.addListener) mq.addListener(onChange);
  return t;
}

export function toggleTheme() {
  return applyTheme(theme.value === 'dark' ? 'light' : 'dark');
}
