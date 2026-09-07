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
const SECOND_RESOURCE_TITLE = 'ROUND 회고 자료'
const THIRD_ROLE_RESOURCE_ID = '00000000-0000-4000-8000-000000000057'

type RoundProductCall = {
  body: unknown
  headers: Record<string, string>
  method: string
  path: string
  query?: Record<string, string>
}

type RoundProductApiOptions = {
  activeMapping?: boolean
  additiveResponseFields?: boolean
  authenticated?: boolean
  claimed?: boolean
  failNextEndAsAlreadyEnded?: boolean
  nullDeleteEndedAt?: boolean
  serverOwnedDomainState?: boolean
}

type HeldMappingRead = {
  finished: Promise<void>
  release: () => void
  started: Promise<void>
}

async function installRoundProductApi(
  page: Page,
  options: RoundProductApiOptions = {},
) {
  const calls: RoundProductCall[] = []
  const authenticated = options.authenticated ?? true
  const claimed = options.claimed ?? true
  let activeMapping = options.activeMapping ?? false
  let failNextEndAsAlreadyEnded = options.failNextEndAsAlreadyEnded ?? false
  const nullDeleteEndedAt = options.nullDeleteEndedAt ?? false
  const responseExtension = options.additiveResponseFields
    ? { futureServerField: 'ignored' }
    : {}
  const serverOwnedDomainState = options.serverOwnedDomainState ?? false
  let holdNextMappingRead: {
    finished: () => void
    release: Promise<void>
    started: () => void
  } | null = null
  const json = (route: Route, status: number, body: unknown) => route.fulfill({
    status,
    json: body,
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
      query: Object.fromEntries(new URL(request.url()).searchParams),
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
    const url = new URL(request.url())
    const path = url.pathname
    calls.push({
      body: request.postData() ? request.postDataJSON() : null,
      headers: request.headers(),
      method: request.method(),
      path,
      query: Object.fromEntries(url.searchParams),
    })
    const mapping = {
      roomId: ROOM_ID,
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      resourceId: SECOND_ROLE_RESOURCE_ID,
      createdAt: '2026-08-09T12:35:00Z',
      endedAt: request.method() === 'DELETE'
        ? nullDeleteEndedAt ? null : '2026-08-09T13:00:00Z'
        : serverOwnedDomainState ? '2026-08-09T13:00:00Z' : null,
      ...responseExtension,
    }
    if (request.method() === 'GET') {
      const currentMappings = activeMapping ? [{ ...mapping }] : []
      const response = {
        mappings: serverOwnedDomainState
          ? [...currentMappings, ...currentMappings]
          : currentMappings,
        ...responseExtension,
      }
      const heldRead = holdNextMappingRead
      if (heldRead) {
        holdNextMappingRead = null
        heldRead.started()
        await heldRead.release
      }
      try {
        return await json(route, 200, response)
      } catch {
        return undefined
      } finally {
        heldRead?.finished()
      }
    }
    if (request.method() === 'POST') activeMapping = true
    if (request.method() === 'DELETE') {
      activeMapping = false
      if (failNextEndAsAlreadyEnded) {
        failNextEndAsAlreadyEnded = false
        return json(route, 404, {
          code: 'ROUND_ROOM_NOT_FOUND',
          message: '이미 다른 참여자가 ROUND 방을 종료했습니다.',
        })
      }
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

  return {
    calls,
    holdNextMappingRead(): HeldMappingRead {
      const release = Promise.withResolvers<void>()
      const finished = Promise.withResolvers<void>()
      const started = Promise.withResolvers<void>()
      holdNextMappingRead = {
        finished: finished.resolve,
        release: release.promise,
        started: started.resolve,
      }
      return {
        finished: finished.promise,
        release: release.resolve,
        started: started.promise,
      }
    },
  }
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
    archivedAt: null,
  })
  return projection
}

function projectionWithMultipleRoundResources() {
  const projection = projectionWithRoundResource()
  projection.resources.push({
    id: THIRD_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: SECOND_RESOURCE_TITLE,
    url: 'https://example.com/round-retrospective',
    description: '스터디 회고 질문',
    createdAt: '2026-08-09T11:01:00Z',
    archivedAt: null,
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

test('@webkit 역할 자료에서 ROUND 방을 시작하고 같은 기기에서 종료한다', async ({ page }, testInfo) => {
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
  await expect(page.getByRole('heading', { level: 1, name: /남은 업무 \d+개/ })).toBeVisible()
  inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()
  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toContain('이 방에 다시 입장할 수 없습니다.')
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

test('@smoke ROUND 시작 중 화면을 떠나면 자동 입장하지 않고 돌아와 만든 방에 입장한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page)
  await openSharedWorkspace(page)
  await page.goto('/')
  await page.getByRole('link', { name: /알고리즘 한 바퀴.*2026 여름 시즌/ }).click()
  const inspector = await openRoundResource(page, testInfo.project.name)
  const creationStarted = Promise.withResolvers<void>()
  const creationResponse = Promise.withResolvers<void>()
  await page.route('**/api/v1/round-room-mappings', async (route) => {
    creationStarted.resolve()
    await creationResponse.promise
    await route.fallback()
  }, { times: 1 })
  try {
    await inspector.getByRole('button', { name: 'ROUND 시작', exact: true }).click()
    await creationStarted.promise
    await page.goBack()
    const response = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/round-room-mappings')
    creationResponse.resolve()
    await (await response).finished()
    await expect(page.getByRole('heading', { level: 1, name: /담당 업무부터/ })).toBeVisible()
    expect(await page.evaluate((roomId) => sessionStorage.getItem(`baton-round-entry:v1:${roomId}`), ROOM_ID)).toBeNull()

    await page.getByRole('link', { name: /알고리즘 한 바퀴.*2026 여름 시즌/ }).click()
    const reopenedInspector = await openRoundResource(page, testInfo.project.name)
    await reopenedInspector.getByRole('button', { name: 'ROUND 입장', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
    expect(roundApi.calls.filter((call) => call.method === 'POST')).toHaveLength(1)
  } finally {
    creationResponse.resolve()
  }
})

test('ROUND 응답은 additive field를 무시한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  await installRoundProductApi(page, {
    additiveResponseFields: true,
  })
  await openSharedWorkspace(page)
  let inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: 'ROUND 시작' }).click()
  await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
  await page.goto(WORKSPACE_PATH)
  inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()
  page.once('dialog', async (dialog) => dialog.accept())
  await inspector.getByRole('button', { name: 'ROUND 종료' }).click()
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toBeVisible()
})

test('현재 ROUND 목록의 종료·중복 상태는 서버 응답으로 수용한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  await installRoundProductApi(page, {
    activeMapping: true,
    serverOwnedDomainState: true,
  })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()
  await expect(inspector.getByRole('button', { name: 'ROUND 연결 다시 확인' })).toHaveCount(0)
})

test('생성 응답의 endedAt은 서버 상태로 수용한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  await installRoundProductApi(page, { serverOwnedDomainState: true })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: 'ROUND 시작' }).click()
  await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
  await expect(page.getByRole('heading', { name: 'ROUND product document' })).toBeVisible()
})

test('종료 응답은 non-null endedAt instant wire contract을 유지한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  await installRoundProductApi(page, { nullDeleteEndedAt: true })
  await openSharedWorkspace(page)
  let inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: 'ROUND 시작' }).click()
  await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
  await page.goto(WORKSPACE_PATH)
  inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()

  page.once('dialog', async (dialog) => dialog.accept())
  await inspector.getByRole('button', { name: 'ROUND 종료' }).click()
  await expect(inspector.getByRole('alert')).toContainText('서버 응답을 확인할 수 없습니다.')
})

test('로그인했지만 구성원 연결 전에는 ROUND 대신 계정 연결을 연다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page, { claimed: false })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: '내 이름 선택 후 ROUND 시작' }).click()
  await expect(page.getByRole('dialog', { name: '구성원 관리' }))
    .toContainText('내 이름 선택')
  expect(roundApi.calls.filter((call) => call.path === '/api/v1/round-room-mappings'))
    .toHaveLength(0)
})

test('sessionStorage가 막혀도 서버 매핑을 다시 조회해 돌아온 화면에서 종료한다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function blockedRoundEntryStorage(key, value) {
      if (this === sessionStorage && key.startsWith('baton-round-')) {
        throw new DOMException('test storage denial', 'QuotaExceededError')
      }
      return originalSetItem.call(this, key, value)
    }
  })
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page)
  await openSharedWorkspace(page)
  let inspector = await openRoundResource(page, testInfo.project.name)

  await inspector.getByRole('button', { name: 'ROUND 시작' }).click()

  await expect(page).toHaveURL(new RegExp(`/room/${ROOM_ID}$`))
  await expect(page.getByRole('heading', { name: 'ROUND product document' })).toBeVisible()
  expect(roundApi.calls.some((call) => (
    call.method === 'POST' && call.path === '/api/v1/round-room-mappings'
  ))).toBe(true)
  expect(await page.evaluate((roomId) => sessionStorage.getItem(
    `baton-round-entry:v1:${roomId}`,
  ), ROOM_ID)).toBeNull()

  await page.goto(WORKSPACE_PATH)
  await expect(page.getByRole('heading', { level: 1, name: /남은 업무 \d+개/ })).toBeVisible()
  inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()

  const mappingReads = roundApi.calls.filter((call) => (
    call.method === 'GET' && call.path === '/api/v1/round-room-mappings'
  ))
  expect(mappingReads.length).toBeGreaterThanOrEqual(2)
  expect(mappingReads.at(-1)?.query).toEqual({
    seasonId: SEASON_ID,
    teamId: TEAM_ID,
  })

  page.once('dialog', async (dialog) => dialog.accept())
  await inspector.getByRole('button', { name: 'ROUND 종료' }).click()
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toBeVisible()
  expect(roundApi.calls.some((call) => (
    call.method === 'DELETE'
      && call.path === `/api/v1/round-room-mappings/${ROOM_ID}`
  ))).toBe(true)
})

test('여러 자료 행은 최초 진입과 focus마다 ROUND 매핑 목록을 한 번만 조회한다', async ({ page }, testInfo) => {
  // StrictMode가 마운트 때 취소한 요청은 완료된 조회에 포함하지 않는다.
  let completedMappingReads = 0
  page.on('requestfinished', request => {
    if (request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/round-room-mappings') {
      completedMappingReads += 1
    }
  })
  await installApi(page, projectionWithMultipleRoundResources())
  const roundApi = await installRoundProductApi(page)
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  await expect(inspector.getByRole('link', {
    name: `${SECOND_RESOURCE_TITLE} 새 창에서 열기`,
  })).toBeVisible()
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toHaveCount(2)
  const mappingReads = () => roundApi.calls.filter((call) => (
    call.method === 'GET' && call.path === '/api/v1/round-room-mappings'
  ))
  await expect.poll(() => completedMappingReads).toBe(1)

  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect.poll(() => completedMappingReads).toBe(2)
  expect(mappingReads().every((call) => (
    JSON.stringify(call.query) === JSON.stringify({
      seasonId: SEASON_ID,
      teamId: TEAM_ID,
    })
  ))).toBe(true)
})

test('종료 직전의 늦은 GET은 DELETE 뒤 종료된 매핑을 되살리지 못한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page, { activeMapping: true })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()

  const heldRead = roundApi.holdNextMappingRead()
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await heldRead.started

  page.once('dialog', async (dialog) => dialog.accept())
  await inspector.getByRole('button', { name: 'ROUND 종료' }).click()
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toBeVisible()
  heldRead.release()
  await heldRead.finished
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toBeVisible()
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toHaveCount(0)
})

test('경쟁자가 먼저 종료해 DELETE가 실패해도 서버 목록을 다시 조회한다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  const roundApi = await installRoundProductApi(page, {
    activeMapping: true,
    failNextEndAsAlreadyEnded: true,
  })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)
  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeVisible()

  page.once('dialog', async (dialog) => dialog.accept())
  await inspector.getByRole('button', { name: 'ROUND 종료' }).click()
  await expect(inspector.getByRole('button', { name: 'ROUND 시작' })).toBeVisible()
  await expect(inspector).toContainText('이미 다른 참여자가 ROUND 방을 종료했습니다.')
  expect(roundApi.calls.filter((call) => (
    call.method === 'GET' && call.path === '/api/v1/round-room-mappings'
  )).length).toBeGreaterThanOrEqual(2)
})

test('종료 시즌에서는 서버가 거부할 ROUND 종료 동작을 노출하되 실행할 수 없게 한다', async ({ page }, testInfo) => {
  const projection = projectionWithRoundResource()
  projection.season.endedAt = '2026-08-09T14:00:00Z'
  projection.seasons[0]!.endedAt = projection.season.endedAt
  await installApi(page, projection)
  const roundApi = await installRoundProductApi(page, { activeMapping: true })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  await expect(inspector.getByRole('button', { name: 'ROUND 입장' })).toBeDisabled()
  await expect(inspector.getByRole('button', { name: 'ROUND 종료' })).toBeDisabled()
  await expect(inspector).toContainText(
    '종료된 시즌에서는 ROUND 방에 입장하거나 종료할 수 없습니다.',
  )
  expect(roundApi.calls.some((call) => call.method === 'DELETE')).toBe(false)
})

test('익명 사용자는 접근 키 없는 로그인 복귀 링크를 받는다', async ({ page }, testInfo) => {
  await installApi(page, projectionWithRoundResource())
  await installRoundProductApi(page, { authenticated: false })
  await openSharedWorkspace(page)
  const inspector = await openRoundResource(page, testInfo.project.name)

  const loginLink = inspector.getByRole('link', { name: '로그인 후 ROUND 시작' })
  const href = await loginLink.getAttribute('href')
  expect(href).toContain(`/login?returnTo=${encodeURIComponent(WORKSPACE_PATH)}`)
  expect(href).not.toContain('accessKey')
})
