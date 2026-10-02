<script setup>
import { ref } from 'vue';
import { User, Lock, Smile, Wand2, LineChart, Code2 } from 'lucide-vue-next';
import BrandMark from './BrandMark.vue';
import { useAuthStore } from '../stores/auth';
import { useUiStore } from '../stores/ui';

const auth = useAuthStore();
const ui = useUiStore();

const mode = ref('login');
const username = ref('');
const password = ref('');
const nickname = ref('');

function submit() {
  ui.guard(async () => {
    const name = username.value.trim();
    if (!name) return ui.toast('请输入用户名', true);
    await auth.submit(mode.value, {
      username: name,
      password: password.value,
      nickname: nickname.value.trim(),
    });
    ui.toast(mode.value === 'login' ? '欢迎回来' : '注册成功，已自动创建候选人画像');
  });
}
</script>

<template>
  <div class="auth-gate">
    <div class="auth-shell">
      <aside class="auth-hero">
        <div class="ah-top">
          <BrandMark :icon-size="22" />
          <span class="brand" style="font-size:16px">智能自适应 AI 面试系统</span>
        </div>
        <div>
          <div class="ah-title">像真实面试一样，<br>一轮一轮地变强</div>
          <p class="ah-sub">简历-JD 缺口分析驱动出题，答完即评，练到点上。</p>
        </div>
        <div class="auth-feat">
          <span class="ic"><Wand2 :size="17" /></span>
          <div><div class="af-t">自适应出题</div><div class="af-d">难度与主题随每轮表现实时重规划</div></div>
        </div>
        <div class="auth-feat">
          <span class="ic"><LineChart :size="17" /></span>
          <div><div class="af-t">证据化复盘</div><div class="af-d">六维评测 + 要点命中 + 你的原话引用</div></div>
        </div>
        <div class="auth-feat">
          <span class="ic"><Code2 :size="17" /></span>
          <div><div class="af-t">代码题沙箱</div><div class="af-d">边写边运行，按用例执行结果评分</div></div>
        </div>
      </aside>
      <div class="auth-form">
        <h2>{{ mode === 'login' ? '登录' : '注册' }}</h2>
        <p class="af-lead">{{ mode === 'login' ? '欢迎回来，开始今天的面试训练。' : '创建账号，自动获得专属候选人画像。' }}</p>
        <div class="auth-tabs">
          <button :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
          <button :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
        </div>
        <div class="input-ic">
          <span class="ic"><User :size="15" /></span>
          <input v-model="username" placeholder="用户名" autocomplete="username" @keyup.enter="submit" />
        </div>
        <div class="input-ic">
          <span class="ic"><Lock :size="15" /></span>
          <input v-model="password" type="password" placeholder="密码（至少6位）" autocomplete="current-password" @keyup.enter="submit" />
        </div>
        <div v-if="mode === 'register'" class="input-ic">
          <span class="ic"><Smile :size="15" /></span>
          <input v-model="nickname" placeholder="昵称（注册选填）" @keyup.enter="submit" />
        </div>
        <button class="primary" @click="submit">{{ mode === 'login' ? '登录' : '注册并开始' }}</button>
        <p class="hint">注册后自动拥有专属候选人画像；本地演示也可用预置账号 demo / demo1234。</p>
      </div>
    </div>
  </div>
</template>
