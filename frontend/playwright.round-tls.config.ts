import { defineConfig, devices } from '@playwright/test'

import { requireBatonLocalhostHttpsOrigin } from './tests/support/loopback-url'

const baseURL = requireBatonLocalhostHttpsOrigin(
  process.env.BATON_ROUND_EDGE_BASE_URL,
  'BATON_ROUND_EDGE_BASE_URL',
)
const outputDir = process.env.BATON_ROUND_EDGE_OUTPUT_DIRECTORY
  ?? 'test-results/round-tls'

export default defineConfig({
  testDir: './tests/round-tls',
  outputDir,
  fullyParallel: false,
  forbidOnly: true,
  failOnFlakyTests: true,
  timeout: 90_000,
  expect: {
    timeout: 15_000,
  },
  retries: 0,
  workers: 1,
  reporter: [['line']],
  use: {
    baseURL,
    ignoreHTTPSErrors: true,
    screenshot: 'off',
    trace: 'off',
    video: 'off',
  },
  projects: [
    { name: 'round-tls-chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
