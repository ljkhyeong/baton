import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import {
  ACCESS_KEY,
  MEMBER_ONE_ID,
  ROLE_ID,
  SECOND_ROLE_RESOURCE_ID,
  SEASON_ID,
  TEAM_ID,
  WORKSPACE_PATH,
  installApi,
  makeProjection,
  navigation,
  openSharedWorkspace,
} from './support/workspaceApiHarness'

const ACCOUNT_ID = '8e448211-66ae-44ab-9888-c4960648c22b'
const CSRF_HEADER_NAME = 'X-CSRF-TOKEN'
const CSRF_TOKEN = 'round-product-csrf-token'
const ROOM_ID = 'bcdf-ghjk-mnpq'
const RESOURCE_TITLE = 'ROUND 진행 자료'

type RoundProductCall = {
  body: unknown
  headers: Record<string, string>
  method: string
  path: string
}

type RoundProductApiOptions = {
  authenticated?: boolean
  claimed?: boolean
}

async function installRoundProductApi(
  page: Page,
  options: RoundProductApiOptions = {},
) {
  const calls: RoundProductCall[] = []
  const authenticated = options.authenticated ?? true
  const claimed = options.claimed ?? true
  const json = (route: Route, status: number, body: unknown) => route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })

  await page.route('**/api/v1/auth/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') {
      return json(route, 200, authenticated
        ? {
            authenticated: true,
            accountId: ACCOUNT_ID,
            csrfHeaderName: CSRF_HEADER_NAME,
            csrfToken: CSRF_TOKEN,
          }
        : { authenticated: false })
    }
    if (request.method() === 'GET' && path === '/api/v1/auth/csrf') {
      return json(route, 200, {
        csrfHeaderName: CSRF_HEADER_NAME,
        csrfToken: CSRF_TOKEN,
      })
    }
    return json(route, 501, {
      code: 'UNEXPECTED_TEST_REQUEST',
      message: `예상하지 못한 인증 요청: ${request.method()} ${path}`,
    })
  })

  await page.route('**/api/v1/account-memberships/current**', async (route) => {
    const request = route.request()
    calls.push({
      body: null,
      headers: request.headers(),
      method: request.method(),
      path: new URL(request.url()).pathname,
    })
    return json(route, 200, claimed
      ? {
          claimed: true,
          accountId: ACCOUNT_ID,
          teamId: TEAM_ID,
          memberId: MEMBER_ONE_ID,
          claimedAt: '2026-08-09T12:34:56Z',
        }
      : { claimed: false })
  })

  await page.route('**/api/v1/round-room-mappings**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    calls.push({
      body: request.postData() ? request.postDataJSON() : null,
      headers: request.headers(),
      method: request.method(),
      path,
    })
    const mapping = {
      roomId: ROOM_ID,
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      resourceId: SECOND_ROLE_RESOURCE_ID,
      createdAt: '2026-08-09T12:35:00Z',
      endedAt: request.method() === 'DELETE' ? '2026-08-09T13:00:00Z' : null,
    }
    return json(route, 200, mapping)
  })

  await page.route(`**/room/${ROOM_ID}`, async (route) => {
    if (route.request().resourceType() !== 'document') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: '<main><h1>ROUND product document</h1></main>',
    })
  })

  return { calls }
}

function projectionWithRoundResource() {
  const projection = makeProjection()
  projection.resources.push({
    id: SECOND_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: RESOURCE_TITLE,
    url: 'https://example.com/round-guide',
    description: '스터디 진행 순서',
    createdAt: '2026-08-09T11:00:00Z',
  })
  return projection
}

async function openRoundResource(page: Page, projectName: string) {
  await navigation(page, projectName).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  const inspector = page.getByLabel(/선택한 역할 상세: 문제 큐레이터/)
  await expect(inspector.getByRole('link', { name: `${RESOURCE_TITLE} 새 창에서 열기` }))
    .toBeVisible()
  return inspector
}

test('@smoke 역할 자료에서 ROUND 방을 시작하고 같은 기기에서 종료한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page)
  await openSharedWorkspace(page)
  let inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: 'ROUND 시작' }).click()
  await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
  await expect(page.getByRole('heading', { name: 'ROUND product document' })).toBeVisible()

  const createCall = roundApi.calls.find((call) => (
    call.method === 'POST' && call.path === '/api/v1/round-room-mappings'
  ))
  expect(createCall?.headers['x-baton-access-key']).toBe(ACCESS_KEY)
  expect(createCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(createCall?.body).toEqual({
    resourceId: SECOND_ROLE_RESOURCE_ID,
    seasonId: SEASON_ID,
    teamId: TEAM_ID,
  })

  const storedEntries = await page.evaluate(() => Object.fromEntries(
    Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
      .filter((key): key is string => key !== null)
      .map((key) => [key, sessionStorage.getItem(key)]),
  ))
  expect(JSON.stringify(storedEntries)).not.toContain(ACCESS_KEY)
  expect(JSON.parse(storedEntries[`baton-round-entry:v1:${ROOM_ID}`] ?? '{}')).toEqual({
    version: 1,
    resourceId: SECOND_ROLE_RESOURCE_ID,
    roomId: ROOM_ID,
    seasonId: SEASON_ID,
    teamId: TEAM_ID,
  })

  await page.goto(WORKSPACE_PATH)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()
  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toContain('같은 방 ID로 다시 입장할 수 없습니다.')
    await dialog.accept()
  })
  await inspector.getByRole('button', { name: 'ROUND 종료' }).click()
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toBeVisible()

  const endCall = roundApi.calls.find((call) => (
    call.method === 'DELETE'
      && call.path === `/api/v1/round-room-mappings/${ROOM_ID}`
  ))
  expect(endCall?.headers['x-baton-access-key']).toBe(ACCESS_KEY)
  expect(endCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(await page.evaluate((roomId) => sessionStorage.getItem(
    `baton-round-entry:v1:${roomId}`,
  ), ROOM_ID)).toBeNull()
})

test('@smoke 로그인했지만 구성원 연결 전에는 ROUND 대신 계정 연결을 연다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page, { claimed: false })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: '계정 연결 후 ROUND 시작' }).click()
  await expect(page.getByRole('dialog', { name: '구성원 관리' }))
    .toContainText('내 계정 연결')
  expect(roundApi.calls.filter((call) => call.path === '/api/v1/round-room-mappings'))
    .toHaveLength(0)
})

test('@smoke sessionStorage가 막혀도 서버가 만든 ROUND 방에는 입장한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page)
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)
  await page.evaluate(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function blockedRoundEntryStorage(key, value) {
      if (this === sessionStorage && key.startsWith('baton-round-')) {
        throw new DOMException('test storage denial', 'QuotaExceededError')
      }
      return originalSetItem.call(this, key, value)
    }
  })

  await inspector.getByRole('button', { name: 'ROUND 시작' }).click()

  await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
  await expect(page.getByRole('heading', { name: 'ROUND product document' })).toBeVisible()
  expect(roundApi.calls.some((call) => (
    call.method === 'POST' && call.path === '/api/v1/round-room-mappings'
  ))).toBe(true)
  expect(await page.evaluate((roomId) => sessionStorage.getItem(
    `baton-round-entry:v1:${roomId}`,
  ), ROOM_ID)).toBeNull()
})

test('@smoke 익명 사용자는 접근 키 없는 로그인 복귀 링크를 받는다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  await installRoundProductApi(page, { authenticated: false })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  const loginLink = inspector.getByRole('link', { name: '로그인 후 ROUND 시작' })
  const href = await loginLink.getAttribute('href')
  expect(href).toContain(`/login?returnTo=${encodeURIComponent(WORKSPACE_PATH)}`)
  expect(href).not.toContain('accessKey')
})
