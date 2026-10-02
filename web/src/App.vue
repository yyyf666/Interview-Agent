<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { History, Play, X, WifiOff, RotateCw } from 'lucide-vue-next';
import AuthGate from './components/AuthGate.vue';
import TopBar from './components/TopBar.vue';
import Toast from './components/Toast.vue';
import ConfirmDialog from './components/ConfirmDialog.vue';
import { useAuthStore } from './stores/auth';
import { useUiStore } from './stores/ui';
import { useSessionStore } from './stores/session';
import { getToken, onUnauthorized, onTokenRefreshed } from './api/client';
import router from './router';
import { setupReveal } from './utils/reveal';

const auth = useAuthStore();
const ui = useUiStore();
const session = useSessionStore();

let healthTimer = null;

// 离线横幅（R-19 §7.4）：断网提示 + 恢复后手动同步一次，不自动重放写操作
const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine);
function onOnline() {
  online.value = true;
  ui.toast('网络已恢复，可点击同步状态或重试提交');
}
function onOffline() {
  online.value = false;
}

// 401 统一回登录门禁（req() 内触发，与旧版 showAuthGate 等价）
onMounted(() => {
  setupReveal(router); // 视口淡入：路由切换后对主容器卡片状区块挂 .reveal
  onUnauthorized(() => { auth.user = null; });
  onTokenRefreshed((data) => auth.applyRefreshed(data));
  if (getToken()) {
    ui.guard(async () => {
      await auth.fetchMe(); // token 失效时 req 会自动回到登录门禁
      await session.bootstrap();
    });
  }
  // 与旧版一致：15s 轮询服务健康状态（登录态才查）
  healthTimer = setInterval(() => { if (getToken()) session.checkHealth(); }, 15000);
  window.addEventListener('online', onOnline);
  window.addEventListener('offline', onOffline);
});
onUnmounted(() => {
  clearInterval(healthTimer);
  window.removeEventListener('online', onOnline);
  window.removeEventListener('offline', onOffline);
});

// 登录/注册成功后初始化主流程（旧版 auth-submit 末尾的 initMain()）
watch(() => auth.loggedIn, (v) => { if (v) session.bootstrap(); });
</script>

<template>
  <AuthGate v-if="!auth.loggedIn" />
  <TopBar />
  <!-- 离线横幅（R-19 §7.4）：断网时非阻塞提示，恢复后可手动同步 -->
  <div v-if="auth.loggedIn && !online" class="offline-banner" role="alert">
    <span class="ic"><WifiOff :size="15" /></span>
    <span>网络已断开，提交与刷新会失败；草稿与进度已保留。</span>
    <span class="spacer"></span>
    <button class="ghost btn-ic" @click="ui.guard(() => session.syncState(), 'sync')">
      <span class="ic"><RotateCw :size="13" /></span>重连后同步
    </button>
  </div>
  <!-- 未完成会话非阻塞横幅（R-06）：替代启动时的原生 confirm() -->
  <div v-if="auth.loggedIn && session.pendingResume" class="resume-banner">
    <span class="ic"><History :size="15" /></span>
    <span>检测到未完成的会话 #{{ session.pendingResume.id }}</span>
    <span class="grow"></span>
    <button class="primary btn-ic" @click="session.resumePending()">
      <span class="ic"><Play :size="13" /></span>继续
    </button>
    <button class="ghost btn-ic" @click="session.dismissPending()">
      <span class="ic"><X :size="13" /></span>放弃
    </button>
  </div>
  <main>
    <router-view v-slot="{ Component }">
      <Transition name="fade" mode="out-in">
        <component :is="Component" />
      </Transition>
    </router-view>
  </main>
  <Toast />
  <!-- 统一确认弹窗（R-19）：全站替代原生 confirm() -->
  <ConfirmDialog />
</template>
