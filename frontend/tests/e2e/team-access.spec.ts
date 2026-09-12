import { expect, test, type Page } from '@playwright/test'
import { TEAM_ID, SEASON_ID, MEMBER_ONE_ID, MEMBER_TWO_ID, WORKSPACE_PATH, SCOPE_PATH,
  installApi, makeProjection, navigation, openSharedWorkspace, recordedCall } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'
const OTHER_ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22c'
const TOKEN = 'a'.repeat(43)
const INVITATION = '00000000-0000-4000-8000-000000000099'
const SECOND_INVITATION = '00000000-0000-4000-8000-000000000100'
const instant = '2026-09-05T03:00:00Z'
const expiresAt = '2026-09-12T03:00:00Z'
async function accountApi(page: Page) {
  await page.route('**/api/v1/auth/**', route => route.fulfill({ json: new URL(route.request().url()).pathname.endsWith('/csrf')
    ? { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'team-access-csrf' }
    : { authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'team-access-csrf' } }))
  await page.route('**/api/v1/account-memberships/current**', route => route.fulfill({ json: {
    claimed: true, accountId: ACCOUNT, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: instant,
  } }))
  await page.route('**/api/v1/teams/*/seasons/*/notifications', route => route.fulfill({ json: {
    accountId: ACCOUNT, teamId: TEAM_ID, seasonId: SEASON_ID, memberId: MEMBER_ONE_ID, unreadCount: 0, items: [],
  } }))
}

test('@operations @webkit 관리자가 초대를 만들고 취소하며 열어 둔 목록에서 만료 상태를 확인한다', async ({ page }, testInfo) => {
  test.slow(testInfo.project.name === 'webkit', '초대 생성·취소·재시도·만료를 한 흐름에서 확인한다')
  await page.clock.install({ time: new Date('2026-09-12T02:00:00Z') })
  const projection = makeProjection()
  await installApi(page, projection)
  await page.route(`**${SCOPE_PATH}/workspace`, route => route.fulfill({ json: projection }))
  await accountApi(page)
  const state = {
    teamId: TEAM_ID, accountId: ACCOUNT, accountAccessEnabled: false, memberId: MEMBER_ONE_ID,
    permission: null as 'ADMIN' | null,
    members: projection.members.map(member => ({ memberId: member.id, memberName: member.name, active: true,
      accountId: member.id === MEMBER_ONE_ID ? ACCOUNT : OTHER_ACCOUNT,
      permission: null as 'ADMIN' | 'MEMBER' | 'VIEWER' | null })),
    invitations: [] as { id: string; memberId: string; permission: 'VIEWER'; createdAt: string; expiresAt: string; acceptedAt: null; revokedAt: string | null }[], audit: [],
  }
  let revokeRequestCount = 0
  await page.route('**/api/v1/team-access/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      expect(request.headers()['x-csrf-token']).toBe('team-access-csrf')
      expect(request.postDataJSON().expectedAccountId).toBe(ACCOUNT)
    }
    if (path.endsWith('/activate')) {
      expect(request.headers()['x-baton-recovery-key']).toBe('operator-recovery-key')
      state.accountAccessEnabled = true
      state.permission = 'ADMIN'
      state.members[0]!.permission = 'ADMIN'
      projection.team.accountAccessEnabled = true
      projection.team.permission = 'ADMIN'
    } else if (request.method() === 'PUT' && path.endsWith(`/members/${MEMBER_TWO_ID}/permission`)) {
      state.members[1]!.permission = request.postDataJSON().permission
    } else if (request.method() === 'POST' && path.endsWith('/invitations')) {
      expect(request.postDataJSON()).toMatchObject({ memberId: MEMBER_TWO_ID, permission: 'VIEWER' })
      const invitation = { id: state.invitations.length === 0 ? INVITATION : SECOND_INVITATION,
        memberId: MEMBER_TWO_ID, permission: 'VIEWER' as const,
        createdAt: instant, expiresAt, acceptedAt: null, revokedAt: null }
      state.invitations.push(invitation)
      return route.fulfill({ json: { invitation, token: TOKEN } })
    } else if (path.endsWith('/revoke')) {
      revokeRequestCount += 1
      if (revokeRequestCount === 1) return route.fulfill({ status: 503, json: {
        code: 'TEAM_INVITATION_REVOKE_FAILED', message: '초대 취소에 실패했습니다.',
      } })
      state.invitations[0]!.revokedAt = instant
    }
    return route.fulfill({ json: state })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할', exact: true }).click()
  await page.getByRole('button', { name: '구성원 관리', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await dialog.getByText('팀 초대·권한 관리', { exact: true }).click()
  await dialog.getByLabel('운영자 복구 키').fill('operator-recovery-key')
  await dialog.getByRole('checkbox', { name: '기존 공유 링크를 막고 내 계정을 관리자로 지정하는 데 동의합니다.' }).check()
  await dialog.getByRole('button', { name: '계정 로그인으로 전환' }).click()
  const invitationMember = dialog.getByLabel('초대할 구성원')
  const invitationButton = dialog.getByRole('button', { name: '초대 링크 만들기' })
  const memberPermission = dialog.getByLabel('김준호 접근 권한')
  await invitationMember.selectOption(MEMBER_TWO_ID)
  page.once('dialog', confirmation => confirmation.accept())
  await memberPermission.selectOption('VIEWER')
  await expect(memberPermission).toHaveValue('VIEWER')
  await expect(invitationMember).toHaveValue('')
  await expect(invitationButton).toBeDisabled()
  page.once('dialog', confirmation => confirmation.accept())
  await memberPermission.selectOption('')
  await expect(memberPermission).toHaveValue('')
  await invitationMember.selectOption(MEMBER_TWO_ID)
  await dialog.getByLabel('초대 권한').selectOption('VIEWER')
  await invitationButton.click()
  await expect(dialog.getByLabel('생성한 초대 링크')).toHaveValue(new RegExp(`/join#invite=${TOKEN}$`))
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: async () => { throw new DOMException('복사 권한 없음', 'NotAllowedError') } } }))
  await dialog.getByRole('button', { name: '초대 링크 복사' }).click()
  await expect(dialog.getByRole('alert')).toContainText('위 링크를 선택해 직접 복사해 주세요.')
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: async () => undefined } }))
  await dialog.getByRole('button', { name: '초대 링크 복사' }).click()
  await expect(dialog.getByRole('status')).toHaveText('초대 링크를 복사했습니다.')
  await expect(dialog.getByText('위 링크를 선택해 직접 복사해 주세요.', { exact: false })).toHaveCount(0)
  page.once('dialog', async confirmation => {
    expect(confirmation.message()).toBe('김준호님의 초대를 취소할까요? 이 초대 링크는 즉시 사용할 수 없게 됩니다.')
    await confirmation.dismiss()
  })
  await dialog.getByRole('button', { name: '초대 취소', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '초대 취소', exact: true })).toBeVisible()
  expect(revokeRequestCount).toBe(0)
  page.once('dialog', confirmation => confirmation.accept())
  await dialog.getByRole('button', { name: '초대 취소', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('초대 취소에 실패했습니다.')
  await expect(dialog.getByLabel('생성한 초대 링크')).toHaveValue(new RegExp(`/join#invite=${TOKEN}$`))
  expect(revokeRequestCount).toBe(1)
  page.once('dialog', confirmation => confirmation.accept())
  await dialog.getByRole('button', { name: '초대 취소', exact: true }).click()
  await expect(dialog.getByLabel('생성한 초대 링크')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '초대 취소', exact: true })).toHaveCount(0)
  await expect(dialog.getByText('초대 취소', { exact: true })).toBeVisible()
  expect(revokeRequestCount).toBe(2)
  await invitationButton.click()
  await expect(dialog.getByRole('button', { name: '초대 취소', exact: true })).toBeVisible()
  await page.clock.fastForward('02:00:00')
  await expect(dialog.getByText('기간 만료', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '초대 취소', exact: true })).toHaveCount(0)
  await expect(dialog.getByLabel('생성한 초대 링크')).toHaveCount(0)
})

test('@operations @webkit 초대 수락 후 공유 키 없이 접속한 열람자는 기록을 읽고 변경할 수 없다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'share', { configurable: true, value: async (data: ShareData) => {
      document.documentElement.dataset.sharedWorkspace = JSON.stringify(data)
    } })
  })
  const projection = makeProjection()
  projection.team.accountAccessEnabled = true
  projection.team.permission = 'VIEWER'
  const api = await installApi(page, projection)
  await accountApi(page)
  await page.route('**/api/v1/team-invitations/**', route => {
    const request = route.request()
    expect(request.headers()['x-csrf-token']).toBe('team-access-csrf')
    expect(request.postDataJSON()).toEqual({ expectedAccountId: ACCOUNT, token: TOKEN })
    return route.fulfill({ json: request.url().endsWith('/preview')
      ? { teamId: TEAM_ID, teamName: projection.team.name, memberId: MEMBER_ONE_ID, memberName: '박민서', permission: 'VIEWER', expiresAt }
      : { accountId: ACCOUNT, teamId: TEAM_ID, seasonId: SEASON_ID, memberId: MEMBER_ONE_ID, permission: 'VIEWER' } })
  })
  await page.goto(`/join#invite=${TOKEN}`)
  await expect(page.getByText('박민서 구성원으로 참여합니다. 권한은 열람자입니다.')).toBeVisible()
  await expect(page).toHaveURL(/\/join$/)
  await page.getByRole('button', { name: '이 계정으로 초대 수락' }).click()
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
  await expect(page.locator('.workspace-switcher')).toContainText(projection.team.name)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
  await expect(page.getByRole('button', { name: '결정 남기기' })).toBeDisabled()
  expect((await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`)).headers['x-baton-access-key'] ?? '').toBe('')
  expect(await page.evaluate(() => sessionStorage.getItem('baton:team-invitation:v1'))).toBeNull()
  const chrome = testInfo.project.name === 'mobile' ? page.locator('.mobile-topbar') : page.locator('.sidebar')
  await chrome.getByRole('button', { name: '공유', exact: true }).click()
  const shared = await page.evaluate(() => JSON.parse(document.documentElement.dataset.sharedWorkspace ?? 'null'))
  expect(shared).toEqual({ url: new URL(WORKSPACE_PATH, page.url()).href })
})

test('@memory @webkit 구성원은 공유 키 없이 현재 계정과 CSRF를 확인한 뒤 결정을 남긴다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.team.accountAccessEnabled = true
  projection.team.permission = 'MEMBER'
  const api = await installApi(page, projection)
  await accountApi(page)
  await page.goto(WORKSPACE_PATH)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()
  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 결정했나요?').fill('계정 권한으로 기록한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('초대받은 구성원이 같은 팀에서 기록을 이어 갑니다.')
  await dialog.getByLabel('작성자').selectOption(MEMBER_ONE_ID)
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()
  await expect(page.getByRole('heading', { name: '계정 권한으로 기록한다' })).toBeVisible()
  const call = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  expect(call.headers['x-baton-account-id']).toBe(ACCOUNT)
  expect(call.headers['x-csrf-token']).toBe('team-access-csrf')
})

test('@operations @webkit 같은 탭에서 다른 초대를 열면 이전 초대의 수락 대상을 유지하지 않는다', async ({ page }) => {
  await installApi(page)
  await accountApi(page)
  await page.route('**/api/v1/team-invitations/preview', route => route.fulfill({ json: {
    teamId: TEAM_ID, teamName: route.request().postDataJSON().token === TOKEN ? '첫 번째 팀' : '두 번째 팀',
    memberId: MEMBER_ONE_ID, memberName: '박민서', permission: 'VIEWER', expiresAt,
  } }))
  await page.goto(`/join#invite=${TOKEN}`)
  await expect(page.getByRole('heading', { name: '첫 번째 팀' })).toBeVisible()
  await page.goto(`/join#invite=${'b'.repeat(43)}`)
  await expect(page.getByRole('heading', { name: '두 번째 팀' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '첫 번째 팀' })).toHaveCount(0)
})

test('@operations @webkit 사용할 수 없는 저장 초대를 지우고 내 팀으로 돌아간다', async ({ page }) => {
  await installApi(page)
  await accountApi(page)
  await page.addInitScript(token => window.sessionStorage.setItem('baton:team-invitation:v1', token), TOKEN)
  await page.route('**/api/v1/team-invitations/preview', route => route.fulfill({ status: 404, json: {
    code: 'TEAM_INVITATION_NOT_FOUND', message: '사용할 수 있는 초대가 없습니다.',
  } }))
  await page.goto('/join')
  await expect(page.getByRole('alert')).toContainText('사용할 수 있는 초대가 없습니다.')
  await page.getByRole('button', { name: '이 초대 지우기' }).click()
  await expect(page).toHaveURL('/my-teams')
  expect(await page.evaluate(() => sessionStorage.getItem('baton:team-invitation:v1'))).toBeNull()
})
