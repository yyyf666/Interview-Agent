/* 智能自适应AI面试系统 - 前端交互逻辑（接入层） */
'use strict';

const API = '/api';
const $ = (id) => document.getElementById(id);

const state = {
  profileId: null, resumeId: null, jdId: null,
  sessionId: null, turnNo: 0, busy: false,
  mediaRecorder: null, audioChunks: [], lastReport: null,
  code: { active: false, language: 'java', testCases: [], lastExecution: null, cm: null },
  hintLevel: 0, // 当前题已请求到的提示等级（1方向/2关键点/3揭晓）
  token: null,  // JWT（localStorage ia_token 持久化）
};

const SESSION_KEY = 'ia_session';
const TOKEN_KEY = 'ia_token';

/* ---------------- 基础工具 ---------------- */

function toast(msg, isError = false) {
  const el = $('toast');
  el.textContent = msg;
  el.className = 'toast show' + (isError ? ' error' : '');
  setTimeout(() => (el.className = 'toast'), 3200);
}

async function req(path, opts = {}) {
  opts.headers = Object.assign({}, opts.headers);
  if (state.token) opts.headers.Authorization = 'Bearer ' + state.token;
  const resp = await fetch(API + path, opts);
  if (resp.status === 401) {
    localStorage.removeItem(TOKEN_KEY);
    state.token = null;
    showAuthGate();
    throw new Error('未登录或登录已过期');
  }
  let body;
  try { body = await resp.json(); } catch { throw new Error('服务无响应'); }
  if (!resp.ok || body.code !== 0) throw new Error(body.message || `HTTP ${resp.status}`);
  return body.data;
}

function postJson(path, data) {
  return req(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

function postFile(path, file, extra = {}) {
  const fd = new FormData();
  fd.append('file', file);
  Object.entries(extra).forEach(([k, v]) => v != null && fd.append(k, v));
  return req(path, { method: 'POST', body: fd });
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s == null ? '' : String(s);
  return d.innerHTML;
}

/* Panel 多面试官彩色徽章（按当轮发言人设） */
const PERSONA_CN = { standard: '标准', friendly: '亲和', pressure: '压力', socratic: '引导', english: '双语' };
function personaBadge(sp) {
  if (!sp) return '';
  const known = Object.prototype.hasOwnProperty.call(PERSONA_CN, sp);
  const cls = 'persona-badge persona-' + (known ? sp : 'unknown');
  return `<span class="${cls}">面试官·${esc(PERSONA_CN[sp] || sp)}</span>`;
}

async function guard(fn) {
  if (state.busy) return toast('上一操作尚未完成');
  state.busy = true;
  try { await fn(); } catch (e) { toast(e.message, true); } finally { state.busy = false; }
}

/* ---------------- 登录/注册 ---------------- */

let authMode = 'login';

function showAuthGate() {
  $('auth-gate').classList.remove('hidden');
  $('logout').classList.add('hidden');
}
function hideAuthGate() {
  $('auth-gate').classList.add('hidden');
  $('logout').classList.remove('hidden');
}
function setAuthMode(m) {
  authMode = m;
  $('tab-login').classList.toggle('active', m === 'login');
  $('tab-register').classList.toggle('active', m === 'register');
  $('auth-nickname').classList.toggle('hidden', m === 'login');
  $('auth-submit').textContent = m === 'login' ? '登录' : '注册并开始';
}
$('tab-login').onclick = () => setAuthMode('login');
$('tab-register').onclick = () => setAuthMode('register');

$('auth-submit').onclick = () => guard(async () => {
  const username = $('auth-username').value.trim();
  const password = $('auth-password').value;
  if (!username) return toast('请输入用户名', true);
  const path = authMode === 'login' ? '/auth/login' : '/auth/register';
  const data = await postJson(path, {
    username, password,
    nickname: authMode === 'register' ? ($('auth-nickname').value.trim() || null) : null,
  });
  state.token = data.token;
  localStorage.setItem(TOKEN_KEY, data.token);
  $('whoami').textContent = '👤 ' + (data.nickname || data.username);
  hideAuthGate();
  await initMain();
  toast(authMode === 'login' ? '欢迎回来' : '注册成功，已自动创建候选人画像');
});

$('logout').onclick = () => {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(SESSION_KEY);
  location.reload();
};

/* ---------------- 视图切换 ---------------- */

document.querySelectorAll('.tab').forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll('.tab').forEach((b) => b.classList.remove('active'));
    document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
    btn.classList.add('active');
    $('view-' + btn.dataset.view).classList.add('active');
  };
});

function showView(name) {
  document.querySelector(`.tab[data-view="${name}"]`).click();
}

/* ---------------- 健康检查 ---------------- */

async function checkHealth() {
  try {
    const r = await fetch('http://' + location.hostname + ':8000/health');
    if (r.ok) $('conn-dot').classList.add('online');
  } catch { /* 跨域失败忽略，走nginx代理检测 */ }
  try {
    await req('/interviews?candidateId=' + (state.profileId || ''));
    $('conn-dot').classList.add('online');
  } catch { $('conn-dot').classList.remove('online'); }
}
setInterval(() => { if (state.token) checkHealth(); }, 15000);

/* ---------------- 准备页 ---------------- */

async function loadProfiles() {
  const list = await req('/profiles');
  const sel = $('profile-select');
  sel.innerHTML = list.map((p) =>
    `<option value="${p.id}">${esc(p.nickname)}（${esc(p.targetRole || '未设岗位')}）</option>`).join('');
  if (list.length) state.profileId = Number(sel.value);
  sel.onchange = () => { state.profileId = Number(sel.value); };
}

$('refresh-profiles').onclick = () => guard(loadProfiles);

$('create-profile').onclick = () => guard(async () => {
  const nickname = $('new-nickname').value.trim();
  if (!nickname) return toast('请输入昵称', true);
  await postJson('/profiles', { nickname, targetRole: $('new-role').value.trim() || null });
  $('new-nickname').value = $('new-role').value = '';
  await loadProfiles();
  toast('候选人已创建');
});

function renderPickList(elId, items, selectedId, onPick, label, sub) {
  const el = $(elId);
  el.innerHTML = items.map((it) =>
    `<div class="item${it.id === selectedId ? ' selected' : ''}" data-id="${it.id}">
       ${esc(label(it))}${sub ? `<small>${esc(sub(it))}</small>` : ''}
     </div>`).join('') || '<div class="kv">暂无数据</div>';
  el.querySelectorAll('.item').forEach((node) => {
    node.onclick = () => { onPick(Number(node.dataset.id)); renderPickList(elId, items, selectedId, onPick, label, sub); };
  });
}

async function loadResumes() {
  const list = await req('/resumes' + (state.profileId ? '?candidateId=' + state.profileId : ''));
  renderPickList('resume-list', list, state.resumeId, (id) => (state.resumeId = id),
    (r) => r.fileName, (r) => r.parsed ? '已解析' : '未解析');
}

async function loadJds() {
  const list = await req('/job-descriptions');
  renderPickList('jd-list', list, state.jdId, (id) => (state.jdId = id),
    (j) => j.title || '未命名岗位', (j) => j.company || '');
}

$('upload-resume').onclick = () => guard(async () => {
  const f = $('resume-file').files[0];
  if (!f) return toast('请选择简历文件', true);
  const r = await postFile('/resumes', f, { candidateId: state.profileId });
  state.resumeId = r.id;
  await loadResumes();
  toast('简历解析完成');
});

$('parse-jd-text').onclick = () => guard(async () => {
  const text = $('jd-text').value.trim();
  if (!text) return toast('请粘贴JD文本', true);
  const r = await postJson('/job-descriptions/text', { rawText: text });
  state.jdId = r.id;
  await loadJds();
  toast('JD解析完成');
});

$('upload-jd').onclick = () => guard(async () => {
  const f = $('jd-file').files[0];
  if (!f) return toast('请选择JD文件', true);
  const r = await postFile('/job-descriptions', f);
  state.jdId = r.id;
  await loadJds();
  toast('JD解析完成');
});

/* ---------------- 面试流程 ---------------- */

function addMsg(role, html, meta) {
  const div = document.createElement('div');
  div.className = 'msg ' + role;
  div.innerHTML = html + (meta ? `<span class="meta">${meta}</span>` : '');
  $('chat').appendChild(div);
  $('chat').scrollTop = $('chat').scrollHeight;
  return div;
}

function setStatus(data) {
  $('s-topic').textContent = data.topic || '-';
  $('s-diff').textContent = (data.difficulty || '-') + ' / 5';
  $('s-turn').textContent = state.turnNo;
}

function enableInput(enable) {
  $('answer-input').disabled = !enable;
  $('send-answer').disabled = !enable;
  $('run-code').disabled = !enable;
}

/* ---------------- 代码题模式 ---------------- */

const CM_MODES = { java: 'text/x-java', python: 'text/x-python', javascript: 'text/javascript' };

function editorCode() {
  return state.code.cm ? state.code.cm.getValue() : $('code-editor-fallback').value;
}

function ensureEditor(initial, language) {
  state.code.lastExecution = null;
  $('code-output').classList.add('hidden');
  $('run-status').textContent = '';
  if (window.CodeMirror) {
    $('code-editor').classList.remove('hidden');
    $('code-editor-fallback').classList.add('hidden');
    const mode = CM_MODES[language] || 'text/plain';
    if (!state.code.cm) {
      state.code.cm = CodeMirror($('code-editor'),
        { value: initial || '', mode, lineNumbers: true, indentUnit: 4, lineWrapping: true });
    } else {
      state.code.cm.setValue(initial || '');
      state.code.cm.setOption('mode', mode);
    }
  } else {
    $('code-editor').classList.add('hidden');
    const ta = $('code-editor-fallback');
    ta.classList.remove('hidden');
    ta.value = initial || '';
  }
}

function enterCodeMode(data) {
  state.code.active = true;
  state.code.language = (data.language || 'java').toLowerCase();
  state.code.testCases = data.test_cases || [];
  $('code-panel').classList.remove('hidden');
  $('code-lang').textContent = state.code.language;
  $('answer-input').classList.add('hidden');
  $('mic-btn').classList.add('hidden');
  ensureEditor(data.starter_code || '', state.code.language);

  const sel = $('tc-select');
  sel.innerHTML = '<option value="-1">自定义输入</option>' + state.code.testCases.map((tc, i) =>
    `<option value="${i}">样例${i + 1}：${esc(String(tc.input || '').slice(0, 24))}</option>`).join('');
  sel.onchange = () => {
    const i = Number(sel.value);
    $('code-stdin').value = i >= 0 ? (state.code.testCases[i].input || '') : '';
    $('run-status').textContent = i >= 0 ? `期望输出：${state.code.testCases[i].expected || '-'}` : '';
  };
}

function exitCodeMode() {
  state.code.active = false;
  $('code-panel').classList.add('hidden');
  $('answer-input').classList.remove('hidden');
  if (!$('stop-mic').classList.contains('hidden')) $('mic-btn').classList.remove('hidden');
}

async function runCode() {
  const code = editorCode();
  if (!code.trim()) return toast('请先编写代码', true);
  $('run-status').textContent = '运行中…';
  $('code-output').classList.remove('hidden');
  try {
    const r = await fetch('/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: state.code.language, code, stdin: $('code-stdin').value }),
    });
    const res = await r.json();
    state.code.lastExecution = {
      language: state.code.language, stdin: $('code-stdin').value,
      stdout: res.stdout || '', stderr: res.stderr || res.error || '',
      exit_code: res.exit_code, timed_out: !!res.timed_out,
    };
    const out = [res.stdout && `【stdout】\n${res.stdout}`,
      res.stderr && `【stderr】\n${res.stderr}`,
      res.error && `【error】${res.error}`].filter(Boolean).join('\n') || '（无输出）';
    $('code-output').textContent = out;
    $('code-output').classList.toggle('run-fail', !res.success);
    $('run-status').textContent = res.success ? `✓ 运行成功（退出码 ${res.exit_code}）` : `✗ ${res.timed_out ? '超时' : '失败'}`;
  } catch (e) {
    $('run-status').textContent = ''; $('code-output').classList.remove('hidden');
    $('code-output').textContent = '运行服务不可用：' + e.message + '（请确认 code-runner 容器已启动）';
  }
}

$('run-code').onclick = () => guard(runCode);

function renderQuestion(data) {
  state.turnNo = (state.turnNo || 0) + 1;
  state.hintLevel = 0;
  localStorage.setItem(SESSION_KEY, String(state.sessionId));
  const badge = personaBadge(data.speaker_persona);
  const tag = data.is_follow_up ? '【追问】' : '';
  if (data.question_type === 'code') {
    enterCodeMode(data);
    addMsg('interviewer', `${badge}⌨️【代码题】${esc(data.question)}`,
      `主题：${esc(data.topic || '')} ｜ 难度：${data.difficulty}/5 ｜ 作答语言：${esc(data.language || 'java')}（可先运行调试，满意后再发送）`);
  } else {
    exitCodeMode();
    addMsg('interviewer', `${badge}${tag}${esc(data.question)}`,
      `主题：${esc(data.topic || '')} ｜ 难度：${data.difficulty}/5 ｜ 来源：${esc(data.source || '')}`);
  }
  setStatus(data);
  enableInput(true);
  if (data.question_type !== 'code') $('answer-input').focus();
}

$('start-interview').onclick = () => guard(async () => {
  if (!state.resumeId) return toast('请先上传并选择简历', true);
  if (!state.jdId) return toast('请先解析并选择JD', true);
  stopRealtime();
  const personas = Array.from(document.querySelectorAll('#persona-checks input:checked'))
    .map((cb) => cb.value);
  if (!personas.length) return toast('至少选择一位面试官风格', true);
  showView('interview');
  $('chat').innerHTML = '';
  exitCodeMode();
  addMsg('system', '正在分析简历与岗位缺口，生成面试计划…');
  const data = await postJson('/interviews', {
    candidateId: state.profileId, resumeId: state.resumeId,
    jdId: state.jdId, mode: $('interview-mode').value,
    personas,
  });
  state.sessionId = data.session_id;
  state.turnNo = 0;
  localStorage.setItem(SESSION_KEY, String(data.session_id));
  $('chat').innerHTML = '';
  if (data.opening_remark) addMsg('system', esc(data.opening_remark));
  const topics = ((data.plan || {}).topics || []).map((t) => t.topic).join(' → ');
  addMsg('system', `面试计划：${esc(topics)}`);
  renderQuestion(data);
  syncRealtimeUI();
});

const INJECT_LABELS = {
  request_question: '🔄 识别到换题请求，面试官已按需调整',
  skip: '⏭ 识别到跳过请求，进入下一题',
  hint: '💁 识别到提示请求（不评分）',
  clarify: '❓ 题意澄清（不评分）',
  end: '🏁 识别到结束请求，进入复盘',
  off_topic: '🚫 请求超出面试范围，回到本题',
};

async function handleDecision(data) {
  const ev = data.evaluation;
  if (data.interviewer_reaction) {
    addMsg('interviewer', `${personaBadge(data.reaction_speaker)}💬 ${esc(data.interviewer_reaction)}`);
  }
  if (data.interjection) {
    addMsg('system', `${INJECT_LABELS[data.interjection.intent] || '🎙 识别到面试请求'}` +
      (data.interjection.acceptable === false ? '（面试官婉拒了该请求）' : ''));
  }
  if (ev) {
    addMsg('system', `本轮评测：总分 ${Math.round(ev.total)}｜准确${Math.round(ev.accuracy)} 完整${Math.round(ev.completeness)} 深度${Math.round(ev.depth)} 逻辑${Math.round(ev.logic)} 表达${Math.round(ev.expression)} 权衡${Math.round(ev.tradeoff)}`);
    if ((ev.checklist || []).length) {
      const marks = { full: '✅', partial: '◐', none: '✖' };
      addMsg('system', '📋 要点命中清单：<br>' + ev.checklist.map((c) =>
        `${marks[c.hit] || '?'} ${esc(c.point)}${c.note ? `<small class="quote">—— ${esc(c.note)}</small>` : ''}`).join('<br>'));
    }
    if ((ev.issues || []).length) {
      const quotes = ev.evidence || [];
      addMsg('system', ev.issues.map((x, i) =>
        `💡 ${esc(x)}${quotes[i] ? `<small class="quote">你的原话：「${esc(quotes[i]).slice(0, 90)}」</small>` : ''}`).join('<br>'));
    }
    if (ev.suspected_recital) {
      addMsg('system', '⚠️ 系统检测到答案与参考答案文本高度相似（疑似背诵），准确性分已下调');
    }
  }
  if (data.decision_reason) addMsg('system', `⚙ Director：${esc(data.decision_reason)}`);
  if (data.plan) addMsg('system', '🧭 Director 根据前几轮表现对剩余主题做了有界重规划，考察安排已更新');

  if (data.action === 'FINAL_REPORT' || data.report) {
    exitCodeMode();
    stopRealtime();
    enableInput(false);
    addMsg('system', '面试已结束，正在生成复盘报告…');
    state.lastReport = data.report;
    localStorage.removeItem(SESSION_KEY);
    renderReport(data.report || {});
    toast('复盘报告已生成');
    showView('report');
    return;
  }
  renderQuestion(data);
}

/* ---------------- 分级提示 ---------------- */

$('hint-btn').onclick = () => guard(async () => {
  if (!state.sessionId) return toast('当前没有进行中的面试', true);
  const level = Math.min(3, state.hintLevel + 1);
  const loading = addMsg('system', '<span class="loading-dots">面试官思考提示中</span>');
  try {
    const r = await postJson(`/interviews/${state.sessionId}/hint`, { level });
    state.hintLevel = level;
    const labels = { 1: '方向提示', 2: '关键点提示', 3: '参考思路揭晓' };
    addMsg('system', `💡 ${labels[level]}（本题第${r.hint_count}次提示，本轮评测按规则降档）：${esc(r.hint)}`);
  } finally { loading.remove(); }
});

$('send-answer').onclick = () => submitTextAnswer();
$('answer-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submitTextAnswer();
});

function submitTextAnswer() {
  guard(async () => {
    if (!state.sessionId) return;
    let text;
    let payload;
    if (state.code.active) {
      text = editorCode();
      if (!text.trim()) return toast('请先编写代码', true);
      payload = { answer: text };
      if (state.code.lastExecution) payload.execution = state.code.lastExecution;
      const shown = text.length > 600 ? text.slice(0, 600) + '\n…' : text;
      const exe = state.code.lastExecution
        ? `\n【运行结果】${state.code.lastExecution.timed_out ? '超时' : `退出码 ${state.code.lastExecution.exit_code}`}\n${(state.code.lastExecution.stdout || state.code.lastExecution.stderr || '').slice(0, 300)}`
        : '\n【未运行，直接发送】';
      addMsg('candidate', `<pre class="sent-code">${esc(shown)}</pre>${esc(exe)}`);
    } else {
      text = $('answer-input').value.trim();
      if (!text) return;
      payload = { answer: text };
      addMsg('candidate', esc(text));
    }
    payload.turnNo = state.turnNo; // 幂等键：防重复提交/双端并发推进两轮
    enableInput(false);
    $('answer-input').value = '';
    const loading = addMsg('system', '<span class="loading-dots">评测中</span>');
    const data = await postJson(`/interviews/${state.sessionId}/answers`, payload);
    loading.remove();
    await handleDecision(data);
  });
}

$('finish-interview').onclick = () => guard(async () => {
  if (!state.sessionId) return toast('当前没有进行中的面试', true);
  stopRealtime();
  addMsg('system', '面试提前结束，正在生成复盘报告…');
  const report = await postJson(`/interviews/${state.sessionId}/finish`, {});
  state.lastReport = report;
  localStorage.removeItem(SESSION_KEY);
  renderReport(report || {});
  exitCodeMode();
  enableInput(false);
  showView('report');
});

/* ---------------- 语音回答（非实时） ---------------- */

$('mic-btn').onclick = async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    state.audioChunks = [];
    const rec = new MediaRecorder(stream, { mimeType: 'audio/webm' });
    rec.ondataavailable = (e) => state.audioChunks.push(e.data);
    rec.onstop = async () => {
      stream.getTracks().forEach((t) => t.stop());
      $('s-rec').classList.add('hidden');
      $('stop-mic').classList.add('hidden');
      const blob = new Blob(state.audioChunks, { type: 'audio/webm' });
      await guard(async () => {
        enableInput(false);
        addMsg('candidate', '🎤 [语音回答，转写中…]');
        const fd = new FormData();
        fd.append('file', blob, 'answer.webm');
        const r = await fetch(API + `/interviews/${state.sessionId}/audio-answers`, {
          method: 'POST', body: fd,
          headers: state.token ? { Authorization: 'Bearer ' + state.token } : {},
        });
        const body = await r.json();
        if (!r.ok || body.code !== 0) throw new Error(body.message || '语音提交失败');
        await handleDecision(body.data);
      });
    };
    rec.start();
    state.mediaRecorder = rec;
    $('s-rec').classList.remove('hidden');
    $('stop-mic').classList.remove('hidden');
    $('stop-mic').onclick = () => rec.stop();
  } catch (e) {
    toast('麦克风不可用: ' + e.message, true);
  }
};

/* ---------------- 实时语音（流式 ASR + 静默自动提交） ---------------- */

const rt = {
  ws: null, ctx: null, stream: null, proc: null,
  silenceTimer: null, finalText: '', active: false,
};

function rtProto() { return location.protocol === 'https:' ? 'wss://' : 'ws://'; }

function floatTo16(input) {
  const out = new Int16Array(input.length);
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]));
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return out;
}

/* 降采样到 16k mono PCM16（浏览器常为 44.1/48k） */
function toPCM16(input, inRate, outRate) {
  if (outRate >= inRate) return floatTo16(input);
  const ratio = inRate / outRate;
  const outLen = Math.floor(input.length / ratio);
  const out = new Int16Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const idx = Math.floor(i * ratio);
    out[i] = Math.max(-1, Math.min(1, input[idx] || 0)) * (input[idx] < 0 ? 0x8000 : 0x7fff);
  }
  return out;
}

function syncRealtimeUI() {
  const realtime = $('interview-mode').value === 'REALTIME';
  $('rt-btn').classList.toggle('hidden', !realtime);
  $('rt-live').classList.toggle('hidden', !realtime);
  if (realtime) {
    $('mic-btn').classList.add('hidden');
  } else {
    stopRealtime();
    if ($('stop-mic').classList.contains('hidden')) $('mic-btn').classList.remove('hidden');
  }
}
$('interview-mode').addEventListener('change', syncRealtimeUI);

function connectRtWs() {
  const url = rtProto() + location.host + '/api/ws/asr?token=' + encodeURIComponent(state.token || '');
  rt.ws = new WebSocket(url);
  rt.ws.binaryType = 'arraybuffer';
  rt.ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === 'error') { toast('语音识别：' + (m.text || '错误'), true); $('rt-status').textContent = '识别中断，可重试'; return; }
    if (m.type === 'final') {
      rt.finalText += (rt.finalText ? ' ' : '') + m.text;
      $('rt-live-text').textContent = rt.finalText;
      scheduleRtSubmit();
    } else {
      $('rt-live-text').textContent = rt.finalText + (m.text ? ('\n…' + m.text) : '');
    }
  };
  rt.ws.onclose = () => { $('rt-status').textContent = ''; };
}

/* 静默 2.5s 后自动提交已定段的整段文字（VAD 简化版） */
function scheduleRtSubmit() {
  clearTimeout(rt.silenceTimer);
  rt.silenceTimer = setTimeout(() => {
    const text = rt.finalText.trim();
    if (!text || !state.sessionId) return;
    rt.finalText = '';
    $('rt-live-text').textContent = '';
    guard(async () => {
      addMsg('candidate', '🎙 [实时语音] ' + esc(text));
      enableInput(false);
      const loading = addMsg('system', '<span class="loading-dots">评测中</span>');
      const data = await postJson(`/interviews/${state.sessionId}/answers`, { answer: text, turnNo: state.turnNo });
      loading.remove();
      await handleDecision(data);
    });
  }, 2500);
}

async function startRealtime() {
  if (!state.sessionId) return toast('请先开始面试', true);
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
    rt.stream = stream;
    const Ctx = window.AudioContext || window.webkitAudioContext;
    rt.ctx = new Ctx();
    const src = rt.ctx.createMediaStreamSource(stream);
    const proc = rt.ctx.createScriptProcessor(4096, 1, 1);
    rt.proc = proc;
    const targetRate = 16000;
    proc.onaudioprocess = (e) => {
      const inp = e.inputBuffer.getChannelData(0);
      const pcm = toPCM16(inp, rt.ctx.sampleRate, targetRate);
      if (rt.ws && rt.ws.readyState === WebSocket.OPEN && pcm.length) rt.ws.send(pcm.buffer);
    };
    src.connect(proc);
    proc.connect(rt.ctx.destination);
    connectRtWs();
    rt.active = true;
    $('rt-btn').textContent = '⏹ 停止实时';
    $('rt-status').innerHTML = '<span class="rt-dot"></span> 聆听中…（停顿自动提交）';
  } catch (e) {
    toast('麦克风不可用: ' + e.message, true);
  }
}

function stopRealtime() {
  if (!rt.active && !rt.ws && !rt.ctx) return;
  rt.active = false;
  clearTimeout(rt.silenceTimer);
  if (rt.proc) { try { rt.proc.disconnect(); } catch { /* noop */ } rt.proc.onaudioprocess = null; rt.proc = null; }
  if (rt.ctx) { try { rt.ctx.close(); } catch { /* noop */ } rt.ctx = null; }
  if (rt.stream) { rt.stream.getTracks().forEach((t) => t.stop()); rt.stream = null; }
  if (rt.ws) { try { rt.ws.send('finish'); rt.ws.close(); } catch { /* noop */ } rt.ws = null; }
  $('rt-btn').textContent = '🎙 实时对话';
  $('rt-status').textContent = '';
}

$('rt-btn').onclick = () => { rt.active ? stopRealtime() : startRealtime(); };

/* ---------------- 复盘报告 ---------------- */

function renderReport(report) {
  const s = $('report-summary');
  const modules = report.module_scores || {};
  s.innerHTML = `
    <div class="score-big">${report.overall_score ?? '-'}<small style="font-size:14px;color:var(--muted)"> /100</small></div>
    <div class="kv">${esc(report.summary || '')}</div>
    ${(report.strengths || []).map((x) => `<div class="kv">✅ ${esc(x)}</div>`).join('')}`;

  $('report-weakness').innerHTML = (report.weaknesses || []).map((w) =>
    `<div class="weak-item"><b>${esc(w.skill)}</b>（${esc(w.root_cause)}）<br>${esc(w.detail)}</div>`
  ).join('') || '<div class="kv">无明显短板 🎉</div>';

  $('report-training').innerHTML = (report.training_plan || []).map((t) =>
    `<div class="task-item"><span class="day">Day ${t.day} · ${esc(t.focus)}</span>
      <ul class="tight">${(t.tasks || []).map((x) => `<li>${esc(x)}</li>`).join('')}
      ${(t.resources || []).length ? `<li style="color:var(--muted)">资料：${t.resources.map(esc).join('、')}</li>` : ''}</ul>
    </div>`).join('') || '<div class="kv">暂无计划</div>';

  const verdicts = { well_matched: ['🟢 充分匹配', 'ok'], basic: ['🟡 基本满足', ''], weak: ['🟠 薄弱', 'warn'], missing: ['⚪ 未考察/未覆盖', 'muted'] };
  $('report-match').innerHTML = (report.requirement_match || []).map((m) => {
    const v = verdicts[m.verdict] || [esc(m.verdict || ''), ''];
    return `<div class="match-item"><b>${esc(m.requirement)}</b>
      <span class="badge ${v[1]}">${v[0]}</span>
      <small>${esc(m.evidence || '')}</small></div>`;
  }).join('') || '<div class="kv">本场未关联到JD要求清单（需上传并解析JD后开始面试）</div>';

  drawRadar(modules);
}

function drawRadar(scores) {
  const cv = $('radar'), ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, cv.width, cv.height);
  const keys = Object.keys(scores);
  if (keys.length < 3) {
    ctx.fillStyle = '#8b96ad'; ctx.font = '13px sans-serif';
    ctx.fillText('模块得分不足，无法绘制雷达图', 90, 180);
    return;
  }
  const cx = cv.width / 2, cy = cv.height / 2, R = 120, n = keys.length;
  const ang = (i) => (Math.PI * 2 * i) / n - Math.PI / 2;

  ctx.strokeStyle = '#2c3a58';
  for (let lv = 1; lv <= 4; lv++) {
    ctx.beginPath();
    for (let i = 0; i <= n; i++) {
      const r = (R * lv) / 4;
      const x = cx + r * Math.cos(ang(i % n)), y = cy + r * Math.sin(ang(i % n));
      i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    }
    ctx.stroke();
  }
  ctx.beginPath();
  keys.forEach((k, i) => {
    const r = (R * Math.max(0, Math.min(100, scores[k]))) / 100;
    const x = cx + r * Math.cos(ang(i)), y = cy + r * Math.sin(ang(i));
    i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  });
  ctx.closePath();
  ctx.fillStyle = 'rgba(79,124,255,.25)';
  ctx.strokeStyle = '#4f7cff';
  ctx.fill(); ctx.stroke();

  ctx.fillStyle = '#c7d2ea'; ctx.font = '12px sans-serif'; ctx.textAlign = 'center';
  keys.forEach((k, i) => {
    const x = cx + (R + 26) * Math.cos(ang(i));
    const y = cy + (R + 22) * Math.sin(ang(i));
    ctx.fillText(`${k} ${Math.round(scores[k])}`, x, y);
  });
  ctx.textAlign = 'left';
}

/* ---------------- 管理页 ---------------- */

async function loadKnowledge() {
  const list = await req('/knowledge');
  $('kb-list').innerHTML = list.map((d) =>
    `<div class="item">${esc(d.title)}
      <span class="badge ${d.chunkStatus === 'INDEXED' ? 'ok' : ''}">${d.chunkStatus}</span>
      <small>${d.chunkCount || 0} 块</small></div>`).join('') || '<div class="kv">暂无文档</div>';
}

$('kb-upload').onclick = () => guard(async () => {
  const f = $('kb-file').files[0];
  if (!f) return toast('请选择文档', true);
  const tags = $('kb-tags').value.split(/[,，]/).filter(Boolean);
  const fd = new FormData();
  fd.append('file', f);
  tags.forEach((t) => fd.append('tags', t.trim()));
  await req('/knowledge?docType=article', { method: 'POST', body: fd });
  $('kb-tags').value = '';
  await loadKnowledge();
  toast('入库并索引完成');
});

async function loadQuestions() {
  const page = await req('/questions?pageNo=1&pageSize=50');
  $('qb-list').innerHTML = (page.records || []).map((q) =>
    `<div class="item">${esc(q.content)}
      <small>${esc(q.skill_name || '')} · 难度${q.difficulty} · ${esc(q.q_type || '')}</small></div>`
  ).join('') || '<div class="kv">题库为空</div>';
}

$('qb-refresh').onclick = () => guard(loadQuestions);
$('qb-reindex').onclick = () => guard(async () => {
  const r = await postJson('/questions/reindex', {});
  toast(`已重建 ${r.indexed ?? 0} 道题的向量索引（依赖Embedding配置）`);
});

/* ---------------- 断点续面与会话回放 ---------------- */

function turnToQuestionData(t) {
  const coding = t.coding || {};
  return {
    question: t.question, topic: t.topic, difficulty: t.difficulty,
    is_follow_up: t.isFollowUp, question_type: t.questionType,
    source: t.questionSource, reference_points: t.referencePoints || [],
    starter_code: coding.starter_code || '', language: coding.language || 'java',
    test_cases: coding.test_cases || [],
  };
}

/** 用 turns+evaluations 重建聊天现场；有开放题则继续面试，否则展示报告 */
async function restoreSession(id) {
  const session = await req('/interviews/' + id);
  if (!session || session.status !== 'RUNNING') {
    localStorage.removeItem(SESSION_KEY);
    toast('会话 #' + id + ' 已结束（' + (session ? session.status : '不存在') + '），无法续面');
    return false;
  }
  const [turns, evals] = await Promise.all([
    req(`/interviews/${id}/turns`), req(`/interviews/${id}/evaluations`)]);
  state.sessionId = Number(id);
  $('chat').innerHTML = '';
  addMsg('system', `⏮ 已恢复未完成的面试会话 #${id}（人格：${esc(session.persona || 'standard')}）`);
  const evalByTurn = {};
  evals.forEach((e) => (evalByTurn[e.turnId] = e));
  let openTurn = null;
  for (const t of turns) {
    if (t.answer == null) { openTurn = t; continue; }
    addMsg('interviewer', `${personaBadge(t.speakerPersona)}${esc(t.question)}`);
    addMsg('candidate', t.answerType === 'CODE'
      ? `<pre class="sent-code">${esc(String(t.answer).slice(0, 600))}</pre>` : esc(t.answer));
    if (t.interviewerReaction) addMsg('interviewer', `${personaBadge(t.speakerPersona)}💬 ${esc(t.interviewerReaction)}`);
    const e = evalByTurn[t.id];
    if (e) addMsg('system', `本轮评测：总分 ${Math.round(Number(e.totalScore))}`);
  }
  if (openTurn) {
    state.turnNo = openTurn.turnNo - 1; // renderQuestion 会 +1
    renderQuestion(turnToQuestionData(openTurn));
    addMsg('system', '↑ 请继续回答当前问题（接上文回放）');
  } else {
    enableInput(false);
  }
  showView('interview');
  return true;
}

/* ---------------- 历史与成长页 ---------------- */

async function loadHistory() {
  const list = await req('/interviews' + (state.profileId ? '?candidateId=' + state.profileId : ''));
  $('hist-sessions').innerHTML = list.map((s) =>
    `<div class="item" data-id="${s.id}">#${s.id} ${esc(s.title || '未命名')}
      <span class="badge ${s.status === 'COMPLETED' ? 'ok' : ''}">${esc(s.status)}</span>
      <small>${s.overallScore != null ? s.overallScore + '分' : '未评分'} · ${esc((s.createdAt || '').slice(0, 10))} · ${esc(s.persona || 'standard')}</small>
    </div>`).join('') || '<div class="kv">暂无面试会话</div>';
  $('hist-sessions').querySelectorAll('.item').forEach((n) => {
    n.onclick = () => guard(() => replaySession(Number(n.dataset.id)));
  });
  drawTrend(list.filter((s) => s.overallScore != null)
    .map((s) => Number(s.overallScore)).reverse());

  if (state.profileId) {
    const profiles = await req('/profiles');
    const p = profiles.find((x) => x.id === state.profileId);
    if (p) {
      const weak = (p.weakPoints || []).slice(0, 6)
        .map((w) => esc(typeof w === 'string' ? w : (w.skill || ''))).filter(Boolean);
      $('hist-profile').innerHTML = `长期画像：累计 ${p.totalInterviews || 0} 场，均分 ${p.avgScore ?? '-'}；`
        + (weak.length ? `高频薄弱点：${weak.join('、')}（下场面试 Director 会自动降难加量）` : '暂无沉淀薄弱点');
    }
  }
}

async function replaySession(id) {
  const [turns, evals] = await Promise.all([
    req(`/interviews/${id}/turns`), req(`/interviews/${id}/evaluations`)]);
  $('replay-title').textContent = '会话 #' + id;
  const evalByTurn = {};
  evals.forEach((e) => (evalByTurn[e.turnId] = e));
  $('replay').innerHTML = turns.map((t) => {
    const e = evalByTurn[t.id];
    return `<div class="msg interviewer">${personaBadge(t.speakerPersona)}<b>Q${t.turnNo}${t.isFollowUp ? '（追问）' : ''}${t.answerType === 'INTERJECTION' ? '（插话·' + esc(t.interjectionIntent || '') + '·不计分）' : ''}｜${esc(t.topic || '')}</b><br>${esc(t.question || '')}
      ${t.interviewerReaction ? `<br><small>💬 ${esc(t.interviewerReaction)}</small>` : ''}</div>
    <div class="msg candidate">${t.answerType === 'CODE'
      ? `<pre class="sent-code">${esc(String(t.answer || '').slice(0, 800))}</pre>` : esc(t.answer || '（未回答）')}</div>
    ${e ? `<div class="msg system">总分 ${Math.round(Number(e.totalScore))}｜准确${Math.round(Number(e.scoreAccuracy))} 完整${Math.round(Number(e.scoreCompleteness))} 深度${Math.round(Number(e.scoreDepth))}${e.suspectedRecital ? ' ⚠️疑似背诵' : ''}${(e.issues || []).length ? `<br><small>问题：${(e.issues || []).slice(0, 3).map(esc).join('；')}</small>` : ''}</div>` : ''}`;
  }).join('') || '<div class="kv">无问答记录</div>';
}

function drawTrend(scores) {
  const cv = $('trend'), ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, cv.width, cv.height);
  ctx.fillStyle = '#8b96ad'; ctx.font = '13px sans-serif';
  if (scores.length < 2) {
    ctx.fillText('完成两场及以上面试后展示总分趋势曲线', 60, 120);
    return;
  }
  const W = cv.width - 60, H = cv.height - 50;
  const pt = (i, v) => [30 + (W * i) / (scores.length - 1), 20 + H - (H * Math.max(0, Math.min(100, v))) / 100];
  ctx.strokeStyle = '#2c3a58';
  ctx.beginPath(); ctx.moveTo(30, 20); ctx.lineTo(30, 20 + H); ctx.lineTo(30 + W, 20 + H); ctx.stroke();
  ctx.strokeStyle = '#4f7cff'; ctx.lineWidth = 2; ctx.beginPath();
  scores.forEach((v, i) => { const [x, y] = pt(i, v); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
  ctx.stroke(); ctx.lineWidth = 1;
  ctx.fillStyle = '#c7d2ea';
  scores.forEach((v, i) => { const [x, y] = pt(i, v); ctx.beginPath(); ctx.arc(x, y, 3.5, 0, 7); ctx.fill(); ctx.fillText(String(v), x - 8, y - 8); });
}

document.querySelector('.tab[data-view="history"]').addEventListener('click', () => guard(loadHistory));
$('hist-refresh').onclick = () => guard(loadHistory);

/* ---------------- 初始化 ---------------- */

async function initMain() {
  await loadProfiles();
  await loadResumes();
  await loadJds();
  await loadKnowledge();
  await loadQuestions();
  checkHealth();
  const saved = localStorage.getItem(SESSION_KEY);
  if (saved && Number(saved) > 0) {
    setTimeout(() => {
      if (confirm(`检测到未完成的面试会话 #${saved}，是否断点续面？\n（恢复历史问答，从中断处继续）`)) {
        guard(() => restoreSession(saved));
      } else {
        localStorage.removeItem(SESSION_KEY);
      }
    }, 300);
  }
}

window.addEventListener('DOMContentLoaded', () => {
  state.token = localStorage.getItem(TOKEN_KEY);
  if (!state.token) { showAuthGate(); return; }
  guard(async () => {
    const me = await req('/auth/me');   // token 失效时 req 会自动回到登录门禁
    $('whoami').textContent = '👤 ' + (me.nickname || me.username);
    hideAuthGate();
    await initMain();
  });
});
