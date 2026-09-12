import { expect, test } from '@playwright/test'
import {
  installApi,
  openSharedWorkspace,
} from './support/workspaceApiHarness'

test('@smoke 라우트와 작업 공간에 맞는 문서 제목을 표시한다', async ({ page }) => {
  await page.route('**/api/v1/auth/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/auth/providers') {
      await route.fulfill({ json: {
        providers: [],
        localRegistrationEnabled: true,
        passwordResetEnabled: false,
        turnstileSiteKey: null,
      } })
      return
    }
    await route.fulfill({ json: { authenticated: false } })
  })

  await page.goto('/login')
  await expect(page).toHaveTitle('로그인 — BATON')

  const api = await installApi(page)
  await openSharedWorkspace(page)
  const projection = api.projection()
  await expect(page).toHaveTitle(
    `${projection.team.name} · ${projection.season.name} — BATON`,
  )
})

test('예상하지 못한 화면 오류에서 안전한 복구 행동을 제공한다', async ({ page }) => {
  await page.route('**/src/pages/LoginPage.tsx*', (route) => route.abort())

  await page.goto('/login')

  await expect(page.getByRole('heading', { name: '화면을 불러오지 못했습니다.' })).toBeVisible()
  await expect(page.getByRole('button', { name: '다시 불러오기' })).toBeVisible()
  await expect(page.getByRole('link', { name: '처음 화면으로 이동' })).toHaveAttribute('href', '/')
  await expect(page.getByRole('link', { name: '서비스 상태 (새 탭)' }))
    .toHaveCount((process.env.VITE_STATUS_PAGE_ENABLED ?? 'true') === 'true' ? 1 : 0)
  await expect(page).toHaveTitle('오류 — BATON')
})
