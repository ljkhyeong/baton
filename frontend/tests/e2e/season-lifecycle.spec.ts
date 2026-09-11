import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import type {
  CreateNextSeasonRequest,
  CreateRoleResourceRequest,
  RoleResource,
  Role,
  Routine,
  SeasonSummary,
  WorkspaceProjection,
} from '../../src/features/workspace/types'

const fixtureUuid = (sequence: number) =>
  `10000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`

const TEAM_ID = fixtureUuid(1)
const SOURCE_SEASON_ID = fixtureUuid(2)
const NEXT_SEASON_ID = fixtureUuid(3)
const MEMBER_ID = fixtureUuid(11)
const ROLE_ID = fixtureUuid(21)
const COPIED_ROLE_ID = fixtureUuid(22)
const ROUTINE_ID = fixtureUuid(31)
const SECOND_ROUTINE_ID = fixtureUuid(32)
const COPIED_ROUTINE_ID = fixtureUuid(33)
const ARCHIVED_ROUTINE_ID = fixtureUuid(34)
const ROUND_ID = fixtureUuid(41)
const EXECUTION_ID = fixtureUuid(42)
const DECISION_ID = fixtureUuid(51)
const HANDOFF_ID = fixtureUuid(61)
const RESOURCE_ID = fixtureUuid(71)
const ACCESS_KEY = 'season-lifecycle-e2e-access-key'
const SOURCE_SCOPE = `/api/v1/teams/${TEAM_ID}/seasons/${SOURCE_SEASON_ID}`
const WORKSPACE_URL = `/teams/${TEAM_ID}/seasons/${SOURCE_SEASON_ID}`
const ENDED_AT = '2026-07-30T03:00:00Z'
const PENDING_SEASON_SUCCESSOR_STORAGE_KEY =
  `baton-pending-season-successor:v1:${TEAM_ID}`

const sourceSeason = (endedAt: string | null = null): SeasonSummary => ({
  id: SOURCE_SEASON_ID,
  name: '2026 여름 시즌',
  startDate: '2026-07-01',
  endDate: '2026-09-30',
  endedAt,
  previousSeasonId: null,
  timeZone: 'Asia/Seoul',
  roundSchedule: null,
})

const nextSeason = (): SeasonSummary => ({
  id: NEXT_SEASON_ID,
  name: '2026 가을 시즌',
  startDate: '2026-10-01',
  endDate: '2026-12-31',
  endedAt: null,
  previousSeasonId: SOURCE_SEASON_ID,
  timeZone: 'Asia/Seoul',
  roundSchedule: null,
})

const role = (id = ROLE_ID): Role => ({
  id,
  name: '문제 큐레이터',
  purpose: '이번 주 학습 목표에 맞는 문제를 고릅니다.',
  currentMemberId: id === ROLE_ID ? MEMBER_ID : null,
  previousRoleId: id === COPIED_ROLE_ID ? ROLE_ID : null,
  nextMemberId: null,
  assignmentStartDate: id === ROLE_ID ? '2026-07-01' : null,
  assignmentEndDate: id === ROLE_ID ? '2026-09-30' : null,
  responsibilities: ['문제 선정', '난이도 확인'],
  risk: null,
})

const routine = (
  id = ROUTINE_ID,
  ownerRoleId = ROLE_ID,
  title = '문제 5개 선정',
  archivedAt: string | null = null,
): Routine => ({
  id,
  title,
  phase: 'BEFORE',
  dueLabel: '수요일 18:00',
  deadlineDayOffset: -1,
  deadlineTime: '18:00:00',
  ownerRoleId,
  detail: '다음 모임의 문제를 고릅니다.',
  archivedAt,
})

function projection(
  season: SeasonSummary,
  seasons: SeasonSummary[],
): WorkspaceProjection {
  const target = season.id === NEXT_SEASON_ID
  const projectionRoles = target ? [role(COPIED_ROLE_ID)] : [role()]
  const projectionRoutines = target
    ? [routine(COPIED_ROUTINE_ID, COPIED_ROLE_ID)]
    : [
        routine(),
        routine(SECOND_ROUTINE_ID, ROLE_ID, '풀이 노트 정리'),
        routine(ARCHIVED_ROUTINE_ID, ROLE_ID, '지난 발표 자료 점검', ENDED_AT),
      ]

  return {
    team: { id: TEAM_ID, name: '알고리즘 한 바퀴' , accountAccessEnabled: false, permission: null },
    season,
    seasons,
    members: [{
      id: MEMBER_ID,
      name: '박민서',
      initials: '민',
      tone: '#d9e4da',
      deactivatedAt: null,
    }],
    roles: projectionRoles,
    routines: projectionRoutines,
    rounds: target ? [] : [{
      id: ROUND_ID,
      name: '1회차',
      meetingDate: '2026-07-10',
      archivedAt: null,
      origin: 'MANUAL',
      scheduledOccurrenceDate: null,
      scheduledAt: null,
      timingStatus: 'IN_PROGRESS',
      routineExecutions: [{
        id: EXECUTION_ID,
        roundId: ROUND_ID,
        routineId: ROUTINE_ID,
        title: '문제 5개 선정',
        phase: 'BEFORE',
        dueLabel: '수요일 18:00',
        ownerRoleId: ROLE_ID,
        detail: '다음 모임의 문제를 고릅니다.',
        status: 'WAITING',
        deadlineAt: '2026-07-09T09:00:00Z',
        timingStatus: 'IN_PROGRESS',
      }],
    }],
    decisions: target ? [] : [{
      id: DECISION_ID,
      title: '격주 회고를 진행한다',
      reason: '운영 문제를 일찍 발견하기 위해서입니다.',
      alternative: '월말에 한 번 모으기',
      authorMemberId: MEMBER_ID,
      authorName: '박민서',
      textFormat: 'PLAIN_TEXT',
      roleIds: [ROLE_ID],
      createdAt: '2026-07-10T03:00:00Z',
      archivedAt: null,
    }],
    handoffItems: target ? [] : [{
      id: HANDOFF_ID,
      roleId: ROLE_ID,
      label: '문제 선정 기준 공유',
      category: 'RESPONSIBILITY',
      completed: false,
      createdAt: '2026-07-10T04:00:00Z',
      archivedAt: null,
    }],
    resources: target ? [] : [{
      id: RESOURCE_ID,
      roleId: ROLE_ID,
      title: '문제 목록',
      url: 'https://example.com/problems',
      description: null,
      createdAt: '2026-07-10T05:00:00Z',
      archivedAt: null,
    }],
    roleHandoffs: [],
    continuitySignals: [],
  }
}

type RecordedSuccessor = {
  body: CreateNextSeasonRequest
  idempotencyKey: string
}

type SeasonApiHarness = {
  successor?: RecordedSuccessor
  successorAttempts: RecordedSuccessor[]
  conflictNextSeasonUpdate: () => void
  endOnNextRoleUpdate: () => void
  holdNextRoleUpdateAsEnded: () => void
  waitForHeldRoleUpdate: () => Promise<void>
  releaseHeldRoleUpdate: () => void
  rejectNextSuccessorAsInvalidInput: () => void
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    headers: { 'X-Request-ID': fixtureUuid(99) },
    json: body,
  })
}

async function attachSeasonApi(
  page: Page,
  initialEndedAt: string | null = null,
  includeNextSeason = true,
): Promise<SeasonApiHarness> {
  let source = sourceSeason(initialEndedAt)
  let target = includeNextSeason ? nextSeason() : null
  let rejectRoleUpdateAsEnded = false
  let heldRoleUpdate: Promise<void> | null = null
  let releaseHeldRoleUpdate = () => {}
  let heldRoleUpdateStarted = Promise.resolve()
  let markHeldRoleUpdateStarted = () => {}
  let rejectNextSuccessorAsInvalidInput = false
  let rejectNextSeasonUpdateAsConflict = false
  const harness: SeasonApiHarness = {
    successorAttempts: [],
    conflictNextSeasonUpdate: () => {
      rejectNextSeasonUpdateAsConflict = true
    },
    endOnNextRoleUpdate: () => {
      rejectRoleUpdateAsEnded = true
    },
    holdNextRoleUpdateAsEnded: () => {
      rejectRoleUpdateAsEnded = true
      const started = Promise.withResolvers<void>()
      heldRoleUpdateStarted = started.promise
      markHeldRoleUpdateStarted = started.resolve
      const update = Promise.withResolvers<void>()
      heldRoleUpdate = update.promise
      releaseHeldRoleUpdate = update.resolve
    },
    waitForHeldRoleUpdate: () => heldRoleUpdateStarted,
    releaseHeldRoleUpdate: () => releaseHeldRoleUpdate(),
    rejectNextSuccessorAsInvalidInput: () => {
      rejectNextSuccessorAsInvalidInput = true
    },
  }

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname
    const seasons = [source, ...(target ? [target] : [])]

    if (method === 'GET' && path.endsWith('/workspace')) {
      const requestedSeason = path.includes(`/seasons/${NEXT_SEASON_ID}/`)
        ? target
        : source
      if (!requestedSeason) {
        await fulfillJson(route, {
          code: 'SEASON_NOT_FOUND',
          message: '시즌을 찾을 수 없습니다.',
        }, 404)
        return
      }
      await fulfillJson(route, projection(requestedSeason, seasons))
      return
    }

    if (method === 'PUT' && path === SOURCE_SCOPE) {
      const body = request.postDataJSON() as Pick<
        SeasonSummary,
        'name' | 'startDate' | 'endDate'
      >
      if (rejectNextSeasonUpdateAsConflict) {
        rejectNextSeasonUpdateAsConflict = false
        source = { ...source, name: '다른 구성원이 고친 시즌' }
        await fulfillJson(route, {
          code: 'WORKSPACE_CONTENT_CONFLICT',
          message: '다른 구성원이 먼저 시즌을 변경했습니다.',
        }, 409)
        return
      }
      source = { ...source, ...body }
      await fulfillJson(route, source)
      return
    }

    if (method === 'PATCH' && path === `${SOURCE_SCOPE}/ending`) {
      const body = request.postDataJSON() as { ended: boolean }
      source = { ...source, endedAt: body.ended ? ENDED_AT : null }
      await fulfillJson(route, source)
      return
    }

    if (method === 'POST' && path === `${SOURCE_SCOPE}/successor`) {
      const body = request.postDataJSON() as CreateNextSeasonRequest
      const recordedSuccessor = {
        body,
        idempotencyKey: request.headers()['idempotency-key'] ?? '',
      }
      harness.successor = recordedSuccessor
      harness.successorAttempts.push(recordedSuccessor)
      if (rejectNextSuccessorAsInvalidInput) {
        rejectNextSuccessorAsInvalidInput = false
        await fulfillJson(route, {
          code: 'INVALID_INPUT',
          message: '다음 시즌 입력을 확인해 주세요.',
        }, 400)
        return
      }
      source = { ...source, endedAt: source.endedAt ?? ENDED_AT }
      target = {
        id: NEXT_SEASON_ID,
        name: body.name,
        startDate: body.startDate,
        endDate: body.endDate,
        endedAt: null,
        previousSeasonId: SOURCE_SEASON_ID,
        timeZone: source.timeZone,
        roundSchedule: null,
      }
      await fulfillJson(route, {
        sourceSeason: source,
        season: target,
        copiedRoles: [{ sourceRoleId: ROLE_ID, roleId: COPIED_ROLE_ID }],
        copiedRoutines: [{
          sourceRoutineId: ROUTINE_ID,
          routineId: COPIED_ROUTINE_ID,
        }],
      }, 201)
      return
    }

    if (method === 'PUT' && path === `${SOURCE_SCOPE}/roles/${ROLE_ID}`) {
      if (rejectRoleUpdateAsEnded) {
        rejectRoleUpdateAsEnded = false
        markHeldRoleUpdateStarted()
        if (heldRoleUpdate) await heldRoleUpdate
        heldRoleUpdate = null
        source = { ...source, endedAt: ENDED_AT }
        await fulfillJson(route, {
          code: 'SEASON_ENDED',
          message: '종료된 시즌의 기록은 변경할 수 없습니다.',
        }, 409)
        return
      }
      await fulfillJson(route, role())
      return
    }

    await fulfillJson(route, {
      code: 'NOT_FOUND',
      message: `테스트 API가 처리하지 않은 요청입니다: ${method} ${path}`,
    }, 404)
  })

  return harness
}

async function failSeasonSuccessorCleanup(
  page: Page,
  failureStateKey: string,
  failureCount = 1,
) {
  await page.addInitScript(({ storageKey, stateKey, failuresToSimulate }) => {
    const originalRemoveItem = Storage.prototype.removeItem

    Storage.prototype.removeItem = function removeItem(key) {
      const failureCount = Number(sessionStorage.getItem(stateKey) ?? '0')
      if (key === storageKey && failureCount < failuresToSimulate) {
        sessionStorage.setItem(stateKey, String(failureCount + 1))
        throw new DOMException('Storage removal disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
  }, {
    storageKey: PENDING_SEASON_SUCCESSOR_STORAGE_KEY,
    stateKey: failureStateKey,
    failuresToSimulate: failureCount,
  })
}

async function openWorkspace(page: Page) {
  await page.addInitScript(({ teamId, accessKey }) => {
    window.localStorage.setItem(`baton-access-key:${teamId}`, accessKey)
  }, { teamId: TEAM_ID, accessKey: ACCESS_KEY })
  await page.goto(WORKSPACE_URL)
  await expect(page.getByRole('heading', { name: /남은 업무 \d+개/ })).toBeVisible()
}

function seasonSwitcher(page: Page) {
  return page.getByRole('button', { name: /현재 시즌 .*시즌 전환|알고리즘 한 바퀴 .*시즌 전환/ }).first()
}

test('@operations 시즌 정보 충돌도 중앙 복구가 편집기를 닫고 최신 데이터를 불러온다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '중앙 충돌 복구는 데스크톱에서 한 번 검증합니다.')
  const api = await attachSeasonApi(page)
  await openWorkspace(page)

  await seasonSwitcher(page).click()
  await page.getByRole('button', { name: '시즌 정보 수정' }).click()
  const editDialog = page.getByRole('dialog', { name: '시즌 정보 수정' })
  await editDialog.getByLabel('시즌 이름').fill('내가 고친 시즌')
  api.conflictNextSeasonUpdate()
  await editDialog.getByRole('button', { name: '시즌 정보 저장' }).click()

  await expect(editDialog).toHaveCount(0)
  await expect(page.locator('.toast[role="status"]')).toContainText(
    '다른 사람이 수정한 내용을 불러왔어요.',
  )
  await seasonSwitcher(page).click()
  await expect(page.getByRole('dialog', { name: '알고리즘 한 바퀴 시즌' }))
    .toContainText('다른 구성원이 고친 시즌')
})

test('@smoke @responsive 시즌 전환은 URL과 화면 상태를 함께 바꾸고 초점을 복원한다', async ({ page }) => {
  await attachSeasonApi(page)
  await openWorkspace(page)

  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await expect(page.getByRole('heading', { name: '역할과 담당자' })).toBeVisible()

  const trigger = seasonSwitcher(page)
  await trigger.focus()
  await trigger.press('Enter')
  const dialog = page.getByRole('dialog', { name: '알고리즘 한 바퀴 시즌' })
  await expect(dialog).toBeVisible()
  await expect.poll(() => dialog.evaluate((element) =>
    element.contains(document.activeElement))).toBe(true)

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()

  await trigger.click()
  await dialog.getByRole('button', { name: /2026 가을 시즌/ }).click()
  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(page.getByRole('heading', { name: '남은 업무 0개' })).toBeVisible()
  await expect(seasonSwitcher(page)).toHaveAccessibleName(/2026 가을 시즌/)
})

test('@operations 종료된 시즌은 기록 변경 동작을 막고 조회·공유·시즌 전환은 유지한다', async ({ page }) => {
  await attachSeasonApi(page, ENDED_AT)
  await openWorkspace(page)

  await expect(page.getByText('이 시즌은 읽기 전용입니다.')).toBeVisible()
  await expect(seasonSwitcher(page)).toBeEnabled()
  await expect(page.getByRole('button', { name: '공유' }).first()).toBeEnabled()
  await expect(page.getByRole('button', { name: '링크 관리' }).first()).toBeEnabled()
  await expect(page.getByRole('button', { name: '업무 추가', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeDisabled()

  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await expect(page.getByRole('button', { name: '구성원 관리' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '역할 추가' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 큐레이터 역할 수정' })).toBeDisabled()

  await page.getByRole('button', { name: '일정', exact: true }).first().click()
  await expect(page.getByRole('button', { name: '반복 업무 추가' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 만들기' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '1회차 회차 보관' })).toBeDisabled()

  await page.getByRole('button', { name: '기록', exact: true }).first().click()
  await expect(page.getByRole('button', { name: '결정 남기기' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '격주 회고를 진행한다 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '격주 회고를 진행한다 보관' })).toBeDisabled()

  await page.getByRole('button', { name: /^인수인계/ }).first().click()
  await expect(page.getByRole('button', { name: '항목 추가' })).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: /문제 선정 기준 공유/ })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 선정 기준 공유 수정' })).toBeDisabled()
})

test("@handoff 다음 시즌 시작 화면을 떠난 뒤 늦은 성공 응답이 현재 화면을 바꾸지 않는다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "후속 시즌 화면 이탈은 데스크톱에서 한 번 검증합니다.")
  await attachSeasonApi(page, null, false)
  const requestStarted = Promise.withResolvers<void>()
  const releaseRequest = Promise.withResolvers<void>()
  await page.route("**" + SOURCE_SCOPE + "/successor", async (route) => {
    requestStarted.resolve()
    await releaseRequest.promise
    await route.fallback()
  }, { times: 1 })
  await openWorkspace(page)
  await seasonSwitcher(page).click()
  await page.getByRole("dialog").getByRole("button", { name: /다음 시즌 시작/ }).click()
  const dialog = page.getByRole("dialog", { name: "다음 시즌 시작" })
  await dialog.getByLabel("다음 시즌 이름").fill("2026 가을 시즌")
  await dialog.getByRole("button", { name: "현재 시즌 종료하고 만들기" }).click()
  await requestStarted.promise

  await page.evaluate(() => {
    window.history.pushState(null, "", "/")
    window.dispatchEvent(new PopStateEvent("popstate"))
  })
  await expect(page.getByRole("heading", { name: "팀 작업 공간 만들기" })).toBeVisible()

  const response = page.waitForResponse("**" + SOURCE_SCOPE + "/successor")
  releaseRequest.resolve()
  await (await response).finished()
  await expect.poll(() => page.evaluate((storageKey) =>
    window.localStorage.getItem(storageKey), PENDING_SEASON_SUCCESSOR_STORAGE_KEY))
    .toBeNull()
  expect(new URL(page.url()).pathname).toBe("/")
})

test('@handoff 다음 시즌 선택은 담당 역할 의존성을 지키고 멱등 요청 뒤 새 시즌으로 이동한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '후속 시즌 계약은 데스크톱에서 한 번 검증합니다.')
  const api = await attachSeasonApi(page, null, false)
  await openWorkspace(page)

  await seasonSwitcher(page).click()
  await page.getByRole('dialog').getByRole('button', { name: /다음 시즌 시작/ }).click()

  const dialog = page.getByRole('dialog', { name: '다음 시즌 시작' })
  const roleCheckbox = dialog.getByRole('checkbox', { name: /^문제 큐레이터/ })
  const firstRoutine = dialog.getByRole('checkbox', { name: /문제 5개 선정/ })
  const secondRoutine = dialog.getByRole('checkbox', { name: /풀이 노트 정리/ })
  const archivedRoutine = dialog.getByRole('checkbox', { name: /지난 발표 자료 점검/ })
  await expect(roleCheckbox).toBeChecked()
  await expect(firstRoutine).toBeChecked()
  await expect(secondRoutine).toBeChecked()
  await expect(archivedRoutine).toHaveCount(0)

  await roleCheckbox.uncheck()
  await expect(firstRoutine).not.toBeChecked()
  await expect(secondRoutine).not.toBeChecked()
  await firstRoutine.check()
  await expect(roleCheckbox).toBeChecked()
  await expect(secondRoutine).not.toBeChecked()

  await dialog.getByLabel('다음 시즌 이름').fill('2026 가을 시즌')
  await dialog.getByRole('button', { name: '현재 시즌 종료하고 만들기' }).click()

  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(page.getByRole('heading', { name: '남은 업무 0개' })).toBeVisible()
  expect(api.successor?.body).toEqual({
    name: '2026 가을 시즌',
    startDate: '2026-10-01',
    endDate: '2026-12-31',
    copyRoleIds: [ROLE_ID],
    copyRoutineIds: [ROUTINE_ID],
  })
  expect(api.successor?.idempotencyKey).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  await expect(page.evaluate((teamId) =>
    window.localStorage.getItem(`baton-pending-season-successor:v1:${teamId}`), TEAM_ID))
    .resolves.toBeNull()
})

test('@handoff 다음 시즌 성공 기록을 삭제하지 못하면 새 시즌 새로고침 뒤 삭제할 수 있다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '시즌 journal 복구는 데스크톱 Chromium에서 한 번 검증합니다.')
  await failSeasonSuccessorCleanup(
    page,
    'baton-e2e-season-successor-success-cleanup-failure',
    2,
  )
  const api = await attachSeasonApi(page, null, false)
  await openWorkspace(page)

  await seasonSwitcher(page).click()
  await page.getByRole('dialog').getByRole('button', { name: /다음 시즌 시작/ }).click()
  const dialog = page.getByRole('dialog', { name: '다음 시즌 시작' })
  await dialog.getByLabel('다음 시즌 이름').fill('2026 가을 시즌')
  await dialog.getByRole('button', { name: '현재 시즌 종료하고 만들기' }).click()

  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(page.evaluate((storageKey) =>
    window.localStorage.getItem(storageKey), PENDING_SEASON_SUCCESSOR_STORAGE_KEY))
    .resolves.not.toBeNull()
  expect(api.successorAttempts).toHaveLength(1)

  await page.reload()
  await expect(page.getByRole('heading', { name: '남은 업무 0개' })).toBeVisible()
  const cleanupBanner = page.getByRole('alert', { name: '시즌 생성 임시 기록 삭제 필요' })
  await expect(cleanupBanner).toBeVisible()
  await cleanupBanner.getByRole('button', { name: '임시 기록 삭제', exact: true }).click()

  await expect(cleanupBanner).toBeVisible()
  await expect(page.evaluate((storageKey) =>
    window.localStorage.getItem(storageKey), PENDING_SEASON_SUCCESSOR_STORAGE_KEY))
    .resolves.not.toBeNull()
  expect(api.successorAttempts).toHaveLength(1)

  await cleanupBanner.getByRole('button', { name: '임시 기록 삭제', exact: true }).click()

  await expect(cleanupBanner).toBeHidden()
  await expect(page.locator('.toast[role="status"]')).toContainText(
    '임시 기록을 삭제했습니다.',
  )
  await expect(page.evaluate((storageKey) =>
    window.localStorage.getItem(storageKey), PENDING_SEASON_SUCCESSOR_STORAGE_KEY))
    .resolves.toBeNull()
  expect(api.successorAttempts).toHaveLength(1)
})

test('@handoff 다음 시즌 종료 요청의 임시 기록을 삭제하지 못하면 새 POST 전에 삭제를 요구한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '시즌 journal 복구는 데스크톱 Chromium에서 한 번 검증합니다.')
  await failSeasonSuccessorCleanup(
    page,
    'baton-e2e-season-successor-terminal-cleanup-failure',
  )
  const api = await attachSeasonApi(page, null, false)
  api.rejectNextSuccessorAsInvalidInput()
  await openWorkspace(page)

  await seasonSwitcher(page).click()
  await page.getByRole('dialog').getByRole('button', { name: /다음 시즌 시작/ }).click()
  const dialog = page.getByRole('dialog', { name: '다음 시즌 시작' })
  await dialog.getByLabel('다음 시즌 이름').fill('2026 가을 시즌')
  await dialog.getByRole('button', { name: '현재 시즌 종료하고 만들기' }).click()

  await expect(dialog.getByText(
    '다음 시즌 생성에 사용한 임시 기록을 삭제하지 못했습니다. 사이트 데이터 저장을 허용한 뒤 다시 시도해 주세요.',
    { exact: true },
  )).toBeVisible()
  await expect(dialog.getByRole('button', { name: '임시 기록 삭제', exact: true }))
    .toBeVisible()
  expect(api.successorAttempts).toHaveLength(1)
  const firstIdempotencyKey = api.successorAttempts[0]!.idempotencyKey

  await dialog.getByLabel('다음 시즌 이름').fill('')
  await dialog.getByLabel('시작일').fill('2026-12-31')
  await dialog.getByLabel('종료일').fill('2026-10-01')
  await dialog.getByRole('button', { name: '임시 기록 삭제', exact: true }).click()

  await expect(dialog.getByText(
    '임시 기록을 삭제했습니다. 입력을 확인한 뒤 다시 제출해 주세요.',
    { exact: true },
  )).toBeVisible()
  await expect(dialog.getByText('다음 시즌 이름을 입력해 주세요.')).toHaveCount(0)
  await expect(dialog.getByText('다음 시즌의 시작일과 종료일을 확인해 주세요.'))
    .toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '현재 시즌 종료하고 만들기' })).toBeVisible()
  await expect(page.evaluate((storageKey) =>
    window.localStorage.getItem(storageKey), PENDING_SEASON_SUCCESSOR_STORAGE_KEY))
    .resolves.toBeNull()
  expect(api.successorAttempts).toHaveLength(1)

  await dialog.getByLabel('다음 시즌 이름').fill('2026 가을 시즌')
  await dialog.getByLabel('시작일').fill('2026-10-01')
  await dialog.getByLabel('종료일').fill('2026-12-31')
  await dialog.getByRole('button', { name: '현재 시즌 종료하고 만들기' }).click()

  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  expect(api.successorAttempts).toHaveLength(2)
  expect(api.successorAttempts[1]!.idempotencyKey).not.toBe(firstIdempotencyKey)
})

test('@operations 이전 시즌에서 늦게 도착한 종료 오류는 현재 시즌 UI를 닫거나 오염시키지 않는다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', 'scope 격리 회귀는 데스크톱 Chromium에서 한 번 검증합니다.')
  const api = await attachSeasonApi(page)
  await openWorkspace(page)

  await seasonSwitcher(page).click()
  await page.getByRole('dialog', { name: '알고리즘 한 바퀴 시즌' })
    .getByRole('button', { name: /2026 가을 시즌/ })
    .click()
  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(seasonSwitcher(page)).toHaveAccessibleName(/2026 가을 시즌/)
  await seasonSwitcher(page).click()
  await page.getByRole('dialog', { name: '알고리즘 한 바퀴 시즌' })
    .getByRole('button', { name: /2026 여름 시즌/ })
    .click()
  await expect(page).toHaveURL(WORKSPACE_URL)
  await expect(seasonSwitcher(page)).toHaveAccessibleName(/2026 여름 시즌/)

  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 목적')
    .fill('이전 시즌에서 늦게 끝나는 수정입니다.')
  api.holdNextRoleUpdateAsEnded()
  const delayedResponse = page.waitForResponse((response) =>
    response.request().method() === 'PUT'
      && new URL(response.url()).pathname === `${SOURCE_SCOPE}/roles/${ROLE_ID}`)
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()
  await api.waitForHeldRoleUpdate()

  await page.goBack()
  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(page.getByRole('heading', { name: '남은 업무 0개' })).toBeVisible()
  await seasonSwitcher(page).click()
  const currentSeasonDialog = page.getByRole('dialog', { name: '알고리즘 한 바퀴 시즌' })
  await expect(currentSeasonDialog).toBeVisible()

  api.releaseHeldRoleUpdate()
  expect((await delayedResponse).status()).toBe(409)
  await page.evaluate(() => new Promise<void>((resolve) => {
    window.requestAnimationFrame(() => window.requestAnimationFrame(() => resolve()))
  }))
  await expect(currentSeasonDialog).toBeVisible()
  await expect(page.getByText('다른 구성원이 시즌을 종료했어요.')).toHaveCount(0)
  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
})

test('@operations 서버가 시즌 종료를 알리면 열려 있던 편집기를 닫고 읽기 전용으로 전환한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '동시 종료 복구 흐름은 데스크톱에서 한 번 검증합니다.')
  const api = await attachSeasonApi(page)
  await openWorkspace(page)

  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  await expect(page.getByRole('dialog', { name: '역할 수정' })).toBeVisible()
  api.endOnNextRoleUpdate()
  await page.getByRole('dialog').getByRole('button', { name: '변경 저장' }).click()

  await expect(page.getByRole('dialog')).toBeHidden()
  await expect(page.getByText('이 시즌은 읽기 전용입니다.')).toBeVisible()
  await expect(page.getByText('다른 구성원이 시즌을 종료했어요.')).toBeVisible()
})


test('@handoff @responsive 이전 시즌 기록을 필요할 때 조회하고 선택한 자료만 현재 역할에 연결한다', async ({ page }, testInfo) => {
  await attachSeasonApi(page, ENDED_AT)
  const target = projection(nextSeason(), [sourceSeason(ENDED_AT), nextSeason()])
  const previous = projection(sourceSeason(ENDED_AT), target.seasons)
  const targetScope = `/api/v1/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`
  let previousRequests = 0
  let denyPrevious = true
  const copies: CreateRoleResourceRequest[] = []
  await page.route(`**${targetScope}/workspace`, (route) => fulfillJson(route, target))
  await page.route(`**${SOURCE_SCOPE}/workspace`, async (route) => {
    previousRequests += 1
    expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
    await fulfillJson(route, denyPrevious
      ? { code: 'WORKSPACE_ACCESS_DENIED', message: '이전 시즌 접근을 확인해 주세요.' }
      : previous, denyPrevious ? 403 : 200)
  })
  await page.route(`**${targetScope}/role-resources`, async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
    expect(route.request().headers()['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
    const input = route.request().postDataJSON() as CreateRoleResourceRequest
    copies.push(input)
    const resource: RoleResource = {
      ...input, description: input.description ?? null, id: fixtureUuid(72), createdAt: '2026-10-01T00:00:00Z', archivedAt: null,
    }
    target.resources.push(resource)
    await fulfillJson(route, resource, 201)
  })
  await page.goto(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}#accessKey=${ACCESS_KEY}`)
  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await page.getByRole('button', { name: /문제 큐레이터 역할 상세 열기/ }).click()
  const records = page.locator('.previous-role-records')
  await expect(records.getByText('이전 시즌 기록 보기', { exact: true })).toBeVisible()
  expect(previousRequests).toBe(0)
  await records.getByText('이전 시즌 기록 보기', { exact: true }).click()
  await expect(records).toContainText('이전 시즌 기록을 불러오지 못했습니다.')
  await expect(records.getByRole('link', { name: '문제 목록', exact: true })).toHaveCount(0)
  denyPrevious = false
  await records.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(records).toContainText('2026 여름 시즌 · 문제 큐레이터')
  await expect(records).toContainText('운영 문제를 일찍 발견하기 위해서입니다.')
  await expect(records).toContainText('문제 선정 기준 공유')
  await expect(page).toHaveURL(new RegExp(`/seasons/${NEXT_SEASON_ID}`))
  await records.getByRole('heading', { name: '참고 자료', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('previous-role-records.png') })
  await records.getByRole('button', { name: '문제 목록 자료를 현재 시즌에 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '참고 자료 추가' })
  await expect(dialog.getByRole('combobox', { name: '역할', exact: true })).toHaveValue(COPIED_ROLE_ID)
  await expect(dialog.getByLabel('자료 이름')).toHaveValue('문제 목록')
  await expect(dialog.getByLabel('링크', { exact: true })).toHaveValue('https://example.com/problems')
  await dialog.getByRole('button', { name: '자료 추가' }).click()
  await expect(dialog).not.toBeVisible()
  expect(copies).toEqual([{ roleId: COPIED_ROLE_ID, title: '문제 목록', url: 'https://example.com/problems', description: null }])
  expect(previous.resources[0]?.roleId).toBe(ROLE_ID)
  await page.reload()
  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await page.getByRole('button', { name: /문제 큐레이터 역할 상세 열기/ }).click()
  await expect(page.getByRole('link', { name: '문제 목록 새 창에서 열기' })).toBeVisible()
})
