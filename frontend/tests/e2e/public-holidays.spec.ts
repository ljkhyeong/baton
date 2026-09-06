import { expect, test } from '@playwright/test'
import { installApi, openSharedWorkspace, navigation } from './support/workspaceApiHarness'

const result = (year: number) => ({ year, status: 'READY', checkedAt: '2026-09-07T01:00:00Z', holidays: [
  { date: `${year}-07-17`, name: '테스트 공휴일' },
  { date: `${year}-10-09`, name: '한글날' },
] })

test('공휴일은 펼칠 때 조회하고 같은 연도의 달 전환에는 결과를 재사용한다 @responsive', async ({ page }, testInfo) => {
  await installApi(page)
  const years: string[] = []
  await page.route('**/api/v1/calendar/holidays?*', route => {
    const year = new URL(route.request().url()).searchParams.get('year')!
    years.push(year)
    return route.fulfill({ json: result(Number(year)) })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정', exact: true }).click()
  expect(years).toEqual([])
  const panel = page.locator('.public-holiday-panel')
  await panel.getByText('대한민국 공휴일 확인', { exact: true }).click()
  await expect(panel.locator('.holiday-notice')).toContainText('테스트 공휴일')
  await panel.getByLabel('조회할 달').fill('2026-10')
  await expect(panel.locator('.holiday-list')).toContainText('한글날')
  expect(years).toEqual(['2026'])
  await panel.getByLabel('조회할 달').fill('2027-10')
  await expect(panel.locator('.holiday-list')).toContainText('한글날')
  await expect.poll(() => years).toEqual(['2026', '2027'])
  await panel.getByLabel('조회할 달').fill('2026-09')
  await expect(panel.getByText('이 달에는 등록된 공휴일이 없습니다.')).toBeVisible()
  await panel.getByLabel('조회할 달').fill('2026-10')
  await expect(panel.locator('.holiday-list')).toContainText('한글날')
  await panel.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('public-holidays.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

for (const [status, message] of [
  ['DISABLED', '현재 공휴일 조회를 사용할 수 없습니다.'],
  ['UNAVAILABLE', '공휴일 정보를 확인하지 못했습니다.'],
  ['OUT_OF_RANGE', '공휴일은 작년부터 내년까지 조회할 수 있습니다.'],
] as const) {
  test(`공휴일 ${status} 응답을 휴일 없음으로 표시하지 않는다`, async ({ page }, testInfo) => {
    await installApi(page)
    await page.route('**/api/v1/calendar/holidays?*', route => route.fulfill({
      json: { year: 2026, status, checkedAt: null, holidays: [] },
    }))
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '일정', exact: true }).click()
    const panel = page.locator('.public-holiday-panel')
    await panel.getByText('대한민국 공휴일 확인', { exact: true }).click()
    await expect(panel.getByText(message, { exact: false })).toBeVisible()
    await expect(panel.getByText('이 달에는 등록된 공휴일이 없습니다.')).toHaveCount(0)
  })
}

test('다른 연도 응답을 거부하고 다시 확인할 수 있다', async ({ page }, testInfo) => {
  await installApi(page)
  let invalid = true
  await page.route('**/api/v1/calendar/holidays?*', route => route.fulfill({ json: result(invalid ? 2027 : 2026) }))
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정', exact: true }).click()
  const panel = page.locator('.public-holiday-panel')
  await panel.getByText('대한민국 공휴일 확인', { exact: true }).click()
  await expect(panel.getByRole('alert')).toBeVisible()
  invalid = false
  await panel.getByRole('button', { name: '다시 확인', exact: true }).click()
  await expect(panel.locator('.holiday-list')).toContainText('테스트 공휴일')
})
