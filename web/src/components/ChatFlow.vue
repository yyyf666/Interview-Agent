<script setup>
import { ref, watch, nextTick } from 'vue';
import MsgBubble from './MsgBubble.vue';
import { useSessionStore } from '../stores/session';

const session = useSessionStore();
const el = ref(null);

// 与旧版 addMsg() 末尾的 scrollTop 拉底一致
watch(() => session.messages.length, async () => {
  await nextTick();
  if (el.value) el.value.scrollTop = el.value.scrollHeight;
});
</script>

<template>
  <div ref="el" class="chat">
    <MsgBubble
      v-for="m in session.messages"
      :key="m.id"
      :msg="m"
      @retry="session.retrySubmit()"
      @bookmark="session.bookmarkMessage"
    />
  </div>
</template>
