import { expect, test, type Page } from '@playwright/test'

const ACCOUNT = '80000000-0000-4000-8000-000000000001'
const TEAM = '80000000-0000-4000-8000-000000000002'
const SEASON = '80000000-0000-4000-8000-000000000003'
const NEXT_SEASON = '80000000-0000-4000-8000-000000000004'
const SUBSCRIPTION = '80000000-0000-4000-8000-000000000005'
const OTHER_TEAM = '80000000-0000-4000-8000-000000000006'
const OTHER_SUBSCRIPTION = '80000000-0000-4000-8000-000000000007'

async function setup(page: Page, options: { empty?: boolean; failFirst?: boolean; wrongAccount?: boolean; failNext?: boolean } = {}) {
  const calls: string[] = []
  let recovered = false
  let nextRequests = 0
  let revoked = false
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    calls.push(`${request.method()} ${path}${url.search}`)
    if (path === '/api/v1/auth/session') return route.fulfill({ json: { authenticated: true, accountId: ACCOUNT,
      csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'list-test-csrf' } })
    if (path === '/api/v1/auth/csrf') return route.fulfill({ json: { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'list-test-csrf' } })
    if (path === '/api/v1/auth/account') return route.fulfill({ json: { accountId: ACCOUNT, displayName: '내 계정',
      identities: [{ provider: 'google', email: null, emailVerified: false }] } })
    if (path === '/api/v1/me/calendar-subscriptions') {
      expect(request.headers()['x-baton-account-id']).toBe(ACCOUNT)
      expect(request.headers()['x-baton-access-key']).toBeUndefined()
      const next = url.searchParams.get('afterSeasonId')
      if (options.failFirst && !recovered || options.failNext && next && ++nextRequests === 1) {
        return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '목록을 불러오지 못했습니다.' } })
      }
      if (next) expect(next).toBe(SEASON)
      return route.fulfill({ json: { accountId: options.wrongAccount ? OTHER_TEAM : ACCOUNT,
        subscriptions: options.empty ? [] : next ? [{ subscriptionId: OTHER_SUBSCRIPTION, teamId: OTHER_TEAM,
          seasonId: NEXT_SEASON, teamName: '운동 모임', seasonName: '여름 시즌', managementStatus: 'REVOKED' }]
          : [{ subscriptionId: SUBSCRIPTION, teamId: TEAM, seasonId: SEASON,
            teamName: '바통 독서 팀', seasonName: '가을 시즌', managementStatus: revoked ? 'REVOKED' : 'CHECK_REQUIRED' }],
        nextAfterSeasonId: options.empty || next ? null : SEASON } })
    }
    if (path === `/api/v1/teams/${TEAM}/seasons/${SEASON}/calendar-subscription`) {
      expect(request.headers()['x-baton-account-id']).toBe(ACCOUNT)
      expect(request.headers()['x-baton-access-key']).toBeUndefined()
      if (request.method() === 'DELETE') {
        expect(request.headers()['x-csrf-token']).toBe('list-test-csrf')
        revoked = true
        return route.fulfill({ status: 204 })
      }
      expect(request.method()).toBe('GET')
      return route.fulfill({ json: { subscriptionId: SUBSCRIPTION, seasonId: SEASON, status: revoked ? 'REVOKED' : 'ACTIVE' } })
    }
    return route.fulfill({ status: 403, json: { code: 'WORKSPACE_ACCESS_DENIED', message: '팀 접근 권한이 없습니다.' } })
  })
  await page.goto('/account')
  const list = page.getByRole('region', { name: '내 캘린더 구독', exact: true })
  return { list, calls, recover: () => { recovered = true } }
}

test('계정에서 여러 팀의 구독을 보고 접근 권한 없이 본인 구독을 해제한다 @smoke @responsive', async ({ page }, testInfo) => {
  const { list, calls } = await setup(page)
  await expect(list.getByText('바통 독서 팀', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.endsWith('/calendar-subscription'))).toEqual([])
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(list.getByText('운동 모임', { exact: true })).toBeVisible()
  await expect(list.getByRole('button', { name: '구독 더 보기' })).toHaveCount(0)
  await list.locator('summary').filter({ hasText: '바통 독서 팀' }).click()
  await expect(list.getByText('구독 중입니다.', { exact: true })).toBeVisible()
  await expect(list.getByRole('button', { name: '새 주소 발급', exact: true })).toHaveCount(0)
  await expect(list.getByLabel('내 구독 주소')).toHaveCount(0)
  await list.getByRole('button', { name: '구독 해제', exact: true }).click()
  expect(calls.filter(call => call.startsWith('DELETE'))).toHaveLength(0)
  await list.getByRole('button', { name: '구독 해제 확인', exact: true }).click()
  await expect(list.getByText('구독을 해제했습니다.', { exact: true })).toBeVisible()
  await expect(list.locator('summary').filter({ hasText: '바통 독서 팀' })).toContainText('해제됨')
  expect(calls.filter(call => call.startsWith('DELETE'))).toHaveLength(1)
  expect(calls.some(call => call.endsWith('/workspace'))).toBe(false)
  await list.screenshot({ path: testInfo.outputPath('my-calendar-subscriptions.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('구독 목록 실패를 다시 조회하고 구독이 없으면 시작 방법을 안내한다 @smoke', async ({ page }) => {
  const { list, recover } = await setup(page, { empty: true, failFirst: true })
  await expect(list.getByRole('alert')).toContainText('목록을 불러오지 못했습니다.')
  recover()
  await list.getByRole('button', { name: '목록 새로고침' }).click()
  await expect(list.getByText('아직 구독 기록이 없습니다.', { exact: false })).toBeVisible()
  await expect(list.getByRole('alert')).toHaveCount(0)
})

test('다음 페이지 조회가 실패해도 기존 구독을 유지하고 더 보기를 재시도한다 @smoke', async ({ page }) => {
  const { list } = await setup(page, { failNext: true })
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(list.getByRole('alert')).toContainText('이전 목록은 유지됩니다.')
  await expect(list.getByText('바통 독서 팀', { exact: true })).toBeVisible()
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(list.getByText('운동 모임', { exact: true })).toBeVisible()
  await expect(list.getByRole('alert')).toHaveCount(0)
})

test('화면 계정과 다른 계정의 목록 응답은 표시하거나 조작하지 않는다 @smoke', async ({ page }) => {
  const { list, calls } = await setup(page, { wrongAccount: true })
  await expect(list.getByRole('alert')).toBeVisible()
  await expect(list.getByText('바통 독서 팀', { exact: true })).toHaveCount(0)
  await expect(list.locator('summary')).toHaveCount(0)
  expect(calls.filter(call => call.endsWith('/calendar-subscription'))).toEqual([])
})
