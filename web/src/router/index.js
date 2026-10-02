import { createRouter, createWebHashHistory } from 'vue-router';

// hash 模式：免改 nginx try_files，静态挂载下深链天然可用
const routes = [
  { path: '/', name: 'setup', component: () => import('../views/SetupView.vue') },
  { path: '/interview', name: 'interview', component: () => import('../views/InterviewView.vue') },
  { path: '/gauntlet', name: 'gauntlet', component: () => import('../views/GauntletView.vue') },
  // 报告深链（R-02）：/report/:id 可刷新/分享，省略 id 时回退最近一次报告
  { path: '/report/:id?', name: 'report', component: () => import('../views/ReportView.vue') },
  { path: '/history', name: 'history', component: () => import('../views/HistoryView.vue') },
  // 错题本（R-09）：入口在顶栏用户菜单与历史页，不占 tab
  { path: '/wrong-book', name: 'wrong-book', component: () => import('../views/WrongBookView.vue') },
  { path: '/manage', name: 'manage', component: () => import('../views/ManageView.vue') },
  // 候选人画像页：入口在顶栏用户下拉菜单，不占 tab
  { path: '/profiles', name: 'profiles', component: () => import('../views/ProfilesView.vue') },
  { path: '/profiles/:id', name: 'profile-detail', component: () => import('../views/ProfileDetailView.vue') },
  // AI 配置页：入口在顶栏用户下拉菜单，不占 tab
  { path: '/settings/ai', name: 'ai-settings', component: () => import('../views/SettingsView.vue') },
];

export default createRouter({
  history: createWebHashHistory(),
  routes,
});
