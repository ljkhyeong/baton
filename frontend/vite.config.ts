import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { sentryVitePlugin } from '@sentry/vite-plugin'
import { fileURLToPath, URL } from 'node:url'

const apiProxyTarget = process.env.BATON_API_PROXY_TARGET ?? 'http://127.0.0.1:8080'

export default defineConfig(({ command }) => {
  const uploadSourcemaps = command === 'build' && process.env.SENTRY_SOURCEMAPS_UPLOAD === 'true'
  if (uploadSourcemaps) {
    if (process.env.NODE_ENV === 'development') throw new Error('소스맵 업로드는 production 빌드에서만 실행합니다.')
    for (const name of ['SENTRY_AUTH_TOKEN', 'SENTRY_ORG', 'SENTRY_PROJECT', 'VITE_SENTRY_DSN']) {
      if (!process.env[name]?.trim()) throw new Error(`소스맵 업로드에 ${name} 설정이 필요합니다.`)
    }
  }

  return {
    plugins: [react(), ...(uploadSourcemaps ? [sentryVitePlugin({
      org: process.env.SENTRY_ORG,
      project: process.env.SENTRY_PROJECT,
      authToken: process.env.SENTRY_AUTH_TOKEN,
      telemetry: false,
      errorHandler: () => { throw new Error('소스맵 업로드에 실패해 웹 빌드를 중단합니다.') },
      release: { create: false, inject: false, finalize: false, setCommits: false },
      sourcemaps: { filesToDeleteAfterUpload: ['./dist/**/*.map'] },
    })] : [])],
    build: { sourcemap: uploadSourcemaps ? 'hidden' : false },
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
  }
})
