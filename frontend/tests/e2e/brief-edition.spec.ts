import { weeklyResolutions } from './support/briefFixtures'
import { expect, test } from '@playwright/test'
import { installApi, makeProjection, openSharedWorkspace, TEAM_ID, SEASON_ID, MEMBER_ONE_ID, ACCESS_KEY, WORKSPACE_PATH } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'
const EDITION = '8e448211-66ae-44ab-9888-c4960648c22c'

test('최신 주간 요약 없음과 전달 대기 뒤 생성·재사용·권한 거부를 구분한다 @smoke', async ({ page }, testInfo) => {
  await installApi(page)
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: {
    authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'test-token',
  } }))
  await page.route('**/api/v1/auth/csrf', (route) => route.fulfill({ json: { csrfHeaderName: 'X-BRIEF-CSRF', csrfToken: 'fresh-token' } }))
  await page.route('**/api/v1/account-memberships/current?*', (route) => route.fulfill({ json: {
    claimed: true, accountId: ACCOUNT, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: '2026-08-31T00:00:00Z',
  } }))
  let generations = 0
  let latestCalls = 0
  const relatedReads = { summary: 0, items: 0, resolutions: 0 }
  let denied = false
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    expect(request.headers()['x-baton-access-key']).toBe(ACCESS_KEY)
    expect(request.headers().authorization).toBeUndefined()
    if (path.endsWith('/delivery-status')) return route.fulfill({ json: { editionId: EDITION, status: generations < 3 ? 'ADDITIONAL_DELIVERIES' : 'NO_ADDITIONAL_DELIVERIES', checkedAt: '2026-09-05T00:00:00Z' } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: '2026-09-05T00:00:00Z' } })
    if (path.endsWith('/editions') && route.request().method() === 'GET') return route.fulfill({ json: { editions: [], nextBeforeGeneration: null } })
    if (path.endsWith('/resolutions')) { relatedReads.resolutions += 1; return route.fulfill({ json: weeklyResolutions }) }
    if (path.endsWith('/summary')) { relatedReads.summary += 1; return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } }) }
    if (path.endsWith('/attention-items')) { relatedReads.items += 1; return route.fulfill({ json: { items: [], nextCursor: null } }) }
    if (request.method() === 'POST') {
      generations += 1
      expect(request.postData()).toBeNull()
      expect(request.headers()['x-brief-csrf']).toBe('fresh-token')
      if (generations === 1) return route.fulfill({ status: 409, json: { code: 'BRIEF_DELIVERY_INCOMPLETE', message: '원본 이벤트 전달이 끝난 뒤 다시 생성해 주세요.' } })
      return route.fulfill({ status: generations === 2 ? 201 : 200, json: {
        executionId: ACCOUNT, editionId: EDITION, generation: 1, deliveryWatermark: 4, sourceCursor: 4, created: generations === 2,
      } })
    }
    latestCalls += 1
    if (denied) return route.fulfill({ status: 403, json: { code: 'BRIEF_ACCESS_DENIED', message: '활동 중인 팀 구성원만 조회할 수 있습니다.' } })
    if (generations < 2) return route.fulfill({ status: 404, json: { code: 'BRIEF_EDITION_NOT_FOUND', message: '저장된 주간 요약이 없습니다.' } })
    return route.fulfill({ json: { editionId: EDITION, workspaceId: TEAM_ID, seasonId: SEASON_ID, generation: 1,
      weekStart: '2026-08-24', zoneId: 'America/New_York', windowStart: '2026-08-24T04:00:00Z', windowEnd: '2026-08-31T04:00:00Z',
      generatedAt: '2026-08-31T00:00:00Z', sourceCursor: 4, ruleVersion: 2,
      items: [{ reasonCode: 'ROLE_UNASSIGNED', severity: 'HIGH', status: 'ACTIVE', sourceReference: 'role:current',
        observedAt: '2026-08-30T00:00:00Z', ruleVersion: 1, aggregateRevision: 2, revisionGap: false, section: 'CURRENT_WEEK' },
      { reasonCode: 'HANDOFF_INCOMPLETE', severity: 'HIGH', status: 'ACTIVE', sourceReference: 'handoff:carry',
        observedAt: '2026-08-20T00:00:00Z', ruleVersion: 1, aggregateRevision: 1, revisionGap: false, section: 'CARRY_OVER' }],
    } })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  await panel.locator('summary').click()
  expect(latestCalls).toBe(0)
  await panel.getByText('저장된 주간 요약', { exact: true }).click()
  const edition = panel.getByRole('region', { name: '저장된 주간 요약' })
  await expect(edition.getByText('아직 저장된 주간 요약이 없습니다.')).toBeVisible()
  const generate = edition.getByRole('button', { name: '이번 주 요약 만들기', exact: true })
  const initialReads = { ...relatedReads }
  await generate.click()
  await expect(edition.getByRole('alert')).toContainText('원본 이벤트 전달이 끝난 뒤')
  expect(generations).toBe(1)
  expect(relatedReads).toEqual(initialReads)
  await generate.click()
  await expect(edition.getByRole('status')).toContainText('새 주간 요약을 생성했습니다.')
  await expect.poll(() => Object.values(relatedReads)).toEqual(Object.values(initialReads).map((count) => count + 1))
  await expect(edition.getByText('2026-08-24 시작 주 · 요약 버전 1')).toBeVisible()
  await expect(edition.getByRole('region', { name: '저장 이후 변경 확인' })).toContainText('새 변경이 전달됐습니다.')
  await expect(edition).toContainText('America/New_York')
  await expect(edition.getByRole('region', { name: '이번 주 변경' })).toContainText('역할 담당자 없음')
  await expect(edition.locator('details[aria-label="저장된 요약 연동 상세"]')).toContainText('role:current')
  await expect(edition.getByRole('region', { name: '이전 주부터 미해결' })).toContainText('인수인계 미완료')
  await expect(edition.locator('details[aria-label="저장된 요약 연동 상세"]')).toContainText('handoff:carry')
  expect(await panel.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await edition.getByRole('region', { name: '이전 주부터 미해결' }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('brief-edition.png'), fullPage: true })
  await generate.click()
  await expect(edition.getByRole('status')).toContainText('내용이 같아 기존 요약을 불러왔습니다.')
  expect(generations).toBe(3)
  await expect.poll(() => Object.values(relatedReads)).toEqual(Object.values(initialReads).map((count) => count + 2))
  await expect(edition.getByRole('region', { name: '저장 이후 변경 확인' })).toContainText('추가 전달 기록이 없습니다.')
  denied = true
  await edition.getByRole('button', { name: '최신 요약 조회' }).click()
  await expect(panel.getByText('role:current', { exact: true })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '이번 주 요약 만들기', exact: true })).toHaveCount(0)
})

for (const access of ['ended', 'viewer'] as const) {
  test(`${access === 'ended' ? '종료 시즌은' : '공유 키 없이 접속한 열람자는'} 저장된 주간 요약만 조회하고 생성을 막는다 @smoke`, async ({ page }) => {
    const projection = makeProjection()
    if (access === 'ended') {
      projection.season.endedAt = '2026-08-31T00:00:00Z'
      projection.seasons.find((season) => season.id === SEASON_ID)!.endedAt = projection.season.endedAt
    } else {
      projection.team.accountAccessEnabled = true
      projection.team.permission = 'VIEWER'
    }
    await installApi(page, projection)
    await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: {
      authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'test-token',
    } }))
    await page.route('**/api/v1/account-memberships/current?*', (route) => route.fulfill({ json: {
      claimed: true, accountId: ACCOUNT, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: '2026-08-31T00:00:00Z',
    } }))
    await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      expect(route.request().method()).toBe('GET')
      if (access === 'viewer') expect(route.request().headers()['x-baton-access-key']).toBeFalsy()
      if (path.endsWith('/delivery-status')) return route.fulfill({ json: { editionId: EDITION, status: 'UNKNOWN', checkedAt: '2026-09-05T00:00:00Z' } })
      if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: '2026-09-05T00:00:00Z' } })
      if (path.endsWith('/editions') && route.request().method() === 'GET') return route.fulfill({ json: { editions: [], nextBeforeGeneration: null } })
      if (path.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
      if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } })
      if (path.endsWith('/attention-items')) return route.fulfill({ json: { items: [], nextCursor: null } })
      return route.fulfill({ json: { editionId: EDITION, workspaceId: TEAM_ID, seasonId: SEASON_ID, generation: 1,
        weekStart: '2026-08-24', zoneId: 'Asia/Seoul', windowStart: '2026-08-23T15:00:00Z', windowEnd: '2026-08-30T15:00:00Z',
        generatedAt: '2026-08-29T00:00:00Z', sourceCursor: 1, ruleVersion: 1,
        items: [{ reasonCode: 'ROUTINE_MISSED', severity: 'MEDIUM', status: 'ACTIVE', sourceReference: 'legacy:+& 한글',
          observedAt: '2026-08-28T00:00:00Z', ruleVersion: 1, aggregateRevision: null, revisionGap: null, section: null }],
      } })
    })
    if (access === 'viewer') await page.goto(WORKSPACE_PATH)
    else await openSharedWorkspace(page)
    const panel = page.locator('.brief-attention')
    await panel.locator('summary').click()
    await panel.getByText('저장된 주간 요약', { exact: true }).click()
    await expect(panel.getByRole('region', { name: '이전 주간 요약 · 분류 미기록' })).toContainText('반복 업무 누락')
    await expect(panel.locator('details[aria-label="저장된 요약 연동 상세"]')).toContainText('legacy:+& 한글')
    await expect(panel.getByText('이전 주간 요약: 변경 번호·누락 여부 미기록')).not.toBeVisible()
    await panel.getByText('연동 상세', { exact: true }).click()
    await expect(panel.getByText('이전 주간 요약: 변경 번호·누락 여부 미기록')).toBeVisible()
    if (access === 'ended') {
      await expect(panel.getByText('종료된 시즌은 저장된 주간 요약만 조회할 수 있습니다.')).toBeVisible()
    } else {
      await expect(panel.getByText('종료된 시즌은 저장된 주간 요약만 조회할 수 있습니다.')).toHaveCount(0)
    }
    await expect(panel.getByRole('button', { name: '이번 주 요약 만들기', exact: true })).toBeDisabled()
  })
}
