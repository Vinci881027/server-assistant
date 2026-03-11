import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.js'],
    restoreMocks: true,
    clearMocks: true,
  },
  server: {
    // 這裡設定代理，讓前端 fetch('/api/...') 能抓到 Spring Boot 的數據
    proxy: {
      '/api': {
        target: 'http://localhost:8008',
        changeOrigin: true,
        secure: false,
      }
    }
  },
  build: {
    // Rolldown 會在這裡自動進行高性能壓縮
    minify: 'rolldown', // 如果你的版本支援直接指定
    outDir: '../src/main/resources/static', // 直接把產出丟進 Spring Boot 靜態資料夾
    emptyOutDir: true,
    // Optimize chunk splitting for better caching (Rolldown function format)
    rollupOptions: {
      output: {
        manualChunks: (id) => {
          // Split vendor libraries into separate chunks
          if (id.includes('node_modules')) {
            if (id.includes('vue') || id.includes('pinia')) {
              return 'vendor-vue'
            }
            if (id.includes('axios') || id.includes('marked') ||
                id.includes('highlight.js') || id.includes('markdown-it')) {
              return 'vendor-utils'
            }
            return 'vendor' // Other node_modules
          }
        },
      },
    },
    // Increase chunk size warning limit
    chunkSizeWarningLimit: 600,
  }
})
