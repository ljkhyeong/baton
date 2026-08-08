import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { contrastRatio } from './support/workspaceApiHarness'

const ACCOUNT_ID = '8e448211-66ae-44ab-9888-c4960648c22b'
const CSRF_HEADER_NAME = 'X-CSRF-TOKEN'
const CSRF_TOKEN = 'e2e-csrf-token'
const EMAIL = 'member@example.com'
const PASSWORD = 'correct horse battery staple'
const VERIFICATION_TOKEN = 'verification-token-'.padEnd(48, 'a')

type AuthProvider = 'google' | 'naver'

type AuthCall = {
  body: string | null
  headers: Record<string, string>
  method: string
  pageHash: string
  path: string
}

type AuthApiOptions = {
  authenticated?: boolean
  providers?: AuthProvider[]
  verificationFailure?: 'invalid' | 'transientOnce'
}

async function installAuthApi(page: Page, options: AuthApiOptions = {}) {
  let authenticated = options.authenticated ?? false
  let verificationAttempts = 0
  const providers = options.providers ?? []
  const calls: AuthCall[] = []

  await page.route('**/api/v1/auth/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const headers = request.headers()
    const body = request.postData()
    calls.push({
      body,
      headers,
      method,
      pageHash: new URL(page.url()).hash,
      path,
    })

    const json = (status: number, value: unknown) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(value),
    })
    const error = (status: number, code: string, message: string) =>
      json(status, { code, message })

    if (method === 'GET' && path === '/api/v1/auth/providers') {
      return json(200, { providers })
    }
    if (method === 'GET' && path === '/api/v1/auth/session') {
      return json(200, authenticated
        ? {
            authenticated: true,
            accountId: ACCOUNT_ID,
            csrfHeaderName: CSRF_HEADER_NAME,
            csrfToken: CSRF_TOKEN,
          }
        : { authenticated: false })
    }
    if (method === 'GET' && path === '/api/v1/auth/csrf') {
      return json(200, {
        csrfHeaderName: CSRF_HEADER_NAME,
        csrfToken: CSRF_TOKEN,
      })
    }

    if (method === 'POST') {
      if (headers[CSRF_HEADER_NAME.toLowerCase()] !== CSRF_TOKEN) {
        return error(403, 'CSRF_DENIED', 'CSRF token이 필요합니다.')
      }

      if (path === '/api/v1/auth/local/registrations') {
        return json(202, { verificationRequired: true })
      }
      if (path === '/api/v1/auth/local/email-verifications') {
        verificationAttempts += 1
        if (options.verificationFailure === 'transientOnce'
          && verificationAttempts === 1) {
          return error(
            503,
            'EMAIL_VERIFICATION_UNAVAILABLE',
            '잠시 후 같은 요청을 다시 시도해 주세요.',
          )
        }
        if (options.verificationFailure === 'invalid') {
          return error(
            400,
            'EMAIL_VERIFICATION_INVALID',
            '이메일 인증 정보가 올바르지 않거나 만료되었습니다.',
          )
        }
        return route.fulfill({ status: 204 })
      }
      if (path === '/api/v1/auth/local/session') {
        authenticated = true
        return route.fulfill({ status: 204 })
      }
      if (path === '/api/v1/auth/logout') {
        authenticated = false
        return route.fulfill({ status: 204 })
      }
    }

    return error(
      501,
      'UNEXPECTED_TEST_REQUEST',
      `예상하지 못한 요청: ${method} ${path}`,
    )
  })

  return { calls }
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

test('@smoke 설정된 로그인 공급자만 노출하고 local 로그인을 항상 유지한다', async ({ page }) => {
  const api = await installAuthApi(page, { providers: ['google'] })
  await page.goto('/login')
  await waitForCall(api.calls, 'GET', '/api/v1/auth/providers')

  await expect(page.getByRole('link', { name: 'Google로 계속하기' }))
    .toHaveAttribute('href', '/oauth2/authorization/google')
  await expect(page.getByRole('link', { name: 'Naver로 계속하기' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()
})

test('@smoke 공급자가 하나도 없으면 social 진입점을 숨기고 fail-closed 한다', async ({ page }) => {
  const api = await installAuthApi(page)
  await page.goto('/login')
  await waitForCall(api.calls, 'GET', '/api/v1/auth/providers')

  await expect(page.locator('.social-login')).toHaveCount(0)
  await expect(page.getByText('또는 이메일')).toHaveCount(0)
  await expect(page.getByLabel('이메일')).toBeVisible()
  await expect(page.getByLabel('비밀번호')).toBeVisible()
})

test('@smoke 자체 이메일 가입은 비밀번호 없이 JSON 등록 요청을 보낸다', async ({ page }) => {
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

test('@smoke 이메일 fragment를 먼저 제거하고 token과 새 비밀번호를 한 번만 검증한다', async ({ page }) => {
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

test('@smoke 일시적 이메일 검증 실패는 제거한 token과 비밀번호로 재시도한다', async ({ page }) => {
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

test('@smoke 유효하지 않은 이메일 token은 비밀번호 form을 닫고 새 메일을 안내한다', async ({ page }) => {
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

test('@smoke local 로그인과 로그아웃은 매번 CSRF를 받고 session 상태를 갱신한다', async ({ page }) => {
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
  await page.getByRole('button', { name: '로그아웃' }).click()

  await expect(page.getByRole('button', { name: '이메일로 로그인' })).toBeVisible()
  const logout = requiredCall(api.calls, 'POST', '/api/v1/auth/logout')
  expect(logout.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(callsFor(api.calls, 'GET', '/api/v1/auth/csrf').length).toBeGreaterThanOrEqual(2)
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([])
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
