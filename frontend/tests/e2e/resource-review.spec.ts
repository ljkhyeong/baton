import { expect, test } from '@playwright/test'
import { TEAM_ID, SEASON_ID, MEMBER_ONE_ID, ROLE_ID, CREATED_ROLE_RESOURCE_ID,
  installApi, makeProjection, navigation, openSharedWorkspace } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'
test('@memory @responsive 자료 재확인 주기를 저장하고 사용 가능 확인 후 다음 날짜를 갱신한다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.resources.push({ id: CREATED_ROLE_RESOURCE_ID, roleId: ROLE_ID, title: '운영 안내', url: 'https://example.com/guide', description: null, archivedAt: null, createdAt: '2026-09-05T00:00:00Z' })
  await installApi(page, projection)
  await page.route('**/api/v1/auth/**', route => route.fulfill({ json: { authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'csrf' } }))
  await page.route('**/api/v1/account-memberships/current?*', route => route.fulfill({ json: {
    claimed: true, accountId: ACCOUNT, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: '2026-09-05T00:00:00Z',
  } }))
  const schedule = { teamId: TEAM_ID, seasonId: SEASON_ID, resourceId: CREATED_ROLE_RESOURCE_ID, version: -1,
    intervalDays: null as number | null, nextReviewOn: null as string | null, today: '2026-09-05', reviewDue: false }
  await page.route('**/resource-reviews', route => route.fulfill({ json: {
    teamId: TEAM_ID, seasonId: SEASON_ID, today: schedule.today, timeZone: projection.season.timeZone,
    resources: schedule.reviewDue ? [{ resourceId: CREATED_ROLE_RESOURCE_ID, roleId: ROLE_ID, title: '운영 안내',
      roleName: '문제 큐레이터', memberId: MEMBER_ONE_ID, memberName: '박민서', nextReviewOn: schedule.nextReviewOn }] : [],
  } }))
  await page.route('**/role-resources/*/verifications/schedule', route => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      expect(body.expectedAccountId).toBe(ACCOUNT)
      expect(body.expectedVersion).toBe(schedule.version)
      expect(route.request().headers()['x-csrf-token']).toBe('csrf')
      schedule.intervalDays = body.intervalDays ?? null
      schedule.nextReviewOn = body.nextReviewOn ?? null
      schedule.reviewDue = schedule.nextReviewOn !== null && schedule.nextReviewOn <= schedule.today
      schedule.version++
    }
    return route.fulfill({ json: schedule })
  })
  await page.route('**/role-resources/*/verifications', route => {
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON().status).toBe('CONFIRMED')
      schedule.nextReviewOn = '2026-10-05'; schedule.reviewDue = false; schedule.version++
    }
    return route.fulfill({ json: { teamId: TEAM_ID, seasonId: SEASON_ID, resourceId: CREATED_ROLE_RESOURCE_ID, resourceVersion: 0, verifications: [] } })
  })
  const open = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '역할', exact: true }).click()
    await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
    await page.locator('.resource-verification').first().locator('summary').click()
  }
  await openSharedWorkspace(page)
  await open()
  const panel = page.getByRole('region', { name: '자료 재확인 주기' })
  await expect(panel.getByText('정기 확인이 꺼져 있습니다.')).toBeVisible()
  await panel.getByLabel('정기 재확인 사용').check()
  await panel.getByLabel('확인 간격(일)').fill('30')
  await panel.getByLabel('다음 확인일').fill('2026-09-05')
  await panel.getByRole('button', { name: '재확인 주기 저장', exact: true }).click()
  await expect(panel.getByText('재확인할 때입니다.', { exact: false })).toBeVisible()
  const closeInspector = page.getByRole('button', { name: '상세 닫기' })
  if (await closeInspector.isVisible()) await closeInspector.click()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘', exact: true }).click()
  await page.getByText('내 업무와 확인할 자료', { exact: true }).click()
  const due = page.getByRole('region', { name: '재확인할 자료' })
  await expect(due.getByText('문제 큐레이터 · 박민서')).toBeVisible()
  await due.getByRole('button', { name: /운영 안내/ }).click()
  const verification = page.locator('.resource-verification:visible').first()
  if (await verification.getAttribute('open') === null) await verification.locator('summary').click()
  await page.getByRole('button', { name: '내 확인 기록 남기기' }).click()
  await expect(panel.getByText('다음 확인일 2026. 10. 5.', { exact: false })).toBeVisible()
  await page.reload()
  await open()
  await expect(panel.getByLabel('다음 확인일')).toHaveValue('2026-10-05')
  await panel.getByLabel('정기 재확인 사용').uncheck()
  await panel.getByRole('button', { name: '재확인 주기 저장', exact: true }).click()
  await expect(panel.getByText('정기 확인이 꺼져 있습니다.')).toBeVisible()
})
