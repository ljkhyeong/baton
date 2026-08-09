import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

const apiProxyTarget = process.env.BATON_API_PROXY_TARGET ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 3000,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
      '/oauth2': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
      '/login/oauth2': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
      '^/round/rooms/[^/?]+/participation-grant/refresh$': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
      '/.well-known/round-participation-jwks.json': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
    },
  },
})
