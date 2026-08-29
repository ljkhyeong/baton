import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import {
  ACCESS_KEY,
  MEMBER_ONE_ID,
  MEMBER_TWO_ID,
  SEASON_ID,
  TEAM_ID,
  installApi,
  makeProjection,
  navigation,
  openSharedWorkspace,
} from './support/workspaceApiHarness'

const ACCOUNT_ID = '8e448211-66ae-44ab-9888-c4960648c22b'
const CSRF_HEADER_NAME = 'X-CSRF-TOKEN'
const CSRF_TOKEN = 'membership-csrf-token'
const CLAIMED_AT = '2026-08-08T12:34:56Z'

type MembershipCall = {
  body: unknown
  headers: Record<string, string>
  method: string
  path: string
  search: string
}

type MembershipApiOptions = {
  additiveResponseFields?: boolean
  authenticated?: boolean
  authSessionFailures?: number
  currentMembershipResponse?: unknown
  claimMembershipResponse?: unknown
}

async function installMembershipApi(
  page: Page,
  options: MembershipApiOptions = {},
) {
  const {
    authenticated = true,
    currentMembershipResponse,
    claimMembershipResponse,
  } = options
  let authSessionFailures = options.authSessionFailures ?? 0
  let claimedMemberId = ''
  const calls: MembershipCall[] = []
  const responseExtension = options.additiveResponseFields
    ? { futureServerField: 'ignored' }
    : {}

  const json = (route: Route, status: number, body: unknown) => route.fulfill({
    status,
    json: body,
  })

  await page.route('**/api/v1/auth/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') {
      if (authSessionFailures > 0) {
        authSessionFailures -= 1
        return json(route, 503, {
          code: 'AUTH_SESSION_TEMPORARILY_UNAVAILABLE',
          message: '로그인 상태를 잠시 확인할 수 없습니다.',
        })
      }
      return json(route, 200, authenticated
        ? {
            authenticated: true,
            accountId: ACCOUNT_ID,
            csrfHeaderName: CSRF_HEADER_NAME,
            csrfToken: CSRF_TOKEN,
          }
        : { authenticated: false })
    }
    if (request.method() === 'GET' && path === '/api/v1/auth/csrf') {
      return json(route, 200, {
        csrfHeaderName: CSRF_HEADER_NAME,
        csrfToken: CSRF_TOKEN,
      })
    }
    return json(route, 501, {
      code: 'UNEXPECTED_TEST_REQUEST',
      message: `예상하지 못한 인증 요청: ${request.method()} ${path}`,
    })
  })

  await page.route('**/api/v1/account-memberships/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    calls.push({
      body: request.postData() ? request.postDataJSON() : null,
      headers: request.headers(),
      method: request.method(),
      path: url.pathname,
      search: url.search,
    })
    if (request.method() === 'GET'
      && url.pathname === '/api/v1/account-memberships/current') {
      if (currentMembershipResponse !== undefined) {
        return json(route, 200, currentMembershipResponse)
      }
      return json(route, 200, claimedMemberId
        ? {
            claimed: true,
            accountId: ACCOUNT_ID,
            teamId: TEAM_ID,
            memberId: claimedMemberId,
            claimedAt: CLAIMED_AT,
            ...responseExtension,
          }
        : { claimed: false, ...responseExtension })
    }
    return json(route, 501, {
      code: 'UNEXPECTED_TEST_REQUEST',
      message: `예상하지 못한 membership 요청: ${request.method()} ${url.pathname}`,
    })
  })

  await page.route('**/api/v1/account-membership-claims', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const body = request.postDataJSON() as {
      teamId: string
      seasonId: string
      memberId: string
    }
    calls.push({
      body,
      headers: request.headers(),
      method: request.method(),
      path: url.pathname,
      search: url.search,
    })
    claimedMemberId = body.memberId
    return json(route, 200, claimMembershipResponse ?? {
      accountId: ACCOUNT_ID,
      teamId: TEAM_ID,
      memberId: claimedMemberId,
      claimedAt: CLAIMED_AT,
      ...responseExtension,
    })
  })

  return { calls }
}

test('@smoke 로그인 계정을 기존 구성원과 연결하고 새로고침 뒤 상태를 복구한다', async ({ page }, testInfo) => {
  await installApi(page)
  const membershipApi = await installMembershipApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(dialog.getByText('내 계정 연결', { exact: true })).toBeVisible()
  await dialog.getByLabel('연결할 구성원').selectOption(MEMBER_ONE_ID)
  page.once('dialog', async (confirmation) => {
    expect(confirmation.message()).toContain('연결 후에는 다른 구성원으로 바꿀 수 없습니다.')
    await confirmation.accept()
  })
  await dialog.getByRole('button', { name: '선택한 구성원과 연결' }).click()

  await expect(dialog.getByText('내 계정이 연결되어 있습니다.')).toBeVisible()
  await expect(dialog.getByText(/박민서 구성원으로 연결되었습니다/)).toBeVisible()

  const currentCall = membershipApi.calls.find(
    (call) => call.method === 'GET'
      && call.path === '/api/v1/account-memberships/current',
  )
  expect(new URLSearchParams(currentCall?.search).get('teamId')).toBe(TEAM_ID)
  expect(currentCall?.headers['x-baton-access-key']).toBe(ACCESS_KEY)

  const claimCall = membershipApi.calls.find(
    (call) => call.method === 'POST'
      && call.path === '/api/v1/account-membership-claims',
  )
  expect(claimCall?.headers['x-baton-access-key']).toBe(ACCESS_KEY)
  expect(claimCall?.headers[CSRF_HEADER_NAME.toLowerCase()]).toBe(CSRF_TOKEN)
  expect(claimCall?.body).toEqual({
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    memberId: MEMBER_ONE_ID,
  })

  await page.reload()
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()
  await expect(page.getByRole('dialog', { name: '구성원 관리' })
    .getByText('내 계정이 연결되어 있습니다.')).toBeVisible()
})

test('@smoke membership 응답의 additive field를 무시한다', async ({ page }, testInfo) => {
  await installApi(page)
  await installMembershipApi(page, { additiveResponseFields: true })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  let dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await dialog.getByLabel('연결할 구성원').selectOption(MEMBER_ONE_ID)
  page.once('dialog', async (confirmation) => confirmation.accept())
  await dialog.getByRole('button', { name: '선택한 구성원과 연결' }).click()
  await expect(dialog.getByText('내 계정이 연결되어 있습니다.')).toBeVisible()

  await page.reload()
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()
  dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(dialog.getByText('내 계정이 연결되어 있습니다.')).toBeVisible()
})

test('@smoke 익명 사용자는 접근 키를 URL에 복제하지 않는 로그인 복귀 경로를 받는다', async ({ page }, testInfo) => {
  await installApi(page)
  await installMembershipApi(page, { authenticated: false })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  const loginLink = page.getByRole('link', { name: '로그인하고 연결하기' })
  const href = await loginLink.getAttribute('href')
  expect(href).toContain('/login?returnTo=')
  expect(href).toContain(encodeURIComponent(`/teams/${TEAM_ID}/seasons/${SEASON_ID}`))
  expect(href).not.toContain('accessKey')
})

test('@smoke 로그인 상태 조회 실패를 익명으로 추측하지 않고 재시도한다', async ({ page }, testInfo) => {
  await installApi(page)
  await installMembershipApi(page, { authSessionFailures: 1 })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(dialog.getByText(/로그인 상태를 확인하지 못했습니다/)).toBeVisible()
  await expect(dialog.getByRole('link', { name: '로그인하고 연결하기' })).toHaveCount(0)
  await dialog.getByRole('button', { name: '로그인 상태 다시 확인' }).click()
  await expect(dialog.getByLabel('연결할 구성원')).toBeVisible()
})

test('@smoke 다른 계정 범위의 current membership 응답은 연결 상태로 캐시하지 않는다', async ({ page }, testInfo) => {
  await installApi(page)
  await installMembershipApi(page, {
    currentMembershipResponse: {
      claimed: true,
      accountId: '9e448211-66ae-44ab-9888-c4960648c22b',
      teamId: TEAM_ID,
      memberId: MEMBER_ONE_ID,
      claimedAt: CLAIMED_AT,
    },
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(dialog.getByText(/서버 응답을 확인할 수 없습니다/)).toBeVisible()
  await expect(dialog.getByText('내 계정이 연결되어 있습니다.')).toHaveCount(0)
})

test('@smoke 활동 종료된 선택값은 남은 활동 구성원으로 보정해 claim한다', async ({ page }, testInfo) => {
  await installApi(page)
  const membershipApi = await installMembershipApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  const memberSelect = dialog.getByLabel('연결할 구성원')
  await memberSelect.selectOption(MEMBER_ONE_ID)
  await dialog.getByRole('button', { name: '박민서 활동 종료' }).click()
  await expect(memberSelect).toHaveValue(MEMBER_TWO_ID)

  page.once('dialog', async (confirmation) => confirmation.accept())
  await dialog.getByRole('button', { name: '선택한 구성원과 연결' }).click()
  await expect(dialog.getByText('내 계정이 연결되어 있습니다.')).toBeVisible()

  const claimCall = membershipApi.calls.find(
    (call) => call.method === 'POST'
      && call.path === '/api/v1/account-membership-claims',
  )
  expect(claimCall?.body).toEqual({
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    memberId: MEMBER_TWO_ID,
  })
})

test('@smoke 종료 시즌에서도 연결 이력 진입을 열고 새 claim만 막는다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  const endedAt = '2026-08-08T12:00:00Z'
  projection.season.endedAt = endedAt
  projection.seasons[0].endedAt = endedAt
  await installApi(page, projection)
  const membershipApi = await installMembershipApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  const manageMembers = page.getByRole('button', { name: '구성원 관리' })
  await expect(manageMembers).toBeEnabled()
  await manageMembers.click()
  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(dialog.getByText(/종료된 시즌은 읽기 전용입니다/)).toBeVisible()
  await expect(dialog.getByLabel('연결할 구성원')).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '선택한 구성원과 연결' })).toBeDisabled()
  expect(membershipApi.calls.some((call) =>
    call.method === 'GET'
      && call.path === '/api/v1/account-memberships/current')).toBe(true)
})

test('@smoke claim 응답의 구성원 범위가 다르면 연결 cache를 갱신하지 않는다', async ({ page }, testInfo) => {
  await installApi(page)
  await installMembershipApi(page, {
    claimMembershipResponse: {
      accountId: ACCOUNT_ID,
      teamId: TEAM_ID,
      memberId: MEMBER_TWO_ID,
      claimedAt: CLAIMED_AT,
    },
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name)
    .getByRole('button', { name: '역할' })
    .click()
  await page.getByRole('button', { name: '구성원 관리' }).click()

  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await dialog.getByLabel('연결할 구성원').selectOption(MEMBER_ONE_ID)
  page.once('dialog', async (confirmation) => confirmation.accept())
  await dialog.getByRole('button', { name: '선택한 구성원과 연결' }).click()

  await expect(dialog.getByText(/서버 응답을 확인할 수 없습니다/)).toBeVisible()
  await expect(dialog.getByText('내 계정이 연결되어 있습니다.')).toHaveCount(0)
})
