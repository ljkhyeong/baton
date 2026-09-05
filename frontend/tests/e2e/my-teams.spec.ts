import { expect, test } from '@playwright/test'
import { TEAM_ID, SEASON_ID, MEMBER_ONE_ID, WORKSPACE_PATH, SCOPE_PATH,
  installApi, makeProjection } from './support/workspaceApiHarness'

const ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22b'
const OTHER_ACCOUNT = '8e448211-66ae-44ab-9888-c4960648c22c'

test('@smoke @responsive 내 팀에서 공유 키 없이 이동하고 계정을 바꾸면 이전 팀을 숨긴다', async ({ page }) => {
  const projection = makeProjection()
  projection.team.accountAccessEnabled = true
  projection.team.permission = 'MEMBER'
  await installApi(page, projection)
  let accountId = ACCOUNT
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true, accountId, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'csrf',
  } }))
  await page.route('**/api/v1/team-access/mine', route => route.fulfill({ json: { accountId,
    teams: accountId === ACCOUNT ? [{ teamId: TEAM_ID, teamName: projection.team.name, memberId: MEMBER_ONE_ID,
      memberName: '민서', permission: 'MEMBER', seasonId: SEASON_ID, seasonName: projection.season.name, seasonEnded: false }] : [],
  } }))
  await page.route(`**${SCOPE_PATH}/workspace`, route => route.fulfill({ json: projection }))
  await page.goto('/my-teams')
  const link = page.getByRole('link', { name: `${projection.team.name} 열기` })
  await expect(link).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', await page.locator('body').evaluate(el => el.clientWidth))
  await link.click()
  await expect(page).toHaveURL(WORKSPACE_PATH)
  await expect(page.getByRole('button', { name: '오늘', exact: true }).filter({ visible: true })).toBeVisible()
  accountId = OTHER_ACCOUNT
  await page.getByRole('link', { name: '내 팀', exact: true }).filter({ visible: true }).click()
  await expect(page.getByText('계정으로 접근할 수 있는 팀이 없습니다.', { exact: false })).toBeVisible()
  await expect(page.getByRole('link', { name: `${projection.team.name} 열기` })).toHaveCount(0)
})

test('@smoke 내 팀은 로그인 후 돌아올 경로를 유지하고 다른 계정의 응답을 거부한다', async ({ page }) => {
  let loggedIn = false
  await page.route('**/api/v1/auth/**', route => route.fulfill({ json: {
    authenticated: loggedIn, ...(loggedIn ? { accountId: ACCOUNT } : {}), csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'csrf',
  } }))
  await page.goto('/my-teams')
  await expect(page).toHaveURL('/login?returnTo=%2Fmy-teams')
  loggedIn = true
  await page.route('**/api/v1/team-access/mine', route => route.fulfill({ json: { accountId: OTHER_ACCOUNT, teams: [] } }))
  await page.goto('/my-teams')
  await expect(page.getByRole('alert')).toContainText('서버 응답을 확인할 수 없습니다')
})
