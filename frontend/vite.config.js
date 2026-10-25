import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 开发：npm run dev（5173），/api 与 /actuator 代理到本地 Spring Boot（8080）
// 发布：npm run build，产物直接输出到 Spring Boot 静态目录，浏览器开 8080 即完整应用
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080'
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
})
