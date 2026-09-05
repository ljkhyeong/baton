import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { installApi, openSharedWorkspace, TEAM_ID, SEASON_ID, MEMBER_ONE_ID, ACCESS_KEY, makeProjection, WORKSPACE_PATH, SCOPE_PATH } from './support/workspaceApiHarness'

const ACCOUNT = '80000000-0000-4000-8000-000000000001'
const SUBSCRIPTION = '80000000-0000-4000-8000-000000000002'
const ADDRESS = `https://cal.b4ton.com/calendars/v1/${'a'.repeat(43)}.ics`
const ROTATED = `https://cal.b4ton.com/calendars/v1/${'b'.repeat(43)}.ics`
async function setup(page: Page, options: { lost?: boolean; authenticated?: boolean; invalidUrl?: boolean; viewer?: boolean; denied?: boolean } = {}) {
  const projection = makeProjection()
  if (options.viewer) { projection.team.accountAccessEnabled = true; projection.team.permission = 'VIEWER' }
  await installApi(page, projection)
  if (options.denied) await page.route(`**${SCOPE_PATH}/workspace`, route => route.fulfill({ status: 403,
    json: { code: 'WORKSPACE_ACCESS_DENIED', message: '팀 접근 권한이 없습니다.' } }))
  await page.route('**/api/v1/auth/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/csrf')) return route.fulfill({ json: { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'calendar-test-csrf' } })
    if (path.endsWith('/session')) return route.fulfill({ json: options.authenticated === false ? { authenticated: false }
      : { authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'calendar-test-csrf' } })
    return route.fallback()
  })
  await page.route('**/api/v1/account-memberships/current?*', (route) => route.fulfill({ json: {
    claimed: true, accountId: ACCOUNT, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: '2026-09-05T00:00:00Z',
  } }))
  let status = options.denied ? 'ACTIVE' : 'NOT_CREATED'
  const calls: string[] = []
  await page.route('**/calendar-subscription{,/rotate}', async (route) => {
    const request = route.request()
    const action = `${request.method()} ${new URL(request.url()).pathname.endsWith('/rotate') ? 'rotate' : 'subscription'}`
    calls.push(action)
    expect(request.headers()['x-baton-access-key']).toBe(request.method() === 'POST' ? (options.viewer ? '' : ACCESS_KEY) : undefined)
    expect(request.headers()['x-baton-account-id']).toBe(ACCOUNT)
    if (request.method() === 'GET') return route.fulfill({ json: { subscriptionId: status === 'NOT_CREATED' ? null : SUBSCRIPTION, seasonId: SEASON_ID, status } })
    expect(request.headers()['x-csrf-token']).toBe('calendar-test-csrf')
    if (request.method() === 'DELETE') { status = 'REVOKED'; return route.fulfill({ status: 204 }) }
    status = 'ACTIVE'
    if (options.lost && action === 'POST subscription') return route.fulfill({ status: 503, json: {
      code: 'CAL_SUBSCRIPTION_UNAVAILABLE', message: '구독 요청 결과를 확인하지 못했습니다. 상태를 먼저 다시 조회해 주세요.',
    } })
    return route.fulfill({ status: action === 'POST rotate' ? 200 : 201, json: { subscriptionId: SUBSCRIPTION, seasonId: SEASON_ID,
      feedUrl: options.invalidUrl ? 'javascript:alert(1)' : action === 'POST rotate' ? ROTATED : ADDRESS } })
  })
  if (options.viewer || options.denied) await page.goto(WORKSPACE_PATH)
  else await openSharedWorkspace(page)
  const panel = page.locator('.calendar-panel')
  await panel.locator(':scope > summary').click()
  return { panel, calls }
}

test('구독 주소 발급·재발급·해제와 화면을 닫을 때 주소 제거 @smoke @responsive', async ({ page }, testInfo) => {
  const { panel, calls } = await setup(page)
  await panel.getByRole('button', { name: '구독 주소 발급', exact: true }).click()
  await expect(panel.getByLabel('내 구독 주소')).toHaveValue(ADDRESS)
  await panel.getByText('캘린더 앱에 등록하는 방법', { exact: true }).click()
  await panel.evaluate((element) => element.scrollIntoView({ block: 'start' }))
  await panel.screenshot({ path: testInfo.outputPath('calendar-panel.png') })
  const stored = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
  expect(stored).not.toContain(ADDRESS)
  await panel.getByRole('button', { name: '새 주소 발급', exact: true }).click()
  expect(calls.filter((call) => call === 'POST rotate')).toHaveLength(0)
  await panel.getByRole('button', { name: '기존 주소를 끄고 재발급' }).click()
  await expect(panel.getByLabel('내 구독 주소')).toHaveValue(ROTATED)
  await panel.locator(':scope > summary').click()
  await panel.locator(':scope > summary').click()
  await expect(panel.getByText('구독 중입니다.', { exact: true })).toBeVisible()
  await expect(panel.getByLabel('내 구독 주소')).toHaveCount(0)
  await panel.getByRole('button', { name: '구독 해제', exact: true }).click()
  await panel.getByRole('button', { name: '구독 해제 확인', exact: true }).click()
  await expect(panel.getByText('구독을 해제했습니다.', { exact: true })).toBeVisible()
  expect(calls.filter((call) => call.startsWith('POST') || call.startsWith('DELETE'))).toEqual(['POST subscription', 'POST rotate', 'DELETE subscription'])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('발급 응답을 놓치면 상태만 다시 조회하고 사용자 선택 전에는 재발급하지 않는다 @smoke', async ({ page }) => {
  const { panel, calls } = await setup(page, { lost: true })
  await panel.getByRole('button', { name: '구독 주소 발급', exact: true }).click()
  await expect(panel.getByRole('alert')).toBeVisible()
  await expect(panel.getByText('구독 중입니다.', { exact: true })).toBeVisible()
  await expect(panel.getByLabel('내 구독 주소')).toHaveCount(0)
  expect(calls.filter((call) => call.startsWith('POST'))).toEqual(['POST subscription'])
  await panel.getByRole('button', { name: '새 주소 발급', exact: true }).click()
  await panel.getByRole('button', { name: '기존 주소를 끄고 재발급' }).click()
  await expect(panel.getByLabel('내 구독 주소')).toHaveValue(ROTATED)
})

test('비로그인 상태에서는 구독 API를 호출하지 않는다 @smoke', async ({ page }) => {
  const { panel, calls } = await setup(page, { authenticated: false })
  await expect(panel.getByRole('link', { name: '로그인', exact: true })).toBeVisible()
  await expect(panel.getByRole('button', { name: '구독 주소 발급', exact: true })).toHaveCount(0)
  expect(calls).toEqual([])
})

test('잘못된 구독 주소는 화면에 표시하지 않는다 @smoke', async ({ page }) => {
  const { panel } = await setup(page, { invalidUrl: true })
  await panel.getByRole('button', { name: '구독 주소 발급', exact: true }).click()
  await expect(panel.getByRole('alert')).toBeVisible()
  await expect(panel.getByLabel('내 구독 주소')).toHaveCount(0)
})

test('열람자는 공유 키 없이 개인 캘린더 주소를 발급할 수 있다 @smoke @responsive', async ({ page }) => {
  const { panel } = await setup(page, { viewer: true })
  await panel.getByRole('button', { name: '구독 주소 발급', exact: true }).click()
  await expect(panel.getByLabel('내 구독 주소')).toHaveValue(ADDRESS)
})

test('팀 접근이 차단되어도 본인 구독을 해제할 수 있다 @smoke @responsive', async ({ page }) => {
  const { panel, calls } = await setup(page, { denied: true })
  await expect(page.getByText('작업 공간을 불러오지 못했어요', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '구독 해제', exact: true }).click()
  await panel.getByRole('button', { name: '구독 해제 확인', exact: true }).click()
  await expect(panel.getByText('구독을 해제했습니다.', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.startsWith('POST'))).toEqual([])
  expect(calls.filter(call => call.startsWith('DELETE'))).toEqual(['DELETE subscription'])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
