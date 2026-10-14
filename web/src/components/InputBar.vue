<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed } from 'vue';
import { Send, Lightbulb, Mic, Square, Radio, AlertCircle, RotateCw, X, SkipForward, Shuffle } from 'lucide-vue-next';
import { useSessionStore } from '../stores/session';
import { useUiStore } from '../stores/ui';
import { classifyError } from '../utils/errors';

const session = useSessionStore();
const ui = useUiStore();
const inputEl = ref(null);

const isRealtime = () => session.mode === 'REALTIME';
// 八股场景专属：跳过/换题（R-11），会消耗 1 题预算且不计分
const isGauntlet = () => session.scene === 'gauntlet';
const canInterject = () => isGauntlet() && !session.code.active && session.inputEnabled;

// 提交失败横幅（R-19）：错误分类后统一文案（离线/超时/上游/业务校验）
const draftErrorInfo = computed(() => (session.draftError ? classifyError(session.draftError) : null));

// 草稿由 store 持有（v-model 直接绑 session.draft）：失败重发与刷新恢复都靠它
function send() {
  if (session.code.active) {
    session.submitCodeAnswer();
    return;
  }
  session.submitTextAnswer(session.draft);
}

function onKeydown(e) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send();
}

// 快捷键（R-19）：Ctrl/Cmd+H 请求提示；Ctrl/Cmd+S 跳过本题（八股场景需确认）
function onGlobalKey(e) {
  if (ui.confirmState) return; // 弹窗打开时交给 ConfirmDialog 处理
  if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
  const k = (e.key || '').toLowerCase();
  if (k === 'h') {
    e.preventDefault();
    if (session.inputEnabled) session.requestHint();
  } else if (k === 's') {
    e.preventDefault();
    skipCurrent();
  }
}
onMounted(() => window.addEventListener('keydown', onGlobalKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey));

// 跳过本题（不计分、消耗 1 题预算）：按钮与 Ctrl+S 共用，先确认再提交
async function skipCurrent() {
  if (!canInterject()) return ui.toast('当前不可跳过（仅八股答题中可用）', true);
  const ok = await ui.ask({
    title: '跳过本题',
    message: '本题不计分，但会消耗 1 题预算（总题量不变）。',
    confirmText: '跳过',
  });
  if (ok) session.interject('skip');
}

// 非代码题下发后聚焦输入框（旧版 renderQuestion 末尾的 answer-input.focus()）
watch(() => session.focusTick, async () => {
  await nextTick();
  if (!session.code.active && inputEl.value) inputEl.value.focus();
});

// 提交失败：输入框恢复可用后把焦点交回，便于直接修改或重试
watch(() => session.submitState, async (v) => {
  if (v !== 'failed') return;
  await nextTick();
  if (!session.code.active && inputEl.value) inputEl.value.focus();
});

// 重录（R-15）：丢弃当前预览后重新开始录音
function retakeVoice() {
  session.recorder.discard();
  session.recorder.start();
}
</script>

<template>
  <div>
    <!-- 提交失败横幅（R-01/R-19）：保留草稿，按错误类型决定是否提供重试 -->
    <div v-if="session.draftError && draftErrorInfo" class="submit-error" role="alert">
      <span class="ic"><AlertCircle :size="15" /></span>
      <div class="se-body">
        <div>{{ draftErrorInfo.title }}</div>
        <small v-if="draftErrorInfo.hint">{{ draftErrorInfo.hint }}</small>
        <small v-if="draftErrorInfo.kind === 'unknown'">本次提交未生效，可修改回答后重新发送</small>
      </div>
      <button
        v-if="session.draftError.retryable"
        class="ghost btn-ic"
        @click="session.retrySubmit()"
      >
        <span class="ic"><RotateCw :size="13" /></span>重试
      </button>
      <button class="icon-btn" title="关闭提示" @click="session.draftError = null">
        <span class="ic"><X :size="14" /></span>
      </button>
    </div>

    <div class="composer-card">
      <textarea
        v-show="!session.code.active"
        ref="inputEl"
        v-model="session.draft"
        rows="3"
        placeholder="输入你的回答…（提交失败会保留草稿）"
        :disabled="!session.inputEnabled"
        @keydown="onKeydown"
      ></textarea>
      <!-- 录音试听与确认（R-15）：停止录音不再直接上传，先试听再决定发送/重录 -->
      <div v-if="!session.code.active && session.recorder.previewUrl" class="voice-preview">
        <audio controls preload="metadata" :src="session.recorder.previewUrl"></audio>
        <button class="primary btn-ic" :disabled="!session.inputEnabled" @click="session.recorder.send()">
          <span class="ic"><Send :size="14" /></span>发送语音
        </button>
        <button class="ghost btn-ic" @click="retakeVoice">
          <span class="ic"><RotateCw :size="14" /></span>重录
        </button>
      </div>
      <div class="composer-foot">
        <!-- 与 legacy 一致：提交按钮在代码题态同样常显，读取编辑器内容提交（修复迁移期被 v-show 掉的提交入口） -->
        <button class="primary btn-ic" :disabled="!session.inputEnabled" @click="send">
          <span class="ic"><Send :size="15" /></span>{{ session.code.active ? '提交代码作答' : '提交回答' }}
        </button>
        <button
          v-show="canInterject()"
          class="ghost btn-ic"
          title="跳过当前题目（不计分，但消耗 1 题预算 · Ctrl+S）"
          @click="skipCurrent"
        >
          <span class="ic"><SkipForward :size="15" /></span>跳过本题
        </button>
        <button
          v-show="canInterject()"
          class="ghost btn-ic"
          title="换一个同主题的题目（本题不计分，消耗 1 题预算）"
          @click="session.interject('request_question')"
        >
          <span class="ic"><Shuffle :size="15" /></span>换一题
        </button>
        <button class="ghost btn-ic" title="卡住时向面试官请求分级提示（方向→关键点→揭晓思路），使用提示会计入评测降档 · Ctrl+H" @click="session.requestHint()">
          <span class="ic"><Lightbulb :size="15" /></span>请求提示
        </button>
        <button v-show="!session.code.active && !isRealtime() && !session.recording && !session.recorder.previewUrl" class="icon-btn" title="语音回答（录完可试听确认）" aria-label="语音回答" @click="session.recorder.start()">
          <span class="ic"><Mic :size="15" /></span>
        </button>
        <!-- 录音中：计时 + 音量峰值 + 取消/停止（R-15） -->
        <span v-if="session.recording" class="rec-state">
          <span class="rec-dot" aria-hidden="true"></span>
          <span class="rec-time">{{ session.recorder.elapsedText }}</span>
          <span class="vol-bar" aria-hidden="true">
            <span class="vol-fill" :style="{ width: Math.round(session.recorder.level * 100) + '%' }"></span>
          </span>
          <button class="icon-btn" title="取消录音（不再上传）" aria-label="取消录音" @click="session.recorder.discard()">
            <span class="ic"><X :size="14" /></span>
          </button>
          <button class="btn-ic" title="停止录音并试听" @click="session.recorder.stop()">
            <span class="ic"><Square :size="14" /></span>停止
          </button>
        </span>
        <button v-show="isRealtime()" class="btn-ic" title="开始/停止实时语音（停顿后写入输入框，需手动提交）" @click="session.rt.toggle()">
          <span class="ic"><Radio :size="15" /></span>{{ session.rt.btnLabel }}
        </button>
        <span class="spacer"></span>
        <span class="kbd">Ctrl + Enter</span>
      </div>
    </div>
    <!-- 实时转写面板（对应旧版 #rt-live）；rt 内的 refs 经 Pinia state 包裹后自动解包 -->
    <div v-show="isRealtime()">
      <div id="rt-live-text">{{ session.rt.liveText }}</div>
      <div class="realtime-bar">
        <span class="mini">
          <span v-if="session.rt.statusDot" class="rt-dot"></span>
          {{ session.rt.statusText }}
        </span>
      </div>
    </div>
  </div>
</template>
