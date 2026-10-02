// fetch 封装：与旧版 app.js req()/postJson()/postFile() 行为对齐，并补齐四件事：
// 1. 超时：默认 90s，AbortController 取消（LLM 链路可能长时间无响应，不能无限挂起）
// 2. 401 静默续期：先用 refresh token 换新访问令牌并重放原请求，失败才回登录门禁
// 3. 错误归一化：Error 上挂 code / errorCode / status，便于区分「参数错 / 无权限 / 上游超时」
// 4. 幂等治理：GET 遇网络错/502/503/504 指数退避重试 2 次；非 GET 带 X-Request-Id 供后端去重
const API = '/api';

export const TOKEN_KEY = 'ia_token';
export const REFRESH_KEY = 'ia_refresh_token';
export const SESSION_KEY = 'ia_session';

/** 默认请求超时（毫秒） */
export const DEFAULT_TIMEOUT = 90000;

/** 这些端点自身失败时不能触发续期（否则会循环） */
const NO_REFRESH_PATHS = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout'];

/** 可重试的上游暂态状态码（仅 GET） */
const RETRY_STATUSES = new Set([502, 503, 504]);
const MAX_RETRIES = 2;
const BACKOFF_MS = [400, 1200];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let token = localStorage.getItem(TOKEN_KEY) || null;
let refreshToken = localStorage.getItem(REFRESH_KEY) || null;
let unauthorizedHandler = null;
let tokenRefreshedHandler = null;
let refreshingPromise = null;

export function getToken() {
  return token;
}

export function setToken(t) {
  token = t;
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else localStorage.removeItem(TOKEN_KEY);
}

export function getRefreshToken() {
  return refreshToken;
}

export function setRefreshToken(t) {
  refreshToken = t;
  if (t) localStorage.setItem(REFRESH_KEY, t);
  else localStorage.removeItem(REFRESH_KEY);
}

/** 注册 401 回调（App 挂载时接 auth store） */
export function onUnauthorized(fn) {
  unauthorizedHandler = fn;
}

/** 续期成功回调（用于同步用户信息，如角色变化） */
export function onTokenRefreshed(fn) {
  tokenRefreshedHandler = fn;
}

/** 错误归一化：Error.message 供人读，errorCode/status 供分支判断 */
function toError(body, status) {
  const message = (body && body.message) || `HTTP ${status}`;
  const err = new Error(message);
  err.status = status;
  err.code = body && typeof body.code === 'number' ? body.code : status;
  err.errorCode = (body && body.errorCode) || (status === 401 ? 'UNAUTHORIZED' : `HTTP_${status}`);
  return err;
}

/** 用 refresh token 换新令牌；并发调用共享同一个请求 */
function tryRefresh() {
  if (!refreshToken) return Promise.resolve(false);
  if (!refreshingPromise) {
    refreshingPromise = (async () => {
      try {
        const resp = await fetch(API + '/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        });
        if (!resp.ok) return false;
        const body = await resp.json();
        if (body.code !== 0 || !body.data || !body.data.token) return false;
        setToken(body.data.token);
        if (body.data.refresh_token) setRefreshToken(body.data.refresh_token);
        if (tokenRefreshedHandler) tokenRefreshedHandler(body.data);
        return true;
      } catch {
        return false;
      } finally {
        refreshingPromise = null;
      }
    })();
  }
  return refreshingPromise;
}

async function send(path, opts, timeout) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  const headers = Object.assign({}, opts.headers);
  if (token) headers.Authorization = 'Bearer ' + token;
  const method = (opts.method || 'GET').toUpperCase();
  if (method !== 'GET' && !headers['X-Request-Id']) {
    headers['X-Request-Id'] = newRequestId();
  }
  try {
    return await fetch(API + path, { ...opts, headers, signal: controller.signal });
  } catch (e) {
    if (e && e.name === 'AbortError') {
      const err = new Error(`请求超时（>${Math.round(timeout / 1000)}s）`);
      err.status = 0;
      err.errorCode = 'CLIENT_TIMEOUT';
      throw err;
    }
    const err = new Error('网络异常，请检查连接');
    err.status = 0;
    err.errorCode = 'NETWORK_ERROR';
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

/** 幂等键：后端按请求头去重创建类接口（Redis SETNX） */
function newRequestId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

async function request(path, opts, retried) {
  const timeout = opts.timeout != null ? opts.timeout : DEFAULT_TIMEOUT;
  const idempotent = !opts.method || opts.method.toUpperCase() === 'GET';

  for (let attempt = 0; ; attempt += 1) {
    let resp;
    try {
      resp = await send(path, opts, timeout);
    } catch (e) {
      // 超时不重试（已等满超时时长），只对连接类错误退避重试
      const retryable = idempotent && attempt < MAX_RETRIES
        && e.status === 0 && e.errorCode === 'NETWORK_ERROR';
      if (!retryable) throw e;
      await sleep(BACKOFF_MS[attempt] || 1200);
      continue;
    }

    if (idempotent && attempt < MAX_RETRIES && RETRY_STATUSES.has(resp.status)) {
      if (resp.body) resp.body.cancel().catch(() => {});
      await sleep(BACKOFF_MS[attempt] || 1200);
      continue;
    }

    if (resp.status === 401 && !retried && !NO_REFRESH_PATHS.some((p) => path.startsWith(p))) {
      const ok = await tryRefresh();
      if (ok) return request(path, opts, true);
      setToken(null);
      setRefreshToken(null);
      if (unauthorizedHandler) unauthorizedHandler();
      throw toError({ message: '未登录或登录已过期', code: 401, errorCode: 'UNAUTHORIZED' }, 401);
    }

    let body;
    try {
      body = await resp.json();
    } catch {
      throw toError({ message: '服务无响应' }, resp.status);
    }
    if (!resp.ok || body.code !== 0) throw toError(body, resp.status);
    return body.data;
  }
}

/** opts 可传 timeout（毫秒）覆盖默认超时 */
export function req(path, opts = {}) {
  return request(path, opts, false);
}

export function postJson(path, data, opts = {}) {
  return request(
    path,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
      timeout: opts.timeout,
    },
    false,
  );
}

export function postFile(path, file, extra = {}, opts = {}) {
  const fd = new FormData();
  fd.append('file', file);
  Object.entries(extra).forEach(([k, v]) => v != null && fd.append(k, v));
  return request(path, { method: 'POST', body: fd, timeout: opts.timeout }, false);
}

/** 解析一个 SSE 帧（event/data 多行） */
function dispatchFrame(frame, emit) {
  let event = 'message';
  const dataLines = [];
  frame.split(/\r?\n/).forEach((line) => {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  });
  if (!dataLines.length) return;
  let payload;
  try {
    payload = JSON.parse(dataLines.join('\n'));
  } catch {
    return;
  }
  emit(event, payload);
}

/**
 * SSE 流式 POST（R-16）：fetch + ReadableStream 逐帧解析，onEvent(event, data) 逐事件回调。
 * 返回 result 事件的数据；环境不支持流式读取时抛 errorCode=SSE_UNSUPPORTED（调用方可回退同步接口）。
 */
export async function streamJson(path, data, onEvent, opts = {}) {
  const headers = Object.assign(
    { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    opts.headers,
  );
  if (token) headers.Authorization = 'Bearer ' + token;
  if (!headers['X-Request-Id']) headers['X-Request-Id'] = newRequestId();

  let resp;
  try {
    resp = await fetch(API + path, { method: 'POST', headers, body: JSON.stringify(data) });
  } catch {
    const err = new Error('网络异常，请检查连接');
    err.status = 0;
    err.errorCode = 'NETWORK_ERROR';
    throw err;
  }

  if (resp.status === 401) {
    const ok = await tryRefresh();
    if (ok) return streamJson(path, data, onEvent, opts);
    setToken(null);
    setRefreshToken(null);
    if (unauthorizedHandler) unauthorizedHandler();
    throw toError({ message: '未登录或登录已过期', errorCode: 'UNAUTHORIZED' }, 401);
  }
  if (!resp.ok) {
    let body = null;
    try {
      body = await resp.json();
    } catch {
      /* 非 JSON 错误体：沿用状态码文案 */
    }
    throw toError(body, resp.status);
  }
  if (!resp.body || typeof resp.body.getReader !== 'function') {
    const err = new Error('当前环境不支持流式响应');
    err.errorCode = 'SSE_UNSUPPORTED';
    throw err;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let result = null;
  let streamError = null;

  const emit = (event, payload) => {
    if (onEvent) onEvent(event, payload);
    if (event === 'result' || event === 'done') result = payload;
    if (event === 'error') {
      const err = new Error(payload && payload.message ? payload.message : '流式请求失败');
      err.errorCode = (payload && payload.errorCode) || 'STREAM_ERROR';
      streamError = err;
    }
  };

  const drain = (final) => {
    let idx = buffer.search(/\r?\n\r?\n/);
    while (idx >= 0) {
      const sepLen = buffer[idx] === '\r' ? 4 : 2;
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + sepLen);
      dispatchFrame(frame, emit);
      idx = buffer.search(/\r?\n\r?\n/);
    }
    if (final && buffer.trim()) {
      dispatchFrame(buffer, emit);
      buffer = '';
    }
  };

  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (value) {
        buffer += decoder.decode(value, { stream: true });
        drain(false);
      }
      if (streamError) {
        try { await reader.cancel(); } catch { /* noop */ }
        break;
      }
      if (done) {
        buffer += decoder.decode();
        drain(true);
        break;
      }
    }
  } catch (e) {
    const err = new Error(e && e.message ? e.message : '流式读取中断');
    err.errorCode = 'STREAM_ERROR';
    throw err;
  }

  if (streamError) throw streamError;
  return result;
}
