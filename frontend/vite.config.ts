/// <reference types="vitest/config" />
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// Selama pengembangan, permintaan /api diteruskan ke backend Spring Boot di 127.0.0.1:8085.
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8085',
    },
  },
  test: {
    environment: 'jsdom',
  },
})
