import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type {
  CreateNextSeasonResponse,
  RoleHandoffTransitionResponse,
  WorkspaceProjection,
} from '../../src/features/workspace/types'
import { makeProjection } from './support/workspaceApiHarness'

type DecoderName =
  | 'decodeAcceptRoleHandoffResponse'
  | 'decodeCancelRoleHandoffResponse'
  | 'decodeCreateNextSeasonResponse'
  | 'decodePrepareRoleHandoffResponse'
  | 'decodeTransferRoleHandoffResponse'

type DecoderScope =
  | { kind: 'next-season'; sourceSeasonId: string }
  | { kind: 'role-handoff'; roleId: string; handoffId: string }

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
  decoderScope: DecoderScope,
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ requestPath, responseDecoderName, scope }) => {
    const { apiRequest } = await import('/src/shared/api/client.ts')
    const responseDecoders = await import(
      '/src/features/workspace/workspaceProjectionDecoder.ts'
    )

    const decode = (value: unknown) => {
      if (responseDecoderName === 'decodeCreateNextSeasonResponse') {
        if (scope.kind !== 'next-season') throw new TypeError('Missing next-season scope.')
        return responseDecoders.decodeCreateNextSeasonResponse(value, scope.sourceSeasonId)
      }
      if (scope.kind !== 'role-handoff') throw new TypeError('Missing role-handoff scope.')
      if (responseDecoderName === 'decodePrepareRoleHandoffResponse') {
        return responseDecoders.decodePrepareRoleHandoffResponse(value, scope.roleId)
      }
      if (responseDecoderName === 'decodeTransferRoleHandoffResponse') {
        return responseDecoders.decodeTransferRoleHandoffResponse(value, scope.roleId, scope.handoffId)
      }
      if (responseDecoderName === 'decodeAcceptRoleHandoffResponse') {
        return responseDecoders.decodeAcceptRoleHandoffResponse(value, scope.roleId, scope.handoffId)
      }
      return responseDecoders.decodeCancelRoleHandoffResponse(value, scope.roleId, scope.handoffId)
    }

    try {
      const value = await apiRequest<unknown>(requestPath, {
        decode,
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
  }, { requestPath: path, responseDecoderName: decoderName, scope: decoderScope })
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

function scopedProjection(scope: { teamId: string; seasonId: string }): WorkspaceProjection {
  const projection = makeProjection()
  projection.team = { ...projection.team, id: scope.teamId }
  projection.season = { ...projection.season, id: scope.seasonId }
  projection.seasons = [structuredClone(projection.season)]
  return projection
}

function addContinuitySignal(
  projection: WorkspaceProjection,
  overrides: Partial<WorkspaceProjection['continuitySignals'][number]> = {},
) {
  projection.continuitySignals.push({
    type: 'ROLE_UNASSIGNED',
    severity: 'WARNING',
    roleId: projection.roles[0]!.id,
    routineId: null,
    title: '현재 담당자가 필요해요',
    reason: '역할에 현재 담당자가 없습니다.',
    recommendedAction: '활동 중인 구성원을 담당자로 지정하세요.',
    relevantDate: null,
    ...overrides,
  })
}

function domainInconsistentRoleHandoffResponse(): RoleHandoffTransitionResponse {
  return {
    role: {
      id: '11111111-1111-4111-8111-111111111111',
      name: '문제 큐레이터',
      purpose: '문제 선정 기준을 유지합니다.',
      currentMemberId: '22222222-2222-4222-8222-222222222222',
      nextMemberId: null,
      assignmentStartDate: '2026-09-17',
      assignmentEndDate: '2026-07-02',
      responsibilities: ['문제 선정'],
      risk: null,
    },
    handoff: {
      id: '33333333-3333-4333-8333-333333333333',
      roleId: '11111111-1111-4111-8111-111111111111',
      fromMemberId: '55555555-5555-4555-8555-555555555555',
      toMemberId: '55555555-5555-4555-8555-555555555555',
      outgoingAssignmentStartDate: '2026-09-17',
      outgoingAssignmentEndDate: '2026-07-02',
      incomingAssignmentStartDate: '2026-09-18',
      incomingAssignmentEndDate: '2026-07-03',
      status: 'PREPARING',
      preparedAt: '2026-09-03T09:00:00Z',
      transferredAt: null,
      acceptedAt: '2026-09-01T09:00:00Z',
      cancelledAt: '2026-09-02T09:00:00Z',
      transferredByMemberId: null,
      acceptedByMemberId: '66666666-6666-4666-8666-666666666666',
      cancelledByMemberId: '77777777-7777-4777-8777-777777777777',
      activeItemCount: -1.5,
      incompleteItemCount: 2.5,
      resourceCount: -3,
      warningAcknowledged: true,
    },
  }
}

function nextSeasonResponse(): CreateNextSeasonResponse {
  const { season: template } = makeProjection()
  return {
    sourceSeason: {
      ...template,
      id: '81818181-8181-4181-8181-818181818181',
      startDate: '2026-07-02',
      endDate: '2026-09-17',
      endedAt: '2026-09-18T09:00:00Z',
      timeZone: 'Asia/Seoul',
    },
    season: {
      ...template,
      id: '82828282-8282-4282-8282-828282828282',
      previousSeasonId: '81818181-8181-4181-8181-818181818181',
      startDate: '2026-09-18',
      endDate: '2026-12-31',
      timeZone: 'Asia/Seoul',
      roundSchedule: {
        enabled: true,
        firstMeetingDate: '2026-09-18',
        generationLeadDays: -1.5,
        meetingTime: '20:00:00',
        nextOccurrenceDate: '2026-09-18',
        recurrence: 'WEEKLY',
      },
    },
    copiedRoles: [{
      roleId: '84848484-8484-4484-8484-848484848484',
      sourceRoleId: '85858585-8585-4585-8585-858585858585',
    }],
    copiedRoutines: [{
      routineId: '86868686-8686-4686-8686-868686868686',
      sourceRoutineId: '87878787-8787-4787-8787-878787878787',
    }],
  }
}

test.beforeEach(async ({ page }, testInfo) => {
  test.skip(
    testInfo.project.name === 'mobile',
    '응답 decoder 계약은 데스크톱 Chromium에서 한 번만 검증합니다.',
  )
  await page.goto('/')
})

test('@smoke 역할 바통 endpoint decoder는 서버가 반환한 도메인 상태를 재계산하지 않는다', async ({ page }) => {
  const response = domainInconsistentRoleHandoffResponse()
  const decoders: DecoderName[] = [
    'decodePrepareRoleHandoffResponse',
    'decodeTransferRoleHandoffResponse',
    'decodeAcceptRoleHandoffResponse',
    'decodeCancelRoleHandoffResponse',
  ]

  for (const [index, decoder] of decoders.entries()) {
    await test.step(decoder, async () => {
      const path = `/response-decoder/role-handoff-${index}`
      await page.route(`**${path}`, (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      }))

      await expect(decodedResponseRequestFromBrowser(page, path, decoder, {
        kind: 'role-handoff',
        roleId: response.role.id,
        handoffId: response.handoff.id,
      })).resolves.toEqual({
        ok: true,
        value: response,
      })
    })
  }
})

test('@smoke 다음 시즌 decoder는 원본과 후속 시즌 계보만 검증한다', async ({ page }) => {
  const path = '/response-decoder/next-season'
  let response = nextSeasonResponse()
  Reflect.set(response, 'futureServerField', 'ignored')
  await page.route(`**${path}`, (route) => route.fulfill({
    status: 201,
    contentType: 'application/json',
    body: JSON.stringify(response),
  }))

  await expect(decodedResponseRequestFromBrowser(
    page,
    path,
    'decodeCreateNextSeasonResponse',
    { kind: 'next-season', sourceSeasonId: response.sourceSeason.id },
  )).resolves.toEqual({ ok: true, value: response })

  response = nextSeasonResponse()
  response.season.previousSeasonId = '83838383-8383-4383-8383-838383838383'
  await expect(decodedResponseRequestFromBrowser(
    page,
    path,
    'decodeCreateNextSeasonResponse',
    { kind: 'next-season', sourceSeasonId: response.sourceSeason.id },
  )).resolves.toEqual(INVALID_RESPONSE_ERROR)
})

test('@smoke 워크스페이스 응답은 요청한 최상위 팀과 현재 시즌 scope를 유지해야 한다', async ({ page }) => {
  const scope = {
    teamId: 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA',
    seasonId: 'BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB',
    accessKey: 'pilot-access-key',
  }
  let response = scopedProjection({
    teamId: scope.teamId.toLowerCase(),
    seasonId: scope.seasonId.toLowerCase(),
  })
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    }),
  )

  await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: true,
    value: response,
  })

  for (const scenario of [
    {
      name: '다른 팀',
      mutate: (projection: WorkspaceProjection) => {
        projection.team.id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
      },
    },
    {
      name: '다른 현재 시즌',
      mutate: (projection: WorkspaceProjection) => {
        projection.season.id = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
      },
    },
  ]) {
    await test.step(scenario.name, async () => {
      response = scopedProjection({
        teamId: scope.teamId.toLowerCase(),
        seasonId: scope.seasonId.toLowerCase(),
      })
      scenario.mutate(response)
      await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual(
        INVALID_RESPONSE_ERROR,
      )
    })
  }

  for (const scenario of [
    {
      name: '현재 시즌 목록 누락',
      mutate: (projection: WorkspaceProjection) => {
        projection.seasons = []
      },
    },
    {
      name: '현재 시즌 목록 중복',
      mutate: (projection: WorkspaceProjection) => {
        projection.seasons.push(structuredClone(projection.seasons[0]!))
      },
    },
  ]) {
    await test.step(scenario.name, async () => {
      response = scopedProjection({
        teamId: scope.teamId.toLowerCase(),
        seasonId: scope.seasonId.toLowerCase(),
      })
      scenario.mutate(response)
      await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual(
        INVALID_RESPONSE_ERROR,
      )
    })
  }

  response = scopedProjection({
    teamId: scope.teamId.toLowerCase(),
    seasonId: scope.seasonId.toLowerCase(),
  })
  response.seasons[0]!.name = '서버가 반환한 별도 요약 이름'
  await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: true,
    value: response,
  })

})

test('@smoke 연속성 신호는 날짜와 동작 대상의 포함 관계를 유지해야 한다', async ({ page }) => {
  const scope = {
    teamId: '94949494-9494-4494-8494-949494949494',
    seasonId: '95959595-9595-4595-8595-959595959595',
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

  const scenarios: Array<{
    name: string
    mutate: (projection: WorkspaceProjection) => void
  }> = [
    {
      name: '존재하지 않는 관련 날짜',
      mutate: (projection) => addContinuitySignal(projection, {
        relevantDate: '2026-02-30',
      }),
    },
    {
      name: '현재 프로젝션에 없는 역할',
      mutate: (projection) => addContinuitySignal(projection, {
        roleId: '96969696-9696-4696-8696-969696969696',
      }),
    },
    {
      name: '다른 역할이 소유한 반복 루틴',
      mutate: (projection) => {
        const otherRoleId = '97979797-9797-4797-8797-979797979797'
        projection.roles.push({
          ...structuredClone(projection.roles[0]!),
          id: otherRoleId,
          name: '회고 진행자',
        })
        addContinuitySignal(projection, {
          type: 'ROUTINE_REPEATEDLY_OVERDUE',
          roleId: otherRoleId,
          routineId: projection.routines[0]!.id,
        })
      },
    },
    {
      name: '반복 지연이 아닌 신호의 루틴 식별자',
      mutate: (projection) => addContinuitySignal(projection, {
        routineId: projection.routines[0]!.id,
      }),
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

test('@smoke 워크스페이스의 UTC instant와 달력 날짜 형식은 렌더 전에 검증한다', async ({ page }) => {
  const scope = {
    teamId: '90909090-9090-4090-8090-909090909090',
    seasonId: '91919191-9191-4191-8191-919191919191',
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

  const scenarios: Array<{
    name: string
    mutate: (projection: WorkspaceProjection) => void
  }> = [
    {
      name: '존재하지 않는 시즌 날짜',
      mutate: (projection) => {
        projection.season.startDate = '2026-02-30'
      },
    },
    {
      name: '존재하지 않는 시즌 종료 instant',
      mutate: (projection) => {
        projection.season.endedAt = '2026-02-30T09:00:00Z'
      },
    },
    {
      name: '존재하지 않는 결정 생성 instant',
      mutate: (projection) => {
        projection.decisions[0]!.createdAt = '2026-02-30T09:00:00Z'
      },
    },
    {
      name: '존재하지 않는 실행 마감 instant',
      mutate: (projection) => {
        projection.rounds[0]!.routineExecutions[0]!.deadlineAt = '2026-02-30T09:00:00Z'
      },
    },
    {
      name: '필수 nullable 루틴 마감 필드 누락',
      mutate: (projection) => {
        Reflect.deleteProperty(projection.routines[0]!, 'deadlineDayOffset')
      },
    },
    {
      name: '시즌 시간대의 잘못된 primitive type',
      mutate: (projection) => {
        Reflect.set(projection.season, 'timeZone', 9)
      },
    },
    {
      name: '지원하지 않는 IANA 시간대',
      mutate: (projection) => {
        projection.season.timeZone = 'server-owned-time-zone'
      },
    },
    {
      name: '시작일보다 이른 시즌 종료일',
      mutate: (projection) => {
        projection.season.startDate = '2026-10-01'
        projection.season.endDate = '2026-09-30'
      },
    },
    {
      name: '루틴 마감 오프셋의 잘못된 primitive type',
      mutate: (projection) => {
        Reflect.set(projection.routines[0]!, 'deadlineDayOffset', '1')
      },
    },
    {
      name: '루틴 마감의 잘못된 local time',
      mutate: (projection) => {
        projection.routines[0]!.deadlineTime = '24:00:00'
      },
    },
    {
      name: '회차의 알 수 없는 enum',
      mutate: (projection) => {
        Reflect.set(projection.rounds[0]!, 'origin', 'SERVER_ONLY')
      },
    },
    {
      name: '다른 부모 회차를 가리키는 루틴 실행',
      mutate: (projection) => {
        projection.rounds[0]!.routineExecutions[0]!.roundId = projection.rounds[1]!.id
      },
    },
    {
      name: '역할의 잘못된 UUID',
      mutate: (projection) => {
        projection.roles[0]!.id = 'not-a-uuid'
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

test('@smoke decoder는 서버 상태 머신을 재계산하지 않는다', async ({ page }) => {
  const scope = {
    teamId: '92929292-9292-4292-8292-929292929292',
    seasonId: '93939393-9393-4393-8393-939393939393',
    accessKey: 'pilot-access-key',
  }
  const response = scopedProjection(scope)
  response.season.roundSchedule = {
    enabled: true,
    firstMeetingDate: '2026-07-02',
    generationLeadDays: -1.5,
    meetingTime: '20:00:00',
    nextOccurrenceDate: '2026-07-02',
    recurrence: 'WEEKLY',
  }
  response.roles[0]!.assignmentStartDate = '2026-09-17'
  response.roles[0]!.assignmentEndDate = '2026-07-02'
  response.routines[0]!.deadlineDayOffset = 31.5
  response.routines[0]!.deadlineTime = null
  response.rounds[0]!.origin = 'AUTOMATIC'
  response.rounds[0]!.scheduledOccurrenceDate = null
  response.rounds[0]!.scheduledAt = null
  response.rounds[0]!.routineExecutions[0]!.status = 'DONE'
  response.rounds[0]!.routineExecutions[0]!.timingStatus = 'OVERDUE'
  const handoffTransition = domainInconsistentRoleHandoffResponse()
  response.roles.push(handoffTransition.role)
  response.roleHandoffs.push(handoffTransition.handoff)
  Reflect.set(response, 'futureServerField', 'ignored')
  await page.route(
    `**/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/workspace`,
    (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    }),
  )

  await expect(workspaceRequestFromBrowser(page, scope)).resolves.toEqual({
    ok: true,
    value: response,
  })
})
