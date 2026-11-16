// 错误分类（R-19 §7.4）：把 api/client.js 归一化后的 Error 映射成可读文案 + 是否可重试。
// 输入：{ message, status, errorCode }（client.js 的 toError / 超时 / 网络异常都会挂这三项）
// 输出：{ kind, title, hint, retryable }，kind 供分支判断，title/hint 供展示。

const KINDS = {
  offline: { title: '网络已断开', hint: '恢复网络后可重试，草稿与进度已保留', retryable: true },
  timeout: { title: '请求超时', hint: '模型响应较慢，可稍后重试（同一轮次不会重复计分）', retryable: true },
  upstream: { title: '服务暂时不可用', hint: 'AI 服务或数据库未就绪，请稍后重试', retryable: true },
  conflict: { title: '数据已被修改', hint: '请在列表中刷新后重新操作', retryable: false },
  auth: { title: '登录已过期', hint: '请重新登录后再操作', retryable: false },
  forbidden: { title: '没有权限', hint: '当前账号无权执行该操作，请联系管理员', retryable: false },
  budget: { title: '调用受限', hint: '触发限流或额度上限，请稍后重试', retryable: true },
  unknown: { title: '操作失败', hint: '', retryable: false },
};

/** 判别错误类别 */
export function classifyError(e) {
  const code = (e && e.errorCode) || '';
  const status = Number((e && e.status) || 0);
  const offline = typeof navigator !== 'undefined' && navigator.onLine === false;

  let kind = 'unknown';
  if (code === 'NETWORK_ERROR' || offline) kind = 'offline';
  else if (code === 'CLIENT_TIMEOUT' || code === 'UPSTREAM_TIMEOUT' || status === 504) kind = 'timeout';
  else if (status === 502 || status === 503 || code === 'STREAM_ERROR' || code === 'UPSTREAM_ERROR') kind = 'upstream';
  else if (status === 409 || code === 'CONFLICT' || code === 'DUPLICATE') kind = 'conflict';
  else if (status === 401 || code === 'UNAUTHORIZED') kind = 'auth';
  else if (status === 403 || code === 'FORBIDDEN' || code === 'ROLE_FORBIDDEN') kind = 'forbidden';
  else if (status === 429 || code === 'BUDGET_EXCEEDED' || code === 'RATE_LIMITED') kind = 'budget';

  const base = KINDS[kind];
  // 业务错误（4xx 参数/状态校验）保留后端原文，不被通用文案覆盖
  const hint = kind === 'unknown' || kind === 'conflict'
    ? ((e && e.message) || base.hint)
    : base.hint;
  return { kind, title: base.title, hint, retryable: base.retryable };
}

/** 一句话文案：业务错误直接用后端 message，其余用「类别标题：建议」 */
export function errorText(e) {
  const c = classifyError(e);
  if (c.kind === 'unknown') return c.hint || '操作失败';
  return c.hint ? `${c.title}：${c.hint}` : c.title;
}
