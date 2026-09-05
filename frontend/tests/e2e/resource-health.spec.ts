import { expect, test } from '@playwright/test'
import { ACCESS_KEY, CREATED_ROLE_RESOURCE_ID, ROLE_ID, SCOPE_PATH,
  makeProjection, installApi, openSharedWorkspace, navigation } from './support/workspaceApiHarness'

for (const mode of ['healthy', 'unavailable', 'wrong-resource'] as const) {
  test(`@operations @responsive 자료 연결 상태와 링크를 분리한다: ${mode}`, async ({ page }, testInfo) => {
    const projection = makeProjection()
    projection.resources.push({ id: CREATED_ROLE_RESOURCE_ID, roleId: ROLE_ID, title: '공유 운영 문서',
      url: 'https://docs.example.com/guide', description: '모임 운영 기준',
      createdAt: '2026-07-06T03:00:00Z', archivedAt: null })
    await installApi(page, projection)
    let checks = 0
    await page.route(`**${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/health`, async (route) => {
      expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
      await route.fulfill({ json: {
        resourceId: mode === 'wrong-resource' ? ROLE_ID : CREATED_ROLE_RESOURCE_ID,
        health: mode === 'unavailable' ? 'UNKNOWN' : 'HEALTHY',
        availability: mode === 'unavailable' ? 'UNAVAILABLE' : 'AVAILABLE',
        lastCheckedAt: mode === 'unavailable' ? null : '2026-09-05T01:00:00Z',
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
    if (mode === 'healthy') {
      await expect(health).toContainText('연결 정상')
      await expect(health).toContainText('최근 점검')
      await health.getByRole('button', { name: '공유 운영 문서 다시 점검' }).click()
      await expect(health.getByRole('status')).toContainText('점검을 접수했습니다.')
      expect(checks).toBe(1)
      await page.screenshot({ path: testInfo.outputPath('resource-health.png'), fullPage: true })
    } else {
      await expect(health).toContainText('연결 상태 확인 불가')
      await expect(health.getByRole('button', { name: '공유 운영 문서 다시 점검' })).toHaveCount(0)
    }
    expect(await health.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  })
}
