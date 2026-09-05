import { expect, test, type Page } from '@playwright/test'

const ACCOUNT = '90000000-0000-4000-8000-000000000001'
const OTHER_ACCOUNT = '90000000-0000-4000-8000-000000000002'
const id = (number: number) => `90000000-0000-4000-8000-${number.toString().padStart(12, '0')}`
type Failure = 'lost' | 'pending' | 'unavailable' | 'account' | 'hold' | 'hold-status'
async function setup(page: Page, options: { count?: number; pageSize?: number; revoked?: number[]; failures?: Record<number, Failure> } = {}) {
  const rows = Array.from({ length: options.count ?? 5 }, (_, index) => ({
    subscriptionId: id(300 + index), teamId: id(100 + index), seasonId: id(200 + index),
    teamName: `독서 모임 ${index + 1}`, seasonName: `가을 시즌 ${index + 1}`,
  }))
  const statuses: Array<'ACTIVE' | 'REVOKED' | 'REVOCATION_PENDING'> = rows.map((_, index) => (options.revoked ?? [3]).includes(index) ? 'REVOKED' : 'ACTIVE')
  const currentIds = rows.map(row => row.subscriptionId)
  const calls: { method: string; path: string }[] = []
  let accountId = ACCOUNT
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    calls.push({ method: request.method(), path })
    if (path.endsWith('/auth/session')) return route.fulfill({ json: { authenticated: true, accountId,
      csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'bulk-test-csrf' } })
    if (path.endsWith('/auth/csrf')) return route.fulfill({ json: { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'bulk-test-csrf' } })
    if (path.endsWith('/auth/account')) return route.fulfill({ json: { accountId, displayName: '내 계정',
      identities: [{ provider: 'google', email: null, emailVerified: false }] } })
    if (path.includes('calendar-subscription')) {
      expect(request.headers()['x-baton-access-key']).toBeUndefined()
      if (request.headers()['x-baton-account-id'] !== accountId) return route.fulfill({ status: 403,
        json: { code: 'CAL_SUBSCRIPTION_ACCOUNT_CHANGED', message: '로그인 계정이 바뀌었습니다.' } })
    }
    if (path === '/api/v1/me/calendar-subscriptions') {
      const query = url.searchParams.get('query') ?? ''
      const after = url.searchParams.get('afterSeasonId')
      const visible = accountId === ACCOUNT ? rows.filter((row, index) => (!after || row.seasonId > after)
        && (row.teamName.includes(query) || row.seasonName.includes(query))
        && (url.searchParams.get('includeRevoked') !== 'false' || statuses[index] !== 'REVOKED')) : []
      const size = options.pageSize ?? 2
      const subscriptions = visible.slice(0, size).map(row => {
        const index = rows.indexOf(row)
        return { ...row, subscriptionId: currentIds[index], managementStatus: ['REVOKED', 'REVOCATION_PENDING'].includes(statuses[index]!) ? statuses[index] : 'CHECK_REQUIRED' }
      })
      return route.fulfill({ json: { accountId, subscriptions,
        nextAfterSeasonId: visible.length > size ? subscriptions.at(-1)?.seasonId : null } })
    }
    const index = rows.findIndex(row => path === `/api/v1/teams/${row.teamId}/seasons/${row.seasonId}/calendar-subscription`)
    if (index >= 0) {
      const failure = options.failures?.[index]
      if (request.method() === 'GET') {
        if (failure === 'hold-status') return
        return route.fulfill({ json: { subscriptionId: currentIds[index], seasonId: rows[index]!.seasonId, status: statuses[index] } })
      }
      expect(request.method()).toBe('DELETE')
      expect(request.headers()['x-csrf-token']).toBe('bulk-test-csrf')
      if (failure === 'hold') return
      if (failure === 'account') return route.fulfill({ status: 403,
        json: { code: 'CAL_SUBSCRIPTION_ACCOUNT_CHANGED', message: '로그인 계정이 바뀌었습니다.' } })
      if (failure === 'pending') statuses[index] = 'REVOCATION_PENDING'
      else if (failure !== 'unavailable') statuses[index] = 'REVOKED'
      if (failure) return route.fulfill({ status: 503,
        json: { code: 'CAL_SUBSCRIPTION_UNAVAILABLE', message: '구독 해제 결과를 확인하지 못했습니다.' } })
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ status: 403, json: { code: 'WORKSPACE_ACCESS_DENIED', message: '접근 권한이 없습니다.' } })
  })
  await page.goto('/account')
  const list = page.getByRole('region', { name: '내 캘린더 구독', exact: true })
  await expect(list.getByText('독서 모임 1', { exact: true })).toBeVisible()
  const selection = (index: number) => list.getByRole('checkbox', { name: `독서 모임 ${index + 1} · 가을 시즌 ${index + 1} 선택`, exact: true })
  return { list, calls, selection, rows, replace: (index: number) => { currentIds[index] = id(999) },
    changeAccount: () => { accountId = OTHER_ACCOUNT } }
}

async function selectAll(list: ReturnType<Page['getByRole']>) {
  await list.getByRole('button', { name: '여러 구독 선택', exact: true }).click()
  await list.getByRole('button', { name: '불러온 항목 모두 선택', exact: true }).click()
  await list.getByRole('button', { name: /선택한 구독 \d+개 해제/ }).click()
}

test('여러 구독을 페이지에 걸쳐 선택하고 대상 확인 후 불러온 항목만 해제한다 @smoke @responsive', async ({ page }, testInfo) => {
  const { list, calls, selection } = await setup(page)
  await list.getByRole('button', { name: '여러 구독 선택' }).click()
  await selection(0).check()
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(selection(0)).toBeChecked()
  await expect(selection(3)).toBeDisabled()
  await list.getByRole('button', { name: '불러온 항목 모두 선택' }).click()
  await list.getByRole('button', { name: '선택한 구독 3개 해제', exact: true }).click()
  const confirmation = list.getByRole('group', { name: '선택한 구독 해제 확인', exact: true })
  await expect(confirmation.getByRole('listitem')).toHaveCount(3)
  await expect(confirmation).not.toContainText('독서 모임 5')
  await expect(list.getByLabel('팀·시즌 검색', { exact: true })).toBeDisabled()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(0)
  await confirmation.getByRole('button', { name: '취소', exact: true }).click()
  await expect(selection(0)).toBeChecked()
  await list.getByRole('button', { name: '선택한 구독 3개 해제', exact: true }).click()
  await confirmation.screenshot({ path: testInfo.outputPath('bulk-calendar-confirmation.png') })
  await confirmation.getByRole('button', { name: /^\d+개 구독 해제$/, exact: true }).click()
  const result = list.getByRole('region', { name: '선택 해제 결과', exact: true })
  await expect(result).toContainText('해제됨 3개 · 처리 중 0개 · 확인 필요 0개')
  await expect(list.getByRole('button', { name: '선택 마치기' })).toBeEnabled()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(3)
  expect(new Set(calls.filter(call => call.method === 'DELETE').map(call => call.path)).size).toBe(3)
  expect(calls.some(call => call.method === 'POST')).toBe(false)
  await list.screenshot({ path: testInfo.outputPath('bulk-calendar-results.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await list.getByLabel('해제된 구독 숨기기', { exact: true }).check()
  await list.getByLabel('팀·시즌 검색', { exact: true }).fill('독서 모임 1')
  await list.getByRole('button', { name: '검색', exact: true }).click()
  await expect(list.getByText('조건에 맞는 구독이 없습니다.', { exact: false })).toBeVisible()
  await list.getByRole('button', { name: '선택 마치기' }).click()
  await expect(list.getByRole('button', { name: '선택한 구독 0개 해제', exact: true })).toHaveCount(0)
})

test('응답 유실은 상태로 확인하고 일부 실패가 있어도 나머지 구독 결과를 표시한다 @smoke', async ({ page }) => {
  const { list, calls } = await setup(page, { count: 3, pageSize: 20, revoked: [], failures: { 0: 'lost', 1: 'pending', 2: 'unavailable' } })
  await selectAll(list)
  await list.getByRole('button', { name: /^\d+개 구독 해제$/, exact: true }).click()
  const result = list.getByRole('region', { name: '선택 해제 결과', exact: true })
  await expect(result).toContainText('해제됨 1개 · 처리 중 1개 · 확인 필요 1개')
  await expect(result.getByRole('listitem').filter({ hasText: '독서 모임 1' })).toContainText('해제됨')
  await expect(result.getByRole('listitem').filter({ hasText: '독서 모임 2' })).toContainText('해제 처리 중')
  await expect(result.getByRole('listitem').filter({ hasText: '독서 모임 3' })).toContainText('해제 여부 확인 필요')
  await expect(list.getByRole('button', { name: '선택 마치기' })).toBeEnabled()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(3)
  await list.getByRole('button', { name: '선택 마치기' }).click()
  await list.locator('summary').filter({ hasText: '독서 모임 2' }).click()
  await expect(list.getByText('구독 해제를 처리 중입니다.', { exact: false })).toBeVisible()
})

test('계정 확인이 실패하면 남은 구독의 해제 요청을 보내지 않는다 @smoke', async ({ page }) => {
  const { list, calls } = await setup(page, { count: 3, pageSize: 20, revoked: [], failures: { 0: 'account' } })
  await selectAll(list)
  await list.getByRole('button', { name: /^\d+개 구독 해제$/, exact: true }).click()
  const result = list.getByRole('region', { name: '선택 해제 결과', exact: true })
  await expect(result).toContainText('로그인 계정 확인 필요')
  await expect(result.getByText('요청하지 않음', { exact: true })).toHaveCount(2)
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
})

test('사용자가 중단하면 진행 중 요청은 확인 필요로 남기고 다음 구독을 해제하지 않는다 @smoke', async ({ page }) => {
  const { list, calls } = await setup(page, { count: 3, pageSize: 20, revoked: [], failures: { 0: 'hold' } })
  await selectAll(list)
  await list.getByRole('button', { name: /^\d+개 구독 해제$/, exact: true }).click()
  await expect.poll(() => calls.filter(call => call.method === 'DELETE').length).toBe(1)
  await list.getByRole('button', { name: '해제 작업 중단' }).click()
  const result = list.getByRole('region', { name: '선택 해제 결과', exact: true })
  await expect(result.getByText('해제 여부 확인 필요', { exact: true })).toHaveCount(1)
  await expect(result.getByText('요청하지 않음', { exact: true })).toHaveCount(2)
  await expect(list.getByRole('button', { name: '선택 마치기' })).toBeEnabled()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
})

test('계정이 바뀌면 이전 계정의 선택과 진행 중 조회를 버린다 @smoke', async ({ page }) => {
  const { list, calls, changeAccount } = await setup(page, { count: 3, pageSize: 20, revoked: [], failures: { 0: 'hold-status' } })
  await selectAll(list)
  await list.getByRole('button', { name: /^\d+개 구독 해제$/, exact: true }).click()
  await expect.poll(() => calls.filter(call => call.path.endsWith('/calendar-subscription')).length).toBe(1)
  changeAccount()
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(list.getByText('아직 구독 기록이 없습니다.', { exact: false })).toBeVisible()
  await expect(list.getByRole('region', { name: '선택 해제 결과', exact: true })).toHaveCount(0)
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(0)
})

test('한 번에 20개까지만 선택하고 검색 조건을 바꾸면 이전 선택을 지운다 @smoke', async ({ page }) => {
  const { list, calls, selection } = await setup(page, { count: 22, pageSize: 20, revoked: [] })
  await list.getByRole('button', { name: '여러 구독 선택' }).click()
  await list.getByRole('button', { name: '불러온 항목 모두 선택' }).click()
  await list.getByRole('button', { name: '구독 더 보기' }).click()
  await expect(selection(20)).toBeDisabled()
  await expect(list.getByRole('checkbox', { checked: true })).toHaveCount(20)
  await selection(0).uncheck()
  await selection(20).check()
  await expect(list.getByRole('button', { name: '선택한 구독 20개 해제', exact: true })).toBeEnabled()
  await list.getByLabel('팀·시즌 검색', { exact: true }).fill('독서 모임 22')
  await list.getByRole('button', { name: '검색', exact: true }).click()
  await expect(selection(21)).not.toBeChecked()
  await expect(list.getByRole('button', { name: '선택한 구독 0개 해제', exact: true })).toBeDisabled()
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(0)
})

test('실행 전에 다른 구독으로 바뀐 항목은 건너뛴다 @smoke', async ({ page }) => {
  const { list, calls, replace, rows } = await setup(page, { count: 2, pageSize: 20, revoked: [] })
  await selectAll(list)
  replace(0)
  await list.getByRole('button', { name: /^\d+개 구독 해제$/, exact: true }).click()
  const result = list.getByRole('region', { name: '선택 해제 결과', exact: true })
  await expect(result.getByRole('listitem').filter({ hasText: '독서 모임 1' })).toContainText('구독 변경됨 · 다시 확인')
  await expect(result.getByRole('listitem').filter({ hasText: '독서 모임 2' })).toContainText('해제됨')
  expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(1)
  expect(calls.find(call => call.method === 'DELETE')?.path).toContain(rows[1]!.seasonId)
})
