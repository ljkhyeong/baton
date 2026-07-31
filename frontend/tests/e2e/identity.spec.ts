import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import type { WorkspaceProjection } from '../../src/features/workspace/types'

const fixtureUuid = (sequence: number) =>
  `10000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`

const TEAM_ID = fixtureUuid(1)
const SEASON_ID = fixtureUuid(2)
const OWNER_ACCOUNT_ID = fixtureUuid(3)
const SECOND_ACCOUNT_ID = fixtureUuid(4)
const OWNER_MEMBER_ID = fixtureUuid(11)
const INVITED_MEMBER_ID = fixtureUuid(12)
const INVITATION_ID = fixtureUuid(21)
const ACCESS_KEY = 'identity-e2e-workspace-access-key'
const CSRF_HEADER_NAME = 'X-BATON-CSRF-E2E'
const CSRF_HEADER_VALUE = 'dynamic-non-default-csrf-token'
const ACCEPT_TOKEN = 'accept-token-memory-only'
const ISSUED_TOKEN = 'issued-token-memory-only'
const WORKSPACE_PATH = `/teams/${TEAM_ID}/seasons/${SEASON_ID}`
const MEMBER_INVITATIONS_PATH = `/api/v1/teams/${TEAM_ID}/member-invitations`
const PENDING_INVITATION_KEY = `baton-pending-member-invitation:v1:${TEAM_ID}`

type IdentityMode = 'anonymous' | 'member' | 'owner'

type RecordedCall = {
  method: string
  path: string
  headers: Record<string, string>
  body?: unknown
}

type OpenInvitation = {
  invitationId: string
  teamId: string
  memberId: string
  issuedAt: string
  expiresAt: string
}

function workspaceProjection(): WorkspaceProjection {
  return {
    team: { id: TEAM_ID, name: '아이덴티티 팀' },
    season: {
      id: SEASON_ID,
      name: '2026 여름 시즌',
      startDate: '2026-07-01',
      endDate: '2026-09-30',
      endedAt: null,
      previousSeasonId: null,
      timeZone: 'Asia/Seoul',
      roundSchedule: null,
    },
    seasons: [{
      id: SEASON_ID,
      name: '2026 여름 시즌',
      startDate: '2026-07-01',
      endDate: '2026-09-30',
      endedAt: null,
      previousSeasonId: null,
      timeZone: 'Asia/Seoul',
      roundSchedule: null,
    }],
    members: [
      {
        id: OWNER_MEMBER_ID,
        name: '팀 소유자',
        initials: '소',
        tone: '#d9e4da',
        deactivatedAt: null,
      },
      {
        id: INVITED_MEMBER_ID,
        name: '초대 구성원',
        initials: '초',
        tone: '#f1d6cc',
        deactivatedAt: null,
      },
    ],
    roles: [],
    routines: [],
    rounds: [],
    decisions: [],
    handoffItems: [],
    roleHandoffs: [],
    resources: [],
  }
}

async function installIdentityApi(page: Page, initialMode: IdentityMode) {
  let mode = initialMode
  let activeAccountId = OWNER_ACCOUNT_ID
  let failNextIssueAfterCommit = false
  let issueGate: Promise<void> | null = null
  let releaseIssue = () => {}
  const issueResults = new Map<string, OpenInvitation>()
  const calls: RecordedCall[] = []

  const requireDynamicCsrf = (route: Route) => {
    const request = route.request()
    return request.headers()[CSRF_HEADER_NAME.toLowerCase()] === CSRF_HEADER_VALUE
  }

  const handleApiRoute = async (route: Route) => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname
    const headers = request.headers()
    const body = request.postData() ? request.postDataJSON() : undefined
    calls.push({ method, path, headers, body })

    const json = (status: number, value: unknown) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(value),
      })
    const error = (status: number, code: string, message: string) =>
      json(status, { code, message })

    if (method === 'GET' && path === '/api/v1/auth/session') {
      const authenticated = mode !== 'anonymous'
      return json(200, {
        authenticated,
        accountId: authenticated ? activeAccountId : null,
        csrfHeaderName: authenticated ? CSRF_HEADER_NAME : null,
        csrfToken: authenticated ? CSRF_HEADER_VALUE : null,
        oidcEnabled: true,
      })
    }

    if (method === 'POST' && path === '/api/v1/me/workspaces') {
      if (mode === 'anonymous') {
        return error(401, 'AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')
      }
      if (!requireDynamicCsrf(route)) {
        return error(403, 'CSRF_TOKEN_INVALID', 'CSRF 토큰이 올바르지 않습니다.')
      }
      return json(201, {
        teamId: TEAM_ID,
        seasonId: SEASON_ID,
      })
    }

    if (method === 'GET' && path === `/api/v1/teams/${TEAM_ID}/membership`) {
      if (mode === 'anonymous') {
        return error(401, 'AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')
      }
      return json(200, {
        accountId: activeAccountId,
        teamId: TEAM_ID,
        memberId: OWNER_MEMBER_ID,
        boundAt: '2026-07-30T01:00:00Z',
        role: mode === 'owner' ? 'OWNER' : 'MEMBER',
      })
    }

    if (method === 'POST' && path === '/api/v1/identity/invitations/preview') {
      if (!requireDynamicCsrf(route)) {
        return error(403, 'INVALID_CSRF_TOKEN', 'CSRF 토큰이 올바르지 않습니다.')
      }
      if ((body as { token?: string })?.token !== ACCEPT_TOKEN) {
        return error(404, 'INVITATION_NOT_FOUND', '초대를 찾을 수 없습니다.')
      }
      return json(200, {
        teamId: TEAM_ID,
        teamName: '아이덴티티 팀',
        memberId: INVITED_MEMBER_ID,
        memberName: '초대 구성원',
        role: 'MEMBER',
        expiresAt: '2026-08-01T12:00:00Z',
        alreadyAccepted: false,
      })
    }

    if (method === 'POST' && path === '/api/v1/identity/invitations/accept') {
      if (!requireDynamicCsrf(route)) {
        return error(403, 'INVALID_CSRF_TOKEN', 'CSRF 토큰이 올바르지 않습니다.')
      }
      if ((body as { token?: string })?.token !== ACCEPT_TOKEN) {
        return error(404, 'INVITATION_NOT_FOUND', '초대를 찾을 수 없습니다.')
      }
      return json(200, {
        invitationId: INVITATION_ID,
        accountId: activeAccountId,
        teamId: TEAM_ID,
        memberId: INVITED_MEMBER_ID,
        boundAt: '2026-07-30T02:00:00Z',
        role: 'MEMBER',
      })
    }

    if (method === 'POST' && path === '/api/v1/session/logout') {
      if (!requireDynamicCsrf(route)) {
        return error(403, 'INVALID_CSRF_TOKEN', 'CSRF 토큰이 올바르지 않습니다.')
      }
      mode = 'anonymous'
      return route.fulfill({ status: 204 })
    }

    if (method === 'GET'
      && path === `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}/workspace`) {
      if (mode === 'anonymous' && headers['x-baton-access-key'] !== ACCESS_KEY) {
        return error(403, 'WORKSPACE_ACCESS_DENIED', '작업 공간 키가 올바르지 않습니다.')
      }
      if (mode !== 'anonymous' && headers['x-baton-access-key']) {
        return error(403, 'WORKSPACE_ACCESS_DENIED', '세션 요청에 레거시 키를 함께 보낼 수 없습니다.')
      }
      return json(200, workspaceProjection())
    }

    if (method === 'PUT'
      && path === `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}`) {
      if (!requireDynamicCsrf(route)) {
        return error(403, 'INVALID_CSRF_TOKEN', 'CSRF 토큰이 올바르지 않습니다.')
      }
      const legacyAccessKey = headers['x-baton-access-key']
      if (legacyAccessKey && legacyAccessKey !== ACCESS_KEY) {
        return error(403, 'WORKSPACE_ACCESS_DENIED', '작업 공간 키가 올바르지 않습니다.')
      }
      if (!legacyAccessKey && mode === 'anonymous') {
        return error(401, 'AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')
      }
      return json(200, {
        ...workspaceProjection().season,
        ...(body as Record<string, unknown>),
      })
    }

    if (method === 'GET' && path === MEMBER_INVITATIONS_PATH) {
      return json(200, [...issueResults.values()])
    }

    if (method === 'POST' && path === MEMBER_INVITATIONS_PATH) {
      if (!requireDynamicCsrf(route)) {
        return error(403, 'INVALID_CSRF_TOKEN', 'CSRF 토큰이 올바르지 않습니다.')
      }
      if (issueGate) {
        const gate = issueGate
        issueGate = null
        await gate
      }
      const idempotencyKey = headers['idempotency-key']
      if (!idempotencyKey) {
        return error(400, 'INVALID_IDEMPOTENCY_KEY', '멱등성 키가 필요합니다.')
      }
      let invitation = issueResults.get(idempotencyKey)
      const replayed = Boolean(invitation)
      if (!invitation) {
        invitation = {
          invitationId: INVITATION_ID,
          teamId: TEAM_ID,
          memberId: (body as { memberId: string }).memberId,
          issuedAt: '2026-07-30T03:00:00Z',
          expiresAt: '2026-08-01T03:00:00Z',
        }
        issueResults.set(idempotencyKey, invitation)
      }
      const response = { ...invitation, token: ISSUED_TOKEN }
      if (failNextIssueAfterCommit) {
        failNextIssueAfterCommit = false
        return route.abort('timedout')
      }
      return json(replayed ? 200 : 201, response)
    }

    const revocation = path.match(
      new RegExp(`^${MEMBER_INVITATIONS_PATH}/([^/]+)/revocation$`),
    )
    if (method === 'POST' && revocation) {
      if (!requireDynamicCsrf(route)) {
        return error(403, 'INVALID_CSRF_TOKEN', 'CSRF 토큰이 올바르지 않습니다.')
      }
      const invitation = [...issueResults.entries()].find(
        ([, value]) => value.invitationId === revocation[1],
      )
      if (invitation) issueResults.delete(invitation[0])
      return json(200, {
        invitationId: revocation[1],
        revokedAt: '2026-07-30T04:00:00Z',
      })
    }

    if (method === 'GET' && path === '/api/v1/auth/oidc/authorization/google') {
      return route.fulfill({
        status: 200,
        contentType: 'text/html',
        body: '<title>Google OIDC navigation</title>',
      })
    }

    return error(501, 'UNEXPECTED_REQUEST', `${method} ${path}`)
  }

  await page.route('**/api/v1/**', handleApiRoute)

  return {
    calls,
    setMode: (nextMode: IdentityMode) => {
      mode = nextMode
    },
    setAccountId: (nextAccountId: string) => {
      activeAccountId = nextAccountId
    },
    attachPage: (peerPage: Page) => peerPage.route('**/api/v1/**', handleApiRoute),
    failNextIssueAfterCommit: () => {
      failNextIssueAfterCommit = true
    },
    holdNextIssue: () => {
      issueGate = new Promise((resolve) => {
        releaseIssue = resolve
      })
    },
    releaseIssue: () => releaseIssue(),
  }
}

async function openWorkspace(page: Page, expectedMode: 'session' | 'legacy' = 'session') {
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page).toHaveURL(expectedMode === 'session'
    ? WORKSPACE_PATH
    : `${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.locator('.main-surface')).toBeVisible()
  await expect(page.locator('.workspace-switcher:visible, .mobile-team:visible'))
    .toContainText('아이덴티티 팀')
}

async function returnFromAnotherTab(page: Page) {
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'hidden',
    })
    window.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    })
    window.dispatchEvent(new Event('visibilitychange'))
  })
}

test.describe('계정과 초대', () => {
  test('@smoke 로그인 사용자는 초기 OWNER를 선택해 접근 키 없는 작업 공간을 만든다', async ({
    page,
  }) => {
    const api = await installIdentityApi(page, 'owner')
    await page.goto('/')

    await page.getByLabel('팀 이름').fill('아이덴티티 팀')
    await page.getByLabel('시즌 이름').fill('2026 여름 시즌')
    await page.getByLabel('시작일').fill('2026-07-01')
    await page.getByLabel('종료일').fill('2026-09-30')
    await page.getByRole('textbox', { name: /^구성원 이름/ }).fill('팀 소유자\n초대 구성원')
    await expect(page.getByLabel('파일럿 생성 코드 (선택)')).toHaveCount(0)
    await page.getByLabel('내 구성원 이름 (OWNER)').selectOption('팀 소유자')
    await page.getByRole('button', { name: '작업 공간 만들기' }).click()

    await expect(page).toHaveURL(WORKSPACE_PATH)
    await expect(page.locator('.main-surface')).toBeVisible()
    const createCall = api.calls.find((call) =>
      call.method === 'POST' && call.path === '/api/v1/me/workspaces')
    expect(createCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)
    expect(createCall?.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,64}$/)
    expect(createCall?.headers['x-baton-creation-key']).toBeUndefined()
    expect(createCall?.headers['x-baton-access-key']).toBeUndefined()
    expect(createCall?.body).toEqual({
      teamName: '아이덴티티 팀',
      seasonName: '2026 여름 시즌',
      startDate: '2026-07-01',
      endDate: '2026-09-30',
      memberNames: ['팀 소유자', '초대 구성원'],
      ownerMemberName: '팀 소유자',
    })
    expect(api.calls.some((call) =>
      call.method === 'POST' && call.path === '/api/v1/workspaces')).toBe(false)
    expect(await page.evaluate((key) =>
      localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBeNull()
    expect(await page.evaluate(() =>
      [...Array(localStorage.length).keys()]
        .map((index) => localStorage.key(index))
        .filter((key) => key?.startsWith('baton-pending-workspace-creation:v3:'))))
      .toEqual([])
  })

  test('@smoke 세션이 만료된 OWNER 생성은 레거시로 내려가지 않고 같은 계정 복구 기록을 보존한다', async ({
    page,
  }) => {
    const api = await installIdentityApi(page, 'owner')
    await page.goto('/')

    await page.getByLabel('팀 이름').fill('만료 경계 팀')
    await page.getByLabel('시즌 이름').fill('2026 가을 시즌')
    await page.getByLabel('시작일').fill('2026-09-01')
    await page.getByLabel('종료일').fill('2026-11-30')
    await page.getByRole('textbox', { name: /^구성원 이름/ }).fill('팀 소유자\n초대 구성원')
    await page.getByLabel('내 구성원 이름 (OWNER)').selectOption('팀 소유자')
    api.setMode('anonymous')
    await page.getByRole('button', { name: '작업 공간 만들기' }).click()

    await expect(page).toHaveURL('/')
    await expect(page.getByRole('alert')).toContainText('같은 계정으로 다시 로그인')
    expect(api.calls.some((call) =>
      call.method === 'POST' && call.path === '/api/v1/me/workspaces')).toBe(false)
    expect(api.calls.some((call) =>
      call.method === 'POST' && call.path === '/api/v1/workspaces')).toBe(false)
    expect(await page.evaluate(() =>
      [...Array(localStorage.length).keys()]
        .map((index) => localStorage.key(index))
        .filter((key) => key?.startsWith('baton-pending-workspace-creation:v3:'))
        .length)).toBe(1)
  })

  test('@smoke 현재 계정과 다른 생성 방식의 journal은 숨기고 레거시 이관 한도를 분리한다', async ({
    page,
  }) => {
    const api = await installIdentityApi(page, 'owner')
    await page.goto('/')
    const pendingPrefix = 'baton-pending-workspace-creation:v3:'
    const legacyStorageKey = 'baton-pending-workspace-creation:v1'
    const legacyIdempotencyKey = 'legacy-workspace-migration-journal-000001'

    await page.evaluate(({
      legacyIdempotencyKey: legacyKey,
      legacyStorageKey: legacySourceKey,
      otherAccountId,
      pendingPrefix: storagePrefix,
    }) => {
      for (let index = 0; index < 5; index += 1) {
        const idempotencyKey = `other-account-session-journal-00000${index}`
        const request = {
          teamName: `다른 계정 팀 ${index}`,
          seasonName: '2026 가을 시즌',
          startDate: '2026-09-01',
          endDate: '2026-11-30',
          memberNames: ['다른 계정 소유자'],
          mode: 'session',
          expectedAccountId: otherAccountId,
          ownerMemberName: '다른 계정 소유자',
        }
        localStorage.setItem(`${storagePrefix}${idempotencyKey}`, JSON.stringify({
          normalizedPayload: JSON.stringify(request),
          idempotencyKey,
          createdAt: 100 + index,
        }))
      }
      const legacyRequest = {
        teamName: '이관할 레거시 팀',
        seasonName: '2026 겨울 시즌',
        startDate: '2026-12-01',
        endDate: '2027-02-28',
        memberNames: ['레거시 구성원'],
      }
      localStorage.setItem(legacySourceKey, JSON.stringify({
        normalizedPayload: JSON.stringify(legacyRequest),
        idempotencyKey: legacyKey,
      }))
    }, {
      legacyIdempotencyKey,
      legacyStorageKey,
      otherAccountId: SECOND_ACCOUNT_ID,
      pendingPrefix,
    })

    await page.reload()
    await expect(page.getByText('Google 계정 연결됨')).toBeVisible()
    await expect(page.getByText(/확인하지 못한 생성 요청 \d+개/)).toHaveCount(0)
    expect(await page.evaluate(({
      legacyIdempotencyKey: legacyKey,
      legacyStorageKey: legacySourceKey,
      pendingPrefix: storagePrefix,
    }) => ({
      legacySource: localStorage.getItem(legacySourceKey),
      migratedLegacy: localStorage.getItem(`${storagePrefix}${legacyKey}`),
    }), {
      legacyIdempotencyKey,
      legacyStorageKey,
      pendingPrefix,
    })).toEqual({
      legacySource: null,
      migratedLegacy: expect.any(String),
    })

    api.setAccountId(SECOND_ACCOUNT_ID)
    await page.reload()
    const secondAccountPendingRegion = page.getByRole('region', {
      name: '확인되지 않은 작업 공간 생성 요청',
    })
    await expect(secondAccountPendingRegion.getByText('확인하지 못한 생성 요청 5개'))
      .toBeVisible()
    await expect(secondAccountPendingRegion.getByText('다른 계정 팀 0')).toBeVisible()

    api.setMode('anonymous')
    await page.reload()
    const pendingRegion = page.getByRole('region', {
      name: '확인되지 않은 작업 공간 생성 요청',
    })
    await pendingRegion.getByText('확인하지 못한 생성 요청 1개').click()
    await expect(pendingRegion.getByText('이관할 레거시 팀')).toBeVisible()
    expect(api.calls.some((call) =>
      call.method === 'POST' && call.path.includes('/workspaces'))).toBe(false)
  })

  test('@smoke 익명 사용자는 원시 OIDC 링크로 이동하고 공유 키만으로 초대 권한을 얻지 않는다', async ({
    page,
  }) => {
    const api = await installIdentityApi(page, 'anonymous')
    await page.goto('/')

    const login = page.getByRole('link', { name: 'Google로 로그인' })
    await expect(login).toHaveAttribute(
      'href',
      '/api/v1/auth/oidc/authorization/google',
    )
    const authorizationRequest = page.waitForRequest((request) =>
      new URL(request.url()).pathname === '/api/v1/auth/oidc/authorization/google')
    await login.click()
    await authorizationRequest

    await openWorkspace(page, 'legacy')
    await page.getByRole('button', { name: '계정·초대' }).click()
    const dialog = page.getByRole('dialog', { name: '계정·초대' })
    await expect(dialog.getByText('로그인 계정이 필요해요')).toBeVisible()
    await expect(dialog.getByText(/공유 키로 작업 공간을 열었더라도/)).toBeVisible()
    expect(api.calls.some((call) =>
      call.path === `/api/v1/teams/${TEAM_ID}/membership`)).toBe(false)
  })

  test('@smoke 로그인 사용자는 토큰을 미리 본 뒤 명시적으로 수락하며 비밀을 저장하지 않는다', async ({
    page,
  }) => {
    const api = await installIdentityApi(page, 'owner')
    await page.goto('/')

    const input = page.getByLabel('초대 토큰')
    await input.fill(ACCEPT_TOKEN)
    await page.getByRole('button', { name: '초대 내용 확인' }).click()
    await expect(page.getByText('초대 구성원').last()).toBeVisible()
    await expect(page.getByRole('button', { name: '이 구성원으로 연결' })).toBeVisible()

    const previewCall = api.calls.find(
      (call) => call.path === '/api/v1/identity/invitations/preview',
    )
    expect(previewCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)
    expect(previewCall?.body).toEqual({ token: ACCEPT_TOKEN })

    await page.getByRole('button', { name: '이 구성원으로 연결' }).click()
    await expect(page.getByRole('status')).toContainText('계정 연결을 완료했습니다')
    await expect(input).toHaveValue('')
    await expect(page).not.toHaveURL(new RegExp(ACCEPT_TOKEN))

    const storedSecrets = await page.evaluate((tokens) => {
      const values: string[] = []
      for (const storage of [window.localStorage, window.sessionStorage]) {
        for (let index = 0; index < storage.length; index += 1) {
          const key = storage.key(index)
          if (key) values.push(`${key}:${storage.getItem(key)}`)
        }
      }
      return values.filter((value) => tokens.some((token) => value.includes(token)))
    }, [ACCEPT_TOKEN, CSRF_HEADER_VALUE])
    expect(storedSecrets).toEqual([])
  })

  test('@smoke 로그아웃은 동적 CSRF를 사용하고 계정 범위 작업 공간 상태를 정리한다', async ({ page }) => {
    const api = await installIdentityApi(page, 'owner')
    await openWorkspace(page)
    const recentStorageKey =
      `baton-recent-workspaces:v2:${encodeURIComponent(OWNER_ACCOUNT_ID)}`
    await expect.poll(() =>
      page.evaluate((key) => localStorage.getItem(key), recentStorageKey),
    ).not.toBeNull()
    await page.evaluate(() => {
      localStorage.setItem('baton-recent-workspaces:v1', '[{"legacy":true}]')
      localStorage.setItem('baton-unrelated-journal:v1', 'keep-this-journal')
      sessionStorage.setItem('baton-round-entry:v1:logout-room', '{"version":1}')
    })
    await page.getByRole('button', { name: '계정·초대' }).click()
    const ownerDialog = page.getByRole('dialog', { name: '계정·초대' })
    await expect(ownerDialog.getByText('구성원 계정 초대')).toBeVisible()
    const invitationReadsBeforeLogout = api.calls.filter((call) =>
      call.method === 'GET' && call.path === MEMBER_INVITATIONS_PATH).length
    await ownerDialog.getByRole('button', { name: '계정·초대 닫기' }).click()

    await page.evaluate(() => {
      window.history.pushState({}, '', '/')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })

    await expect(page.getByRole('region', { name: '최근 작업 공간' }))
      .toContainText('아이덴티티 팀')
    expect(await page.evaluate(() =>
      localStorage.getItem('baton-recent-workspaces:v1'))).toBeNull()
    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page.getByRole('status')).toContainText('계정에 연결된 작업 공간 화면도 정리했습니다')
    expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`))
      .toBeNull()
    expect(await page.evaluate((key) => localStorage.getItem(key), recentStorageKey))
      .toBeNull()
    await expect(page.getByRole('region', { name: '최근 작업 공간' })).toHaveCount(0)
    expect(await page.evaluate(() =>
      localStorage.getItem('baton-unrelated-journal:v1'))).toBe('keep-this-journal')
    expect(await page.evaluate(() =>
      sessionStorage.getItem('baton-round-entry:v1:logout-room'))).toBeNull()

    const logoutCall = api.calls.find((call) => call.path === '/api/v1/session/logout')
    expect(logoutCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)

    await page.evaluate((path) => {
      window.history.pushState({}, '', path)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, WORKSPACE_PATH)
    await expect(page.getByText('이 작업 공간을 열 수 없어요.', { exact: true }))
      .toBeVisible()
    await expect(page.locator('.main-surface')).toHaveCount(0)
    expect(api.calls.filter((call) =>
      call.method === 'GET' && call.path === MEMBER_INVITATIONS_PATH)).toHaveLength(
      invitationReadsBeforeLogout,
    )
  })

  test('@smoke 계정 전환은 이전 계정 최근 목록을 지우고 새 계정 목록으로 전환한다', async ({
    page,
  }) => {
    const ownerRecentStorageKey =
      `baton-recent-workspaces:v2:${encodeURIComponent(OWNER_ACCOUNT_ID)}`
    const secondRecentStorageKey =
      `baton-recent-workspaces:v2:${encodeURIComponent(SECOND_ACCOUNT_ID)}`
    await page.addInitScript(({ ownerKey, secondKey }) => {
      const recent = (teamName: string) => JSON.stringify([{
        teamId: `${teamName}-team`,
        seasonId: `${teamName}-season`,
        teamName,
        seasonName: '계정별 시즌',
        lastOpenedAt: '2026-07-31T01:00:00.000Z',
      }])
      localStorage.setItem(ownerKey, recent('첫 번째 계정 팀'))
      localStorage.setItem(secondKey, recent('두 번째 계정 팀'))
      localStorage.setItem('baton-idempotency-journal:v1:keep', '{"version":1}')
      sessionStorage.setItem('baton-round-entry:v1:switch-room', '{"version":1}')
    }, { ownerKey: ownerRecentStorageKey, secondKey: secondRecentStorageKey })
    const api = await installIdentityApi(page, 'owner')
    await page.goto('/')
    await expect(page.getByRole('region', { name: '최근 작업 공간' }))
      .toContainText('첫 번째 계정 팀')
    await expect(page.getByText('두 번째 계정 팀')).toHaveCount(0)

    api.setAccountId(SECOND_ACCOUNT_ID)
    await returnFromAnotherTab(page)

    await expect(page.getByRole('region', { name: '최근 작업 공간' }))
      .toContainText('두 번째 계정 팀')
    await expect(page.getByText('첫 번째 계정 팀')).toHaveCount(0)
    await expect.poll(() =>
      page.evaluate((key) => localStorage.getItem(key), ownerRecentStorageKey),
    ).toBeNull()
    expect(await page.evaluate((key) =>
      localStorage.getItem(key), secondRecentStorageKey)).not.toBeNull()
    expect(await page.evaluate(() =>
      localStorage.getItem('baton-idempotency-journal:v1:keep')))
      .toBe('{"version":1}')
    expect(await page.evaluate(() =>
      sessionStorage.getItem('baton-round-entry:v1:switch-room'))).toBeNull()
  })

  test('@smoke 세션 작업 공간은 계정 캐시와 동적 CSRF를 쓰고 레거시 모드를 섞지 않는다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: () => Promise.reject(new Error('denied')) },
      })
    })
    const api = await installIdentityApi(page, 'owner')
    await openWorkspace(page)

    const workspaceGet = api.calls.find((call) =>
      call.method === 'GET'
      && call.path === `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}/workspace`)
    expect(workspaceGet?.headers['x-baton-access-key']).toBeUndefined()
    await expect(page.getByRole('button', { name: '키 관리' })).toHaveCount(0)

    await page.getByRole('button', { name: '공유' }).click()
    const shareDialog = page.getByRole('dialog', { name: '공유 링크 직접 복사' })
    await expect(shareDialog.getByLabel('공유 링크')).toHaveValue(
      `${new URL(page.url()).origin}${WORKSPACE_PATH}`,
    )
    await expect(shareDialog).toContainText('이 주소만으로 권한이 생기지 않으며')
    await shareDialog.getByRole('button', { name: '확인' }).click()

    const result = await page.evaluate(async ({
      accountId,
      accessKey,
      seasonId,
      teamId,
    }) => {
      const { queryClient } = await import('/src/shared/api/queryClient.ts')
      const { currentCsrfCredential } = await import('/src/features/identity/queries.ts')
      const {
        createWorkspaceScope,
        updateSeason,
      } = await import('/src/features/workspace/api.ts')
      const { workspaceKeys } = await import('/src/features/workspace/queries.ts')
      const currentCsrf = () => currentCsrfCredential(queryClient, false)
      const sessionScope = createWorkspaceScope(
        teamId,
        seasonId,
        { mode: 'session', accountId },
        currentCsrf,
      )
      await updateSeason(sessionScope, {
        name: '세션 수정',
        startDate: '2026-07-01',
        endDate: '2026-09-30',
      })
      const legacyScope = createWorkspaceScope(
        teamId,
        seasonId,
        { mode: 'legacy', accessKey, cacheIdentity: 'legacy-test' },
        currentCsrf,
      )
      await updateSeason(legacyScope, {
        name: '레거시 수정',
        startDate: '2026-07-01',
        endDate: '2026-09-30',
      })
      let invalidLegacyCode = ''
      try {
        await updateSeason(
          createWorkspaceScope(
            teamId,
            seasonId,
            {
              mode: 'legacy',
              accessKey: 'invalid-legacy-key',
              cacheIdentity: 'invalid-legacy-test',
            },
            currentCsrf,
          ),
          {
            name: '실패해야 하는 수정',
            startDate: '2026-07-01',
            endDate: '2026-09-30',
          },
        )
      } catch (error) {
        invalidLegacyCode = (error as { code?: string }).code ?? ''
      }
      return {
        invalidLegacyCode,
        sessionQueryKey: workspaceKeys.detail(
          teamId,
          seasonId,
          sessionScope.authCacheIdentity,
        ),
      }
    }, {
      accountId: OWNER_ACCOUNT_ID,
      accessKey: ACCESS_KEY,
      seasonId: SEASON_ID,
      teamId: TEAM_ID,
    })

    expect(JSON.stringify(result.sessionQueryKey)).toContain(OWNER_ACCOUNT_ID)
    expect(JSON.stringify(result.sessionQueryKey)).not.toContain(ACCESS_KEY)
    expect(result.invalidLegacyCode).toBe('WORKSPACE_ACCESS_DENIED')
    const updates = api.calls.filter((call) =>
      call.method === 'PUT'
      && call.path === `/api/v1/teams/${TEAM_ID}/seasons/${SEASON_ID}`)
    expect(updates).toHaveLength(3)
    expect(updates[0]?.headers['x-baton-access-key']).toBeUndefined()
    expect(updates[1]?.headers['x-baton-access-key']).toBe(ACCESS_KEY)
    expect(updates[2]?.headers['x-baton-access-key']).toBe('invalid-legacy-key')
    updates.forEach((call) => {
      expect(call.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)
    })
  })

  test('@smoke 다른 탭의 세션 만료는 화면 메모리의 초대 비밀과 OWNER 상태를 즉시 지운다', async ({
    page,
  }) => {
    const api = await installIdentityApi(page, 'owner')
    await page.goto('/')

    const tokenInput = page.getByLabel('초대 토큰')
    await tokenInput.fill(ACCEPT_TOKEN)
    await page.getByRole('button', { name: '초대 내용 확인' }).click()
    await expect(page.getByText('초대 구성원').last()).toBeVisible()

    api.setMode('anonymous')
    await returnFromAnotherTab(page)
    await expect(page.getByRole('link', { name: 'Google로 로그인' })).toBeVisible()
    await expect(tokenInput).toHaveValue('')
    await expect(page.getByRole('button', { name: '이 구성원으로 연결' })).toHaveCount(0)

    api.setMode('owner')
    await openWorkspace(page)
    await page.getByRole('button', { name: '계정·초대' }).click()
    const dialog = page.getByRole('dialog', { name: '계정·초대' })
    await dialog.getByRole('button', { name: '일회성 초대 발급' }).click()
    await expect(dialog.getByLabel('발급된 초대 토큰')).toHaveValue(ISSUED_TOKEN)
    await page.evaluate(() => {
      sessionStorage.setItem('baton-round-entry:v1:test-room', '{"version":1}')
    })

    api.setMode('anonymous')
    await returnFromAnotherTab(page)
    await expect(dialog).toHaveCount(0)
    await expect(page.getByText('이 작업 공간을 열 수 없어요.', { exact: true }))
      .toBeVisible()
    expect(await page.evaluate(() =>
      sessionStorage.getItem('baton-round-entry:v1:test-room'))).toBeNull()
  })

  test('@smoke OWNER는 응답 유실 뒤 같은 키로 재시도하고 토큰 복사 후 초대를 폐기한다', async ({
    context,
    page,
  }, testInfo) => {
    test.skip(testInfo.project.name === 'mobile', '발급·재생 계약은 데스크톱에서 한 번 검증합니다.')
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    const api = await installIdentityApi(page, 'owner')
    api.failNextIssueAfterCommit()
    await openWorkspace(page)

    await page.getByRole('button', { name: '계정·초대' }).click()
    const dialog = page.getByRole('dialog', { name: '계정·초대' })
    await expect(dialog.getByText('구성원 계정 초대')).toBeVisible()
    await dialog.getByLabel('초대할 구성원').selectOption(INVITED_MEMBER_ID)
    await dialog.getByRole('button', { name: '일회성 초대 발급' }).click()
    await expect(dialog.getByRole('alert')).toContainText(/요청 결과를 확인할 수 없습니다/)

    const journal = await page.evaluate((key) => {
      const value = localStorage.getItem(key)
      return value ? JSON.parse(value) as Record<string, unknown> : null
    }, PENDING_INVITATION_KEY)
    expect(journal).toEqual({
      teamId: TEAM_ID,
      memberId: INVITED_MEMBER_ID,
      idempotencyKey: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ),
    })

    await dialog.getByRole('button', { name: '같은 요청으로 결과 다시 확인' }).click()
    await expect.poll(() => api.calls.filter((call) =>
      call.path === MEMBER_INVITATIONS_PATH && call.method === 'POST').length).toBe(2)
    const issueCalls = api.calls.filter((call) =>
      call.path === MEMBER_INVITATIONS_PATH && call.method === 'POST')
    expect(issueCalls).toHaveLength(2)
    expect(issueCalls[0]?.headers['idempotency-key']).toBe(
      issueCalls[1]?.headers['idempotency-key'],
    )
    expect(issueCalls[1]?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)
    expect(issueCalls[1]?.headers['x-baton-access-key']).toBeUndefined()

    const issuedToken = dialog.getByLabel('발급된 초대 토큰')
    await expect(issuedToken).toHaveValue(ISSUED_TOKEN)
    await dialog.getByRole('button', { name: '토큰 복사' }).click()
    await expect(issuedToken).toHaveCount(0)
    expect(await page.evaluate((key) => localStorage.getItem(key), PENDING_INVITATION_KEY))
      .toBeNull()
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(ISSUED_TOKEN)

    await expect(dialog.getByRole('heading', { name: '열린 초대' })).toBeVisible()
    await expect(dialog.getByText('초대 구성원').last()).toBeVisible()
    await dialog.getByRole('button', { name: '초대 폐기' }).click()
    await expect(dialog.getByText('열린 초대가 없습니다.')).toBeVisible()
    const revokeCall = api.calls.find((call) => call.path.endsWith('/revocation'))
    expect(revokeCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)
  })

  test('@smoke MEMBER는 초대 발급·목록을 사용할 수 없다', async ({ page }) => {
    const api = await installIdentityApi(page, 'member')
    await openWorkspace(page)
    await page.getByRole('button', { name: '계정·초대' }).click()

    const dialog = page.getByRole('dialog', { name: '계정·초대' })
    await expect(dialog.getByText('소유자만 초대를 관리할 수 있어요')).toBeVisible()
    await expect(dialog.getByRole('button', { name: /초대 발급/ })).toHaveCount(0)
    expect(api.calls.some((call) => call.path === MEMBER_INVITATIONS_PATH)).toBe(false)
  })

  test('@smoke 두 탭이 동시에 발급해도 Web Lock이 POST를 하나로 제한한다', async ({
    context,
    page,
  }, testInfo) => {
    test.skip(testInfo.project.name === 'mobile', '다중 탭 동시성은 데스크톱에서 한 번 검증합니다.')
    const api = await installIdentityApi(page, 'owner')
    const peerPage = await context.newPage()
    await api.attachPage(peerPage)
    await openWorkspace(page)
    await openWorkspace(peerPage)

    await page.getByRole('button', { name: '계정·초대' }).click()
    await peerPage.getByRole('button', { name: '계정·초대' }).click()
    const dialog = page.getByRole('dialog', { name: '계정·초대' })
    const peerDialog = peerPage.getByRole('dialog', { name: '계정·초대' })
    await dialog.getByLabel('초대할 구성원').selectOption(INVITED_MEMBER_ID)
    await peerDialog.getByLabel('초대할 구성원').selectOption(INVITED_MEMBER_ID)

    api.holdNextIssue()
    await dialog.getByRole('button', { name: '일회성 초대 발급' }).click()
    await expect.poll(() => api.calls.filter((call) =>
      call.method === 'POST' && call.path === MEMBER_INVITATIONS_PATH).length).toBe(1)

    await peerDialog.getByRole('button', { name: /초대 발급|같은 요청/ }).click()
    await expect(peerDialog.getByRole('alert')).toContainText('다른 탭에서 이 팀의 초대를 처리')
    expect(api.calls.filter((call) =>
      call.method === 'POST' && call.path === MEMBER_INVITATIONS_PATH)).toHaveLength(1)

    api.releaseIssue()
    await expect(dialog.getByLabel('발급된 초대 토큰')).toHaveValue(ISSUED_TOKEN)
  })

  test('@responsive 모바일 계정·초대 모달은 키보드로 닫히고 주요 조작 영역이 44px 이상이다', async ({
    page,
  }, testInfo) => {
    test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')
    await installIdentityApi(page, 'owner')
    await openWorkspace(page)

    const entry = page.getByRole('button', { name: '계정·초대' })
    const entryBox = await entry.boundingBox()
    expect(entryBox?.height).toBeGreaterThanOrEqual(44)
    await entry.click()

    const dialog = page.getByRole('dialog', { name: '계정·초대' })
    await expect(dialog).toBeVisible()
    const issueButton = dialog.getByRole('button', { name: '일회성 초대 발급' })
    const issueBox = await issueButton.boundingBox()
    expect(issueBox?.height).toBeGreaterThanOrEqual(44)

    await page.keyboard.press('Tab')
    await expect(dialog.locator(':focus')).toHaveCount(1)
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(entry).toBeFocused()
  })
})
