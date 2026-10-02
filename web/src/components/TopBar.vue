<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ClipboardList, MessageSquare, Swords, BarChart3, History, Library, LogOut, UserRound, ChevronDown, Settings, BookOpen, Sun, Moon } from 'lucide-vue-next';
import BrandMark from './BrandMark.vue';
import { useAuthStore } from '../stores/auth';
import { useUiStore } from '../stores/ui';
import { theme, toggleTheme } from '../utils/theme';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const ui = useUiStore();

// 与旧版五个 tab 一一对应（setup/interview/report/history/manage），仅加图标语言
const allTabs = [
  { name: 'setup', label: '准备', icon: ClipboardList },
  { name: 'interview', label: '面试', icon: MessageSquare },
  { name: 'gauntlet', label: '八股拷打', icon: Swords },
  { name: 'report', label: '复盘报告', icon: BarChart3 },
  { name: 'history', label: '历史与成长', icon: History },
  { name: 'manage', label: '知识库/题库', icon: Library, adminOnly: true },
];

// 管理类入口仅 ADMIN 可见（后端已用 @RequireRole 拦截，前端仅做可见性收敛）
const tabs = computed(() => allTabs.filter((t) => !t.adminOnly || auth.isAdmin));

function go(t) {
  router.push({ name: t.name });
}

const user = computed(() => auth.user ? (auth.user.nickname || auth.user.username || '') : '');
const initial = computed(() => (user.value.slice(0, 1) || '?').toUpperCase());

// 用户下拉菜单：菜单/遮罩 teleport 到 body（顶栏 backdrop-filter 会为 fixed 后代创建包含块，
// 就地渲染遮罩会被裁剪在顶栏内），open 时按胶囊坐标定位
const menuOpen = ref(false);
const chipBtn = ref(null);
const menuStyle = ref({});
function openMenu() {
  const r = chipBtn.value.getBoundingClientRect();
  menuStyle.value = { top: r.bottom + 8 + 'px', right: window.innerWidth - r.right + 'px' };
  menuOpen.value = true;
}
function closeMenu() { menuOpen.value = false; }
function goProfiles() {
  closeMenu();
  router.push({ name: 'profiles' });
}
function goWrongBook() {
  closeMenu();
  router.push({ name: 'wrong-book' });
}
function goSettings() {
  closeMenu();
  router.push({ name: 'ai-settings' });
}
function doLogout() {
  closeMenu();
  auth.logout();
}

// 主题切换（R-19）：深色/浅色写入 localStorage，刷新后保持
function switchTheme() {
  toggleTheme();
  closeMenu();
}

// Esc 关闭用户菜单（R-19 快捷键统一）
function onEsc(e) {
  if (e.key === 'Escape' && menuOpen.value) closeMenu();
}
onMounted(() => window.addEventListener('keydown', onEsc));
onUnmounted(() => window.removeEventListener('keydown', onEsc));
</script>

<template>
  <header class="topbar">
    <div class="brand-block">
      <BrandMark />
      <span class="brand">智能自适应 AI 面试系统</span>
    </div>
    <nav class="tabs" aria-label="主导航">
      <button
        v-for="t in tabs"
        :key="t.name"
        class="tab"
        :class="{ active: route.name === t.name }"
        @click="go(t)"
      >
        <span class="ic"><component :is="t.icon" :size="15" /></span>{{ t.label }}
      </button>
    </nav>
    <span class="conn-pill" :class="{ online: ui.connOnline }" title="服务状态" :aria-label="ui.connOnline ? '服务在线' : '服务离线'">
      <span class="conn-dot"></span>{{ ui.connOnline ? '服务在线' : '服务离线' }}
    </span>
    <span class="user-chip" v-if="auth.loggedIn">
      <button ref="chipBtn" class="chip-btn" :class="{ open: menuOpen }" title="用户菜单" aria-haspopup="menu" :aria-expanded="menuOpen" aria-label="用户菜单" @click="openMenu()">
        <span class="avatar">{{ initial }}</span>
        <span class="conn-name">{{ user }}</span>
        <span class="ic"><ChevronDown :size="14" /></span>
      </button>
      <Teleport to="body">
        <template v-if="menuOpen">
          <span class="menu-mask" @click="closeMenu()"></span>
          <span class="user-menu" :style="menuStyle">
            <button v-if="auth.isAdmin" class="menu-item" @click="goSettings()">
              <span class="ic"><Settings :size="15" /></span>AI 配置
            </button>
            <button class="menu-item" @click="goProfiles()">
              <span class="ic"><UserRound :size="15" /></span>候选人画像
            </button>
            <button class="menu-item" @click="goWrongBook()">
              <span class="ic"><BookOpen :size="15" /></span>错题本
            </button>
            <button class="menu-item" @click="switchTheme()">
              <span class="ic"><Sun v-if="theme === 'dark'" :size="15" /><Moon v-else :size="15" /></span>{{ theme === 'dark' ? '浅色模式' : '深色模式' }}
            </button>
            <span class="menu-sep"></span>
            <button class="menu-item danger" @click="doLogout()">
              <span class="ic"><LogOut :size="15" /></span>退出登录
            </button>
          </span>
        </template>
      </Teleport>
    </span>
    <span class="user-chip" v-else-if="auth.user">
      <span class="conn-name">{{ user }}</span>
    </span>
  </header>
</template>
