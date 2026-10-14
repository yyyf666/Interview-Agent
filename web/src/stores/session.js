import { ref, reactive, watch } from 'vue';
import { defineStore } from 'pinia';
import { req, postJson, postFile, streamJson, getToken, SESSION_KEY } from '../api/client';
import { useUiStore } from './ui';
import { useManageStore } from './manage';
import { createRealtimeVoice } from '../composables/useRealtimeVoice';
import { createAudioRecorder } from '../composables/useAudioRecorder';
import { normalizeEvaluation } from '../utils/evaluation';
import { track } from '../utils/track';
import router from '../router';

/* Panel 多面试官彩色徽章（按当轮发言人设） */
export const PERSONA_CN = { standard: '标准', friendly: '亲和', pressure: '压力', socratic: '引导', english: '双语' };

/** 最近一次报告会话 id（报告页无路由参数时回退，R-02） */
export const LAST_REPORT_KEY = 'ia_last_report';

/** 草稿键：按会话 + 轮次隔离，避免串题（R-01） */
const draftKey = (id, n) => `ia_draft:${id}:${n}`;

/** 高级面试配置（R-08）：localStorage 记忆，刷新后保留 */
const OPTIONS_KEY = 'ia_interview_options';
const OPTIONS_DEFAULTS = {
  preset: 'standard', question_budget: 10, duration_min: 40, difficulty: null,
  code_mode: 'auto', code_language: 'java', followup_level: 'standard',
  interview_language: 'zh', focus_skills: [], study_mode: false,
};

function loadInterviewOptions() {
  try {
    return { ...OPTIONS_DEFAULTS, ...JSON.parse(localStorage.getItem(OPTIONS_KEY) || '{}') };
  } catch {
    return { ...OPTIONS_DEFAULTS };
  }
}

const INJECT_LABELS = {
  request_question: '识别到换题请求，面试官已按需调整',
  skip: '识别到跳过请求，进入下一题',
  hint: '识别到提示请求（不评分）',
  clarify: '题意澄清（不评分）',
  end: '识别到结束请求，进入复盘',
  off_topic: '请求超出面试范围，回到本题',
};

let msgSeq = 0;

export const useSessionStore = defineStore('session', () => {
  const ui = useUiStore();

  /* ---------------- 准备页数据 ---------------- */
  const profileId = ref(null);
  const resumeId = ref(null);
  const jdId = ref(null);
  const profiles = ref([]);
  const resumes = ref([]);
  const jds = ref([]);

  /* ---------------- 面试流程状态 ---------------- */
  const sessionId = ref(null);
  const scene = ref('interview'); // interview=正式面试 / gauntlet=八股拷打
  const turnNo = ref(0);
  const hintLevel = ref(0); // 当前题已请求到的提示等级（1方向/2关键点/3揭晓）
  const mode = ref('TEXT');
  const messages = ref([]);
  const inputEnabled = ref(false);
  const status = reactive({ topic: '-', difficulty: '-' });
  const lastReport = ref(null);
  const focusTick = ref(0); // 非代码题下发后聚焦输入框
  const recording = ref(false); // 整段录音进行中标记（s-rec / stop-mic 显隐）

  /* ---------------- 提交态机与草稿（R-01） ---------------- */
  const submitState = ref('idle'); // idle | submitting | failed
  const draft = ref('');
  const draftError = ref(null); // { message, errorCode, status, retryable }
  const currentReferencePoints = ref([]); // 当前题的参考要点（评测卡展示，R-04）
  const currentHintCount = ref(0);
  let lastSubmit = null; // { payload, bubbleId }：重试复用同一 turnNo，不生成重复轮次

  /* ---------------- 开前缺口分析（R-03） ---------------- */
  const gap = ref(null);
  const gapLoading = ref(false);
  const gapError = ref('');

  /* ---------------- 高级面试配置（R-08） ---------------- */
  const interviewOptions = ref(loadInterviewOptions());
  /** 本场计划的配置快照（顶部 chip 展示，来自后端回传的 plan.options） */
  const planMeta = ref({});
  watch(interviewOptions, (v) => {
    try {
      localStorage.setItem(OPTIONS_KEY, JSON.stringify(v));
    } catch {
      /* 隐私模式禁用存储时静默失败 */
    }
  }, { deep: true });

  /* ---------------- 错题本（R-09） ---------------- */
  /** 当前会话已收藏的 turnId 集合（评测卡按钮态） */
  const wrongBookIds = ref(new Set());

  /* ---------------- 训练任务回写（R-10） ---------------- */
  /** 报告「去练习」带上来的目标任务 `${sessionId}:${day}:${index}`，非报告入口时为 null */
  const gauntletSource = ref(null);
  /** 本场八股标签 id（训练进度记录用） */
  const gauntletTags = ref([]);

  /* ---------------- 未完成会话横幅（R-06） ---------------- */
  const pendingResume = ref(null); // { id }

  /* 代码题模式（state.code 对应物；readCode 由 CodePanel 挂载时注入） */
  const code = reactive({
    active: false,
    language: 'java',
    testCases: [],
    starterCode: '',
    starterCodes: {},
    lastExecution: null,
    results: [],
    stdin: '',
    output: { text: '', fail: false, runStatus: '' },
    readCode: null,
    // R-13：题目切换/重置模板时自增，CodePanel 据此重载编辑器内容
    version: 0,
    fullscreen: false,
  });

  /* ---------------- 实时语音（组合式，依赖注入避免循环引用） ---------------- */
  const rt = createRealtimeVoice({
    getToken,
    getSessionId: () => sessionId.value,
    getTurnNo: () => turnNo.value,
    onToast: (msg, err) => ui.toast(msg, err),
    // R-15：静默后只做转写落地，不自动提交；用户确认后点提交
    onTranscript: (text) => appendTranscript(text),
  });

  /* ---------------- 整段录音上传（降级链路） ---------------- */
  const recorder = createAudioRecorder({
    getSessionId: () => sessionId.value,
    getToken,
    guard: (fn) => ui.guard(fn, 'answer'),
    onToast: (msg, err) => ui.toast(msg, err),
    onRecordingChange: (v) => { recording.value = v; },
    onSubmitting: () => {
      inputEnabled.value = false;
      return addMsg({ role: 'candidate', text: '[语音回答，转写中…]' });
    },
    onDecision: (data) => handleDecision(data),
    onFail: () => { /* 错误由 guard 统一 toast，与旧版一致 */ },
  });

  /* ---------------- 消息流（addMsg 等价物，结构化替代 HTML 字符串） ---------------- */
  const MSG_PASSTHROUGH = ['items', 'code', 'stages', 'startedAt', 'evaluation', 'referencePoints', 'hintCount', 'decisionReason', 'turnId', 'bookmarked', 'defaultOpen'];

  function addMsg(m) {
    const msg = {
      id: ++msgSeq, role: m.role, kind: m.kind || 'text',
      text: m.text || '', meta: m.meta || '', badge: m.badge || null, status: m.status || null,
    };
    MSG_PASSTHROUGH.forEach((k) => { if (m[k] !== undefined) msg[k] = m[k]; });
    messages.value.push(msg);
    return msg.id;
  }

  function markMsg(id, patch) {
    const msg = messages.value.find((m) => m.id === id);
    if (msg) Object.assign(msg, patch);
  }

  function removeMsg(id) {
    const i = messages.value.findIndex((m) => m.id === id);
    if (i >= 0) messages.value.splice(i, 1);
  }

  /* ---------------- 健康检查 ---------------- */
  async function checkHealth() {
    // 只探测业务后端（经 nginx / vite 同源代理）：
    // AI 能力层（:8000）已收为内网，浏览器不再直连；分页接口只取 1 条，避免探测拉全量实体
    try {
      await req('/interviews/page?pageNo=1&pageSize=1');
      ui.connOnline = true;
    } catch {
      ui.connOnline = false;
    }
  }

  /* ---------------- 准备页动作 ---------------- */
  async function loadProfiles() {
    const list = await req('/profiles');
    profiles.value = list;
    if (list.length) profileId.value = Number(list[0].id); // 与旧版 select 重置行为一致
  }

  async function createProfile(nickname, targetRole) {
    if (!nickname) return ui.toast('请输入昵称', true);
    await postJson('/profiles', { nickname, targetRole: targetRole || null });
    await loadProfiles();
    ui.toast('候选人已创建');
  }

  async function loadResumes() {
    resumes.value = await req('/resumes' + (profileId.value ? '?candidateId=' + profileId.value : ''));
  }

  async function loadJds() {
    jds.value = await req('/job-descriptions');
  }

  async function uploadResume(file) {
    if (!file) return ui.toast('请选择简历文件', true);
    const r = await postFile('/resumes', file, { candidateId: profileId.value });
    resumeId.value = r.id;
    await loadResumes();
    ui.toast('简历解析完成');
  }

  async function parseJdText(text) {
    if (!text) return ui.toast('请粘贴JD文本', true);
    const r = await postJson('/job-descriptions/text', { rawText: text });
    jdId.value = r.id;
    await loadJds();
    ui.toast('JD解析完成');
  }

  async function uploadJd(file) {
    if (!file) return ui.toast('请选择JD文件', true);
    const r = await postFile('/job-descriptions', file);
    jdId.value = r.id;
    await loadJds();
    ui.toast('JD解析完成');
  }

  /* ---------------- 候选人画像页（查看/编辑） ---------------- */
  async function fetchProfile(id) {
    return await req('/profiles/' + id);
  }

  async function saveProfile(payload) {
    // 全字段非 null 提交：字符串空用 ''、数组用 []、对象用 {}（updateById 会跳过 null 清不掉旧值）
    await req('/profiles/' + payload.id, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    await loadProfiles();
    ui.toast('画像已保存');
  }

  async function loadProfileGrowth(id) {
    return await req('/profiles/' + id + '/growth');
  }

  /* ---------------- AI 配置（全局一份，Java 转发 Python 热生效；key 出参为掩码） ---------------- */
  async function fetchAiConfig() {
    return await req('/ai-config');
  }

  async function saveAiConfig(payload) {
    await req('/ai-config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    ui.toast('配置已保存并生效');
  }

  async function testAiConfig(draft) {
    return await req('/ai-config/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(draft),
    });
  }

  /* ---------------- 准备页删除（后端级联：会话解绑+删文件；删后重拉列表并修正选中态） ---------------- */
  async function deleteProfile(id) {
    await req('/profiles/' + id, { method: 'DELETE' });
    await loadProfiles();
    if (!profiles.value.length) profileId.value = null; // 列表已空时 loadProfiles 不会重置选中
    await loadResumes(); // 名下简历已被级联删除
    if (resumeId.value && !resumes.value.some((r) => r.id === resumeId.value)) {
      resumeId.value = resumes.value.length ? resumes.value[0].id : null;
    }
    ui.toast('候选人已删除');
  }

  async function deleteResume(id) {
    await req('/resumes/' + id, { method: 'DELETE' });
    await loadResumes();
    if (resumeId.value === id) resumeId.value = resumes.value.length ? resumes.value[0].id : null;
    ui.toast('简历已删除');
  }

  async function deleteJd(id) {
    await req('/job-descriptions/' + id, { method: 'DELETE' });
    await loadJds();
    if (jdId.value === id) jdId.value = jds.value.length ? jds.value[0].id : null;
    ui.toast('JD已删除');
  }

  /* ---------------- 解析预览与重新解析（R-05） ---------------- */
  function replaceInList(list, item) {
    const i = list.value.findIndex((x) => String(x.id) === String(item.id));
    if (i >= 0) list.value[i] = item;
  }

  async function reparseResume(id) {
    const r = await req(`/resumes/${id}/reparse`, { method: 'POST' });
    replaceInList(resumes, r);
    ui.toast('简历已重新解析');
    return r;
  }

  async function reparseJd(id) {
    const r = await req(`/job-descriptions/${id}/reparse`, { method: 'POST' });
    replaceInList(jds, r);
    ui.toast('JD已重新解析');
    return r;
  }

  /* ---------------- 开前缺口分析（R-03） ---------------- */
  /** 分析失败只记录 gapError，不 toast、不阻塞开始面试 */
  async function analyzeGap(force = false) {
    if (gapLoading.value) return null;
    if (!resumeId.value || !jdId.value) return null;
    if (gap.value && !force) return gap.value;
    gapLoading.value = true;
    gapError.value = '';
    try {
      gap.value = await postJson('/interviews/gap-analysis', {
        candidateId: profileId.value, resumeId: resumeId.value, jdId: jdId.value,
      }, { timeout: 60000 });
      return gap.value;
    } catch (e) {
      gapError.value = e.message || '分析失败';
      return null;
    } finally {
      gapLoading.value = false;
    }
  }

  // 换简历/换 JD 后旧分析作废
  watch([resumeId, jdId], () => {
    gap.value = null;
    gapError.value = '';
  });

  /* ---------------- 历史会话管理（R-06） ---------------- */
  async function renameSession(id, title) {
    const updated = await req('/interviews/' + id, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    });
    ui.toast('标题已更新');
    return updated;
  }

  async function deleteSession(id) {
    await req('/interviews/' + id, { method: 'DELETE' });
    if (String(localStorage.getItem(LAST_REPORT_KEY)) === String(id)) localStorage.removeItem(LAST_REPORT_KEY);
    if (String(localStorage.getItem(SESSION_KEY)) === String(id)) localStorage.removeItem(SESSION_KEY);
    if (pendingResume.value && String(pendingResume.value.id) === String(id)) pendingResume.value = null;
    ui.toast('会话已删除');
  }

  function resumePending() {
    const p = pendingResume.value;
    if (!p) return;
    pendingResume.value = null;
    return ui.guard(() => restoreSession(p.id), 'restore');
  }

  function dismissPending() {
    pendingResume.value = null;
    localStorage.removeItem(SESSION_KEY);
  }

  /* ---------------- 题目渲染与决策处理 ---------------- */
  function setStatus(data) {
    status.topic = data.topic || '-';
    status.difficulty = (data.difficulty || '-') + ' / 5';
  }

  /* ---------------- 代码题草稿（R-13，localStorage 按会话+轮次） ---------------- */
  const CODE_DRAFT_PREFIX = 'ia_code:';

  function codeDraftKey() {
    return `${CODE_DRAFT_PREFIX}${sessionId.value}:${turnNo.value}`;
  }

  /** 切题/切语言/关页前保存：{code, stdin, language} */
  function saveCodeDraft() {
    if (!code.active || !sessionId.value) return;
    try {
      const text = code.readCode ? code.readCode() : '';
      localStorage.setItem(codeDraftKey(), JSON.stringify({
        code: text, stdin: code.stdin || '', language: code.language,
      }));
    } catch {
      /* 隐私模式/超额时静默 */
    }
  }

  function loadCodeDraft() {
    if (!sessionId.value) return null;
    try {
      const raw = localStorage.getItem(codeDraftKey());
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  function clearCodeDraft() {
    try {
      if (sessionId.value) localStorage.removeItem(codeDraftKey());
    } catch {
      /* 忽略 */
    }
  }

  function enterCodeMode(data) {
    // 上一题仍是代码题：先落草稿再换题
    if (code.active) saveCodeDraft();
    const draft = loadCodeDraft();
    code.active = true;
    code.language = String((draft && draft.language) || data.language || 'java').toLowerCase();
    code.testCases = data.test_cases || [];
    code.starterCodes = data.starter_codes || {};
    const draftCode = draft && draft.code ? draft.code : '';
    code.starterCode = draftCode
      || data.starter_code
      || code.starterCodes[code.language]
      || '';
    code.stdin = (draft && draft.stdin) || '';
    code.lastExecution = null;
    code.results = [];
    code.output = { text: '', fail: false, runStatus: '' };
    code.version += 1; // 通知 CodePanel 重置编辑器
  }

  function exitCodeMode() {
    if (code.active) saveCodeDraft();
    code.active = false;
    code.fullscreen = false;
  }

  /** 切换作答语言（R-13）：用 starter_codes[lang] 覆盖编辑器并提示 */
  function setCodeLanguage(lang) {
    const next = String(lang || '').toLowerCase();
    if (!['java', 'python', 'javascript'].includes(next) || next === code.language) return;
    code.language = next;
    const tpl = code.starterCodes[next] || '';
    if (tpl) {
      code.starterCode = tpl;
      code.version += 1;
      ui.toast(`已切换到 ${next}，已同步该语言的初始模板`);
    } else {
      ui.toast(`已切换到 ${next}（本题无该语言模板，保留当前代码）`);
    }
    saveCodeDraft();
  }

  /** 重置为当前语言的初始模板 */
  function resetCode() {
    const tpl = code.starterCodes[code.language] || '';
    if (!tpl) return ui.toast('本题没有可用的初始模板', true);
    code.starterCode = tpl;
    code.version += 1;
    saveCodeDraft();
    ui.toast('已重置为初始模板');
  }

  /* ---------------- 草稿读写（sessionStorage，按会话+轮次） ---------------- */
  function loadDraft() {
    draft.value = (sessionId.value && turnNo.value
      && sessionStorage.getItem(draftKey(sessionId.value, turnNo.value))) || '';
  }

  function saveDraft() {
    if (!sessionId.value || !turnNo.value) return;
    if (draft.value) sessionStorage.setItem(draftKey(sessionId.value, turnNo.value), draft.value);
    else sessionStorage.removeItem(draftKey(sessionId.value, turnNo.value));
  }

  function clearDraft() {
    if (sessionId.value && turnNo.value) {
      sessionStorage.removeItem(draftKey(sessionId.value, turnNo.value));
    }
    draft.value = '';
  }

  function renderQuestion(data) {
    turnNo.value = (turnNo.value || 0) + 1;
    hintLevel.value = 0;
    // 评测卡要展示本题的参考要点与提示次数（R-04）
    currentReferencePoints.value = data.reference_points || [];
    currentHintCount.value = 0;
    draftError.value = null;
    localStorage.setItem(SESSION_KEY, String(sessionId.value));
    const tag = data.is_follow_up ? '【追问】' : '';
    if (data.question_type === 'code') {
      enterCodeMode(data);
      addMsg({
        role: 'interviewer', badge: data.speaker_persona,
        text: '代码题 · ' + data.question,
        meta: `主题：${data.topic || ''} ｜ 难度：${data.difficulty}/5 ｜ 作答语言：${data.language || 'java'}（可先运行调试，满意后再发送）`,
      });
    } else {
      exitCodeMode();
      addMsg({
        role: 'interviewer', badge: data.speaker_persona,
        text: `${tag}${data.question}`,
        meta: `主题：${data.topic || ''} ｜ 难度：${data.difficulty}/5 ｜ 来源：${data.source || ''}`,
      });
      focusTick.value++;
    }
    setStatus(data);
    inputEnabled.value = true;
    loadDraft();
  }

  async function handleDecision(data) {
    const ev = data.evaluation;
    if (data.interviewer_reaction) {
      addMsg({ role: 'interviewer', badge: data.reaction_speaker, text: data.interviewer_reaction });
    }
    if (data.interjection) {
      addMsg({
        role: 'system',
        text: (INJECT_LABELS[data.interjection.intent] || '识别到面试请求') +
          (data.interjection.acceptable === false ? '（面试官婉拒了该请求）' : ''),
      });
    }
    if (ev) {
      // R-04：一条评测卡取代旧的「分数行 + 清单 + 问题」三条消息
      const evNorm = normalizeEvaluation(ev);
      // 决策依据有时挂在顶层 data 上（不在 evaluation 内），统一收进卡片
      if (!evNorm.decisionReason && data.decision_reason) evNorm.decisionReason = data.decision_reason;
      addMsg({
        role: 'system', kind: 'eval_detail',
        evaluation: evNorm,
        referencePoints: currentReferencePoints.value,
        hintCount: currentHintCount.value,
        decisionReason: data.decision_reason || '',
        turnId: data.turn_id || null,
        bookmarked: !!data.turn_id && wrongBookIds.value.has(data.turn_id),
        // 学习模式（R-11）：出题后自动展开精讲；需等 planMeta 回传 study_mode
        defaultOpen: !!planMeta.value.study_mode,
      });
      if (ev.suspected_recital) {
        addMsg({ role: 'system', text: '系统检测到答案与参考答案文本高度相似（疑似背诵），准确性分已下调' });
      }
    }
    // 无评测（插话/免评分轮）时保留 Director 说明；有评测时已在卡内展示决策依据
    if (data.decision_reason && !ev) addMsg({ role: 'system', text: 'Director：' + data.decision_reason });
    if (data.plan) addMsg({ role: 'system', text: 'Director 根据前几轮表现对剩余主题做了有界重规划，考察安排已更新' });

    if (data.action === 'FINAL_REPORT' || data.report) {
      exitCodeMode();
      rt.stop();
      inputEnabled.value = false;
      addMsg({
        role: 'system',
        text: scene.value === 'gauntlet' ? '八股拷打结束，正在生成复盘报告…' : '面试已结束，正在生成复盘报告…',
      });
      lastReport.value = data.report;
      const finishedId = sessionId.value;
      localStorage.removeItem(SESSION_KEY);
      if (finishedId) localStorage.setItem(LAST_REPORT_KEY, String(finishedId));
      ui.toast('复盘报告已生成');
      // 训练任务回写（R-10）：从报告「去练习」发起的八股练习，完成即标记
      if (scene.value === 'gauntlet' && gauntletSource.value && finishedId) {
        const [sid, day, idx] = String(gauntletSource.value).split(':');
        postJson('/training-progress', {
          sourceSessionId: Number(sid), planDay: Number(day), taskIndex: Number(idx),
          tagIds: gauntletTags.value, gauntletSessionId: finishedId,
        }).catch(() => ui.toast('训练进度回写失败（不影响报告）', true));
        gauntletSource.value = null;
      }
      // R-02：报告页深链携带会话 id，刷新/分享后才能还原同一份报告
      router.push({ name: 'report', params: finishedId ? { id: finishedId } : {} });
      return;
    }
    renderQuestion(data);
  }

  /* ---------------- 提交作答（文本/代码/实时语音三链路汇聚点） ---------------- */
  const isRetryable = (e) => !!e && (
    e.errorCode === 'CLIENT_TIMEOUT' || e.errorCode === 'NETWORK_ERROR' || e.status >= 500);

  /* ---------------- 流式作答（R-16）：节点级 SSE，不可用时回退同步接口 ---------------- */
  const STAGE_LABELS = {
    evaluate: '六维评分', decide: '决策追问', react: '面试官回应',
    follow_up: '准备追问', question: '整理下一题', interject: '处理插话', final_report: '生成报告',
  };

  async function sendAnswerStream(payload, loading) {
    let gotStage = false;
    try {
      const result = await streamJson(
        `/interviews/${sessionId.value}/answers/stream`, payload,
        (event, data) => {
          if (event !== 'stage' || !data) return;
          gotStage = true;
          markMsg(loading, { stageText: STAGE_LABELS[data.node] || '评测中' });
        },
      );
      if (!result) {
        const err = new Error('流式响应缺少结果');
        err.errorCode = 'STREAM_ERROR';
        throw err;
      }
      return result;
    } catch (e) {
      // 仅「环境不支持」或「首个阶段事件前就失败」才回退；已推进的链路交给失败态重试（turnNo 幂等）
      if (e.errorCode !== 'SSE_UNSUPPORTED' && gotStage) throw e;
      markMsg(loading, { stageText: '流式不可用，改用标准提交' });
      return postJson(`/interviews/${sessionId.value}/answers`, payload, { timeout: 180000 });
    }
  }

  /**
   * 发送已构建的 payload（R-01 提交态机）：
   * 成功 → 气泡 sent / 清草稿；失败 → 气泡 failed / 保留草稿 / 错误横幅；409 → 自动同步状态。
   */
  async function sendPayload(payload, bubbleId, source) {
    lastSubmit = { payload, bubbleId, source };
    submitState.value = 'submitting';
    draftError.value = null;
    inputEnabled.value = false;
    const loading = addMsg({
      role: 'system', kind: 'loading', text: '评测中',
      stages: ['六维评分', '生成追问', '整理下一题'], startedAt: Date.now(),
    });
    const turn = turnNo.value; // 埋点用：handleDecision 可能已推进轮次
    try {
      const data = await sendAnswerStream(payload, loading);
      removeMsg(loading);
      markMsg(bubbleId, { status: 'sent' });
      submitState.value = 'idle';
      lastSubmit = null;
      clearDraft();
      if (source === 'code') clearCodeDraft(); // R-13：提交成功即清代码草稿
      track('answer_submit', { scene: scene.value, turn, source });
      await handleDecision(data);
      return true;
    } catch (e) {
      removeMsg(loading);
      const conflict = e.status === 409 || e.errorCode === 'CONFLICT';
      if (conflict) {
        // 服务端已推进（例如别的端已提过）：不提供重试，直接对齐状态
        markMsg(bubbleId, { status: 'sent' });
        submitState.value = 'idle';
        lastSubmit = null;
        ui.toast('服务端已推进，正在同步最新状态');
        await syncState();
        return false;
      }
      markMsg(bubbleId, { status: 'failed' });
      submitState.value = 'failed';
      inputEnabled.value = true;
      draftError.value = {
        message: e.message || '提交失败',
        errorCode: e.errorCode || '',
        status: e.status,
        retryable: isRetryable(e),
      };
      // 草稿回填：文本类回答可直接重试或继续修改
      if (!draft.value && payload.answer) draft.value = payload.answer;
      saveDraft();
      ui.toast(e.message || '提交失败', true);
      return false;
    }
  }

  async function submitAnswer({ source, text }) {
    if (!sessionId.value) return false;
    let payload;
    let bubbleId;
    if (source === 'code') {
      if (!text.trim()) { ui.toast('请先编写代码', true); return false; }
      payload = { answer: text };
      if (code.lastExecution) payload.execution = code.lastExecution;
      const shown = text.length > 600 ? text.slice(0, 600) + '\n…' : text;
      const exe = code.lastExecution
        ? `\n【运行结果】${code.lastExecution.timed_out ? '超时' : `退出码 ${code.lastExecution.exit_code}`}\n${(code.lastExecution.stdout || code.lastExecution.stderr || '').slice(0, 300)}`
        : '\n【未运行，直接发送】';
      bubbleId = addMsg({ role: 'candidate', kind: 'code', code: { shown, exe }, status: 'pending' });
    } else {
      if (source === 'realtime') {
        bubbleId = addMsg({ role: 'candidate', text: '[实时语音] ' + text, status: 'pending' });
      } else {
        text = (text || '').trim();
        if (!text) { ui.toast('请先输入回答', true); return false; }
        bubbleId = addMsg({ role: 'candidate', text, status: 'pending' });
      }
      payload = { answer: text };
    }
    payload.turnNo = turnNo.value; // 幂等键：驳回重复提交；已作答未评测时服务端会自动补评测
    return sendPayload(payload, bubbleId, source);
  }

  /** 重试：复用同一 payload（同一 turnNo），不生成重复轮次 */
  function retrySubmit() {
    if (!lastSubmit) return ui.toast('没有可重试的提交', true);
    const { payload, bubbleId, source } = lastSubmit;
    markMsg(bubbleId, { status: 'pending' });
    draftError.value = null;
    return sendPayload(payload, bubbleId, source);
  }

  /** 人工重试评测（同步/巡检入口；前端常规重试走 /answers） */
  async function retryEvaluation(id, turn) {
    const target = id || sessionId.value;
    if (!target) return null;
    return postJson(`/interviews/${target}/turns/${turn}/retry-evaluation`, {}, { timeout: 180000 });
  }

  function submitTextAnswer(text) {
    return ui.guard(() => submitAnswer({ source: 'text', text }), 'answer');
  }

  /** 实时语音转写落地（R-15）：追加到草稿并提示确认，不自动提交 */
  function appendTranscript(text) {
    const t = (text || '').trim();
    if (!t) return;
    draft.value = draft.value ? `${draft.value}\n${t}` : t;
    saveDraft();
    focusTick.value += 1; // 把焦点交回输入框，方便直接修改/提交
    ui.toast('已转写，确认后点击提交');
  }

  function submitCodeAnswer() {
    return ui.guard(() => {
      const text = code.readCode ? code.readCode() : '';
      return submitAnswer({ source: 'code', text });
    }, 'answer');
  }

  /* ---------------- 分级提示 ---------------- */
  function requestHint() {
    return ui.guard(async () => {
      if (!sessionId.value) return ui.toast('当前没有进行中的面试', true);
      const level = Math.min(3, hintLevel.value + 1);
      const loading = addMsg({ role: 'system', kind: 'loading', text: '面试官思考提示中' });
      try {
        const r = await postJson(`/interviews/${sessionId.value}/hint`, { level });
        hintLevel.value = level;
        const labels = { 1: '方向提示', 2: '关键点提示', 3: '参考思路揭晓' };
        addMsg({ role: 'system', text: `${labels[level]}（本题第${r.hint_count}次提示，本轮评测按规则降档）：${r.hint}` });
      } finally {
        removeMsg(loading);
      }
    }, 'hint');
  }

  /* ---------------- 八股跳过/换题（R-11） ---------------- */

  const INTERJECT_LABELS = { skip: '跳过本题', request_question: '换一题' };

  /**
   * 主动跳过/换题：不产生评测与乱序计分，服务端标记轮次为 INTERJECTION 后直接出下一题。
   * 会消耗 1 题预算，成功后提示用户（避免误触）。
   */
  function interject(intent) {
    return ui.guard(async () => {
      if (!sessionId.value) return ui.toast('当前没有进行中的面试', true);
      if (scene.value !== 'gauntlet') return ui.toast('仅八股练习支持跳过/换题', true);
      if (!inputEnabled.value) return ui.toast('面试官正在出题，请稍候', true);
      const label = INTERJECT_LABELS[intent] || INTERJECT_LABELS.skip;
      inputEnabled.value = false;
      const bubble = addMsg({ role: 'candidate', text: `[${label}]`, status: 'pending' });
      const loading = addMsg({
        role: 'system', kind: 'loading', text: '正在换题',
        stages: ['记录跳过', '挑选新题'], startedAt: Date.now(),
      });
      try {
        const data = await postJson(`/interviews/${sessionId.value}/interject`, { intent }, { timeout: 180000 });
        removeMsg(loading);
        markMsg(bubble, { status: 'sent' });
        clearDraft();
        ui.toast('本题不计分，已消耗 1 题预算');
        await handleDecision(data);
        return true;
      } catch (e) {
        removeMsg(loading);
        markMsg(bubble, { status: 'failed' });
        inputEnabled.value = true;
        ui.toast(e.message || '换题失败', true);
        return false;
      }
    }, 'interject');
  }

  /* ---------------- 错题本（R-09） ---------------- */

  /** 拉取当前会话已收藏的轮次 id（评测卡按钮态）；失败静默，不阻塞主流程 */
  async function refreshWrongBookIds() {
    if (!sessionId.value) {
      wrongBookIds.value = new Set();
      return;
    }
    try {
      const r = await req('/review-items?pageNo=1&pageSize=100');
      const ids = (r.records || [])
        .filter((x) => Number(x.sessionId) === Number(sessionId.value))
        .map((x) => x.turnId);
      wrongBookIds.value = new Set(ids);
      messages.value.forEach((m) => {
        if (m.kind === 'eval_detail' && m.turnId) m.bookmarked = wrongBookIds.value.has(m.turnId);
      });
    } catch {
      /* 未登录/离线：保持原状 */
    }
  }

  /** 收藏一轮问答（幂等）：成功后按钮态变「已在错题本」 */
  function addToWrongBook(turnId, extra) {
    return ui.guard(async () => {
      const id = turnId || null;
      if (!id) return ui.toast('该轮次暂无评测，不可收藏', true);
      const item = await postJson('/review-items', {
        sessionId: sessionId.value, turnId: id, ...(extra || {}),
      });
      wrongBookIds.value = new Set([...wrongBookIds.value, id]);
      messages.value.forEach((m) => {
        if (m.kind === 'eval_detail' && m.turnId === id) m.bookmarked = true;
      });
      track('wrong_book_add', { turnId: id, sessionId: sessionId.value });
      ui.toast('已加入错题本');
      return item;
    }, 'review');
  }

  /** MsgBubble 冒泡入口：用消息上的 turnId 收藏 */
  function bookmarkMessage(msg) {
    return addToWrongBook(msg && msg.turnId);
  }

  /* ---------------- 开始/结束面试 ---------------- */

  /** 进入面试页后的等待现场（R-07）：骨架 + 阶段条，首题到达前不渲染半成品 */
  function showWaiting(stages, label) {
    messages.value = [];
    exitCodeMode();
    draft.value = '';
    draftError.value = null;
    submitState.value = 'idle';
    const skeleton = addMsg({ role: 'interviewer', kind: 'skeleton' });
    const loading = addMsg({
      role: 'system', kind: 'loading', text: label,
      stages, startedAt: Date.now(),
    });
    return { skeleton, loading };
  }

  function startInterview(personas, gapSnapshot, options) {
    return ui.guard(async () => {
      if (!resumeId.value) return ui.toast('请先上传并选择简历', true);
      if (!jdId.value) return ui.toast('请先解析并选择JD', true);
      rt.stop();
      if (!personas.length) return ui.toast('至少选择一位面试官风格', true);
      scene.value = 'interview';
      router.push({ name: 'interview' });
      const wait = showWaiting(['解析简历', '匹配岗位', '规划主题', '生成首题'], '生成面试计划');
      const opts = { ...(options || interviewOptions.value) };
      let data;
      try {
        const body = {
          candidateId: profileId.value, resumeId: resumeId.value,
          jdId: jdId.value, mode: mode.value,
          personas, options: opts,
        };
        // 开前缺口分析已确认时透传，PLAN 节点直接复用（R-03）
        if (gapSnapshot && Object.keys(gapSnapshot).length) body.gap = gapSnapshot;
        data = await postJson('/interviews', body, { timeout: 180000 });
      } catch (e) {
        removeMsg(wait.skeleton);
        removeMsg(wait.loading);
        messages.value = [];
        inputEnabled.value = false;
        router.push({ name: 'setup' }); // 失败回到准备页，选择保持，可直接重试
        throw e;
      }
      removeMsg(wait.skeleton);
      removeMsg(wait.loading);
      sessionId.value = data.session_id;
      turnNo.value = 0;
      wrongBookIds.value = new Set();
      localStorage.setItem(SESSION_KEY, String(data.session_id));
      messages.value = []; // 首题到达后一次性渲染，避免布局跳动
      planMeta.value = (data.plan && data.plan.options) || opts;
      if (data.opening_remark) addMsg({ role: 'system', text: data.opening_remark });
      const topics = ((data.plan || {}).topics || []).map((t) => t.topic).join(' → ');
      addMsg({ role: 'system', text: `面试计划：${topics}` });
      renderQuestion(data);
      track('interview_start', { mode: mode.value, budget: opts.question_budget, code: opts.code_mode });
      track('first_question_visible', { sessionId: sessionId.value, scene: 'interview' });
      gap.value = null;
      gapError.value = '';
    }, 'start');
  }

  /* 八股拷打：不校验简历/JD，仅传标签；复用同一会话/评测/报告链路 */
  function startGauntlet(tagIds, personas, difficulty, studyMode) {
    return ui.guard(async () => {
      if (!tagIds.length) return ui.toast('请至少选择一个八股标签', true);
      if (!personas.length) return ui.toast('至少选择一位面试官风格', true);
      rt.stop();
      scene.value = 'gauntlet';
      router.push({ name: 'interview' });
      const wait = showWaiting(['生成标签计划', '抽取首题'], '准备八股拷打');
      gauntletTags.value = tagIds.slice();
      let data;
      try {
        data = await postJson('/gauntlet/start', {
          tagIds, difficulty, personas, studyMode: !!studyMode,
        }, { timeout: 180000 });
      } catch (e) {
        removeMsg(wait.skeleton);
        removeMsg(wait.loading);
        messages.value = [];
        inputEnabled.value = false;
        throw e;
      }
      removeMsg(wait.skeleton);
      removeMsg(wait.loading);
      sessionId.value = data.session_id;
      turnNo.value = 0;
      wrongBookIds.value = new Set();
      localStorage.setItem(SESSION_KEY, String(data.session_id));
      messages.value = [];
      planMeta.value = (data.plan && data.plan.options) || { study_mode: !!studyMode };
      if (data.opening_remark) addMsg({ role: 'system', text: data.opening_remark });
      const topics = ((data.plan || {}).topics || []).map((t) => t.topic).join(' → ');
      if (topics) addMsg({ role: 'system', text: `拷打标签：${topics}` });
      renderQuestion(data);
      track('practice_start', { tags: tagIds.length, difficulty, study: !!studyMode });
      track('first_question_visible', { sessionId: sessionId.value, scene: 'gauntlet' });
    }, 'start');
  }

  /* ---------------- 异步报告（R-16）：finish 立刻返回，报告在后台生成 ---------------- */
  const REPORT_POLL_MS = 3000;
  const REPORT_POLL_LIMIT = 180000;
  const REPORT_STAGES = ['汇总轮次', '归纳短板', '生成训练计划', '整理报告'];

  function reportFailure(message) {
    const err = new Error(message || '报告生成失败');
    err.errorCode = 'REPORT_FAILED';
    return err;
  }

  /**
   * 等待后台报告：finish 返回 READY 直接用；RUNNING 每 3s 轮询 report-status（最长 180s）。
   * FAILED / 超时抛 REPORT_FAILED，由调用方决定重试或跳转报告页。
   */
  async function waitForReport(target, loading, initial) {
    const startedAt = Date.now();
    let data = initial;
    while (true) {
      if (data) {
        if (data.report_status === 'READY' && data.report) return data.report;
        if (data.report_status === 'FAILED') throw reportFailure(data.report_error);
      }
      if (Date.now() - startedAt >= REPORT_POLL_LIMIT) {
        throw reportFailure('报告生成超时，请稍后在历史中查看');
      }
      await new Promise((resolve) => setTimeout(resolve, REPORT_POLL_MS));
      markMsg(loading, {
        stageText: REPORT_STAGES[Math.min(REPORT_STAGES.length - 1,
          Math.floor((Date.now() - startedAt) / 5000))],
      });
      const st = await req(`/interviews/${target}/report-status`, { timeout: 20000 });
      if (st.reportStatus === 'READY') {
        const detail = await req(`/interviews/${target}`, { timeout: 20000 });
        return (detail && detail.report) || {};
      }
      if (st.reportStatus === 'FAILED') throw reportFailure(st.reportError);
      if (st.reportStatus === 'NONE') throw reportFailure('会话未进入报告流程，请重试');
      data = null;
    }
  }

  function finishInterview() {
    return ui.guard(async () => {
      if (!sessionId.value) return ui.toast('当前没有进行中的面试', true);
      const target = sessionId.value;
      rt.stop();
      exitCodeMode();
      inputEnabled.value = false;
      const loading = addMsg({
        role: 'system', kind: 'loading', text: '生成复盘报告',
        stages: REPORT_STAGES, startedAt: Date.now(),
      });
      let report;
      try {
        const initial = await postJson(`/interviews/${target}/finish`, {}, { timeout: 60000 });
        report = await waitForReport(target, loading, initial);
      } catch (e) {
        removeMsg(loading);
        localStorage.removeItem(SESSION_KEY);
        localStorage.setItem(LAST_REPORT_KEY, String(target));
        if (e.errorCode === 'REPORT_FAILED') {
          // 报告失败：会话已结束，转到报告页可查看失败原因并重试（R-16）
          ui.toast(e.message, true);
          router.push({ name: 'report', params: { id: target } });
          return;
        }
        inputEnabled.value = true;
        throw e;
      }
      removeMsg(loading);
      lastReport.value = report;
      localStorage.removeItem(SESSION_KEY);
      localStorage.setItem(LAST_REPORT_KEY, String(target));
      ui.toast('复盘报告已生成');
      router.push({ name: 'report', params: { id: target } });
    }, 'finish');
  }

  /**
   * 报告页「结束并生成报告」（无进行中会话时按 id 收尾，R-02）：
   * 只负责发起（R-16 异步），报告页据此展示「生成中」并自行轮询。
   */
  function finishSession(id) {
    return ui.guard(async () => {
      const target = id || sessionId.value;
      if (!target) return ui.toast('当前没有可结束的会话', true);
      const initial = await postJson(`/interviews/${target}/finish`, {}, { timeout: 60000 });
      localStorage.setItem(LAST_REPORT_KEY, String(target));
      if (initial.report_status === 'READY' && initial.report) {
        lastReport.value = initial.report;
        ui.toast('复盘报告已生成');
      } else {
        ui.toast('报告生成中，完成后自动刷新');
      }
      return initial;
    }, 'finish');
  }

  /* ---------------- 代码题运行（/run 沙箱直连，免鉴权） ---------------- */

  /** 调用沙箱运行一段代码（R-13：单次运行与全部样例共用） */
  async function runOne(source, stdin) {
    const r = await fetch('/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: code.language, code: source, stdin: stdin || '' }),
    });
    return r.json();
  }

  /** 输出比对：去行尾空白与 \r，避免格式差异误判 */
  const normOut = (s) => String(s == null ? '' : s).replace(/\r\n/g, '\n').trim();

  function runCode() {
    return ui.guard(async () => {
      const text = code.readCode ? code.readCode() : '';
      if (!text.trim()) return ui.toast('请先编写代码', true);
      code.output.runStatus = '运行中…';
      try {
        const res = await runOne(text, code.stdin || '');
        code.lastExecution = {
          language: code.language, stdin: code.stdin || '',
          stdout: res.stdout || '', stderr: res.stderr || res.error || '',
          exit_code: res.exit_code, timed_out: !!res.timed_out,
        };
        const out = [res.stdout && `【stdout】\n${res.stdout}`,
          res.stderr && `【stderr】\n${res.stderr}`,
          res.error && `【error】${res.error}`].filter(Boolean).join('\n') || '（无输出）';
        code.output.text = out;
        code.output.fail = !res.success;
        code.output.runStatus = res.success ? `✓ 运行成功（退出码 ${res.exit_code}）` : `✗ ${res.timed_out ? '超时' : '失败'}`;
      } catch (e) {
        code.output.runStatus = '';
        code.output.text = '运行服务不可用：' + e.message + '（请确认 code-runner 容器已启动）';
        code.output.fail = true;
      }
    });
  }

  /** 运行全部样例（R-13）：逐个调沙箱，记录 #/输入/期望/实际/结果 */
  function runAllCases() {
    return ui.guard(async () => {
      const text = code.readCode ? code.readCode() : '';
      if (!text.trim()) return ui.toast('请先编写代码', true);
      if (!code.testCases.length) return ui.toast('本题没有样例用例', true);
      const rows = [];
      let passed = 0;
      code.output.runStatus = '逐个运行样例…';
      for (let i = 0; i < code.testCases.length; i += 1) {
        const tc = code.testCases[i] || {};
        let res = {};
        try {
          res = await runOne(text, tc.input || '');
        } catch (e) {
          res = { error: e.message, exit_code: -1 };
        }
        const actual = String(res.stdout || '').trim() || String(res.stderr || res.error || '').trim();
        const pass = !res.timed_out && res.exit_code === 0 && normOut(res.stdout) === normOut(tc.expected);
        if (pass) passed += 1;
        rows.push({
          index: i + 1, input: tc.input || '', expected: tc.expected || '',
          actual, pass, timed_out: !!res.timed_out, exit_code: res.exit_code,
        });
      }
      code.results = rows;
      code.lastExecution = {
        language: code.language,
        stdin: rows.map((r) => r.input).join('\n---\n'),
        stdout: `样例通过 ${passed}/${rows.length}`,
        exit_code: passed === rows.length ? 0 : 1,
        timed_out: false,
        results: rows,
      };
      code.output.fail = passed < rows.length;
      code.output.runStatus = `样例通过 ${passed}/${rows.length}`;
      code.output.text = rows.map((r) =>
        `#${r.index} ${r.pass ? '✓ 通过' : (r.timed_out ? '✗ 超时' : '✗ 未通过')}\n输入: ${r.input}\n期望: ${r.expected}\n实际: ${r.actual}`,
      ).join('\n\n');
    }, 'runAll');
  }

  /* ---------------- 断点续面 ---------------- */
  function turnToQuestionData(t) {
    const coding = t.coding || {};
    return {
      question: t.question, topic: t.topic, difficulty: t.difficulty,
      is_follow_up: t.isFollowUp, question_type: t.questionType,
      source: t.questionSource, reference_points: t.referencePoints || [],
      starter_code: coding.starter_code || '', language: coding.language || 'java',
      test_cases: coding.test_cases || [],
      starter_codes: coding.starter_codes || {},
    };
  }

  /** 用 /state 快照重建聊天现场（R-01：一次请求拿到 session/turns/evaluations/开放轮次） */
  function applyState(st) {
    const s = st.session || {};
    const turns = st.turns || [];
    const evals = st.evaluations || [];
    scene.value = s.plan && s.plan.mode === 'GAUNTLET' ? 'gauntlet' : 'interview';
    planMeta.value = (s.plan && s.plan.options) || {};
    messages.value = [];
    exitCodeMode();
    draftError.value = null;
    submitState.value = 'idle';
    addMsg({
      role: 'system',
      text: `已同步${scene.value === 'gauntlet' ? '八股拷打' : '面试'}会话 #${sessionId.value} 的最新进度（人格：${s.persona || 'standard'}）`,
    });
    const evalByTurn = {};
    evals.forEach((e) => (evalByTurn[e.turnId] = e));
    let openTurn = null;
    for (const t of turns) {
      if (t.answer == null) { openTurn = t; continue; }
      addMsg({ role: 'interviewer', badge: t.speakerPersona, text: t.question });
      if (t.answerType === 'CODE') {
        addMsg({ role: 'candidate', kind: 'code', code: { shown: String(t.answer).slice(0, 600), exe: '' } });
      } else {
        addMsg({ role: 'candidate', text: t.answer });
      }
      if (t.interviewerReaction) {
        addMsg({ role: 'interviewer', badge: t.speakerPersona, text: t.interviewerReaction });
      }
      const e = evalByTurn[t.id];
      if (e) {
        addMsg({
          role: 'system', kind: 'eval_detail',
          evaluation: normalizeEvaluation(e),
          referencePoints: t.referencePoints || [],
          hintCount: t.hintCount || 0,
          decisionReason: '',
          turnId: t.id,
          bookmarked: wrongBookIds.value.has(t.id),
          defaultOpen: !!planMeta.value.study_mode,
        });
      }
    }
    if (openTurn) {
      turnNo.value = openTurn.turnNo - 1; // renderQuestion 会 +1
      renderQuestion(turnToQuestionData(openTurn));
      addMsg({ role: 'system', text: '↑ 请继续回答当前问题（接上文回放）' });
    } else {
      turnNo.value = turns.length;
      inputEnabled.value = false;
    }
    refreshWrongBookIds(); // 已收藏轮次回显（失败静默）
  }

  /** 用 state 重建聊天现场；有开放题则继续面试，否则不进入面试页 */
  async function restoreSession(id) {
    const st = await req('/interviews/' + id + '/state');
    const s = st.session;
    if (!s || s.status !== 'RUNNING') {
      localStorage.removeItem(SESSION_KEY);
      ui.toast('会话 #' + id + ' 已结束（' + (s ? s.status : '不存在') + '），可在历史中查看报告');
      return false;
    }
    sessionId.value = Number(id);
    applyState(st);
    router.push({ name: 'interview' });
    return true;
  }

  /** 顶栏「同步状态」：服务端已出报告就跳报告，否则对齐当前轮次 */
  async function syncState() {
    if (!sessionId.value) return ui.toast('当前没有进行中的会话', true);
    const st = await req('/interviews/' + sessionId.value + '/state');
    const s = st.session || {};
    const hasReport = s.report && typeof s.report === 'object' && Object.keys(s.report).length > 0;
    if (s.status === 'COMPLETED' || hasReport) {
      localStorage.removeItem(SESSION_KEY);
      localStorage.setItem(LAST_REPORT_KEY, String(sessionId.value));
      ui.toast('会话已结束，正在打开报告');
      router.push({ name: 'report', params: { id: sessionId.value } });
      return;
    }
    if (s.status !== 'RUNNING') {
      ui.toast('会话状态：' + (s.status || '未知'), true);
      return;
    }
    applyState(st);
    ui.toast('已同步到服务端最新状态');
  }

  /* ---------------- 登录后初始化（旧版 initMain） ---------------- */
  let bootstrapped = false;
  async function bootstrap() {
    if (bootstrapped) return;
    bootstrapped = true;
    await loadProfiles();
    await loadResumes();
    await loadJds();
    const manage = useManageStore();
    await manage.loadKnowledge();
    await manage.loadQuestions();
    checkHealth();
    // R-06：不再弹原生 confirm()，改为页面顶部非阻塞横幅（App.vue）
    const saved = localStorage.getItem(SESSION_KEY);
    if (saved && Number(saved) > 0) {
      pendingResume.value = { id: Number(saved) };
    }
  }

  /* 录音提交后错误也要清 loading 气泡：与旧版 guard 行为一致（旧版气泡已并入 handleDecision 前移除） */

  return {
    profileId, resumeId, jdId, profiles, resumes, jds,
    sessionId, scene, turnNo, hintLevel, mode, messages, inputEnabled, status, lastReport,
    focusTick, recording, code, interviewOptions, planMeta,
    submitState, draft, draftError, currentReferencePoints, currentHintCount,
    gap, gapLoading, gapError, pendingResume,
    rt, recorder,
    loadProfiles, createProfile, loadResumes, loadJds, uploadResume, parseJdText, uploadJd,
    fetchProfile, saveProfile, loadProfileGrowth,
        fetchAiConfig, saveAiConfig, testAiConfig,
    deleteProfile, deleteResume, deleteJd,
    reparseResume, reparseJd, analyzeGap,
    renameSession, deleteSession, resumePending, dismissPending,
    checkHealth, addMsg, removeMsg,
    startInterview, startGauntlet, submitTextAnswer, submitCodeAnswer, requestHint, interject, finishInterview,
    finishSession, retrySubmit, retryEvaluation, syncState,
    runCode, runAllCases, setCodeLanguage, resetCode,
    saveCodeDraft, loadCodeDraft, clearCodeDraft,
    restoreSession, bootstrap,
    wrongBookIds, refreshWrongBookIds, addToWrongBook, bookmarkMessage,
    gauntletSource, gauntletTags,
  };
});

export { INJECT_LABELS };
