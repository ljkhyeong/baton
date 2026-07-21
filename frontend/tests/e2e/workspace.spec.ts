import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoutineRequest,
  CreateWorkspaceRequest,
  HandoffItem,
  Role,
  Routine,
  WorkspaceProjection,
} from '../../src/features/workspace/types'

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
const ACCESS_KEY = 'e2e-access-key'
const ROTATED_ACCESS_KEY = 'e2e-rotated-access-key'
const WORKSPACE_PATH = `/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const SCOPE_PATH = `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const PENDING_CREATION_STORAGE_PREFIX = 'baton-pending-workspace-creation:v3:'
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
  failNextWorkspaceCreation: () => void
  commitNextWorkspaceCreationThenTimeout: () => void
  expireNextWorkspaceCreationReplay: () => void
  commitNextAccessKeyRotationThenTimeout: () => void
  conflictNextAccessKeyRotation: () => void
  rotateAccessKeyFromAnotherDevice: () => void
  expireNextAccessKeyRotationReplay: () => void
  expireAccessKeyRotationHistory: () => void
  failNextWorkspaceGet: () => void
  failNextRoutineCompletion: () => void
  holdNextRoutineCompletion: () => void
  releaseRoutineCompletion: () => void
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
        status: 'WAITING',
        detail: '그래프 2개 · DP 2개 · 구현 1개',
      },
      {
        id: SECOND_ROUTINE_ID,
        title: '풀이 노트 정리',
        phase: 'AFTER',
        dueLabel: '금요일 21:00',
        ownerRoleId: ROLE_ID,
        status: 'WAITING',
        detail: '이번 회차의 핵심 풀이를 한 문단으로 남깁니다.',
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
    decisions: [],
    handoffItems: [],
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
  const accessKeyRotationResults = new Map<string, string>()
  const expiredAccessKeyRotationResults = new Set<string>()
  const workspaceCreationResults = new Map<string, { teamId: string; seasonId: string; accessKey: string }>()
  let failedGetsRemaining = 0
  let failRoutineCompletion = false
  let routineCompletionGate: Promise<void> | null = null
  let releaseRoutineCompletion = () => {}
  let handoffCompletionGate: Promise<void> | null = null
  let releaseHandoffCompletion = () => {}
  const calls: RecordedCall[] = []

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname
    const headers = request.headers()
    const body = request.postData() ? request.postDataJSON() : undefined
    calls.push({ method, path, headers, body })

    const json = (status: number, value: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    const error = (status: number, code: string, message: string) => json(status, { code, message })

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
      return json(201, created)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/routines`) {
      const input = body as CreateRoutineRequest
      const created: Routine = { id: CREATED_ROUTINE_ID, status: 'WAITING', ...input }
      projection.routines.push(created)
      return json(201, created)
    }

    const routineCompletion = path.match(new RegExp(`^${SCOPE_PATH}/routines/([^/]+)/completion$`))
    if (method === 'PATCH' && routineCompletion) {
      const gate = routineCompletionGate
      routineCompletionGate = null
      if (gate) await gate
      if (failRoutineCompletion) {
        failRoutineCompletion = false
        return error(503, 'ROUTINE_COMPLETION_FAILED', '루틴 상태를 저장하지 못했습니다.')
      }
      const routine = projection.routines.find((candidate) => candidate.id === routineCompletion[1])
      if (!routine) return error(404, 'ROUTINE_NOT_FOUND', '루틴을 찾을 수 없습니다.')
      routine.status = (body as { completed: boolean }).completed ? 'DONE' : 'WAITING'
      return json(200, routine)
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
      return json(201, created)
    }

    if (method === 'POST' && path === `${SCOPE_PATH}/handoff-items`) {
      const input = body as CreateHandoffItemRequest
      const created: HandoffItem = { id: CREATED_HANDOFF_ID, completed: false, ...input }
      projection.handoffItems.push(created)
      return json(201, created)
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
  })

  return {
    calls,
    projection: () => structuredClone(projection),
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
    failNextWorkspaceGet: () => { failedGetsRemaining = 2 },
    failNextRoutineCompletion: () => { failRoutineCompletion = true },
    holdNextRoutineCompletion: () => {
      routineCompletionGate = new Promise((resolve) => { releaseRoutineCompletion = resolve })
    },
    releaseRoutineCompletion: () => releaseRoutineCompletion(),
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

async function recordedCall(api: ApiHarness, method: string, path: string) {
  await expect.poll(() => api.calls.filter((call) => call.method === method && call.path === path).length).toBeGreaterThan(0)
  const call = [...api.calls].reverse().find((candidate) => candidate.method === method && candidate.path === path)
  expect(call, `${method} ${path} 요청 기록`).toBeTruthy()
  return call!
}

function expectScopedCall(call: RecordedCall, body?: unknown, accessKey = ACCESS_KEY) {
  expect(call.headers['x-baton-access-key']).toBe(accessKey)
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

  await expect(page.getByRole('button', { name: /질문 큐레이터/ })).toBeVisible()
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
  await expect(page.getByRole('button', { name: /질문 큐레이터/ })).toBeVisible()
  expect(await page.evaluate(() => ['baton-roles', 'baton-routines', 'baton-decisions', 'baton-handoff'].map((key) => localStorage.getItem(key)))).toEqual([null, null, null, null])
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

test('@operations 루틴을 만들고 완료 상태를 서버에 저장한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
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

  api.holdNextRoutineCompletion()
  const createdRoutineToggle = page.locator('.routine-row').filter({ hasText: '회고 질문 준비' }).locator('.check-button')
  const existingRoutineToggle = page.locator('.routine-row').filter({ hasText: '문제 5개 선정' }).locator('.check-button')
  await createdRoutineToggle.click()
  await expect(createdRoutineToggle).toBeDisabled()
  await expect(existingRoutineToggle).toBeDisabled()
  api.releaseRoutineCompletion()
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
  const completionCall = await recordedCall(api, 'PATCH', `${SCOPE_PATH}/routines/${CREATED_ROUTINE_ID}/completion`)
  expectScopedCall(completionCall, { completed: true })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
})

test('@operations 루틴 완료 저장 실패를 서버 상태로 되돌리고 알린다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  api.failNextRoutineCompletion()
  await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()

  await expect(page.getByRole('status')).toHaveText(/완료 상태를 바꾸지 못했어요.*루틴 상태를 저장하지 못했습니다/)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
  expect(api.projection().routines.find((routine) => routine.id === ROUTINE_ID)?.status).toBe('WAITING')
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
