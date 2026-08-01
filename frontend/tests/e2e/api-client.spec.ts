import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

type BrowserRequestResult =
  | {
    ok: true
    value: unknown
  }
  | {
    ok: false
    name: string
    message: string
    kind?: string
    status?: number
    code?: string
    requestId?: string
  }

async function apiRequestFromBrowser(
  page: Page,
  path: string,
  options: { timeoutMs?: number } = {},
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ requestPath, requestOptions }) => {
    const { apiRequest } = await import('/src/shared/api/client.ts')

    try {
      const value = await apiRequest<unknown>(requestPath, requestOptions)
      return { ok: true as const, value }
    } catch (error) {
      const apiError = error as Error & {
        kind?: string
        status?: number
        code?: string
        requestId?: string
      }
      return {
        ok: false as const,
        name: apiError.name,
        message: apiError.message,
        kind: apiError.kind,
        status: apiError.status,
        code: apiError.code,
        requestId: apiError.requestId,
      }
    }
  }, { requestPath: path, requestOptions: options })
}

async function workspaceRequestFromBrowser(
  page: Page,
  scope: { teamId: string; seasonId: string; accessKey: string },
): Promise<BrowserRequestResult> {
  return page.evaluate(async (workspaceScope) => {
    const { getWorkspace } = await import('/src/features/workspace/api.ts')

    try {
      const value = await getWorkspace(workspaceScope)
      return { ok: true as const, value }
    } catch (error) {
      const apiError = error as Error & { kind?: string }
      return {
        ok: false as const,
        name: apiError.name,
        message: apiError.message,
        kind: apiError.kind,
      }
    }
  }, scope)
}

function workspaceProjectionWithMalformedMember(scope: { teamId: string; seasonId: string }) {
  const season = {
    id: scope.seasonId,
    name: '2026 여름 시즌',
    startDate: '2026-07-01',
    endDate: '2026-09-30',
    timeZone: 'Asia/Seoul',
    endedAt: null,
    previousSeasonId: null,
    roundSchedule: null,
  }

  return {
    team: { id: scope.teamId, name: 'BATON 스터디' },
    season,
    seasons: [season],
    continuitySignals: [],
    decisions: [],
    handoffItems: [],
    members: [null],
    resources: [],
    roleHandoffs: [],
    roles: [],
    rounds: [],
    routines: [],
  }
}

test.beforeEach(async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '공용 전송 계층은 데스크톱 Chromium에서 한 번만 검증합니다.')
  await page.goto('/')
})

test('@smoke 연결이 끊기면 network 오류로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/network', (route) => route.abort('connectionreset'))

  await expect(apiRequestFromBrowser(page, '/api-client-test/network')).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'network',
    message: '서버에 연결하지 못해 요청 결과를 확인할 수 없습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.',
  })
})

test('@smoke 응답 제한 시간을 넘기면 timeout 오류로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/timeout', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 100))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ delayed: true }),
    })
  })

  await expect(apiRequestFromBrowser(page, '/api-client-test/timeout', { timeoutMs: 20 })).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'timeout',
    message: '서버 응답이 늦어 요청 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  })
})

test('@smoke 성공 응답이 JSON이 아니거나 손상되면 invalid-response로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/plain-text', (route) => route.fulfill({
    status: 200,
    contentType: 'text/plain',
    body: 'BATON is ready',
  }))
  await page.route('**/api-client-test/malformed-json', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"ready":',
  }))

  const expectedError = {
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  }
  await expect(apiRequestFromBrowser(page, '/api-client-test/plain-text')).resolves.toEqual(expectedError)
  await expect(apiRequestFromBrowser(page, '/api-client-test/malformed-json')).resolves.toEqual(expectedError)
})

test('@smoke 워크스페이스 성공 응답의 필수 shape가 없으면 복구 가능한 invalid-response로 수렴한다', async ({ page }) => {
  const scope = {
    teamId: '11111111-1111-4111-8111-111111111111',
    seasonId: '22222222-2222-4222-8222-222222222222',
    accessKey: 'pilot-access-key',
  }
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{}',
    }),
  )

  await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  })

  await page.goto(
    `/teams/${scope.teamId}/seasons/${scope.seasonId}#accessKey=${scope.accessKey}`,
  )

  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.')).toBeVisible()
  await expect(page.getByRole('button', { name: '다시 시도하기' })).toBeVisible()
})

test('@smoke 워크스페이스 배열의 손상된 원소도 복구 가능한 invalid-response로 수렴한다', async ({ page }) => {
  const scope = {
    teamId: '33333333-3333-4333-8333-333333333333',
    seasonId: '44444444-4444-4444-8444-444444444444',
    accessKey: 'pilot-access-key',
  }
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(workspaceProjectionWithMalformedMember(scope)),
    }),
  )

  await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  })

  await page.goto(
    `/teams/${scope.teamId}/seasons/${scope.seasonId}#accessKey=${scope.accessKey}`,
  )

  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.')).toBeVisible()
  await expect(page.getByRole('button', { name: '다시 시도하기' })).toBeVisible()
})

test('@smoke HTTP 오류는 계약 정보를 보존하고 손상된 오류 본문은 공용 값으로 대체한다', async ({ page }) => {
  const conflictRequestId = '11111111-2222-4333-8444-555555555555'
  const serverErrorRequestId = '66666666-7777-4888-8999-aaaaaaaaaaaa'
  await page.route('**/api-client-test/conflict', (route) => route.fulfill({
    status: 409,
    contentType: 'application/json',
    headers: { 'X-Request-ID': conflictRequestId },
    body: JSON.stringify({
      code: 'WORKSPACE_CONTENT_CONFLICT',
      message: '다른 구성원이 먼저 내용을 변경했습니다.',
    }),
  }))
  await page.route('**/api-client-test/malformed-error', (route) => route.fulfill({
    status: 502,
    contentType: 'application/json',
    headers: { 'X-Request-ID': serverErrorRequestId },
    body: JSON.stringify({ code: 1, message: {} }),
  }))

  await expect(apiRequestFromBrowser(page, '/api-client-test/conflict')).resolves.toEqual({
    ok: false,
    name: 'ApiError',
    status: 409,
    code: 'WORKSPACE_CONTENT_CONFLICT',
    requestId: conflictRequestId,
    message: '다른 구성원이 먼저 내용을 변경했습니다.',
  })
  await expect(apiRequestFromBrowser(page, '/api-client-test/malformed-error')).resolves.toEqual({
    ok: false,
    name: 'ApiError',
    status: 502,
    code: 'UNKNOWN_ERROR',
    requestId: serverErrorRequestId,
    message: `요청을 처리하지 못했습니다. (요청 ID: ${serverErrorRequestId})`,
  })
})
