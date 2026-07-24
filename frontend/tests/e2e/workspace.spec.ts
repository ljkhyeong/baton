import { expect, test } from '@playwright/test'
import type { Locator, Page, Route } from '@playwright/test'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  CreateWorkspaceRequest,
  HandoffItem,
  Role,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
  UpdateRoleRequest,
  UpdateRoleResourceRequest,
  UpdateRoutineRequest,
  WorkspaceProjection,
} from '../../src/features/workspace/types'
import type { ContentCreationOperation } from '../../src/features/workspace/pendingContentCreation'

const fixtureUuid = (sequence: number) => `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`

const TEAM_ID = fixtureUuid(1)
const SEASON_ID = fixtureUuid(2)
const MEMBER_ONE_ID = fixtureUuid(11)
const MEMBER_TWO_ID = fixtureUuid(12)
const MEMBER_THREE_ID = fixtureUuid(13)
const ROLE_ID = fixtureUuid(21)
const CREATED_ROLE_ID = fixtureUuid(22)
const ROUTINE_ID = fixtureUuid(31)
const SECOND_ROUTINE_ID = fixtureUuid(32)
const CREATED_ROUTINE_ID = fixtureUuid(33)
const DECISION_ID = fixtureUuid(41)
const CREATED_DECISION_ID = fixtureUuid(42)
const HANDOFF_ONE_ID = fixtureUuid(51)
const HANDOFF_TWO_ID = fixtureUuid(52)
const CREATED_HANDOFF_ID = fixtureUuid(53)
const CREATED_ROLE_RESOURCE_ID = fixtureUuid(54)
const ROUND_ONE_ID = fixtureUuid(61)
const ROUND_TWO_ID = fixtureUuid(62)
const CREATED_ROUND_ID = fixtureUuid(63)
const ROUND_ONE_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(71)
const ROUND_ONE_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(72)
const ROUND_TWO_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(73)
const ROUND_TWO_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(74)
const CREATED_ROUND_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(75)
const CREATED_ROUND_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(76)
const CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID = fixtureUuid(77)
const ACCESS_KEY = 'e2e-access-key'
const ROTATED_ACCESS_KEY = 'e2e-rotated-access-key'
const WORKSPACE_PATH = `/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const SCOPE_PATH = `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const PENDING_CREATION_STORAGE_PREFIX = 'baton-pending-workspace-creation:v3:'
const PENDING_CONTENT_CREATION_STORAGE_PREFIX = 'baton-pending-content-creation:v1:'
const LEGACY_PENDING_CREATION_STORAGE_KEY = 'baton-pending-workspace-creation:v1'

type RecordedCall = {
  method: string
  path: string
  headers: Record<string, string>
  body?: unknown
}

type ApiHarness = {
  calls: RecordedCall[]
  projection: () => WorkspaceProjection
  attachPage: (page: Page) => Promise<void>
  failNextWorkspaceCreation: () => void
  commitNextWorkspaceCreationThenTimeout: () => void
  expireNextWorkspaceCreationReplay: () => void
  commitNextAccessKeyRotationThenTimeout: () => void
  conflictNextAccessKeyRotation: () => void
  rotateAccessKeyFromAnotherDevice: () => void
  expireNextAccessKeyRotationReplay: () => void
  expireAccessKeyRotationHistory: () => void
  commitNextContentCreationThenTimeout: (operation: ContentCreationOperation) => void
  rejectNextContentCreationAsReused: (operation: ContentCreationOperation) => void
  failNextWorkspaceGet: () => void
  holdNextRoleUpdate: () => void
  releaseRoleUpdate: () => void
  holdNextRoutineUpdate: () => void
  releaseRoutineUpdate: () => void
  conflictNextRoleResourceUpdate: (resource: RoleResource) => void
  failNextRoutineCompletion: () => void
  holdNextHandoffCompletion: () => void
  releaseHandoffCompletion: () => void
}

function makeProjection(): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: '알고리즘 한 바퀴' },
    season: { id: SEASON_ID, name: '2026 여름 시즌', startDate: '2026-07-02', endDate: '2026-09-17' },
    members: [
      { id: MEMBER_ONE_ID, name: '박민서', initials: '민', tone: '#d9e4da' },
      { id: MEMBER_TWO_ID, name: '김준호', initials: '준', tone: '#f1d6cc' },
      { id: MEMBER_THREE_ID, name: '최유진', initials: '유', tone: '#d8dfee' },
    ],
    roles: [
      {
        id: ROLE_ID,
        name: '문제 큐레이터',
        purpose: '이번 주 학습 목표에 맞는 문제를 선정합니다.',
        currentMemberId: MEMBER_ONE_ID,
        nextMemberId: MEMBER_TWO_ID,
        assignmentStartDate: '2026-07-02',
        assignmentEndDate: '2026-09-17',
        responsibilities: ['문제 5개 선정', '난이도 균형 확인'],
        risk: '문제 선정 기준이 개인 메모에만 있어요.',
      },
    ],
    routines: [
      {
        id: ROUTINE_ID,
        title: '문제 5개 선정',
        phase: 'BEFORE',
        dueLabel: '수요일 18:00',
        ownerRoleId: ROLE_ID,
        detail: '그래프 2개 · DP 2개 · 구현 1개',
      },
      {
        id: SECOND_ROUTINE_ID,
        title: '풀이 노트 정리',
        phase: 'AFTER',
        dueLabel: '금요일 21:00',
        ownerRoleId: ROLE_ID,
        detail: '이번 회차의 핵심 풀이를 한 문단으로 남깁니다.',
      },
    ],
    rounds: [
      {
        id: ROUND_TWO_ID,
        name: '2회차',
        meetingDate: '2026-07-17',
        routineExecutions: [
          {
            id: ROUND_TWO_ROUTINE_ONE_EXECUTION_ID,
            roundId: ROUND_TWO_ID,
            routineId: ROUTINE_ID,
            title: '문제 5개 선정',
            phase: 'BEFORE',
            dueLabel: '수요일 18:00',
            ownerRoleId: ROLE_ID,
            status: 'DONE',
            detail: '그래프 2개 · DP 2개 · 구현 1개',
          },
          {
            id: ROUND_TWO_ROUTINE_TWO_EXECUTION_ID,
            roundId: ROUND_TWO_ID,
            routineId: SECOND_ROUTINE_ID,
            title: '풀이 노트 정리',
            phase: 'AFTER',
            dueLabel: '금요일 21:00',
            ownerRoleId: ROLE_ID,
            status: 'WAITING',
            detail: '이번 회차의 핵심 풀이를 한 문단으로 남깁니다.',
          },
        ],
      },
      {
        id: ROUND_ONE_ID,
        name: '1회차',
        meetingDate: '2026-07-10',
        routineExecutions: [
          {
            id: ROUND_ONE_ROUTINE_ONE_EXECUTION_ID,
            roundId: ROUND_ONE_ID,
            routineId: ROUTINE_ID,
            title: '문제 5개 선정',
            phase: 'BEFORE',
            dueLabel: '수요일 18:00',
            ownerRoleId: ROLE_ID,
            status: 'WAITING',
            detail: '그래프 2개 · DP 2개 · 구현 1개',
          },
          {
            id: ROUND_ONE_ROUTINE_TWO_EXECUTION_ID,
            roundId: ROUND_ONE_ID,
            routineId: SECOND_ROUTINE_ID,
            title: '풀이 노트 정리',
            phase: 'AFTER',
            dueLabel: '금요일 21:00',
            ownerRoleId: ROLE_ID,
            status: 'WAITING',
            detail: '이번 회차의 핵심 풀이를 한 문단으로 남깁니다.',
          },
        ],
      },
    ],
    decisions: [
      {
        id: DECISION_ID,
        title: '한 회차의 문제 수를 5개로 정한다',
        reason: '풀이를 비교하는 시간을 확보하기 위해서입니다.',
        alternative: '모임 시간을 늘리기',
        createdAt: '2026-07-03T12:00:00Z',
        authorName: '박민서',
        roleIds: [ROLE_ID],
      },
    ],
    handoffItems: [
      { id: HANDOFF_ONE_ID, roleId: ROLE_ID, label: '역할의 한 줄 목적', category: 'RESPONSIBILITY', completed: true },
      { id: HANDOFF_TWO_ID, roleId: ROLE_ID, label: '자주 생기는 문제와 대응법', category: 'ADVICE', completed: false },
    ],
    resources: [],
  }
}

function projectionFromOnboarding(request: CreateWorkspaceRequest): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: request.teamName },
    season: { id: SEASON_ID, name: request.seasonName, startDate: request.startDate, endDate: request.endDate },
    members: request.memberNames.map((name, index) => ({
      id: fixtureUuid(11 + index),
      name,
      initials: name.slice(-1),
      tone: ['#d9e4da', '#f1d6cc', '#d8dfee'][index % 3] ?? '#d9e4da',
    })),
    roles: [],
    routines: [],
    rounds: [],
    decisions: [],
    handoffItems: [],
    resources: [],
  }
}

async function installApi(page: Page, initialProjection = makeProjection()): Promise<ApiHarness> {
  let projection = structuredClone(initialProjection)
  let activeAccessKey = ACCESS_KEY
  let failWorkspaceCreation = false
  let commitWorkspaceCreationThenTimeout = false
  let expireWorkspaceCreationReplay = false
  let commitRotationThenTimeout = false
  let conflictAccessKeyRotation = false
  let expireAccessKeyRotationReplay = false
  let contentCreationToCommitThenTimeout: ContentCreationOperation | null = null
  let contentCreationToRejectAsReused: ContentCreationOperation | null = null
  const accessKeyRotationResults = new Map<string, string>()
  const expiredAccessKeyRotationResults = new Set<string>()
  const workspaceCreationResults = new Map<string, { teamId: string; seasonId: string; accessKey: string }>()
  const contentCreationResults = new Map<string, unknown>()
  let failedGetsRemaining = 0
  let roleUpdateGate: Promise<void> | null = null
  let releaseRoleUpdate = () => {}
  let routineUpdateGate: Promise<void> | null = null
  let releaseRoutineUpdate = () => {}
  let nextRoleResourceConflict: RoleResource | null = null
  let failRoutineCompletion = false
  let handoffCompletionGate: Promise<void> | null = null
  let releaseHandoffCompletion = () => {}
  const calls: RecordedCall[] = []

  const handleApiRoute = async (route: Route) => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname
    const headers = request.headers()
    const body = request.postData() ? request.postDataJSON() : undefined
    calls.push({ method, path, headers, body })

    const json = (status: number, value: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    const error = (status: number, code: string, message: string) => json(status, { code, message })
    const contentOperation = method === 'POST'
      ? path === `${SCOPE_PATH}/roles`
        ? 'role'
        : path === `${SCOPE_PATH}/routines`
          ? 'routine'
          : path === `${SCOPE_PATH}/rounds`
            ? 'round'
            : path === `${SCOPE_PATH}/decisions`
              ? 'decision'
              : path === `${SCOPE_PATH}/handoff-items`
                ? 'handoffItem'
                : path === `${SCOPE_PATH}/role-resources`
                  ? 'roleResource'
                  : null
      : null

    if (method === 'POST' && path === '/api/v1/workspaces') {
      const creationIdempotencyKey = headers['idempotency-key']
      const committedResult = creationIdempotencyKey
        ? workspaceCreationResults.get(creationIdempotencyKey)
        : undefined
      if (committedResult) return json(201, committedResult)
      if (expireWorkspaceCreationReplay) {
        expireWorkspaceCreationReplay = false
        return error(409, 'IDEMPOTENCY_REPLAY_EXPIRED', '이전 요청 결과의 보관 기간이 지났습니다.')
      }
      if (failWorkspaceCreation) {
        failWorkspaceCreation = false
        return error(503, 'WORKSPACE_CREATION_FAILED', '작업 공간을 잠시 만들 수 없습니다.')
      }
      projection = projectionFromOnboarding(body as CreateWorkspaceRequest)
      const result = { teamId: TEAM_ID, seasonId: SEASON_ID, accessKey: ACCESS_KEY }
      if (creationIdempotencyKey) workspaceCreationResults.set(creationIdempotencyKey, result)
      if (commitWorkspaceCreationThenTimeout) {
        commitWorkspaceCreationThenTimeout = false
        return error(504, 'WORKSPACE_CREATION_TIMEOUT', '작업 공간 생성 응답을 확인하지 못했습니다.')
      }
      return json(201, result)
    }

    if (!path.startsWith(SCOPE_PATH)) return error(501, 'UNEXPECTED_TEST_REQUEST', `예상하지 못한 요청: ${method} ${path}`)
    const isAccessKeyRotation = method === 'POST' && path === `${SCOPE_PATH}/access-key/rotate`
    const rotationIdempotencyKey = headers['idempotency-key']
    if (isAccessKeyRotation && rotationIdempotencyKey) {
      if (expiredAccessKeyRotationResults.has(rotationIdempotencyKey)) {
        return error(409, 'IDEMPOTENCY_REPLAY_EXPIRED', '이전 요청 결과의 보관 기간이 지났습니다.')
      }
      const committedResult = accessKeyRotationResults.get(rotationIdempotencyKey)
      if (committedResult) return json(200, { accessKey: committedResult })
    }
    if (headers['x-baton-access-key'] !== activeAccessKey) return error(403, 'WORKSPACE_ACCESS_DENIED', '워크스페이스 접근 권한이 없습니다.')

    const contentIdempotencyKey = contentOperation ? headers['idempotency-key'] : undefined
    const contentResultKey = contentOperation && contentIdempotencyKey
      ? `${contentOperation}:${contentIdempotencyKey}`
      : null
    if (contentOperation && !contentIdempotencyKey) {
      return error(400, 'INVALID_INPUT', 'Idempotency-Key가 필요합니다.')
    }
    if (contentResultKey && contentCreationResults.has(contentResultKey)) {
      return json(201, structuredClone(contentCreationResults.get(contentResultKey)))
    }
    if (contentOperation && contentCreationToRejectAsReused === contentOperation) {
      contentCreationToRejectAsReused = null
      return error(409, 'IDEMPOTENCY_KEY_REUSED', '같은 멱등 키를 다른 요청에 사용할 수 없습니다.')
    }

    const finishContentCreation = (operation: ContentCreationOperation, created: unknown) => {
      const resultKey = `${operation}:${contentIdempotencyKey}`
      contentCreationResults.set(resultKey, structuredClone(created))
      if (contentCreationToCommitThenTimeout === operation) {
        contentCreationToCommitThenTimeout = null
        return error(504, 'CONTENT_CREATION_TIMEOUT', '저장 결과를 확인하지 못했습니다.')
      }
      return json(201, created)
    }

    if (method === 'GET' && path === `${SCOPE_PATH}/workspace`) {
      if (failedGetsRemaining > 0) {
        failedGetsRemaining -= 1
        return error(503, 'WORKSPACE_TEMPORARILY_UNAVAILABLE', '작업 공간을 잠시 불러올 수 없습니다.')
      }
      return json(200, structuredClone(projection))
    }

    if (isAccessKeyRotation) {
      if (!rotationIdempotencyKey) return error(400, 'INVALID_INPUT', 'Idempotency-Key가 필요합니다.')
      if (expireAccessKeyRotationReplay) {
        expireAccessKeyRotationReplay = false
        return error(409, 'IDEMPOTENCY_REPLAY_EXPIRED', '이전 요청 결과의 보관 기간이 지났습니다.')
      }
      if (conflictAccessKeyRotation) {
        conflictAccessKeyRotation = false
        return error(409, 'WORKSPACE_ACCESS_KEY_CONFLICT', '다른 접근 키 변경을 처리하고 있습니다.')
      }
      activeAccessKey = ROTATED_ACCESS_KEY
      accessKeyRotationResults.set(rotationIdempotencyKey, ROTATED_ACCESS_KEY)
      if (commitRotationThenTimeout) {
        commitRotationThenTimeout = false
        return error(504, 'ACCESS_KEY_ROTATION_TIMEOUT', '접근 키 변경 응답을 확인하지 못했습니다.')
      }
      return json(200, { accessKey: ROTATED_ACCESS_KEY })
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/roles`) {
      const input = body as CreateRoleRequest
      const created: Role = { id: CREATED_ROLE_ID, ...input }
      projection.roles.push(created)
      return finishContentCreation('role', created)
    }

    const roleUpdate = path.match(new RegExp(`^${SCOPE_PATH}/roles/([^/]+)$`))
    if (method === 'PUT' && roleUpdate) {
      const gate = roleUpdateGate
      roleUpdateGate = null
      if (gate) await gate
      const roleIndex = projection.roles.findIndex((candidate) => candidate.id === roleUpdate[1])
      if (roleIndex < 0) return error(404, 'ROLE_NOT_FOUND', '역할을 찾을 수 없습니다.')
      const updated: Role = { id: roleUpdate[1]!, ...(body as UpdateRoleRequest) }
      projection.roles[roleIndex] = updated
      return json(200, updated)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/routines`) {
      const input = body as CreateRoutineRequest
      const created: Routine = { id: CREATED_ROUTINE_ID, ...input }
      projection.routines.push(created)
      return finishContentCreation('routine', created)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/rounds`) {
      const input = body as CreateSeasonRoundRequest
      if (projection.rounds.some((round) => round.name.trim() === input.name.trim())) {
        return error(409, 'ROUND_NAME_CONFLICT', '같은 시즌에 동일한 회차 이름을 사용할 수 없습니다.')
      }
      const executionIds = [
        CREATED_ROUND_ROUTINE_ONE_EXECUTION_ID,
        CREATED_ROUND_ROUTINE_TWO_EXECUTION_ID,
        CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID,
      ]
      const routineExecutions: RoutineExecution[] = projection.routines.map((routine, index) => ({
        id: executionIds[index] ?? fixtureUuid(80 + index),
        roundId: CREATED_ROUND_ID,
        routineId: routine.id,
        title: routine.title,
        phase: routine.phase,
        dueLabel: routine.dueLabel,
        ownerRoleId: routine.ownerRoleId,
        status: 'WAITING',
        detail: routine.detail,
      }))
      const created: SeasonRound = {
        id: CREATED_ROUND_ID,
        name: input.name,
        meetingDate: input.meetingDate,
        routineExecutions,
      }
      projection.rounds.push(created)
      return finishContentCreation('round', created)
    }

    const routineUpdate = path.match(new RegExp(`^${SCOPE_PATH}/routines/([^/]+)$`))
    if (method === 'PUT' && routineUpdate) {
      const gate = routineUpdateGate
      routineUpdateGate = null
      if (gate) await gate
      const routineIndex = projection.routines.findIndex((candidate) => candidate.id === routineUpdate[1])
      if (routineIndex < 0) return error(404, 'ROUTINE_NOT_FOUND', '루틴을 찾을 수 없습니다.')
      const existing = projection.routines[routineIndex]!
      const updated: Routine = { ...existing, ...(body as UpdateRoutineRequest) }
      projection.routines[routineIndex] = updated
      return json(200, updated)
    }

    const routineCompletion = path.match(new RegExp(
      `^${SCOPE_PATH}/rounds/([^/]+)/routine-executions/([^/]+)/completion$`,
    ))
    if (method === 'PATCH' && routineCompletion) {
      if (failRoutineCompletion) {
        failRoutineCompletion = false
        return error(503, 'ROUTINE_COMPLETION_FAILED', '루틴 상태를 저장하지 못했습니다.')
      }
      const round = projection.rounds.find((candidate) => candidate.id === routineCompletion[1])
      if (!round) return error(404, 'SEASON_ROUND_NOT_FOUND', '시즌 회차를 찾을 수 없습니다.')
      const execution = round.routineExecutions.find((candidate) => candidate.id === routineCompletion[2])
      if (!execution) return error(404, 'ROUTINE_EXECUTION_NOT_FOUND', '루틴 실행을 찾을 수 없습니다.')
      execution.status = (body as { completed: boolean }).completed ? 'DONE' : 'WAITING'
      return json(200, execution)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/decisions`) {
      const input = body as CreateDecisionRequest
      const author = projection.members.find((member) => member.id === input.authorMemberId)
      const created = {
        id: CREATED_DECISION_ID,
        title: input.title,
        reason: input.reason,
        alternative: input.alternative,
        createdAt: '2026-07-20T12:00:00Z',
        authorName: author?.name ?? '알 수 없음',
        roleIds: input.roleIds,
      }
      projection.decisions.unshift(created)
      return finishContentCreation('decision', created)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/handoff-items`) {
      const input = body as CreateHandoffItemRequest
      const created: HandoffItem = { id: CREATED_HANDOFF_ID, completed: false, ...input }
      projection.handoffItems.push(created)
      return finishContentCreation('handoffItem', created)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/role-resources`) {
      const input = body as CreateRoleResourceRequest
      const created: RoleResource = {
        id: CREATED_ROLE_RESOURCE_ID,
        ...input,
        description: input.description ?? null,
      }
      projection.resources.push(created)
      return finishContentCreation('roleResource', created)
    }

    const roleResourceUpdate = path.match(new RegExp(`^${SCOPE_PATH}/role-resources/([^/]+)$`))
    if (method === 'PUT' && roleResourceUpdate) {
      const resourceIndex = projection.resources.findIndex(
        (candidate) => candidate.id === roleResourceUpdate[1],
      )
      if (resourceIndex < 0) {
        return error(404, 'ROLE_RESOURCE_NOT_FOUND', '역할 자료를 찾을 수 없습니다.')
      }
      if (nextRoleResourceConflict) {
        projection.resources[resourceIndex] = structuredClone(nextRoleResourceConflict)
        nextRoleResourceConflict = null
        return error(409, 'WORKSPACE_CONTENT_CONFLICT', '다른 사용자가 먼저 내용을 변경했습니다.')
      }
      const input = body as UpdateRoleResourceRequest
      const updated: RoleResource = {
        id: roleResourceUpdate[1]!,
        ...input,
        description: input.description ?? null,
      }
      projection.resources[resourceIndex] = updated
      return json(200, updated)
    }

    const handoffCompletion = path.match(new RegExp(`^${SCOPE_PATH}/handoff-items/([^/]+)/completion$`))
    if (method === 'PATCH' && handoffCompletion) {
      const gate = handoffCompletionGate
      handoffCompletionGate = null
      if (gate) await gate
      const item = projection.handoffItems.find((candidate) => candidate.id === handoffCompletion[1])
      if (!item) return error(404, 'HANDOFF_ITEM_NOT_FOUND', '바통 항목을 찾을 수 없습니다.')
      item.completed = (body as { completed: boolean }).completed
      return json(200, item)
    }

    return error(501, 'UNEXPECTED_TEST_REQUEST', `예상하지 못한 요청: ${method} ${path}`)
  }

  await page.route('**/api/v1/**', handleApiRoute)

  return {
    calls,
    projection: () => structuredClone(projection),
    attachPage: (peerPage) => peerPage.route('**/api/v1/**', handleApiRoute),
    failNextWorkspaceCreation: () => { failWorkspaceCreation = true },
    commitNextWorkspaceCreationThenTimeout: () => { commitWorkspaceCreationThenTimeout = true },
    expireNextWorkspaceCreationReplay: () => { expireWorkspaceCreationReplay = true },
    commitNextAccessKeyRotationThenTimeout: () => { commitRotationThenTimeout = true },
    conflictNextAccessKeyRotation: () => { conflictAccessKeyRotation = true },
    rotateAccessKeyFromAnotherDevice: () => { activeAccessKey = ROTATED_ACCESS_KEY },
    expireNextAccessKeyRotationReplay: () => { expireAccessKeyRotationReplay = true },
    expireAccessKeyRotationHistory: () => {
      accessKeyRotationResults.forEach((_accessKey, idempotencyKey) => expiredAccessKeyRotationResults.add(idempotencyKey))
      accessKeyRotationResults.clear()
    },
    commitNextContentCreationThenTimeout: (operation) => { contentCreationToCommitThenTimeout = operation },
    rejectNextContentCreationAsReused: (operation) => { contentCreationToRejectAsReused = operation },
    failNextWorkspaceGet: () => { failedGetsRemaining = 2 },
    holdNextRoleUpdate: () => {
      roleUpdateGate = new Promise((resolve) => { releaseRoleUpdate = resolve })
    },
    releaseRoleUpdate: () => releaseRoleUpdate(),
    holdNextRoutineUpdate: () => {
      routineUpdateGate = new Promise((resolve) => { releaseRoutineUpdate = resolve })
    },
    releaseRoutineUpdate: () => releaseRoutineUpdate(),
    conflictNextRoleResourceUpdate: (resource) => {
      nextRoleResourceConflict = structuredClone(resource)
    },
    failNextRoutineCompletion: () => { failRoutineCompletion = true },
    holdNextHandoffCompletion: () => {
      handoffCompletionGate = new Promise((resolve) => { releaseHandoffCompletion = resolve })
    },
    releaseHandoffCompletion: () => releaseHandoffCompletion(),
  }
}

async function openSharedWorkspace(page: Page) {
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
}

function navigation(page: Page, projectName: string) {
  return page.getByRole('navigation', { name: projectName === 'mobile' ? '모바일 주 메뉴' : '주 메뉴' })
}

async function blockBrowserStorage(page: Page) {
  await page.addInitScript(() => {
    const unavailable = () => { throw new DOMException('Storage disabled', 'SecurityError') }
    Storage.prototype.getItem = unavailable
    Storage.prototype.setItem = unavailable
    Storage.prototype.removeItem = unavailable
  })
}

async function blockContentCreationStorage(page: Page) {
  await page.addInitScript((prefix) => {
    const originalGetItem = Storage.prototype.getItem
    const originalSetItem = Storage.prototype.setItem
    const originalRemoveItem = Storage.prototype.removeItem
    Storage.prototype.getItem = function getItem(key) {
      if (key.startsWith(prefix)) throw new DOMException('Storage disabled', 'SecurityError')
      return originalGetItem.call(this, key)
    }
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith(prefix)) throw new DOMException('Storage disabled', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
    Storage.prototype.removeItem = function removeItem(key) {
      if (key.startsWith(prefix)) throw new DOMException('Storage disabled', 'SecurityError')
      originalRemoveItem.call(this, key)
    }
  }, PENDING_CONTENT_CREATION_STORAGE_PREFIX)
}

async function pendingCreationEntries(page: Page) {
  return page.evaluate((prefix) => {
    const entries: { normalizedPayload: string; idempotencyKey: string; createdAt: number }[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (!key?.startsWith(prefix)) continue
      try {
        const parsed = JSON.parse(localStorage.getItem(key) ?? 'null')
        if (parsed) entries.push(parsed)
      } catch {
        // Malformed values are not valid pending entries.
      }
    }
    return entries
  }, PENDING_CREATION_STORAGE_PREFIX)
}

async function pendingContentCreationEntries(page: Page) {
  return page.evaluate((prefix) => {
    const entries: {
      teamId: string
      seasonId: string
      operation: ContentCreationOperation
      normalizedPayload: string
      idempotencyKey: string
      createdAt: number
    }[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (!key?.startsWith(prefix)) continue
      try {
        const parsed = JSON.parse(localStorage.getItem(key) ?? 'null')
        if (parsed) entries.push(parsed)
      } catch {
        // Malformed values are not valid pending entries.
      }
    }
    return entries
  }, PENDING_CONTENT_CREATION_STORAGE_PREFIX)
}

async function recordedCall(api: ApiHarness, method: string, path: string) {
  await expect.poll(() => api.calls.filter((call) => call.method === method && call.path === path).length).toBeGreaterThan(0)
  const call = [...api.calls].reverse().find((candidate) => candidate.method === method && candidate.path === path)
  expect(call, `${method} ${path} 요청 기록`).toBeTruthy()
  return call!
}

function expectScopedCall(call: RecordedCall, body?: unknown, accessKey = ACCESS_KEY) {
  expect(call.headers['x-baton-access-key']).toBe(accessKey)
  if (call.method === 'POST') expect(call.headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  if (body !== undefined) expect(call.body).toEqual(body)
}

test('@smoke 온보딩으로 실제 작업 공간을 만든다', async ({ page }) => {
  const api = await installApi(page)
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('함께 푸는 알고리즘')
  await page.getByLabel('시즌 이름').fill('2026 가을 시즌')
  await page.getByLabel('시작일').fill('2026-09-01')
  await page.getByLabel('종료일').fill('2026-11-30')
  await page.getByLabel('구성원 이름').fill('박민서\n김준호, 최유진')
  await page.getByLabel('파일럿 생성 코드 (선택)').fill('pilot-only-code')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ACCESS_KEY)

  const createCall = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(createCall.headers['x-baton-access-key']).toBeUndefined()
  expect(createCall.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,64}$/)
  expect(createCall.headers['x-baton-creation-key']).toBe('pilot-only-code')
  expect(createCall.body).toEqual({
    teamName: '함께 푸는 알고리즘',
    seasonName: '2026 가을 시즌',
    startDate: '2026-09-01',
    endDate: '2026-11-30',
    memberNames: ['박민서', '김준호', '최유진'],
  })
  expectScopedCall(await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`))
  expect(await pendingCreationEntries(page)).toHaveLength(0)

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()
})

test('@smoke 같은 구성원 이름은 구분해서 입력하도록 안내하고 API를 호출하지 않는다', async ({ page }) => {
  const api = await installApi(page)
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('이름 구분 스터디')
  await page.getByLabel('시즌 이름').fill('2027 봄 시즌')
  await page.getByLabel('시작일').fill('2027-03-01')
  await page.getByLabel('종료일').fill('2027-05-31')
  await page.getByLabel('구성원 이름').fill('박민서\n 박민서 ')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('같은 이름은 구분할 수 있게 다르게 입력해 주세요.')
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(0)
  expect(await pendingCreationEntries(page)).toHaveLength(0)
})

test('@smoke 생성 pending을 내구 저장할 수 없으면 reload 후에도 API를 호출하지 않는다', async ({ page }) => {
  await blockBrowserStorage(page)
  const api = await installApi(page)

  const submitWorkspace = async () => {
    await page.getByLabel('팀 이름').fill('저장 필수 스터디')
    await page.getByLabel('시즌 이름').fill('2027 여름 시즌')
    await page.getByLabel('시작일').fill('2027-06-01')
    await page.getByLabel('종료일').fill('2027-08-31')
    await page.getByLabel('구성원 이름').fill('박민서\n김준호')
    await page.getByRole('button', { name: '작업 공간 만들기' }).click()
    await expect(page.getByRole('alert')).toContainText('일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.')
  }

  await page.goto('/')
  await submitWorkspace()
  await page.reload()
  await submitWorkspace()

  expect(api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(0)
})

test('@smoke 구성원 순서가 바뀐 온보딩 재시도는 reload 후에도 같은 멱등 키를 사용한다', async ({ page }) => {
  const api = await installApi(page)
  api.failNextWorkspaceCreation()
  await page.goto('/')

  const fillWorkspace = async (memberNames: string) => {
    await page.getByLabel('팀 이름').fill(' 재시도 스터디 ')
    await page.getByLabel('시즌 이름').fill(' 2026 겨울 시즌 ')
    await page.getByLabel('시작일').fill('2026-12-01')
    await page.getByLabel('종료일').fill('2027-02-28')
    await page.getByLabel('구성원 이름').fill(memberNames)
  }

  await fillWorkspace('박민서\n김준호\n최유진')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('alert')).toContainText('작업 공간을 잠시 만들 수 없습니다.')
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect((firstAttempt.body as CreateWorkspaceRequest).memberNames).toEqual(['박민서', '김준호', '최유진'])

  await page.reload()
  await fillWorkspace(' 최유진, 박민서, 김준호 ')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect((attempts[1]?.body as CreateWorkspaceRequest).memberNames).toEqual(['최유진', '박민서', '김준호'])
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  expect(attempts[1]?.headers['x-baton-creation-key']).toBeUndefined()
})

test('@smoke 만료된 온보딩 멱등 기록은 지우고 다음 명시적 시도에 새 키를 사용한다', async ({ page }) => {
  const api = await installApi(page)
  api.expireNextWorkspaceCreationReplay()
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('재시작 스터디')
  await page.getByLabel('시즌 이름').fill('2027 여름 시즌')
  await page.getByLabel('시작일').fill('2027-06-01')
  await page.getByLabel('종료일').fill('2027-08-31')
  await page.getByLabel('구성원 이름').fill('박민서\n김준호')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('작업 공간이 이미 만들어졌을 수 있으니 운영자나 기존 공유 링크를 먼저 확인해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@smoke 서로 다른 탭의 생성 pending을 보존하고 응답 유실 뒤 같은 키로 복구한다', async ({ page, context }) => {
  const apiA = await installApi(page)
  apiA.failNextWorkspaceCreation()
  await page.goto('/')

  const fillWorkspaceA = async () => {
    await page.getByLabel('팀 이름').fill('A 탭 스터디')
    await page.getByLabel('시즌 이름').fill('2027 A 시즌')
    await page.getByLabel('시작일').fill('2027-01-01')
    await page.getByLabel('종료일').fill('2027-03-31')
    await page.getByLabel('구성원 이름').fill('박민서')
  }
  await fillWorkspaceA()

  const pageB = await context.newPage()
  const apiB = await installApi(pageB)
  apiB.commitNextWorkspaceCreationThenTimeout()
  await pageB.goto('/')
  const fillWorkspaceB = async () => {
    await pageB.getByLabel('팀 이름').fill('B 탭 스터디')
    await pageB.getByLabel('시즌 이름').fill('2027 B 시즌')
    await pageB.getByLabel('시작일').fill('2027-04-01')
    await pageB.getByLabel('종료일').fill('2027-06-30')
    await pageB.getByLabel('구성원 이름').fill('김준호')
  }
  await fillWorkspaceB()
  await Promise.all([
    page.getByRole('button', { name: '작업 공간 만들기' }).click(),
    pageB.getByRole('button', { name: '작업 공간 만들기' }).click(),
  ])
  await expect(page.getByRole('alert')).toContainText('작업 공간을 잠시 만들 수 없습니다.')
  await expect(pageB.getByRole('alert')).toContainText('작업 공간 생성 응답을 확인하지 못했습니다.')
  const firstAttemptA = await recordedCall(apiA, 'POST', '/api/v1/workspaces')
  const firstAttemptB = await recordedCall(apiB, 'POST', '/api/v1/workspaces')

  const pendingAfterBoth = await pendingCreationEntries(pageB)
  expect(pendingAfterBoth).toHaveLength(2)
  expect(pendingAfterBoth.map((entry) => entry.idempotencyKey)).toEqual(expect.arrayContaining([
    firstAttemptA.headers['idempotency-key'],
    firstAttemptB.headers['idempotency-key'],
  ]))

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const pendingAfterAClear = await pendingCreationEntries(pageB)
  expect(pendingAfterAClear).toHaveLength(1)
  expect(pendingAfterAClear[0]?.idempotencyKey).toBe(firstAttemptB.headers['idempotency-key'])

  await pageB.reload()
  await fillWorkspaceB()
  await pageB.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(pageB.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attemptsB = apiB.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attemptsB).toHaveLength(2)
  expect(attemptsB[1]?.headers['idempotency-key']).toBe(firstAttemptB.headers['idempotency-key'])
  await expect.poll(async () => (await pendingCreationEntries(pageB)).length).toBe(0)
})

test('@smoke 손상된 온보딩 pending 저장소를 무시하고 정상 멱등 키로 재시도한다', async ({ page }) => {
  const malformedIdempotencyKey = 'invalid key'
  await page.addInitScript(({ storageKey, invalidKey }) => {
    localStorage.setItem(storageKey, JSON.stringify({ normalizedPayload: '{}', idempotencyKey: invalidKey }))
  }, { storageKey: LEGACY_PENDING_CREATION_STORAGE_KEY, invalidKey: malformedIdempotencyKey })

  const api = await installApi(page)
  api.failNextWorkspaceCreation()
  await page.goto('/')
  await page.getByLabel('팀 이름').fill('저장소 복구 스터디')
  await page.getByLabel('시즌 이름').fill('2027 봄 시즌')
  await page.getByLabel('시작일').fill('2027-03-01')
  await page.getByLabel('종료일').fill('2027-05-31')
  await page.getByLabel('구성원 이름').fill('박민서')

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('alert')).toContainText('작업 공간을 잠시 만들 수 없습니다.')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[0]?.headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  expect(attempts[0]?.headers['idempotency-key']).not.toBe(malformedIdempotencyKey)
  expect(attempts[1]?.headers['idempotency-key']).toBe(attempts[0]?.headers['idempotency-key'])
})

test('@smoke 브라우저 저장소가 막혀도 일회성 접근 키를 잃지 않는다', async ({ page }) => {
  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith('baton-access-key:')) throw new DOMException('Storage disabled', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  })
  const api = await installApi(page)
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('저장소 제한 스터디')
  await page.getByLabel('시즌 이름').fill('2026 가을 시즌')
  await page.getByLabel('시작일').fill('2026-09-01')
  await page.getByLabel('종료일').fill('2026-11-30')
  await page.getByLabel('구성원 이름').fill('박민서')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()
  expectScopedCall(await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`))
  expect(await pendingCreationEntries(page)).toHaveLength(0)

  await page.reload()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()
})

test('@smoke 잘못된 fragment 키가 저장된 정상 키를 덮지 않고 복구할 수 있다', async ({ page }) => {
  const api = await installApi(page)
  await page.addInitScript(({ storageKey, accessKey }) => {
    localStorage.setItem(storageKey, accessKey)
  }, { storageKey: `baton-access-key:${TEAM_ID}`, accessKey: ACCESS_KEY })
  await page.goto(`${WORKSPACE_PATH}#accessKey=wrong-access-key`)

  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  const call = await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`)
  expect(call.headers['x-baton-access-key']).toBe('wrong-access-key')
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ACCESS_KEY)

  await page.getByRole('button', { name: '저장된 키로 다시 열기' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  const successfulGet = [...api.calls].reverse().find((candidate) => candidate.method === 'GET' && candidate.path === `${SCOPE_PATH}/workspace`)
  expect(successfulGet?.headers['x-baton-access-key']).toBe(ACCESS_KEY)
})

test('@smoke 최근 작업 공간에서 다시 열고 목록을 지울 수 있다', async ({ page }) => {
  await installApi(page)
  await openSharedWorkspace(page)

  await expect.poll(() => page.evaluate(() => Boolean(localStorage.getItem('baton-recent-workspaces:v1')))).toBeTruthy()
  await page.evaluate(() => {
    const storageKey = 'baton-recent-workspaces:v1'
    const recent = JSON.parse(localStorage.getItem(storageKey) ?? '[]') as unknown[]
    localStorage.setItem(storageKey, JSON.stringify([...recent, { malformed: true }]))
  })

  await page.goto('/')
  const recentSection = page.getByRole('region', { name: '최근 작업 공간' })
  const workspaceLink = recentSection.getByRole('link', { name: /알고리즘 한 바퀴.*2026 여름 시즌/ })
  await expect(workspaceLink).toBeVisible()
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('baton-recent-workspaces:v1') ?? '[]'))).toHaveLength(1)
  await workspaceLink.click()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  await page.goto('/')
  await page.getByRole('button', { name: '알고리즘 한 바퀴 2026 여름 시즌 최근 목록에서 지우기' }).click()
  await expect(page.getByRole('region', { name: '최근 작업 공간' })).toHaveCount(0)
})

test('@smoke 서버 작업 공간에서 역할을 만들고 reload 후에도 유지한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.reject(new Error('denied')) },
    })
  })
  await openSharedWorkspace(page)
  expectScopedCall(await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`))

  const shareButton = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar').getByRole('button', { name: '공유' })
    : page.locator('.sidebar').getByRole('button', { name: '공유' })
  await shareButton.click()
  await expect(page.getByRole('status')).toHaveText(/직접 복사할 링크를 열었어요/)
  const shareDialog = page.getByRole('dialog', { name: '공유 링크 직접 복사' })
  const shareLink = shareDialog.getByLabel('공유 링크')
  const expectedShareUrl = `${new URL(page.url()).origin}${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`
  await expect(shareLink).toHaveValue(expectedShareUrl)
  await expect(shareLink).toBeFocused()
  expect(await shareLink.evaluate((input: HTMLInputElement) => [input.selectionStart, input.selectionEnd])).toEqual([0, expectedShareUrl.length])
  await shareDialog.getByRole('button', { name: '확인' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await dialog.getByLabel('역할 이름').fill('질문 큐레이터')
  await dialog.getByLabel('이 역할이 존재하는 이유').fill('막힌 지점을 모아 다음 세션에서 함께 풉니다.')
  await dialog.getByLabel('현재 담당자').selectOption(MEMBER_ONE_ID)
  await dialog.getByLabel('다음 담당자').selectOption(MEMBER_TWO_ID)
  await dialog.getByLabel('담당 시작일').fill('2026-07-20')
  await dialog.getByLabel('담당 종료일').fill('2026-09-17')
  await dialog.getByLabel('핵심 책임').fill('질문 수집\n공통 막힘 정리')
  await dialog.getByLabel('위험 신호').fill('질문 목록이 개인 메모에만 남을 수 있어요.')
  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(page.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })).toBeVisible()
  const roleCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  expectScopedCall(roleCall, {
    name: '질문 큐레이터',
    purpose: '막힌 지점을 모아 다음 세션에서 함께 풉니다.',
    currentMemberId: MEMBER_ONE_ID,
    nextMemberId: MEMBER_TWO_ID,
    assignmentStartDate: '2026-07-20',
    assignmentEndDate: '2026-09-17',
    responsibilities: ['질문 수집', '공통 막힘 정리'],
    risk: '질문 목록이 개인 메모에만 남을 수 있어요.',
  })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await expect(page.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })).toBeVisible()
  expect(await page.evaluate(() => ['baton-roles', 'baton-routines', 'baton-decisions', 'baton-handoff'].map((key) => localStorage.getItem(key)))).toEqual([null, null, null, null])
})

test('@operations 역할과 루틴 정의를 수정해도 기존 회차의 실행 스냅샷은 유지한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()

  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(roleDialog.getByLabel('역할 이름')).toHaveValue('문제 큐레이터')
  await expect(roleDialog.getByLabel('현재 담당자')).toHaveValue(MEMBER_ONE_ID)
  await roleDialog.getByLabel('역할 이름').fill('문제 운영 큐레이터')
  await roleDialog.getByLabel('이 역할이 존재하는 이유').fill('문제 선정과 진행 기준을 함께 관리합니다.')
  await roleDialog.getByLabel('현재 담당자').selectOption(MEMBER_TWO_ID)
  await roleDialog.getByLabel('다음 담당자').selectOption(MEMBER_THREE_ID)
  await roleDialog.getByLabel('담당 시작일').fill('2026-07-10')
  await roleDialog.getByLabel('담당 종료일').fill('2026-09-10')
  await roleDialog.getByLabel('핵심 책임').fill('문제 6개 선정\n진행 순서 공유')
  await roleDialog.getByLabel('위험 신호').fill('선정 기준이 오래된 문서에 남아 있어요.')
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(page.getByRole('status')).toContainText('역할 정보를 수정했어요.')
  await expect(page.locator('.role-row-open').filter({ hasText: '문제 운영 큐레이터' })).toBeVisible()
  const roleCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/roles/${ROLE_ID}`)
  expectScopedCall(roleCall, {
    name: '문제 운영 큐레이터',
    purpose: '문제 선정과 진행 기준을 함께 관리합니다.',
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: MEMBER_THREE_ID,
    assignmentStartDate: '2026-07-10',
    assignmentEndDate: '2026-09-10',
    responsibilities: ['문제 6개 선정', '진행 순서 공유'],
    risk: '선정 기준이 오래된 문서에 남아 있어요.',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '문제 5개 선정 루틴 수정' }).click()

  const routineDialog = page.getByRole('dialog', { name: '루틴 수정' })
  await expect(routineDialog.getByLabel('루틴 이름')).toHaveValue('문제 5개 선정')
  await expect(routineDialog.getByLabel('담당 역할')).toHaveValue(ROLE_ID)
  await routineDialog.getByLabel('루틴 이름').fill('문제 6개 선정')
  await routineDialog.getByLabel('운영 단계').selectOption('DURING')
  await routineDialog.getByLabel('언제까지').fill('목요일 20:00')
  await routineDialog.getByLabel('세부 설명').fill('난이도와 풀이 시간을 확인해 여섯 문제를 확정합니다.')
  await routineDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(page.getByRole('status')).toContainText('루틴 정보를 수정했어요.')
  const beforePhase = page.locator('.rhythm-phase').filter({ has: page.getByRole('heading', { name: '모임 전' }) })
  const snapshottedRoutine = beforePhase.locator('.routine-row').filter({ hasText: '문제 5개 선정' })
  await expect(snapshottedRoutine).toContainText('그래프 2개 · DP 2개 · 구현 1개')
  await expect(snapshottedRoutine).toContainText('수요일 18:00')
  await expect(page.getByRole('button', { name: '문제 6개 선정 루틴 수정' })).toBeVisible()
  await expect(page.locator('.routine-row').filter({ hasText: '문제 6개 선정' })).toHaveCount(0)
  const routineCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/routines/${ROUTINE_ID}`)
  expectScopedCall(routineCall, {
    title: '문제 6개 선정',
    phase: 'DURING',
    dueLabel: '목요일 20:00',
    ownerRoleId: ROLE_ID,
    detail: '난이도와 풀이 시간을 확인해 여섯 문제를 확정합니다.',
  })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await expect(page.locator('.role-row-open').filter({ hasText: '문제 운영 큐레이터' })).toBeVisible()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.locator('.rhythm-phase').filter({ has: page.getByRole('heading', { name: '모임 전' }) }).locator('.routine-row').filter({ hasText: '문제 5개 선정' })).toBeVisible()
  await expect(page.getByRole('button', { name: '문제 6개 선정 루틴 수정' })).toBeVisible()
})

test('@operations 수정 저장 중에는 닫기와 배경 클릭으로 dialog를 닫지 않는다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  api.holdNextRoleUpdate()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 이름').fill('저장 중인 문제 큐레이터')
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()
  await recordedCall(api, 'PUT', `${SCOPE_PATH}/roles/${ROLE_ID}`)

  const roleClose = roleDialog.getByRole('button', { name: '닫기' })
  await expect(roleDialog).toHaveAttribute('aria-busy', 'true')
  await expect(roleClose).toBeDisabled()
  await roleClose.click({ force: true })
  await expect(roleDialog).toBeVisible()
  api.releaseRoleUpdate()
  await expect(roleDialog).toHaveCount(0)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  api.holdNextRoutineUpdate()
  await page.getByRole('button', { name: '문제 5개 선정 루틴 수정' }).click()
  const routineDialog = page.getByRole('dialog', { name: '루틴 수정' })
  await routineDialog.getByLabel('언제까지').fill('저장 완료 후 공개')
  await routineDialog.getByRole('button', { name: '변경 저장' }).click()
  await recordedCall(api, 'PUT', `${SCOPE_PATH}/routines/${ROUTINE_ID}`)

  await expect(routineDialog).toHaveAttribute('aria-busy', 'true')
  await expect(routineDialog.getByRole('button', { name: '닫기' })).toBeDisabled()
  await page.locator('.modal-backdrop').click({ position: { x: 5, y: 5 }, force: true })
  await expect(routineDialog).toBeVisible()
  api.releaseRoutineUpdate()
  await expect(routineDialog).toHaveCount(0)
})

test('@smoke 생성 재시도 정보를 내구 저장할 수 없으면 콘텐츠 POST를 보내지 않는다', async ({ page }, testInfo) => {
  await blockContentCreationStorage(page)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const expectStorageBlock = async (dialog: Locator, submitLabel: string) => {
    await dialog.getByRole('button', { name: submitLabel }).click()
    await expect(dialog.getByRole('alert')).toContainText('일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.')
    await dialog.getByRole('button', { name: '닫기' }).click()
  }

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('저장 차단 역할')
  await roleDialog.getByLabel('이 역할이 존재하는 이유').fill('중복 요청을 보내지 않는지 확인합니다.')
  await expectStorageBlock(roleDialog, '역할 만들기')

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '루틴 추가' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await routineDialog.getByLabel('루틴 이름').fill('저장 차단 루틴')
  await routineDialog.getByLabel('언제까지').fill('수요일 18:00')
  await routineDialog.getByLabel('세부 설명').fill('저장 가능한 경우에만 전송합니다.')
  await expectStorageBlock(routineDialog, '루틴 만들기')

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('회차 이름').fill('저장 차단 회차')
  await expectStorageBlock(roundDialog, '회차 만들기')

  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()
  const decisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await decisionDialog.getByLabel('무엇을 바꾸기로 했나요?').fill('저장 가능한 요청만 보낸다')
  await decisionDialog.getByLabel('왜 이 선택을 했나요?').fill('응답 유실 뒤 중복 생성을 막기 위해서입니다.')
  await expectStorageBlock(decisionDialog, '결정 기록하기')

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()
  const handoffDialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await handoffDialog.getByLabel('남길 내용').fill('저장 차단 확인')
  await expectStorageBlock(handoffDialog, '항목 추가하기')

  const contentPaths = new Set([
    `${SCOPE_PATH}/roles`,
    `${SCOPE_PATH}/routines`,
    `${SCOPE_PATH}/rounds`,
    `${SCOPE_PATH}/decisions`,
    `${SCOPE_PATH}/handoff-items`,
  ])
  expect(api.calls.filter((call) => call.method === 'POST' && contentPaths.has(call.path))).toHaveLength(0)
})

test('@smoke 확인되지 않은 생성 요청이 한도에 이르면 기존 요청 정리를 안내한다', async ({ page }, testInfo) => {
  await page.addInitScript(({ prefix, count }) => {
    for (let index = 0; index < count; index += 1) {
      const idempotencyKey = `pending-content-${String(index).padStart(32, '0')}`
      localStorage.setItem(`${prefix}${idempotencyKey}`, JSON.stringify({
        teamId: 'another-team',
        seasonId: 'another-season',
        operation: 'handoffItem',
        normalizedPayload: JSON.stringify({
          roleId: `another-role-${index}`,
          label: `미확인 바통 ${index}`,
          category: 'ADVICE',
        }),
        idempotencyKey,
        createdAt: index,
      }))
    }
  }, { prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX, count: 20 })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await dialog.getByLabel('역할 이름').fill('스물한 번째 역할')
  await dialog.getByLabel('이 역할이 존재하는 이유').fill('한도 안내를 확인합니다.')
  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('확인되지 않은 생성 요청이 20개 남아 새 요청을 시작할 수 없습니다.')
  await expect(dialog.getByRole('alert')).toContainText('이전에 제출했던 같은 내용을 다시 제출해 결과를 확인한 뒤 시도해 주세요.')
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`)).toHaveLength(0)
})

test('@smoke 접근 키를 바꾸면 저장 키와 새 공유 링크를 함께 교체한다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.reject(new Error('denied')) },
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await page.evaluate((accessKey) => {
    window.history.replaceState(window.history.state, '', `${window.location.pathname}#accessKey=${accessKey}`)
  }, ACCESS_KEY)
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  await expect(keyDialog.getByText('이전 공유 링크는 즉시 열리지 않습니다.')).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

  const rotateCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expectScopedCall(rotateCall)
  expect(rotateCall.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,64}$/)
  await expect.poll(() => api.calls.some((call) =>
    call.method === 'GET'
      && call.path === `${SCOPE_PATH}/workspace`
      && call.headers['x-baton-access-key'] === ROTATED_ACCESS_KEY,
  )).toBeTruthy()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))

  await workspaceChrome.getByRole('button', { name: '공유' }).click()
  const shareLink = page.getByRole('dialog', { name: '공유 링크 직접 복사' }).getByLabel('공유 링크')
  await expect(shareLink).toHaveValue(new RegExp(`#accessKey=${ROTATED_ACCESS_KEY}$`))
  await page.getByRole('dialog', { name: '공유 링크 직접 복사' }).getByRole('button', { name: '확인' }).click()

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
})

test('@smoke 폐기된 접근 키 링크는 같은 앱 세션의 캐시를 재사용하지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(({ storageKey, accessKey, recentWorkspace }) => {
    localStorage.setItem(storageKey, accessKey)
    localStorage.setItem('baton-recent-workspaces:v1', JSON.stringify([recentWorkspace]))
  }, {
    storageKey: `baton-access-key:${TEAM_ID}`,
    accessKey: ACCESS_KEY,
    recentWorkspace: {
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      teamName: '알고리즘 한 바퀴',
      seasonName: '2026 여름 시즌',
      lastOpenedAt: '2026-07-24T00:00:00.000Z',
    },
  })
  await installApi(page)
  await page.goto('/')
  await page.getByRole('region', { name: '최근 작업 공간' })
    .getByRole('link', { name: /알고리즘 한 바퀴.*2026 여름 시즌/ })
    .click()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' })
    .getByRole('button', { name: '접근 키 바꾸기' })
    .click()

  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`),
  ).toBe(ROTATED_ACCESS_KEY)

  await page.evaluate(() => {
    document.documentElement.dataset.batonSameDocument = 'true'
  })
  await page.goBack()
  await expect(page.getByRole('heading', { level: 1, name: /사람이 바뀌어도/ })).toBeVisible()
  await page.goForward()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  expect(await page.evaluate(() =>
    document.documentElement.dataset.batonSameDocument)).toBe('true')
  const deniedResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === `${SCOPE_PATH}/workspace`
        && response.request().headers()['x-baton-access-key'] === ACCESS_KEY,
    { timeout: 5_000 },
  )
  const navigationResponse = await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)

  expect(navigationResponse).toBeNull()
  expect((await deniedResponse).status()).toBe(403)
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  expect(await page.evaluate((key) =>
    localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
})

test('@smoke 접근 키 회전 후 브라우저 저장이 실패하면 새 키를 fragment에 보존한다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith('baton-access-key:')) throw new DOMException('Storage disabled', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  })
  const api = await installApi(page)
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()

  await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ROTATED_ACCESS_KEY}`)
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBeNull()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()

  await page.reload()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ROTATED_ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  const reloadedGet = [...api.calls].reverse().find((call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`)
  expect(reloadedGet?.headers['x-baton-access-key']).toBe(ROTATED_ACCESS_KEY)
})

test('@smoke 회전 pending을 내구 저장할 수 없으면 reload 후에도 API를 호출하지 않는다', async ({ page }) => {
  await blockBrowserStorage(page)
  const api = await installApi(page)

  const tryRotation = async () => {
    const workspaceChrome = page.viewportSize()?.width === 390
      ? page.locator('.mobile-topbar')
      : page.locator('.sidebar')
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
    await expect(page.getByRole('alert')).toContainText('일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.')
  }

  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await tryRotation()

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await tryRotation()

  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)).toHaveLength(0)
})

test('@smoke 만료된 접근 키 회전 기록은 지우고 다음 명시적 시도에 새 키를 사용한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.expireNextAccessKeyRotationReplay()
  await openSharedWorkspace(page)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(keyDialog.getByRole('alert')).toContainText('새 요청으로 다시 시도해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('status')).toContainText('접근 키를 바꿨어요.')

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@smoke 응답이 유실된 접근 키 회전을 403 화면에서 같은 멱등 키로 복구한다', async ({ page }) => {
  const api = await installApi(page)
  api.commitNextAccessKeyRotationThenTimeout()
  await openSharedWorkspace(page)

  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('alert')).toContainText('접근 키 변경 응답을 확인하지 못했습니다.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expect(firstAttempt.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,64}$/)

  await page.reload()
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' }).click()

  await expect.poll(() => api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`).length).toBe(2)
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  const recoveredGet = [...api.calls].reverse().find((call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`)
  expect(recoveredGet?.headers['x-baton-access-key']).toBe(ROTATED_ACCESS_KEY)
})

test('@smoke 충돌 pending 복구가 403이면 반복을 멈추고 최신 공유 링크 확인을 안내한다', async ({ page }) => {
  const api = await installApi(page)
  api.conflictNextAccessKeyRotation()
  await openSharedWorkspace(page)

  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()

  await expect(page.getByRole('alert')).toContainText('다른 접근 키 변경을 처리하고 있습니다.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  const pendingAfterConflict = await page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)
  expect(JSON.parse(pendingAfterConflict ?? 'null')?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

  api.rotateAccessKeyFromAnotherDevice()
  await page.reload()
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' }).click()

  await expect(page.getByRole('alert')).toContainText('다른 기기에서 더 최신 접근 키 변경이 완료된 것으로 보입니다.')
  await expect(page.getByText('작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.')).toBeVisible()
  await expect(page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' })).toHaveCount(0)
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
})

test('@smoke 만료된 접근 키 복구 기록을 지우고 최신 공유 링크 확인을 안내한다', async ({ page }) => {
  const api = await installApi(page)
  api.commitNextAccessKeyRotationThenTimeout()
  await openSharedWorkspace(page)

  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('alert')).toContainText('접근 키 변경 응답을 확인하지 못했습니다.')
  api.expireAccessKeyRotationHistory()

  await page.reload()
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' }).click()

  await expect(page.getByRole('alert')).toContainText('더 최신 접근 키 변경이 완료되어 이전 결과를 자동 복구할 수 없습니다.')
  await expect(page.getByText('작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.')).toBeVisible()
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(attempts[0]?.headers['idempotency-key'])
})

test('@smoke 손상된 회전 pending 저장소를 무시하고 정상 멱등 키로 replay한다', async ({ page }) => {
  const pendingStorageKey = `baton-pending-access-key-change:v1:${TEAM_ID}`
  const malformedIdempotencyKey = 'invalid key'
  await page.addInitScript(({ storageKey, invalidKey }) => {
    localStorage.setItem(storageKey, JSON.stringify({ operation: 'recover', idempotencyKey: invalidKey }))
  }, { storageKey: pendingStorageKey, invalidKey: malformedIdempotencyKey })

  const api = await installApi(page)
  api.commitNextAccessKeyRotationThenTimeout()
  await openSharedWorkspace(page)
  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()

  const rotate = async () => {
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
  }
  await rotate()
  await expect(page.getByRole('alert')).toContainText('접근 키 변경 응답을 확인하지 못했습니다.')
  await rotate()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[0]?.headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  expect(attempts[0]?.headers['idempotency-key']).not.toBe(malformedIdempotencyKey)
  expect(attempts[1]?.headers['idempotency-key']).toBe(attempts[0]?.headers['idempotency-key'])
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
  await expect.poll(() => api.calls.some((call) =>
    call.method === 'GET'
      && call.path === `${SCOPE_PATH}/workspace`
      && call.headers['x-baton-access-key'] === ROTATED_ACCESS_KEY,
  )).toBeTruthy()
})

test('@operations 루틴과 회차를 내구 생성하고 선택한 회차의 완료 상태를 독립적으로 저장한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await page.getByRole('button', { name: '루틴 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await dialog.getByLabel('루틴 이름').fill('회고 질문 준비')
  await dialog.getByLabel('운영 단계').selectOption({ label: '모임 전' })
  await dialog.getByLabel('담당 역할').selectOption(ROLE_ID)
  await dialog.getByLabel('언제까지').fill('목요일 19:00')
  await dialog.getByLabel('세부 설명').fill('지난 회차에서 이어갈 질문 두 개를 고릅니다.')
  await dialog.getByRole('button', { name: '루틴 만들기' }).click()

  const routineCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/routines`)
  expectScopedCall(routineCall, {
    title: '회고 질문 준비',
    phase: 'BEFORE',
    dueLabel: '목요일 19:00',
    ownerRoleId: ROLE_ID,
    detail: '지난 회차에서 이어갈 질문 두 개를 고릅니다.',
  })

  const futureRoutine = page.locator('.routine-row').filter({ hasText: '회고 질문 준비' })
  await expect(futureRoutine).toContainText('다음 회차부터')
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 처리' })).toHaveCount(0)

  api.commitNextContentCreationThenTimeout('round')
  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await expect(roundDialog.getByLabel('회차 이름')).toHaveValue('3회차')
  await expect(roundDialog.getByLabel('모임 날짜')).toHaveAttribute('min', '2026-07-02')
  await expect(roundDialog.getByLabel('모임 날짜')).toHaveAttribute('max', '2026-09-17')
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-24')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()

  await expect(roundDialog.getByRole('alert')).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')
  const firstRoundAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/rounds`)
  expectScopedCall(firstRoundAttempt, { name: '3회차', meetingDate: '2026-07-24' })
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      operation: 'round',
      idempotencyKey: firstRoundAttempt.headers['idempotency-key'],
    }),
  ])

  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(CREATED_ROUND_ID)
  const roundAttempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/rounds`)
  expect(roundAttempts).toHaveLength(2)
  expect(roundAttempts[1]?.headers['idempotency-key']).toBe(firstRoundAttempt.headers['idempotency-key'])
  expect(api.projection().rounds.filter((round) => round.id === CREATED_ROUND_ID)).toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)

  const createdRoutineToggle = page.getByRole('button', { name: '회고 질문 준비 완료 처리' })
  await createdRoutineToggle.click()
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
  const completionCall = await recordedCall(api, 'PATCH', `${SCOPE_PATH}/rounds/${CREATED_ROUND_ID}/routine-executions/${CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID}/completion`)
  expectScopedCall(completionCall, { completed: true })

  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
  await expect(page.locator('.routine-row').filter({ hasText: '회고 질문 준비' })).toContainText('다음 회차부터')
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toHaveCount(0)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(CREATED_ROUND_ID)
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
})

test('@operations 다른 기기의 루틴 완료 변경을 열린 화면에 자동 반영한다', async ({ page, browser }, testInfo) => {
  const api = await installApi(page)
  const peerContext = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    viewport: testInfo.project.name === 'mobile'
      ? { width: 390, height: 844 }
      : { width: 1280, height: 720 },
  })
  const peerPage = await peerContext.newPage()
  await api.attachPage(peerPage)

  try {
    await openSharedWorkspace(page)
    await openSharedWorkspace(peerPage)

    for (const clientPage of [page, peerPage]) {
      await navigation(clientPage, testInfo.project.name).getByRole('button', { name: '운영' }).click()
      await clientPage.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
      await expect(clientPage.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
    }

    await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()
    await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()
    await expect(peerPage.getByRole('button', { name: '문제 5개 선정 완료 취소' }))
      .toBeVisible({ timeout: 15_000 })
  } finally {
    await peerContext.close()
  }
})

test('@operations 루틴 완료 저장 실패를 서버 상태로 되돌리고 알린다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)

  api.failNextRoutineCompletion()
  await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()

  await expect(page.getByRole('status')).toHaveText(/완료 상태를 바꾸지 못했어요.*루틴 상태를 저장하지 못했습니다/)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_ONE_ID)?.routineExecutions
    .find((execution) => execution.routineId === ROUTINE_ID)?.status).toBe('WAITING')
})

test('@smoke 동기화 실패에도 기존 내용을 유지하고 수동으로 다시 확인한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.failNextWorkspaceGet()
  await page.getByRole('button', { name: '지금 새로고침' }).click()

  const syncStatus = page.locator('.workspace-sync-status')
  await expect(syncStatus).toContainText('최신 내용을 확인하지 못했어요')
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  await page.getByRole('button', { name: '지금 새로고침' }).click()
  await expect(syncStatus).toContainText('화면 갱신')
})

test('@smoke 창 포커스와 네트워크 복구 때 즉시 최신 내용을 확인한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  const workspaceGetCount = () => api.calls.filter((call) =>
    call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length

  const initialGets = workspaceGetCount()
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect.poll(workspaceGetCount, { timeout: 3_000 }).toBeGreaterThan(initialGets)

  const getsAfterFocus = workspaceGetCount()
  await page.evaluate(() => {
    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))
  })
  await expect.poll(workspaceGetCount, { timeout: 3_000 }).toBeGreaterThan(getsAfterFocus)
})

test('@smoke 다른 기기에서 접근 키가 바뀌면 자동 동기화가 편집 화면을 닫는다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.rotateAccessKeyFromAnotherDevice()

  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' }))
    .toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toHaveCount(0)
})

test('@memory 결정과 작성자를 서버 기록으로 남긴다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()

  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('회고를 10분 먼저 시작한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('다음 액션을 정리할 시간이 자주 부족했기 때문입니다.')
  await dialog.getByLabel('검토한 다른 선택').fill('모임을 10분 연장한다')
  await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
  await dialog.getByLabel('영향받는 역할').selectOption(ROLE_ID)
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '회고를 10분 먼저 시작한다' })).toBeVisible()
  await expect(page.getByText('김준호', { exact: true })).toBeVisible()
  const decisionCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  expectScopedCall(decisionCall, {
    title: '회고를 10분 먼저 시작한다',
    reason: '다음 액션을 정리할 시간이 자주 부족했기 때문입니다.',
    alternative: '모임을 10분 연장한다',
    authorMemberId: MEMBER_TWO_ID,
    roleIds: [ROLE_ID],
  })
  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await expect(page.getByRole('heading', { name: '회고를 10분 먼저 시작한다' })).toBeVisible()
})

test('@memory 결정 저장 응답 유실 뒤 reload해도 같은 요청으로 결과를 회수한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.commitNextContentCreationThenTimeout('decision')
  await openSharedWorkspace(page)

  const openAndFillDecision = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
    await page.getByRole('button', { name: '결정 남기기' }).click()
    const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
    await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('응답 유실 재시도 규칙을 유지한다')
    await dialog.getByLabel('왜 이 선택을 했나요?').fill('같은 결정이 두 번 저장되는 것을 막기 위해서입니다.')
    await dialog.getByLabel('검토한 다른 선택').fill('사용자가 직접 중복을 정리한다')
    await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
    await dialog.getByLabel('영향받는 역할').selectOption(ROLE_ID)
    return dialog
  }

  const firstDialog = await openAndFillDecision()
  await firstDialog.getByRole('button', { name: '결정 기록하기' }).click()
  await expect(firstDialog.getByRole('alert')).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')

  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  const pendingAfterTimeout = await pendingContentCreationEntries(page)
  expect(pendingAfterTimeout).toHaveLength(1)
  expect(pendingAfterTimeout[0]).toMatchObject({
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    operation: 'decision',
    idempotencyKey: firstAttempt.headers['idempotency-key'],
  })
  expect(api.projection().decisions.filter((decision) => decision.title === '응답 유실 재시도 규칙을 유지한다')).toHaveLength(1)

  await page.reload()
  const retryDialog = await openAndFillDecision()
  await expect(retryDialog.getByRole('status')).toContainText('이전에 저장 결과를 확인하지 못한 요청이 있습니다.')
  await retryDialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '응답 유실 재시도 규칙을 유지한다' })).toBeVisible()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/decisions`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  expect(api.projection().decisions.filter((decision) => decision.title === '응답 유실 재시도 규칙을 유지한다')).toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff 역할 자료를 생성·수정하고 바통북에서 다시 연다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.commitNextContentCreationThenTimeout('roleResource')
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()

  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '자료 추가' }).click()
  const createDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await createDialog.getByLabel('역할').selectOption(ROLE_ID)
  await createDialog.getByLabel('링크').fill('https://docs.example.com/problem-selection')
  await createDialog.getByRole('button', { name: '자료 연결하기' }).click()
  await expect(createDialog.getByRole('alert')).toContainText('자료 이름을 입력해 주세요.')
  await expect(createDialog.getByLabel('자료 이름')).toBeFocused()
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/role-resources`)).toHaveLength(0)

  await createDialog.getByLabel('자료 이름').fill('문제 선정 기준 문서')
  await createDialog.getByLabel('자료 설명').fill('매주 문제 후보를 고를 때 확인하는 기준입니다.')
  await createDialog.getByRole('button', { name: '자료 연결하기' }).click()
  await expect(createDialog.getByRole('alert')).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')

  const firstCreateCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/role-resources`)
  expectScopedCall(firstCreateCall, {
    roleId: ROLE_ID,
    title: '문제 선정 기준 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '매주 문제 후보를 고를 때 확인하는 기준입니다.',
  })
  const pendingAfterTimeout = (await pendingContentCreationEntries(page))
    .filter((entry) => entry.operation === 'roleResource')
  expect(pendingAfterTimeout).toHaveLength(1)
  expect(pendingAfterTimeout[0]?.idempotencyKey).toBe(firstCreateCall.headers['idempotency-key'])
  expect(api.projection().resources.filter((resource) => resource.title === '문제 선정 기준 문서')).toHaveLength(1)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  await inspector.getByRole('button', { name: '자료 추가' }).click()
  const retryDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await retryDialog.getByLabel('역할').selectOption(ROLE_ID)
  await retryDialog.getByLabel('자료 이름').fill('문제 선정 기준 문서')
  await retryDialog.getByLabel('링크').fill('https://docs.example.com/problem-selection')
  await retryDialog.getByLabel('자료 설명').fill('매주 문제 후보를 고를 때 확인하는 기준입니다.')
  await expect(retryDialog.getByRole('status')).toContainText('이전에 저장 결과를 확인하지 못한 요청이 있습니다.')
  await retryDialog.getByRole('button', { name: '자료 연결하기' }).click()

  const createAttempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/role-resources`,
  )
  expect(createAttempts).toHaveLength(2)
  expect(createAttempts[1]?.headers['idempotency-key']).toBe(firstCreateCall.headers['idempotency-key'])
  expect(api.projection().resources.filter((resource) => resource.title === '문제 선정 기준 문서')).toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page))
    .filter((entry) => entry.operation === 'roleResource').length).toBe(0)

  const createdLink = inspector.getByRole('link', {
    name: '문제 선정 기준 문서 새 창에서 열기',
  })
  await expect(createdLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection')
  await expect(createdLink).toHaveAttribute('target', '_blank')
  await expect(createdLink).toHaveAttribute('rel', 'noopener noreferrer')

  await inspector.getByRole('button', { name: '문제 선정 기준 문서 자료 수정' }).click()
  const updateDialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await updateDialog.getByLabel('자료 이름').fill('문제 선정 기준 최신본')
  await updateDialog.getByLabel('링크').fill('https://docs.example.com/problem-selection-v2')
  await updateDialog.getByLabel('자료 설명').fill('난이도와 풀이 시간을 같이 확인하는 최신 기준입니다.')
  await updateDialog.getByRole('button', { name: '변경 저장' }).click()

  const updateCall = await recordedCall(
    api,
    'PUT',
    `${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}`,
  )
  expectScopedCall(updateCall, {
    roleId: ROLE_ID,
    title: '문제 선정 기준 최신본',
    url: 'https://docs.example.com/problem-selection-v2',
    description: '난이도와 풀이 시간을 같이 확인하는 최신 기준입니다.',
  })
  const updatedLink = inspector.getByRole('link', {
    name: '문제 선정 기준 최신본 새 창에서 열기',
  })
  await expect(updatedLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection-v2')
  await expect(updatedLink).toHaveAttribute('target', '_blank')
  await expect(updatedLink).toHaveAttribute('rel', 'noopener noreferrer')

  if (testInfo.project.name === 'mobile') {
    await inspector.getByRole('button', { name: '상세 닫기' }).click()
  }
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '바통북 미리보기' }).click()
  const preview = page.getByRole('dialog', { name: '문제 큐레이터 바통북' })
  const previewLink = preview.getByRole('link', {
    name: '문제 선정 기준 최신본 새 창에서 열기',
  })
  await expect(previewLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection-v2')
  await expect(previewLink).toHaveAttribute('target', '_blank')
  await expect(previewLink).toHaveAttribute('rel', 'noopener noreferrer')
  await expect(preview.getByText('난이도와 풀이 시간을 같이 확인하는 최신 기준입니다.')).toBeVisible()
  await preview.getByRole('button', { name: '미리보기 닫기' }).click()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  const reloadedLink = page.getByLabel('선택한 역할 상세').getByRole('link', {
    name: '문제 선정 기준 최신본 새 창에서 열기',
  })
  await expect(reloadedLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection-v2')

  const storedProductData = await page.evaluate((needles) => {
    const matches: string[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const value = localStorage.getItem(localStorage.key(index) ?? '') ?? ''
      if (needles.some((needle) => value.includes(needle))) matches.push(value)
    }
    return matches
  }, [
    '문제 선정 기준 문서',
    'https://docs.example.com/problem-selection',
    '문제 선정 기준 최신본',
    'https://docs.example.com/problem-selection-v2',
  ])
  expect(storedProductData).toEqual([])
})

test('@handoff 역할 자료 충돌은 낡은 폼을 닫고 최신 내용을 다시 연다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.resources.push({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '문제 선정 기준 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '기존 기준입니다.',
  })
  const api = await installApi(page, initialProjection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()

  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '문제 선정 기준 문서 자료 수정' }).click()
  const dialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await dialog.getByLabel('자료 이름').fill('내 화면의 낡은 수정')
  await dialog.getByLabel('링크').fill('https://docs.example.com/stale-edit')

  api.conflictNextRoleResourceUpdate({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '다른 구성원이 갱신한 기준',
    url: 'https://docs.example.com/remote-edit',
    description: '서버의 최신 기준입니다.',
  })
  await dialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(dialog).toBeHidden()
  await expect(page.getByRole('status')).toContainText('다른 구성원의 최신 자료를 불러왔어요')
  const latestLink = inspector.getByRole('link', {
    name: '다른 구성원이 갱신한 기준 새 창에서 열기',
  })
  await expect(latestLink).toHaveAttribute('href', 'https://docs.example.com/remote-edit')

  await inspector.getByRole('button', { name: '다른 구성원이 갱신한 기준 자료 수정' }).click()
  const reopenedDialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await expect(reopenedDialog.getByLabel('자료 이름')).toHaveValue('다른 구성원이 갱신한 기준')
  await expect(reopenedDialog.getByLabel('링크')).toHaveValue('https://docs.example.com/remote-edit')
  await expect(reopenedDialog.getByLabel('자료 설명')).toHaveValue('서버의 최신 기준입니다.')
})

test('@handoff 재사용할 수 없는 생성 요청은 pending을 지우고 다음 제출에 새 키를 쓴다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.rejectNextContentCreationAsReused('handoffItem')
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('재사용 종료 확인')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('목록에 항목이 이미 생겼는지 확인한 뒤, 필요하면 다시 제출해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)

  await dialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(page.getByRole('checkbox', { name: '재사용 종료 확인' })).toBeVisible()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff 한 탭의 성공은 다른 탭이 보관한 같은 내용의 pending을 지우지 않는다', async ({ page }, testInfo) => {
  const firstKey = 'content-race-key-00000000000000000001'
  const secondKey = 'content-race-key-00000000000000000002'
  await page.addInitScript(({ prefix, teamId, seasonId, roleId, first, second }) => {
    const normalizedPayload = JSON.stringify({
      roleId,
      label: '멀티탭 복구 보존',
      category: 'RESPONSIBILITY',
    })
    const records = [
      { idempotencyKey: first, createdAt: 1 },
      { idempotencyKey: second, createdAt: 2 },
    ]
    records.forEach(({ idempotencyKey, createdAt }) => {
      localStorage.setItem(`${prefix}${idempotencyKey}`, JSON.stringify({
        teamId,
        seasonId,
        operation: 'handoffItem',
        normalizedPayload,
        idempotencyKey,
        createdAt,
      }))
    })
  }, {
    prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX,
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    roleId: ROLE_ID,
    first: firstKey,
    second: secondKey,
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('멀티탭 복구 보존')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  const call = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  expect(call.headers['idempotency-key']).toBe(firstKey)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(1)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({ idempotencyKey: secondKey }),
  ])
})

test('@handoff 바통 항목을 만들고 완료한 뒤 바통북을 확인한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await dialog.getByLabel('역할').selectOption(ROLE_ID)
  await dialog.getByLabel('남길 내용').fill('문제 선정 기준 문서 링크')
  await dialog.getByLabel('항목 종류').selectOption({ label: '자료' })
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  const createCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  expectScopedCall(createCall, { roleId: ROLE_ID, label: '문제 선정 기준 문서 링크', category: 'RESOURCE' })
  const checkbox = page.getByRole('checkbox', { name: '문제 선정 기준 문서 링크' })
  await expect(checkbox).not.toBeChecked()
  api.holdNextHandoffCompletion()
  await checkbox.click()
  await expect(checkbox).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: '역할의 한 줄 목적' })).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })).toBeDisabled()
  api.releaseHandoffCompletion()
  await expect(checkbox).toBeChecked()
  const completionCall = await recordedCall(api, 'PATCH', `${SCOPE_PATH}/handoff-items/${CREATED_HANDOFF_ID}/completion`)
  expectScopedCall(completionCall, { completed: true })

  await page.getByRole('button', { name: '바통북 미리보기' }).click()
  const preview = page.getByRole('dialog', { name: '문제 큐레이터 바통북' })
  await expect(preview.getByText(/문제 5개 선정/)).toBeVisible()
  await expect(preview.getByText('자주 생기는 문제와 대응법')).toBeVisible()
  await expect(preview.getByText('문제 선정 기준 문서 링크')).toHaveCount(0)
  await preview.getByRole('button', { name: '미리보기 닫기' }).click()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await expect(page.getByRole('checkbox', { name: '문제 선정 기준 문서 링크' })).toBeChecked()
})

test('@responsive 390x844에서 루틴 추가와 완료를 수행할 수 있다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '루틴 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await expect(dialog).toBeInViewport()
  await dialog.getByLabel('루틴 이름').fill('다음 문제 예고')
  await dialog.getByLabel('운영 단계').selectOption('AFTER')
  await dialog.getByLabel('담당 역할').selectOption(ROLE_ID)
  await dialog.getByLabel('언제까지').fill('금요일 20:00')
  await dialog.getByLabel('세부 설명').fill('다음 주 주제를 한 줄로 공유합니다.')
  await dialog.getByRole('button', { name: '루틴 만들기' }).click()
  await expect(page.locator('.routine-row').filter({ hasText: '다음 문제 예고' })).toContainText('다음 회차부터')
  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await expect(roundDialog).toBeInViewport()
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-31')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await page.getByRole('button', { name: '다음 문제 예고 완료 처리' }).click()
  await expect(page.getByRole('button', { name: '다음 문제 예고 완료 취소' })).toBeVisible()
})

test('@smoke 일시적인 조회 오류에서 다시 시도할 수 있다', async ({ page }) => {
  const api = await installApi(page)
  api.failNextWorkspaceGet()
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '다시 시도하기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
})
