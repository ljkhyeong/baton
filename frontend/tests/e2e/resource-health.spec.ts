import { expect, test } from '@playwright/test'
import { ACCESS_KEY, CREATED_ROLE_RESOURCE_ID, ROLE_ID, SCOPE_PATH,
  makeProjection, installApi, openSharedWorkspace, navigation } from './support/workspaceApiHarness'

for (const mode of ['healthy', 'rate-limited', 'unavailable', 'wrong-resource'] as const) {
  test(`@operations @responsive 자료 연결 상태와 링크를 분리한다: ${mode}`, async ({ page }, testInfo) => {
    const projection = makeProjection()
    projection.resources.push({ id: CREATED_ROLE_RESOURCE_ID, roleId: ROLE_ID, title: '공유 운영 문서',
      url: 'https://docs.example.com/guide', description: '모임 운영 기준',
      createdAt: '2026-07-06T03:00:00Z', archivedAt: null })
    await installApi(page, projection)
    await page.clock.install()
    let checks = 0
    let reads = 0
    let lastCheckedAt = '2026-09-05T01:00:00Z'
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
      expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
      reads++
      await route.fulfill({ json: {
        resourceId: mode === 'wrong-resource' ? ROLE_ID : CREATED_ROLE_RESOURCE_ID,
        health: mode === 'unavailable' ? 'UNKNOWN' : 'HEALTHY',
        availability: mode === 'unavailable' ? 'UNAVAILABLE' : 'AVAILABLE',
        lastCheckedAt: mode === 'unavailable' ? null : lastCheckedAt,
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
    await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
    await page.getByRole('button', { name: '공유 운영 문서 역할에서 보기' }).click()
    const inspector = page.getByLabel(/선택한 역할 상세/)
    const health = inspector.getByRole('group', { name: '공유 운영 문서 연결 상태' })
    await expect(inspector.getByRole('link', { name: '공유 운영 문서 새 창에서 열기' }))
      .toHaveAttribute('href', 'https://docs.example.com/guide')
    await expect(health).toContainText('로그인 후 접근 권한은 확인하지 않습니다.')
    if (mode === 'healthy' || mode === 'rate-limited') {
      await expect(health).toContainText('연결 정상')
      await expect(health).toContainText('최근 점검')
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
      await expect(health.getByRole('status')).toContainText('점검을 접수했습니다.')
      await expect(button).toBeDisabled()
      expect(checks).toBe(mode === 'rate-limited' ? 2 : 1)
      // 같은 점검 시각의 재조회는 접수 완료로 바꾸지 않는다.
      await expect.poll(() => reads).toBeGreaterThanOrEqual(2)
      await expect(health.getByRole('status')).not.toContainText('새 점검 결과')
      const readsBeforeResult = reads
      lastCheckedAt = '2026-09-05T01:00:01Z'
      await expect.poll(async () => {
        await page.clock.fastForward(31_000)
        return reads
      }).toBeGreaterThan(readsBeforeResult)
      await expect(health.getByRole('status')).toHaveText('새 점검 결과를 확인했습니다.')
      await expect(button).toBeEnabled()
      await page.screenshot({ path: testInfo.outputPath('resource-health.png'), fullPage: true })
    } else {
      await expect(health).toContainText('연결 상태 확인 불가')
      await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toHaveCount(0)
    }
    expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
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
        lastCheckedAt: '2026-09-05T01:00:00Z', checkRequestAllowed: true } })
      active--
    })
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
    await page.getByRole('button', { name: '운영 자료 1 역할에서 보기' }).click()
    const inspector = page.getByLabel(/선택한 역할 상세/)
    await expect.poll(() => reads.length).toBe(2)
    if (closeWhileWaiting) {
      if (testInfo.project.name === 'mobile') await inspector.getByRole('button', { name: '상세 닫기' }).click()
      await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
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
