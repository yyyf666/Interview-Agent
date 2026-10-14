// 非实时语音（R-15）：整段录音先本地试听确认，再手动发送；录音中提供计时与音量峰值。
// 历史行为（停止即上传）已移除，改为 pendingBlob + previewUrl + send()/discard()。
import { computed, ref } from 'vue';

const API = '/api';

export function createAudioRecorder(deps) {
  const recording = ref(false);
  const elapsed = ref(0);          // 录音时长（秒）
  const level = ref(0);            // 音量峰值 0~1，仅驱动音量条
  const pendingBlob = ref(null);   // 停止后待发送的音频
  const previewUrl = ref('');      // URL.createObjectURL，试听/发送/丢弃后都要释放

  let rec = null;
  let chunks = [];
  let stream = null;
  let timer = null;
  let audioCtx = null;
  let analyser = null;
  let wave = null;
  let volTimer = null;
  let discardNext = false;

  const elapsedText = computed(() => {
    const m = Math.floor(elapsed.value / 60);
    const s = elapsed.value % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
  });

  function reset() {
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value);
      previewUrl.value = '';
    }
    pendingBlob.value = null;
  }

  function startTimer() {
    elapsed.value = 0;
    clearInterval(timer);
    timer = setInterval(() => { elapsed.value += 1; }, 1000);
  }

  function stopTimer() {
    clearInterval(timer);
    timer = null;
  }

  /* 音量峰值：AnalyserNode 时域波形取绝对值最大值，120ms 刷新一次（够顺滑且省 CPU） */
  function startMeter(srcStream) {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    try {
      audioCtx = new Ctx();
      const src = audioCtx.createMediaStreamSource(srcStream);
      analyser = audioCtx.createAnalyser();
      analyser.fftSize = 512;
      src.connect(analyser);
      wave = new Uint8Array(analyser.fftSize);
      volTimer = setInterval(() => {
        analyser.getByteTimeDomainData(wave);
        let peak = 0;
        for (let i = 0; i < wave.length; i++) {
          const v = Math.abs(wave[i] - 128);
          if (v > peak) peak = v;
        }
        level.value = Math.min(1, peak / 96);
      }, 120);
    } catch {
      /* 音量条为增强信息，取不到就不显示 */
    }
  }

  function stopMeter() {
    clearInterval(volTimer);
    volTimer = null;
    if (audioCtx) {
      try { audioCtx.close(); } catch { /* noop */ }
      audioCtx = null;
    }
    analyser = null;
    wave = null;
    level.value = 0;
  }

  function cleanupStream() {
    if (stream) {
      stream.getTracks().forEach((t) => t.stop());
      stream = null;
    }
  }

  async function start() {
    if (recording.value) return;
    reset();
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      chunks = [];
      discardNext = false;
      rec = new MediaRecorder(stream, { mimeType: 'audio/webm' });
      rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
      rec.onstop = () => {
        cleanupStream();
        stopTimer();
        stopMeter();
        recording.value = false;
        deps.onRecordingChange(false);
        const blob = new Blob(chunks, { type: 'audio/webm' });
        chunks = [];
        if (discardNext) { discardNext = false; return; }
        if (!blob.size) return;
        pendingBlob.value = blob;
        previewUrl.value = URL.createObjectURL(blob);
      };
      rec.start();
      recording.value = true;
      deps.onRecordingChange(true);
      startTimer();
      startMeter(stream);
    } catch (e) {
      cleanupStream();
      deps.onToast('麦克风不可用: ' + e.message, true);
    }
  }

  /** 结束录音：只生成本地预览，不上传 */
  function stop() {
    if (rec && rec.state !== 'inactive') rec.stop();
  }

  /** 取消录音/丢弃预览：释放 blob URL，不再上传 */
  function discard() {
    if (rec && rec.state !== 'inactive') {
      discardNext = true;
      rec.stop();
    }
    reset();
  }

  /** 手动发送待确认录音：失败保留预览，用户可重试发送或重录 */
  async function send() {
    const blob = pendingBlob.value;
    if (!blob) return deps.onToast('没有可发送的录音', true);
    await deps.guard(async () => {
      deps.onSubmitting();
      const fd = new FormData();
      fd.append('file', blob, 'answer.webm');
      const r = await fetch(API + `/interviews/${deps.getSessionId()}/audio-answers`, {
        method: 'POST',
        body: fd,
        headers: deps.getToken() ? { Authorization: 'Bearer ' + deps.getToken() } : {},
      });
      const body = await r.json();
      if (!r.ok || body.code !== 0) throw new Error(body.message || '语音提交失败');
      reset();
      deps.onDecision(body.data);
    });
  }

  return { recording, elapsed, elapsedText, level, pendingBlob, previewUrl, start, stop, send, discard };
}
