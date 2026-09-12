import { expect, test } from '@playwright/test'

test('@webkit Sentry는 기본 비활성이며 켜도 오류 위치와 소스맵 식별자만 전송한다', async ({ page }) => {
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
    Object.assign(window, { _sentryDebugIds: {
      [error.stack]: '80c5f231-b215-4c90-9bb3-f06d9c479ace',
    } })
    reporting.reportReactError(error, { componentStack: 'secret-form-input' })
  })

  await expect.poll(() => reports.length).toBe(1)
  expect(reports[0]).toContain('/assets/test.js')
  expect(reports[0]).toContain('80c5f231-b215-4c90-9bb3-f06d9c479ace')
  expect(reports[0]).toContain('"type":"sourcemap"')
  expect(reports[0]).not.toMatch(/member@example\.com|secret-token|secret-password|secret-form-input|#private/)
})

test('소스맵 정보는 보고할 코드 파일과 연결된 식별자만 남긴다', async ({ page }) => {
  await page.goto('/login')
  const result = await page.evaluate(async () => {
    const path = '/src/app/errorReporting.ts'
    const { errorLocationOnly } = await import(/* @vite-ignore */ path)
    const filename = `${location.origin}/assets/test.js?token=secret#private`
    const debugId = '80c5f231-b215-4c90-9bb3-f06d9c479ace'
    return errorLocationOnly({
      exception: { values: [{ type: 'Error', stacktrace: { frames: [{ filename, lineno: 12, colno: 34 }] } }] },
      debug_meta: { images: [
        { type: 'sourcemap', code_file: filename, debug_id: debugId, extra: 'secret' },
        { type: 'sourcemap', code_file: filename, debug_id: 'member@example.com' },
        { type: 'sourcemap', code_file: 'https://outside.invalid/assets/test.js', debug_id: debugId },
        { type: 'sourcemap', code_file: `${location.origin}/assets/unrelated.js`, debug_id: debugId },
        { type: 'macho', code_file: filename, debug_id: debugId },
      ] },
    }, {})
  })
  expect(result.debug_meta.images).toEqual([{
    type: 'sourcemap', code_file: 'http://127.0.0.1:3100/assets/test.js',
    debug_id: '80c5f231-b215-4c90-9bb3-f06d9c479ace',
  }])
  expect(JSON.stringify(result)).not.toMatch(/secret|private|member@example|outside|unrelated|macho/)
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
