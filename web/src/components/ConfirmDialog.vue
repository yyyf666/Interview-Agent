<script setup>
import { ref, watch, nextTick, onUnmounted } from 'vue';
import { AlertTriangle, HelpCircle } from 'lucide-vue-next';
import { useUiStore } from '../stores/ui';

/**
 * 统一确认弹窗（R-19）：替代 5 处原生 confirm()。
 * 用法：await ui.ask({ title, message, confirmText, danger }) → true/false。
 * 支持 requireText（如输入 DELETE 才允许确认）；Esc 取消、Enter 确认、Tab 焦点回路。
 */
const ui = useUiStore();
const dialogEl = ref(null);
const typed = ref('');

const focusables = () => {
  const el = dialogEl.value;
  if (!el) return [];
  return [...el.querySelectorAll('button, input, [tabindex]:not([tabindex="-1"])')]
    .filter((n) => !n.disabled);
};

function onKeydown(e) {
  const st = ui.confirmState;
  if (!st) return;
  if (e.key === 'Escape') {
    e.preventDefault();
    e.stopPropagation();
    ui.answerConfirm(false);
    return;
  }
  if (e.key === 'Enter') {
    // 输入确认词时，Enter 等同于点击确认（输入未匹配时保持弹窗）
    e.preventDefault();
    e.stopPropagation();
    submit();
    return;
  }
  if (e.key === 'Tab') {
    const list = focusables();
    if (!list.length) return;
    const first = list[0];
    const last = list[list.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }
}

function submit() {
  const st = ui.confirmState;
  if (!st) return;
  if (st.requireText && typed.value.trim() !== st.requireText) {
    ui.toast(`请输入 ${st.requireText} 以确认`, true);
    return;
  }
  ui.answerConfirm(true);
}

watch(() => ui.confirmState, async (v) => {
  if (v) {
    typed.value = '';
    window.addEventListener('keydown', onKeydown, true);
    await nextTick();
    const list = focusables();
    const target = list.find((n) => n.classList.contains('danger') || n.classList.contains('primary')) || list[0];
    if (target) target.focus();
  } else {
    window.removeEventListener('keydown', onKeydown, true);
  }
});

onUnmounted(() => window.removeEventListener('keydown', onKeydown, true));
</script>

<template>
  <Teleport to="body">
    <div v-if="ui.confirmState" class="dialog-mask" @click.self="ui.answerConfirm(false)">
      <div
        ref="dialogEl"
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="ui.confirmState.title"
      >
        <div class="cd-head">
          <span class="ic" :class="ui.confirmState.danger ? 'err' : 'ok'">
            <component :is="ui.confirmState.danger ? AlertTriangle : HelpCircle" :size="17" />
          </span>
          <strong>{{ ui.confirmState.title }}</strong>
        </div>
        <p v-if="ui.confirmState.message" class="cd-msg">{{ ui.confirmState.message }}</p>
        <input
          v-if="ui.confirmState.requireText"
          v-model="typed"
          class="cd-input"
          :placeholder="`输入 ${ui.confirmState.requireText} 确认`"
          :aria-label="`输入 ${ui.confirmState.requireText} 确认`"
        />
        <div class="cd-actions">
          <button class="ghost btn-ic" @click="ui.answerConfirm(false)">
            {{ ui.confirmState.cancelText }}
          </button>
          <button
            class="btn-ic"
            :class="ui.confirmState.danger ? 'danger' : 'primary'"
            :disabled="!!ui.confirmState.requireText && typed.trim() !== ui.confirmState.requireText"
            @click="submit"
          >
            {{ ui.confirmState.confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
