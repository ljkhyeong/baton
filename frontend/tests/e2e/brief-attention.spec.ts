import { weeklyResolutions } from './support/briefFixtures'
import { expect, test } from '@playwright/test'
import { installApi, openSharedWorkspace, TEAM_ID, MEMBER_ONE_ID, ACCESS_KEY } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'

test('BRIEF 요약에서 조건을 선택하고 다음 페이지와 필터 초기화를 확인한다 @smoke', async ({ page }) => {
  await installApi(page)
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: {
    authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'test-token',
  } }))
  await page.route('**/api/v1/account-memberships/current?*', (route) => route.fulfill({ json: {
    claimed: true, accountId: ACCOUNT, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: '2026-08-31T00:00:00Z',
  } }))
  const calls: URLSearchParams[] = []
  const historyCalls: URLSearchParams[] = []
  let unavailable = false
  let denied = false
  await page.route('**/brief/attention-items**', async (route) => {
    expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
    expect(route.request().headers().authorization).toBeUndefined()
    const url = new URL(route.request().url())
    if (denied) return route.fulfill({ status: 403, json: { code: 'BRIEF_ACCESS_DENIED', message: '활동 중인 팀 구성원만 조회할 수 있습니다.' } })
    if (url.pathname.endsWith('/transitions')) {
      historyCalls.push(url.searchParams)
      const older = url.searchParams.has('beforeAggregateRevision')
      return route.fulfill({ json: { transitions: [{ eventId: ACCOUNT, aggregateRevision: older ? 2 : 3,
        state: older ? 'RESOLVED' : 'ACTIVE', observedAt: '2026-08-31T00:00:00Z', detectedRevisionGap: older, sourceSeverity: older ? 'CRITICAL' : 'WARNING' }],
      nextBeforeAggregateRevision: older ? null : 3 } })
    }
    if (url.pathname.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
    if (url.pathname.endsWith('/summary')) {
      return unavailable
        ? route.fulfill({ status: 503, json: { code: 'BRIEF_UNAVAILABLE', message: 'BRIEF에 연결할 수 없습니다.' } })
        : route.fulfill({ json: { highCount: 2, mediumCount: 1, revisionGapCount: 1 } })
    }
    calls.push(url.searchParams)
    if (url.searchParams.get('status') === 'RESOLVED') return route.fulfill({ json: { items: [], nextCursor: null } })
    const gap = url.searchParams.get('revisionGap') === 'true'
    const after = url.searchParams.get('afterSourceReference')
    const sourceReference = gap ? 'role:gap' : after ? 'role:second' : 'role:+& 한글'
    return route.fulfill({ json: { items: [{ reasonCode: 'ROLE_UNASSIGNED', severity: gap ? 'MEDIUM' : 'HIGH',
      sourceReference, status: 'ACTIVE', observedAt: '2026-08-31T00:00:00Z', aggregateRevision: 3, ruleVersion: 1, revisionGap: gap }],
    nextCursor: !gap && !after ? { eventType: 'ROLE_UNASSIGNED', sourceReference } : null } })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  await panel.locator('summary').click()
  await expect(panel.getByRole('button', { name: '높은 심각도 2건' })).toBeVisible()
  expect(historyCalls).toHaveLength(0)
  await panel.getByRole('button', { name: '상태 변화 보기' }).click()
  const history = panel.getByRole('region', { name: '관심 항목 상태 변화' })
  await expect(history.getByText('활성', { exact: true })).toBeVisible()
  await expect(history.getByText('원본 심각도: 주의')).toBeVisible()
  expect(historyCalls.at(-1)?.get('sourceReference')).toBe('role:+& 한글')
  await history.getByRole('button', { name: '이전 상태 변화' }).click()
  await expect(history.getByText('해소', { exact: true })).toBeVisible()
  await expect(history.getByText('원본 심각도: 긴급')).toBeVisible()
  await history.getByText('변경 근거 보기', { exact: true }).click()
  await expect(history.getByText('리비전 2', { exact: true })).toBeVisible()
  await expect(history.getByText('이 전이에서 공백 발견', { exact: true })).toBeVisible()
  expect(historyCalls.at(-1)?.get('beforeAggregateRevision')).toBe('3')
  await history.getByRole('button', { name: '최신 전이부터 새로고침' }).click()
  await expect(history.getByText('활성', { exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '높은 심각도 2건' }).click()
  await expect(history).toHaveCount(0)
  await panel.getByRole('combobox', { name: '중간 기록 공백', exact: true }).selectOption('false')
  await expect.poll(() => calls.at(-1)?.get('revisionGap')).toBe('false')
  await panel.getByRole('button', { name: '다음 페이지' }).click()
  await expect(panel.getByText('role:second', { exact: true })).toBeAttached()
  await panel.getByText('원본 기록 보기', { exact: true }).click()
  await expect(panel.getByText('role:second', { exact: true })).toBeVisible()
  expect(calls.at(-1)?.get('afterSourceReference')).toBe('role:+& 한글')
  expect(calls.at(-1)?.get('severity')).toBe('HIGH')
  expect(calls.at(-1)?.get('revisionGap')).toBe('false')
  await panel.getByRole('button', { name: '중간 기록 공백 1건' }).click()
  await expect(panel.getByText('role:gap', { exact: true })).toBeAttached()
  expect(await panel.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect(calls.at(-1)?.get('afterEventType')).toBeNull()
  expect(calls.at(-1)?.get('severity')).toBeNull()
  await panel.getByRole('combobox', { name: '상태', exact: true }).selectOption('RESOLVED')
  await expect(panel.getByText('선택한 조건에 해당하는 관심 항목이 없습니다.')).toBeVisible()
  unavailable = true
  await panel.getByRole('button', { name: '첫 페이지부터 새로고침' }).click()
  await expect(panel.getByRole('alert')).toContainText('요약을 불러오지 못했습니다.')
  await expect(panel.getByRole('button', { name: '높은 심각도 0건' })).toHaveCount(0)
  unavailable = false
  await panel.getByRole('combobox', { name: '상태', exact: true }).selectOption('ACTIVE')
  await expect(panel.getByText('role:gap', { exact: true })).toBeAttached()
  denied = true
  await panel.getByRole('button', { name: '상태 변화 보기' }).click()
  await expect(panel.getByText('role:gap', { exact: true })).toHaveCount(0)
  await expect(panel.getByRole('alert').last()).toContainText('활동 중인 팀 구성원')
})

test('로그인하지 않은 사용자는 BRIEF를 호출하지 않고 로그인 안내를 본다 @smoke', async ({ page }) => {
  await installApi(page)
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: { authenticated: false } }))
  let briefCalls = 0
  await page.route('**/brief/attention-items**', async (route) => { briefCalls += 1; await route.abort() })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  await panel.locator('summary').click()
  await expect(panel.getByRole('link', { name: '로그인', exact: true })).toBeVisible()
  expect(briefCalls).toBe(0)
})
