import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import type { WorkspaceProjection } from '../../src/features/workspace/types'

const fixtureUuid = (sequence: number) =>
  `10000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`

const TEAM_ID = fixtureUuid(1)
const SEASON_ID = fixtureUuid(2)
const OWNER_ACCOUNT_ID = fixtureUuid(3)
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
        accountId: authenticated ? OWNER_ACCOUNT_ID : null,
        csrfHeaderName: authenticated ? CSRF_HEADER_NAME : null,
        csrfToken: authenticated ? CSRF_HEADER_VALUE : null,
        oidcEnabled: true,
      })
    }

    if (method === 'GET' && path === `/api/v1/teams/${TEAM_ID}/membership`) {
      if (mode === 'anonymous') {
        return error(401, 'AUTHENTICATION_REQUIRED', '로그인이 필요합니다.')
      }
      return json(200, {
        accountId: OWNER_ACCOUNT_ID,
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
        accountId: OWNER_ACCOUNT_ID,
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
      if (headers['x-baton-access-key'] !== ACCESS_KEY) {
        return error(403, 'WORKSPACE_ACCESS_DENIED', '작업 공간 키가 올바르지 않습니다.')
      }
      return json(200, workspaceProjection())
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

async function openWorkspace(page: Page) {
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
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

    await openWorkspace(page)
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

  test('@smoke 로그아웃은 동적 CSRF를 사용하고 작업 공간 공유 키를 보존한다', async ({ page }) => {
    const api = await installIdentityApi(page, 'owner')
    await openWorkspace(page)
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

    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page.getByRole('status')).toContainText('저장된 작업 공간 키는 그대로 유지됩니다')
    expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`))
      .toBe(ACCESS_KEY)

    const logoutCall = api.calls.find((call) => call.path === '/api/v1/session/logout')
    expect(logoutCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_HEADER_VALUE)

    await page.evaluate((path) => {
      window.history.pushState({}, '', path)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, WORKSPACE_PATH)
    await expect(page.locator('.main-surface')).toBeVisible()
    await page.getByRole('button', { name: '계정·초대' }).click()
    const anonymousDialog = page.getByRole('dialog', { name: '계정·초대' })
    await expect(anonymousDialog.getByText('로그인 계정이 필요해요')).toBeVisible()
    await expect(anonymousDialog.getByText('구성원 계정 초대')).toHaveCount(0)
    expect(api.calls.filter((call) =>
      call.method === 'GET' && call.path === MEMBER_INVITATIONS_PATH)).toHaveLength(
      invitationReadsBeforeLogout,
    )
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

    api.setMode('anonymous')
    await returnFromAnotherTab(page)
    await expect(dialog.getByText('로그인 계정이 필요해요')).toBeVisible()
    await expect(dialog.getByLabel('발급된 초대 토큰')).toHaveCount(0)
    await expect(dialog.getByText('구성원 계정 초대')).toHaveCount(0)
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
