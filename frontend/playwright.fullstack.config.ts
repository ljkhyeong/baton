import { defineConfig, devices } from '@playwright/test'

import {
  requireLoopbackHttpOrigin,
  requireRoundEdgeHttpsOrigin,
} from './tests/support/loopback-url'

const roundEdgeMode = process.env.BATON_FULLSTACK_ROUND_EDGE === 'true'
const baseURL = roundEdgeMode
  ? requireRoundEdgeHttpsOrigin(
      process.env.BATON_FULLSTACK_BASE_URL,
      'BATON_FULLSTACK_BASE_URL',
    )
  : requireLoopbackHttpOrigin(
      process.env.BATON_FULLSTACK_BASE_URL ?? 'http://127.0.0.1:3200',
      'BATON_FULLSTACK_BASE_URL',
    )

export default defineConfig({
  testDir: './tests/fullstack',
  outputDir: 'test-results/fullstack',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  failOnFlakyTests: Boolean(process.env.CI),
  timeout: 90_000,
  expect: {
    timeout: 15_000,
  },
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [
        ['html', { open: 'never', outputFolder: 'playwright-report/fullstack' }],
        ['github'],
      ]
    : [
        ['list'],
        ['html', { open: 'never', outputFolder: 'playwright-report/fullstack' }],
      ],
  use: {
    baseURL,
    ignoreHTTPSErrors: roundEdgeMode,
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'fullstack-chromium',
      use: {
        ...devices['Desktop Chrome'],
        launchOptions: roundEdgeMode
          ? {
              args: [
                '--host-resolver-rules=MAP baton.fullstack.test 127.0.0.1',
              ],
            }
          : undefined,
      },
    },
  ],
})
