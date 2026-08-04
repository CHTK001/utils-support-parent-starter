import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
    server: {
      port: 28849,
      strictPort: true,
      host: '0.0.0.0',
      proxy: {
        '/ws': {
          target: 'ws://127.0.0.1:8082',
          ws: true
        },
        '/api': {
          target: 'http://127.0.0.1:3000'
        }
      }
    },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
