import { expect, test } from '@playwright/test'
import type { BrowserContext, Page, Route } from '@playwright/test'
import {
  contrastRatio,
  ACCESS_KEY,
  installApi,
  navigation,
  openSharedWorkspace,
  SEASON_ID,
  SCOPE_PATH,
  TEAM_ID,
  WORKSPACE_PATH,
} from './support/workspaceApiHarness'

const ACCOUNT_ID = '8e448211-66ae-44ab-9888-c4960648c22b'
const CSRF_HEADER_NAME = 'X-CSRF-TOKEN'
const CSRF_TOKEN = 'e2e-csrf-token'
const EMAIL = 'member@example.com'
const PASSWORD = 'correct horse battery staple'
const VERIFICATION_TOKEN = 'verification-token-'.padEnd(48, 'a')
const ROUND_ROOM_PATH = '/room/bcdf-ghjk-mnpq'

type AuthProvider = 'google' | 'naver'

type AuthCall = {
  body: string | null
  headers: Record<string, string>
  method: string
  pageHash: string
  path: string
}

type AuthApiOptions = {
  sessionState?: { authenticated: boolean }
  additiveResponseFields?: boolean
  authenticated?: boolean
  csrfHeaderName?: string
  localRegistrationEnabled?: boolean
  providerFailuresBeforeSuccess?: number
  providersDeferred?: boolean
  providers?: AuthProvider[]
  registrationVerificationRequired?: boolean
  verificationFailure?: 'invalid' | 'transientOnce' | 'responseLostOnce'
}

async function installAuthApi(target: Page | BrowserContext, options: AuthApiOptions = {}) {
  const sessionState = options.sessionState ?? { authenticated: options.authenticated ?? false }
  const csrfHeaderName = options.csrfHeaderName ?? CSRF_HEADER_NAME
  const responseExtension = options.additiveResponseFields
    ? { futureServerField: 'ignored' }
    : {}
  let providerAttempts = 0
  let verificationAttempts = 0
  const providers = options.providers ?? []
  const calls: AuthCall[] = []
  const providersGate = Promise.withResolvers<void>()

  await target.route('**/api/v1/auth/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const headers = request.headers()
    const body = request.postData()
    calls.push({
      body,
      headers,
      method,
      pageHash: new URL(request.frame().url()).hash,
      path,
    })

    const json = (status: number, value: unknown) => route.fulfill({
      status,
      json: value,
    })
    const error = (status: number, code: string, message: string) =>
      json(status, { code, message })

    if (method === 'GET' && path === '/api/v1/auth/providers') {
      providerAttempts += 1
      if (options.providersDeferred && providerAttempts === 1) {
        await providersGate.promise
      }
      if (providerAttempts <= (options.providerFailuresBeforeSuccess ?? 0)) {
        return error(
          500,
          'AUTH_PROVIDERS_UNAVAILABLE',
          '로그인 수단을 확인하지 못했습니다.',
        )
      }
      return json(200, {
        providers,
        localRegistrationEnabled: options.localRegistrationEnabled ?? true,
        ...responseExtension,
      })
    }
    if (method === 'GET' && path === '/api/v1/auth/session') {
      return json(200, sessionState.authenticated
        ? {
            authenticated: true,
            accountId: ACCOUNT_ID,
            csrfHeaderName,
            csrfToken: CSRF_TOKEN,
            ...responseExtension,
          }
        : { authenticated: false, ...responseExtension })
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      return json(200, {
        csrfHeaderName,
        csrfToken: CSRF_TOKEN,
        ...responseExtension,
      })
    }

    if (method === 'POST') {
      if (headers[csrfHeaderName.toLowerCase()] !== CSRF_TOKEN) {
        return error(403, 'CSRF_DENIED', 'CSRF token이 필요합니다.')
      }

      if (path === '/api/v1/auth/local/registrations') {
        return json(202, {
          verificationRequired: options.registrationVerificationRequired ?? true,
          ...responseExtension,
        })
      }
      if (path === '/api/v1/auth/local/email-verifications') {
        verificationAttempts += 1
        if (options.verificationFailure === 'responseLostOnce'
          && verificationAttempts === 1) {
          return route.abort('connectionreset')
        }
        if (options.verificationFailure === 'transientOnce'
          && verificationAttempts === 1) {
          return error(
            503,
            'EMAIL_VERIFICATION_UNAVAILABLE',
            '잠시 후 같은 요청을 다시 시도해 주세요.',
          )
        }
        if (options.verificationFailure === 'invalid'
          || options.verificationFailure === 'responseLostOnce') {
          return error(
            400,
            'EMAIL_VERIFICATION_INVALID',
            '이메일 인증 정보가 올바르지 않거나 만료되었습니다.',
          )
        }
        return route.fulfill({ status: 204 })
      }
      if (path === '/api/v1/auth/local/session') {
        sessionState.authenticated = true
        return route.fulfill({ status: 204 })
      }
      if (path === '/api/v1/auth/logout') {
        sessionState.authenticated = false
        return route.fulfill({ status: 204 })
      }
    }

    return error(
      501,
      'UNEXPECTED_TEST_REQUEST',
      `예상하지 못한 요청: ${method} ${path}`,
    )
  })

  return {
    calls,
    releaseProviders: providersGate.resolve,
  }
}

function callsFor(calls: AuthCall[], method: string, path: string) {
  return calls.filter((call) => call.method === method && call.path === path)
}

function requiredCall(calls: AuthCall[], method: string, path: string) {
  const call = callsFor(calls, method, path).at(-1)
  expect(call, `${method} ${path} 요청`).toBeDefined()
  return call!
}

async function waitForCall(calls: AuthCall[], method: string, path: string) {
  await expect.poll(() => callsFor(calls, method, path).length).toBeGreaterThan(0)
}

async function fillVerificationPassword(page: Page, password = PASSWORD) {
  await page.locator('input[name="password"]').fill(password)
  await page.locator('input[name="passwordConfirmation"]').fill(password)
}

async function installRoundRoomDocument(page: Page) {
  let documentRequests = 0
  await page.route(`**${ROUND_ROOM_PATH}`, async (route) => {
    if (route.request().resourceType() !== 'document') {
      await route.continue()
      return
    }
    documentRequests += 1
    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: '<main><h1>ROUND document boundary</h1></main>',
    })
  })
  return () => documentRequests
}

test('설정된 로그인 공급자만 노출하고 local 로그인을 항상 유지한다', async ({ page }) => {
  const api = await installAuthApi(page, { providers: ['google'] })
  await page.goto('/login')
  await waitForCall(api.calls, 'GET', '/api/v1/auth/providers')

  await expect(page.getByRole('link', { name: 'Google로 계속하기' }))
    .toHaveAttribute('href', '/oauth2/authorization/google')
  await expect(page.getByRole('link', { name: 'Naver로 계속하기' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()
})

test('인증 응답의 additive field를 무시한다', async ({ page }) => {
  const api = await installAuthApi(page, {
    additiveResponseFields: true,
    providers: ['google', 'google'],
    registrationVerificationRequired: false,
  })
  await page.goto('/register')

  await page.getByLabel('표시 이름').fill('박민서')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByRole('button', { name: '인증 메일 받기' }).click()
  await expect(page.getByRole('heading', { name: '인증 메일을 확인해 주세요.' }))
    .toBeVisible()

  await page.goto('/login')
  await expect(page.getByRole('link', { name: 'Google로 계속하기' })).toHaveCount(2)
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()
  await expect(page).toHaveURL(/\/$/)
  expect(callsFor(api.calls, 'GET', '/api/v1/auth/csrf').length)
    .toBeGreaterThanOrEqual(2)
})

test('@webkit CSRF 헤더 이름은 브라우저 Headers 규칙으로 검증한다', async ({ page }) => {
  await installAuthApi(page, { csrfHeaderName: 'X CSRF TOKEN' })
  await page.goto('/register')

  await page.getByLabel('표시 이름').fill('박민서')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByRole('button', { name: '인증 메일 받기' }).click()

  await expect(page.getByRole('alert')).toContainText(
    '서버 응답을 확인할 수 없습니다.',
  )
})

test('공급자가 하나도 없으면 social 진입점을 숨기고 fail-closed 한다', async ({ page }) => {
  const api = await installAuthApi(page)
  await page.goto('/login')
  await waitForCall(api.calls, 'GET', '/api/v1/auth/providers')

  await expect(page.locator('.social-login')).toHaveCount(0)
  await expect(page.getByText('또는 이메일')).toHaveCount(0)
  await expect(page.getByLabel('이메일')).toBeVisible()
  await expect(page.getByLabel('비밀번호')).toBeVisible()
})

test('공급자 조회가 지연되어도 local 로그인을 즉시 사용할 수 있다', async ({ page }) => {
  const api = await installAuthApi(page, {
    providers: ['google'],
    providersDeferred: true,
  })
  await page.goto('/login')

  await expect(page.getByRole('status')).toContainText('소셜 로그인 수단을 확인하고 있습니다.')
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeEnabled()
  await expect(page.getByLabel('이메일')).toBeEditable()

  api.releaseProviders()
  await expect(page.getByRole('link', { name: 'Google로 계속하기' })).toBeVisible()
  await expect(page.getByText('소셜 로그인 수단을 확인하고 있습니다.')).toHaveCount(0)
})

test('공급자 조회 500을 local 로그인과 격리하고 재시도한다', async ({ page }) => {
  const api = await installAuthApi(page, {
    providerFailuresBeforeSuccess: 1,
    providers: ['google'],
  })
  await page.goto('/login')

  await expect(page.getByRole('alert')).toContainText('소셜 로그인 수단을 불러오지 못했습니다.')
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeEnabled()
  await page.getByRole('button', { name: '소셜 로그인 다시 확인' }).click()

  await expect(page.getByRole('link', { name: 'Google로 계속하기' })).toBeVisible()
  expect(callsFor(api.calls, 'GET', '/api/v1/auth/providers')).toHaveLength(2)
})

test('OAuth login_failed를 안내한 뒤 오류 query만 지우고 안전한 복귀 경로를 유지한다', async ({ page }) => {
  await installAuthApi(page)
  const query = new URLSearchParams({
    oauthError: 'login_failed',
    returnTo: WORKSPACE_PATH,
    source: 'oauth callback',
  })
  await page.goto(`/login?${query}`)

  const alert = page.getByRole('alert')
  await expect(alert).toContainText('소셜 로그인을 완료하지 못했습니다.')
  await expect(alert).toContainText('다시 시도하거나 다른 로그인 수단을 선택해 주세요.')
  await expect.poll(() => new URL(page.url()).searchParams.has('oauthError')).toBe(false)
  const scrubbedUrl = new URL(page.url())
  expect(scrubbedUrl.searchParams.get('returnTo')).toBe(WORKSPACE_PATH)
  expect(scrubbedUrl.searchParams.get('source')).toBe('oauth callback')

  await page.reload()
  await expect(page.getByText('소셜 로그인을 완료하지 못했습니다.')).toHaveCount(0)
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
})

test('OAuth temporarily_unavailable을 안내하고 기억한 복귀 경로와 local 로그인을 유지한다', async ({ page }) => {
  await page.addInitScript(({ key, returnTo }) => {
    if (window.name === 'oauth-return-seeded') return
    window.name = 'oauth-return-seeded'
    window.sessionStorage.setItem(key, returnTo)
  }, {
    key: 'baton-auth-return-to:v1',
    returnTo: WORKSPACE_PATH,
  })
  await installAuthApi(page)
  await page.goto('/login?oauthError=temporarily_unavailable&source=oauth')

  const alert = page.getByRole('alert')
  await expect(alert).toContainText('현재 인증 요청을 처리할 수 없습니다.')
  await expect(alert).toContainText('잠시 후 다시 시도해 주세요.')
  await expect.poll(() => new URL(page.url()).searchParams.has('oauthError')).toBe(false)
  expect(new URL(page.url()).searchParams.get('source')).toBe('oauth')
  expect(await page.evaluate(() => (
    window.sessionStorage.getItem('baton-auth-return-to:v1')
  ))).toBe(WORKSPACE_PATH)

  await page.reload()
  await expect(page.getByText('현재 인증 요청을 처리할 수 없습니다.')).toHaveCount(0)
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
})

test('알 수 없는 OAuth 오류는 노출하지 않고 해당 query만 지운다', async ({ page }) => {
  await installAuthApi(page)
  await page.goto('/login?oauthError=provider_private_detail&source=oauth')

  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeEnabled()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect.poll(() => new URL(page.url()).searchParams.has('oauthError')).toBe(false)
  expect(new URL(page.url()).searchParams.get('source')).toBe('oauth')
})

test('local 가입이 비활성화되면 CTA를 숨기고 직접 진입한 가입 화면을 닫는다', async ({ page }) => {
  const api = await installAuthApi(page, { localRegistrationEnabled: false })
  await page.goto('/login')
  await waitForCall(api.calls, 'GET', '/api/v1/auth/providers')

  await expect(page.getByRole('link', { name: '계정 만들기' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()

  await page.goto('/register')
  await expect(page.getByRole('heading', {
    name: '현재 새 자체 이메일 계정을 만들 수 없습니다.',
  })).toBeVisible()
  await expect(page.getByLabel('표시 이름')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '로그인 화면으로' })).toBeVisible()
  expect(callsFor(api.calls, 'POST', '/api/v1/auth/local/registrations')).toHaveLength(0)
})

test('자체 이메일 가입은 비밀번호 없이 JSON 등록 요청을 보낸다', async ({ page }) => {
  const api = await installAuthApi(page)
  await page.goto('/register')

  await page.getByLabel('표시 이름').fill('박민서')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByRole('button', { name: '인증 메일 받기' }).click()

  await expect(page.getByRole('heading', { name: '인증 메일을 확인해 주세요.' }))
    .toBeVisible()
  await expect(page.getByRole('status')).toContainText('비밀번호를 정하면')
  const registration = requiredCall(
    api.calls,
    'POST',
    '/api/v1/auth/local/registrations',
  )
  expect(registration.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(registration.headers['content-type']).toContain('application/json')
  const body = JSON.parse(registration.body ?? '{}') as Record<string, unknown>
  expect(body).toEqual({ displayName: '박민서', email: EMAIL })
  expect(body).not.toHaveProperty('password')
})

test('이메일 fragment를 먼저 제거하고 token과 새 비밀번호를 한 번만 검증한다', async ({ page }) => {
  const api = await installAuthApi(page)
  await page.goto(`/verify-email#token=${encodeURIComponent(VERIFICATION_TOKEN)}`)

  await expect(page).toHaveURL(/\/verify-email$/)
  await fillVerificationPassword(page)
  await page.getByRole('button', { name: '비밀번호 정하고 인증 완료' }).click()

  await expect(page.getByRole('heading', { name: '이메일 인증을 완료했습니다.' }))
    .toBeVisible()
  const verifications = callsFor(
    api.calls,
    'POST',
    '/api/v1/auth/local/email-verifications',
  )
  expect(verifications).toHaveLength(1)
  expect(verifications[0]?.pageHash).toBe('')
  expect(verifications[0]?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(JSON.parse(verifications[0]?.body ?? '{}')).toEqual({
    token: VERIFICATION_TOKEN,
    password: PASSWORD,
  })
})

test('일시적 이메일 검증 실패는 제거한 token과 비밀번호로 재시도한다', async ({ page }) => {
  const api = await installAuthApi(page, { verificationFailure: 'transientOnce' })
  await page.goto(`/verify-email#token=${encodeURIComponent(VERIFICATION_TOKEN)}`)
  await fillVerificationPassword(page)

  await page.getByRole('button', { name: '비밀번호 정하고 인증 완료' }).click()
  await expect(page.getByRole('alert')).toContainText('잠시 후 같은 요청을 다시 시도해 주세요.')
  await expect(page).toHaveURL(/\/verify-email$/)

  await page.getByRole('button', { name: '비밀번호 정하고 인증 완료' }).click()
  await expect(page.getByRole('heading', { name: '이메일 인증을 완료했습니다.' }))
    .toBeVisible()

  const verifications = callsFor(
    api.calls,
    'POST',
    '/api/v1/auth/local/email-verifications',
  )
  expect(verifications).toHaveLength(2)
  expect(verifications.every((call) => call.pageHash === '')).toBe(true)
  expect(verifications.map((call) => JSON.parse(call.body ?? '{}'))).toEqual([
    { token: VERIFICATION_TOKEN, password: PASSWORD },
    { token: VERIFICATION_TOKEN, password: PASSWORD },
  ])
})

test('유효하지 않은 이메일 token은 비밀번호 form을 닫고 새 메일을 안내한다', async ({ page }) => {
  const api = await installAuthApi(page, { verificationFailure: 'invalid' })
  await page.goto(`/verify-email#token=${encodeURIComponent(VERIFICATION_TOKEN)}`)
  await fillVerificationPassword(page)
  await page.getByRole('button', { name: '비밀번호 정하고 인증 완료' }).click()

  await expect(page.getByRole('heading', { name: '인증 링크를 확인해 주세요.' }))
    .toBeVisible()
  await expect(page.getByRole('link', { name: '인증 메일 다시 받기' }))
    .toHaveAttribute('href', '/register')
  await expect(page.locator('input[name="password"]')).toHaveCount(0)
  expect(callsFor(
    api.calls,
    'POST',
    '/api/v1/auth/local/email-verifications',
  )).toHaveLength(1)
})

test('@smoke 이메일 인증 성공 응답을 잃으면 재시도 뒤 로그인으로 복구한다', async ({ page }) => {
  const api = await installAuthApi(page, { verificationFailure: 'responseLostOnce' })
  await page.goto(`/verify-email#token=${encodeURIComponent(VERIFICATION_TOKEN)}`)
  await fillVerificationPassword(page)

  await page.getByRole('button', { name: '비밀번호 정하고 인증 완료' }).click()
  await expect(page.getByRole('alert')).toContainText('요청 결과를 확인할 수 없습니다.')
  await page.getByRole('button', { name: '비밀번호 정하고 인증 완료' }).click()

  await expect(page.getByRole('alert')).toContainText('해당 비밀번호로 먼저 로그인해 보세요.')
  await page.getByRole('link', { name: '로그인하기', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(/\/$/)
  expect(callsFor(api.calls, 'POST', '/api/v1/auth/local/email-verifications')).toHaveLength(2)
  expect(callsFor(api.calls, 'POST', '/api/v1/auth/local/registrations')).toHaveLength(0)
})

test('@smoke local 로그인과 로그아웃은 매번 CSRF를 받고 session 상태를 갱신한다', async ({ page, context }) => {
  const api = await installAuthApi(page)
  await page.goto('/login')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(/\/$/)
  const login = requiredCall(api.calls, 'POST', '/api/v1/auth/local/session')
  expect(login.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(login.headers['content-type']).toContain('application/x-www-form-urlencoded')
  expect(Object.fromEntries(new URLSearchParams(login.body ?? ''))).toEqual({
    email: EMAIL,
    password: PASSWORD,
  })
  const loginIndex = api.calls.indexOf(login)
  expect(api.calls.some((call, index) =>
    index > loginIndex
      && call.method === 'GET'
      && call.path === '/api/v1/auth/session')).toBe(true)

  await page.goto('/login')
  await expect(page.getByText('이미 로그인되어 있습니다.')).toBeVisible()
  await expect(page.getByText(`계정 ID ${ACCOUNT_ID}`)).toBeVisible()
  const peer = await context.newPage()
  const workspaceApi = await installApi(peer)
  await openSharedWorkspace(peer)
  await page.evaluate(({ teamId, seasonId }) => {
    const roomId = 'bcdf-ghjk-mnpq'
    const resourceId = '00000000-0000-4000-8000-000000000056'
    localStorage.setItem('baton-access-key:00000000-0000-4000-8000-000000000099', 'other-capability')
    localStorage.setItem('unrelated-local-setting', 'keep')
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
    sessionStorage.setItem('unrelated-session-setting', 'keep')
  }, { teamId: TEAM_ID, seasonId: SEASON_ID })
  await page.getByRole('button', { name: '로그아웃' }).click()

  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()
  await expect(peer.getByText('접근 키 필요')).toBeVisible()
  const workspaceGetCount = () => workspaceApi.calls.filter((call) =>
    call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length
  const requestsAfterLogout = workspaceGetCount()
  await peer.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
  })
  await peer.waitForTimeout(300)
  expect(workspaceGetCount()).toBe(requestsAfterLogout)
  const logout = requiredCall(api.calls, 'POST', '/api/v1/auth/logout')
  expect(logout.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(callsFor(api.calls, 'GET', '/api/v1/auth/csrf').length).toBeGreaterThanOrEqual(2)
  expect(await page.evaluate(() => Object.keys(localStorage).filter((key) => (
    key.startsWith('baton-access-key:') || key === 'baton-recent-workspaces:v1'
  )))).toEqual([])
  expect(await page.evaluate(() => localStorage.getItem('unrelated-local-setting'))).toBe('keep')
  expect(await page.evaluate(() => Object.keys(sessionStorage).filter((key) => (
    key.startsWith('baton-round-entry:') || key.startsWith('baton-round-resource:')
  )))).toEqual([])
  expect(await page.evaluate(() => sessionStorage.getItem('unrelated-session-setting')))
    .toBe('keep')
  await peer.close()
})

for (const otherTab of [false, true]) {
  test(`@smoke ${otherTab ? '다른 탭' : '같은 탭'}에서 로그아웃한 뒤 지연된 키 변경 응답이 접근 키를 되살리지 않는다`, async ({ page, context }, testInfo) => {
    const workspaceApi = await installApi(page)
    const sessionState = { authenticated: true }
    await installAuthApi(page, { sessionState })
    await openSharedWorkspace(page)
    await page.goto('/')
    await page.getByRole('link', { name: /알고리즘 한 바퀴.*2026 여름 시즌/ }).click()

    const logoutPage = otherTab ? await context.newPage() : page
    if (otherTab) {
      await installAuthApi(logoutPage, { sessionState })
      await logoutPage.goto('/login')
      await expect(logoutPage.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible()
    }
    const chrome = testInfo.project.name === 'mobile' ? page.locator('.mobile-topbar') : page.locator('.sidebar')
    await chrome.getByRole('button', { name: '키 관리' }).click()
    workspaceApi.holdAccessKeyRotations()
    try {
      const rotationStarted = page.waitForRequest(`**${SCOPE_PATH}/access-key/rotate`)
      page.once('dialog', (dialog) => dialog.accept())
      await page.getByRole('dialog', { name: '공유 접근 키 관리' })
        .getByRole('button', { name: '접근 키 바꾸기' }).click()
      await rotationStarted
      if (!otherTab) {
        await page.goBack()
        await page.getByRole('link', { name: '계정 로그인' }).click()
      }
      await logoutPage.getByRole('button', { name: '로그아웃', exact: true }).click()
      await expect(logoutPage.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()
      if (otherTab) await expect(page.getByText('접근 키 필요')).toBeVisible()

      const rotationResponse = page.waitForResponse(`**${SCOPE_PATH}/access-key/rotate`)
      workspaceApi.releaseAccessKeyRotations()
      await (await rotationResponse).finished()
      await page.evaluate((teamId) => navigator.locks.request(`baton-access-key-rotation:${teamId}`, () => {}), TEAM_ID)

      expect(await page.evaluate((teamId) => localStorage.getItem(`baton-access-key:${teamId}`), TEAM_ID)).toBeNull()
      await page.reload()
      expect(await page.evaluate((teamId) => localStorage.getItem(`baton-access-key:${teamId}`), TEAM_ID)).toBeNull()
    } finally {
      workspaceApi.releaseAccessKeyRotations()
      if (otherTab) await logoutPage.close()
    }
  })
}

test('@smoke 로그인 성공 뒤 이전 세션 조회를 기다리지 않고 새 세션으로 이동한다', async ({ page }) => {
  const api = await installAuthApi(page)
  await page.goto('/login')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)

  const sessionStarted = Promise.withResolvers<void>()
  const sessionResponse = Promise.withResolvers<void>()
  await page.route('**/api/v1/auth/session', async (route) => {
    sessionStarted.resolve()
    await sessionResponse.promise
    await route.fulfill({ json: { authenticated: false } })
  }, { times: 1 })

  await page.evaluate(() => {
    window.dispatchEvent(new Event('visibilitychange'))
  })
  await sessionStarted.promise
  try {
    await page.getByRole('button', { name: '이메일로 로그인' }).click()
    await expect(page).toHaveURL(/\/$/)
    expect(callsFor(api.calls, 'POST', '/api/v1/auth/local/session')).toHaveLength(1)
  } finally {
    sessionResponse.resolve()
  }
})

test('@smoke 로그아웃 후 늦게 도착한 세션 응답으로 이전 계정을 표시하지 않는다', async ({ page }) => {
  await installAuthApi(page, { authenticated: true })
  await page.goto('/login')
  await expect(page.getByText('이미 로그인되어 있습니다.')).toBeVisible()

  const sessionStarted = Promise.withResolvers<void>()
  const sessionResponse = Promise.withResolvers<void>()
  await page.route('**/api/v1/auth/session', async (route) => {
    sessionStarted.resolve()
    await sessionResponse.promise
    await route.fulfill({
      json: {
        authenticated: true,
        accountId: ACCOUNT_ID,
        csrfHeaderName: CSRF_HEADER_NAME,
        csrfToken: CSRF_TOKEN,
      },
    })
  }, { times: 1 })

  await page.evaluate(() => {
    window.dispatchEvent(new Event('visibilitychange'))
  })
  await sessionStarted.promise
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()

  const lateResponse = page.waitForResponse('**/api/v1/auth/session')
  sessionResponse.resolve()
  await (await lateResponse).finished()

  await expect(page.getByText(`계정 ID ${ACCOUNT_ID}`)).toHaveCount(0)
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()
})

test('로그아웃 후 기기 정리 재시도는 서버 로그아웃을 반복하지 않는다', async ({ page }) => {
  const api = await installAuthApi(page, { authenticated: true })
  await page.addInitScript(({ accessKeyStorageKey, accessKey, teamId, seasonId }) => {
    const roomId = 'bcdf-ghjk-mnpq'
    const resourceId = '00000000-0000-4000-8000-000000000056'
    const originalRemoveItem = Storage.prototype.removeItem
    localStorage.setItem(accessKeyStorageKey, accessKey)
    localStorage.setItem('baton-recent-workspaces:v1', '[]')
    localStorage.setItem('unrelated-local-setting', 'keep')
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
    sessionStorage.setItem('unrelated-session-setting', 'keep')

    let accessKeyRemovalFailed = false
    Storage.prototype.removeItem = function removeItem(key) {
      if (this === localStorage
        && key === accessKeyStorageKey
        && !accessKeyRemovalFailed) {
        accessKeyRemovalFailed = true
        throw new DOMException('Storage disabled', 'SecurityError')
      }
      originalRemoveItem.call(this, key)
    }
  }, {
    accessKeyStorageKey: `baton-access-key:${TEAM_ID}`,
    accessKey: 'device-cleanup-capability',
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
  })

  await page.goto('/login')
  await expect(page.getByText('이미 로그인되어 있습니다.')).toBeVisible()
  await page.getByRole('button', { name: '로그아웃' }).click()

  await expect(page.getByRole('alert')).toContainText(
    '이 기기의 작업 공간 접근 정보를 모두 지우지 못했습니다.',
  )
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`))
    .toBe('device-cleanup-capability')
  expect(callsFor(api.calls, 'POST', '/api/v1/auth/logout')).toHaveLength(1)

  await page.getByRole('button', { name: '이 기기 접근 정보 다시 지우기' }).click()

  await expect(page.getByRole('status')).toContainText(
    '이 기기의 접근 정보를 정리했습니다.',
  )
  expect(callsFor(api.calls, 'POST', '/api/v1/auth/logout')).toHaveLength(1)
  expect(await page.evaluate(() => Object.keys(localStorage).filter((key) => (
    key.startsWith('baton-access-key:') || key === 'baton-recent-workspaces:v1'
  )))).toEqual([])
  expect(await page.evaluate(() => Object.keys(sessionStorage).filter((key) => (
    key.startsWith('baton-round-entry:') || key.startsWith('baton-round-resource:')
  )))).toEqual([])
  expect(await page.evaluate(() => localStorage.getItem('unrelated-local-setting'))).toBe('keep')
  expect(await page.evaluate(() => sessionStorage.getItem('unrelated-session-setting')))
    .toBe('keep')
})

test('@smoke 접근 키를 저장하지 못하면 새 탭에서 로그인하고 원래 작업 공간을 유지한다', async ({ page, context }, testInfo) => {
  await context.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith('baton-access-key:')) throw new DOMException('Storage disabled', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  })
  const sessionState = { authenticated: false }
  await installApi(page)
  await installAuthApi(page, { sessionState })
  const popupApi = await installAuthApi(context, { sessionState })
  await page.route('**/api/v1/account-memberships/**', (route) => route.fulfill({ json: { claimed: false } }))
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  const originalUrl = page.url()
  const personalPanel = page.getByRole('region', { name: '내 담당 업무' })
  const personalLogin = personalPanel.getByRole('link', { name: '로그인 (새 탭)', exact: true })
  await expect(personalLogin).toHaveAttribute('href', '/login')
  await expect(personalLogin).toHaveAttribute('target', '_blank')
  await expect(personalPanel).toContainText('이 탭을 닫지 말고 로그인 후 돌아와 주세요.')

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '구성원 관리' }).click()
  const membershipLogin = page.getByRole('link', { name: '로그인하고 연결하기 (새 탭)', exact: true })
  await expect(membershipLogin).toHaveAttribute('href', '/login')
  const popupPromise = context.waitForEvent('page')
  await membershipLogin.click()
  const popup = await popupPromise
  await expect(popup).toHaveURL(/\/login$/)
  expect(await popup.evaluate(() => window.opener)).toBeNull()
  await popup.getByLabel('이메일').fill(EMAIL)
  await popup.getByLabel('비밀번호').fill(PASSWORD)
  await popup.getByRole('button', { name: '이메일로 로그인' }).click()
  await expect(popup).toHaveURL(/\/$/)
  expect(requiredCall(popupApi.calls, 'POST', '/api/v1/auth/local/session').pageHash).toBe('')

  await popup.close()
  await page.bringToFront()
  await page.reload()
  await expect(personalPanel).toContainText('아직 연결한 팀 구성원이 없습니다.')
  await expect(page).toHaveURL(originalUrl)
  expect(originalUrl).toContain(`#accessKey=${ACCESS_KEY}`)
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBeNull()
})

test('로그인은 검증된 내부 workspace 경로로 돌아가고 임시 경로를 지운다', async ({ page }) => {
  await installAuthApi(page)
  await page.goto(`/login?returnTo=${encodeURIComponent(WORKSPACE_PATH)}`)
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByText('접근 키 필요')).toBeVisible()
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([])
})

test('로그인은 canonical ROUND 경로를 새 문서로 열고 임시 경로를 지운다', async ({ page }) => {
  const roundDocumentRequests = await installRoundRoomDocument(page)
  await installAuthApi(page)
  await page.goto(`/login?returnTo=${encodeURIComponent(ROUND_ROOM_PATH)}`)
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(new RegExp(`${ROUND_ROOM_PATH}$`))
  await expect(page.getByRole('heading', { name: 'ROUND document boundary' })).toBeVisible()
  expect(roundDocumentRequests()).toBe(1)
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([])
})

test('canonical 형식이 아닌 ROUND returnTo는 로그인 복귀 경로로 사용하지 않는다', async ({ page }) => {
  await installAuthApi(page)
  await page.goto('/login?returnTo=%2Froom%2Fbcdf-ghjk-mnpo')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(/\/$/)
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([])
})

test('외부 returnTo는 거부하고 로그인 뒤 시작 화면으로 이동한다', async ({ page }) => {
  await installAuthApi(page)
  await page.goto('/login?returnTo=%2F%2Fevil.example%2Fsteal')
  await page.getByLabel('이메일').fill(EMAIL)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '이메일로 로그인' }).click()

  await expect(page).toHaveURL(/\/$/)
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([])
})

test('소셜 callback session은 같은 탭의 검증된 workspace 복귀 경로를 이어 간다', async ({ page }) => {
  await page.addInitScript(({ key, returnTo }) => {
    window.sessionStorage.setItem(key, returnTo)
  }, {
    key: 'baton-auth-return-to:v1',
    returnTo: WORKSPACE_PATH,
  })
  await installAuthApi(page, { authenticated: true })
  await page.goto('/login')

  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.getByText('접근 키 필요')).toBeVisible()
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([])
})

test('소셜 callback session도 기억한 ROUND 경로를 새 문서로 연다', async ({ page }) => {
  const roundDocumentRequests = await installRoundRoomDocument(page)
  await page.addInitScript(({ key, returnTo }) => {
    if (window.name === 'baton-round-return-seeded') return
    window.name = 'baton-round-return-seeded'
    window.sessionStorage.setItem(key, returnTo)
  }, {
    key: 'baton-auth-return-to:v1',
    returnTo: ROUND_ROOM_PATH,
  })
  await installAuthApi(page, { authenticated: true })
  await page.goto('/login')

  await expect(page).toHaveURL(new RegExp(`${ROUND_ROOM_PATH}$`))
  await expect(page.getByRole('heading', { name: 'ROUND document boundary' })).toBeVisible()
  expect(roundDocumentRequests()).toBe(1)
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([])
})

test('@responsive 모바일 로그인은 가로 넘침 없이 키보드 focus와 터치 크기를 유지한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 viewport 전용 접근성 경계입니다.')
  await installAuthApi(page, { providers: ['google', 'naver'] })
  await page.goto('/login')

  const google = page.getByRole('link', { name: 'Google로 계속하기' })
  const naver = page.getByRole('link', { name: 'Naver로 계속하기' })
  const email = page.getByLabel('이메일')
  const password = page.getByLabel('비밀번호')
  const submit = page.getByRole('button', { name: '이메일로 로그인' })
  await expect(naver).toBeVisible()

  expect(await page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(await page.evaluate(() => document.documentElement.clientWidth))

  for (const target of [google, naver, email, password, submit]) {
    const box = await target.boundingBox()
    expect(box?.height).toBeGreaterThanOrEqual(44)
  }

  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'BATON 시작 화면' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(google).toBeFocused()
  const focusStyle = await google.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      style: style.outlineStyle,
      width: Number.parseFloat(style.outlineWidth),
    }
  })
  expect(focusStyle.style).toBe('solid')
  expect(focusStyle.width).toBeGreaterThanOrEqual(3)

  const naverColors = await naver.evaluate((element) => {
    const style = getComputedStyle(element)
    return { background: style.backgroundColor, foreground: style.color }
  })
  expect(contrastRatio(naverColors.foreground, naverColors.background))
    .toBeGreaterThanOrEqual(4.5)
})
