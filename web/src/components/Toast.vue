<script setup>
import { CheckCircle2, AlertTriangle } from 'lucide-vue-next';
import { useUiStore } from '../stores/ui';

const ui = useUiStore();
</script>

<template>
  <div class="toast" :class="{ show: ui.toastShow, error: ui.toastError }" role="status" aria-live="polite">
    <span class="ic" :class="ui.toastError ? 'err' : 'ok'">
      <component :is="ui.toastError ? AlertTriangle : CheckCircle2" :size="16" />
    </span>
    {{ ui.toastText }}
  </div>
</template>
