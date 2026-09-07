import { weeklyResolutions } from './support/briefFixtures'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { installApi, makeProjection, navigation, openSharedWorkspace, TEAM_ID, SEASON_ID, MEMBER_ONE_ID, ROLE_ID, ROUTINE_ID, ACCESS_KEY, WORKSPACE_PATH } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'
const OLD = '8e448211-66ae-44ab-9888-c4960648c221'
const MIDDLE = '8e448211-66ae-44ab-9888-c4960648c222'
const LATEST = '8e448211-66ae-44ab-9888-c4960648c223'
const SOURCE = 'baton-continuity:8e448211-66ae-44ab-9888-c4960648c224'
const currentItem = { reasonCode: 'ROLE_UNASSIGNED', sourceReference: SOURCE, severity: 'HIGH', status: 'ACTIVE',
  observedAt: '2026-08-25T00:00:00Z', ruleVersion: 1, aggregateRevision: 1, revisionGap: false, section: 'CARRY_OVER' }
const oldItem = { ...currentItem, section: 'CURRENT_WEEK' }
const summaries = [LATEST, MIDDLE, OLD].map((editionId, index) => ({ editionId, generation: 3 - index,
  weekStart: index === 0 ? '2026-08-31' : '2026-08-24', zoneId: 'Asia/Seoul', generatedAt: '2026-09-05T00:00:00Z', sourceCursor: 3 - index, ruleVersion: 2, itemCount: 1 }))
function edition(id: string) {
  const summary = summaries.find((entry) => entry.editionId === id)!
  return { ...summary, workspaceId: TEAM_ID, seasonId: SEASON_ID,
    windowStart: summary.weekStart === '2026-08-31' ? '2026-08-30T15:00:00Z' : '2026-08-23T15:00:00Z',
    windowEnd: summary.weekStart === '2026-08-31' ? '2026-09-06T15:00:00Z' : '2026-08-30T15:00:00Z', items: [id === OLD ? oldItem : currentItem] }
}
async function login(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: { authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'test-token' } }))
  await page.route('**/api/v1/auth/csrf', (route) => route.fulfill({ json: { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'test-token' } }))
  await page.route('**/api/v1/account-memberships/current?*', (route) => route.fulfill({ json: { claimed: true, accountId: ACCOUNT, teamId: TEAM_ID,
    memberId: MEMBER_ONE_ID, claimedAt: '2026-08-31T00:00:00Z' } }))
}

test('지난 주간 요약을 탐색·비교하고 현재 업무로 이동한다 @smoke', async ({ page }, testInfo) => {
  const projection = makeProjection()
  await installApi(page, projection); await login(page)
  const historyCursors: (string | null)[] = []
  let malformed = false
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname
    expect(request.headers()['x-baton-access-key']).toBe(ACCESS_KEY)
    expect(request.headers().authorization).toBeUndefined()
    if (path.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/attention-items')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: '2026-09-05T00:00:00Z' } })
    if (path.endsWith('/delivery-status')) return route.fulfill({ json: { editionId: path.split('/').at(-2), status: 'NO_ADDITIONAL_DELIVERIES', checkedAt: '2026-09-05T00:00:00Z' } })
    if (path.endsWith('/sources/query')) {
      expect(request.headers()['x-csrf-token']).toBe('test-token')
      const sources = request.postDataJSON().sources as { eventType: string; sourceReference: string }[]
      expect(sources).toHaveLength(1)
      return route.fulfill({ json: { sources: sources.map((source) => ({ ...source,
        target: { title: projection.roles.find((role) => role.id === ROLE_ID)!.name, roleId: ROLE_ID, routineId: null, archived: false } })) } })
    }
    if (path.endsWith('/editions')) {
      historyCursors.push(url.searchParams.get('beforeGeneration'))
      return route.fulfill({ json: url.searchParams.has('beforeGeneration') ? { editions: [summaries[2]], nextBeforeGeneration: null }
        : { editions: summaries.slice(0, 2), nextBeforeGeneration: 2 } })
    }
    if (path.endsWith('/changes')) {
      expect(url.searchParams.get('fromEditionId')).toBe(OLD)
      expect(path).toContain(MIDDLE)
      return route.fulfill({ json: { from: summaries[2], to: malformed ? summaries[0] : summaries[1], added: [], removed: [], changed: [{ before: oldItem, after: currentItem }] } })
    }
    return route.fulfill({ json: edition(path.endsWith('/latest') ? LATEST : path.split('/').at(-1)!) })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  await panel.locator('summary').click(); await panel.getByText('저장된 주간 요약', { exact: true }).click()
  await expect(panel.getByRole('heading', { name: '2026-08-31 시작 주 · 요약 버전 3', exact: true })).toBeVisible()
  await panel.getByRole('button', { name: '이전 요약 더보기' }).click()
  await expect.poll(() => historyCursors.at(-1)).toBe('2')
  await panel.getByRole('combobox', { name: '조회할 요약' }).selectOption(MIDDLE)
  await expect(panel.getByRole('heading', { name: '2026-08-24 시작 주 · 요약 버전 2', exact: true })).toBeVisible()
  await panel.getByRole('combobox', { name: '비교할 요약' }).selectOption(OLD)
  const comparison = panel.getByRole('region', { name: '주간 요약 비교 결과' })
  await expect(comparison).toContainText('분류 변경: 이번 주 변경 → 이전 주부터 미해결')
  await expect(comparison).toContainText('추가 0건 · 제외 0건 · 변경 1건')
  await panel.getByRole('combobox', { name: '심각도', exact: true }).selectOption('HIGH')
  await panel.getByText('저장된 주간 요약', { exact: true }).click()
  await panel.getByText('저장된 주간 요약', { exact: true }).click()
  await expect(panel.getByRole('combobox', { name: '조회할 요약' })).toHaveValue(MIDDLE)
  await panel.locator(':scope > summary').click()
  await panel.locator(':scope > summary').click()
  await expect(panel.getByRole('combobox', { name: '심각도', exact: true })).toHaveValue('HIGH')
  await expect(panel.getByRole('combobox', { name: '조회할 요약' })).toHaveValue(MIDDLE)
  await expect(panel.getByRole('combobox', { name: '비교할 요약' })).toHaveValue(OLD)
  await expect(comparison).toContainText('추가 0건 · 제외 0건 · 변경 1건')
  await expect(comparison).toContainText('문제가 해결됐다는 뜻은 아닙니다.')
  await expect(comparison.getByRole('button', { name: '담당자 확인' }).first()).toBeVisible()
  await expect(comparison.getByText('현재 담당자와 담당 기간을 확인해 주세요.').first()).toBeVisible()
  await expect(comparison.locator('code').first()).not.toBeVisible()
  await comparison.getByText('연동 상세', { exact: true }).first().click()
  await expect(comparison.locator('code').first()).toBeVisible()
  expect(await comparison.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await comparison.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('brief-history-comparison.png'), fullPage: true })
  malformed = true
  await panel.getByRole('combobox', { name: '비교할 요약' }).selectOption('')
  await panel.getByRole('combobox', { name: '비교할 요약' }).selectOption(OLD)
  await expect(comparison.getByRole('alert')).toContainText('비교 결과를 불러오지 못했습니다.')
  await expect(comparison.getByText('추가 0건 · 제외 0건 · 변경 1건')).toHaveCount(0)
  await panel.getByRole('region', { name: '이전 주부터 미해결' }).getByRole('button', { name: '담당자 확인' }).click()
  await expect(page.locator('.role-row.selected')).toContainText(projection.roles.find((role) => role.id === ROLE_ID)!.name)
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '상세 닫기' }).click()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘', exact: true }).click()
  await expect(panel.getByRole('combobox', { name: '심각도', exact: true })).toHaveValue('HIGH')
  await expect(panel.getByRole('combobox', { name: '조회할 요약' })).toHaveValue(MIDDLE)
  await expect(panel.getByRole('combobox', { name: '비교할 요약' })).toHaveValue(OLD)
})

test('전달 대기와 실패를 미리 표시하고 준비 완료 때만 생성을 요청한다 @smoke', async ({ page }) => {
  await installApi(page); await login(page)
  let state = 'DELIVERY_PENDING'; let calls = 0
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/attention-items')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: state, pendingCount: state === 'DELIVERY_PENDING' ? 2 : 0,
      failedCount: state === 'DELIVERY_FAILED' ? 1 : 0, lastDeliveredAt: null, checkedAt: '2026-09-05T00:00:00Z' } })
    if (route.request().method() === 'POST') { calls++; state = 'DELIVERY_PENDING'; return route.fulfill({ status: 409,
      json: { code: 'BRIEF_DELIVERY_INCOMPLETE', message: '새 변경사항의 전달이 끝난 뒤 다시 생성해 주세요.' } }) }
    if (path.endsWith('/editions')) return route.fulfill({ json: { editions: [], nextBeforeGeneration: null } })
    return route.fulfill({ status: 404, json: { code: 'BRIEF_EDITION_NOT_FOUND', message: '아직 생성하지 않았습니다.' } })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention'); await panel.locator('summary').click(); await panel.getByText('저장된 주간 요약', { exact: true }).click()
  const generate = panel.getByRole('button', { name: '이번 주 요약 만들기', exact: true })
  await expect(generate).toBeDisabled(); await expect(panel).toContainText('전달 대기 2건')
  state = 'DELIVERY_FAILED'; await panel.getByRole('button', { name: '전달 상태 새로고침' }).click()
  await expect(panel).toContainText('변경사항 전송 실패 · 관리자 확인 필요'); await expect(generate).toBeDisabled()
  state = 'READY'; await panel.getByRole('button', { name: '전달 상태 새로고침' }).click()
  await expect(generate).toBeEnabled(); await generate.click()
  await expect(panel.getByRole('alert')).toContainText('새 변경사항의 전달이 끝난 뒤')
  await expect(generate).toBeDisabled(); expect(calls).toBe(1)
})

test('현재 점검 항목에서 보관된 원본 루틴으로 이동한다 @smoke', async ({ page }) => {
  const projection = makeProjection(); const routine = projection.routines.find((entry) => entry.id === ROUTINE_ID)!
  routine.archivedAt = '2026-09-01T00:00:00Z'
  await installApi(page, projection); await login(page)
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 1, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/sources/query')) return route.fulfill({ json: { sources: [{ eventType: 'ROUTINE_REPEATEDLY_OVERDUE', sourceReference: SOURCE,
      target: { title: routine.title, roleId: routine.ownerRoleId, routineId: ROUTINE_ID, archived: true } }] } })
    return route.fulfill({ json: { items: [{ ...currentItem, reasonCode: 'ROUTINE_REPEATEDLY_OVERDUE' }], nextCursor: null } })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention'); await panel.locator('summary').click()
  await expect(panel).toContainText('보관됨')
  await panel.getByRole('button', { name: '보관된 반복 업무 보기' }).click()
  await expect(page.locator(`.routine-archive-shelf [data-archived-routine-id="${ROUTINE_ID}"]`)).toBeVisible()
  await expect(page.getByRole('button', { name: `${routine.title} 반복 업무 복원` })).toBeFocused()
})


test('선택한 주간 요약의 추가 전달 상태를 구분하고 잘못된 대상·오류·권한 거부를 감춘다 @smoke', async ({ page }) => {
  await installApi(page); await login(page)
  let mode = 'ADDITIONAL_DELIVERIES'
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/attention-items')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: '2026-09-05T00:00:00Z' } })
    if (path.endsWith('/sources/query')) return route.fulfill({ json: { sources: route.request().postDataJSON().sources.map((source: object) => ({ ...source, target: null })) } })
    if (path.endsWith('/editions')) return route.fulfill({ json: { editions: summaries, nextBeforeGeneration: null } })
    if (path.endsWith('/delivery-status')) {
      if (mode === 'DENIED') return route.fulfill({ status: 403, json: { code: 'BRIEF_ACCESS_DENIED', message: '활동 중인 팀 구성원만 조회할 수 있습니다.' } })
      if (mode === 'UNAVAILABLE') return route.fulfill({ status: 503, json: { code: 'BRIEF_UNAVAILABLE', message: '일시적으로 연결할 수 없습니다.' } })
      const requestedId = path.split('/').at(-2)
      return route.fulfill({ json: { editionId: mode === 'WRONG_EDITION' ? LATEST : requestedId,
        status: requestedId === OLD ? 'UNKNOWN' : mode === 'WRONG_EDITION' ? 'NO_ADDITIONAL_DELIVERIES' : mode,
        checkedAt: '2026-09-05T00:00:00Z' } })
    }
    return route.fulfill({ json: edition(path.endsWith('/latest') ? LATEST : path.split('/').at(-1)!) })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention'); await panel.locator('summary').click()
  await panel.getByText('저장된 주간 요약', { exact: true }).click()
  const delivery = panel.getByRole('region', { name: '저장 이후 변경 확인' })
  await expect(delivery).toContainText('마지막 생성 확인 이후 새 변경이 전달됐습니다.')
  await panel.getByRole('combobox', { name: '조회할 요약' }).selectOption(OLD)
  await expect(delivery).toContainText('생성 확인 기록이 없어 추가 전달 여부를 알 수 없습니다.')
  await expect(panel.getByRole('button', { name: '이번 주 요약 만들기', exact: true })).toBeEnabled()
  mode = 'NO_ADDITIONAL_DELIVERIES'
  await panel.getByRole('combobox', { name: '조회할 요약' }).selectOption(MIDDLE)
  await expect(delivery).toContainText('마지막 생성 확인 이후 추가 전달 기록이 없습니다.')
  const refresh = delivery.getByRole('button', { name: '저장 이후 변경 새로고침' })
  for (const error of ['WRONG_EDITION', 'UNAVAILABLE']) {
    mode = error; await refresh.click()
    await expect(delivery).toContainText('추가 전달 기록을 불러오지 못했습니다.')
    await expect(delivery.getByText('마지막 생성 확인 이후 추가 전달 기록이 없습니다.')).toHaveCount(0)
  }
  mode = 'DENIED'; await refresh.click()
  await expect(panel.getByRole('alert')).toContainText('활동 중인 팀 구성원')
  await expect(delivery).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '이번 주 요약 만들기', exact: true })).toHaveCount(0)
})


test('이번 주 해결과 지난주 비교에서 결과·없음·장애·권한 거부를 구분한다 @smoke', async ({ page }) => {
  await installApi(page); await login(page)
  let previousStatus = 200
  let resolutionStatus = 200
  let resolvedCount = 2
  const previousTargets: string[] = []
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const url = new URL(route.request().url()); const path = url.pathname
    if (path.endsWith('/resolutions')) return route.fulfill(resolutionStatus === 200
      ? { json: { ...weeklyResolutions, resolvedCount, items: resolvedCount === 0 ? [] : weeklyResolutions.items } }
      : { status: resolutionStatus, json: { code: resolutionStatus === 403 ? 'BRIEF_ACCESS_DENIED' : 'BRIEF_UNAVAILABLE', message: '해결 조회 확인이 필요합니다.' } })
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/attention-items')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: weeklyResolutions.evaluatedAt } })
    if (path.endsWith('/delivery-status')) return route.fulfill({ json: { editionId: path.split('/').at(-2), status: 'UNKNOWN', checkedAt: weeklyResolutions.evaluatedAt } })
    if (path.endsWith('/sources/query')) return route.fulfill({ json: { sources: route.request().postDataJSON().sources.map((source: object) => ({ ...source, target: null })) } })
    if (path.endsWith('/editions')) return route.fulfill({ json: { editions: [summaries[0]], nextBeforeGeneration: null } })
    if (path.endsWith('/previous-week')) {
      previousTargets.push(path.split('/').at(-2)!)
      return route.fulfill(previousStatus === 200 ? { json: edition(MIDDLE) }
        : { status: previousStatus, json: { code: previousStatus === 404 ? 'BRIEF_EDITION_NOT_FOUND' : previousStatus === 403 ? 'BRIEF_ACCESS_DENIED' : 'BRIEF_UNAVAILABLE', message: '지난주 조회 확인이 필요합니다.' } })
    }
    if (path.endsWith('/changes')) {
      expect(url.searchParams.get('fromEditionId')).toBe(MIDDLE)
      expect(path).toContain(LATEST)
      return route.fulfill({ json: { from: summaries[1], to: summaries[0], added: [], removed: [], changed: [] } })
    }
    return route.fulfill({ json: edition(LATEST) })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  await panel.locator('summary').click()
  const resolutions = panel.getByRole('region', { name: '이번 주 해결 요약' })
  await expect(resolutions).toContainText('이번 주 해결 2건')
  resolutionStatus = 503
  await panel.getByRole('button', { name: '목록 새로고침' }).click()
  await expect(resolutions).toContainText('이번 주 해결 확인 실패')
  await expect(resolutions).not.toContainText('이번 주 해결 0건')
  resolutionStatus = 200; resolvedCount = 0
  await resolutions.getByRole('button', { name: '해결 요약 다시 조회' }).click()
  await expect(resolutions).toContainText('이번 주 해결 0건')
  await panel.getByText('저장된 주간 요약', { exact: true }).click()
  await expect(panel.getByRole('heading', { name: '2026-08-31 시작 주 · 요약 버전 3', exact: true })).toBeVisible()
  const button = panel.getByRole('button', { name: '지난주와 바로 비교' })
  await button.click()
  await expect(panel.getByRole('region', { name: '주간 요약 비교 결과' })).toContainText('기준: 2026-08-24 · 요약 버전 2')
  expect(previousTargets).toEqual([LATEST])
  previousStatus = 404
  await button.click()
  await expect(panel.getByText('선택한 주간 요약과 같은 시간대의 지난주 요약이 없습니다.')).toBeVisible()
  await expect(panel.getByRole('region', { name: '주간 요약 비교 결과' })).toHaveCount(0)
  previousStatus = 503
  await button.click()
  await expect(panel.getByText('지난주 요약을 불러오지 못했습니다. 다시 비교해 주세요.')).toBeVisible()
  previousStatus = 403
  await button.click()
  await expect(panel.getByRole('button', { name: '주간 요약 조회 권한 다시 확인' })).toBeVisible()
  await expect(panel.getByRole('heading', { name: '2026-08-31 시작 주 · 요약 버전 3', exact: true })).toHaveCount(0)
  resolutionStatus = 403
  await panel.getByRole('button', { name: '목록 새로고침' }).click()
  await expect(panel.getByRole('button', { name: '권한 다시 확인', exact: true })).toBeVisible()
  await expect(resolutions).toHaveCount(0)
})

test('해결 업무·시점을 탐색하고 생성 후 첫 페이지 갱신과 주간 변경을 처리한다 @smoke', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  const projection = makeProjection()
  await installApi(page, projection); await login(page)
  const first = { ...weeklyResolutions.items[0], sourceReference: SOURCE }
  const second = { ...weeklyResolutions.items[1], sourceReference: 'role:+& 한글' }
  const next = { eventType: first.reasonCode, sourceReference: first.sourceReference }
  let generated = false
  let nextWeek = false
  let denied = false
  const itemQueries: { after: string | null; severity: string | null }[] = []
  const resolutionCursors: (string | null)[] = []
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: generated ? 1 : 2, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/resolutions')) {
      resolutionCursors.push(url.searchParams.get('afterSourceReference'))
      if (denied) return route.fulfill({ status: 403, json: { code: 'BRIEF_ACCESS_DENIED', message: '활동 중인 팀 구성원만 조회할 수 있습니다.' } })
      if (nextWeek) return route.fulfill({ json: { ...weeklyResolutions, weekStart: '2026-09-07', windowStart: '2026-09-06T15:00:00Z',
        windowEnd: '2026-09-13T15:00:00Z', evaluatedAt: '2026-09-07T00:00:00Z', resolvedCount: 0, items: [], nextCursor: null } })
      return route.fulfill({ json: { ...weeklyResolutions, items: url.searchParams.has('afterSourceReference') ? [second] : [first],
        nextCursor: url.searchParams.has('afterSourceReference') ? null : next } })
    }
    if (path.endsWith('/attention-items')) {
      itemQueries.push({ after: url.searchParams.get('afterSourceReference'), severity: url.searchParams.get('severity') })
      const after = url.searchParams.has('afterSourceReference')
      return route.fulfill({ json: { items: [{ ...currentItem, sourceReference: after ? 'role:second' : SOURCE }], nextCursor: after ? null : next } })
    }
    if (path.endsWith('/transitions')) return route.fulfill({ json: { transitions: [], nextBeforeAggregateRevision: null } })
    if (path.endsWith('/sources/query')) return route.fulfill({ json: { sources: request.postDataJSON().sources.map((source: object) => ({ ...source,
      target: { title: projection.roles.find((role) => role.id === ROLE_ID)!.name, roleId: ROLE_ID, routineId: null, archived: false } })) } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: weeklyResolutions.evaluatedAt } })
    if (path.endsWith('/delivery-status')) return route.fulfill({ json: { editionId: LATEST, status: 'NO_ADDITIONAL_DELIVERIES', checkedAt: weeklyResolutions.evaluatedAt } })
    if (path.endsWith('/editions') && request.method() === 'POST') {
      generated = true
      return route.fulfill({ status: 201, json: { executionId: ACCOUNT, editionId: LATEST, generation: 3, deliveryWatermark: 3, sourceCursor: 3, created: true } })
    }
    if (path.endsWith('/editions')) return route.fulfill({ json: { editions: summaries, nextBeforeGeneration: null } })
    return route.fulfill({ json: edition(LATEST) })
  })
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  await panel.locator('summary').click()
  const resolved = panel.getByRole('region', { name: '이번 주 해결 요약' })
  await resolved.getByRole('button', { name: '이번 주 해결 2건' }).click()
  await expect(resolved).toContainText(/해결 (?:20)?26\. 9\. 4\. 오전 9:00 \(Asia\/Seoul\)/)
  await expect(resolved.getByText('해결 시 변경 번호 2')).not.toBeVisible()
  await resolved.getByText('연동 상세').click()
  await expect(resolved.getByText('해결 시 변경 번호 2')).toBeVisible()
  await resolved.getByRole('button', { name: '현재 업무 보기' }).click()
  await expect(page.locator('.role-row.selected')).toContainText(projection.roles.find((role) => role.id === ROLE_ID)!.name)
  if (testInfo.project.name === 'mobile') await page.getByRole('button', { name: '상세 닫기' }).click()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘', exact: true }).click()
  await expect(resolved.getByRole('button', { name: '이번 주 해결 2건' })).toHaveAttribute('aria-expanded', 'true')
  expect(await resolved.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await resolved.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('brief-resolution-details.png'), fullPage: true })
  await resolved.getByRole('button', { name: '다음 해결 항목' }).click()
  await expect.poll(() => resolutionCursors.at(-1)).toBe(SOURCE)
  await expect(resolved.getByRole('button', { name: '다음 해결 항목' })).toBeDisabled()
  await resolved.getByText('연동 상세').click()
  await expect(resolved.locator('code')).toHaveText('role:+& 한글')
  await panel.getByRole('button', { name: '높은 심각도 2건' }).click()
  await panel.getByRole('button', { name: '다음 페이지', exact: true }).click()
  await expect.poll(() => itemQueries.at(-1)?.after).toBe(SOURCE)
  await panel.getByRole('button', { name: '변경 이력 보기', exact: true }).click()
  await expect(panel.getByRole('region', { name: '점검 항목 변경 이력' })).toBeVisible()
  await panel.getByText('저장된 주간 요약', { exact: true }).click()
  await panel.getByRole('button', { name: '이번 주 요약 만들기', exact: true }).click()
  await expect(panel.getByText('새 주간 요약을 생성했습니다. 요약 버전 3')).toBeVisible()
  await expect(panel.getByRole('button', { name: '높은 심각도 1건' })).toBeVisible()
  await expect.poll(() => itemQueries.at(-1)).toEqual({ after: null, severity: 'HIGH' })
  await expect.poll(() => resolutionCursors.at(-1)).toBeNull()
  await expect(panel.getByRole('region', { name: '점검 항목 변경 이력' })).toHaveCount(0)
  await expect(resolved.getByRole('button', { name: '다음 해결 항목' })).toBeEnabled()
  nextWeek = true
  await resolved.getByRole('button', { name: '다음 해결 항목' }).click()
  await expect(resolved).toContainText('조회 주간이 바뀌었습니다.')
  await expect(resolved.locator('.brief-items')).toHaveCount(0)
  await expect(resolved.getByRole('button', { name: '다음 해결 항목' })).toBeDisabled()
  await resolved.getByRole('button', { name: '해결 목록 새로고침' }).click()
  await expect(resolved).toContainText('이번 주에 해결 시점을 확인한 항목이 없습니다.')
  await expect(resolved).not.toContainText('조회 주간이 바뀌었습니다.')
  denied = true
  await resolved.getByRole('button', { name: '해결 목록 새로고침' }).click()
  await expect(panel.getByRole('button', { name: '권한 다시 확인' })).toBeVisible()
  await expect(panel.getByRole('region', { name: '이번 주 해결 요약' })).toHaveCount(0)
})


async function installSharedEdition(page: Page, options: { printItems?: boolean } = {}) {
  const projection = makeProjection()
  await installApi(page, projection); await login(page)
  await page.route('**/api/v1/teams/*/seasons/*/brief/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/resolutions')) return route.fulfill({ json: weeklyResolutions })
    if (path.endsWith('/summary')) return route.fulfill({ json: { highCount: 0, mediumCount: 0, revisionGapCount: 0 } })
    if (path.endsWith('/attention-items')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/generation-readiness')) return route.fulfill({ json: { status: 'READY', pendingCount: 0, failedCount: 0, lastDeliveredAt: null, checkedAt: weeklyResolutions.evaluatedAt } })
    if (path.endsWith('/delivery-status')) return route.fulfill({ json: { editionId: path.split('/').at(-2), status: 'UNKNOWN', checkedAt: weeklyResolutions.evaluatedAt } })
    if (path.endsWith('/sources/query')) return route.fulfill({ json: { sources: route.request().postDataJSON().sources.map((source: object) => ({ ...source,
      target: { title: projection.roles.find((role) => role.id === ROLE_ID)!.name, roleId: ROLE_ID, routineId: null, archived: false } })) } })
    if (path.endsWith('/editions')) return route.fulfill({ json: { editions: [summaries[0]], nextBeforeGeneration: null } })
    if (path.endsWith('/latest')) return route.fulfill({ json: edition(LATEST) })
    const selected = edition(OLD)
    return route.fulfill({ json: options.printItems ? { ...selected, itemCount: 24, items: Array.from({ length: 24 }, (_, index) => ({ ...oldItem,
      sourceReference: index === 0 ? SOURCE : `role:print-${index}`, section: index % 3 === 0 ? 'CURRENT_WEEK' : index % 3 === 1 ? 'CARRY_OVER' : null,
      aggregateRevision: index % 3 === 2 ? null : 1, revisionGap: index % 3 === 2 ? null : false })) } : selected })
  })
}

test('복사한 링크는 비밀 없이 특정 생성본을 열고 로그인 경로에도 남는다 @smoke', async ({ page }) => {
  await installSharedEdition(page)
  await openSharedWorkspace(page)
  const target = `${WORKSPACE_PATH}?brief=${OLD}`
  await page.goto(target)
  const panel = page.locator('.brief-attention')
  await expect(panel.getByRole('combobox', { name: '조회할 요약' })).toHaveValue(OLD)
  await expect(panel.getByRole('heading', { name: '2026-08-24 시작 주 · 요약 버전 1', exact: true })).toBeVisible()
  await expect(panel.getByRole('heading', { name: '2026-08-31 시작 주 · 요약 버전 3', exact: true })).toHaveCount(0)
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: async () => { throw new Error('복사 차단') } } }))
  await panel.getByRole('button', { name: '요약 링크 복사' }).click()
  const fallback = panel.getByRole('textbox', { name: '직접 복사할 주간 요약 링크' })
  await expect(fallback).toHaveValue(new URL(target, page.url()).href)
  expect(await fallback.inputValue()).not.toContain(ACCESS_KEY)
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: async (value: string) => { document.documentElement.dataset.copiedBrief = value } } }))
  await panel.getByRole('button', { name: '요약 링크 복사' }).click()
  await expect(panel.getByText('선택한 주간 요약 링크를 복사했습니다.', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.dataset.copiedBrief)).toBe(new URL(target, page.url()).href)
  await expect(fallback).toHaveCount(0)
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: { authenticated: false } }))
  await page.reload()
  await expect(panel.getByRole('link', { name: '로그인', exact: true })).toHaveAttribute('href', `/login?${new URLSearchParams({ returnTo: target })}`)
  await expect(page.locator('.brief-print-sheet')).toHaveCount(0)
})

test('잘못된 주간 요약 링크와 조회 거부는 최신 생성본으로 바꾸지 않는다 @smoke', async ({ page }) => {
  await installSharedEdition(page)
  await openSharedWorkspace(page)
  const panel = page.locator('.brief-attention')
  for (const query of ['brief=잘못된값', `brief=${OLD}&brief=${LATEST}`]) {
    await page.goto(`${WORKSPACE_PATH}?${query}`)
    await expect(panel.getByRole('alert')).toContainText('주간 요약 링크가 올바르지 않습니다.')
    await expect(panel.getByRole('button', { name: '요약 링크 복사' })).toHaveCount(0)
  }
  for (const status of [404, 403]) {
    await page.route(`**/brief/editions/${OLD}`, (route) => route.fulfill({ status, json: {
      code: status === 404 ? 'BRIEF_EDITION_NOT_FOUND' : 'BRIEF_ACCESS_DENIED', message: '선택한 주간 요약을 확인할 수 없습니다.' } }))
    await page.goto(`${WORKSPACE_PATH}?brief=${OLD}`)
    await expect(panel.getByText(status === 404 ? '선택한 주간 요약을 찾을 수 없습니다.' : '선택한 주간 요약을 확인할 수 없습니다.')).toBeVisible()
    await expect(panel.getByRole('heading', { name: '2026-08-31 시작 주 · 요약 버전 3', exact: true })).toHaveCount(0)
    await expect(panel.getByRole('button', { name: '인쇄·PDF 저장' })).toHaveCount(0)
    await expect(page.locator('.brief-print-sheet')).toHaveCount(0)
  }
})

test('인쇄에는 선택한 불변 주간 요약과 현재 업무명 구분만 담는다 @smoke', async ({ page, browserName }, testInfo) => {
  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith('baton-access-key:')) throw new DOMException('저장소 사용 불가', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  })
  await installSharedEdition(page, { printItems: true })
  const sharedPath = `${WORKSPACE_PATH}?brief=${OLD}#accessKey=${ACCESS_KEY}`
  await page.goto(sharedPath)
  const printSheet = page.locator('.brief-print-sheet')
  const print = page.getByRole('button', { name: '인쇄·PDF 저장' })
  await expect(print).toBeEnabled()
  await expect(printSheet).not.toBeVisible()
  await page.evaluate(() => { window.print = () => { document.documentElement.dataset.printCalled = 'true' } })
  await print.click()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}?brief=${OLD}`)
  expect(await page.evaluate(() => document.documentElement.dataset.printCalled)).toBe('true')
  await page.emulateMedia({ media: 'print' })
  await expect(printSheet).toBeVisible()
  await expect(page.locator('#root')).not.toBeVisible()
  await expect(printSheet.locator('li')).toHaveCount(24)
  await expect(printSheet).toContainText('2026-08-24 시작 주 요약')
  await expect(printSheet).toContainText('업무명은 현재 이름')
  await expect(printSheet).toContainText('이번 주 변경 · 8건')
  await expect(printSheet).toContainText('이전 주부터 미해결 · 8건')
  await expect(printSheet).toContainText('이전 주간 요약 · 분류 미기록 · 8건')
  await expect(printSheet).toContainText('이전 주간 요약: 변경 번호·누락 이력 미기록')
  await expect(printSheet).toContainText(OLD)
  await expect(printSheet).not.toContainText(LATEST)
  await expect(printSheet).not.toContainText(ACCESS_KEY)
  await expect(printSheet).not.toContainText('이번 주 해결 2건')
  expect(await printSheet.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  if (browserName === 'chromium' && testInfo.project.name !== 'mobile') {
    await page.pdf({ path: testInfo.outputPath('selected-brief.pdf'), preferCSSPageSize: true, displayHeaderFooter: true })
  }
  await page.screenshot({ path: testInfo.outputPath('selected-brief-print.png'), fullPage: true })
  await page.evaluate(() => window.dispatchEvent(new Event('afterprint')))
  await expect(page).toHaveURL(sharedPath)
})
