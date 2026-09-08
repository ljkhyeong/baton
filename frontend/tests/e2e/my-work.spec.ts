import { expect, test } from '@playwright/test'
import { TEAM_ID, SEASON_ID, MEMBER_ONE_ID, makeProjection, installApi, navigation } from './support/workspaceApiHarness'

const ACCOUNT = '00000000-0000-4000-8000-000000000901'
const OTHER_TEAM = '00000000-0000-4000-8000-000000000902'
const OTHER_SEASON = '00000000-0000-4000-8000-000000000903'
const ACTIVE_SEASON = '00000000-0000-4000-8000-000000000904'
const ENDED_SEASON = '00000000-0000-4000-8000-000000000905'

test('@smoke @responsive 여러 팀의 업무를 모아 원본 회차로 이동하고 접근이 거부된 팀은 제외한다', async ({ page }, testInfo) => {
  await page.clock.install()
  const current = makeProjection()
  current.team.accountAccessEnabled = true; current.team.permission = 'MEMBER'
  const other = structuredClone(current)
  other.team = { ...other.team, id: OTHER_TEAM, name: '두 번째 운영 팀' }
  other.season = { ...other.season, id: OTHER_SEASON, name: '가을 시즌' }
  other.seasons = [other.season]
  other.rounds[0]!.routineExecutions[1]!.title = '두 번째 팀 회고 정리'
  const active = structuredClone(current)
  active.season = { ...active.season, id: ACTIVE_SEASON, name: '추가 진행 시즌' }
  const ended = { ...current.season, id: ENDED_SEASON, name: '종료 시즌', endedAt: '2026-09-01T00:00:00Z' }
  current.seasons = [current.season, active.season, ended]
  active.seasons = current.seasons
  active.rounds[0]!.routineExecutions[1]!.title = '다른 진행 시즌의 회고'
  const api = await installApi(page, current)
  await page.route(`**/teams/${TEAM_ID}/seasons/${ACTIVE_SEASON}/workspace`, route => route.fulfill({ json: active }))
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true, accountId: ACCOUNT, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'csrf',
  } }))
  await page.route('**/api/v1/team-access/mine', route => route.fulfill({ json: { accountId: ACCOUNT,
    teams: [current, other].map(workspace => ({ teamId: workspace.team.id, teamName: workspace.team.name,
      memberId: MEMBER_ONE_ID, memberName: '박민서', permission: 'MEMBER', seasonId: workspace.season.id,
      seasonName: workspace.season.name, seasonEnded: false })),
  } }))
  let allowed = true
  let otherRequests = 0
  await page.route(`**/teams/${OTHER_TEAM}/seasons/${OTHER_SEASON}/workspace`, route => {
    otherRequests++
    return route.fulfill(allowed ? { json: other }
      : { status: 403, json: { code: 'WORKSPACE_ACCESS_DENIED', message: '권한이 회수되었습니다.' } })
  })
  await page.goto('/my-teams')
  const work = page.getByRole('region', { name: '모든 팀의 내 할 일' })
  const task = work.getByRole('link', { name: /두 번째 팀 회고 정리/ })
  await expect(task).toBeVisible()
  await expect(work.getByRole('link', { name: /다른 진행 시즌의 회고/ })).toBeVisible()
  expect(api.calls.some(call => call.path.includes(ENDED_SEASON))).toBe(false)
  await expect(task).toHaveAttribute('href', new RegExp(`/teams/${OTHER_TEAM}/seasons/${OTHER_SEASON}\\?workKind=execution`))
  const first = work.getByRole('link', { name: /알고리즘 한 바퀴/ }).first()
  await first.click()
  await expect(page).toHaveURL(new RegExp(`/teams/${TEAM_ID}/seasons/${SEASON_ID}\\?workKind=execution`))
  await expect(navigation(page, testInfo.project.name).getByRole('button', { name: '일정', exact: true })).toHaveAttribute('aria-current', 'page')
  await page.getByRole('link', { name: '내 팀', exact: true }).filter({ visible: true }).click()
  await expect(task).toBeVisible()
  allowed = false
  await work.getByRole('button', { name: '업무 새로고침' }).click()
  await expect(work.getByRole('alert')).toContainText('일부 팀이나 시즌의 업무가 빠져 있습니다')
  await expect(task).toHaveCount(0)
  await expect(work.getByRole('link', { name: /알고리즘 한 바퀴/ }).first()).toBeVisible()
  const blockedRequests = otherRequests
  await page.evaluate(() => {
    window.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))
  })
  await page.clock.runFor(61_000)
  expect(otherRequests).toBe(blockedRequests)
  allowed = true
  await work.getByRole('button', { name: '업무 새로고침' }).click()
  await expect(task).toBeVisible()
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', await page.locator('body').evaluate(el => el.clientWidth))
})
