import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 4100,
    headers: { 'Access-Control-Allow-Origin': '*' },
    proxy: {
      // mse 登录/会话接口：单体模式指向 8080；微服务模式改为 48080（网关）
      '/api': {
        target: process.env.MSE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      },
      // OpenIM 实例（租户 1 手动栈默认端口；多实例时指向该租户实例端口）
      '/im-api': {
        target: 'http://localhost:10002',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/im-api/, '')
      }
    }
  }
})
