import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import type {
  CreateNextSeasonRequest,
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
const ROUND_ID = fixtureUuid(41)
const EXECUTION_ID = fixtureUuid(42)
const DECISION_ID = fixtureUuid(51)
const HANDOFF_ID = fixtureUuid(61)
const RESOURCE_ID = fixtureUuid(71)
const ACCESS_KEY = 'season-lifecycle-e2e-access-key'
const SOURCE_SCOPE = `/api/v1/teams/${TEAM_ID}/seasons/${SOURCE_SEASON_ID}`
const WORKSPACE_URL = `/teams/${TEAM_ID}/seasons/${SOURCE_SEASON_ID}`
const ENDED_AT = '2026-07-30T03:00:00Z'

const sourceSeason = (endedAt: string | null = null): SeasonSummary => ({
  id: SOURCE_SEASON_ID,
  name: '2026 여름 시즌',
  startDate: '2026-07-01',
  endDate: '2026-09-30',
  endedAt,
  previousSeasonId: null,
})

const nextSeason = (): SeasonSummary => ({
  id: NEXT_SEASON_ID,
  name: '2026 가을 시즌',
  startDate: '2026-10-01',
  endDate: '2026-12-31',
  endedAt: null,
  previousSeasonId: SOURCE_SEASON_ID,
})

const role = (id = ROLE_ID): Role => ({
  id,
  name: '문제 큐레이터',
  purpose: '이번 주 학습 목표에 맞는 문제를 고릅니다.',
  currentMemberId: id === ROLE_ID ? MEMBER_ID : null,
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
): Routine => ({
  id,
  title,
  phase: 'BEFORE',
  dueLabel: '수요일 18:00',
  ownerRoleId,
  detail: '다음 모임의 문제를 고릅니다.',
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
      ]

  return {
    team: { id: TEAM_ID, name: '알고리즘 한 바퀴' },
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
      }],
    }],
    decisions: target ? [] : [{
      id: DECISION_ID,
      title: '격주 회고를 진행한다',
      reason: '운영 문제를 일찍 발견하기 위해서입니다.',
      alternative: '월말에 한 번 모으기',
      authorMemberId: MEMBER_ID,
      authorName: '박민서',
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
      archivedAt: null,
    }],
    resources: target ? [] : [{
      id: RESOURCE_ID,
      roleId: ROLE_ID,
      title: '문제 목록',
      url: 'https://example.com/problems',
      description: null,
    }],
  }
}

type RecordedSuccessor = {
  body: CreateNextSeasonRequest
  idempotencyKey: string
}

type SeasonApiHarness = {
  successor?: RecordedSuccessor
  endOnNextRoleUpdate: () => void
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'X-Request-ID': fixtureUuid(99) },
    body: JSON.stringify(body),
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
  const harness: SeasonApiHarness = {
    endOnNextRoleUpdate: () => {
      rejectRoleUpdateAsEnded = true
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
      harness.successor = {
        body,
        idempotencyKey: request.headers()['idempotency-key'] ?? '',
      }
      source = { ...source, endedAt: source.endedAt ?? ENDED_AT }
      target = {
        id: NEXT_SEASON_ID,
        name: body.name,
        startDate: body.startDate,
        endDate: body.endDate,
        endedAt: null,
        previousSeasonId: SOURCE_SEASON_ID,
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

async function openWorkspace(page: Page) {
  await page.addInitScript(({ teamId, accessKey }) => {
    window.localStorage.setItem(`baton-access-key:${teamId}`, accessKey)
  }, { teamId: TEAM_ID, accessKey: ACCESS_KEY })
  await page.goto(WORKSPACE_URL)
  await expect(page.getByRole('heading', { name: /개의 바통이 남았어요/ })).toBeVisible()
}

function seasonSwitcher(page: Page) {
  return page.getByRole('button', { name: /현재 시즌 .*시즌 전환|알고리즘 한 바퀴 .*시즌 전환/ }).first()
}

test('@smoke @responsive 시즌 전환은 URL과 화면 상태를 함께 바꾸고 포커스를 복원한다', async ({ page }) => {
  await attachSeasonApi(page)
  await openWorkspace(page)

  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await expect(page.getByRole('heading', { name: '사람이 바뀌어도 역할은 남아요' })).toBeVisible()

  const trigger = seasonSwitcher(page)
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '알고리즘 한 바퀴 시즌' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()

  await trigger.click()
  await dialog.getByRole('button', { name: /2026 가을 시즌/ }).click()
  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(page.getByRole('heading', { name: '0개의 바통이 남았어요' })).toBeVisible()
  await expect(seasonSwitcher(page)).toHaveAccessibleName(/2026 가을 시즌/)
})

test('@operations 종료된 시즌은 기록 변경 동작을 막고 조회·공유·시즌 전환은 유지한다', async ({ page }) => {
  await attachSeasonApi(page, ENDED_AT)
  await openWorkspace(page)

  await expect(page.getByText('이 시즌은 읽기 전용입니다.')).toBeVisible()
  await expect(seasonSwitcher(page)).toBeEnabled()
  await expect(page.getByRole('button', { name: '공유' }).first()).toBeEnabled()
  await expect(page.getByRole('button', { name: '키 관리' }).first()).toBeEnabled()
  await expect(page.getByRole('button', { name: '결정 남기기' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 만들기' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeDisabled()

  await page.getByRole('button', { name: '역할', exact: true }).first().click()
  await expect(page.getByRole('button', { name: '구성원 관리' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '역할 추가' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 큐레이터 역할 수정' })).toBeDisabled()

  await page.getByRole('button', { name: '운영', exact: true }).first().click()
  await expect(page.getByRole('button', { name: '루틴 추가' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '1회차 회차 보관' })).toBeDisabled()

  await page.getByRole('button', { name: '기록', exact: true }).first().click()
  await expect(page.getByRole('button', { name: '격주 회고를 진행한다 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '격주 회고를 진행한다 보관' })).toBeDisabled()

  await page.getByRole('button', { name: /^바통/ }).first().click()
  await expect(page.getByRole('button', { name: '항목 추가' })).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: /문제 선정 기준 공유/ })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 선정 기준 공유 수정' })).toBeDisabled()
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
  await expect(roleCheckbox).toBeChecked()
  await expect(firstRoutine).toBeChecked()
  await expect(secondRoutine).toBeChecked()

  await roleCheckbox.uncheck()
  await expect(firstRoutine).not.toBeChecked()
  await expect(secondRoutine).not.toBeChecked()
  await firstRoutine.check()
  await expect(roleCheckbox).toBeChecked()
  await expect(secondRoutine).not.toBeChecked()

  await dialog.getByLabel('다음 시즌 이름').fill('2026 가을 시즌')
  await dialog.getByRole('button', { name: '현재 시즌을 닫고 시작' }).click()

  await expect(page).toHaveURL(`/teams/${TEAM_ID}/seasons/${NEXT_SEASON_ID}`)
  await expect(page.getByRole('heading', { name: '0개의 바통이 남았어요' })).toBeVisible()
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
