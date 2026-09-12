import { expect, test } from '@playwright/test'

test('Sentry는 기본 비활성이며 켜도 오류 위치만 전송한다', async ({ page }) => {
  const reports: string[] = []
  await page.route('https://o0.ingest.sentry.io/**', async (route) => {
    reports.push(route.request().postData() ?? '')
    await route.fulfill({ status: 200, body: '{}', headers: { 'access-control-allow-origin': '*' } })
  })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: {
    authenticated: false, providers: [], localRegistrationEnabled: false,
    passwordResetEnabled: false, turnstileSiteKey: null,
  } }))
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()

  await page.evaluate(async () => {
    const path = '/src/app/errorReporting.ts'
    const reporting = await import(/* @vite-ignore */ path)
    reporting.reportReactError(new Error('disabled-error'), { componentStack: '' })
  })
  expect(reports).toHaveLength(0)

  await page.evaluate(async () => {
    const path = '/src/app/errorReporting.ts'
    const reporting = await import(/* @vite-ignore */ path)
    reporting.initializeErrorReporting('https://publickey@o0.ingest.sentry.io/1')
    const error = new Error('member@example.com secret-token secret-password')
    error.stack = `Error: member@example.com secret-token\n    at submit (${location.origin}/assets/test.js?token=secret-token#private:12:34)`
    reporting.reportReactError(error, { componentStack: 'secret-form-input' })
  })

  await expect.poll(() => reports.length).toBe(1)
  expect(reports[0]).toContain('/assets/test.js')
  expect(reports[0]).not.toMatch(/member@example\.com|secret-token|secret-password|secret-form-input|#private/)
})

test('잘못된 오류 수집 설정으로 로그인 화면이 중단되지 않는다', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(async () => {
    const path = '/src/app/errorReporting.ts'
    const reporting = await import(/* @vite-ignore */ path)
    reporting.initializeErrorReporting('invalid-dsn')
  })
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
})
