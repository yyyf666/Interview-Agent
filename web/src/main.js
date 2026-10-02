import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
// 自托管字体（离线部署可用；unicode-range 分包，浏览器按需取字形）
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/noto-sans-sc/400.css';
import '@fontsource/noto-sans-sc/500.css';
import '@fontsource/noto-sans-sc/600.css';
import '@fontsource/noto-sans-sc/700.css';
import './styles/global.css';
import { initTheme } from './utils/theme';

// 外观主题（R-19）：在挂载前写 data-theme，避免首帧白底闪烁
initTheme();

createApp(App).use(createPinia()).use(router).mount('#app');
