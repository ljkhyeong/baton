import { expect, test } from '@playwright/test'

test('@responsive 서비스 상태 링크는 활성화한 경우에만 표시하고 현재 주소를 전송하지 않는다', async ({ page, context }, testInfo) => {
  const enabled = (process.env.VITE_STATUS_PAGE_ENABLED ?? 'true') === 'true'
  const requests: { url: string; referer?: string }[] = []
  await context.route('https://status.b4ton.com/**', async (route) => {
    requests.push({ url: route.request().url(), referer: route.request().headers().referer })
    await route.fulfill({ contentType: 'text/html', body: '<title>BATON status</title>' })
  })
  await page.route('**/api/v1/auth/**', (route) => route.fulfill({ json: {
    authenticated: false, providers: [], localRegistrationEnabled: false,
    passwordResetEnabled: false, turnstileSiteKey: null,
  } }))

  for (const [path, selector, name] of [
    ['/', '.onboarding-page', 'start'],
    ['/login', '.auth-page', 'login'],
  ] as const) {
    await page.goto(`${path}?returnTo=private-workspace#private-token`)
    await expect(page.locator(selector)).toBeVisible()
    const link = page.getByRole('link', { name: '서비스 상태 (새 탭)' })
    await expect(link).toHaveCount(enabled ? 1 : 0)
    expect(requests).toHaveLength(0)
    if (!enabled) continue

    await expect(link).toHaveAttribute('href', 'https://status.b4ton.com')
    await link.focus()
    await expect(link).toBeFocused()
    const bounds = await link.boundingBox()
    expect(bounds?.height).toBeGreaterThanOrEqual(44)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`status-${name}.png`), fullPage: true })
  }

  if (!enabled) return
  const sourceUrl = page.url()
  const popupPromise = page.waitForEvent('popup')
  await page.getByRole('link', { name: '서비스 상태 (새 탭)' }).press('Enter')
  const popup = await popupPromise
  await expect(popup).toHaveTitle('BATON status')
  expect(requests).toEqual([{ url: 'https://status.b4ton.com/', referer: undefined }])
  expect(await popup.evaluate(() => window.opener === null)).toBe(true)
  expect(page.url()).toBe(sourceUrl)
  await popup.close()
})
