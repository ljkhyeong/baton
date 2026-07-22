import { defineConfig, devices } from '@playwright/test'

const TEST_BASE_URL = 'http://127.0.0.1:3100'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['github']] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: TEST_BASE_URL,
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 } } },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 3100 --strictPort',
    env: {
      VITE_WORKSPACE_SYNC_INTERVAL_MS: '2000',
    },
    url: TEST_BASE_URL,
    reuseExistingServer: false,
  },
})
