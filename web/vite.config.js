import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 构建产物仍输出到 web/dist，由 nginx 只读挂载（docker-compose: ./web/dist:/usr/share/nginx/html:ro）
// 开发期用 Vite dev server（5173）代理到后端，与生产 nginx 转发规则保持一致
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true, // 实时语音 /api/ws/asr
      },
      '/run': {
        target: 'http://localhost:8090', // code-runner 沙箱服务
        changeOrigin: true,
      },
    },
  },
});
