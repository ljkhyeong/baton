import { expect } from '@playwright/test'
import type { Locator, Page, Route } from '@playwright/test'
import type {
  CancelRoleHandoffRequest,
  ConfirmRoleHandoffRequest,
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
  PrepareRoleHandoffRequest,
  Role,
  RoleHandoff,
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
  UpdateRoutineArchiveRequest,
  UpdateRoundScheduleRequest,
  UpdateSeasonRoundRequest,
  TransferRoleHandoffRequest,
  WorkspaceProjection,
} from '../../../src/features/workspace/types'
import type { ContentCreationOperation } from '../../../src/features/workspace/pendingContentCreation'

const fixtureUuid = (sequence: number) => `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`

export const TEAM_ID = fixtureUuid(1)
export const SEASON_ID = fixtureUuid(2)
export const MEMBER_ONE_ID = fixtureUuid(11)
export const MEMBER_TWO_ID = fixtureUuid(12)
export const MEMBER_THREE_ID = fixtureUuid(13)
const CREATED_MEMBER_ID = fixtureUuid(14)
export const ROLE_ID = fixtureUuid(21)
const CREATED_ROLE_ID = fixtureUuid(22)
export const SECOND_ROLE_ID = fixtureUuid(23)
export const ROUTINE_ID = fixtureUuid(31)
export const SECOND_ROUTINE_ID = fixtureUuid(32)
const CREATED_ROUTINE_ID = fixtureUuid(33)
export const DECISION_ID = fixtureUuid(41)
const CREATED_DECISION_ID = fixtureUuid(42)
export const HANDOFF_ONE_ID = fixtureUuid(51)
export const HANDOFF_TWO_ID = fixtureUuid(52)
export const CREATED_HANDOFF_ID = fixtureUuid(53)
export const CREATED_ROLE_RESOURCE_ID = fixtureUuid(54)
export const ROLE_HANDOFF_ID = fixtureUuid(55)
export const SECOND_ROLE_RESOURCE_ID = fixtureUuid(56)
export const ROUND_ONE_ID = fixtureUuid(61)
export const ROUND_TWO_ID = fixtureUuid(62)
export const CREATED_ROUND_ID = fixtureUuid(63)
export const AUTOMATIC_ROUND_ID = fixtureUuid(64)
const ROUND_ONE_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(71)
const ROUND_ONE_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(72)
const ROUND_TWO_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(73)
export const ROUND_TWO_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(74)
const CREATED_ROUND_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(75)
const CREATED_ROUND_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(76)
export const CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID = fixtureUuid(77)
export const AUTOMATIC_ROUND_ROUTINE_ONE_EXECUTION_ID = fixtureUuid(78)
export const AUTOMATIC_ROUND_ROUTINE_TWO_EXECUTION_ID = fixtureUuid(79)
export const ACCESS_KEY = 'e2e-access-key'
export const ROTATED_ACCESS_KEY = 'e2e-rotated-access-key'
export const SECOND_ROTATED_ACCESS_KEY = 'e2e-second-rotated-access-key'
export const WORKSPACE_PATH = `/teams/${TEAM_ID}/seasons/${SEASON_ID}`
export const SCOPE_PATH = `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}`
export const CONTENT_CREATION_PATHS: Record<ContentCreationOperation, string> = {
  member: `${SCOPE_PATH}/members`,
  role: `${SCOPE_PATH}/roles`,
  routine: `${SCOPE_PATH}/routines`,
  round: `${SCOPE_PATH}/rounds`,
  decision: `${SCOPE_PATH}/decisions`,
  handoffItem: `${SCOPE_PATH}/handoff-items`,
  roleResource: `${SCOPE_PATH}/role-resources`,
  roleHandoff: `${SCOPE_PATH}/roles/${ROLE_ID}/handoffs`,
}
export const PENDING_CREATION_STORAGE_PREFIX = 'baton-pending-workspace-creation:v3:'
export const PENDING_CONTENT_CREATION_STORAGE_PREFIX = 'baton-pending-content-creation:v1:'
const CONTENT_CREATION_CLEANUP_MARKER_STORAGE_KEY =
  'baton-content-creation-cleanup-required:v1'
export const PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY = `baton-pending-access-key-change:v1:${TEAM_ID}`
export const LEGACY_PENDING_CREATION_STORAGE_KEY = 'baton-pending-workspace-creation:v1'

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

export function contrastRatio(foreground: string, background: string) {
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

export async function expectVisibleFocus(locator: Locator, background: string) {
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
  returnMalformedNextWorkspaceCreationResponse: () => void
  expireNextWorkspaceCreationReplay: () => void
  commitNextAccessKeyRotationThenTimeout: () => void
  returnMalformedNextAccessKeyRotationResponse: () => void
  conflictNextAccessKeyRotation: () => void
  rotateAccessKeyFromAnotherDevice: () => void
  expireNextAccessKeyRotationReplay: () => void
  expireAccessKeyRotationHistory: () => void
  holdAccessKeyRotations: () => void
  releaseAccessKeyRotations: () => void
  commitNextContentCreationThenTimeout: (operation: ContentCreationOperation) => void
  returnMalformedNextContentCreationResponse: (operation: ContentCreationOperation) => void
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
  holdNextRoutineArchive: () => void
  releaseRoutineArchive: () => void
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

export function makeProjection(): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: '알고리즘 한 바퀴' , accountAccessEnabled: false, permission: null },
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
        previousRoleId: null,
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
        archivedAt: null,
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
        archivedAt: null,
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
        textFormat: 'PLAIN_TEXT',
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
        createdAt: '2026-07-04T03:00:00Z',
        archivedAt: null,
      },
      {
        id: HANDOFF_TWO_ID,
        roleId: ROLE_ID,
        label: '자주 생기는 문제와 대응법',
        category: 'ADVICE',
        completed: false,
        createdAt: null,
        archivedAt: null,
      },
    ],
    roleHandoffs: [],
    resources: [],
    continuitySignals: [],
  }
}

function projectionFromOnboarding(request: CreateWorkspaceRequest): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: request.teamName , accountAccessEnabled: false, permission: null },
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
    roleHandoffs: [],
    resources: [],
    continuitySignals: [],
  }
}

export async function installApi(page: Page, initialProjection = makeProjection()): Promise<ApiHarness> {
  let projection = structuredClone(initialProjection)
  let activeAccessKey = ACCESS_KEY
  let failWorkspaceCreation = false
  let workspaceCreationGate: Promise<void> | null = null
  let releaseWorkspaceCreation = () => {}
  let rejectWorkspaceCreationAsInvalidInput = false
  let commitWorkspaceCreationThenTimeout = false
  let returnMalformedWorkspaceCreationResponse = false
  let expireWorkspaceCreationReplay = false
  let commitRotationThenTimeout = false
  let returnMalformedAccessKeyRotationResponse = false
  let conflictAccessKeyRotation = false
  let expireAccessKeyRotationReplay = false
  let accessKeyRotationGate: Promise<void> | null = null
  let releaseAccessKeyRotations = () => {}
  let contentCreationToCommitThenTimeout: ContentCreationOperation | null = null
  let contentCreationToReturnMalformed: ContentCreationOperation | null = null
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
  let routineArchiveGate: Promise<void> | null = null
  let releaseRoutineArchive = () => {}
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

    const json = (status: number, value: unknown) => route.fulfill({ status, json: value })
    const error = (status: number, code: string, message: string) => json(status, { code, message })
    const roleHandoffPreparation = path.match(
      new RegExp(`^${SCOPE_PATH}/roles/([^/]+)/handoffs$`),
    )
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
                  : roleHandoffPreparation
                    ? 'roleHandoff'
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
      if (returnMalformedWorkspaceCreationResponse) {
        returnMalformedWorkspaceCreationResponse = false
        return json(201, {})
      }
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
    if (!projection.team.accountAccessEnabled && headers['x-baton-access-key'] !== activeAccessKey) return error(403, 'WORKSPACE_ACCESS_DENIED', '워크스페이스 접근 권한이 없습니다.')

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
      if (contentCreationToReturnMalformed === operation) {
        contentCreationToReturnMalformed = null
        return json(201, {
          ...(typeof created === 'object' && created !== null ? created : {}),
          id: 'not-a-uuid',
          name: '',
        })
      }
      if (contentCreationToCommitThenTimeout === operation) {
        contentCreationToCommitThenTimeout = null
        return error(504, 'CONTENT_CREATION_TIMEOUT', '저장 결과를 확인하지 못했습니다.')
      }
      return json(201, created)
    }

    if (method === 'GET' && path === `${SCOPE_PATH}/resource-reviews`) return json(200, { teamId: TEAM_ID, seasonId: SEASON_ID, today: '2026-09-05', timeZone: projection.season.timeZone, resources: [] })

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
      if (returnMalformedAccessKeyRotationResponse) {
        returnMalformedAccessKeyRotationResponse = false
        return json(200, {})
      }
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
      const created: Role = { id: CREATED_ROLE_ID, previousRoleId: null, ...input }
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
      const updated: Role = { ...projection.roles[roleIndex]!, ...(body as UpdateRoleRequest) }
      projection.roles[roleIndex] = updated
      return json(200, updated)
    }

    if (method === 'POST' && roleHandoffPreparation) {
      const role = projection.roles.find((candidate) => candidate.id === roleHandoffPreparation[1])
      if (!role) return error(404, 'ROLE_NOT_FOUND', '역할을 찾을 수 없습니다.')
      if (!role.currentMemberId || !role.assignmentStartDate) {
        return error(
          409,
          'ROLE_HANDOFF_STATE_CONFLICT',
          '현재 담당자와 담당 시작일이 있는 역할만 인수인계를 준비할 수 있습니다.',
        )
      }
      if (projection.roleHandoffs.some((handoff) =>
        handoff.roleId === role.id
        && (handoff.status === 'PREPARING' || handoff.status === 'TRANSFERRED'))) {
        return error(409, 'ROLE_HANDOFF_STATE_CONFLICT', '이미 진행 중인 역할 인수인계가 있습니다.')
      }
      const input = body as PrepareRoleHandoffRequest
      const handoff: RoleHandoff = {
        id: ROLE_HANDOFF_ID,
        roleId: role.id,
        fromMemberId: role.currentMemberId,
        toMemberId: input.toMemberId,
        outgoingAssignmentStartDate: role.assignmentStartDate,
        outgoingAssignmentEndDate: role.assignmentEndDate,
        incomingAssignmentStartDate: input.incomingAssignmentStartDate,
        incomingAssignmentEndDate: input.incomingAssignmentEndDate ?? null,
        status: 'PREPARING',
        preparedAt: '2026-07-22T09:00:00Z',
        transferredAt: null,
        acceptedAt: null,
        cancelledAt: null,
        transferredByMemberId: null,
        acceptedByMemberId: null,
        cancelledByMemberId: null,
        activeItemCount: null,
        incompleteItemCount: null,
        resourceCount: null,
        warningAcknowledged: false,
      }
      role.nextMemberId = input.toMemberId
      projection.roleHandoffs.unshift(handoff)
      return finishContentCreation('roleHandoff', {
        role: structuredClone(role),
        handoff: structuredClone(handoff),
      })
    }

    const roleHandoffTransfer = path.match(
      new RegExp(`^${SCOPE_PATH}/roles/([^/]+)/handoffs/([^/]+)/transfer$`),
    )
    if (method === 'PATCH' && roleHandoffTransfer) {
      const role = projection.roles.find((candidate) => candidate.id === roleHandoffTransfer[1])
      const handoff = projection.roleHandoffs.find((candidate) =>
        candidate.id === roleHandoffTransfer[2] && candidate.roleId === roleHandoffTransfer[1])
      if (!role || !handoff) {
        return error(409, 'ROLE_HANDOFF_STATE_CONFLICT', '역할 인수인계를 전달할 수 없습니다.')
      }
      const input = body as TransferRoleHandoffRequest
      const activeItems = projection.handoffItems.filter((item) =>
        item.roleId === role.id && !item.archivedAt)
      const incompleteItemCount = activeItems.filter((item) => !item.completed).length
      const resourceCount = projection.resources.filter((resource) =>
        resource.roleId === role.id).length
      const hasWarning = activeItems.length === 0
        || incompleteItemCount > 0
        || resourceCount === 0
      if (hasWarning && !input.warningAcknowledged) {
        return error(
          409,
          'ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED',
          '준비도 경고를 확인해야 역할 인수인계를 전달할 수 있습니다.',
        )
      }
      if (handoff.status !== 'PREPARING'
        || input.confirmedByMemberId !== handoff.fromMemberId) {
        return error(409, 'ROLE_HANDOFF_STATE_CONFLICT', '역할 인수인계 상태가 먼저 바뀌었습니다.')
      }
      handoff.status = 'TRANSFERRED'
      handoff.transferredAt = '2026-07-22T09:10:00Z'
      handoff.transferredByMemberId = input.confirmedByMemberId
      handoff.activeItemCount = activeItems.length
      handoff.incompleteItemCount = incompleteItemCount
      handoff.resourceCount = resourceCount
      handoff.warningAcknowledged = input.warningAcknowledged
      return json(200, {
        role: structuredClone(role),
        handoff: structuredClone(handoff),
      })
    }

    const roleHandoffAcceptance = path.match(
      new RegExp(`^${SCOPE_PATH}/roles/([^/]+)/handoffs/([^/]+)/acceptance$`),
    )
    if (method === 'PATCH' && roleHandoffAcceptance) {
      const role = projection.roles.find((candidate) => candidate.id === roleHandoffAcceptance[1])
      const handoff = projection.roleHandoffs.find((candidate) =>
        candidate.id === roleHandoffAcceptance[2] && candidate.roleId === roleHandoffAcceptance[1])
      const input = body as ConfirmRoleHandoffRequest
      if (!role || !handoff || handoff.status !== 'TRANSFERRED'
        || input.confirmedByMemberId !== handoff.toMemberId) {
        return error(409, 'ROLE_HANDOFF_STATE_CONFLICT', '역할 인수인계를 수락할 수 없습니다.')
      }
      handoff.status = 'ACCEPTED'
      handoff.acceptedAt = '2026-07-22T09:20:00Z'
      handoff.acceptedByMemberId = input.confirmedByMemberId
      role.currentMemberId = handoff.toMemberId
      role.nextMemberId = null
      role.assignmentStartDate = handoff.incomingAssignmentStartDate
      role.assignmentEndDate = handoff.incomingAssignmentEndDate
      return json(200, {
        role: structuredClone(role),
        handoff: structuredClone(handoff),
      })
    }

    const roleHandoffCancellation = path.match(
      new RegExp(`^${SCOPE_PATH}/roles/([^/]+)/handoffs/([^/]+)/cancellation$`),
    )
    if (method === 'PATCH' && roleHandoffCancellation) {
      const role = projection.roles.find((candidate) => candidate.id === roleHandoffCancellation[1])
      const handoff = projection.roleHandoffs.find((candidate) =>
        candidate.id === roleHandoffCancellation[2] && candidate.roleId === roleHandoffCancellation[1])
      const input = body as CancelRoleHandoffRequest
      if (!role || !handoff
        || (handoff.status !== 'PREPARING' && handoff.status !== 'TRANSFERRED')
        || input.confirmedByMemberId !== handoff.fromMemberId) {
        return error(409, 'ROLE_HANDOFF_STATE_CONFLICT', '역할 인수인계를 취소할 수 없습니다.')
      }
      handoff.status = 'CANCELLED'
      handoff.cancelledAt = '2026-07-22T09:20:00Z'
      handoff.cancelledByMemberId = input.confirmedByMemberId
      role.nextMemberId = null
      return json(200, {
        role: structuredClone(role),
        handoff: structuredClone(handoff),
      })
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/routines`) {
      const input = body as CreateRoutineRequest
      const created: Routine = {
        id: CREATED_ROUTINE_ID,
        ...input,
        deadlineDayOffset: input.deadlineDayOffset ?? null,
        deadlineTime: input.deadlineTime ? serializeLocalTime(input.deadlineTime) : null,
        archivedAt: null,
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
      const routineExecutions: RoutineExecution[] = projection.routines
        .filter((routine) => !routine.archivedAt)
        .map((routine, index) => ({
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
      if (routineIndex < 0) return error(404, 'ROUTINE_NOT_FOUND', '반복 업무를 찾을 수 없습니다.')
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

    const routineArchive = path.match(new RegExp(`^${SCOPE_PATH}/routines/([^/]+)/archive$`))
    if (method === 'PATCH' && routineArchive) {
      const gate = routineArchiveGate
      routineArchiveGate = null
      if (gate) await gate
      const routine = projection.routines.find((candidate) => candidate.id === routineArchive[1])
      if (!routine) return error(404, 'ROUTINE_NOT_FOUND', '반복 업무를 찾을 수 없습니다.')
      routine.archivedAt = (body as UpdateRoutineArchiveRequest).archived
        ? '2026-07-21T12:00:00Z'
        : null
      return json(200, routine)
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
        return error(503, 'ROUTINE_COMPLETION_FAILED', '반복 업무 상태를 저장하지 못했습니다.')
      }
      const round = projection.rounds.find((candidate) => candidate.id === routineCompletion[1])
      if (!round || round.archivedAt) {
        return error(404, 'SEASON_ROUND_NOT_FOUND', '시즌 회차를 찾을 수 없습니다.')
      }
      const execution = round.routineExecutions.find((candidate) => candidate.id === routineCompletion[2])
      if (!execution) return error(404, 'ROUTINE_EXECUTION_NOT_FOUND', '반복 업무 실행을 찾을 수 없습니다.')
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
        textFormat: input.textFormat ?? 'PLAIN_TEXT',
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
        textFormat: input.textFormat ?? projection.decisions[decisionIndex]!.textFormat,
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
        createdAt: '2026-07-22T03:00:00Z',
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
        return error(404, 'HANDOFF_ITEM_NOT_FOUND', '인수인계 항목을 찾을 수 없습니다.')
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
      if (!item) return error(404, 'HANDOFF_ITEM_NOT_FOUND', '인수인계 항목을 찾을 수 없습니다.')
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
        createdAt: '2026-07-22T03:00:00Z',
        archivedAt: null,
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
        ...projection.resources[resourceIndex]!,
        ...input,
        description: input.description ?? null,
      }
      projection.resources[resourceIndex] = updated
      return json(200, updated)
    }

    const roleResourceArchive = path.match(
      new RegExp(`^${SCOPE_PATH}/role-resources/([^/]+)/archive$`),
    )
    if (method === 'PATCH' && roleResourceArchive) {
      const resource = projection.resources.find(
        (candidate) => candidate.id === roleResourceArchive[1],
      )
      if (!resource) {
        return error(404, 'ROLE_RESOURCE_NOT_FOUND', '역할 자료를 찾을 수 없습니다.')
      }
      resource.archivedAt = (body as UpdateRecordArchiveRequest).archived
        ? '2026-07-22T12:00:00Z'
        : null
      return json(200, resource)
    }

    const handoffCompletion = path.match(new RegExp(`^${SCOPE_PATH}/handoff-items/([^/]+)/completion$`))
    if (method === 'PATCH' && handoffCompletion) {
      const gate = handoffCompletionGate
      handoffCompletionGate = null
      if (gate) await gate
      if (failHandoffCompletion) {
        failHandoffCompletion = false
        return error(503, 'HANDOFF_COMPLETION_FAILED', '인수인계 상태를 저장하지 못했습니다.')
      }
      const item = projection.handoffItems.find((candidate) => candidate.id === handoffCompletion[1])
      if (!item) return error(404, 'HANDOFF_ITEM_NOT_FOUND', '인수인계 항목을 찾을 수 없습니다.')
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
    attachPage: async (peerPage) => {
      await peerPage.route('**/api/v1/**', handleApiRoute)
    },
    addRoundFromAnotherDevice: (round) => {
      projection.rounds.push(structuredClone(round))
    },
    failNextWorkspaceCreation: () => { failWorkspaceCreation = true },
    holdNextWorkspaceCreation: () => {
      const gate = Promise.withResolvers<void>()
      workspaceCreationGate = gate.promise
      releaseWorkspaceCreation = gate.resolve
    },
    releaseWorkspaceCreation: () => {
      releaseWorkspaceCreation()
      releaseWorkspaceCreation = () => {}
    },
    rejectNextWorkspaceCreationAsInvalidInput: () => { rejectWorkspaceCreationAsInvalidInput = true },
    commitNextWorkspaceCreationThenTimeout: () => { commitWorkspaceCreationThenTimeout = true },
    returnMalformedNextWorkspaceCreationResponse: () => {
      returnMalformedWorkspaceCreationResponse = true
    },
    expireNextWorkspaceCreationReplay: () => { expireWorkspaceCreationReplay = true },
    commitNextAccessKeyRotationThenTimeout: () => { commitRotationThenTimeout = true },
    returnMalformedNextAccessKeyRotationResponse: () => {
      returnMalformedAccessKeyRotationResponse = true
    },
    conflictNextAccessKeyRotation: () => { conflictAccessKeyRotation = true },
    rotateAccessKeyFromAnotherDevice: () => { activeAccessKey = ROTATED_ACCESS_KEY },
    expireNextAccessKeyRotationReplay: () => { expireAccessKeyRotationReplay = true },
    expireAccessKeyRotationHistory: () => {
      accessKeyRotationResults.forEach((_accessKey, idempotencyKey) => expiredAccessKeyRotationResults.add(idempotencyKey))
      accessKeyRotationResults.clear()
    },
    holdAccessKeyRotations: () => {
      const gate = Promise.withResolvers<void>()
      accessKeyRotationGate = gate.promise
      releaseAccessKeyRotations = gate.resolve
    },
    releaseAccessKeyRotations: () => {
      accessKeyRotationGate = null
      releaseAccessKeyRotations()
      releaseAccessKeyRotations = () => {}
    },
    commitNextContentCreationThenTimeout: (operation) => { contentCreationToCommitThenTimeout = operation },
    returnMalformedNextContentCreationResponse: (operation) => {
      contentCreationToReturnMalformed = operation
    },
    rejectNextContentCreationAsReused: (operation) => { contentCreationToRejectAsReused = operation },
    rejectNextMemberAsConflict: () => { rejectMemberAsConflict = true },
    holdNextContentCreation: (operation) => {
      const gate = Promise.withResolvers<void>()
      contentCreationGate = {
        operation,
        pending: gate.promise,
      }
      releaseContentCreation = gate.resolve
    },
    releaseContentCreation: () => {
      releaseContentCreation()
      releaseContentCreation = () => {}
    },
    failNextWorkspaceGet: () => { failedGetsRemaining = 2 },
    makeWorkspaceGetsUnavailable: () => { workspaceGetsUnavailable = true },
    restoreWorkspaceGets: () => { workspaceGetsUnavailable = false },
    holdNextRoleUpdate: () => {
      const gate = Promise.withResolvers<void>()
      roleUpdateGate = gate.promise
      releaseRoleUpdate = gate.resolve
    },
    releaseRoleUpdate: () => releaseRoleUpdate(),
    conflictNextRoleUpdate: (role) => {
      nextRoleConflict = structuredClone(role)
    },
    holdNextRoutineUpdate: () => {
      const gate = Promise.withResolvers<void>()
      routineUpdateGate = gate.promise
      releaseRoutineUpdate = gate.resolve
    },
    releaseRoutineUpdate: () => releaseRoutineUpdate(),
    conflictNextRoutineUpdate: (routine) => {
      nextRoutineConflict = structuredClone(routine)
    },
    holdNextRoutineArchive: () => {
      const gate = Promise.withResolvers<void>()
      routineArchiveGate = gate.promise
      releaseRoutineArchive = gate.resolve
    },
    releaseRoutineArchive: () => releaseRoutineArchive(),
    conflictNextRoleResourceUpdate: (resource) => {
      nextRoleResourceConflict = structuredClone(resource)
    },
    failNextRoutineCompletion: () => { failRoutineCompletion = true },
    conflictNextRoutineCompletion: (completed) => { nextRoutineCompletionConflict = completed },
    holdNextRoutineCompletion: () => {
      const gate = Promise.withResolvers<void>()
      routineCompletionGate = gate.promise
      releaseRoutineCompletion = gate.resolve
    },
    releaseRoutineCompletion: () => releaseRoutineCompletion(),
    holdNextHandoffCompletion: () => {
      const gate = Promise.withResolvers<void>()
      handoffCompletionGate = gate.promise
      releaseHandoffCompletion = gate.resolve
    },
    failNextHandoffCompletion: () => { failHandoffCompletion = true },
    conflictNextHandoffCompletion: (completed) => { nextHandoffCompletionConflict = completed },
    releaseHandoffCompletion: () => releaseHandoffCompletion(),
    holdWorkspaceGets: () => {
      const gate = Promise.withResolvers<void>()
      workspaceGetGate = gate.promise
      releaseWorkspaceGets = gate.resolve
    },
    releaseWorkspaceGets: () => {
      workspaceGetGate = null
      releaseWorkspaceGets()
    },
  }
}

export async function openSharedWorkspace(page: Page) {
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: /이번 회차 미완료 업무 \d+개/ })).toBeVisible()
  await expect(page.getByLabel('회차', { exact: true })).toHaveValue(ROUND_TWO_ID)
}

export async function openMemberCreationDialog(page: Page) {
  await page.getByRole('button', { name: '구성원 관리' }).click()
  const managementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await managementDialog.getByRole('button', { name: '구성원 추가' }).click()
  return page.getByRole('dialog', { name: '구성원 추가' })
}

export function navigation(page: Page, projectName: string) {
  return page.getByRole('navigation', { name: projectName === 'mobile' ? '모바일 주 메뉴' : '주 메뉴' })
}

export async function blockBrowserStorage(page: Page) {
  await page.addInitScript(() => {
    const unavailable = () => { throw new DOMException('Storage disabled', 'SecurityError') }
    Storage.prototype.getItem = unavailable
    Storage.prototype.setItem = unavailable
    Storage.prototype.removeItem = unavailable
  })
}

export async function blockContentCreationStorage(page: Page) {
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

export async function failNextJournalCleanup(
  page: Page,
  target: { storageKey?: string; storagePrefix?: string },
  failureStateKey: string,
) {
  await page.addInitScript(({ targetStorageKey, targetStoragePrefix, stateKey }) => {
    const originalRemoveItem = Storage.prototype.removeItem
    const matches = (key: string) => key === targetStorageKey
      || Boolean(targetStoragePrefix && key.startsWith(targetStoragePrefix))

    Storage.prototype.removeItem = function removeItem(key) {
      if (matches(key) && sessionStorage.getItem(stateKey) === null) {
        sessionStorage.setItem(stateKey, 'failed')
        throw new DOMException('Storage removal disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
  }, {
    targetStorageKey: target.storageKey,
    targetStoragePrefix: target.storagePrefix,
    stateKey: failureStateKey,
  })
}

export const CONTENT_CREATION_CLEANUP_RELEASE_KEY = 'baton-e2e-content-cleanup-released'

export async function blockContentCreationCleanupUntilReleased(page: Page) {
  await page.addInitScript(({ prefix, releaseKey }) => {
    const originalRemoveItem = Storage.prototype.removeItem
    const cleanupBlocked = (key: string) => key.startsWith(prefix)
      && sessionStorage.getItem(releaseKey) !== 'true'

    Storage.prototype.removeItem = function removeItem(key) {
      if (cleanupBlocked(key)) {
        throw new DOMException('Storage removal disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
  }, {
    prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX,
    releaseKey: CONTENT_CREATION_CLEANUP_RELEASE_KEY,
  })
}

export const CONTENT_CREATION_GUARD_FAILURE_RELEASE_KEY =
  'baton-e2e-content-guard-failure-released'

export async function failContentCreationMarkerAndCleanupUntilReleased(page: Page) {
  await page.addInitScript(({ prefix, markerKey, releaseKey }) => {
    const originalRemoveItem = Storage.prototype.removeItem
    const originalSetItem = Storage.prototype.setItem
    const blocked = () => sessionStorage.getItem(releaseKey) !== 'true'

    Storage.prototype.removeItem = function removeItem(key) {
      if (blocked() && key.startsWith(prefix)) {
        throw new DOMException('Storage removal disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
    Storage.prototype.setItem = function setItem(key, value) {
      if (blocked() && (key === markerKey
        || (key.startsWith(prefix)
          && value.includes('"cleanupRequired":true')))) {
        throw new DOMException('Storage marker disabled', 'SecurityError')
      }
      originalSetItem.call(this, key, value)
    }
  }, {
    prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX,
    markerKey: CONTENT_CREATION_CLEANUP_MARKER_STORAGE_KEY,
    releaseKey: CONTENT_CREATION_GUARD_FAILURE_RELEASE_KEY,
  })
}

export async function failNextAccessKeyRotationCleanup(page: Page) {
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

export function pendingWorkspaceCreationEntry(
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

export async function seedPendingWorkspaceCreations(
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

export async function fillOnboardingForm(page: Page, request: CreateWorkspaceRequest) {
  await page.getByLabel('팀 이름').fill(request.teamName)
  await page.getByLabel('시즌 이름').fill(request.seasonName)
  await page.getByLabel('시작일').fill(request.startDate)
  await page.getByLabel('종료일').fill(request.endDate)
  await page.getByLabel('구성원 이름').fill(request.memberNames.join('\n'))
}

export async function pendingCreationEntries(page: Page) {
  return page.evaluate((prefix) => {
    const entries: PendingWorkspaceCreationEntry[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (!key?.startsWith(prefix)) continue
      try {
        const parsed = JSON.parse(localStorage.getItem(key) ?? 'null')
        if (parsed) entries.push(parsed)
      } catch {
        // 형식이 잘못된 값은 유효한 생성 대기 기록으로 보지 않는다.
      }
    }
    return entries
  }, PENDING_CREATION_STORAGE_PREFIX)
}

export async function pendingContentCreationEntries(page: Page) {
  return page.evaluate((prefix) => {
    const entries: {
      teamId: string
      seasonId: string
      operation: ContentCreationOperation
      normalizedPayload: string
      idempotencyKey: string
      createdAt: number
      requestGuard?: true
      cleanupRequired?: true
    }[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (!key?.startsWith(prefix)) continue
      try {
        const parsed = JSON.parse(localStorage.getItem(key) ?? 'null')
        if (parsed) entries.push(parsed)
      } catch {
        // 형식이 잘못된 값은 유효한 생성 대기 기록으로 보지 않는다.
      }
    }
    return entries
  }, PENDING_CONTENT_CREATION_STORAGE_PREFIX)
}

export async function recordedCall(api: ApiHarness, method: string, path: string) {
  await expect.poll(() => api.calls.filter((call) => call.method === method && call.path === path).length).toBeGreaterThan(0)
  const call = [...api.calls].reverse().find((candidate) => candidate.method === method && candidate.path === path)
  expect(call, `${method} ${path} 요청 기록`).toBeTruthy()
  return call!
}

export function expectScopedCall(call: RecordedCall, body?: unknown, accessKey = ACCESS_KEY) {
  expect(call.headers['x-baton-access-key']).toBe(accessKey)
  if (call.method === 'POST') expect(call.headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  if (body !== undefined) expect(call.body).toEqual(body)
}

export async function expectPendingCreationDialogLocked({
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
