// 实时语音：getUserMedia → 16k 单声道 PCM16 帧 → /api/ws/asr 流式转写；静默 2.5s 后写入输入框待确认（R-15）
// 不再自动提交，避免误触发送；deps 由 session store 注入，避免 store 间循环引用
import { ref } from 'vue';

export function createRealtimeVoice(deps) {
  const active = ref(false);
  const liveText = ref('');     // 对应 #rt-live-text
  const statusText = ref('');   // 对应 #rt-status 文本
  const statusDot = ref(false); // 对应 #rt-status 内的 .rt-dot
  const btnLabel = ref('实时对话');

  let ws = null;
  let ctx = null;
  let stream = null;
  let proc = null;
  let silenceTimer = null;
  let finalText = '';

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

  function connectRtWs() {
    const url = rtProto() + location.host + '/api/ws/asr?token=' + encodeURIComponent(deps.getToken() || '');
    ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    ws.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      if (m.type === 'error') {
        deps.onToast('语音识别：' + (m.text || '错误'), true);
        statusText.value = '识别中断，可重试';
        statusDot.value = false;
        return;
      }
      if (m.type === 'final') {
        finalText += (finalText ? ' ' : '') + m.text;
        liveText.value = finalText;
        scheduleRtSubmit();
      } else {
        liveText.value = finalText + (m.text ? ('\n…' + m.text) : '');
      }
    };
    ws.onclose = () => { statusText.value = ''; statusDot.value = false; };
  }

  /* 静默 2.5s 后把已定段的整段文字交给输入框，由用户确认后再提交（R-15） */
  function scheduleRtSubmit() {
    clearTimeout(silenceTimer);
    silenceTimer = setTimeout(() => {
      const text = finalText.trim();
      if (!text || !deps.getSessionId()) return;
      finalText = '';
      liveText.value = '';
      deps.onTranscript(text);
    }, 2500);
  }

  async function start() {
    if (!deps.getSessionId()) return deps.onToast('请先开始面试', true);
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      });
      const Ctx = window.AudioContext || window.webkitAudioContext;
      ctx = new Ctx();
      const src = ctx.createMediaStreamSource(stream);
      proc = ctx.createScriptProcessor(4096, 1, 1);
      const targetRate = 16000;
      proc.onaudioprocess = (e) => {
        const inp = e.inputBuffer.getChannelData(0);
        const pcm = toPCM16(inp, ctx.sampleRate, targetRate);
        if (ws && ws.readyState === WebSocket.OPEN && pcm.length) ws.send(pcm.buffer);
      };
      src.connect(proc);
      proc.connect(ctx.destination);
      connectRtWs();
      active.value = true;
      btnLabel.value = '停止实时';
      statusDot.value = true;
      statusText.value = '聆听中…（停顿后写入输入框）';
    } catch (e) {
      deps.onToast('麦克风不可用: ' + e.message, true);
    }
  }

  function stop() {
    if (!active.value && !ws && !ctx) return;
    active.value = false;
    clearTimeout(silenceTimer);
    if (proc) { try { proc.disconnect(); } catch { /* noop */ } proc.onaudioprocess = null; proc = null; }
    if (ctx) { try { ctx.close(); } catch { /* noop */ } ctx = null; }
    if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null; }
    if (ws) { try { ws.send('finish'); ws.close(); } catch { /* noop */ } ws = null; }
    btnLabel.value = '实时对话';
    statusText.value = '';
    statusDot.value = false;
  }

  function toggle() {
    if (active.value) stop(); else start();
  }

  return { active, liveText, statusText, statusDot, btnLabel, start, stop, toggle };
}
