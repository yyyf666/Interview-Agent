import { ref, computed } from 'vue';
import { defineStore } from 'pinia';
import {
  req,
  postJson,
  setToken,
  setRefreshToken,
  getRefreshToken,
  TOKEN_KEY,
  REFRESH_KEY,
  SESSION_KEY,
} from '../api/client';

// 登录/注册/退出/当前用户 —— 对应旧版「登录/注册」与 DOMContentLoaded 校验段
export const useAuthStore = defineStore('auth', () => {
  const user = ref(null);
  const loggedIn = computed(() => !!user.value);
  // 管理入口（知识库/题库、AI 配置）仅 ADMIN 可见；角色由后端 JWT claim 下发
  const isAdmin = computed(() => !!user.value && user.value.role === 'ADMIN');

  /** mode: 'login' | 'register'；成功后访问令牌与 refresh token 均已写入 */
  async function submit(mode, { username, password, nickname }) {
    const data = await postJson(mode === 'login' ? '/auth/login' : '/auth/register', {
      username,
      password,
      nickname: mode === 'register' ? (nickname || null) : null,
    });
    setToken(data.token);
    setRefreshToken(data.refresh_token);
    user.value = data;
    return data;
  }

  /** 刷新后用已存 token 校验身份；失效时 req() 会先静默续期，再回门禁 */
  async function fetchMe() {
    user.value = await req('/auth/me');
  }

  /** 续期成功后同步角色/昵称等易变字段 */
  function applyRefreshed(data) {
    if (!user.value) return;
    user.value = { ...user.value, ...data };
  }

  async function logout() {
    const rt = getRefreshToken();
    // 先吊销 refresh token（失败不阻断本地清理）
    if (rt) {
      try {
        await postJson('/auth/logout', { refreshToken: rt });
      } catch {
        /* 忽略：本地清理优先 */
      }
    }
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(SESSION_KEY);
    setToken(null);
    setRefreshToken(null);
    location.reload(); // 与旧版一致：整页重载回到登录门禁
  }

  return { user, loggedIn, isAdmin, submit, fetchMe, applyRefreshed, logout };
});
