import { setTimeout as delay } from 'node:timers/promises'
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
      const value = await apiRequest<unknown>(requestPath, {
        ...requestOptions,
        decode: (response) => response,
      })
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

async function noContentRequestFromBrowser(
  page: Page,
  path: string,
): Promise<BrowserRequestResult> {
  return page.evaluate(async (requestPath) => {
    const { apiRequest } = await import('/src/shared/api/client.ts')

    try {
      const value = await apiRequest(requestPath, { responseType: 'no-content' })
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
  }, path)
}

async function acceptRoleHandoffRequestFromBrowser(
  page: Page,
  scope: { teamId: string; seasonId: string; accessKey: string },
  roleId: string,
  handoffId: string,
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ workspaceScope, requestedRoleId, requestedHandoffId }) => {
    const { acceptRoleHandoff } = await import('/src/features/workspace/api.ts')

    try {
      const value = await acceptRoleHandoff(
        workspaceScope,
        requestedRoleId,
        requestedHandoffId,
        { confirmedByMemberId: '22222222-2222-4222-8222-222222222222' },
      )
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
  }, {
    workspaceScope: scope,
    requestedRoleId: roleId,
    requestedHandoffId: handoffId,
  })
}

async function updateRoutineExecutionRequestFromBrowser(
  page: Page,
  scope: { teamId: string; seasonId: string; accessKey: string },
  roundId: string,
  executionId: string,
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ workspaceScope, requestedRoundId, requestedExecutionId }) => {
    const { setRoutineExecutionCompletion } = await import('/src/features/workspace/api.ts')

    try {
      const value = await setRoutineExecutionCompletion(
        workspaceScope,
        requestedRoundId,
        requestedExecutionId,
        true,
      )
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
  }, {
    workspaceScope: scope,
    requestedRoundId: roundId,
    requestedExecutionId: executionId,
  })
}

async function abortableApiRequestFromBrowser(
  page: Page,
  path: string,
): Promise<BrowserRequestResult> {
  return page.evaluate(async (requestPath) => {
    const { apiRequest } = await import('/src/shared/api/client.ts')
    const originalFetch = window.fetch
    const controller = new AbortController()
    window.fetch = (_input, init) => {
      const signal = init?.signal
      if (!signal) {
        return Promise.reject(new Error('요청 취소 신호가 전달되지 않았습니다.'))
      }

      return new Promise<Response>((_resolve, reject) => {
        if (signal.aborted) {
          reject(signal.reason)
          return
        }
        signal.addEventListener('abort', () => reject(signal.reason), { once: true })
      })
    }

    try {
      const result = apiRequest<unknown>(requestPath, {
        decode: (response) => response,
        signal: controller.signal,
        timeoutMs: 1_000,
      })
        .then((value) => ({ ok: true as const, value }))
        .catch((error: Error & { kind?: string }) => ({
          ok: false as const,
          name: error.name,
          message: error.message,
          kind: error.kind,
        }))
      controller.abort()
      return await result
    } finally {
      window.fetch = originalFetch
    }
  }, path)
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

async function createWorkspaceRequestFromBrowser(
  page: Page,
  idempotencyKey: string,
): Promise<BrowserRequestResult> {
  return page.evaluate(async (requestIdempotencyKey) => {
    const { createWorkspace } = await import('/src/features/workspace/api.ts')

    try {
      const value = await createWorkspace({
        teamName: '응답 경계 스터디',
        seasonName: '2026 가을 시즌',
        startDate: '2026-09-01',
        endDate: '2026-11-30',
        memberNames: ['박민서'],
      }, { idempotencyKey: requestIdempotencyKey })
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
  }, idempotencyKey)
}

async function rotateAccessKeyRequestFromBrowser(
  page: Page,
  scope: { teamId: string; seasonId: string; accessKey: string },
  idempotencyKey: string,
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ workspaceScope, requestIdempotencyKey }) => {
    const { rotateAccessKey } = await import('/src/features/workspace/api.ts')

    try {
      const value = await rotateAccessKey(workspaceScope, requestIdempotencyKey)
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
  }, { workspaceScope: scope, requestIdempotencyKey: idempotencyKey })
}

async function createNextSeasonRequestFromBrowser(
  page: Page,
  scope: { teamId: string; seasonId: string; accessKey: string },
): Promise<BrowserRequestResult> {
  return page.evaluate(async (workspaceScope) => {
    const { createNextSeason } = await import('/src/features/workspace/api.ts')

    try {
      const value = await createNextSeason(workspaceScope, {
        name: '다음 시즌',
        startDate: '2026-10-01',
        endDate: '2026-12-31',
        copyRoleIds: [],
        copyRoutineIds: [],
      }, 'next-season-response-boundary-00000000000000000000000000000000')
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

function acceptedRoleHandoffTransitionResponse() {
  return {
    role: {
      id: '11111111-1111-4111-8111-111111111111',
      name: '문제 큐레이터',
      purpose: '문제 선정 기준을 유지합니다.',
      previousRoleId: null,
      currentMemberId: '22222222-2222-4222-8222-222222222222',
      nextMemberId: null as string | null,
      assignmentStartDate: '2026-09-17',
      assignmentEndDate: null as string | null,
      responsibilities: ['문제 선정'],
      risk: null as string | null,
    },
    handoff: {
      id: '33333333-3333-4333-8333-333333333333',
      roleId: '11111111-1111-4111-8111-111111111111',
      fromMemberId: '44444444-4444-4444-8444-444444444444',
      toMemberId: '22222222-2222-4222-8222-222222222222',
      outgoingAssignmentStartDate: '2026-07-02',
      outgoingAssignmentEndDate: '2026-09-16',
      incomingAssignmentStartDate: '2026-09-17',
      incomingAssignmentEndDate: null as string | null,
      status: 'ACCEPTED',
      preparedAt: '2026-09-01T09:00:00Z',
      transferredAt: '2026-09-02T09:00:00Z',
      acceptedAt: '2026-09-03T09:00:00Z',
      cancelledAt: null as string | null,
      transferredByMemberId: '44444444-4444-4444-8444-444444444444',
      acceptedByMemberId: '22222222-2222-4222-8222-222222222222',
      cancelledByMemberId: null as string | null,
      activeItemCount: 1,
      incompleteItemCount: 0,
      resourceCount: 1,
      warningAcknowledged: false,
    },
  }
}

function workspaceProjection(scope: { teamId: string; seasonId: string }) {
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
    team: { id: scope.teamId, name: 'BATON 스터디', accountAccessEnabled: false, permission: null },
    season,
    seasons: [season],
    continuitySignals: [],
    decisions: [],
    handoffItems: [],
    members: [],
    resources: [],
    roleHandoffs: [],
    roles: [],
    rounds: [],
    routines: [],
  }
}

function workspaceProjectionWithMalformedMember(scope: { teamId: string; seasonId: string }) {
  return {
    ...workspaceProjection(scope),
    members: [null],
  }
}

test.beforeEach(async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '공용 전송 계층은 데스크톱 Chromium에서 한 번만 검증합니다.')
  await page.goto('/')
})

test('연결이 끊기면 network 오류로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/network', (route) => route.abort('connectionreset'))

  await expect(apiRequestFromBrowser(page, '/api-client-test/network')).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'network',
    message: '서버에 연결하지 못해 요청 결과를 확인할 수 없습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.',
  })
})

test('응답 제한 시간을 넘기면 timeout 오류로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/timeout', async (route) => {
    await delay(100)
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

test('@webkit 외부 취소 신호를 timeout으로 오인하지 않는다', async ({ page }) => {
  const result = await abortableApiRequestFromBrowser(page, '/api-client-test/external-abort')

  expect(result).toMatchObject({ ok: false, name: 'AbortError' })
  expect(result.ok ? undefined : result.kind).toBeUndefined()
})

test('성공 응답이 JSON이 아니거나 손상되면 invalid-response로 분류한다', async ({ page }) => {
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

test('204는 명시한 no-content 계약에서만 성공한다', async ({ page }) => {
  await page.route('**/api-client-test/no-content', (route) => route.fulfill({ status: 204 }))
  await page.route('**/api-client-test/unexpected-content', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ ignored: true }),
  }))

  await expect(noContentRequestFromBrowser(page, '/api-client-test/no-content'))
    .resolves.toEqual({ ok: true, value: undefined })

  const expectedError = {
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  }
  await expect(apiRequestFromBrowser(page, '/api-client-test/no-content'))
    .resolves.toEqual(expectedError)
  await expect(noContentRequestFromBrowser(page, '/api-client-test/unexpected-content'))
    .resolves.toEqual(expectedError)
})

test('역할 바통 전이 응답이 nextMemberId를 누락하면 invalid-response로 분류한다', async ({ page }) => {
  const response = acceptedRoleHandoffTransitionResponse()
  const scope = {
    teamId: '77777777-7777-4777-8777-777777777777',
    seasonId: '88888888-8888-4888-8888-888888888888',
    accessKey: 'pilot-access-key',
  }
  const path = `/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/roles/${response.role.id}/handoffs/${response.handoff.id}/acceptance`
  Reflect.deleteProperty(response.role, 'nextMemberId')
  await page.route(`**${path}`, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(response),
  }))

  await expect(acceptRoleHandoffRequestFromBrowser(
    page,
    scope,
    response.role.id,
    response.handoff.id,
  )).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  })
})

test('역할 바통 응답은 서버 상태와 별개로 요청 경로의 식별자를 유지한다', async ({ page }) => {
  const scope = {
    teamId: '77777777-7777-4777-8777-777777777777',
    seasonId: '88888888-8888-4888-8888-888888888888',
    accessKey: 'pilot-access-key',
  }
  const roleId = '11111111-1111-4111-8111-111111111111'
  const handoffId = '33333333-3333-4333-8333-333333333333'
  const path = `/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/roles/${roleId}/handoffs/${handoffId}/acceptance`
  let response = acceptedRoleHandoffTransitionResponse()
  await page.route(`**${path}`, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(response),
  }))

  response.handoff.status = 'TRANSFERRED'
  Reflect.set(response.handoff, 'transferredAt', null)
  response.handoff.activeItemCount = -1
  response.handoff.incompleteItemCount = 1.5
  await expect(acceptRoleHandoffRequestFromBrowser(
    page,
    scope,
    roleId,
    handoffId,
  )).resolves.toEqual({ ok: true, value: response })

  for (const scenario of [
    {
      name: '역할과 바통의 roleId 불일치',
      mutate: () => {
        response.handoff.roleId = '99999999-9999-4999-8999-999999999999'
      },
    },
    {
      name: '요청 경로와 응답 roleId 불일치',
      mutate: () => {
        response.role.id = '99999999-9999-4999-8999-999999999999'
        response.handoff.roleId = response.role.id
      },
    },
    {
      name: '요청 경로와 응답 handoffId 불일치',
      mutate: () => {
        response.handoff.id = '99999999-9999-4999-8999-999999999999'
      },
    },
    {
      name: '잘못된 UUID 형식',
      mutate: () => {
        response.handoff.roleId = 'not-a-uuid'
      },
    },
    {
      name: '알 수 없는 status enum',
      mutate: () => {
        Reflect.set(response.handoff, 'status', 'UNKNOWN')
      },
    },
    {
      name: '잘못된 count primitive type',
      mutate: () => {
        Reflect.set(response.handoff, 'activeItemCount', '1')
      },
    },
  ]) {
    await test.step(scenario.name, async () => {
      response = acceptedRoleHandoffTransitionResponse()
      scenario.mutate()
      await expect(acceptRoleHandoffRequestFromBrowser(
        page,
        scope,
        roleId,
        handoffId,
      )).resolves.toEqual({
        ok: false,
        name: 'ApiClientError',
        kind: 'invalid-response',
        message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      })
    })
  }
})

test('루틴 완료 응답은 요청한 회차와 실행 식별자를 유지한다', async ({ page }) => {
  const scope = {
    teamId: '77777777-7777-4777-8777-777777777777',
    seasonId: '88888888-8888-4888-8888-888888888888',
    accessKey: 'pilot-access-key',
  }
  const roundId = '11111111-1111-4111-8111-111111111111'
  const executionId = '22222222-2222-4222-8222-222222222222'
  const path = `/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/rounds/${roundId}/routine-executions/${executionId}/completion`
  const validResponse = () => ({
    id: executionId,
    roundId,
    routineId: '33333333-3333-4333-8333-333333333333',
    title: '풀이 노트 정리',
    phase: 'AFTER',
    dueLabel: '모임 다음 날',
    ownerRoleId: '44444444-4444-4444-8444-444444444444',
    detail: '풀이를 정리합니다',
    status: 'DONE',
    deadlineAt: null,
    timingStatus: 'COMPLETED',
  })
  let response = validResponse()
  await page.route(`**${path}`, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(response),
  }))

  await expect(updateRoutineExecutionRequestFromBrowser(
    page,
    scope,
    roundId,
    executionId,
  )).resolves.toEqual({ ok: true, value: response })

  for (const scenario of [
    {
      name: '다른 실행 식별자',
      mutate: () => {
        response.id = '99999999-9999-4999-8999-999999999999'
      },
    },
    {
      name: '다른 부모 회차',
      mutate: () => {
        response.roundId = '99999999-9999-4999-8999-999999999999'
      },
    },
  ]) {
    await test.step(scenario.name, async () => {
      response = validResponse()
      scenario.mutate()
      await expect(updateRoutineExecutionRequestFromBrowser(
        page,
        scope,
        roundId,
        executionId,
      )).resolves.toEqual({
        ok: false,
        name: 'ApiClientError',
        kind: 'invalid-response',
        message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      })
    })
  }
})

test('다음 시즌 응답은 요청한 원본 시즌과 직접 계보를 유지한다', async ({ page }) => {
  const scope = {
    teamId: '77777777-7777-4777-8777-777777777777',
    seasonId: '88888888-8888-4888-8888-888888888888',
    accessKey: 'pilot-access-key',
  }
  const validResponse = () => ({
    sourceSeason: {
      id: scope.seasonId,
      name: '원본 시즌',
      startDate: '2026-07-01',
      endDate: '2026-09-30',
      timeZone: 'Asia/Seoul',
      endedAt: '2026-09-30T09:00:00Z' as string | null,
      previousSeasonId: null as string | null,
      roundSchedule: null,
    },
    season: {
      id: '99999999-9999-4999-8999-999999999999',
      name: '다음 시즌',
      startDate: '2026-10-01',
      endDate: '2026-12-31',
      timeZone: 'Asia/Seoul',
      endedAt: null,
      previousSeasonId: scope.seasonId as string | null,
      roundSchedule: null,
    },
    copiedRoles: [],
    copiedRoutines: [],
  })
  let response = validResponse()
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/successor`,
    (route) => route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(response),
    }),
  )

  await expect(createNextSeasonRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: true,
    value: response,
  })

  for (const scenario of [
    {
      name: '요청 경로와 다른 원본 시즌',
      mutate: () => {
        response.sourceSeason.id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        response.season.previousSeasonId = response.sourceSeason.id
      },
    },
    {
      name: '원본과 다른 이전 시즌 계보',
      mutate: () => {
        response.season.previousSeasonId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
      },
    },
    {
      name: '원본 시즌의 필수 종료 instant 누락',
      mutate: () => {
        response.sourceSeason.endedAt = null
      },
    },
    {
      name: '새 시즌의 원본 계보 누락',
      mutate: () => {
        response.season.previousSeasonId = null
      },
    },
  ]) {
    await test.step(scenario.name, async () => {
      response = validResponse()
      scenario.mutate()
      await expect(createNextSeasonRequestFromBrowser(page, scope)).resolves.toEqual({
        ok: false,
        name: 'ApiClientError',
        kind: 'invalid-response',
        message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      })
    })
  }
})

test('워크스페이스 생성의 자격 증명 응답이 비거나 필수 값을 잃으면 invalid-response로 분류한다', async ({ page }) => {
  const responses = [
    { status: 201, body: '{}' },
    { status: 201, body: 'null' },
    {
      status: 201,
      body: JSON.stringify({
        teamId: '11111111-1111-4111-8111-111111111111',
        seasonId: '22222222-2222-4222-8222-222222222222',
      }),
    },
    {
      status: 201,
      body: JSON.stringify({
        teamId: 'not-a-uuid',
        seasonId: '22222222-2222-4222-8222-222222222222',
        accessKey: 'new-access-key',
      }),
    },
  ]
  let responseIndex = 0
  await page.route('**/api/v1/workspaces', (route) => {
    const response = responses[responseIndex++]
    if (!response) throw new Error('예상하지 못한 워크스페이스 생성 요청입니다.')
    return route.fulfill({
      status: response.status,
      contentType: 'application/json',
      body: response.body,
    })
  })

  const expectedError = {
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  }
  for (let index = 0; index < responses.length; index += 1) {
    await expect(createWorkspaceRequestFromBrowser(
      page,
      `workspace-response-boundary-${String(index).padStart(32, '0')}`,
    )).resolves.toEqual(expectedError)
  }
  expect(responseIndex).toBe(responses.length)
})

test('접근 키 회전의 one-time credential 응답이 비면 invalid-response로 분류한다', async ({ page }) => {
  const scope = {
    teamId: '33333333-3333-4333-8333-333333333333',
    seasonId: '44444444-4444-4444-8444-444444444444',
    accessKey: 'pilot-access-key',
  }
  const responses = [
    { status: 200, body: '{}' },
    { status: 200, body: 'null' },
    { status: 200, body: JSON.stringify({ accessKey: '   ' }) },
  ]
  let responseIndex = 0
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/access-key/rotate`,
    (route) => {
      const response = responses[responseIndex++]
      if (!response) throw new Error('예상하지 못한 접근 키 회전 요청입니다.')
      return route.fulfill({
        status: response.status,
        contentType: 'application/json',
        body: response.body,
      })
    },
  )

  const expectedError = {
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  }
  for (let index = 0; index < responses.length; index += 1) {
    await expect(rotateAccessKeyRequestFromBrowser(
      page,
      scope,
      `rotation-response-boundary-${String(index).padStart(32, '0')}`,
    )).resolves.toEqual(expectedError)
  }
  expect(responseIndex).toBe(responses.length)
})

test('워크스페이스 성공 응답의 필수 shape가 없으면 복구 가능한 invalid-response로 수렴한다', async ({ page }) => {
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

test('워크스페이스 배열의 손상된 원소도 복구 가능한 invalid-response로 수렴한다', async ({ page }) => {
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
})

test('워크스페이스 일정은 ISO local time의 소수초를 보존한다', async ({ page }) => {
  const scope = {
    teamId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    seasonId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    accessKey: 'pilot-access-key',
  }
  const projection = workspaceProjection(scope)
  Reflect.set(projection.season, 'roundSchedule', {
      firstMeetingDate: '2026-07-01',
      meetingTime: '20:00:00.123456',
      recurrence: 'WEEKLY',
      generationLeadDays: 7,
      enabled: true,
      nextOccurrenceDate: '2026-07-08',
  })
  projection.seasons = [projection.season]
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(projection),
    }),
  )

  await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: true,
    value: projection,
  })
})

test('HTTP 오류는 계약 정보를 보존하고 손상된 오류 본문은 공용 값으로 대체한다', async ({ page }) => {
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
