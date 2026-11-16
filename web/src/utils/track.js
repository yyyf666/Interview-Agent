// 轻量埋点（R-19 §7.3）：console 输出 + localStorage 环形缓存最近 200 条。
// 不接第三方：只做本地诊断与产品走查，payload 仅放场景标签类字段（不放回答正文/密钥）。
const KEY = 'ia_events';
const MAX = 200;

/**
 * @param {string} event 事件名，如 interview_start / answer_submit / report_view
 * @param {object} payload 轻量上下文（id、mode、耗时等）
 */
export function track(event, payload = {}) {
  try {
    const rec = { event, ts: Date.now(), ...payload };
    // eslint-disable-next-line no-console
    console.info('[track]', event, payload);
    const list = JSON.parse(localStorage.getItem(KEY) || '[]');
    list.push(rec);
    if (list.length > MAX) list.splice(0, list.length - MAX);
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* 埋点失败静默：localStorage 满/隐私模式不影响主流程 */
  }
}

/** 读取最近事件（调试用，可在控制台调 __iaEvents()） */
export function readEvents() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '[]');
  } catch {
    return [];
  }
}

if (typeof window !== 'undefined') window.__iaEvents = readEvents;
