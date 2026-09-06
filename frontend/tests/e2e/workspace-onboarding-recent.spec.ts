import { expect, test } from '@playwright/test'
import type {
  CreateWorkspaceRequest,
} from '../../src/features/workspace/types'
import {
  TEAM_ID,
  SEASON_ID,
  ACCESS_KEY,
  WORKSPACE_PATH,
  SCOPE_PATH,
  PENDING_CREATION_STORAGE_PREFIX,
  LEGACY_PENDING_CREATION_STORAGE_KEY,
  makeProjection,
  installApi,
  openSharedWorkspace,
  navigation,
  blockBrowserStorage,
  failNextJournalCleanup,
  pendingWorkspaceCreationEntry,
  seedPendingWorkspaceCreations,
  fillOnboardingForm,
  pendingCreationEntries,
  recordedCall,
  expectScopedCall,
} from './support/workspaceApiHarness'

test.describe('조직 달력 날짜 경계', () => {
  test.use({ timezoneId: 'UTC' })

  test('시즌 첫날은 경과한 주 없이 시작한다', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium', '데스크톱 사이드바에서만 표시되는 진행률입니다.')
    await page.clock.setFixedTime(new Date('2026-07-02T14:00:00Z'))
    await installApi(page)
    await openSharedWorkspace(page)

    await expect(page.locator('.season-mini strong')).toHaveText('0 / 11주')
  })

  test('브라우저가 UTC여도 시즌 시간대로 오늘 날짜를 표시한다', async ({ page }) => {
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

  test('하루짜리 시즌은 해당 날짜에 완료 진행률을 표시한다', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium', '데스크톱 사이드바에서만 표시되는 진행률입니다.')
    const projection = makeProjection()
    projection.season = {
      ...projection.season,
      startDate: '2026-07-02',
      endDate: '2026-07-02',
    }
    projection.seasons = projection.seasons.map((season) =>
      season.id === projection.season.id
        ? structuredClone(projection.season)
        : season,
    )
    await page.clock.setFixedTime(new Date('2026-07-01T15:00:00Z'))
    await installApi(page, projection)
    await openSharedWorkspace(page)

    await expect(page.locator('.season-mini strong')).toHaveText('1 / 1주')
    await expect(page.locator('.season-mini .mini-progress > span')).toHaveAttribute(
      'style',
      'width: 100%;',
    )
  })

  test('일반 시즌은 종료일에 전체 진행률을 표시한다', async ({ page }, testInfo) => {
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

  test('한국 날짜가 종료일 다음 날이면 지난 시즌으로 표시한다', async ({ page }, testInfo) => {
    await page.clock.setFixedTime(new Date('2026-09-17T15:30:00Z'))
    await installApi(page)
    await openSharedWorkspace(page)

    await expect(page.locator('.main-surface .page-header .eyebrow')).toHaveText(
      '9월 18일 금요일 · 2026 여름 시즌',
    )
    await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()

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
  await page.getByLabel('작업 공간 생성 코드 (선택)').fill('pilot-only-code')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()
})

test("@smoke 작업 공간 생성 화면을 떠난 뒤 늦은 성공 응답이 현재 화면을 바꾸지 않는다", async ({ page }) => {
  const request: CreateWorkspaceRequest = {
    teamName: "화면 이탈 스터디",
    seasonName: "2029 가을 시즌",
    startDate: "2029-09-01",
    endDate: "2029-11-30",
    memberNames: ["박민서"],
  }
  const api = await installApi(page)
  api.holdNextWorkspaceCreation()
  await page.goto("/")
  await fillOnboardingForm(page, request)
  await page.getByRole("button", { name: "작업 공간 만들기" }).click()
  await expect.poll(() => api.calls.filter((call) =>
    call.method === "POST" && call.path === "/api/v1/workspaces").length).toBe(1)

  await page.getByRole("link", { name: "계정 로그인" }).click()
  await expect(page).toHaveURL(/\/login/)

  const response = page.waitForResponse("**/api/v1/workspaces")
  api.releaseWorkspaceCreation()
  await (await response).finished()
  await expect.poll(() => page.evaluate(
    (teamId) => localStorage.getItem("baton-access-key:" + teamId),
    TEAM_ID,
  )).toBe(ACCESS_KEY)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
  await expect(page).toHaveURL(/\/login/)
})

test('온보딩 자격 증명 응답이 손상되면 생성 journal과 현재 위치를 보존한다', async ({ page }) => {
  const api = await installApi(page)
  api.returnMalformedNextWorkspaceCreationResponse()
  await page.goto('/')

  await fillOnboardingForm(page, {
    teamName: '응답 경계 스터디',
    seasonName: '2026 가을 시즌',
    startDate: '2026-09-01',
    endDate: '2026-11-30',
    memberNames: ['박민서'],
  })
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page.getByRole('alert')).toContainText(
    '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  )
  await expect(page).toHaveURL(/\/$/)
  const createCall = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(await pendingCreationEntries(page)).toEqual([
    expect.objectContaining({
      idempotencyKey: createCall.headers['idempotency-key'],
    }),
  ])
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    `baton-access-key:${TEAM_ID}`,
  )).toBeNull()
})

test('같은 구성원 이름은 구분해서 입력하도록 안내하고 API를 호출하지 않는다', async ({ page }) => {
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

test('온보딩 입력 한도를 서버 호출 전에 안내한다', async ({ page }) => {
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

test('생성 pending을 내구 저장할 수 없으면 reload 후에도 API를 호출하지 않는다', async ({ page }) => {
  await blockBrowserStorage(page)
  const api = await installApi(page)

  const submitWorkspace = async () => {
    await page.getByLabel('팀 이름').fill('저장 필수 스터디')
    await page.getByLabel('시즌 이름').fill('2027 여름 시즌')
    await page.getByLabel('시작일').fill('2027-06-01')
    await page.getByLabel('종료일').fill('2027-08-31')
    await page.getByLabel('구성원 이름').fill('박민서\n김준호')
    await page.getByRole('button', { name: '작업 공간 만들기' }).click()
    await expect(page.getByRole('alert')).toContainText('일반 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도하세요.')
  }

  await page.goto('/')
  await submitWorkspace()
  await page.reload()
  await submitWorkspace()

  expect(api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(0)
})

test('구성원 순서가 바뀐 온보딩 재시도는 reload 후에도 같은 멱등 키를 사용한다', async ({ page }) => {
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect((attempts[1]?.body as CreateWorkspaceRequest).memberNames).toEqual(['최유진', '박민서', '김준호'])
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  expect(attempts[1]?.headers['x-baton-creation-key']).toBeUndefined()
})

test('서버 입력 오류 뒤 온보딩 pending을 지우고 다음 시도에 새 멱등 키를 사용한다', async ({ page }) => {
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.body).toEqual(firstAttempt.body)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
  expect(await pendingCreationEntries(page)).toHaveLength(0)
})

test('불러온 온보딩 복구 요청이 입력 오류로 거절되면 새 요청을 바로 시작할 수 있다', async ({ page }) => {
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
    name: '완료 여부를 확인할 작업 공간',
  })
  await pendingRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
  await pendingRegion.getByRole('button', {
    name: `${request.teamName} ${request.seasonName} 저장된 입력 불러오기`,
  }).click()
  await page.getByRole('button', { name: '작업 공간 다시 확인' }).click()

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

test('온보딩 terminal 기록 cleanup이 실패하면 재전송 전에 정리를 요구한다', async ({ page }) => {
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
    '작업 공간을 만들 때 저장한 임시 기록을 지우지 못했습니다. 브라우저 저장을 허용한 뒤 다시 시도해 주세요.',
    { exact: true },
  )).toBeVisible()
  await expect(page.getByRole('button', { name: '임시 기록 정리 필요' })).toBeDisabled()
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(await pendingCreationEntries(page)).toHaveLength(1)

  await page.getByRole('button', { name: '임시 기록 삭제 재시도' }).click()

  await expect(page.getByRole('alert')).toContainText('브라우저의 임시 기록을 정리했습니다.')
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

test('온보딩 성공 기록 cleanup이 실패하면 정리를 확인한 뒤 한 번만 이동한다', async ({ page }) => {
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CREATION_STORAGE_PREFIX },
    'baton-e2e-workspace-success-cleanup-failure',
  )
  const api = await installApi(page)
  await page.goto('/')
  await fillOnboardingForm(page, {
    teamName: '성공 기록 정리 스터디',
    seasonName: '2028 가을 시즌',
    startDate: '2028-09-01',
    endDate: '2028-11-30',
    memberNames: ['박민서'],
  })

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText(
    '작업 공간을 만들 때 저장한 임시 기록을 지우지 못했습니다. 브라우저 저장을 허용한 뒤 다시 시도해 주세요.',
    { exact: true },
  )).toBeVisible()
  await expect(page.getByRole('button', { name: '임시 기록 정리 필요' })).toBeDisabled()
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(1)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)

  await page.getByRole('button', { name: '임시 기록 삭제 재시도' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)
})

test('다른 탭이 생성 결과를 확인하는 동안 온보딩 cleanup 재시도를 막는다', async ({ page, context }) => {
  const request: CreateWorkspaceRequest = {
    teamName: '정리 잠금 스터디',
    seasonName: '2029 겨울 시즌',
    startDate: '2029-12-01',
    endDate: '2030-02-28',
    memberNames: ['박민서'],
  }
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CREATION_STORAGE_PREFIX },
    'baton-e2e-workspace-cleanup-lock-contention',
  )
  const api = await installApi(page)
  await page.goto('/')
  await fillOnboardingForm(page, request)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  const cleanupButton = page.getByRole('button', { name: '임시 기록 삭제 재시도' })
  await expect(cleanupButton).toBeVisible()
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(1)

  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  await peerPage.goto('/')
  const peerRegion = peerPage.getByRole('region', {
    name: '완료 여부를 확인할 작업 공간',
  })
  await peerRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
  await peerRegion.getByRole('button', {
    name: `${request.teamName} ${request.seasonName} 저장된 입력 불러오기`,
  }).click()

  api.holdNextWorkspaceCreation()
  await peerPage.getByRole('button', { name: '작업 공간 다시 확인' }).click()
  await expect.poll(() => api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces').length).toBe(2)

  await cleanupButton.click()
  await expect(page.getByRole('alert')).toContainText(
    '다른 탭에서 작업 공간 생성 결과를 확인 중입니다.',
  )
  expect(await pendingCreationEntries(page)).toHaveLength(1)

  api.releaseWorkspaceCreation()
  await expect(peerPage).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)

  await cleanupButton.click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(2)
})

test('만료된 온보딩 멱등 기록은 기존 결과 확인 전 새 요청을 막는다', async ({ page }) => {
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

  await expect(page.getByRole('alert')).toContainText('이미 만들어졌을 수 있으니 운영자에게 최신 링크를 요청하세요.')
  const firstAttempt = await recordedCall(api, 'POST', '/api/v1/workspaces')
  expect(firstAttempt.headers['idempotency-key']).toBe(pendingEntry.idempotencyKey)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
  await expect(page.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)

  await page.getByLabel('팀 이름').fill(`${request.teamName} 수정`)
  await expect(page.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  await expect(page.getByText(
    '공유 링크가 바뀌어 작업 공간을 다시 열 수 없습니다.',
  )).toBeVisible()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)

  await page.getByRole('button', {
    name: '기존 작업 공간 확인 후 새로 만들기',
  }).click()
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()

  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('만료된 온보딩 결과 확인은 다른 복구 snapshot을 불러와도 유지된다', async ({ page }) => {
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

  await expect(page.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  const pendingRegion = page.getByRole('region', {
    name: '완료 여부를 확인할 작업 공간',
  })
  await pendingRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
  await pendingRegion.getByRole('button', {
    name: `${otherRequest.teamName} ${otherRequest.seasonName} 저장된 입력 불러오기`,
  }).click()

  await expect(page.getByLabel('팀 이름')).toHaveValue(otherRequest.teamName)
  await expect(page.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  await expect(page.getByText(
    '공유 링크가 바뀌어 작업 공간을 다시 열 수 없습니다.',
  )).toBeVisible()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(1)
})

test('서로 다른 탭의 생성 pending을 순서대로 보존하고 응답 유실 뒤 같은 키로 복구한다', async ({ page, context }) => {
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const pendingAfterAClear = await pendingCreationEntries(pageB)
  expect(pendingAfterAClear).toHaveLength(1)
  expect(pendingAfterAClear[0]?.idempotencyKey).toBe(firstAttemptB.headers['idempotency-key'])

  await pageB.reload()
  await fillWorkspaceB()
  await pageB.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(pageB.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attemptsB = apiB.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attemptsB).toHaveLength(2)
  expect(attemptsB[1]?.headers['idempotency-key']).toBe(firstAttemptB.headers['idempotency-key'])
  await expect.poll(async () => (await pendingCreationEntries(pageB)).length).toBe(0)
})

test('손상된 온보딩 pending 저장소를 무시하고 정상 멱등 키로 재시도한다', async ({ page }) => {
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[0]?.headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  expect(attempts[0]?.headers['idempotency-key']).not.toBe(malformedIdempotencyKey)
  expect(attempts[1]?.headers['idempotency-key']).toBe(attempts[0]?.headers['idempotency-key'])
})

test('생성 계약을 벗어난 v3 온보딩 pending을 정리하고 정상 생성한다', async ({ page }) => {
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(1)
  expect(invalidEntries.map((entry) => entry.idempotencyKey))
    .not.toContain(attempts[0]?.headers['idempotency-key'])
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
})

test('탭 간 생성 잠금을 지원하지 않으면 온보딩 요청을 전송하지 않는다', async ({ page }) => {
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

  await expect(page.getByRole('alert')).toContainText('작업 공간을 만들거나 생성 결과를 확인할 수 없습니다.')
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
    name: '완료 여부를 확인할 작업 공간',
  })
  await pendingRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
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
  const loadedNotice = page.getByText('입력을 불러왔습니다. 생성 코드가 필요하면 입력한 뒤 ‘작업 공간 다시 확인’을 누르세요.')
  await expect(loadedNotice).toBeVisible()
  expect(api.calls.filter((call) => call.path === '/api/v1/workspaces')).toHaveLength(0)

  await page.getByLabel('팀 이름').fill(`${pendingRequest.teamName} 수정`)
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeVisible()
  await expect(loadedNotice).toBeHidden()
  await page.getByLabel('팀 이름').fill(pendingRequest.teamName)

  await page.getByRole('button', { name: '작업 공간 다시 확인' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(1)
  expect(attempts[0]?.headers['idempotency-key']).toBe(pendingEntry.idempotencyKey)
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
})

test('불러온 온보딩 snapshot이 바뀌면 명시적 확인 전 새 요청으로 전환하지 않는다', async ({ page }) => {
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
    name: '완료 여부를 확인할 작업 공간',
  })
  await pendingRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
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
  await page.getByRole('button', { name: '작업 공간 다시 확인' }).click()

  await expect(page.getByRole('alert')).toContainText('다른 탭에서 임시 기록이 변경됐습니다.')
  await expect(page.getByText('다른 탭에서 이 작업 공간의 임시 기록을 변경했습니다.')).toBeVisible()
  await expect(page.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === '/api/v1/workspaces',
  )).toHaveLength(0)

  await page.getByRole('button', {
    name: '기존 작업 공간 확인 후 새로 만들기',
  }).click()
  await expect(page.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
})

test('다른 탭이 생성 결과를 확인하는 동안 온보딩 pending 폐기를 막는다', async ({ page, context }) => {
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
    name: '완료 여부를 확인할 작업 공간',
  })
  await pageRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
  await pageRegion.getByRole('button', {
    name: `${pendingLabel} 저장된 입력 불러오기`,
  }).click()
  await page.getByRole('button', { name: '작업 공간 다시 확인' }).click()
  await expect.poll(() =>
    api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces').length,
  ).toBe(1)

  const peerRegion = peerPage.getByRole('region', {
    name: '완료 여부를 확인할 작업 공간',
  })
  await peerRegion.getByText('완료 여부를 확인할 작업 공간 1개').click()
  await peerRegion.getByRole('button', {
    name: `${pendingLabel} 저장된 입력 불러오기`,
  }).click()
  await expect(peerPage.getByRole('button', { name: '작업 공간 다시 확인' })).toBeVisible()
  await peerRegion.getByRole('button', {
    name: `${pendingLabel} 확인 대기 목록에서 삭제`,
  }).click()
  await peerRegion.getByRole('group', {
    name: '확인 대기 목록에서 삭제할까요?',
  }).getByRole('button', { name: '목록에서 삭제' }).click()

  await expect(peerRegion.getByRole('alert')).toContainText('다른 탭에서 작업 공간 생성 결과를 확인 중입니다.')
  expect(await pendingCreationEntries(peerPage)).toEqual([pendingEntry])

  api.releaseWorkspaceCreation()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect.poll(async () => (await pendingCreationEntries(peerPage)).length).toBe(0)
  await expect(peerPage.getByText('다른 탭에서 이 요청의 결과를 확인했거나 확인 대기 목록에서 삭제했습니다.')).toBeVisible()
  await expect(peerPage.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  await expect(peerPage.getByRole('link', { name: new RegExp(pendingRequest.teamName) })).toBeVisible()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)

  await peerPage.getByRole('button', {
    name: '기존 작업 공간 확인 후 새로 만들기',
  }).click()
  await expect(peerPage.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)
})

test('같은 신규 온보딩 요청의 탭 경합은 결과 확인 전 재제출을 막는다', async ({ page, context }) => {
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
  await expect(peerPage.getByRole('button', { name: '기존 작업 공간 확인 필요' })).toBeDisabled()
  await expect(peerPage.getByRole('link', { name: new RegExp(request.teamName) })).toBeVisible()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)

  await peerPage.getByRole('button', {
    name: '기존 작업 공간 확인 후 새로 만들기',
  }).click()
  await expect(peerPage.getByRole('button', { name: '작업 공간 만들기' })).toBeEnabled()
  expect(api.calls.filter((call) =>
    call.method === 'POST' && call.path === '/api/v1/workspaces')).toHaveLength(1)
})

test('온보딩 pending 한 건을 확인 후 폐기하고 새 작업 공간을 만든다', async ({ page }) => {
  const pendingRequests = Array.from({ length: 5 }, (_, index): CreateWorkspaceRequest => ({
    teamName: `작업 공간이 만들어졌는지 확인해 주세요 스터디 ${index + 1}`,
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
    name: '완료 여부를 확인할 작업 공간',
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

  await expect(page.getByRole('alert')).toContainText('완료 여부를 확인하지 못한 작업 공간이 5개 있습니다.')
  expect(api.calls.filter((call) => call.path === '/api/v1/workspaces')).toHaveLength(0)
  await expect.poll(async () =>
    (await pendingCreationEntries(page))
      .map((entry) => entry.idempotencyKey)
      .sort(),
  ).toEqual(seededKeys)

  const firstRequest = pendingRequests[0]!
  const firstItem = pendingRegion.getByRole('listitem').filter({ hasText: firstRequest.teamName })
  const discardButtonName = `${firstRequest.teamName} ${firstRequest.seasonName} 확인 대기 목록에서 삭제`
  await firstItem.getByRole('button', { name: discardButtonName }).click()
  const confirmation = firstItem.getByRole('group', { name: '확인 대기 목록에서 삭제할까요?' })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '계속 보관' }).click()
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(5)

  await firstItem.getByRole('button', { name: discardButtonName }).click()
  await confirmation.getByRole('button', { name: '목록에서 삭제' }).click()

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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(1)
  expect(seededKeys).not.toContain(attempts[0]?.headers['idempotency-key'])
  await expect.poll(async () =>
    (await pendingCreationEntries(page))
      .map((entry) => entry.idempotencyKey)
      .sort(),
  ).toEqual(remainingKeys)
})

test('@webkit 브라우저 저장소가 막혀도 일회성 접근 키를 잃지 않는다', async ({ page }) => {
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
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()
  expectScopedCall(await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`))
  expect(await pendingCreationEntries(page)).toHaveLength(0)

  await page.reload()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: '이번 회차 미완료 업무 0개' })).toBeVisible()
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

  await page.getByRole('button', { name: '저장된 공유 링크로 열기' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByRole('heading', { level: 1, name: /이번 회차 미완료 업무 \d+개/ })).toBeVisible()
  const successfulGet = [...api.calls].reverse().find((candidate) => candidate.method === 'GET' && candidate.path === `${SCOPE_PATH}/workspace`)
  expect(successfulGet?.headers['x-baton-access-key']).toBe(ACCESS_KEY)
})

test('@smoke 이 기기에서 삭제는 접근 키와 최근 목록과 ROUND 기록을 함께 지운다', async ({ page, context }) => {
  test.slow()
  const api = await installApi(page)
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
  await expect(page.getByRole('heading', { level: 1, name: /이번 회차 미완료 업무 \d+개/ })).toBeVisible()

  await page.goto('/')
  await page.evaluate(({ roomId, teamId, seasonId }) => {
    const resourceId = '00000000-0000-4000-8000-000000000056'
    sessionStorage.setItem(`baton-round-entry:v1:${roomId}`, JSON.stringify({
      version: 1,
      resourceId,
      roomId,
      seasonId,
      teamId,
    }))
    sessionStorage.setItem(
      `baton-round-resource:v1:${teamId}:${seasonId}:${resourceId}`,
      roomId,
    )
  }, { roomId: 'bcdf-ghjk-mnpq', teamId: TEAM_ID, seasonId: SEASON_ID })
  const peer = await context.newPage()
  await api.attachPage(peer)
  await peer.goto(WORKSPACE_PATH)
  await expect(peer.getByRole('heading', { level: 1, name: /이번 회차 미완료 업무 \d+개/ })).toBeVisible()
  const workspaceGetCount = () => api.calls.filter((call) =>
    call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length

  const requestsBeforeRefresh = workspaceGetCount()
  api.holdWorkspaceGets()
  await peer.bringToFront()
  await expect.poll(workspaceGetCount).toBeGreaterThan(requestsBeforeRefresh)

  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toContain('공유 링크와 최근 방문 목록')
    await dialog.accept()
  })
  await page.getByRole('button', {
    name: '알고리즘 한 바퀴 2026 여름 시즌 이 기기에서 공유 링크 삭제',
  }).click()
  await expect(page.getByRole('region', { name: '최근 작업 공간' })).toHaveCount(0)
  await expect(peer.getByText('로그인 또는 공유 링크 필요')).toBeVisible()
  api.releaseWorkspaceGets()
  const requestsAfterRemoval = workspaceGetCount()
  await peer.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
  })
  await peer.waitForTimeout(300)
  expect(workspaceGetCount()).toBe(requestsAfterRemoval)
  await peer.goto('/')
  await expect(peer.getByRole('region', { name: '최근 작업 공간' })).toHaveCount(0)
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('baton-recent-workspaces:v1') ?? '[]'))).toHaveLength(0)
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`))
    .toBeNull()
  expect(await page.evaluate(() => Object.keys(sessionStorage).filter((key) => (
    key.startsWith('baton-round-entry:') || key.startsWith('baton-round-resource:')
  )))).toEqual([])
  await peer.close()
})

test('최근 목록 저장 실패 시 접근 키 제거 사실과 남은 목록을 정확히 안내한다', async ({ page }) => {
  await page.addInitScript(({ storageKey, accessKeyStorageKey, accessKey, recentWorkspace }) => {
    const originalSetItem = Storage.prototype.setItem
    originalSetItem.call(localStorage, storageKey, JSON.stringify([recentWorkspace]))
    originalSetItem.call(localStorage, accessKeyStorageKey, accessKey)
    Storage.prototype.setItem = function setItem(key, value) {
      if (key === storageKey) throw new DOMException('Storage disabled', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  }, {
    storageKey: 'baton-recent-workspaces:v1',
    accessKeyStorageKey: `baton-access-key:${TEAM_ID}`,
    accessKey: ACCESS_KEY,
    recentWorkspace: {
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      teamName: '알고리즘 한 바퀴',
      seasonName: '2026 여름 시즌',
      lastOpenedAt: '2026-07-24T00:00:00.000Z',
    },
  })
  await page.goto('/')

  const forgetButton = page.getByRole('button', {
    name: '알고리즘 한 바퀴 2026 여름 시즌 이 기기에서 공유 링크 삭제',
  })
  await expect(forgetButton).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await forgetButton.click()

  await expect(forgetButton).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('공유 링크는 지웠지만 최근 방문 목록을 지우지 못했습니다.')
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`))
    .toBeNull()
  expect(await page.evaluate(() =>
    JSON.parse(localStorage.getItem('baton-recent-workspaces:v1') ?? '[]'))).toHaveLength(1)
})

test('접근 키 제거 실패 시 권한을 지웠다고 표시하지 않는다', async ({ page }) => {
  await page.addInitScript(({ storageKey, accessKeyStorageKey, accessKey, recentWorkspace }) => {
    const originalSetItem = Storage.prototype.setItem
    const originalRemoveItem = Storage.prototype.removeItem
    originalSetItem.call(localStorage, storageKey, JSON.stringify([recentWorkspace]))
    originalSetItem.call(localStorage, accessKeyStorageKey, accessKey)
    Storage.prototype.removeItem = function removeItem(key) {
      if (this === localStorage && key === accessKeyStorageKey) {
        throw new DOMException('Storage disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
  }, {
    storageKey: 'baton-recent-workspaces:v1',
    accessKeyStorageKey: `baton-access-key:${TEAM_ID}`,
    accessKey: ACCESS_KEY,
    recentWorkspace: {
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      teamName: '알고리즘 한 바퀴',
      seasonName: '2026 여름 시즌',
      lastOpenedAt: '2026-07-24T00:00:00.000Z',
    },
  })
  await page.goto('/')

  const forgetButton = page.getByRole('button', {
    name: '알고리즘 한 바퀴 2026 여름 시즌 이 기기에서 공유 링크 삭제',
  })
  page.once('dialog', (dialog) => dialog.accept())
  await forgetButton.click()

  await expect(forgetButton).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('이 기기에 저장된 작업 공간 접근 권한을 제거하지 못했습니다.')
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`))
    .toBe(ACCESS_KEY)
  expect(await page.evaluate(() =>
    JSON.parse(localStorage.getItem('baton-recent-workspaces:v1') ?? '[]'))).toHaveLength(1)
})
