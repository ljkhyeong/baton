import { expect, test } from '@playwright/test'
import { ACCESS_KEY, CREATED_ROLE_RESOURCE_ID, ROLE_ID, SCOPE_PATH,
  ROLE_HANDOFF_ID, MEMBER_ONE_ID, MEMBER_TWO_ID, SECOND_ROLE_ID, SECOND_ROLE_RESOURCE_ID,
  makeProjection, installApi, openSharedWorkspace, navigation } from './support/workspaceApiHarness'

function projectionWithResource() {
  const projection = makeProjection()
  projection.resources.push({ id: CREATED_ROLE_RESOURCE_ID, roleId: ROLE_ID, title: '공유 운영 문서',
    url: 'https://docs.example.com/guide', description: '모임 운영 기준',
    createdAt: '2026-07-06T03:00:00Z', archivedAt: null })
  return projection
}

for (const mode of ['healthy', 'rate-limited', 'unavailable', 'wrong-resource'] as const) {
  test(`@operations @responsive 자료 연결 상태와 링크를 분리한다: ${mode}`, async ({ page }, testInfo) => {
    const projection = projectionWithResource()
    await installApi(page, projection)
    await page.clock.install()
    let checks = 0
    let reads = 0
    let lastCheckedAt = '2026-09-05T01:00:00Z'
    let lastConclusiveAt = lastCheckedAt
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
      expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
      reads++
      await route.fulfill({ json: {
        resourceId: mode === 'wrong-resource' ? ROLE_ID : CREATED_ROLE_RESOURCE_ID,
        health: mode === 'unavailable' ? 'UNKNOWN' : 'HEALTHY',
        availability: mode === 'unavailable' ? 'UNAVAILABLE' : 'AVAILABLE',
        lastCheckedAt: mode === 'unavailable' ? null : lastCheckedAt,
        lastConclusiveAt: mode === 'unavailable' ? null : lastConclusiveAt,
        lastOutcome: lastCheckedAt === lastConclusiveAt ? 'SUCCESS' : 'INTERNAL_FAILURE',
        checkRequestAllowed: mode !== 'unavailable',
      } })
    })
    await page.route('**/api/v1/auth/csrf', (route) => route.fulfill({ json: {
      csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'watch-test-csrf',
    } }))
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/check-requests`, async (route) => {
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
      expect(route.request().headers()['x-csrf-token']).toBe('watch-test-csrf')
      checks++
      if (mode === 'rate-limited' && checks === 1) {
        await route.fulfill({ status: 429, headers: { 'Retry-After': '17' }, json: {
          code: 'WATCH_CHECK_RATE_LIMITED', message: '재점검 요청 간격이 너무 짧습니다.',
        } })
        return
      }
      await route.fulfill({ status: 202, json: { resourceId: CREATED_ROLE_RESOURCE_ID, status: 'ALREADY_SCHEDULED' } })
    })
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
    const inspector = page.getByLabel(/선택한 역할 상세/)
    const health = inspector.getByRole('group', { name: '공유 운영 문서 연결 상태' })
    await expect(inspector.getByRole('link', { name: '공유 운영 문서 새 창에서 열기' }))
      .toHaveAttribute('href', 'https://docs.example.com/guide')
    await expect(health).toContainText('로그인 후 접근 권한은 확인하지 않습니다.')
    if (mode === 'healthy' || mode === 'rate-limited') {
      await expect(health).toContainText('연결 정상')
      await expect(health).toContainText('최근 점검 시도')
      await expect(health).toContainText('최근 상태 확인')
      const button = health.getByRole('button', { name: '공유 운영 문서 다시 점검' })
      await button.click()
      if (mode === 'rate-limited') {
        await expect(health.getByRole('alert')).toContainText('재점검 요청 간격이 너무 짧습니다.')
        await expect(button).toContainText('17초')
        await expect(button).toBeDisabled()
        await page.clock.fastForward(16_000)
        await expect(button).toBeDisabled()
        expect(checks).toBe(1)
        await page.clock.fastForward(2_000)
        await expect(button).toBeEnabled()
        await button.click()
      }
      await expect(health.getByRole('status', { name: '공유 운영 문서 재점검 안내' })).toContainText('점검을 접수했습니다.')
      await expect(button).toBeDisabled()
      expect(checks).toBe(mode === 'rate-limited' ? 2 : 1)
      // 같은 판정이나 내부 오류로 시도 시각만 바뀐 조회는 새 결과로 안내하지 않는다.
      await expect.poll(() => reads).toBeGreaterThanOrEqual(2)
      await expect(health.getByRole('status', { name: '공유 운영 문서 재점검 안내' })).not.toContainText('새 점검 결과')
      const readsBeforeResult = reads
      lastCheckedAt = '2026-09-05T01:00:01Z'
      await expect.poll(async () => {
        await page.clock.fastForward(31_000)
        return reads
      }).toBeGreaterThan(readsBeforeResult)
      await expect(health).toContainText('점검 서비스 오류로 연결 상태를 확인하지 못했습니다.')
      await expect(health.getByRole('status', { name: '공유 운영 문서 재점검 안내' })).not.toContainText('새 점검 결과')
      const readsBeforeConclusion = reads
      lastCheckedAt = '2026-09-05T01:01:00Z'
      lastConclusiveAt = lastCheckedAt
      await expect.poll(async () => {
        await page.clock.fastForward(31_000)
        return reads
      }).toBeGreaterThan(readsBeforeConclusion)
      await expect(health.getByRole('status', { name: '공유 운영 문서 재점검 안내' })).toHaveText('새 점검 결과를 확인했습니다.')
      await expect(button).toBeEnabled()
      await page.screenshot({ path: testInfo.outputPath('resource-health.png'), fullPage: true })
    } else {
      await expect(health).toContainText('연결 상태 확인 불가')
      await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toHaveCount(0)
    }
    expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  })
}

for (const lock of ['handoff', 'conflict', 'ended-season'] as const) {
  test(`@operations @responsive 편집 제한과 서버의 점검 상태를 구분한다: ${lock}`, async ({ page }, testInfo) => {
    const projection = projectionWithResource()
    if (lock === 'handoff') {
      projection.roleHandoffs.push({
        id: ROLE_HANDOFF_ID, roleId: ROLE_ID, fromMemberId: MEMBER_ONE_ID, toMemberId: MEMBER_TWO_ID,
        outgoingAssignmentStartDate: '2026-07-02', outgoingAssignmentEndDate: '2026-09-17',
        incomingAssignmentStartDate: '2026-08-01', incomingAssignmentEndDate: '2026-09-17',
        status: 'TRANSFERRED', preparedAt: '2026-07-22T09:00:00Z', transferredAt: '2026-07-22T09:10:00Z',
        acceptedAt: null, cancelledAt: null, transferredByMemberId: MEMBER_ONE_ID,
        acceptedByMemberId: null, cancelledByMemberId: null,
        activeItemCount: 2, incompleteItemCount: 1, resourceCount: 1, warningAcknowledged: true,
      })
    } else if (lock === 'ended-season') {
      projection.season.endedAt = '2026-09-17T09:00:00Z'
      projection.seasons[0]!.endedAt = projection.season.endedAt
    }
    const api = await installApi(page, projection)
    let reads = 0
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
      reads++
      const monitored = lock !== 'ended-season'
      await route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID,
        health: monitored ? 'HEALTHY' : 'UNKNOWN', availability: monitored ? 'AVAILABLE' : 'NOT_MONITORED',
        lastConclusiveAt: monitored ? '2026-09-05T01:00:00Z' : null,
        lastCheckedAt: monitored ? '2026-09-05T01:00:00Z' : null, checkRequestAllowed: monitored,
        lastOutcome: monitored ? 'SUCCESS' : null, consecutiveFailures: monitored ? 0 : null,
        monitoringReason: monitored ? null : 'SEASON_ENDED' } })
    })
    await openSharedWorkspace(page)
    if (lock === 'conflict') {
      await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
      await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
      const dialog = page.getByRole('dialog', { name: '역할 수정' })
      await dialog.getByLabel('역할 이름').fill('저장할 역할 이름')
      api.conflictNextRoleUpdate({ ...projection.roles[0]!, name: '다른 구성원이 수정한 역할' })
      api.makeWorkspaceGetsUnavailable()
      await dialog.getByRole('button', { name: '변경 저장' }).click()
      await expect(dialog).toBeHidden()
      await expect(page.locator('.workspace-sync-status')).toContainText('다른 사람이 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 수정하세요.')
    }
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
    const inspector = page.getByLabel(/선택한 역할 상세/)
    const health = inspector.getByRole('group', { name: '공유 운영 문서 연결 상태' })
    await expect(health).toContainText(lock === 'ended-season' ? '자동 점검 대상 아님' : '연결 정상')
    await expect(health).not.toContainText('자동 점검 중지')
    if (lock === 'ended-season') await expect(health).toContainText('종료된 시즌의 자료는 자동으로 점검하지 않습니다.')
    await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toHaveCount(0)
    await expect(inspector.getByRole('button', { name: '공유 운영 문서 자료 수정' })).toBeDisabled()
    await expect(health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })).toBeEnabled()
    expect(reads).toBeGreaterThan(0)
  })
}

test('@operations @responsive 점검 실패 원인과 횟수를 최신 결과에 맞춰 표시한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithResource())
  await page.clock.install()
  let reads = 0
  const results = [
    { health: 'BROKEN', lastOutcome: 'DNS_FAILURE', consecutiveFailures: 3 },
    { health: 'BROKEN', lastOutcome: 'INTERNAL_FAILURE', consecutiveFailures: 3 },
    { health: 'HEALTHY', lastOutcome: 'SUCCESS', consecutiveFailures: 0 },
  ]
  let resultIndex = 0
  await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
    reads++
    await route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID, availability: 'AVAILABLE',
      lastConclusiveAt: '2026-09-05T01:00:00Z', lastCheckedAt: '2026-09-05T01:00:00Z', checkRequestAllowed: true, ...results[resultIndex] } })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
  await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
  const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
  await expect(health).toContainText('도메인 주소를 찾지 못했습니다.')
  await expect(health).toContainText('연속 연결 실패 3회')
  expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('resource-health-failure.png'), fullPage: true })
  for (resultIndex = 1; resultIndex < results.length; resultIndex++) {
    const previousReads = reads
    await expect.poll(async () => {
      await page.clock.fastForward(31_000)
      return reads
    }).toBeGreaterThan(previousReads)
    await expect(health).not.toContainText('도메인 주소를 찾지 못했습니다.')
    if (resultIndex === 1) {
      await expect(health).toContainText('점검 서비스 오류로 연결 상태를 확인하지 못했습니다.')
      await expect(health).toContainText('연속 연결 실패 3회')
    } else {
      await expect(health).toContainText('연결 정상')
      await expect(health).not.toContainText('연속 연결 실패')
      await expect(health).not.toContainText('점검 서비스에서 오류가 발생했습니다.')
    }
  }
})

test('@operations @responsive 오래된 연결 판정과 최근 시도를 구분하고 판정이 없으면 대기한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithResource())
  await page.clock.install()
  let reads = 0
  let lastConclusiveAt: string | null = '2026-09-05T00:52:00Z'
  await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, (route) => {
    reads++
    return route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID, health: 'UNKNOWN',
      availability: lastConclusiveAt ? 'STALE' : 'PENDING', lastConclusiveAt,
      lastCheckedAt: '2026-09-05T01:00:00Z', checkRequestAllowed: true,
      lastOutcome: null, consecutiveFailures: null, monitoringReason: null } })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
  await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
  const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
  await expect(health).toContainText('최근 점검 정보 없음')
  await expect(health).not.toContainText('연결 정상')
  const conclusion = health.getByText(/^최근 상태 확인 /)
  const attempt = health.getByText(/^최근 점검 시도 /)
  await expect(conclusion).toBeVisible()
  await expect(attempt).toBeVisible()
  expect((await conclusion.innerText()).replace('최근 상태 확인 ', ''))
    .not.toBe((await attempt.innerText()).replace('최근 점검 시도 ', ''))
  await expect(page.getByRole('link', { name: '공유 운영 문서 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/guide')
  expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('resource-health-stale.png'), fullPage: true })
  const previousReads = reads
  lastConclusiveAt = null
  await expect.poll(async () => {
    await page.clock.fastForward(31_000)
    return reads
  }).toBeGreaterThan(previousReads)
  await expect(health).toContainText('점검 결과 대기')
  await expect(conclusion).toHaveCount(0)
  await expect(attempt).toBeVisible()
  await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toBeEnabled()
})

for (const invalidField of ['lastOutcome', 'monitoringReason', 'lastConclusiveAt']) {
  test(`@operations @responsive 잘못된 점검 코드와 판정 시각을 정상 상태로 표시하지 않는다: ${invalidField}`, async ({ page }, testInfo) => {
    await installApi(page, projectionWithResource())
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, (route) => route.fulfill({
      json: { resourceId: CREATED_ROLE_RESOURCE_ID, health: 'HEALTHY', availability: 'AVAILABLE',
        lastConclusiveAt: '2026-09-05T01:00:00Z', lastCheckedAt: '2026-09-05T01:00:00Z', checkRequestAllowed: true,
        lastOutcome: 'SUCCESS', consecutiveFailures: 0, [invalidField]: 'UNSUPPORTED_VALUE' },
    }))
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
    const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
    await expect(health).toContainText('연결 상태 확인 불가')
    await expect(health).not.toContainText('연결 정상')
    await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toHaveCount(0)
    await expect(health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })).toBeEnabled()
  })
}

for (const [reason, message] of [
  ['SYNC_PENDING', '자료 주소를 점검 서비스에 반영하고 있습니다.'],
  ['MONITORING_PAUSED', '자동 점검을 일시 중지한 상태입니다.'],
  ['URL_NOT_ELIGIBLE', '자동 점검에 사용할 수 없는 URL 형식입니다.'],
] as const) {
  test(`@operations @responsive 자동 점검 제외 사유와 동기화 대기를 안내한다: ${reason}`, async ({ page }, testInfo) => {
    await installApi(page, projectionWithResource())
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, (route) => route.fulfill({
      json: { resourceId: CREATED_ROLE_RESOURCE_ID, health: 'UNKNOWN',
        availability: reason === 'SYNC_PENDING' ? 'PENDING' : 'NOT_MONITORED',
        lastConclusiveAt: null, lastCheckedAt: null, lastOutcome: null, consecutiveFailures: null,
        monitoringReason: reason, checkRequestAllowed: false },
    }))
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
    const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
    await expect(health).toContainText(reason === 'SYNC_PENDING' ? '자료 주소 반영 대기' : '자동 점검 대상 아님')
    await expect(health).toContainText(message)
    await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toHaveCount(0)
    await expect(health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })).toBeEnabled()
    expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('monitoring-reason.png'), fullPage: true })
  })
}

for (const response of ['accepted', 'rate-limited'] as const) {
  test(`@operations @responsive 다른 역할을 보고 돌아와도 자료별 재점검 대기시간을 유지한다: ${response}`, async ({ page }, testInfo) => {
    const projection = projectionWithResource()
    projection.roles.push({ ...projection.roles[0]!, id: SECOND_ROLE_ID, name: '기록 담당' })
    projection.resources.push({ ...projection.resources[0]!, id: SECOND_ROLE_RESOURCE_ID,
      roleId: SECOND_ROLE_ID, title: '다른 운영 문서', url: 'https://docs.example.com/other' })
    await installApi(page, projection)
    await page.clock.install()
    await page.route(`**${SCOPE_PATH}/role-resources/*/health`, (route) => route.fulfill({ json: {
      resourceId: route.request().url().split('/').at(-2), health: 'HEALTHY', availability: 'AVAILABLE',
      lastConclusiveAt: '2026-09-05T01:00:00Z', lastCheckedAt: '2026-09-05T01:00:00Z', checkRequestAllowed: true,
    } }))
    await page.route('**/api/v1/auth/csrf', (route) => route.fulfill({ json: {
      csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'watch-test-csrf',
    } }))
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    let requests = 0
    const waitSeconds = response === 'accepted' ? 30 : 600
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/check-requests`, async (route) => {
      requests++
      await gate
      if (response === 'rate-limited') {
        await route.fulfill({ status: 429, headers: { 'Retry-After': String(waitSeconds) }, json: {
          code: 'WATCH_CHECK_RATE_LIMITED', message: '재점검 요청 간격이 너무 짧습니다.',
        } })
      } else {
        await route.fulfill({ status: 202, json: { resourceId: CREATED_ROLE_RESOURCE_ID, status: 'SCHEDULED' } })
      }
    })
    await openSharedWorkspace(page)
    const showResource = async (title: string) => {
      const inspector = page.getByLabel(/선택한 역할 상세/)
      if (testInfo.project.name === 'mobile' && await inspector.isVisible()) {
        await inspector.getByRole('button', { name: '상세 닫기' }).click()
      }
      await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
      await page.getByRole('button', { name: `${title} 역할에서 보기` }).click()
    }
    await showResource('공유 운영 문서')
    const button = page.getByRole('button', { name: '공유 운영 문서 다시 점검' })
    await button.click()
    await expect(button).toContainText('점검 요청 중')
    await showResource('다른 운영 문서')
    await expect(page.getByRole('button', { name: '다른 운영 문서 다시 점검' })).toBeEnabled()
    await showResource('공유 운영 문서')
    await expect(button).toContainText('점검 요청 중')
    await expect(button).toBeDisabled()
    release()
    await expect(button).toHaveText(`다시 점검 (${waitSeconds}초)`)
    if (response === 'accepted') await expect(page.getByRole('status', { name: '공유 운영 문서 재점검 안내' }))
      .toContainText('점검을 접수했습니다.')
    await showResource('다른 운영 문서')
    // 429 대기는 기본 쿼리 정리 시간인 5분보다 오래 화면을 떠나 있어도 남아야 한다.
    const elapsed = response === 'accepted' ? 5 : 360
    await page.clock.fastForward(elapsed * 1_000)
    await showResource('공유 운영 문서')
    if (response === 'accepted') await expect(page.getByRole('status', { name: '공유 운영 문서 재점검 안내' }))
      .toContainText('점검을 접수했습니다.')
    await expect(button).toHaveText(/다시 점검 \(\d+초\)/)
    const remaining = Number((await button.innerText()).match(/\((\d+)초\)/)![1])
    expect(remaining).toBeGreaterThanOrEqual(waitSeconds - elapsed - 4)
    expect(remaining).toBeLessThanOrEqual(waitSeconds - elapsed)
    await expect(button).toBeDisabled()
    expect(requests).toBe(1)
    await page.clock.fastForward((remaining + 1) * 1_000)
    await expect(button).toBeEnabled()
  })
}

for (const closeWhileWaiting of [false, true]) {
  test(`@operations @responsive 여러 자료 조회를 나누고 닫힌 화면의 대기를 취소한다: ${closeWhileWaiting}`, async ({ page }, testInfo) => {
    const projection = makeProjection()
    const resources = Array.from({ length: 8 }, (_, index) => ({
      id: `aaaaaaaa-aaaa-4aaa-8aaa-${String(index).padStart(12, '0')}`,
      roleId: ROLE_ID, title: `운영 자료 ${index + 1}`, url: `https://docs.example.com/${index}`,
      description: '', createdAt: '2026-07-06T03:00:00Z', archivedAt: null,
    }))
    projection.resources.push(...resources)
    await installApi(page, projection)
    const reads: string[] = []
    let active = 0
    let peak = 0
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    await page.route(`**${SCOPE_PATH}/role-resources/*/health`, async (route) => {
      const resourceId = route.request().url().split('/').at(-2)!
      reads.push(resourceId)
      active++
      peak = Math.max(peak, active)
      await gate
      await route.fulfill({ json: { resourceId, health: 'HEALTHY', availability: 'AVAILABLE',
        lastConclusiveAt: '2026-09-05T01:00:00Z', lastCheckedAt: '2026-09-05T01:00:00Z', checkRequestAllowed: true } })
      active--
    })
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: '운영 자료 1 역할에서 보기' }).click()
    const inspector = page.getByLabel(/선택한 역할 상세/)
    await expect.poll(() => reads.length).toBe(2)
    if (closeWhileWaiting) {
      if (testInfo.project.name === 'mobile') await inspector.getByRole('button', { name: '상세 닫기' }).click()
      await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    }
    release()
    if (closeWhileWaiting) {
      // 다음 시작 간격을 넘긴 뒤에도 취소한 자료를 요청하지 않고, 다시 열면 정상 조회한다.
      await page.clock.install()
      await page.clock.fastForward(1_000)
      expect(reads).toHaveLength(2)
      await page.getByRole('button', { name: '운영 자료 1 역할에서 보기' }).click()
    }
    await expect(inspector.getByText('연결 정상', { exact: true })).toHaveCount(8)
    expect(new Set(reads).size).toBe(8)
    expect(peak).toBeLessThanOrEqual(2)
  })
}


test('@operations @responsive 새 결과 확인 안내는 오프라인과 역할 이동 뒤에도 유지한다', async ({ page, context }, testInfo) => {
  const projection = projectionWithResource()
  projection.roles.push({ ...projection.roles[0]!, id: SECOND_ROLE_ID, name: '기록 담당' })
  projection.resources.push({ ...projection.resources[0]!, id: SECOND_ROLE_RESOURCE_ID,
    roleId: SECOND_ROLE_ID, title: '다른 운영 문서', url: 'https://docs.example.com/other' })
  await installApi(page, projection)
  await page.clock.install()
  let conclusiveAt = '2026-09-05T01:00:00Z'
  let unavailable = false
  await page.route(`**${SCOPE_PATH}/role-resources/*/health`, (route) => route.fulfill({ json: {
    resourceId: route.request().url().split('/').at(-2),
    availability: unavailable ? 'UNAVAILABLE' : 'AVAILABLE', health: unavailable ? 'UNKNOWN' : 'HEALTHY',
    lastConclusiveAt: unavailable ? null : conclusiveAt,
    lastCheckedAt: unavailable ? null : conclusiveAt, checkRequestAllowed: !unavailable,
  } }))
  await page.route('**/api/v1/auth/csrf', (route) => route.fulfill({ json: {
    csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'watch-test-csrf',
  } }))
  let checks = 0
  await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/check-requests`, (route) => {
    checks++
    return route.fulfill({ status: 202, json: { resourceId: CREATED_ROLE_RESOURCE_ID,
      status: checks === 1 ? 'SCHEDULED' : 'IN_PROGRESS' } })
  })
  await openSharedWorkspace(page)
  const showResource = async (title: string) => {
    const inspector = page.getByLabel(/선택한 역할 상세/)
    if (testInfo.project.name === 'mobile' && await inspector.isVisible()) {
      await inspector.getByRole('button', { name: '상세 닫기' }).click()
    }
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: `${title} 역할에서 보기` }).click()
  }
  await showResource('공유 운영 문서')
  const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
  const receipt = health.getByRole('status', { name: '공유 운영 문서 재점검 안내' })
  const check = health.getByRole('button', { name: '공유 운영 문서 다시 점검' })
  await check.click()
  await expect(receipt).toContainText('점검을 접수했습니다.')
  conclusiveAt = '2026-09-05T01:01:00Z'
  await health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' }).click()
  await expect(receipt).toHaveText('새 점검 결과를 확인했습니다.')

  await context.setOffline(true)
  await expect(health).toContainText('오프라인 · 연결 상태 확인 불가')
  await expect(receipt).toHaveText('새 점검 결과를 확인했습니다.')
  unavailable = true
  await context.setOffline(false)
  await expect(health).not.toContainText('오프라인')
  await expect(health).toContainText('연결 상태 확인 불가')
  await expect(receipt).toHaveText('새 점검 결과를 확인했습니다.')
  await showResource('다른 운영 문서')
  await expect(page.getByRole('status', { name: '다른 운영 문서 재점검 안내' })).toBeEmpty()
  await showResource('공유 운영 문서')
  await expect(receipt).toHaveText('새 점검 결과를 확인했습니다.')

  unavailable = false
  await page.clock.fastForward(31_000)
  await expect(check).toBeEnabled()
  await check.click()
  await expect(receipt).toContainText('이미 점검 중입니다.')
  await expect(receipt).not.toContainText('새 점검 결과')
  // URL 변경은 다른 점검 범위이므로 이전 접수 안내를 가져오지 않는다.
  await page.getByRole('button', { name: '공유 운영 문서 자료 수정' }).click()
  const dialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await dialog.getByLabel('링크').fill('https://docs.example.com/updated')
  await dialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(page.getByRole('link', { name: '공유 운영 문서 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/updated')
  await expect(receipt).toBeEmpty()
  expect(checks).toBe(2)
})

test('@operations @responsive 상태 조회 안내는 수동 재조회와 실제 상태 변경을 알린다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithResource())
  await page.clock.install()
  let reads = 0
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
    reads++
    if (reads === 2) await gate
    await route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID,
      health: reads < 4 ? 'HEALTHY' : 'BROKEN', availability: 'AVAILABLE',
      lastConclusiveAt: '2026-09-05T01:00:00Z', lastCheckedAt: '2026-09-05T01:00:00Z',
      checkRequestAllowed: true } })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
  await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
  const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
  const announcement = health.getByRole('status', { name: '공유 운영 문서 상태 조회 안내' })
  await expect(announcement).toHaveAttribute('aria-live', 'polite')
  await expect(announcement).toHaveAttribute('aria-atomic', 'true')
  await expect(announcement).toHaveText('공유 운영 문서: 연결 정상.')
  await health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' }).press('Enter')
  await expect(announcement).toHaveText('공유 운영 문서: 연결 상태를 다시 조회하고 있습니다.')
  release()
  await expect(announcement).toHaveText('공유 운영 문서: 연결 정상.')
  // DOM의 알림 텍스트 변경만 관찰해 같은 상태의 자동 조회가 반복 낭독을 만들지 않는지 확인한다.
  await announcement.evaluate((element) => {
    element.setAttribute('data-announcement-updates', '0')
    new MutationObserver((records) => {
      element.setAttribute('data-announcement-updates',
        String(Number(element.getAttribute('data-announcement-updates')) + records.length))
    }).observe(element, { childList: true, characterData: true, subtree: true })
  })
  await page.clock.fastForward(31_000)
  await expect.poll(() => reads).toBe(3)
  await expect(health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })).toBeEnabled()
  await expect(announcement).toHaveAttribute('data-announcement-updates', '0')
  await page.clock.fastForward(31_000)
  await expect(announcement).toHaveText('공유 운영 문서: 연결 실패.')
  expect(reads).toBe(4)
  expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('resource-health-announcement.png'), fullPage: true })
})

test('@operations @responsive 오프라인에서는 이전 정상을 숨기고 재연결하면 새 상태를 조회한다', async ({ page, context }, testInfo) => {
  await installApi(page, projectionWithResource())
  await page.clock.install()
  let reads = 0
  let broken = false
  let checks = 0
  page.on('request', (request) => { if (request.url().endsWith('/check-requests')) checks++ })
  await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, (route) => {
    reads++
    return route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID, availability: 'AVAILABLE',
      health: broken ? 'BROKEN' : 'HEALTHY', lastConclusiveAt: '2026-09-05T01:00:00Z',
      lastCheckedAt: '2026-09-05T01:00:00Z', lastOutcome: broken ? 'DNS_FAILURE' : 'SUCCESS',
      consecutiveFailures: broken ? 3 : 0, checkRequestAllowed: true } })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
  await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
  const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
  const refresh = health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })
  await expect(health).toContainText('연결 정상')
  const onlineReads = reads
  await context.setOffline(true)
  await expect(health).toContainText('오프라인 · 연결 상태 확인 불가')
  await expect(health).not.toContainText('연결 정상')
  await expect(refresh).toBeDisabled()
  await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toBeDisabled()
  await page.clock.fastForward(600_000)
  expect(reads).toBe(onlineReads)
  await expect(health).not.toContainText('연결 정상')
  await expect(health.locator('.health-healthy')).toHaveCount(0)
  await expect(health).toContainText('최근 상태 확인')
  await expect(page.getByRole('link', { name: '공유 운영 문서 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/guide')
  await page.screenshot({ path: testInfo.outputPath('resource-health-offline.png'), fullPage: true })
  broken = true
  await context.setOffline(false)
  await expect.poll(() => reads).toBeGreaterThan(onlineReads)
  await expect(health).toContainText('연결 실패')
  await expect(health).toContainText('도메인 주소를 찾지 못했습니다.')
  await expect(refresh).toBeEnabled()
  expect(checks).toBe(0)
})

test('@operations @responsive 숨긴 탭의 조회 캐시가 만료되면 정상을 숨기고 복귀 즉시 다시 조회한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithResource())
  await page.clock.install()
  let reads = 0
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
    reads++
    if (reads > 1) await gate
    await route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID, availability: 'AVAILABLE', health: 'HEALTHY',
      lastConclusiveAt: '2026-09-05T01:00:00Z', lastCheckedAt: '2026-09-05T01:00:00Z',
      lastOutcome: 'SUCCESS', consecutiveFailures: 0, checkRequestAllowed: true } })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
  await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
  const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
  await expect(health).toContainText('연결 정상')
  const initialReads = reads
  // 탭 표시 여부는 브라우저 경계에서 바꾸고, 앱의 쿼리 상태는 직접 조작하지 않는다.
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
  })
  await page.clock.fastForward(31_000)
  await expect(health).toContainText('상태를 새로고침해 주세요.')
  await expect(health).not.toContainText('연결 정상')
  expect(reads).toBe(initialReads)
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
  })
  await expect.poll(() => reads).toBeGreaterThan(initialReads)
  await expect(health).toContainText('연결 상태 확인 중')
  await expect(health).not.toContainText('연결 정상')
  await expect(health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })).toBeDisabled()
  release()
  await expect(health).toContainText('연결 정상')
})

for (const failure of ['unavailable', 'transport'] as const) {
  test(`@operations @responsive 상태 재조회는 실패 후 재시도할 수 있고 URL 점검을 접수하지 않는다: ${failure}`, async ({ page }, testInfo) => {
    await installApi(page, projectionWithResource())
    await page.clock.install()
    let reads = 0
    let checks = 0
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    page.on('request', (request) => { if (request.url().endsWith('/check-requests')) checks++ })
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
      expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
      reads++
      if (reads === 2) await gate
      if (reads < 3 && failure === 'transport') {
        await route.fulfill({ status: 503, json: { code: 'TEMPORARY_UNAVAILABLE', message: '잠시 후 다시 조회해 주세요.' } })
        return
      }
      await route.fulfill({ json: { resourceId: CREATED_ROLE_RESOURCE_ID,
        availability: reads < 3 ? 'UNAVAILABLE' : 'AVAILABLE', health: reads < 3 ? 'UNKNOWN' : 'HEALTHY',
        lastConclusiveAt: reads < 3 ? null : '2026-09-05T01:00:00Z',
        lastCheckedAt: reads < 3 ? null : '2026-09-05T01:00:00Z', checkRequestAllowed: reads >= 3 } })
    })
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '검색' }).click()
    await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
    const health = page.getByRole('group', { name: '공유 운영 문서 연결 상태' })
    const refresh = health.getByRole('button', { name: '공유 운영 문서 상태 새로고침' })
    await expect(health).toContainText('연결 상태 확인 불가')
    await refresh.focus()
    await refresh.press('Enter')
    await expect.poll(() => reads).toBe(2)
    await expect(health.getByRole('status', { name: '공유 운영 문서 상태 조회 안내' }))
      .toHaveText('공유 운영 문서: 연결 상태를 다시 조회하고 있습니다.')
    await expect(refresh).toHaveText('상태 조회 중')
    await expect(refresh).toBeDisabled()
    release()
    await expect(health).toContainText('연결 상태 확인 불가')
    await expect(refresh).toBeEnabled()
    await expect(health.getByRole('status', { name: '공유 운영 문서 상태 조회 안내' }))
      .toHaveText('공유 운영 문서: 연결 상태 확인 불가.')
    await refresh.click()
    await expect(health).toContainText('연결 정상')
    await expect(health.getByRole('status', { name: '공유 운영 문서 상태 조회 안내' }))
      .toHaveText('공유 운영 문서: 연결 정상.')
    expect(reads).toBe(3)
    expect(checks).toBe(0)
    expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  })
}
