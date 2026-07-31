import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/ws': {
        target: 'ws://192.168.50.101:8082',
        ws: true
      },
      '/api': {
        target: 'http://192.168.50.101:8083'
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
