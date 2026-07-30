import { expect, test } from '@playwright/test'
import type { Dialog, Locator, Page, Route } from '@playwright/test'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateMemberRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  CreateWorkspaceRequest,
  Decision,
  HandoffItem,
  Member,
  Role,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
  UpdateDecisionRequest,
  UpdateHandoffItemRequest,
  UpdateMemberDeactivationRequest,
  UpdateMemberRequest,
  UpdateRecordArchiveRequest,
  UpdateRoleRequest,
  UpdateRoleResourceRequest,
  UpdateRoutineRequest,
  UpdateRoundScheduleRequest,
  UpdateSeasonRoundRequest,
  WorkspaceProjection,
} from '../../src/features/workspace/types'
import type { ContentCreationOperation } from '../../src/features/workspace/pendingContentCreation'

const fixtureUuid = (sequence: number) => `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`

const TEAM_ID = fixtureUuid(1)
const SEASON_ID = fixtureUuid(2)
const MEMBER_ONE_ID = fixtureUuid(11)
const MEMBER_TWO_ID = fixtureUuid(12)
const MEMBER_THREE_ID = fixtureUuid(13)
const CREATED_MEMBER_ID = fixtureUuid(14)
const ROLE_ID = fixtureUuid(21)
const CREATED_ROLE_ID = fixtureUuid(22)
const SECOND_ROLE_ID = fixtureUuid(23)
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
const AUTOMATIC_ROUND_ID = fixtureUuid(64)
const ROUND_ONE_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(71)
const ROUND_ONE_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(72)
const ROUND_TWO_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(73)
const ROUND_TWO_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(74)
const CREATED_ROUND_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(75)
const CREATED_ROUND_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(76)
const CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID = fixtureUuid(77)
const AUTOMATIC_ROUND_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(78)
const AUTOMATIC_ROUND_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(79)
const ACCESS_KEY = 'e2e-access-key'
const ROTATED_ACCESS_KEY = 'e2e-rotated-access-key'
const SECOND_ROTATED_ACCESS_KEY = 'e2e-second-rotated-access-key'
const WORKSPACE_PATH = `/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const SCOPE_PATH = `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const CONTENT_CREATION_PATHS: Record<ContentCreationOperation, string> = {
  member: `${SCOPE_PATH}/members`,
  role: `${SCOPE_PATH}/roles`,
  routine: `${SCOPE_PATH}/routines`,
  round: `${SCOPE_PATH}/rounds`,
  decision: `${SCOPE_PATH}/decisions`,
  handoffItem: `${SCOPE_PATH}/handoff-items`,
  roleResource: `${SCOPE_PATH}/role-resources`,
}
const PENDING_CREATION_STORAGE_PREFIX = 'baton-pending-workspace-creation:v3:'
const PENDING_CONTENT_CREATION_STORAGE_PREFIX = 'baton-pending-content-creation:v1:'
const PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY = `baton-pending-access-key-change:v1:${TEAM_ID}`
const LEGACY_PENDING_CREATION_STORAGE_KEY = 'baton-pending-workspace-creation:v1'

function parseCssColor(value: string): [number, number, number] {
  const normalized = value.trim()
  if (/^#[0-9a-f]{6}$/i.test(normalized)) {
    return [
      Number.parseInt(normalized.slice(1, 3), 16),
      Number.parseInt(normalized.slice(3, 5), 16),
      Number.parseInt(normalized.slice(5, 7), 16),
    ]
  }

  const rgb = normalized.match(
    /^rgba?\(\s*([\d.]+)(?:\s*,\s*|\s+)([\d.]+)(?:\s*,\s*|\s+)([\d.]+)/i,
  )
  if (!rgb) {
    throw new Error(`해석할 수 없는 CSS 색상입니다: ${value}`)
  }
  return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])]
}

function relativeLuminance(value: string) {
  const [red, green, blue] = parseCssColor(value)
  const linearize = (channel: number) => {
    const normalized = channel / 255
    return normalized <= 0.04045
      ? normalized / 12.92
      : ((normalized + 0.055) / 1.055) ** 2.4
  }
  return (0.2126 * linearize(red)) + (0.7152 * linearize(green)) + (0.0722 * linearize(blue))
}

function contrastRatio(foreground: string, background: string) {
  const foregroundLuminance = relativeLuminance(foreground)
  const backgroundLuminance = relativeLuminance(background)
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

function routineDeadlineAt(routine: Routine, meetingDate: string) {
  if (routine.deadlineDayOffset == null || !routine.deadlineTime) return null
  const deadline = new Date(`${meetingDate}T${routine.deadlineTime}+09:00`)
  deadline.setUTCDate(deadline.getUTCDate() + routine.deadlineDayOffset)
  return deadline.toISOString()
}

function serializeLocalTime(value: string) {
  return value.length === 5 ? `${value}:00` : value
}

function nextScheduleOccurrence(
  firstMeetingDate: string,
  recurrence: 'WEEKLY' | 'BIWEEKLY',
  previousCursor?: string,
) {
  if (!previousCursor || previousCursor <= firstMeetingDate) return firstMeetingDate
  const first = Date.parse(`${firstMeetingDate}T00:00:00Z`)
  const lowerBound = Date.parse(`${previousCursor}T00:00:00Z`)
  const intervalDays = recurrence === 'WEEKLY' ? 7 : 14
  const daysFromFirst = Math.floor((lowerBound - first) / 86_400_000)
  const intervals = Math.ceil(daysFromFirst / intervalDays)
  return new Date(first + intervals * intervalDays * 86_400_000)
    .toISOString()
    .slice(0, 10)
}

async function expectVisibleFocus(locator: Locator, background: string) {
  const focusStyle = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      color: style.outlineColor,
      style: style.outlineStyle,
      width: style.outlineWidth,
    }
  })

  expect(focusStyle.style).toBe('solid')
  expect(Number.parseFloat(focusStyle.width)).toBeGreaterThanOrEqual(3)
  expect(contrastRatio(focusStyle.color, background)).toBeGreaterThanOrEqual(3)
}

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
  addRoundFromAnotherDevice: (round: SeasonRound) => void
  failNextWorkspaceCreation: () => void
  holdNextWorkspaceCreation: () => void
  releaseWorkspaceCreation: () => void
  rejectNextWorkspaceCreationAsInvalidInput: () => void
  commitNextWorkspaceCreationThenTimeout: () => void
  expireNextWorkspaceCreationReplay: () => void
  commitNextAccessKeyRotationThenTimeout: () => void
  conflictNextAccessKeyRotation: () => void
  rotateAccessKeyFromAnotherDevice: () => void
  expireNextAccessKeyRotationReplay: () => void
  expireAccessKeyRotationHistory: () => void
  holdAccessKeyRotations: () => void
  releaseAccessKeyRotations: () => void
  commitNextContentCreationThenTimeout: (operation: ContentCreationOperation) => void
  rejectNextContentCreationAsReused: (operation: ContentCreationOperation) => void
  rejectNextMemberAsConflict: () => void
  holdNextContentCreation: (operation: ContentCreationOperation) => void
  releaseContentCreation: () => void
  failNextWorkspaceGet: () => void
  makeWorkspaceGetsUnavailable: () => void
  restoreWorkspaceGets: () => void
  holdNextRoleUpdate: () => void
  releaseRoleUpdate: () => void
  conflictNextRoleUpdate: (role: Role) => void
  holdNextRoutineUpdate: () => void
  releaseRoutineUpdate: () => void
  conflictNextRoutineUpdate: (routine: Routine) => void
  conflictNextRoleResourceUpdate: (resource: RoleResource) => void
  failNextRoutineCompletion: () => void
  conflictNextRoutineCompletion: (completed: boolean) => void
  holdNextRoutineCompletion: () => void
  releaseRoutineCompletion: () => void
  holdNextHandoffCompletion: () => void
  failNextHandoffCompletion: () => void
  conflictNextHandoffCompletion: (completed: boolean) => void
  releaseHandoffCompletion: () => void
  holdWorkspaceGets: () => void
  releaseWorkspaceGets: () => void
}

function makeProjection(): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: '알고리즘 한 바퀴' },
    season: {
      id: SEASON_ID,
      name: '2026 여름 시즌',
      startDate: '2026-07-02',
      endDate: '2026-09-17',
      endedAt: null,
      previousSeasonId: null,
      timeZone: 'Asia/Seoul',
      roundSchedule: null,
    },
    seasons: [{
      id: SEASON_ID,
      name: '2026 여름 시즌',
      startDate: '2026-07-02',
      endDate: '2026-09-17',
      endedAt: null,
      previousSeasonId: null,
      timeZone: 'Asia/Seoul',
      roundSchedule: null,
    }],
    members: [
      { id: MEMBER_ONE_ID, name: '박민서', initials: '민', tone: '#d9e4da', deactivatedAt: null },
      { id: MEMBER_TWO_ID, name: '김준호', initials: '준', tone: '#f1d6cc', deactivatedAt: null },
      { id: MEMBER_THREE_ID, name: '최유진', initials: '유', tone: '#d8dfee', deactivatedAt: null },
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
        deadlineDayOffset: -1,
        deadlineTime: '22:00:00',
        ownerRoleId: ROLE_ID,
        detail: '그래프 2개 · DP 2개 · 구현 1개',
      },
      {
        id: SECOND_ROUTINE_ID,
        title: '풀이 노트 정리',
        phase: 'AFTER',
        dueLabel: '금요일 21:00',
        deadlineDayOffset: 1,
        deadlineTime: '21:00:00',
        ownerRoleId: ROLE_ID,
        detail: '이번 회차의 핵심 풀이를 한 문단으로 남깁니다.',
      },
    ],
    rounds: [
      {
        id: ROUND_TWO_ID,
        name: '2회차',
        meetingDate: '2026-07-17',
        archivedAt: null,
        origin: 'MANUAL',
        scheduledOccurrenceDate: null,
        scheduledAt: null,
        timingStatus: 'IN_PROGRESS',
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
            deadlineAt: '2026-07-16T13:00:00Z',
            timingStatus: 'COMPLETED',
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
            deadlineAt: '2026-07-18T12:00:00Z',
            timingStatus: 'IN_PROGRESS',
          },
        ],
      },
      {
        id: ROUND_ONE_ID,
        name: '1회차',
        meetingDate: '2026-07-10',
        archivedAt: null,
        origin: 'MANUAL',
        scheduledOccurrenceDate: null,
        scheduledAt: null,
        timingStatus: 'PLANNED',
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
            deadlineAt: '2026-07-09T13:00:00Z',
            timingStatus: 'PLANNED',
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
            deadlineAt: '2026-07-11T12:00:00Z',
            timingStatus: 'PLANNED',
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
        authorMemberId: MEMBER_ONE_ID,
        authorName: '박민서',
        roleIds: [ROLE_ID],
        archivedAt: null,
      },
    ],
    handoffItems: [
      {
        id: HANDOFF_ONE_ID,
        roleId: ROLE_ID,
        label: '역할의 한 줄 목적',
        category: 'RESPONSIBILITY',
        completed: true,
        archivedAt: null,
      },
      {
        id: HANDOFF_TWO_ID,
        roleId: ROLE_ID,
        label: '자주 생기는 문제와 대응법',
        category: 'ADVICE',
        completed: false,
        archivedAt: null,
      },
    ],
    resources: [],
  }
}

function projectionFromOnboarding(request: CreateWorkspaceRequest): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: request.teamName },
    season: {
      id: SEASON_ID,
      name: request.seasonName,
      startDate: request.startDate,
      endDate: request.endDate,
      endedAt: null,
      previousSeasonId: null,
      timeZone: 'Asia/Seoul',
      roundSchedule: null,
    },
    seasons: [{
      id: SEASON_ID,
      name: request.seasonName,
      startDate: request.startDate,
      endDate: request.endDate,
      endedAt: null,
      previousSeasonId: null,
      timeZone: 'Asia/Seoul',
      roundSchedule: null,
    }],
    members: request.memberNames.map((name, index) => ({
      id: fixtureUuid(11 + index),
      name,
      initials: name.slice(-1),
      tone: ['#d9e4da', '#f1d6cc', '#d8dfee'][index % 3] ?? '#d9e4da',
      deactivatedAt: null,
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
  let workspaceCreationGate: Promise<void> | null = null
  let releaseWorkspaceCreation = () => {}
  let rejectWorkspaceCreationAsInvalidInput = false
  let commitWorkspaceCreationThenTimeout = false
  let expireWorkspaceCreationReplay = false
  let commitRotationThenTimeout = false
  let conflictAccessKeyRotation = false
  let expireAccessKeyRotationReplay = false
  let accessKeyRotationGate: Promise<void> | null = null
  let releaseAccessKeyRotations = () => {}
  let contentCreationToCommitThenTimeout: ContentCreationOperation | null = null
  let contentCreationToRejectAsReused: ContentCreationOperation | null = null
  let rejectMemberAsConflict = false
  let contentCreationGate: {
    operation: ContentCreationOperation
    pending: Promise<void>
  } | null = null
  let releaseContentCreation = () => {}
  const accessKeyRotationResults = new Map<string, string>()
  const expiredAccessKeyRotationResults = new Set<string>()
  const workspaceCreationResults = new Map<string, { teamId: string; seasonId: string; accessKey: string }>()
  const contentCreationResults = new Map<string, unknown>()
  let failedGetsRemaining = 0
  let workspaceGetsUnavailable = false
  let roleUpdateGate: Promise<void> | null = null
  let releaseRoleUpdate = () => {}
  let nextRoleConflict: Role | null = null
  let routineUpdateGate: Promise<void> | null = null
  let releaseRoutineUpdate = () => {}
  let nextRoutineConflict: Routine | null = null
  let nextRoleResourceConflict: RoleResource | null = null
  let failRoutineCompletion = false
  let nextRoutineCompletionConflict: boolean | null = null
  let routineCompletionGate: Promise<void> | null = null
  let releaseRoutineCompletion = () => {}
  let failHandoffCompletion = false
  let nextHandoffCompletionConflict: boolean | null = null
  let handoffCompletionGate: Promise<void> | null = null
  let releaseHandoffCompletion = () => {}
  let workspaceGetGate: Promise<void> | null = null
  let releaseWorkspaceGets = () => {}
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
      ? path === `${SCOPE_PATH}/members`
        ? 'member'
        : path === `${SCOPE_PATH}/roles`
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
      if (workspaceCreationGate) {
        const gate = workspaceCreationGate
        workspaceCreationGate = null
        await gate
      }
      const creationIdempotencyKey = headers['idempotency-key']
      const committedResult = creationIdempotencyKey
        ? workspaceCreationResults.get(creationIdempotencyKey)
        : undefined
      if (committedResult) return json(201, committedResult)
      if (expireWorkspaceCreationReplay) {
        expireWorkspaceCreationReplay = false
        return error(409, 'IDEMPOTENCY_REPLAY_EXPIRED', '이전 요청 결과의 보관 기간이 지났습니다.')
      }
      if (rejectWorkspaceCreationAsInvalidInput) {
        rejectWorkspaceCreationAsInvalidInput = false
        return error(400, 'INVALID_INPUT', '입력한 작업 공간 정보를 확인해 주세요.')
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
    if (contentOperation && contentCreationGate?.operation === contentOperation) {
      const gate = contentCreationGate.pending
      contentCreationGate = null
      await gate
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
      if (workspaceGetGate) await workspaceGetGate
      if (workspaceGetsUnavailable) {
        return error(503, 'WORKSPACE_TEMPORARILY_UNAVAILABLE', '작업 공간을 잠시 불러올 수 없습니다.')
      }
      if (failedGetsRemaining > 0) {
        failedGetsRemaining -= 1
        return error(503, 'WORKSPACE_TEMPORARILY_UNAVAILABLE', '작업 공간을 잠시 불러올 수 없습니다.')
      }
      return json(200, structuredClone(projection))
    }

    if (method === 'PUT' && path === `${SCOPE_PATH}/round-schedule`) {
      const input = body as UpdateRoundScheduleRequest
      const previousCursor = projection.season.roundSchedule?.nextOccurrenceDate
      const updatedSeason = {
        ...projection.season,
        timeZone: input.timeZone.trim(),
        roundSchedule: {
          firstMeetingDate: input.firstMeetingDate,
          meetingTime: serializeLocalTime(input.meetingTime),
          recurrence: input.recurrence,
          generationLeadDays: input.generationLeadDays,
          enabled: input.enabled,
          nextOccurrenceDate: nextScheduleOccurrence(
            input.firstMeetingDate,
            input.recurrence,
            previousCursor,
          ),
        },
      }
      projection.season = updatedSeason
      projection.seasons = projection.seasons.map((season) =>
        season.id === updatedSeason.id ? updatedSeason : season)
      return json(200, updatedSeason)
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
      if (accessKeyRotationGate) await accessKeyRotationGate
      const nextAccessKey = activeAccessKey === ACCESS_KEY
        ? ROTATED_ACCESS_KEY
        : SECOND_ROTATED_ACCESS_KEY
      activeAccessKey = nextAccessKey
      accessKeyRotationResults.set(rotationIdempotencyKey, nextAccessKey)
      if (commitRotationThenTimeout) {
        commitRotationThenTimeout = false
        return error(504, 'ACCESS_KEY_ROTATION_TIMEOUT', '접근 키 변경 응답을 확인하지 못했습니다.')
      }
      return json(200, { accessKey: nextAccessKey })
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/members`) {
      const input = body as CreateMemberRequest
      const normalizedName = input.name.trim()
      if (rejectMemberAsConflict
        || projection.members.some((member) => member.name.trim() === normalizedName)) {
        rejectMemberAsConflict = false
        return error(
          409,
          'MEMBER_NAME_CONFLICT',
          '같은 팀에 동일한 구성원 이름을 사용할 수 없습니다.',
        )
      }
      const created: Member = {
        id: CREATED_MEMBER_ID,
        name: normalizedName,
        initials: [...normalizedName][0] ?? '',
        tone: '#e7d9ef',
        deactivatedAt: null,
      }
      projection.members.push(created)
      return finishContentCreation('member', created)
    }

    const memberDeactivation = path.match(
      new RegExp(`^${SCOPE_PATH}/members/([^/]+)/deactivation$`),
    )
    if (method === 'PATCH' && memberDeactivation) {
      const member = projection.members.find((candidate) => candidate.id === memberDeactivation[1])
      if (!member) return error(404, 'MEMBER_NOT_FOUND', '구성원을 찾을 수 없습니다.')
      member.deactivatedAt = (body as UpdateMemberDeactivationRequest).deactivated
        ? '2026-07-21T12:00:00Z'
        : null
      return json(200, member)
    }

    const memberUpdate = path.match(new RegExp(`^${SCOPE_PATH}/members/([^/]+)$`))
    if (method === 'PUT' && memberUpdate) {
      const member = projection.members.find((candidate) => candidate.id === memberUpdate[1])
      if (!member) return error(404, 'MEMBER_NOT_FOUND', '구성원을 찾을 수 없습니다.')
      const normalizedName = (body as UpdateMemberRequest).name.trim()
      if (projection.members.some((candidate) =>
        candidate.id !== member.id && candidate.name.trim() === normalizedName)) {
        return error(
          409,
          'MEMBER_NAME_CONFLICT',
          '같은 팀에 동일한 구성원 이름을 사용할 수 없습니다.',
        )
      }
      member.name = normalizedName
      member.initials = [...normalizedName][0] ?? ''
      projection.decisions
        .filter((decision) => decision.authorMemberId === member.id)
        .forEach((decision) => {
          decision.authorName = normalizedName
        })
      return json(200, member)
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
      if (nextRoleConflict) {
        projection.roles[roleIndex] = structuredClone(nextRoleConflict)
        nextRoleConflict = null
        return error(409, 'WORKSPACE_CONTENT_CONFLICT', '다른 사용자가 먼저 내용을 변경했습니다.')
      }
      const updated: Role = { id: roleUpdate[1]!, ...(body as UpdateRoleRequest) }
      projection.roles[roleIndex] = updated
      return json(200, updated)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/routines`) {
      const input = body as CreateRoutineRequest
      const created: Routine = {
        id: CREATED_ROUTINE_ID,
        ...input,
        deadlineDayOffset: input.deadlineDayOffset ?? null,
        deadlineTime: input.deadlineTime ? serializeLocalTime(input.deadlineTime) : null,
      }
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
        deadlineAt: routineDeadlineAt(routine, input.meetingDate),
        timingStatus: routine.deadlineDayOffset == null ? 'UNSCHEDULED' : 'PLANNED',
      }))
      const created: SeasonRound = {
        id: CREATED_ROUND_ID,
        name: input.name,
        meetingDate: input.meetingDate,
        routineExecutions,
        archivedAt: null,
        origin: 'MANUAL',
        scheduledOccurrenceDate: null,
        scheduledAt: null,
        timingStatus: 'PLANNED',
      }
      projection.rounds.push(created)
      return finishContentCreation('round', created)
    }

    const roundUpdate = path.match(new RegExp(`^${SCOPE_PATH}/rounds/([^/]+)$`))
    if (method === 'PUT' && roundUpdate) {
      const roundIndex = projection.rounds.findIndex((candidate) => candidate.id === roundUpdate[1])
      if (roundIndex < 0 || projection.rounds[roundIndex]?.archivedAt) {
        return error(404, 'SEASON_ROUND_NOT_FOUND', '회차를 찾을 수 없습니다.')
      }
      const input = body as UpdateSeasonRoundRequest
      if (projection.rounds.some(
        (round) => round.id !== roundUpdate[1] && round.name.trim() === input.name.trim(),
      )) {
        return error(409, 'ROUND_NAME_CONFLICT', '같은 시즌에 동일한 회차 이름을 사용할 수 없습니다.')
      }
      const updated: SeasonRound = {
        ...projection.rounds[roundIndex]!,
        ...input,
      }
      projection.rounds[roundIndex] = updated
      return json(200, updated)
    }

    const roundArchive = path.match(new RegExp(`^${SCOPE_PATH}/rounds/([^/]+)/archive$`))
    if (method === 'PATCH' && roundArchive) {
      const round = projection.rounds.find((candidate) => candidate.id === roundArchive[1])
      if (!round) return error(404, 'SEASON_ROUND_NOT_FOUND', '회차를 찾을 수 없습니다.')
      round.archivedAt = (body as UpdateRecordArchiveRequest).archived
        ? '2026-07-21T12:00:00Z'
        : null
      return json(200, round)
    }

    const routineUpdate = path.match(new RegExp(`^${SCOPE_PATH}/routines/([^/]+)$`))
    if (method === 'PUT' && routineUpdate) {
      const gate = routineUpdateGate
      routineUpdateGate = null
      if (gate) await gate
      const routineIndex = projection.routines.findIndex((candidate) => candidate.id === routineUpdate[1])
      if (routineIndex < 0) return error(404, 'ROUTINE_NOT_FOUND', '루틴을 찾을 수 없습니다.')
      if (nextRoutineConflict) {
        projection.routines[routineIndex] = structuredClone(nextRoutineConflict)
        nextRoutineConflict = null
        return error(409, 'WORKSPACE_CONTENT_CONFLICT', '다른 사용자가 먼저 내용을 변경했습니다.')
      }
      const existing = projection.routines[routineIndex]!
      const input = body as UpdateRoutineRequest
      const updated: Routine = {
        ...existing,
        ...input,
        deadlineTime: input.deadlineTime ? serializeLocalTime(input.deadlineTime) : null,
      }
      projection.routines[routineIndex] = updated
      return json(200, updated)
    }

    const routineCompletion = path.match(new RegExp(
      `^${SCOPE_PATH}/rounds/([^/]+)/routine-executions/([^/]+)/completion$`,
    ))
    if (method === 'PATCH' && routineCompletion) {
      const gate = routineCompletionGate
      routineCompletionGate = null
      if (gate) await gate
      if (failRoutineCompletion) {
        failRoutineCompletion = false
        return error(503, 'ROUTINE_COMPLETION_FAILED', '루틴 상태를 저장하지 못했습니다.')
      }
      const round = projection.rounds.find((candidate) => candidate.id === routineCompletion[1])
      if (!round || round.archivedAt) {
        return error(404, 'SEASON_ROUND_NOT_FOUND', '시즌 회차를 찾을 수 없습니다.')
      }
      const execution = round.routineExecutions.find((candidate) => candidate.id === routineCompletion[2])
      if (!execution) return error(404, 'ROUTINE_EXECUTION_NOT_FOUND', '루틴 실행을 찾을 수 없습니다.')
      if (nextRoutineCompletionConflict !== null) {
        execution.status = nextRoutineCompletionConflict ? 'DONE' : 'WAITING'
        nextRoutineCompletionConflict = null
        return error(409, 'WORKSPACE_CONTENT_CONFLICT', '다른 사용자가 먼저 내용을 변경했습니다.')
      }
      execution.status = (body as { completed: boolean }).completed ? 'DONE' : 'WAITING'
      execution.timingStatus = execution.status === 'DONE'
        ? 'COMPLETED'
        : execution.deadlineAt
          ? 'IN_PROGRESS'
          : 'UNSCHEDULED'
      round.timingStatus = round.routineExecutions.every(
        (candidate) => candidate.timingStatus === 'COMPLETED',
      )
        ? 'COMPLETED'
        : 'IN_PROGRESS'
      return json(200, execution)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/decisions`) {
      const input = body as CreateDecisionRequest
      const author = projection.members.find((member) => member.id === input.authorMemberId)
      const created: Decision = {
        id: CREATED_DECISION_ID,
        title: input.title,
        reason: input.reason,
        alternative: input.alternative,
        createdAt: '2026-07-20T12:00:00Z',
        authorMemberId: input.authorMemberId,
        authorName: author?.name ?? '알 수 없음',
        roleIds: input.roleIds,
        archivedAt: null,
      }
      projection.decisions.unshift(created)
      return finishContentCreation('decision', created)
    }

    const decisionUpdate = path.match(new RegExp(`^${SCOPE_PATH}/decisions/([^/]+)$`))
    if (method === 'PUT' && decisionUpdate) {
      const decisionIndex = projection.decisions.findIndex(
        (candidate) => candidate.id === decisionUpdate[1],
      )
      if (decisionIndex < 0) return error(404, 'DECISION_NOT_FOUND', '결정 기록을 찾을 수 없습니다.')
      const input = body as UpdateDecisionRequest
      const author = projection.members.find((member) => member.id === input.authorMemberId)
      const updated: Decision = {
        ...projection.decisions[decisionIndex]!,
        ...input,
        authorName: author?.name ?? '알 수 없음',
      }
      projection.decisions[decisionIndex] = updated
      return json(200, updated)
    }

    const decisionArchive = path.match(new RegExp(`^${SCOPE_PATH}/decisions/([^/]+)/archive$`))
    if (method === 'PATCH' && decisionArchive) {
      const decision = projection.decisions.find((candidate) => candidate.id === decisionArchive[1])
      if (!decision) return error(404, 'DECISION_NOT_FOUND', '결정 기록을 찾을 수 없습니다.')
      decision.archivedAt = (body as UpdateRecordArchiveRequest).archived
        ? '2026-07-21T12:00:00Z'
        : null
      return json(200, decision)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/handoff-items`) {
      const input = body as CreateHandoffItemRequest
      const created: HandoffItem = {
        id: CREATED_HANDOFF_ID,
        completed: false,
        archivedAt: null,
        ...input,
      }
      projection.handoffItems.push(created)
      return finishContentCreation('handoffItem', created)
    }

    const handoffItemUpdate = path.match(new RegExp(`^${SCOPE_PATH}/handoff-items/([^/]+)$`))
    if (method === 'PUT' && handoffItemUpdate) {
      const itemIndex = projection.handoffItems.findIndex(
        (candidate) => candidate.id === handoffItemUpdate[1],
      )
      if (itemIndex < 0) {
        return error(404, 'HANDOFF_ITEM_NOT_FOUND', '바통 항목을 찾을 수 없습니다.')
      }
      const updated: HandoffItem = {
        ...projection.handoffItems[itemIndex]!,
        ...(body as UpdateHandoffItemRequest),
      }
      projection.handoffItems[itemIndex] = updated
      return json(200, updated)
    }

    const handoffItemArchive = path.match(
      new RegExp(`^${SCOPE_PATH}/handoff-items/([^/]+)/archive$`),
    )
    if (method === 'PATCH' && handoffItemArchive) {
      const item = projection.handoffItems.find(
        (candidate) => candidate.id === handoffItemArchive[1],
      )
      if (!item) return error(404, 'HANDOFF_ITEM_NOT_FOUND', '바통 항목을 찾을 수 없습니다.')
      item.archivedAt = (body as UpdateRecordArchiveRequest).archived
        ? '2026-07-21T12:00:00Z'
        : null
      return json(200, item)
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
      if (failHandoffCompletion) {
        failHandoffCompletion = false
        return error(503, 'HANDOFF_COMPLETION_FAILED', '바통 상태를 저장하지 못했습니다.')
      }
      const item = projection.handoffItems.find((candidate) => candidate.id === handoffCompletion[1])
      if (!item) return error(404, 'HANDOFF_ITEM_NOT_FOUND', '바통 항목을 찾을 수 없습니다.')
      if (nextHandoffCompletionConflict !== null) {
        item.completed = nextHandoffCompletionConflict
        nextHandoffCompletionConflict = null
        return error(409, 'WORKSPACE_CONTENT_CONFLICT', '다른 사용자가 먼저 내용을 변경했습니다.')
      }
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
    addRoundFromAnotherDevice: (round) => {
      projection.rounds.push(structuredClone(round))
    },
    failNextWorkspaceCreation: () => { failWorkspaceCreation = true },
    holdNextWorkspaceCreation: () => {
      workspaceCreationGate = new Promise((resolve) => { releaseWorkspaceCreation = resolve })
    },
    releaseWorkspaceCreation: () => {
      releaseWorkspaceCreation()
      releaseWorkspaceCreation = () => {}
    },
    rejectNextWorkspaceCreationAsInvalidInput: () => { rejectWorkspaceCreationAsInvalidInput = true },
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
    holdAccessKeyRotations: () => {
      accessKeyRotationGate = new Promise((resolve) => { releaseAccessKeyRotations = resolve })
    },
    releaseAccessKeyRotations: () => {
      accessKeyRotationGate = null
      releaseAccessKeyRotations()
      releaseAccessKeyRotations = () => {}
    },
    commitNextContentCreationThenTimeout: (operation) => { contentCreationToCommitThenTimeout = operation },
    rejectNextContentCreationAsReused: (operation) => { contentCreationToRejectAsReused = operation },
    rejectNextMemberAsConflict: () => { rejectMemberAsConflict = true },
    holdNextContentCreation: (operation) => {
      contentCreationGate = {
        operation,
        pending: new Promise((resolve) => { releaseContentCreation = resolve }),
      }
    },
    releaseContentCreation: () => {
      releaseContentCreation()
      releaseContentCreation = () => {}
    },
    failNextWorkspaceGet: () => { failedGetsRemaining = 2 },
    makeWorkspaceGetsUnavailable: () => { workspaceGetsUnavailable = true },
    restoreWorkspaceGets: () => { workspaceGetsUnavailable = false },
    holdNextRoleUpdate: () => {
      roleUpdateGate = new Promise((resolve) => { releaseRoleUpdate = resolve })
    },
    releaseRoleUpdate: () => releaseRoleUpdate(),
    conflictNextRoleUpdate: (role) => {
      nextRoleConflict = structuredClone(role)
    },
    holdNextRoutineUpdate: () => {
      routineUpdateGate = new Promise((resolve) => { releaseRoutineUpdate = resolve })
    },
    releaseRoutineUpdate: () => releaseRoutineUpdate(),
    conflictNextRoutineUpdate: (routine) => {
      nextRoutineConflict = structuredClone(routine)
    },
    conflictNextRoleResourceUpdate: (resource) => {
      nextRoleResourceConflict = structuredClone(resource)
    },
    failNextRoutineCompletion: () => { failRoutineCompletion = true },
    conflictNextRoutineCompletion: (completed) => { nextRoutineCompletionConflict = completed },
    holdNextRoutineCompletion: () => {
      routineCompletionGate = new Promise((resolve) => { releaseRoutineCompletion = resolve })
    },
    releaseRoutineCompletion: () => releaseRoutineCompletion(),
    holdNextHandoffCompletion: () => {
      handoffCompletionGate = new Promise((resolve) => { releaseHandoffCompletion = resolve })
    },
    failNextHandoffCompletion: () => { failHandoffCompletion = true },
    conflictNextHandoffCompletion: (completed) => { nextHandoffCompletionConflict = completed },
    releaseHandoffCompletion: () => releaseHandoffCompletion(),
    holdWorkspaceGets: () => {
      workspaceGetGate = new Promise((resolve) => { releaseWorkspaceGets = resolve })
    },
    releaseWorkspaceGets: () => {
      workspaceGetGate = null
      releaseWorkspaceGets()
    },
  }
}

async function openSharedWorkspace(page: Page) {
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
}

async function openMemberCreationDialog(page: Page) {
  await page.getByRole('button', { name: '구성원 관리' }).click()
  const managementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await managementDialog.getByRole('button', { name: '구성원 추가' }).click()
  return page.getByRole('dialog', { name: '구성원 추가' })
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

async function failNextJournalCleanup(
  page: Page,
  target: { storageKey?: string; storagePrefix?: string },
  failureStateKey: string,
) {
  await page.addInitScript(({ targetStorageKey, targetStoragePrefix, stateKey }) => {
    const originalRemoveItem = Storage.prototype.removeItem
    const originalSetItem = Storage.prototype.setItem
    const matches = (key: string) => key === targetStorageKey
      || Boolean(targetStoragePrefix && key.startsWith(targetStoragePrefix))

    Storage.prototype.removeItem = function removeItem(key) {
      if (matches(key) && sessionStorage.getItem(stateKey) === null) {
        sessionStorage.setItem(stateKey, 'remove-failed')
        throw new DOMException('Storage removal disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
    Storage.prototype.setItem = function setItem(key, value) {
      if (matches(key)
        && value === 'null'
        && sessionStorage.getItem(stateKey) === 'remove-failed') {
        sessionStorage.setItem(stateKey, 'complete')
        throw new DOMException('Storage tombstone disabled', 'SecurityError')
      }
      originalSetItem.call(this, key, value)
    }
  }, {
    targetStorageKey: target.storageKey,
    targetStoragePrefix: target.storagePrefix,
    stateKey: failureStateKey,
  })
}

async function failNextAccessKeyRotationCleanup(page: Page) {
  await failNextJournalCleanup(
    page,
    { storageKey: PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY },
    'baton-e2e-access-key-cleanup-failure',
  )
}

type PendingWorkspaceCreationEntry = {
  normalizedPayload: string
  idempotencyKey: string
  createdAt: number
}

function pendingWorkspaceCreationEntry(
  request: CreateWorkspaceRequest,
  sequence: number,
  createdAt = sequence,
): PendingWorkspaceCreationEntry {
  return {
    idempotencyKey: fixtureUuid(sequence),
    createdAt,
    normalizedPayload: JSON.stringify({
      teamName: request.teamName.trim(),
      seasonName: request.seasonName.trim(),
      startDate: request.startDate.trim(),
      endDate: request.endDate.trim(),
      memberNames: request.memberNames.map((name) => name.trim()).sort(),
    }),
  }
}

async function seedPendingWorkspaceCreations(
  page: Page,
  entries: PendingWorkspaceCreationEntry[],
  marker: string,
) {
  await page.addInitScript(({ prefix, pendingEntries, seedMarker }) => {
    if (sessionStorage.getItem(seedMarker) !== null) return
    pendingEntries.forEach((entry) => {
      localStorage.setItem(`${prefix}${entry.idempotencyKey}`, JSON.stringify(entry))
    })
    sessionStorage.setItem(seedMarker, 'seeded')
  }, {
    prefix: PENDING_CREATION_STORAGE_PREFIX,
    pendingEntries: entries,
    seedMarker: marker,
  })
}

async function fillOnboardingForm(page: Page, request: CreateWorkspaceRequest) {
  await page.getByLabel('팀 이름').fill(request.teamName)
  await page.getByLabel('시즌 이름').fill(request.seasonName)
  await page.getByLabel('시작일').fill(request.startDate)
  await page.getByLabel('종료일').fill(request.endDate)
  await page.getByLabel('구성원 이름').fill(request.memberNames.join('\n'))
}

async function pendingCreationEntries(page: Page) {
  return page.evaluate((prefix) => {
    const entries: PendingWorkspaceCreationEntry[] = []
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

async function expectPendingCreationDialogLocked({
  api,
  dialog,
  operation,
  page,
  submitLabel,
}: {
  api: ApiHarness
  dialog: Locator
  operation: ContentCreationOperation
  page: Page
  submitLabel: string
}) {
  const path = CONTENT_CREATION_PATHS[operation]
  const callCount = () => api.calls.filter(
    (call) => call.method === 'POST' && call.path === path,
  ).length
  const callsBeforeSubmit = callCount()
  api.holdNextContentCreation(operation)

  try {
    await dialog.getByRole('button', { name: submitLabel })
      .evaluate((button: HTMLButtonElement) => {
        button.click()
        button.click()
        button.closest('form')?.querySelector<HTMLButtonElement>('button[type="button"]')?.click()
        document.dispatchEvent(new KeyboardEvent('keydown', {
          bubbles: true,
          cancelable: true,
          key: 'Escape',
        }))
      })
    await expect(dialog).toBeVisible()
    await expect.poll(callCount).toBe(callsBeforeSubmit + 1)
    const createCall = [...api.calls].reverse().find(
      (call) => call.method === 'POST' && call.path === path,
    )
    expect(createCall).toBeTruthy()

    await expect(dialog).toHaveAttribute('aria-busy', 'true')
    const closeButton = dialog.getByRole('button', { name: '닫기' })
    await expect(closeButton).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '취소' })).toBeDisabled()
    await expect(dialog.locator('button[type="submit"]')).toBeDisabled()

    await page.keyboard.press('Escape')
    await expect(dialog).toBeVisible()
    await closeButton.click({ force: true })
    await expect(dialog).toBeVisible()
    await page.locator('.modal-backdrop').click({ position: { x: 5, y: 5 }, force: true })
    await expect(dialog).toBeVisible()
    expect(callCount()).toBe(callsBeforeSubmit + 1)

    const pendingEntries = (await pendingContentCreationEntries(page))
      .filter((entry) => entry.operation === operation)
    expect(pendingEntries).toEqual([
      expect.objectContaining({
        idempotencyKey: createCall?.headers['idempotency-key'],
      }),
    ])
  } finally {
    api.releaseContentCreation()
  }

  await expect(dialog).toHaveCount(0)
  expect(callCount()).toBe(callsBeforeSubmit + 1)
  await expect.poll(async () => (await pendingContentCreationEntries(page))
    .filter((entry) => entry.operation === operation).length).toBe(0)
}

test.describe('조직 달력 날짜 경계', () => {
  test.use({ timezoneId: 'UTC' })

  test('@smoke 시즌 첫날은 경과한 주 없이 시작한다', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium', '데스크톱 사이드바에서만 표시되는 진행률입니다.')
    await page.clock.setFixedTime(new Date('2026-07-02T14:00:00Z'))
    await installApi(page)
    await openSharedWorkspace(page)

    await expect(page.locator('.season-mini strong')).toHaveText('0 / 11주')
  })

  test('@smoke 브라우저가 UTC여도 시즌 시간대로 오늘 날짜를 표시한다', async ({ page }) => {
    const projection = makeProjection()
    projection.season = {
      ...projection.season,
      timeZone: 'America/New_York',
    }
    projection.seasons = projection.seasons.map((season) => ({
      ...season,
      timeZone: 'America/New_York',
    }))
    await page.clock.setFixedTime(new Date('2026-07-02T02:00:00Z'))
    await installApi(page, projection)
    await openSharedWorkspace(page)

    await expect(page.locator('.main-surface .page-header .eyebrow')).toHaveText(
      '7월 1일 수요일 · 2026 여름 시즌',
    )
  })

  test('@smoke 하루짜리 시즌은 해당 날짜에 완료 진행률을 표시한다', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium', '데스크톱 사이드바에서만 표시되는 진행률입니다.')
    const projection = makeProjection()
    projection.season = {
      ...projection.season,
      startDate: '2026-07-02',
      endDate: '2026-07-02',
    }
    await page.clock.setFixedTime(new Date('2026-07-01T15:00:00Z'))
    await installApi(page, projection)
    await openSharedWorkspace(page)

    await expect(page.locator('.season-mini strong')).toHaveText('1 / 1주')
    await expect(page.locator('.season-mini .mini-progress > span')).toHaveAttribute(
      'style',
      'width: 100%;',
    )
  })

  test('@smoke 일반 시즌은 종료일에 전체 진행률을 표시한다', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium', '데스크톱 사이드바에서만 표시되는 진행률입니다.')
    await page.clock.setFixedTime(new Date('2026-09-16T15:00:00Z'))
    await installApi(page)
    await openSharedWorkspace(page)

    await expect(page.locator('.season-mini strong')).toHaveText('11 / 11주')
    await expect(page.locator('.season-mini .mini-progress > span')).toHaveAttribute(
      'style',
      'width: 100%;',
    )
  })

  test('@smoke 한국 날짜가 종료일 다음 날이면 지난 시즌으로 표시한다', async ({ page }, testInfo) => {
    await page.clock.setFixedTime(new Date('2026-09-17T15:30:00Z'))
    await installApi(page)
    await openSharedWorkspace(page)

    await expect(page.locator('.main-surface .page-header .eyebrow')).toHaveText(
      '9월 18일 금요일 · 2026 여름 시즌',
    )
    await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()

    const pageHeader = page.locator('.main-surface .page-header')
    await expect(pageHeader.locator('.eyebrow')).toHaveText('2026. 9. 17. 시즌 종료')
    await expect(pageHeader).not.toContainText('시즌 종료까지 0일')
  })
})

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

test('@smoke 온보딩 입력 한도를 서버 호출 전에 안내한다', async ({ page }) => {
  const api = await installApi(page)
  await page.goto('/')

  await expect(page.getByLabel('팀 이름')).toHaveAttribute('maxlength', '100')
  await expect(page.getByLabel('시즌 이름')).toHaveAttribute('maxlength', '100')
  await page.getByLabel('팀 이름').fill('   ')
  await page.getByLabel('시즌 이름').fill('2027 가을 시즌')
  await page.getByLabel('시작일').fill('2027-09-01')
  await page.getByLabel('종료일').fill('2027-11-30')
  await page.getByLabel('구성원 이름').fill('박민서')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('팀 이름을 입력해 주세요.')

  await page.getByLabel('팀 이름').fill('입력 한도 스터디')
  await page.getByLabel('시즌 이름').fill('   ')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('시즌 이름을 입력해 주세요.')

  await page.getByLabel('시즌 이름').fill('2027 가을 시즌')
  await page.getByLabel('구성원 이름').fill(
    Array.from({ length: 101 }, (_, index) => `구성원 ${index + 1}`).join('\n'),
  )
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('구성원은 최대 100명까지 입력해 주세요.')
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(0)
  expect(await pendingCreationEntries(page)).toHaveLength(0)

  await page.getByLabel('구성원 이름').fill('가'.repeat(101))
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('구성원 이름은 각각 100자 이하로 입력해 주세요.')
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

test('@smoke 서버 입력 오류 뒤 온보딩 pending을 지우고 다음 시도에 새 멱등 키를 사용한다', async ({ page }) => {
  const api = await installApi(page)
  api.rejectNextWorkspaceCreationAsInvalidInput()
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('입력 수정 스터디')
  await page.getByLabel('시즌 이름').fill('2027 겨울 시즌')
  await page.getByLabel('시작일').fill('2027-12-01')
  await page.getByLabel('종료일').fill('2028-02-29')
  await page.getByLabel('구성원 이름').fill('박민서\n김준호')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('입력한 작업 공간 정보를 확인해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.body).toEqual(firstAttempt.body)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
  expect(await pendingCreationEntries(page)).toHaveLength(0)
})

test('@smoke 불러온 온보딩 복구 요청이 입력 오류로 거절되면 새 요청을 바로 시작할 수 있다', async ({ page }) => {
  const request: CreateWorkspaceRequest = {
    teamName: '복구 입력 정정 스터디',
    seasonName: '2028 봄 시즌',
    startDate: '2028-03-01',
    endDate: '2028-05-31',
    memberNames: ['박민서'],
  }
  const pendingEntry = pendingWorkspaceCreationEntry(request, 970)
  await seedPendingWorkspaceCreations(
    page,
    [pendingEntry],
    'baton-e2e-terminal-recovery-workspace-seeded',
  )
  const api = await installApi(page)
  api.rejectNextWorkspaceCreationAsInvalidInput()
  await page.goto('/')

  const pendingRegion = page.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await pendingRegion.getByText('확인하지 못한 생성 요청 1개').click()
  await pendingRegion.getByRole('button', {
    name: `${request.teamName} ${request.seasonName} 저장된 입력 불러오기`,
  }).click()
  await page.getByRole('button', { name: '같은 생성 결과 확인하기' }).click()

  await expect(page.getByRole('alert')).toContainText('입력한 작업 공간 정보를 확인해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(firstAttempt.headers['idempotency-key']).toBe(pendingEntry.idempotencyKey)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
  await expect(page.getByText('작업 공간이 이미 만들어졌을 수 있으니')).toHaveCount(0)

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(pendingEntry.idempotencyKey)
})

test('@smoke 온보딩 terminal 기록 cleanup이 실패하면 재전송 전에 정리를 요구한다', async ({ page }) => {
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CREATION_STORAGE_PREFIX },
    'baton-e2e-workspace-terminal-cleanup-failure',
  )
  const api = await installApi(page)
  api.rejectNextWorkspaceCreationAsInvalidInput()
  await page.goto('/')
  await fillOnboardingForm(page, {
    teamName: '완료 기록 정리 스터디',
    seasonName: '2028 여름 시즌',
    startDate: '2028-06-01',
    endDate: '2028-08-31',
    memberNames: ['박민서'],
  })

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByText(
    '이전 생성 요청의 완료 기록을 정리하지 못했습니다. 브라우저 저장을 허용한 뒤 완료 기록 정리를 다시 확인해 주세요.',
    { exact: true },
  )).toBeVisible()
  await expect(page.getByRole('button', { name: '완료 기록 정리 필요' })).toBeDisabled()
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(await pendingCreationEntries(page)).toHaveLength(1)

  await page.getByRole('button', { name: '완료 기록 정리 다시 확인' }).click()

  await expect(page.getByRole('alert')).toContainText('이전 생성 요청의 완료 기록을 정리했습니다.')
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@smoke 만료된 온보딩 멱등 기록은 기존 결과 확인 전 새 요청을 막는다', async ({ page }) => {
  const request: CreateWorkspaceRequest = {
    teamName: '재시작 스터디',
    seasonName: '2027 여름 시즌',
    startDate: '2027-06-01',
    endDate: '2027-08-31',
    memberNames: ['박민서', '김준호'],
  }
  const pendingEntry = pendingWorkspaceCreationEntry(request, 971)
  await seedPendingWorkspaceCreations(
    page,
    [pendingEntry],
    'baton-e2e-expired-workspace-replay-seeded',
  )
  const api = await installApi(page)
  api.expireNextWorkspaceCreationReplay()
  await page.goto('/')

  await fillOnboardingForm(page, request)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('작업 공간이 이미 만들어졌을 수 있으니 운영자나 기존 공유 링크를 먼저 확인해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(firstAttempt.headers['idempotency-key']).toBe(pendingEntry.idempotencyKey)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
  await expect(page.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)

  await page.getByLabel('팀 이름').fill(`${request.teamName} 수정`)
  await expect(page.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  await expect(page.getByText(
    '이전 생성 요청으로 만든 작업 공간의 접근 키가 이미 변경되어 결과를 다시 받을 수 없습니다.',
  )).toBeVisible()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)

  await page.getByRole('button', {
    name: '기존 결과를 확인했고 새 요청으로 전환',
  }).click()
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@smoke 만료된 온보딩 결과 확인은 다른 복구 snapshot을 불러와도 유지된다', async ({ page }) => {
  const expiredRequest: CreateWorkspaceRequest = {
    teamName: '만료 확인 유지 스터디',
    seasonName: '2029 여름 시즌',
    startDate: '2029-06-01',
    endDate: '2029-08-31',
    memberNames: ['박민서'],
  }
  const otherRequest: CreateWorkspaceRequest = {
    teamName: '다른 복구 스터디',
    seasonName: '2029 가을 시즌',
    startDate: '2029-09-01',
    endDate: '2029-11-30',
    memberNames: ['김준호'],
  }
  await seedPendingWorkspaceCreations(
    page,
    [
      pendingWorkspaceCreationEntry(expiredRequest, 973),
      pendingWorkspaceCreationEntry(otherRequest, 974),
    ],
    'baton-e2e-expired-confirmation-survives-load',
  )
  const api = await installApi(page)
  api.expireNextWorkspaceCreationReplay()
  await page.goto('/')
  await fillOnboardingForm(page, expiredRequest)

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  const pendingRegion = page.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await pendingRegion.getByText('확인하지 못한 생성 요청 1개').click()
  await pendingRegion.getByRole('button', {
    name: `${otherRequest.teamName} ${otherRequest.seasonName} 저장된 입력 불러오기`,
  }).click()

  await expect(page.getByLabel('팀 이름')).toHaveValue(otherRequest.teamName)
  await expect(page.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  await expect(page.getByText(
    '이전 생성 요청으로 만든 작업 공간의 접근 키가 이미 변경되어 결과를 다시 받을 수 없습니다.',
  )).toBeVisible()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)
})

test('@smoke 서로 다른 탭의 생성 pending을 순서대로 보존하고 응답 유실 뒤 같은 키로 복구한다', async ({ page, context }) => {
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
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('alert')).toContainText('작업 공간을 잠시 만들 수 없습니다.')
  await pageB.getByRole('button', { name: '작업 공간 만들기' }).click()
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

test('@smoke 생성 계약을 벗어난 v3 온보딩 pending을 정리하고 정상 생성한다', async ({ page }) => {
  const baseRequest: CreateWorkspaceRequest = {
    teamName: '오래된 스터디',
    seasonName: '2028 과거 시즌',
    startDate: '2028-01-01',
    endDate: '2028-03-31',
    memberNames: ['기존 구성원'],
  }
  const invalidRequests: CreateWorkspaceRequest[] = [
    { ...baseRequest, teamName: '가'.repeat(101) },
    { ...baseRequest, seasonName: '나'.repeat(101) },
    { ...baseRequest, memberNames: ['다'.repeat(101)] },
    {
      ...baseRequest,
      memberNames: Array.from(
        { length: 101 },
        (_, index) => `구성원 ${String(index + 1).padStart(3, '0')}`,
      ),
    },
    { ...baseRequest, startDate: '2028-02-30' },
    { ...baseRequest, startDate: '2028-04-01', endDate: '2028-03-31' },
  ]
  const invalidEntries = invalidRequests.map((request, index) =>
    pendingWorkspaceCreationEntry(request, 901 + index, index + 1))
  await seedPendingWorkspaceCreations(
    page,
    invalidEntries,
    'baton-e2e-invalid-pending-workspace-seeded',
  )

  const api = await installApi(page)
  await page.goto('/')
  await page.getByLabel('팀 이름').fill('저장소 정리 스터디')
  await page.getByLabel('시즌 이름').fill('2028 봄 시즌')
  await page.getByLabel('시작일').fill('2028-03-01')
  await page.getByLabel('종료일').fill('2028-05-31')
  await page.getByLabel('구성원 이름').fill('박민서')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(1)
  expect(invalidEntries.map((entry) => entry.idempotencyKey))
    .not.toContain(attempts[0]?.headers['idempotency-key'])
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
})

test('@smoke 탭 간 생성 잠금을 지원하지 않으면 온보딩 요청을 전송하지 않는다', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: undefined,
    })
  })
  const api = await installApi(page)
  await page.goto('/')
  await fillOnboardingForm(page, {
    teamName: '안전 잠금 확인 스터디',
    seasonName: '2028 가을 시즌',
    startDate: '2028-09-01',
    endDate: '2028-11-30',
    memberNames: ['박민서'],
  })

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('탭 사이의 생성 요청을 안전하게 조정할 수 없습니다.')
  expect(api.calls.filter((call) => call.path === '/api/v1/workspaces')).toHaveLength(0)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
})

test('@smoke 저장된 온보딩 입력으로 같은 멱등 생성 결과를 확인한다', async ({ page }) => {
  const pendingRequest: CreateWorkspaceRequest = {
    teamName: '응답 확인 스터디',
    seasonName: '2028 가을 시즌',
    startDate: '2028-09-01',
    endDate: '2028-11-30',
    memberNames: ['박민서', '김준호'],
  }
  const pendingEntry = pendingWorkspaceCreationEntry(
    pendingRequest,
    951,
    Date.UTC(2028, 8, 1, 9),
  )
  await seedPendingWorkspaceCreations(
    page,
    [pendingEntry],
    'baton-e2e-recover-pending-workspace-seeded',
  )

  const api = await installApi(page)
  await page.goto('/')

  const pendingRegion = page.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await pendingRegion.getByText('확인하지 못한 생성 요청 1개').click()
  await expect(pendingRegion.getByText(pendingRequest.teamName)).toBeVisible()
  await pendingRegion.getByRole('button', {
    name: `${pendingRequest.teamName} ${pendingRequest.seasonName} 저장된 입력 불러오기`,
  }).click()

  await expect(page.getByLabel('팀 이름')).toHaveValue(pendingRequest.teamName)
  await expect(page.getByLabel('시즌 이름')).toHaveValue(pendingRequest.seasonName)
  await expect(page.getByLabel('시작일')).toHaveValue(pendingRequest.startDate)
  await expect(page.getByLabel('종료일')).toHaveValue(pendingRequest.endDate)
  await expect(page.getByLabel('구성원 이름')).toHaveValue(
    [...pendingRequest.memberNames].sort().join('\n'),
  )
  const loadedNotice = page.getByText('저장된 입력을 불러왔습니다. 필요한 생성 코드를 입력한 뒤 같은 생성 결과를 확인해 주세요.')
  await expect(loadedNotice).toBeVisible()
  expect(api.calls.filter((call) => call.path === '/api/v1/workspaces')).toHaveLength(0)

  await page.getByLabel('팀 이름').fill(`${pendingRequest.teamName} 수정`)
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeVisible()
  await expect(loadedNotice).toBeHidden()
  await page.getByLabel('팀 이름').fill(pendingRequest.teamName)

  await page.getByRole('button', { name: '같은 생성 결과 확인하기' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(1)
  expect(attempts[0]?.headers['idempotency-key']).toBe(pendingEntry.idempotencyKey)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
})

test('@smoke 불러온 온보딩 snapshot이 바뀌면 명시적 확인 전 새 요청으로 전환하지 않는다', async ({ page }) => {
  const request: CreateWorkspaceRequest = {
    teamName: 'snapshot 확인 스터디',
    seasonName: '2029 봄 시즌',
    startDate: '2029-03-01',
    endDate: '2029-05-31',
    memberNames: ['박민서'],
  }
  const pendingEntry = pendingWorkspaceCreationEntry(request, 972)
  await seedPendingWorkspaceCreations(
    page,
    [pendingEntry],
    'baton-e2e-changed-workspace-snapshot-seeded',
  )
  const api = await installApi(page)
  await page.goto('/')

  const pendingRegion = page.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await pendingRegion.getByText('확인하지 못한 생성 요청 1개').click()
  await pendingRegion.getByRole('button', {
    name: `${request.teamName} ${request.seasonName} 저장된 입력 불러오기`,
  }).click()

  await page.evaluate(({ prefix, entry }) => {
    localStorage.setItem(`${prefix}${entry.idempotencyKey}`, JSON.stringify({
      ...entry,
      normalizedPayload: JSON.stringify({
        ...JSON.parse(entry.normalizedPayload),
        teamName: '다른 탭이 바꾼 snapshot',
      }),
    }))
  }, { prefix: PENDING_CREATION_STORAGE_PREFIX, entry: pendingEntry })
  await page.getByRole('button', { name: '같은 생성 결과 확인하기' }).click()

  await expect(page.getByRole('alert')).toContainText('다른 탭에서 복구 기록이 변경됐습니다.')
  await expect(page.getByText('이 입력의 복구 기록이 다른 탭에서 변경되었습니다.')).toBeVisible()
  await expect(page.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(0)

  await page.getByRole('button', {
    name: '기존 결과를 확인했고 새 요청으로 전환',
  }).click()
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
})

test('@smoke 다른 탭이 생성 결과를 확인하는 동안 온보딩 pending 폐기를 막는다', async ({ page, context }) => {
  const pendingRequest: CreateWorkspaceRequest = {
    teamName: '다중 탭 복구 스터디',
    seasonName: '2028 겨울 시즌',
    startDate: '2028-12-01',
    endDate: '2029-02-28',
    memberNames: ['박민서'],
  }
  const pendingEntry = pendingWorkspaceCreationEntry(
    pendingRequest,
    952,
    Date.UTC(2028, 11, 1, 9),
  )
  await seedPendingWorkspaceCreations(
    page,
    [pendingEntry],
    'baton-e2e-lock-pending-workspace-seeded',
  )

  const api = await installApi(page)
  api.holdNextWorkspaceCreation()
  await page.goto('/')

  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  await peerPage.goto('/')

  const pendingLabel = `${pendingRequest.teamName} ${pendingRequest.seasonName}`
  const pageRegion = page.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await pageRegion.getByText('확인하지 못한 생성 요청 1개').click()
  await pageRegion.getByRole('button', {
    name: `${pendingLabel} 저장된 입력 불러오기`,
  }).click()
  await page.getByRole('button', { name: '같은 생성 결과 확인하기' }).click()
  await expect.poll(() =>
    api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces').length,
  ).toBe(1)

  const peerRegion = peerPage.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await peerRegion.getByText('확인하지 못한 생성 요청 1개').click()
  await peerRegion.getByRole('button', {
    name: `${pendingLabel} 저장된 입력 불러오기`,
  }).click()
  await expect(peerPage.getByRole('button', { name: '같은 생성 결과 확인하기' })).toBeVisible()
  await peerRegion.getByRole('button', {
    name: `${pendingLabel} 복구 기록 폐기`,
  }).click()
  await peerRegion.getByRole('group', {
    name: '이 복구 기록을 폐기할까요?',
  }).getByRole('button', { name: '확인하고 폐기' }).click()

  await expect(peerRegion.getByRole('alert')).toContainText('다른 탭에서 작업 공간 생성 결과를 확인 중입니다.')
  expect(await pendingCreationEntries(peerPage)).toEqual([pendingEntry])

  api.releaseWorkspaceCreation()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect.poll(async () => (await pendingCreationEntries(peerPage)).length).toBe(0)
  await expect(peerPage.getByText('이 입력의 복구 기록이 다른 탭에서 확인되었거나 폐기되었습니다.')).toBeVisible()
  await expect(peerPage.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  await expect(peerPage.getByRole('link', { name: new RegExp(pendingRequest.teamName) })).toBeVisible()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)

  await peerPage.getByRole('button', {
    name: '기존 결과를 확인했고 새 요청으로 전환',
  }).click()
  await expect(peerPage.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)
})

test('@smoke 같은 신규 온보딩 요청의 탭 경합은 결과 확인 전 재제출을 막는다', async ({ page, context }) => {
  const request: CreateWorkspaceRequest = {
    teamName: '동시 시작 스터디',
    seasonName: '2029 봄 시즌',
    startDate: '2029-03-01',
    endDate: '2029-05-31',
    memberNames: ['박민서'],
  }
  const api = await installApi(page)
  api.holdNextWorkspaceCreation()
  await page.goto('/')
  await fillOnboardingForm(page, request)

  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  await peerPage.goto('/')
  await fillOnboardingForm(peerPage, request)

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect.poll(() =>
    api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces').length,
  ).toBe(1)
  await peerPage.getByRole('button', { name: '작업 공간 만들기' }).click()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)

  api.releaseWorkspaceCreation()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(peerPage.getByRole('button', { name: '기존 결과 확인 필요' })).toBeDisabled()
  await expect(peerPage.getByRole('link', { name: new RegExp(request.teamName) })).toBeVisible()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)

  await peerPage.getByRole('button', {
    name: '기존 결과를 확인했고 새 요청으로 전환',
  }).click()
  await expect(peerPage.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)
})

test('@smoke 온보딩 pending 한 건을 확인 후 폐기하고 새 작업 공간을 만든다', async ({ page }) => {
  const pendingRequests = Array.from({ length: 5 }, (_, index): CreateWorkspaceRequest => ({
    teamName: `복구 대기 스터디 ${index + 1}`,
    seasonName: `2028 봄 시즌 ${index + 1}`,
    startDate: '2028-03-01',
    endDate: '2028-05-31',
    memberNames: [`구성원 ${index + 1}`],
  }))
  const pendingEntries = pendingRequests.map((request, index) =>
    pendingWorkspaceCreationEntry(request, 961 + index, Date.UTC(2028, 2, index + 1, 9)))
  const seededKeys = pendingEntries.map((entry) => entry.idempotencyKey).sort()
  await seedPendingWorkspaceCreations(
    page,
    pendingEntries,
    'baton-e2e-discard-pending-workspace-seeded',
  )

  const api = await installApi(page)
  await page.goto('/')

  const pendingRegion = page.getByRole('region', {
    name: '확인되지 않은 작업 공간 생성 요청',
  })
  await expect(pendingRegion.getByRole('listitem')).toHaveCount(5)

  const newRequest: CreateWorkspaceRequest = {
    teamName: '새로운 스터디',
    seasonName: '2028 여름 시즌',
    startDate: '2028-06-01',
    endDate: '2028-08-31',
    memberNames: ['박민서'],
  }
  await fillOnboardingForm(page, newRequest)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText('확인하지 못한 생성 요청이 5개 남아')
  expect(api.calls.filter((call) => call.path === '/api/v1/workspaces')).toHaveLength(0)
  await expect.poll(async () =>
    (await pendingCreationEntries(page))
      .map((entry) => entry.idempotencyKey)
      .sort(),
  ).toEqual(seededKeys)

  const firstRequest = pendingRequests[0]!
  const firstItem = pendingRegion.getByRole('listitem').filter({ hasText: firstRequest.teamName })
  const discardButtonName = `${firstRequest.teamName} ${firstRequest.seasonName} 복구 기록 폐기`
  await firstItem.getByRole('button', { name: discardButtonName }).click()
  const confirmation = firstItem.getByRole('group', { name: '이 복구 기록을 폐기할까요?' })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '계속 보관' }).click()
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(5)

  await firstItem.getByRole('button', { name: discardButtonName }).click()
  await confirmation.getByRole('button', { name: '확인하고 폐기' }).click()

  const remainingKeys = seededKeys.filter((key) => key !== pendingEntries[0]?.idempotencyKey)
  await expect(pendingRegion.getByRole('listitem')).toHaveCount(4)
  await expect.poll(async () =>
    (await pendingCreationEntries(page))
      .map((entry) => entry.idempotencyKey)
      .sort(),
  ).toEqual(remainingKeys)

  await page.reload()
  await expect.poll(async () =>
    (await pendingCreationEntries(page))
      .map((entry) => entry.idempotencyKey)
      .sort(),
  ).toEqual(remainingKeys)
  await fillOnboardingForm(page, newRequest)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(1)
  expect(seededKeys).not.toContain(attempts[0]?.headers['idempotency-key'])
  await expect.poll(async () =>
    (await pendingCreationEntries(page))
      .map((entry) => entry.idempotencyKey)
      .sort(),
  ).toEqual(remainingKeys)
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

test('@smoke 기존 팀에 구성원을 추가하고 중복과 응답 유실을 안전하게 처리한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const memberPath = `${SCOPE_PATH}/members`
  const openMemberDialog = async () => {
    return openMemberCreationDialog(page)
  }

  const dialog = await openMemberDialog()
  const nameInput = dialog.getByLabel('구성원 이름')
  await expect(nameInput).toBeFocused()
  await nameInput.fill(' 박민서 ')
  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText(
    '이미 등록된 구성원 이름입니다. 같은 이름이면 구분할 별칭을 붙여 주세요.',
  )
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === memberPath))
    .toHaveLength(0)

  api.rejectNextMemberAsConflict()
  await nameInput.fill('서버 충돌 구성원')
  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText(
    '이미 등록된 구성원 이름입니다. 같은 이름이면 구분할 별칭을 붙여 주세요.',
  )
  const conflictCall = await recordedCall(api, 'POST', memberPath)
  expectScopedCall(conflictCall, { name: '서버 충돌 구성원' })

  api.commitNextContentCreationThenTimeout('member')
  await nameInput.fill('이서준(응답 복구)')
  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('같은 요청으로 안전하게 확인합니다.')
  await expect(dialog.getByText(/이전에 저장 결과를 확인하지 못한 요청/)).toHaveCount(0)

  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('status')).toContainText(
    '이서준(응답 복구)님을 팀 구성원으로 추가했어요.',
  )

  const replayCalls = api.calls.filter(
    (call) => call.method === 'POST'
      && call.path === memberPath
      && (call.body as CreateMemberRequest | undefined)?.name === '이서준(응답 복구)',
  )
  expect(replayCalls).toHaveLength(2)
  expect(replayCalls[0]?.headers['idempotency-key']).toBe(
    replayCalls[1]?.headers['idempotency-key'],
  )
  expect(api.projection().members.filter((member) => member.name === '이서준(응답 복구)'))
    .toHaveLength(1)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await expect(roleDialog.getByLabel('현재 담당자').getByRole('option', {
    name: '이서준(응답 복구)',
  })).toHaveCount(1)
})

test('@smoke 구성원 표시 이름과 활동 상태를 관리하고 기존 기록만 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const manageMembersButton = page.getByRole('button', { name: '구성원 관리' })
  await manageMembersButton.click()
  const managementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(managementDialog.getByRole('list', { name: '팀 구성원' }))
    .toContainText('박민서')

  await managementDialog.getByRole('button', { name: '박민서 이름 수정' }).click()
  const editDialog = page.getByRole('dialog', { name: '구성원 이름 수정' })
  await expect(editDialog.getByLabel('구성원 이름')).toHaveValue('박민서')
  await editDialog.getByLabel('구성원 이름').fill('박민서(리드)')
  await editDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(managementDialog).toBeVisible()
  await expect(managementDialog.getByText('박민서(리드)', { exact: true })).toBeVisible()
  expectScopedCall(
    await recordedCall(api, 'PUT', `${SCOPE_PATH}/members/${MEMBER_ONE_ID}`),
    { name: '박민서(리드)' },
  )

  const deactivateButton = managementDialog
    .getByRole('button', { name: '박민서(리드) 활동 종료' })
  await deactivateButton.click()
  await expect(page.getByRole('status')).toContainText(
    '박민서(리드)님의 활동을 종료했어요.',
  )
  expectScopedCall(
    await recordedCall(
      api,
      'PATCH',
      `${SCOPE_PATH}/members/${MEMBER_ONE_ID}/deactivation`,
    ),
    { deactivated: true },
  )

  const reactivateButton = managementDialog
    .getByRole('button', { name: '박민서(리드) 다시 활성화' })
  await expect(reactivateButton).toBeFocused()
  await expect(managementDialog.getByRole('list', { name: '팀 구성원' }))
    .toContainText('활동 종료')
  await managementDialog.getByRole('button', { name: '닫기' }).click()

  const roleRow = page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' })
  await expect(roleRow).toContainText('박민서(리드) · 활동 종료')
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleEditDialog = page.getByRole('dialog', { name: '역할 수정' })
  const retainedOwner = roleEditDialog.getByLabel('현재 담당자').getByRole('option', {
    name: '박민서(리드) (활동 종료 · 기존 선택)',
  })
  await expect(roleEditDialog.getByLabel('현재 담당자')).toHaveValue(MEMBER_ONE_ID)
  await expect(retainedOwner).toHaveAttribute('disabled', '')
  await roleEditDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '역할 추가' }).click()
  const newRoleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await expect(newRoleDialog.getByLabel('현재 담당자').getByRole('option', {
    name: /박민서\(리드\)/,
  })).toHaveCount(0)
  await newRoleDialog.getByRole('button', { name: '닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  const decisionTitle = '한 회차의 문제 수를 5개로 정한다'
  await expect(page.getByRole('article').filter({
    has: page.getByRole('heading', { name: decisionTitle }),
  }).getByText('박민서(리드)', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: `${decisionTitle} 수정` }).click()
  const decisionEditDialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  const retainedAuthor = decisionEditDialog.getByLabel('작성자').getByRole('option', {
    name: '박민서(리드) (활동 종료 · 기존 작성자)',
  })
  await expect(decisionEditDialog.getByLabel('작성자')).toHaveValue(MEMBER_ONE_ID)
  await expect(retainedAuthor).toHaveAttribute('disabled', '')
  await decisionEditDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '결정 남기기', exact: true }).click()
  const newDecisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await expect(newDecisionDialog.getByLabel('작성자').getByRole('option', {
    name: /박민서\(리드\)/,
  })).toHaveCount(0)
  await newDecisionDialog.getByRole('button', { name: '닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await manageMembersButton.click()
  await managementDialog.getByRole('button', { name: '박민서(리드) 다시 활성화' }).click()
  await expect(page.getByRole('status')).toContainText('박민서(리드)님을 다시 활성화했어요.')
  await expect(managementDialog.getByRole('button', {
    name: '박민서(리드) 활동 종료',
  })).toBeFocused()
  await managementDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '역할 추가' }).click()
  await expect(page.getByRole('dialog', { name: '새 역할 만들기' })
    .getByLabel('현재 담당자')
    .getByRole('option', { name: '박민서(리드)' })).toBeEnabled()
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
  await expect(shareButton).toBeFocused()

  const manageAccessButton = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar').getByRole('button', { name: '키 관리' })
    : page.locator('.sidebar').getByRole('button', { name: '키 관리' })
  await manageAccessButton.click()
  const accessKeyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  await expect(accessKeyDialog).toBeFocused()
  await accessKeyDialog.getByRole('button', { name: '현재 링크 복사' }).click()
  const chainedShareDialog = page.getByRole('dialog', { name: '공유 링크 직접 복사' })
  await expect(chainedShareDialog.getByLabel('공유 링크')).toBeFocused()
  await chainedShareDialog.getByRole('button', { name: '확인' }).click()
  await expect(manageAccessButton).toBeFocused()

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

test('@smoke dialog는 focus를 내부에 유지하고 Escape 뒤 진입 버튼으로 돌려보낸다', async ({ page }, testInfo) => {
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const opener = page.getByRole('button', { name: '역할 추가' })
  await opener.click()

  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  const appShell = page.locator('.app-shell')
  await expect.poll(() => appShell.evaluate((element: HTMLElement) => element.inert)).toBe(true)
  await expect(appShell).toHaveAttribute('aria-hidden', 'true')
  await expect.poll(() => dialog.evaluate((element) =>
    element.contains(document.activeElement))).toBe(true)

  const first = dialog.getByRole('button', { name: '닫기' })
  const last = dialog.getByRole('button', { name: '역할 만들기' })
  await first.focus()
  await page.keyboard.press('Shift+Tab')
  await expect(last).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(first).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect.poll(() => appShell.evaluate((element: HTMLElement) => element.inert)).toBe(false)
  await expect(appShell).not.toHaveAttribute('aria-hidden', 'true')
  await expect(opener).toBeFocused()
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
    deadlineDayOffset: -1,
    deadlineTime: '22:00',
  })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await expect(page.locator('.role-row-open').filter({ hasText: '문제 운영 큐레이터' })).toBeVisible()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.locator('.rhythm-phase').filter({ has: page.getByRole('heading', { name: '모임 전' }) }).locator('.routine-row').filter({ hasText: '문제 5개 선정' })).toBeVisible()
  await expect(page.getByRole('button', { name: '문제 6개 선정 루틴 수정' })).toBeVisible()
})

test('@operations 역할과 루틴 수정 충돌은 낡은 폼을 닫고 최신 내용을 다시 연다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 이름').fill('내 화면의 낡은 역할 수정')

  api.conflictNextRoleUpdate({
    ...api.projection().roles[0]!,
    name: '다른 구성원이 갱신한 역할',
    purpose: '서버에서 먼저 갱신한 최신 역할 목적입니다.',
    responsibilities: ['최신 문제 기준 관리', '변경 내용 공유'],
    risk: '최신 기준이 구성원에게 아직 전파되지 않았어요.',
  })
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(roleDialog).toBeHidden()
  await expect(page.getByRole('status')).toContainText('다른 구성원이 먼저 바꾼 최신 역할을 불러왔어요')
  await page.getByRole('button', { name: '다른 구성원이 갱신한 역할 역할 수정' }).click()
  const reopenedRoleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(reopenedRoleDialog.getByLabel('역할 이름')).toHaveValue('다른 구성원이 갱신한 역할')
  await expect(reopenedRoleDialog.getByLabel('이 역할이 존재하는 이유'))
    .toHaveValue('서버에서 먼저 갱신한 최신 역할 목적입니다.')
  await expect(reopenedRoleDialog.getByLabel('핵심 책임'))
    .toHaveValue('최신 문제 기준 관리\n변경 내용 공유')
  await expect(reopenedRoleDialog.getByLabel('위험 신호'))
    .toHaveValue('최신 기준이 구성원에게 아직 전파되지 않았어요.')
  await reopenedRoleDialog.getByRole('button', { name: '닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '문제 5개 선정 루틴 수정' }).click()
  const routineDialog = page.getByRole('dialog', { name: '루틴 수정' })
  await routineDialog.getByLabel('루틴 이름').fill('내 화면의 낡은 루틴 수정')

  api.conflictNextRoutineUpdate({
    ...api.projection().routines[0]!,
    title: '다른 구성원이 갱신한 루틴',
    phase: 'AFTER',
    dueLabel: '금요일 22:00',
    detail: '서버에서 먼저 갱신한 최신 루틴 설명입니다.',
  })
  await routineDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(routineDialog).toBeHidden()
  await expect(page.getByRole('status')).toContainText('다른 구성원이 먼저 바꾼 최신 루틴을 불러왔어요')
  await page.getByRole('button', { name: '다른 구성원이 갱신한 루틴 루틴 수정' }).click()
  const reopenedRoutineDialog = page.getByRole('dialog', { name: '루틴 수정' })
  await expect(reopenedRoutineDialog.getByLabel('루틴 이름')).toHaveValue('다른 구성원이 갱신한 루틴')
  await expect(reopenedRoutineDialog.getByLabel('운영 단계')).toHaveValue('AFTER')
  await expect(reopenedRoutineDialog.getByLabel('언제까지')).toHaveValue('금요일 22:00')
  await expect(reopenedRoutineDialog.getByLabel('세부 설명'))
    .toHaveValue('서버에서 먼저 갱신한 최신 루틴 설명입니다.')

  expect(api.calls.filter(
    (call) => call.method === 'PUT' && call.path === `${SCOPE_PATH}/roles/${ROLE_ID}`,
  )).toHaveLength(1)
  expect(api.calls.filter(
    (call) => call.method === 'PUT' && call.path === `${SCOPE_PATH}/routines/${ROUTINE_ID}`,
  )).toHaveLength(1)
})

test('@operations 역할 수정 충돌 뒤 최신 조회가 실패하면 재편집을 막고 새로고침 후 최신 폼을 연다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const roleUpdatePath = `${SCOPE_PATH}/roles/${ROLE_ID}`
  const workspacePath = `${SCOPE_PATH}/workspace`
  const rolePutCount = () => api.calls.filter(
    (call) => call.method === 'PUT' && call.path === roleUpdatePath,
  ).length
  const workspaceGetCount = () => api.calls.filter(
    (call) => call.method === 'GET' && call.path === workspacePath,
  ).length

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 이름').fill('내 화면의 낡은 역할 수정')

  api.conflictNextRoleUpdate({
    ...api.projection().roles[0]!,
    name: '다른 구성원이 갱신한 역할',
    purpose: '서버에서 먼저 갱신한 최신 역할 목적입니다.',
    responsibilities: ['최신 문제 기준 관리', '변경 내용 공유'],
    risk: '최신 기준이 구성원에게 아직 전파되지 않았어요.',
  })
  api.makeWorkspaceGetsUnavailable()
  const getsBeforeConflict = workspaceGetCount()

  await roleDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(roleDialog).toBeHidden()
  await expect.poll(rolePutCount).toBe(1)
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeConflict)
  const syncStatus = page.locator('.workspace-sync-status')
  await expect(syncStatus).toContainText('최신 기록을 확인해야 다시 수정할 수 있어요.')
  await expect(page.getByRole('button', { name: '다른 구성원이 갱신한 역할 역할 수정' }))
    .toHaveCount(0)
  await expect(page.getByRole('button', { name: '문제 큐레이터 역할 수정' })).toBeDisabled()
  await expect(page.locator('.main-surface')).toBeFocused()
  await expect(page.getByRole('dialog', { name: '역할 수정' })).toHaveCount(0)
  await expect.poll(rolePutCount).toBe(1)

  api.restoreWorkspaceGets()
  const getsBeforeRecovery = workspaceGetCount()
  await page.getByRole('button', { name: '최신 내용 다시 확인' }).click()

  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeRecovery)
  await expect(syncStatus).toContainText('화면 갱신')
  const latestEditButton = page.getByRole('button', {
    name: '다른 구성원이 갱신한 역할 역할 수정',
  })
  await expect(latestEditButton).toBeVisible()
  await latestEditButton.click()

  const reopenedRoleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(reopenedRoleDialog.getByLabel('역할 이름')).toHaveValue('다른 구성원이 갱신한 역할')
  await expect(reopenedRoleDialog.getByLabel('이 역할이 존재하는 이유'))
    .toHaveValue('서버에서 먼저 갱신한 최신 역할 목적입니다.')
  await expect(reopenedRoleDialog.getByLabel('핵심 책임'))
    .toHaveValue('최신 문제 기준 관리\n변경 내용 공유')
  await expect(reopenedRoleDialog.getByLabel('위험 신호'))
    .toHaveValue('최신 기준이 구성원에게 아직 전파되지 않았어요.')
  await expect.poll(rolePutCount).toBe(1)
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
  await page.keyboard.press('Escape')
  await expect(roleDialog).toBeVisible()
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

test('@smoke 모든 콘텐츠 생성은 서버 응답 전 dialog 종료와 재진입을 막는다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  const memberDialog = await openMemberCreationDialog(page)
  await memberDialog.getByLabel('구성원 이름').fill('생성 잠금 구성원')
  await expectPendingCreationDialogLocked({
    api,
    dialog: memberDialog,
    operation: 'member',
    page,
    submitLabel: '구성원 추가하기',
  })

  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('생성 잠금 역할')
  await roleDialog.getByLabel('이 역할이 존재하는 이유').fill('응답 전에는 같은 역할을 다시 제출하지 않습니다.')
  await expectPendingCreationDialogLocked({
    api,
    dialog: roleDialog,
    operation: 'role',
    page,
    submitLabel: '역할 만들기',
  })
  await expect(page.locator('.role-row-open').filter({ hasText: '생성 잠금 역할' })).toHaveCount(1)

  if (testInfo.project.name === 'mobile') {
    await page.locator('.role-row-open').filter({ hasText: '생성 잠금 역할' }).click()
  }
  await page.getByRole('button', { name: '자료 추가' }).click()
  const resourceDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await resourceDialog.getByLabel('자료 이름').fill('생성 잠금 자료')
  await resourceDialog.getByLabel('링크').fill('https://docs.example.com/pending-lock')
  await expectPendingCreationDialogLocked({
    api,
    dialog: resourceDialog,
    operation: 'roleResource',
    page,
    submitLabel: '자료 연결하기',
  })
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('dialog', { name: /선택한 역할 상세/ })
      .getByRole('button', { name: '상세 닫기' })
      .click()
  }

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '루틴 추가' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await routineDialog.getByLabel('루틴 이름').fill('생성 잠금 루틴')
  await routineDialog.getByLabel('언제까지').fill('모임 하루 전')
  await routineDialog.getByLabel('세부 설명').fill('서버 응답을 받은 뒤에만 생성 화면을 닫습니다.')
  await expectPendingCreationDialogLocked({
    api,
    dialog: routineDialog,
    operation: 'routine',
    page,
    submitLabel: '루틴 만들기',
  })

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-31')
  await expectPendingCreationDialogLocked({
    api,
    dialog: roundDialog,
    operation: 'round',
    page,
    submitLabel: '회차 만들기',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()
  const decisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await decisionDialog.getByLabel('무엇을 바꾸기로 했나요?').fill('생성 요청은 응답까지 한 화면에서 기다린다')
  await decisionDialog.getByLabel('왜 이 선택을 했나요?').fill('중복 요청과 완료 callback 유실을 막기 위해서입니다.')
  await expectPendingCreationDialogLocked({
    api,
    dialog: decisionDialog,
    operation: 'decision',
    page,
    submitLabel: '결정 기록하기',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가', exact: true }).click()
  const handoffDialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await handoffDialog.getByLabel('남길 내용').fill('생성 요청이 끝날 때까지 dialog 유지')
  await expectPendingCreationDialogLocked({
    api,
    dialog: handoffDialog,
    operation: 'handoffItem',
    page,
    submitLabel: '항목 추가하기',
  })

  for (const operation of Object.keys(CONTENT_CREATION_PATHS) as ContentCreationOperation[]) {
    const path = CONTENT_CREATION_PATHS[operation]
    expect(api.calls.filter((call) => call.method === 'POST' && call.path === path)).toHaveLength(1)
  }
  expect(api.projection().roles.filter((role) => role.name === '생성 잠금 역할')).toHaveLength(1)
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
  const memberDialog = await openMemberCreationDialog(page)
  await memberDialog.getByLabel('구성원 이름').fill('저장 차단 구성원')
  await expectStorageBlock(memberDialog, '구성원 추가하기')

  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('저장 차단 역할')
  await roleDialog.getByLabel('이 역할이 존재하는 이유').fill('중복 요청을 보내지 않는지 확인합니다.')
  await expectStorageBlock(roleDialog, '역할 만들기')

  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  const roleInspector = page.getByLabel('선택한 역할 상세')
  await roleInspector.getByRole('button', { name: '자료 추가' }).click()
  const resourceDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await resourceDialog.getByLabel('자료 이름').fill('저장 차단 자료')
  await resourceDialog.getByLabel('링크').fill('https://docs.example.com/storage-blocked')
  await expectStorageBlock(resourceDialog, '자료 연결하기')
  if (testInfo.project.name === 'mobile') {
    await roleInspector.getByRole('button', { name: '상세 닫기' }).click()
  }

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

  const contentPaths = new Set(Object.values(CONTENT_CREATION_PATHS))
  expect(api.calls.filter((call) => call.method === 'POST' && contentPaths.has(call.path))).toHaveLength(0)
})

test('@smoke 생성 정보를 쓴 뒤 다른 값이 읽히면 콘텐츠 POST를 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript((prefix) => {
    const originalGetItem = Storage.prototype.getItem
    Storage.prototype.getItem = function getItem(key) {
      const storedValue = originalGetItem.call(this, key)
      if (key.startsWith(prefix) && storedValue !== null) return 'null'
      return storedValue
    }
  }, PENDING_CONTENT_CREATION_STORAGE_PREFIX)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await dialog.getByLabel('역할 이름').fill('재읽기 검증 역할')
  await dialog.getByLabel('이 역할이 존재하는 이유').fill('저장 성공처럼 보여도 실제 기록을 확인합니다.')
  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.')
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`)).toHaveLength(0)
  expect(await pendingContentCreationEntries(page)).toHaveLength(0)
})

test('@smoke 완료한 생성 정보를 지울 수 없으면 tombstone으로 다음 재사용을 막는다', async ({ page }, testInfo) => {
  await page.addInitScript((prefix) => {
    const originalRemoveItem = Storage.prototype.removeItem
    Storage.prototype.removeItem = function removeItem(key) {
      if (key.startsWith(prefix)) throw new DOMException('Storage removal disabled', 'SecurityError')
      originalRemoveItem.call(this, key)
    }
  }, PENDING_CONTENT_CREATION_STORAGE_PREFIX)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const submitRole = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
    await page.getByRole('button', { name: '역할 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
    await dialog.getByLabel('역할 이름').fill('삭제 실패 복구 역할')
    await dialog.getByLabel('이 역할이 존재하는 이유').fill('완료한 pending을 다시 쓰지 않도록 확인합니다.')
    await dialog.getByRole('button', { name: '역할 만들기' }).click()
    await expect(dialog).toHaveCount(0)
  }

  await submitRole()
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  const firstKey = firstAttempt.headers['idempotency-key']!
  expect(await page.evaluate(
    (storageKey) => localStorage.getItem(storageKey),
    `${PENDING_CONTENT_CREATION_STORAGE_PREFIX}${firstKey}`,
  )).toBe('null')

  await submitRole()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstKey)
  expect(await page.evaluate(
    (storageKey) => localStorage.getItem(storageKey),
    `${PENDING_CONTENT_CREATION_STORAGE_PREFIX}${attempts[1]?.headers['idempotency-key']}`,
  )).toBe('null')
})

test('@smoke 콘텐츠 생성 성공 뒤 cleanup이 실패하면 다음 POST 전에 기록부터 정리한다', async ({ page }, testInfo) => {
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX },
    'baton-e2e-content-success-cleanup-failure',
  )
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const openRoleCreation = async (name: string) => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
    await page.getByRole('button', { name: '역할 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
    await dialog.getByLabel('역할 이름').fill(name)
    await dialog.getByLabel('이 역할이 존재하는 이유').fill('완료 기록 정리 경계를 확인합니다.')
    return dialog
  }

  const firstDialog = await openRoleCreation('cleanup 성공 첫 역할')
  await firstDialog.getByRole('button', { name: '역할 만들기' }).click()
  await expect(firstDialog).toHaveCount(0)

  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      operation: 'role',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
    }),
  ])

  const secondDialog = await openRoleCreation('cleanup 성공 둘째 역할')
  await expect(secondDialog.getByRole('alert')).toContainText(
    '완료 기록을 정리하지 못해 같은 요청을 다시 보내지 않았습니다.',
  )
  await secondDialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(secondDialog.getByRole('alert')).toContainText('완료 기록을 정리했습니다.')
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`,
  )).toHaveLength(1)

  await secondDialog.getByRole('button', { name: '역할 만들기' }).click()
  await expect(secondDialog).toHaveCount(0)
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
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

test('@smoke 접근 키 회전은 서버 응답 전 dialog 종료와 재진입을 막는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: () => {
          document.documentElement.dataset.batonShareAttempted = 'true'
          return Promise.resolve()
        },
      },
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  const rotationPath = `${SCOPE_PATH}/access-key/rotate`
  const rotationCallCount = () => api.calls.filter(
    (call) => call.method === 'POST' && call.path === rotationPath,
  ).length
  let confirmationCount = 0
  const acceptConfirmation = async (dialog: Dialog) => {
    confirmationCount += 1
    await dialog.accept()
  }
  page.on('dialog', acceptConfirmation)
  api.holdAccessKeyRotations()

  try {
    await keyDialog.getByRole('button', { name: '접근 키 바꾸기' })
      .evaluate((button: HTMLButtonElement) => {
        button.click()
        button.click()
        const dialog = button.closest('[role="dialog"]')
        dialog?.querySelector<HTMLButtonElement>('.secondary-button')?.click()
        dialog?.querySelector<HTMLButtonElement>('.modal-close')?.click()
        dialog?.parentElement?.dispatchEvent(new MouseEvent('mousedown', {
          bubbles: true,
          cancelable: true,
        }))
        document.dispatchEvent(new KeyboardEvent('keydown', {
          bubbles: true,
          cancelable: true,
          key: 'Escape',
        }))
      })

    await expect.poll(() => confirmationCount).toBe(1)
    await expect.poll(rotationCallCount).toBe(1)
    await expect(keyDialog).toBeVisible()
    await expect(keyDialog).toHaveAttribute('aria-busy', 'true')
    const closeButton = keyDialog.getByRole('button', { name: '닫기' })
    await expect(closeButton).toBeDisabled()
    await expect(keyDialog.getByRole('button', { name: '현재 링크 복사' })).toBeDisabled()
    await expect(keyDialog.getByRole('button', { name: '접근 키 바꾸는 중…' })).toBeDisabled()
    expect(await page.evaluate(() =>
      document.documentElement.dataset.batonShareAttempted)).toBeUndefined()

    await page.keyboard.press('Escape')
    await expect(keyDialog).toBeVisible()
    await closeButton.click({ force: true })
    await expect(keyDialog).toBeVisible()
    await page.locator('.modal-backdrop').click({ position: { x: 5, y: 5 }, force: true })
    await expect(keyDialog).toBeVisible()
    expect(rotationCallCount()).toBe(1)

    const rotateCall = [...api.calls].reverse().find(
      (call) => call.method === 'POST' && call.path === rotationPath,
    )
    expect(JSON.parse(await page.evaluate(
      (key) => localStorage.getItem(key) ?? 'null',
      PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
    ))?.idempotencyKey).toBe(rotateCall?.headers['idempotency-key'])
  } finally {
    page.off('dialog', acceptConfirmation)
    api.releaseAccessKeyRotations()
  }

  await expect(keyDialog).toHaveCount(0)
  expect(rotationCallCount()).toBe(1)
  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
  ).toBeNull()
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    `baton-access-key:${TEAM_ID}`,
  )).toBe(ROTATED_ACCESS_KEY)
})

test('@smoke 접근 키 회전 journal은 탭 간 요청 완료까지 같은 임계 구역에서 보호한다', async ({ page, context }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  await openSharedWorkspace(peerPage)

  const openKeyManagement = async (target: Page) => {
    const workspaceChrome = testInfo.project.name === 'mobile'
      ? target.locator('.mobile-topbar')
      : target.locator('.sidebar')
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
    return target.getByRole('dialog', { name: '공유 접근 키 관리' })
  }
  const rotationCalls = () => api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  )

  api.holdAccessKeyRotations()
  try {
    const firstDialog = await openKeyManagement(page)
    page.once('dialog', (dialog) => dialog.accept())
    await firstDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
    const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)

    const peerDialog = await openKeyManagement(peerPage)
    peerPage.once('dialog', (dialog) => dialog.accept())
    await peerDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

    await expect(peerDialog.getByRole('alert')).toContainText(
      '다른 탭에서 접근 키 변경 결과를 확인 중입니다.',
    )
    expect(rotationCalls()).toHaveLength(1)
    expect(JSON.parse(await page.evaluate(
      (key) => localStorage.getItem(key) ?? 'null',
      PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
    ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

    api.releaseAccessKeyRotations()

    await expect(firstDialog).toHaveCount(0)
    await expect.poll(() =>
      page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
    ).toBeNull()
    expect(rotationCalls()).toHaveLength(1)
  } finally {
    api.releaseAccessKeyRotations()
    await peerPage.close()
  }
})

test('@smoke Web Locks를 사용할 수 없으면 접근 키 회전 요청을 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: undefined,
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

  await expect(keyDialog.getByRole('alert')).toContainText(
    '탭 사이의 접근 키 변경을 안전하게 조정할 수 없습니다.',
  )
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  )).toHaveLength(0)
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  )).toBeNull()
})

test('@smoke 완료한 접근 키 회전 정보를 지울 수 없으면 tombstone으로 다음 재사용을 막는다', async ({ page }, testInfo) => {
  await page.addInitScript((pendingStorageKey) => {
    const originalRemoveItem = Storage.prototype.removeItem
    Storage.prototype.removeItem = function removeItem(key) {
      if (key === pendingStorageKey) {
        throw new DOMException('Storage removal disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
  }, PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const openKeyManagement = async () => {
    const workspaceChrome = testInfo.project.name === 'mobile'
      ? page.locator('.mobile-topbar')
      : page.locator('.sidebar')
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
    return page.getByRole('dialog', { name: '공유 접근 키 관리' })
  }
  const rotate = async () => {
    const keyDialog = await openKeyManagement()
    page.once('dialog', (dialog) => dialog.accept())
    await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
    await expect(keyDialog).toHaveCount(0)
  }

  await rotate()
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  )).toBe('null')

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await rotate()

  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
  expect(attempts[1]?.headers['x-baton-access-key']).toBe(ROTATED_ACCESS_KEY)
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  )).toBe('null')
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    `baton-access-key:${TEAM_ID}`,
  )).toBe(SECOND_ROTATED_ACCESS_KEY)
})

test('@smoke 접근 키 회전 완료 기록을 전혀 정리하지 못하면 과거 결과를 성공으로 오인하지 않는다', async ({ page }, testInfo) => {
  await failNextAccessKeyRotationCleanup(page)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  const acceptConfirmation = (dialog: Dialog) => dialog.accept()
  page.on('dialog', acceptConfirmation)

  try {
    const rotateButton = () => keyDialog.getByRole('button', { name: '접근 키 바꾸기' })
    await rotateButton().click()
    await expect(keyDialog.getByRole('alert')).toContainText('완료 기록을 정리하지 못했습니다.')
    const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
    expect(JSON.parse(await page.evaluate(
      (key) => localStorage.getItem(key) ?? 'null',
      PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
    ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])
    expect(await page.evaluate(
      (key) => localStorage.getItem(key),
      `baton-access-key:${TEAM_ID}`,
    )).toBe(ROTATED_ACCESS_KEY)

    await keyDialog.getByRole('button', { name: '닫기' }).click()
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()

    await rotateButton().click()
    await expect(keyDialog.getByRole('alert')).toContainText('접근 키는 이번 요청에서 새로 바뀌지 않았습니다.')
    const replayAttempts = api.calls.filter(
      (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
    )
    expect(replayAttempts).toHaveLength(2)
    expect(replayAttempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
    await expect.poll(() =>
      page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
    ).toBeNull()

    await rotateButton().click()
    await expect(keyDialog).toHaveCount(0)
    const attempts = api.calls.filter(
      (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
    )
    expect(attempts).toHaveLength(3)
    expect(attempts[2]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
    expect(await page.evaluate(
      (key) => localStorage.getItem(key),
      `baton-access-key:${TEAM_ID}`,
    )).toBe(SECOND_ROTATED_ACCESS_KEY)
  } finally {
    page.off('dialog', acceptConfirmation)
  }
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
  await failNextAccessKeyRotationCleanup(page)
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
  await expect(keyDialog.getByText(/새 요청으로 다시 시도해 주세요/)).toBeVisible()
  await expect(keyDialog.getByText(/이전 접근 키 변경 기록을 정리하지 못했습니다/)).toBeVisible()
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expect(JSON.parse(await page.evaluate(
    (key) => localStorage.getItem(key) ?? 'null',
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('status')).toContainText('접근 키를 바꿨어요.')
  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
  ).toBeNull()

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
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' })
    .evaluate((button: HTMLButtonElement) => {
      button.click()
      button.click()
    })

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
  await failNextAccessKeyRotationCleanup(page)
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

  await expect(page.getByText('다른 기기에서 더 최신 접근 키 변경이 완료된 것으로 보입니다.')).toBeVisible()
  await expect(page.getByText('이전 접근 키 변경 기록을 정리하지 못했습니다. 브라우저 저장을 허용한 뒤 완료 기록 정리를 다시 확인해 주세요.')).toBeVisible()
  await expect(page.getByText('작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.')).toBeVisible()
  await expect(page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' })).toHaveCount(0)
  expect(JSON.parse(await page.evaluate(
    (key) => localStorage.getItem(key) ?? 'null',
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

  await page.getByRole('button', { name: '완료 기록 정리 다시 확인' }).click()
  await expect(page.getByRole('button', { name: '완료 기록 정리 다시 확인' })).toHaveCount(0)
  await expect(page.getByText('이전 접근 키 변경 기록을 정리했습니다. 최신 공유 링크로 다시 열어 주세요.')).toBeVisible()
  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
  ).toBeNull()

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

test('@smoke 자동 회차와 지연 상태를 오늘 화면에서 구분하고 직접 수정을 막는다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  const automaticRound = projection.rounds.find((round) => round.id === ROUND_TWO_ID)
  expect(automaticRound).toBeDefined()
  automaticRound!.origin = 'AUTOMATIC'
  automaticRound!.scheduledOccurrenceDate = automaticRound!.meetingDate
  automaticRound!.scheduledAt = '2026-07-17T10:00:00Z'
  automaticRound!.timingStatus = 'OVERDUE'
  automaticRound!.routineExecutions[1]!.timingStatus = 'OVERDUE'

  await installApi(page, projection)
  await openSharedWorkspace(page)

  await expect(page.locator('.round-meta')).toContainText('자동 생성 · 지연 · 2회차')
  await expect(page.locator('.relay-status').filter({ hasText: '지연' })).toBeVisible()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 수정' }))
    .toHaveAttribute('title', '자동 회차는 반복 설정으로 관리합니다')
})

test('@operations 새 자동 회차는 관련 기본 선택을 갱신하되 사용자가 고른 회차는 보존한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const sourceRound = api.projection().rounds.find((round) => round.id === ROUND_ONE_ID)
  expect(sourceRound).toBeDefined()
  const automaticRound: SeasonRound = {
    ...sourceRound!,
    id: AUTOMATIC_ROUND_ID,
    name: '자동 3회차',
    meetingDate: '2026-07-24',
    origin: 'AUTOMATIC',
    scheduledOccurrenceDate: '2026-07-24',
    scheduledAt: '2026-07-24T10:00:00Z',
    timingStatus: 'OVERDUE',
    routineExecutions: sourceRound!.routineExecutions.map((execution, index) => ({
      ...execution,
      id: index === 0
        ? AUTOMATIC_ROUND_ROUTINE_ONE_EXECUTION_ID
        : AUTOMATIC_ROUND_ROUTINE_TWO_EXECUTION_ID,
      roundId: AUTOMATIC_ROUND_ID,
      timingStatus: index === 0 ? 'OVERDUE' : 'PLANNED',
    })),
  }
  api.addRoundFromAnotherDevice(automaticRound)

  await expect(page.getByLabel('운영 회차')).toHaveValue(AUTOMATIC_ROUND_ID)

  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
  const workspaceGetCount = () => api.calls.filter(
    (call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length
  const getsBeforeRefresh = workspaceGetCount()
  const refreshButton = page.getByRole('button', { name: '지금 새로고침' })
  await expect(refreshButton).toBeEnabled()
  await refreshButton.click()
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeRefresh)
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_ONE_ID)
})

test('@operations 시즌 시간대와 격주 일정을 저장해 자동 회차 운영 카드를 갱신한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  await expect(page.getByRole('heading', { name: '자동 회차가 꺼져 있어요' })).toBeVisible()
  await page.getByRole('button', { name: '설정하기' }).click()

  const dialog = page.getByRole('dialog', { name: '자동 회차 설정' })
  await dialog.getByLabel('시즌 시간대').fill('Asia/Seoul')
  await dialog.getByLabel('첫 자동 회차').fill('2026-08-06')
  await dialog.getByLabel('모임 시각').fill('20:30')
  await dialog.getByLabel('반복 주기').selectOption('BIWEEKLY')
  await dialog.getByLabel('미리 만들 기간').selectOption('14')
  await dialog.getByRole('button', { name: '자동 회차 저장' }).click()

  await expect(page.getByRole('heading', { name: '격주 20:30' })).toBeVisible()
  await expect(page.locator('.round-schedule-card')).toContainText(
    'Asia/Seoul · 자동 생성 중 · 다음 발생 2026. 8. 6.',
  )
  const scheduleCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/round-schedule`)
  expectScopedCall(scheduleCall, {
    timeZone: 'Asia/Seoul',
    firstMeetingDate: '2026-08-06',
    meetingTime: '20:30',
    recurrence: 'BIWEEKLY',
    generationLeadDays: 14,
    enabled: true,
  })
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
    deadlineDayOffset: -1,
    deadlineTime: '22:00',
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
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await page.getByLabel('운영 회차').selectOption(CREATED_ROUND_ID)
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
})

test('@operations 회차 정보를 정정하고 보관·복원해도 실행 기록과 선택 회차를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  const executionSnapshot = structuredClone(
    api.projection().rounds
      .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions,
  )
  expect(executionSnapshot).toBeDefined()

  await page.getByRole('button', { name: '회차 수정' }).click()
  const editDialog = page.getByRole('dialog', { name: '회차 정보 수정' })
  await expect(editDialog.getByLabel('회차 이름')).toHaveValue('2회차')
  await expect(editDialog.getByLabel('모임 날짜')).toHaveValue('2026-07-17')

  await editDialog.getByLabel('회차 이름').fill('1회차')
  await editDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(editDialog.getByRole('alert'))
    .toContainText('같은 시즌에 동일한 회차 이름을 사용할 수 없습니다.')

  await editDialog.getByLabel('회차 이름').fill('심화 풀이 모임')
  await editDialog.getByLabel('모임 날짜').fill('2026-07-18')
  await editDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(editDialog).toHaveCount(0)

  const updateCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}`)
  expectScopedCall(updateCall, { name: '심화 풀이 모임', meetingDate: '2026-07-18' })
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByLabel('운영 회차').locator('option:checked')).toContainText('심화 풀이 모임')
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()

  await page.getByRole('button', { name: '심화 풀이 모임 회차 보관' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_ONE_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
  const archiveCall = await recordedCall(
    api,
    'PATCH',
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/archive`,
  )
  expectScopedCall(archiveCall, { archived: true })

  await page.getByText('보관한 회차 1개', { exact: true }).click()
  const archivedRow = page.locator('.archive-row').filter({ hasText: '심화 풀이 모임' })
  await expect(archivedRow).toContainText('1/2 완료')
  await archivedRow.getByRole('button', { name: '심화 풀이 모임 회차 복원' }).click()

  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()
  const restoreCall = api.calls.filter((call) =>
    call.method === 'PATCH' && call.path === `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/archive`,
  ).at(-1)
  expect(restoreCall).toBeDefined()
  expectScopedCall(restoreCall!, { archived: false })
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions)
    .toEqual(executionSnapshot)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByLabel('운영 회차').locator('option:checked')).toContainText('심화 풀이 모임')
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()
})

test('@operations 오늘 화면에서 선택한 회차의 루틴을 완료하고 취소한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const checklist = page.getByRole('region', { name: '2회차 루틴 완료하기' })
  const completeButton = checklist.getByRole('button', { name: '풀이 노트 정리 완료 처리' })
  await expect(checklist.getByText('1/2 완료')).toBeVisible()
  await completeButton.scrollIntoViewIfNeeded()
  await expect(completeButton).toBeInViewport()
  await completeButton.click()

  await expect(checklist.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeEnabled()
  await expect(checklist.getByText('2/2 완료')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const completionPath =
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`
  const completeCall = await recordedCall(api, 'PATCH', completionPath)
  expectScopedCall(completeCall, { completed: true })

  await checklist.getByRole('button', { name: '풀이 노트 정리 완료 취소' }).click()

  await expect(checklist.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeEnabled()
  await expect(checklist.getByText('1/2 완료')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '1개의 바통이 남았어요' })).toBeVisible()

  const completionCalls = api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === completionPath,
  )
  expect(completionCalls).toHaveLength(2)
  expectScopedCall(completionCalls[1]!, { completed: false })
})

test('@operations 완료 저장 중에는 같은 회차 관리만 잠근다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  api.holdNextRoutineCompletion()
  await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()

  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '2회차 회차 보관' })).toBeDisabled()

  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '1회차 회차 보관' })).toBeEnabled()
  await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeEnabled()

  api.releaseRoutineCompletion()
  await expect.poll(() => api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions
    .find((execution) => execution.id === ROUND_TWO_ROUTINE_TWO_EXECUTION_ID)?.status)
    .toBe('DONE')
  await page.getByLabel('운영 회차').selectOption(ROUND_TWO_ID)
  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeEnabled()
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

test('@operations 오늘 화면의 루틴 완료 저장 실패를 서버 상태로 되돌리고 알린다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.failNextRoutineCompletion()
  await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()

  await expect(page.getByRole('status')).toHaveText(/완료 상태를 바꾸지 못했어요.*루틴 상태를 저장하지 못했습니다/)
  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '1개의 바통이 남았어요' })).toBeVisible()
  const failureCall = await recordedCall(
    api,
    'PATCH',
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`,
  )
  expectScopedCall(failureCall, { completed: true })
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions
    .find((execution) => execution.routineId === SECOND_ROUTINE_ID)?.status).toBe('WAITING')
})

test('@operations @handoff 완료 충돌은 공용 복구로 상대 사용자의 최신 상태를 다시 불러온다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const workspaceGetCount = () => api.calls.filter(
    (call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length
  const routineCompletionPath =
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`
  const handoffCompletionPath = `${SCOPE_PATH}/handoff-items/${HANDOFF_TWO_ID}/completion`
  const completionPatchCount = (path: string) => api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === path,
  ).length

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  api.conflictNextRoutineCompletion(false)
  const getsBeforeRoutineConflict = workspaceGetCount()
  await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()

  await expect.poll(() => completionPatchCount(routineCompletionPath)).toBe(1)
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeRoutineConflict)
  await expect(page.getByRole('status')).toContainText('다른 구성원의 최신 회차 실행을 불러왔어요.')
  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeVisible()
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions
    .find((execution) => execution.id === ROUND_TWO_ROUTINE_TWO_EXECUTION_ID)?.status)
    .toBe('WAITING')
  expectScopedCall(await recordedCall(api, 'PATCH', routineCompletionPath), { completed: true })
  expect(completionPatchCount(routineCompletionPath)).toBe(1)

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  const handoffCheckbox = page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })
  api.conflictNextHandoffCompletion(false)
  const getsBeforeHandoffConflict = workspaceGetCount()
  await handoffCheckbox.click()

  await expect.poll(() => completionPatchCount(handoffCompletionPath)).toBe(1)
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeHandoffConflict)
  await expect(page.getByRole('status')).toContainText('다른 구성원의 최신 바통 항목을 불러왔어요.')
  await expect(handoffCheckbox).not.toBeChecked()
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_TWO_ID)?.completed).toBe(false)
  expectScopedCall(await recordedCall(api, 'PATCH', handoffCompletionPath), { completed: true })
  expect(completionPatchCount(handoffCompletionPath)).toBe(1)
})

test('@operations 루틴 완료 실패 롤백이 동시에 성공한 바통 상태를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.holdNextRoutineCompletion()
  api.failNextRoutineCompletion()
  api.holdWorkspaceGets()

  try {
    await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeDisabled()

    await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
    const handoffCheckbox = page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })
    await expect(handoffCheckbox).toBeEnabled()
    await handoffCheckbox.click()
    await expect(handoffCheckbox).toBeChecked()
    await recordedCall(
      api,
      'PATCH',
      `${SCOPE_PATH}/handoff-items/${HANDOFF_TWO_ID}/completion`,
    )

    api.releaseRoutineCompletion()
    await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘' }).click()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeVisible()

    await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
    await expect(handoffCheckbox).toBeChecked()
  } finally {
    api.releaseWorkspaceGets()
  }
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
  await expect(dialog.getByRole('checkbox', { name: '문제 큐레이터' })).toBeChecked()
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

test('@memory 결정 기록을 수정하고 보관·복원해 원문 시각을 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()

  const originalTitle = '한 회차의 문제 수를 5개로 정한다'
  const updatedTitle = '한 회차의 문제 수를 네 개로 조정한다'
  await page.getByRole('button', { name: `${originalTitle} 수정` }).click()

  const dialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  await expect(dialog.getByLabel('작성자')).toHaveValue(MEMBER_ONE_ID)
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill(updatedTitle)
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('각 풀이를 끝까지 설명할 시간을 확보하기 위해서입니다.')
  await dialog.getByLabel('검토한 다른 선택').fill('문제 난이도를 낮춘다')
  await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
  await dialog.getByRole('button', { name: '변경 저장' }).click()

  const updatePath = `${SCOPE_PATH}/decisions/${DECISION_ID}`
  expectScopedCall(await recordedCall(api, 'PUT', updatePath), {
    title: updatedTitle,
    reason: '각 풀이를 끝까지 설명할 시간을 확보하기 위해서입니다.',
    alternative: '문제 난이도를 낮춘다',
    authorMemberId: MEMBER_TWO_ID,
    roleIds: [ROLE_ID],
  })
  await expect(dialog).toHaveCount(0)
  const updatedDecision = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: updatedTitle }),
  })
  await expect(updatedDecision).toBeVisible()
  await expect(updatedDecision.getByText('김준호', { exact: true })).toBeVisible()
  expect(api.projection().decisions.find((decision) => decision.id === DECISION_ID)).toMatchObject({
    createdAt: '2026-07-03T12:00:00Z',
    authorMemberId: MEMBER_TWO_ID,
    archivedAt: null,
  })

  await page.getByRole('button', { name: `${updatedTitle} 보관` }).click()
  const archivePath = `${updatePath}/archive`
  expectScopedCall(await recordedCall(api, 'PATCH', archivePath), { archived: true })
  await expect(page.getByRole('heading', { name: updatedTitle })).toHaveCount(0)

  const archiveSummary = page.getByText('보관한 결정 1개', { exact: true })
  await archiveSummary.scrollIntoViewIfNeeded()
  await archiveSummary.click()
  await page.getByRole('button', { name: `${updatedTitle} 복원` }).click()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  ).length).toBe(2)
  const archiveCalls = api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  )
  expectScopedCall(archiveCalls[0]!, { archived: true })
  expectScopedCall(archiveCalls[1]!, { archived: false })
  await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible()
  expect(api.projection().decisions.find((decision) => decision.id === DECISION_ID)).toMatchObject({
    createdAt: '2026-07-03T12:00:00Z',
    archivedAt: null,
  })
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
    await expect(dialog.getByRole('checkbox', { name: '문제 큐레이터' })).toBeChecked()
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

test('@memory 결정 생성 연결이 끊겨도 같은 요청으로 안전하게 재제출한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '전송 오류 복구 의미는 데스크톱 Chromium에서 한 번만 검증합니다.')
  const api = await installApi(page)
  let firstIdempotencyKey: string | undefined
  await page.route(
    (url) => url.pathname === `${SCOPE_PATH}/decisions`,
    async (route) => {
      firstIdempotencyKey = route.request().headers()['idempotency-key']
      await route.abort('connectionreset')
    },
    { times: 1 },
  )
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()

  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('연결 오류에도 같은 결정을 다시 확인한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('응답을 모를 때 새 요청을 만들지 않기 위해서입니다.')
  await dialog.getByLabel('검토한 다른 선택').fill('목록에서 수동으로 중복을 찾는다')
  await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  const alert = dialog.getByRole('alert')
  await expect(alert).toContainText('서버에 연결하지 못해 요청 결과를 확인할 수 없습니다.')
  await expect(alert).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')
  expect(firstIdempotencyKey).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      operation: 'decision',
      idempotencyKey: firstIdempotencyKey,
    }),
  ])

  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '연결 오류에도 같은 결정을 다시 확인한다' })).toBeVisible()
  const retry = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  expect(retry.headers['idempotency-key']).toBe(firstIdempotencyKey)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff 역할 탭은 방향키로 순환하고 선택한 tabpanel을 연결한다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.roles.push({
    id: SECOND_ROLE_ID,
    name: '질문 큐레이터',
    purpose: '구성원이 막힌 지점을 다음 모임의 질문으로 정리합니다.',
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: MEMBER_THREE_ID,
    assignmentStartDate: '2026-07-02',
    assignmentEndDate: '2026-09-17',
    responsibilities: ['막힌 지점 수집', '질문 순서 정리'],
    risk: '',
  })
  await installApi(page, projection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()

  const tablist = page.getByRole('tablist', { name: '역할별 바통' })
  const first = tablist.getByRole('tab', { name: /^문제 큐레이터/ })
  const second = tablist.getByRole('tab', { name: /^질문 큐레이터/ })
  const panel = page.getByRole('tabpanel')

  await expect(first).toHaveAttribute('aria-selected', 'true')
  await expect(first).toHaveAttribute('tabindex', '0')
  await expect(second).toHaveAttribute('tabindex', '-1')
  await expect(first).toHaveAttribute('aria-controls', await panel.getAttribute('id') ?? '')
  await expect(panel).toHaveAttribute('aria-labelledby', await first.getAttribute('id') ?? '')

  await first.focus()
  await page.keyboard.press('ArrowRight')
  await expect(second).toBeFocused()
  await expect(second).toHaveAttribute('aria-selected', 'true')
  await expect(second).toHaveAttribute('tabindex', '0')
  await expect(first).toHaveAttribute('tabindex', '-1')
  await expect(panel).toHaveAttribute('aria-labelledby', await second.getAttribute('id') ?? '')
  await expect(page.getByRole('heading', { name: '최유진님에게 넘길 바통' })).toBeVisible()

  await page.keyboard.press('ArrowRight')
  await expect(first).toBeFocused()
  await page.keyboard.press('End')
  await expect(second).toBeFocused()
  await page.keyboard.press('Home')
  await expect(first).toBeFocused()
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

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/role-resources`,
  ).length).toBe(2)
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

test('@handoff 콘텐츠 terminal 기록 cleanup이 실패하면 같은 키 재전송을 막는다', async ({ page }, testInfo) => {
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX },
    'baton-e2e-content-terminal-cleanup-failure',
  )
  const api = await installApi(page)
  api.rejectNextContentCreationAsReused('handoffItem')
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('terminal cleanup 재전송 차단')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('완료 기록을 정리하지 못해 같은 요청을 다시 보내지 않았습니다.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      operation: 'handoffItem',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
    }),
  ])

  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('완료 기록을 정리했습니다.')
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(1)

  await dialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(page.getByRole('checkbox', { name: 'terminal cleanup 재전송 차단' })).toBeVisible()
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@handoff 같은 바통 생성 요청의 탭 경합은 한 번만 전송한다', async ({ page, context }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  api.holdNextContentCreation('handoffItem')

  try {
    await openSharedWorkspace(peerPage)
    await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
    await navigation(peerPage, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()

    await page.getByRole('button', { name: '항목 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
    await dialog.getByLabel('역할').selectOption(ROLE_ID)
    await dialog.getByLabel('남길 내용').fill('멀티탭 생성 잠금 확인')
    await dialog.getByLabel('항목 종류').selectOption('RESPONSIBILITY')

    await peerPage.getByRole('button', { name: '항목 추가' }).click()
    const peerDialog = peerPage.getByRole('dialog', { name: '바통북 항목 추가' })
    await peerDialog.getByLabel('역할').selectOption(ROLE_ID)
    await peerDialog.getByLabel('남길 내용').fill('멀티탭 생성 잠금 확인')
    await peerDialog.getByLabel('항목 종류').selectOption('RESPONSIBILITY')

    await dialog.getByRole('button', { name: '항목 추가하기' }).click()
    const handoffCreateCalls = () => api.calls.filter(
      (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
    ).length
    await expect.poll(handoffCreateCalls).toBe(1)

    await peerDialog.getByRole('button', { name: '항목 추가하기' }).click()
    await expect(peerDialog.getByRole('alert'))
      .toContainText('다른 탭에서 콘텐츠 생성 요청을 처리 중입니다.')
    expect(handoffCreateCalls()).toBe(1)

    api.releaseContentCreation()

    await expect(dialog).toHaveCount(0)
    await expect.poll(() =>
      api.projection().handoffItems.filter((item) => item.label === '멀티탭 생성 잠금 확인').length,
    ).toBe(1)
    await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
    expect(handoffCreateCalls()).toBe(1)
  } finally {
    api.releaseContentCreation()
    await peerPage.close()
  }
})

test('@handoff Web Locks를 사용할 수 없으면 바통 생성 요청을 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: undefined,
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('Web Locks 미지원 차단')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert'))
    .toContainText('탭 사이의 콘텐츠 생성 요청을 안전하게 조정할 수 없습니다.')
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(0)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff Web Locks 요청이 실패하면 바통 생성 요청을 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: () => Promise.reject(new Error('Web Locks request failed')),
      },
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '바통북 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('Web Locks 요청 실패 차단')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert'))
    .toContainText('콘텐츠 생성 요청의 안전 잠금을 확인하지 못했습니다.')
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(0)
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
  await expect(page.getByRole('button', { name: '문제 선정 기준 문서 링크 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 선정 기준 문서 링크 보관' })).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: '역할의 한 줄 목적' })).toBeEnabled()
  await expect(page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })).toBeEnabled()
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

test('@handoff 바통 완료 실패 롤백이 동시에 성공한 회차 상태를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()

  api.holdNextHandoffCompletion()
  api.failNextHandoffCompletion()
  api.holdWorkspaceGets()

  const handoffCheckbox = page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })
  try {
    await handoffCheckbox.click()
    await expect(handoffCheckbox).toBeChecked()

    await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘' }).click()
    await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeVisible()
    await recordedCall(
      api,
      'PATCH',
      `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`,
    )

    api.releaseHandoffCompletion()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeVisible()

    await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
    await expect(handoffCheckbox).not.toBeChecked()
  } finally {
    api.releaseWorkspaceGets()
  }
})

test('@handoff 완료한 바통 항목을 수정하고 보관·복원해 완료 상태를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()

  const originalLabel = '역할의 한 줄 목적'
  const updatedLabel = '역할의 한 줄 목적과 성공 기준'
  await expect(page.getByRole('checkbox', { name: originalLabel })).toBeChecked()
  await page.getByRole('button', { name: `${originalLabel} 수정` }).click()

  const dialog = page.getByRole('dialog', { name: '바통북 항목 수정' })
  await dialog.getByLabel('남길 내용').fill(updatedLabel)
  await dialog.getByLabel('항목 종류').selectOption('ADVICE')
  await dialog.getByRole('button', { name: '변경 저장' }).click()

  const updatePath = `${SCOPE_PATH}/handoff-items/${HANDOFF_ONE_ID}`
  expectScopedCall(await recordedCall(api, 'PUT', updatePath), {
    roleId: ROLE_ID,
    label: updatedLabel,
    category: 'ADVICE',
  })
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toBeChecked()
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_ONE_ID)).toMatchObject({
    completed: true,
    archivedAt: null,
  })

  await page.getByRole('button', { name: `${updatedLabel} 보관` }).click()
  const archivePath = `${updatePath}/archive`
  expectScopedCall(await recordedCall(api, 'PATCH', archivePath), { archived: true })
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toHaveCount(0)
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_ONE_ID)).toMatchObject({
    completed: true,
    archivedAt: '2026-07-21T12:00:00Z',
  })

  const archiveSummary = page.getByText('보관한 바통 1개', { exact: true })
  await archiveSummary.scrollIntoViewIfNeeded()
  await archiveSummary.click()
  await page.getByRole('button', { name: `${updatedLabel} 복원` }).click()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  ).length).toBe(2)
  const archiveCalls = api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  )
  expectScopedCall(archiveCalls[0]!, { archived: true })
  expectScopedCall(archiveCalls[1]!, { archived: false })
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toBeChecked()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toBeChecked()
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_ONE_ID)).toMatchObject({
    completed: true,
    archivedAt: null,
  })
})

test('@responsive 390x844에서 구성원 관리 동작과 focus 복귀를 유지한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const opener = page.getByRole('button', { name: '구성원 관리' })
  await opener.click()
  const managementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(managementDialog).toBeInViewport()

  const editButton = managementDialog.getByRole('button', { name: '박민서 이름 수정' })
  const deactivateButton = managementDialog.getByRole('button', { name: '박민서 활동 종료' })
  await expect.poll(async () => (await editButton.boundingBox())?.height ?? 0)
    .toBeGreaterThanOrEqual(44)
  await expect.poll(async () => (await deactivateButton.boundingBox())?.height ?? 0)
    .toBeGreaterThanOrEqual(44)

  await editButton.click()
  const editDialog = page.getByRole('dialog', { name: '구성원 이름 수정' })
  await expect(editDialog.getByLabel('구성원 이름')).toBeFocused()
  await editDialog.getByRole('button', { name: '취소' }).click()
  await expect(managementDialog).toBeFocused()

  await managementDialog.getByRole('button', { name: '구성원 추가' }).click()
  const createDialog = page.getByRole('dialog', { name: '구성원 추가' })
  await expect(createDialog.getByLabel('구성원 이름')).toBeFocused()
  await createDialog.getByRole('button', { name: '취소' }).click()
  await expect(managementDialog).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(opener).toBeFocused()
})

test('@responsive 모바일 역할 상세는 닫힌 focus를 차단하고 Escape 뒤 역할 행으로 돌아간다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const appShell = page.locator('.app-shell')
  const inspector = page.locator('.inspector')
  const hiddenClose = inspector.locator('.inspector-close')
  const opener = page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' })

  await expect(opener).toHaveAccessibleName(/역할 상세 열기/)
  await expect(inspector).toHaveAttribute('aria-hidden', 'true')
  await expect.poll(() => inspector.evaluate((element: HTMLElement) => element.inert)).toBe(true)
  expect(await hiddenClose.evaluate((element: HTMLElement) => {
    element.focus()
    return document.activeElement === element
  })).toBe(false)

  await opener.click()
  const drawer = page.getByRole('dialog', { name: /선택한 역할 상세: 문제 큐레이터/ })
  const close = drawer.getByRole('button', { name: '상세 닫기' })
  const last = drawer.getByRole('button', { name: /바통 정리하기/ })
  await expect(close).toBeFocused()
  await expect.poll(() => appShell.evaluate((element: HTMLElement) => element.inert)).toBe(true)

  const addResource = drawer.getByRole('button', { name: '자료 추가' })
  await addResource.click()
  const resourceDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await expect(resourceDialog.getByLabel('자료 이름')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(resourceDialog).toHaveCount(0)
  await expect(addResource).toBeFocused()
  await expect(drawer).toBeVisible()

  await close.focus()
  await page.keyboard.press('Shift+Tab')
  await expect(last).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(close).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(inspector).toHaveAttribute('aria-hidden', 'true')
  await expect.poll(() => inspector.evaluate((element: HTMLElement) => element.inert)).toBe(true)
  await expect.poll(() => appShell.evaluate((element: HTMLElement) => element.inert)).toBe(false)
  await expect(opener).toBeFocused()
})

test('@responsive 보조 문구와 경고 및 키보드 focus 대비를 유지한다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.rounds.find((round) => round.id === ROUND_ONE_ID)!.archivedAt = '2026-07-21T12:00:00Z'
  await installApi(page, initialProjection)
  await openSharedWorkspace(page)

  const palette = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    const color = (name: string) => style.getPropertyValue(name).trim()
    return {
      canvas: color('--canvas'),
      faint: color('--faint'),
      focusRing: color('--focus-ring'),
      muted: color('--muted'),
      nav: color('--nav'),
      warning: color('--warning'),
      warningSoft: color('--warning-soft'),
    }
  })

  expect(contrastRatio(palette.faint, palette.canvas)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.muted, palette.canvas)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.muted, palette.warningSoft)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.warning, palette.warningSoft)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.focusRing, palette.canvas)).toBeGreaterThanOrEqual(3)
  expect(contrastRatio(palette.focusRing, palette.nav)).toBeGreaterThanOrEqual(3)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  const archiveSummary = page.getByText('보관한 회차 1개', { exact: true })
  await archiveSummary.focus()
  await page.keyboard.press('Tab')
  await page.keyboard.press('Shift+Tab')
  await expect(archiveSummary).toBeFocused()
  await expectVisibleFocus(archiveSummary, palette.canvas)

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  const selectedRoleTab = page.getByRole('tab', { selected: true })
  await selectedRoleTab.focus()
  await page.keyboard.press('Tab')

  const tabPanel = page.getByRole('tabpanel')
  await expect(tabPanel).toBeFocused()
  await expectVisibleFocus(tabPanel, palette.canvas)
  await page.keyboard.press('Tab')

  const checkbox = tabPanel.getByRole('checkbox', { name: '역할의 한 줄 목적' })
  await expect(checkbox).toBeFocused()
  const visibleCheckbox = checkbox.locator('xpath=following-sibling::span[contains(@class, "custom-check")]')
  await expectVisibleFocus(visibleCheckbox, palette.canvas)
})

test('@responsive 역할 상세는 desktop 보조 패널과 1100px drawer 경계를 구분한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '데스크톱 프로젝트에서 breakpoint를 검증합니다.')
  await installApi(page)
  await openSharedWorkspace(page)

  const inspector = page.locator('.inspector')
  const addResource = inspector.getByRole('button', { name: '자료 추가' })
  await expect.poll(() => inspector.evaluate((element: HTMLElement) => element.inert)).toBe(false)
  await expect(inspector).not.toHaveAttribute('aria-hidden', 'true')
  await addResource.focus()
  await expect(addResource).toBeFocused()

  await page.setViewportSize({ width: 1100, height: 800 })
  await expect(inspector).toHaveAttribute('aria-hidden', 'true')
  await expect.poll(() => inspector.evaluate((element: HTMLElement) => element.inert)).toBe(true)
  await expect(page.locator('.main-surface')).toBeFocused()

  await page.locator('.sidebar').getByRole('button', { name: '역할' }).click()
  const opener = page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' })
  await opener.click()
  const drawer = page.getByRole('dialog', { name: /선택한 역할 상세: 문제 큐레이터/ })
  await expect(drawer.getByRole('button', { name: '상세 닫기' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(opener).toBeFocused()

  await opener.click()
  const reopenedDrawer = page.getByRole('dialog', { name: /선택한 역할 상세: 문제 큐레이터/ })
  await reopenedDrawer.getByRole('button', { name: '자료 추가' }).focus()
  await page.setViewportSize({ width: 1280, height: 800 })
  await expect(reopenedDrawer).toHaveCount(0)
  await expect.poll(() => inspector.evaluate((element: HTMLElement) => element.inert)).toBe(false)
  await expect(inspector).not.toHaveAttribute('aria-hidden', 'true')
  await expect(page.locator('.main-surface')).toBeFocused()
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
  await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘' }).click()

  const todayChecklist = page.getByRole('region', { name: '3회차 루틴 완료하기' })
  const todayToggle = todayChecklist.getByRole('button', { name: '다음 문제 예고 완료 처리' })
  await todayToggle.scrollIntoViewIfNeeded()
  await expect(todayToggle).toBeInViewport()
  await todayToggle.click()
  await expect(todayChecklist.getByRole('button', { name: '다음 문제 예고 완료 취소' })).toBeVisible()
})

test('@smoke 일시적인 조회 오류에서 다시 시도할 수 있다', async ({ page }) => {
  const api = await installApi(page)
  api.failNextWorkspaceGet()
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '다시 시도하기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
})
