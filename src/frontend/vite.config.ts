import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端与网关同源部署；开发期用 proxy 把 /api 与 /v1 转发到本地网关。
// VITE_GATEWAY_TARGET 指向网关地址（默认 http://localhost:8888）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_GATEWAY_TARGET || 'http://localhost:8888'
  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': { target, changeOrigin: true },
        // /v1 是 SSE 流式，关闭代理缓冲
        '/v1': { target, changeOrigin: true },
      },
    },
  }
})
