import { expect, test, type Page } from '@playwright/test'

const ACCOUNT = '80000000-0000-4000-8000-000000000001'
const TEAM = '80000000-0000-4000-8000-000000000002'
const SEASON = '80000000-0000-4000-8000-000000000003'
const NEXT_SEASON = '80000000-0000-4000-8000-000000000004'
const SUBSCRIPTION = '80000000-0000-4000-8000-000000000005'
const OTHER_TEAM = '80000000-0000-4000-8000-000000000006'
const OTHER_SUBSCRIPTION = '80000000-0000-4000-8000-000000000007'

async function setup(page: Page, options: { empty?: boolean; failFirst?: boolean; wrongAccount?: boolean; failNext?: boolean; revokePending?: boolean; pending?: 'IN_PROGRESS' | 'REVOCATION_PENDING' } = {}) {
  const calls: string[] = []
  let recovered = false
  let nextRequests = 0
  let revoked = false
  let pending = Boolean(options.pending)
  let failStatus = false
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
      const query = url.searchParams.get('query') ?? ''
      const includeRevoked = url.searchParams.get('includeRevoked') !== 'false'
      const all = [
        { subscriptionId: SUBSCRIPTION, teamId: TEAM, seasonId: SEASON, teamName: '인수인계 독서 팀', seasonName: '가을 시즌',
          managementStatus: revoked ? 'REVOKED' : pending ? options.pending ?? 'REVOCATION_PENDING' : 'CHECK_REQUIRED' },
        { subscriptionId: OTHER_SUBSCRIPTION, teamId: OTHER_TEAM, seasonId: NEXT_SEASON,
          teamName: '운동 모임', seasonName: '여름 시즌', managementStatus: 'REVOKED' },
      ].filter(row => !options.empty && (row.teamName.includes(query) || row.seasonName.includes(query))
        && (includeRevoked || row.managementStatus !== 'REVOKED'))
      const remaining = all.filter(row => !next || row.seasonId > next)
      return route.fulfill({ json: { accountId: options.wrongAccount ? OTHER_TEAM : ACCOUNT,
        subscriptions: remaining.slice(0, 1), nextAfterSeasonId: remaining.length > 1 ? remaining[0]?.seasonId ?? null : null } })
    }
    if (path === `/api/v1/teams/${TEAM}/seasons/${SEASON}/calendar-subscription`) {
      expect(request.headers()['x-baton-account-id']).toBe(ACCOUNT)
      expect(request.headers()['x-baton-access-key']).toBeUndefined()
      if (request.method() === 'DELETE') {
        expect(request.headers()['x-csrf-token']).toBe('list-test-csrf')
        if (options.revokePending) {
          pending = true
          return route.fulfill({ status: 503, json: { code: 'CAL_SUBSCRIPTION_UNAVAILABLE', message: '구독 해제 결과를 확인하지 못했습니다.' } })
        }
        revoked = true
        return route.fulfill({ status: 204 })
      }
      expect(request.method()).toBe('GET')
      if (failStatus) return route.fulfill({ status: 503, json: { code: 'CAL_SUBSCRIPTION_UNAVAILABLE', message: '구독 상태를 확인하지 못했습니다.' } })
      return route.fulfill({ json: { subscriptionId: SUBSCRIPTION, seasonId: SEASON, status: revoked ? 'REVOKED' : pending ? options.pending ?? 'REVOCATION_PENDING' : 'ACTIVE' } })
    }
    return route.fulfill({ status: 403, json: { code: 'WORKSPACE_ACCESS_DENIED', message: '팀 접근 권한이 없습니다.' } })
  })
  await page.goto('/account')
  const list = page.getByRole('region', { name: '내 캘린더 구독', exact: true })
  return { list, calls, recover: () => { recovered = true }, failStatus: (value: boolean) => { failStatus = value },
    finish: () => { pending = false; revoked = options.pending === 'REVOCATION_PENDING' || Boolean(options.revokePending) } }
}

test('계정에서 여러 팀의 구독을 보고 접근 권한 없이 본인 구독을 해제한다 @smoke @responsive', async ({ page }, testInfo) => {
  const { list, calls } = await setup(page)
  await expect(list.getByText('인수인계 독서 팀', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.endsWith('/calendar-subscription'))).toEqual([])
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(list.getByText('운동 모임', { exact: true })).toBeVisible()
  await expect(list.getByRole('button', { name: '구독 더 보기' })).toHaveCount(0)
  await list.locator('summary').filter({ hasText: '인수인계 독서 팀' }).click()
  await expect(list.getByText('구독 중입니다.', { exact: true })).toBeVisible()
  const summary = list.locator('summary').filter({ hasText: '인수인계 독서 팀' })
  await expect(summary).toContainText('구독 중')
  await expect(summary.locator('time')).toContainText('확인')
  await summary.click()
  await expect(summary).toContainText('구독 중')
  await expect(summary.locator('time')).toBeVisible()
  await summary.click()
  await expect(list.getByRole('button', { name: '새 주소 발급', exact: true })).toHaveCount(0)
  await expect(list.getByLabel('내 구독 주소')).toHaveCount(0)
  await list.getByRole('button', { name: '구독 해제', exact: true }).click()
  expect(calls.filter(call => call.startsWith('DELETE'))).toHaveLength(0)
  await list.getByRole('group', { name: '구독 해제 확인', exact: true }).getByRole('button', { name: '구독 해제', exact: true }).click()
  await expect(list.getByText('구독을 해제했습니다.', { exact: true })).toBeVisible()
  await expect(list.locator('summary').filter({ hasText: '인수인계 독서 팀' })).toContainText('해제됨')
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
  await expect(list.getByText('인수인계 독서 팀', { exact: true })).toBeVisible()
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(list.getByText('운동 모임', { exact: true })).toBeVisible()
  await expect(list.getByRole('alert')).toHaveCount(0)
})

test('화면 계정과 다른 계정의 목록 응답은 표시하거나 조작하지 않는다 @smoke', async ({ page }) => {
  const { list, calls } = await setup(page, { wrongAccount: true })
  await expect(list.getByRole('alert')).toBeVisible()
  await expect(list.getByText('인수인계 독서 팀', { exact: true })).toHaveCount(0)
  await expect(list.locator('summary')).toHaveCount(0)
  expect(calls.filter(call => call.endsWith('/calendar-subscription'))).toEqual([])
})

for (const pending of ['IN_PROGRESS', 'REVOCATION_PENDING'] as const) {
  test(`${pending} 상태는 자동 조회로 완료되고 확인한 상태와 시각을 목록에 남긴다 @smoke`, async ({ page }) => {
    await page.clock.install()
    const { list, calls, finish } = await setup(page, { pending })
    const summary = list.locator('summary').filter({ hasText: '인수인계 독서 팀' })
    await summary.click()
    await expect(list.getByText('처리가 끝났는지 확인하고 있습니다.', { exact: false })).toBeVisible()
    finish()
    await page.clock.runFor(3_100)
    await expect(summary).toContainText(pending === 'IN_PROGRESS' ? '구독 중' : '해제됨')
    await expect(summary.locator('time')).toBeVisible()
    const reads = calls.filter(call => call.endsWith('/calendar-subscription')).length
    await page.clock.runFor(9_100)
    expect(calls.filter(call => call.endsWith('/calendar-subscription'))).toHaveLength(reads)
    expect(calls.filter(call => call.startsWith('POST') || call.startsWith('DELETE'))).toEqual([])
  })
}

test('자동 조회는 제한 시간 뒤 멈추고 수동 확인으로 재개하며 항목을 닫으면 중단한다 @smoke', async ({ page }) => {
  await page.clock.install()
  const { list, calls } = await setup(page, { pending: 'IN_PROGRESS' })
  const summary = list.locator('summary').filter({ hasText: '인수인계 독서 팀' })
  await summary.click()
  await expect(summary.locator('time')).toBeVisible()
  const readCount = () => calls.filter(call => call.endsWith('/calendar-subscription')).length
  await page.clock.fastForward(89_000)
  await expect(list.getByText('처리가 끝났는지 확인하고 있습니다.', { exact: false })).toBeVisible()
  await page.clock.fastForward(1_100)
  await expect(list.getByText('아직 완료 여부를 확인하지 못했습니다.', { exact: false })).toBeVisible()
  await expect(list.getByRole('button', { name: '상태 다시 확인' })).toBeEnabled()
  const stopped = readCount()
  await page.clock.runFor(12_000)
  expect(readCount()).toBe(stopped)
  await list.getByRole('button', { name: '상태 다시 확인' }).click()
  await expect.poll(readCount).toBeGreaterThan(stopped)
  await expect(list.getByText('아직 완료 여부를 확인하지 못했습니다.', { exact: false })).toHaveCount(0)
  await expect(list.getByText('처리가 끝났는지 확인하고 있습니다.', { exact: false })).toBeVisible()
  await expect(list.getByRole('button', { name: '상태 다시 확인' })).toBeEnabled()
  const manual = readCount()
  await page.clock.runFor(3_100)
  await expect.poll(readCount).toBeGreaterThan(manual)
  await summary.click()
  await expect(list.locator('.calendar-content')).toHaveCount(0)
  const closed = readCount()
  await page.clock.runFor(9_100)
  expect(readCount()).toBe(closed)
})

test('자동 상태 조회가 실패하면 요청을 반복하지 않고 사용자 재확인을 기다린다 @smoke', async ({ page }) => {
  await page.clock.install()
  const { list, calls, failStatus } = await setup(page, { pending: 'REVOCATION_PENDING' })
  await list.locator('summary').filter({ hasText: '인수인계 독서 팀' }).click()
  await expect(list.locator('time')).toBeVisible()
  failStatus(true)
  await page.clock.runFor(3_100)
  await expect(list.getByRole('alert')).toContainText('구독 상태를 확인하지 못했습니다.')
  await expect(list.getByText('자동 확인을 멈췄습니다.', { exact: false })).toBeVisible()
  const reads = calls.filter(call => call.endsWith('/calendar-subscription')).length
  await page.clock.runFor(9_100)
  expect(calls.filter(call => call.endsWith('/calendar-subscription'))).toHaveLength(reads)
  await expect(list.getByRole('button', { name: '구독 해제', exact: true })).toBeDisabled()
  failStatus(false)
  await list.getByRole('button', { name: '상태 다시 확인' }).click()
  await expect(list.getByRole('alert')).toHaveCount(0)
})

test('검색은 아직 불러오지 않은 구독을 찾고 필터를 바꾸면 첫 페이지에서 다시 조회한다 @smoke @responsive', async ({ page }, testInfo) => {
  const { list, calls } = await setup(page)
  await expect(list.getByText('인수인계 독서 팀', { exact: true })).toBeVisible()
  await list.getByLabel('팀·시즌 검색', { exact: true }).fill('  운동  ')
  await list.getByRole('button', { name: '검색', exact: true }).click()
  await expect(list.getByText('운동 모임', { exact: true })).toBeVisible()
  expect(calls.at(-1)).toContain('query=')
  expect(calls.at(-1)).not.toContain('afterSeasonId')
  await list.getByLabel('해제된 구독 숨기기').check()
  await expect(list.getByText('조건에 맞는 구독이 없습니다.', { exact: false })).toBeVisible()
  expect(calls.at(-1)).toContain('includeRevoked=false')
  await list.getByLabel('해제된 구독 숨기기').uncheck()
  await expect(list.getByText('운동 모임', { exact: true })).toBeVisible()
  await list.screenshot({ path: testInfo.outputPath('calendar-subscription-search.png') })
  await list.getByRole('button', { name: '검색·필터 초기화' }).click()
  await expect(list.getByText('인수인계 독서 팀', { exact: true })).toBeVisible()
  await expect(list.getByRole('button', { name: '구독 더 보기' })).toBeVisible()
  await expect(list.getByLabel('팀·시즌 검색', { exact: true })).toHaveValue('')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})


test('해제 응답을 놓쳐도 상태 조회로 완료를 확인하고 이전 오류를 정리한다 @smoke', async ({ page }) => {
  await page.clock.install()
  const { list, calls, finish } = await setup(page, { revokePending: true })
  await list.locator('summary').filter({ hasText: '인수인계 독서 팀' }).click()
  await list.getByRole('button', { name: '구독 해제', exact: true }).click()
  await list.getByRole('group', { name: '구독 해제 확인', exact: true }).getByRole('button', { name: '구독 해제', exact: true }).click()
  await expect(list.getByRole('alert')).toContainText('구독 해제 결과를 확인하지 못했습니다.')
  await expect(list.getByRole('button', { name: '상태 다시 확인' })).toBeEnabled()
  finish()
  await page.clock.runFor(3_100)
  await expect(list.getByText('구독을 해제했습니다.', { exact: true })).toBeVisible()
  await expect(list.getByRole('alert')).toHaveCount(0)
  expect(calls.filter(call => call.startsWith('DELETE'))).toHaveLength(1)
})


test('자동 확인으로 해제가 완료되면 검색·숨김 조건을 유지한 채 목록에서 제외한다 @smoke', async ({ page }) => {
  await page.clock.install()
  const { list, calls, finish } = await setup(page, { pending: 'REVOCATION_PENDING' })
  await list.getByLabel('팀·시즌 검색', { exact: true }).fill('독서')
  await list.getByRole('button', { name: '검색', exact: true }).click()
  await list.getByLabel('해제된 구독 숨기기').check()
  await list.locator('summary').filter({ hasText: '인수인계 독서 팀' }).click()
  await expect(list.locator('time')).toBeVisible()
  const listReads = () => calls.filter(call => call.startsWith('GET /api/v1/me/calendar-subscriptions'))
  const before = listReads().length
  finish()
  await page.clock.runFor(3_100)
  await expect(list.getByText('조건에 맞는 구독이 없습니다.', { exact: false })).toBeVisible()
  await expect(list.locator('summary')).toHaveCount(0)
  await expect(list.getByLabel('해제된 구독 숨기기')).toBeChecked()
  await expect(list.getByLabel('팀·시즌 검색', { exact: true })).toHaveValue('독서')
  expect(listReads()).toHaveLength(before + 1)
  const refreshed = new URL(listReads().at(-1)!.slice(4), 'https://baton.example')
  expect(refreshed.searchParams.get('query')).toBe('독서')
  expect(refreshed.searchParams.get('includeRevoked')).toBe('false')
  const settled = calls.length
  await page.clock.runFor(9_100)
  expect(calls).toHaveLength(settled)
  expect(calls.filter(call => call.startsWith('POST') || call.startsWith('DELETE'))).toEqual([])
})
