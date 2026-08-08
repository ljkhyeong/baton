import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type {
  RoleHandoffStatus,
  RoleHandoffTransitionResponse,
  WorkspaceProjection,
} from '../../src/features/workspace/types'
import { makeProjection } from './support/workspaceApiHarness'

type DecoderName =
  | 'decodeAcceptRoleHandoffResponse'
  | 'decodeCancelRoleHandoffResponse'
  | 'decodePrepareRoleHandoffResponse'
  | 'decodeTransferRoleHandoffResponse'

type BrowserRequestResult =
  | { ok: true; value: unknown }
  | { ok: false; name: string; message: string; kind?: string }

const INVALID_RESPONSE_ERROR = {
  ok: false,
  name: 'ApiClientError',
  kind: 'invalid-response',
  message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
}

async function decodedResponseRequestFromBrowser(
  page: Page,
  path: string,
  decoderName: DecoderName,
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ requestPath, responseDecoderName }) => {
    const { apiRequest } = await import('/src/shared/api/client.ts')
    const responseDecoders = await import(
      '/src/features/workspace/workspaceProjectionDecoder.ts'
    )

    try {
      const value = await apiRequest<unknown>(requestPath, {
        decode: responseDecoders[responseDecoderName],
      })
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
  }, { requestPath: path, responseDecoderName: decoderName })
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

function acceptedRoleHandoffTransitionResponse(): RoleHandoffTransitionResponse {
  return {
    role: {
      id: '11111111-1111-4111-8111-111111111111',
      name: '문제 큐레이터',
      purpose: '문제 선정 기준을 유지합니다.',
      currentMemberId: '22222222-2222-4222-8222-222222222222',
      nextMemberId: null,
      assignmentStartDate: '2026-09-17',
      assignmentEndDate: null,
      responsibilities: ['문제 선정'],
      risk: null,
    },
    handoff: {
      id: '33333333-3333-4333-8333-333333333333',
      roleId: '11111111-1111-4111-8111-111111111111',
      fromMemberId: '44444444-4444-4444-8444-444444444444',
      toMemberId: '22222222-2222-4222-8222-222222222222',
      outgoingAssignmentStartDate: '2026-07-02',
      outgoingAssignmentEndDate: '2026-09-16',
      incomingAssignmentStartDate: '2026-09-17',
      incomingAssignmentEndDate: null,
      status: 'ACCEPTED',
      preparedAt: '2026-09-01T09:00:00Z',
      transferredAt: '2026-09-02T09:00:00Z',
      acceptedAt: '2026-09-03T09:00:00Z',
      cancelledAt: null,
      transferredByMemberId: '44444444-4444-4444-8444-444444444444',
      acceptedByMemberId: '22222222-2222-4222-8222-222222222222',
      cancelledByMemberId: null,
      activeItemCount: 1,
      incompleteItemCount: 0,
      resourceCount: 1,
      warningAcknowledged: false,
    },
  }
}

function roleHandoffTransitionResponse(
  status: RoleHandoffStatus,
): RoleHandoffTransitionResponse {
  const response = structuredClone(acceptedRoleHandoffTransitionResponse())
  const { handoff, role } = response

  handoff.status = status
  if (status === 'PREPARING' || status === 'TRANSFERRED') {
    role.currentMemberId = handoff.fromMemberId
    role.nextMemberId = handoff.toMemberId
  } else {
    role.nextMemberId = null
  }

  if (status === 'PREPARING') {
    handoff.transferredAt = null
    handoff.transferredByMemberId = null
    handoff.acceptedAt = null
    handoff.acceptedByMemberId = null
    handoff.cancelledAt = null
    handoff.cancelledByMemberId = null
    handoff.activeItemCount = null
    handoff.incompleteItemCount = null
    handoff.resourceCount = null
  } else if (status === 'TRANSFERRED') {
    handoff.acceptedAt = null
    handoff.acceptedByMemberId = null
    handoff.cancelledAt = null
    handoff.cancelledByMemberId = null
  } else if (status === 'CANCELLED') {
    role.currentMemberId = handoff.fromMemberId
    handoff.acceptedAt = null
    handoff.acceptedByMemberId = null
    handoff.cancelledAt = '2026-09-03T10:00:00Z'
    handoff.cancelledByMemberId = handoff.fromMemberId
  }

  return response
}

function scopedProjection(scope: { teamId: string; seasonId: string }): WorkspaceProjection {
  const projection = makeProjection()
  const roundSchedule = {
    enabled: true,
    firstMeetingDate: '2026-07-02',
    generationLeadDays: 7,
    meetingTime: '20:00:00',
    nextOccurrenceDate: '2026-07-09',
    recurrence: 'WEEKLY' as const,
  }
  projection.team = { ...projection.team, id: scope.teamId }
  projection.season = {
    ...projection.season,
    id: scope.seasonId,
    roundSchedule,
  }
  projection.seasons = [structuredClone(projection.season)]
  return projection
}

function populatedScopedProjection(
  scope: { teamId: string; seasonId: string },
): WorkspaceProjection {
  const projection = scopedProjection(scope)
  const transition = acceptedRoleHandoffTransitionResponse()
  projection.roles.push(transition.role)
  projection.roleHandoffs = [transition.handoff]
  projection.resources = [{
    id: '77777777-7777-4777-8777-777777777777',
    roleId: transition.role.id,
    title: '역할 운영 문서',
    url: 'https://docs.example.com/role-guide',
    description: '역할 수행 기준입니다.',
    createdAt: '2026-07-04T03:00:00Z',
  }]
  return projection
}

test.beforeEach(async ({ page }, testInfo) => {
  test.skip(
    testInfo.project.name === 'mobile',
    '응답 decoder 계약은 데스크톱 Chromium에서 한 번만 검증합니다.',
  )
  await page.goto('/')
})

test('@smoke 역할 바통 endpoint decoder는 멱등 재생으로 도달 가능한 현재 상태를 허용한다', async ({ page }) => {
  const scenarios: Array<{
    decoder: DecoderName
    name: string
    status: RoleHandoffStatus
  }> = [
    { name: '준비 응답의 준비 상태', decoder: 'decodePrepareRoleHandoffResponse', status: 'PREPARING' },
    { name: '준비 재생의 전달 상태', decoder: 'decodePrepareRoleHandoffResponse', status: 'TRANSFERRED' },
    { name: '준비 재생의 수락 상태', decoder: 'decodePrepareRoleHandoffResponse', status: 'ACCEPTED' },
    { name: '준비 재생의 취소 상태', decoder: 'decodePrepareRoleHandoffResponse', status: 'CANCELLED' },
    { name: '전달 응답의 전달 상태', decoder: 'decodeTransferRoleHandoffResponse', status: 'TRANSFERRED' },
    { name: '전달 재생의 수락 상태', decoder: 'decodeTransferRoleHandoffResponse', status: 'ACCEPTED' },
    { name: '전달 재생의 취소 상태', decoder: 'decodeTransferRoleHandoffResponse', status: 'CANCELLED' },
    { name: '수락 응답의 수락 상태', decoder: 'decodeAcceptRoleHandoffResponse', status: 'ACCEPTED' },
    { name: '취소 응답의 취소 상태', decoder: 'decodeCancelRoleHandoffResponse', status: 'CANCELLED' },
  ]

  for (const [index, scenario] of scenarios.entries()) {
    await test.step(scenario.name, async () => {
      const path = `/response-decoder/role-handoff-replay-${index}`
      const response = roleHandoffTransitionResponse(scenario.status)
      await page.route(`**${path}`, (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      }))

      await expect(decodedResponseRequestFromBrowser(
        page,
        path,
        scenario.decoder,
      )).resolves.toEqual({ ok: true, value: response })
    })
  }
})

test('@smoke 역할 바통 endpoint decoder는 재생 계약으로 도달할 수 없는 상태를 거절한다', async ({ page }) => {
  const scenarios: Array<{
    decoder: DecoderName
    name: string
    status: RoleHandoffStatus
  }> = [
    { name: '전달 응답의 준비 상태', decoder: 'decodeTransferRoleHandoffResponse', status: 'PREPARING' },
    { name: '수락 응답의 준비 상태', decoder: 'decodeAcceptRoleHandoffResponse', status: 'PREPARING' },
    { name: '수락 응답의 전달 상태', decoder: 'decodeAcceptRoleHandoffResponse', status: 'TRANSFERRED' },
    { name: '수락 응답의 취소 상태', decoder: 'decodeAcceptRoleHandoffResponse', status: 'CANCELLED' },
    { name: '취소 응답의 준비 상태', decoder: 'decodeCancelRoleHandoffResponse', status: 'PREPARING' },
    { name: '취소 응답의 전달 상태', decoder: 'decodeCancelRoleHandoffResponse', status: 'TRANSFERRED' },
    { name: '취소 응답의 수락 상태', decoder: 'decodeCancelRoleHandoffResponse', status: 'ACCEPTED' },
  ]

  for (const [index, scenario] of scenarios.entries()) {
    await test.step(scenario.name, async () => {
      const path = `/response-decoder/role-handoff-impossible-${index}`
      await page.route(`**${path}`, (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(roleHandoffTransitionResponse(scenario.status)),
      }))

      await expect(decodedResponseRequestFromBrowser(
        page,
        path,
        scenario.decoder,
      )).resolves.toEqual(INVALID_RESPONSE_ERROR)
    })
  }
})

test('@smoke 워크스페이스 응답은 요청 scope와 동일한 현재 시즌 snapshot을 가져야 한다', async ({ page }) => {
  const scope = {
    teamId: '45454545-4545-4545-8545-454545454545',
    seasonId: '56565656-5656-4656-8656-565656565656',
    accessKey: 'pilot-access-key',
  }
  let response = scopedProjection(scope)
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    }),
  )

  await test.step('요청 scope와 현재 시즌 snapshot이 일치하는 응답', async () => {
    await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
      ok: true,
      value: response,
    })
  })

  const scenarios: Array<{
    name: string
    mutate: (projection: WorkspaceProjection) => void
  }> = [
    {
      name: '다른 팀 응답',
      mutate: (projection) => {
        projection.team.id = '67676767-6767-4767-8676-676767676767'
      },
    },
    {
      name: '다른 현재 시즌 응답',
      mutate: (projection) => {
        projection.season.id = '78787878-7878-4787-8787-787878787878'
      },
    },
    {
      name: '현재 시즌이 빠진 시즌 목록',
      mutate: (projection) => {
        projection.seasons = [{
          ...projection.season,
          id: '89898989-8989-4989-8989-898989898989',
        }]
      },
    },
    {
      name: '현재 시즌이 중복된 시즌 목록',
      mutate: (projection) => {
        projection.seasons = [projection.season, { ...projection.season }]
      },
    },
    {
      name: '현재 시즌과 이름이 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.name = '2026 가을 시즌'
      },
    },
    {
      name: '현재 시즌과 시작일이 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.startDate = '2026-07-03'
      },
    },
    {
      name: '현재 시즌과 종료일이 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.endDate = '2026-09-16'
      },
    },
    {
      name: '현재 시즌과 시간대가 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.timeZone = 'Europe/London'
      },
    },
    {
      name: '현재 시즌과 종료 시각이 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.endedAt = '2026-09-17T09:00:00Z'
      },
    },
    {
      name: '현재 시즌과 이전 시즌이 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.previousSeasonId = '90919191-9191-4191-8191-919191919191'
      },
    },
    {
      name: '현재 시즌과 자동 회차 일정이 상충하는 시즌 요약',
      mutate: (projection) => {
        projection.seasons[0]!.roundSchedule!.meetingTime = '21:00:00'
      },
    },
  ]

  for (const scenario of scenarios) {
    await test.step(scenario.name, async () => {
      response = scopedProjection(scope)
      scenario.mutate(response)
      await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual(
        INVALID_RESPONSE_ERROR,
      )
    })
  }
})

test('@smoke 워크스페이스의 OpenAPI date-time 필드는 유효한 UTC instant여야 한다', async ({ page }) => {
  const scope = {
    teamId: '90909090-9090-4090-8090-909090909090',
    seasonId: '91919191-9191-4191-8191-919191919191',
    accessKey: 'pilot-access-key',
  }
  const invalidInstant = '2026-02-30T09:00:00Z'
  let response = populatedScopedProjection(scope)
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    }),
  )
  const scenarios: Array<{
    name: string
    mutate: (projection: WorkspaceProjection) => void
  }> = [
    {
      name: '시즌 종료 시각',
      mutate: (projection) => {
        projection.season.endedAt = invalidInstant
        projection.seasons[0]!.endedAt = invalidInstant
      },
    },
    {
      name: '구성원 활동 종료 시각',
      mutate: (projection) => {
        projection.members[0]!.deactivatedAt = invalidInstant
      },
    },
    {
      name: '결정 생성 시각',
      mutate: (projection) => {
        projection.decisions[0]!.createdAt = invalidInstant
      },
    },
    {
      name: '결정 보관 시각',
      mutate: (projection) => {
        projection.decisions[0]!.archivedAt = invalidInstant
      },
    },
    {
      name: '바통 항목 생성 시각',
      mutate: (projection) => {
        projection.handoffItems[0]!.createdAt = invalidInstant
      },
    },
    {
      name: '바통 항목 보관 시각',
      mutate: (projection) => {
        projection.handoffItems[0]!.archivedAt = invalidInstant
      },
    },
    {
      name: '역할 자료 생성 시각',
      mutate: (projection) => {
        projection.resources[0]!.createdAt = invalidInstant
      },
    },
    {
      name: '역할 바통 준비 시각',
      mutate: (projection) => {
        projection.roleHandoffs[0]!.preparedAt = invalidInstant
      },
    },
    {
      name: '역할 바통 전달 시각',
      mutate: (projection) => {
        projection.roleHandoffs[0]!.transferredAt = invalidInstant
      },
    },
    {
      name: '역할 바통 수락 시각',
      mutate: (projection) => {
        projection.roleHandoffs[0]!.acceptedAt = invalidInstant
      },
    },
    {
      name: '역할 바통 취소 시각',
      mutate: (projection) => {
        projection.roleHandoffs[0]!.cancelledAt = invalidInstant
      },
    },
    {
      name: '루틴 보관 시각',
      mutate: (projection) => {
        projection.routines[0]!.archivedAt = invalidInstant
      },
    },
    {
      name: '회차 보관 시각',
      mutate: (projection) => {
        projection.rounds[0]!.archivedAt = invalidInstant
      },
    },
    {
      name: '자동 회차 예정 시각',
      mutate: (projection) => {
        projection.rounds[0]!.scheduledAt = invalidInstant
      },
    },
    {
      name: '루틴 실행 마감 시각',
      mutate: (projection) => {
        projection.rounds[0]!.routineExecutions[0]!.deadlineAt = invalidInstant
      },
    },
  ]

  for (const scenario of scenarios) {
    await test.step(scenario.name, async () => {
      response = populatedScopedProjection(scope)
      scenario.mutate(response)
      await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual(
        INVALID_RESPONSE_ERROR,
      )
    })
  }
})

test('@smoke 루틴 마감 오프셋과 시각은 함께 설정하고 허용 범위를 지켜야 한다', async ({ page }) => {
  const scope = {
    teamId: '92929292-9292-4292-8292-929292929292',
    seasonId: '93939393-9393-4393-8393-939393939393',
    accessKey: 'pilot-access-key',
  }
  let response = populatedScopedProjection(scope)
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    }),
  )
  const invalidScenarios: Array<{
    name: string
    mutate: (projection: WorkspaceProjection) => void
  }> = [
    {
      name: '시각만 존재',
      mutate: (projection) => {
        projection.routines[0]!.deadlineDayOffset = null
      },
    },
    {
      name: '오프셋만 존재',
      mutate: (projection) => {
        projection.routines[0]!.deadlineTime = null
      },
    },
    {
      name: '정수가 아닌 오프셋',
      mutate: (projection) => {
        projection.routines[0]!.deadlineDayOffset = 0.5
      },
    },
    {
      name: '최솟값보다 작은 오프셋',
      mutate: (projection) => {
        projection.routines[0]!.deadlineDayOffset = -31
      },
    },
    {
      name: '최댓값보다 큰 오프셋',
      mutate: (projection) => {
        projection.routines[0]!.deadlineDayOffset = 31
      },
    },
    {
      name: '유효하지 않은 시각',
      mutate: (projection) => {
        projection.routines[0]!.deadlineTime = '24:00:00'
      },
    },
  ]

  for (const scenario of invalidScenarios) {
    await test.step(scenario.name, async () => {
      response = populatedScopedProjection(scope)
      scenario.mutate(response)
      await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual(
        INVALID_RESPONSE_ERROR,
      )
    })
  }

  await test.step('-30과 30 경계 오프셋', async () => {
    response = populatedScopedProjection(scope)
    response.routines[0]!.deadlineDayOffset = -30
    response.routines[0]!.deadlineTime = '00:00:00.123456789'
    response.routines[1]!.deadlineDayOffset = 30
    response.routines[1]!.deadlineTime = '23:59:59.999999999'
    await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
      ok: true,
      value: response,
    })
  })

  await test.step('nullable 쌍', async () => {
    response = populatedScopedProjection(scope)
    response.routines[0]!.deadlineDayOffset = null
    response.routines[0]!.deadlineTime = null
    await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
      ok: true,
      value: response,
    })
  })
})
