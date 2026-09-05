import { expect, test, type Page } from '@playwright/test'
import { TEAM_ID, SEASON_ID, MEMBER_ONE_ID, MEMBER_TWO_ID, WORKSPACE_PATH, SCOPE_PATH,
  installApi, makeProjection, navigation, openSharedWorkspace, recordedCall } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'
const TOKEN = 'a'.repeat(43)
const INVITATION = '00000000-0000-4000-8000-000000000099'
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

test('@operations @webkit 관리자가 계정 권한 전환 후 초대를 만들고 취소한다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  await installApi(page, projection)
  await page.route(`**${SCOPE_PATH}/workspace`, route => route.fulfill({ json: projection }))
  await accountApi(page)
  const state = {
    teamId: TEAM_ID, accountId: ACCOUNT, accountAccessEnabled: false, memberId: MEMBER_ONE_ID,
    permission: null as 'ADMIN' | null,
    members: projection.members.map(member => ({ memberId: member.id, memberName: member.name, active: true,
      accountId: member.id === MEMBER_ONE_ID ? ACCOUNT : null, permission: null as 'ADMIN' | null })),
    invitations: [] as { id: string; memberId: string; permission: 'VIEWER'; createdAt: string; expiresAt: string; acceptedAt: null; revokedAt: string | null }[], audit: [],
  }
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
    } else if (request.method() === 'POST' && path.endsWith('/invitations')) {
      expect(request.postDataJSON()).toMatchObject({ memberId: MEMBER_TWO_ID, permission: 'VIEWER' })
      const invitation = { id: INVITATION, memberId: MEMBER_TWO_ID, permission: 'VIEWER' as const,
        createdAt: instant, expiresAt, acceptedAt: null, revokedAt: null }
      state.invitations.push(invitation)
      return route.fulfill({ json: { invitation, token: TOKEN } })
    } else if (path.endsWith('/revoke')) state.invitations[0]!.revokedAt = instant
    return route.fulfill({ json: state })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할', exact: true }).click()
  await page.getByRole('button', { name: '구성원 관리', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '구성원 관리' })
  await dialog.getByText('팀 초대·권한 관리', { exact: true }).click()
  await dialog.getByLabel('운영자 복구 키').fill('operator-recovery-key')
  await dialog.getByRole('checkbox', { name: '공유 링크 접근 종료와 현재 계정의 관리자 지정을 확인했습니다.' }).check()
  await dialog.getByRole('button', { name: '관리자 지정 후 계정 권한으로 전환' }).click()
  await dialog.getByLabel('초대할 구성원').selectOption(MEMBER_TWO_ID)
  await dialog.getByLabel('초대 권한').selectOption('VIEWER')
  await dialog.getByRole('button', { name: '7일 유효 초대 링크 만들기' }).click()
  await expect(dialog.getByLabel('생성한 초대 링크')).toHaveValue(new RegExp(`/join#invite=${TOKEN}$`))
  await dialog.getByRole('button', { name: '초대 취소', exact: true }).click()
  await expect(dialog.getByLabel('생성한 초대 링크')).toHaveCount(0)
  await expect(dialog.getByText('초대 취소', { exact: true })).toBeVisible()
})

test('@operations @webkit 초대 수락 후 공유 키 없이 접속한 열람자는 기록을 읽고 변경할 수 없다', async ({ page }, testInfo) => {
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
  await expect(page.getByRole('heading', { name: projection.team.name })).toBeVisible()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
  await expect(page.getByRole('button', { name: '결정 남기기' })).toBeDisabled()
  expect((await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`)).headers['x-baton-access-key'] ?? '').toBe('')
  expect(await page.evaluate(() => sessionStorage.getItem('baton:team-invitation:v1'))).toBeNull()
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
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('계정 권한으로 기록한다')
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
