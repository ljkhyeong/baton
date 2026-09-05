import { expect, test } from '@playwright/test'
import { TEAM_ID, ACCESS_KEY, makeProjection, installApi, openSharedWorkspace, navigation } from './support/workspaceApiHarness'

const OTHER_SEASON_ID = '00000000-0000-4000-8000-000000000602'
const OTHER_ROLE_ID = '00000000-0000-4000-8000-000000000603'
const OTHER_DECISION_ID = '00000000-0000-4000-8000-000000000604'
function sources() {
  const current = makeProjection()
  const other = makeProjection()
  other.season = { ...other.season, id: OTHER_SEASON_ID, name: '이전 운영 시즌', timeZone: 'UTC', endedAt: '2026-09-18T00:00:00Z' }
  current.seasons = [current.season, other.season]
  other.seasons = current.seasons
  other.roles = [{ ...other.roles[0]!, id: OTHER_ROLE_ID }]
  other.routines = []; other.rounds = []; other.handoffItems = []; other.resources = []; other.roleHandoffs = []
  other.continuitySignals = []
  other.decisions = [{ ...other.decisions[0]!, id: OTHER_DECISION_ID, title: '지난 시즌의 문제 선정 기준',
    roleIds: [OTHER_ROLE_ID], createdAt: '2026-07-03T15:30:00Z' }]
  return { current, other }
}

test('@records @responsive 모든 시즌 검색은 요청 시 조회하고 각 시즌의 날짜로 좁혀 원본을 연다', async ({ page }, testInfo) => {
  const { current, other } = sources()
  await installApi(page, current)
  let requests = 0
  await page.route(`**/api/v1/teams/${TEAM_ID}/seasons/${OTHER_SEASON_ID}/workspace`, route => {
    requests++
    expect(route.request().headers()['x-baton-access-key']).toBe(ACCESS_KEY)
    return route.fulfill({ json: other })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  expect(requests).toBe(0)
  await page.getByLabel('검색할 시즌').selectOption('all')
  const card = page.locator('.record-search-card').filter({ hasText: '지난 시즌의 문제 선정 기준' })
  await expect(card).toContainText('이전 운영 시즌')
  await page.getByLabel('무엇을 다시 찾고 있나요?').fill('지난 시즌')
  await page.getByLabel('시작일', { exact: true }).fill('2026-07-03')
  await page.getByLabel('종료일', { exact: true }).fill('2026-07-03')
  await expect(card).toBeVisible()
  await page.getByRole('combobox', { name: '관련 역할', exact: true }).selectOption(OTHER_ROLE_ID)
  await expect(card).toBeVisible()
  const source = card.getByRole('link', { name: '지난 시즌의 문제 선정 기준 원본 시즌 새 창에서 보기' })
  await expect(source).toHaveAttribute('target', '_blank')
  await page.goto((await source.getAttribute('href'))!)
  await expect(page.locator(`[data-decision-id="${OTHER_DECISION_ID}"]`)).toBeVisible()
  await expect(navigation(page, testInfo.project.name).getByRole('button', { name: '기록' })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByText('이 시즌은 읽기 전용입니다.')).toBeVisible()
})

test('@records 다른 시즌 응답의 소속 오류와 접근 거부는 검색 결과에서 제외하고 재시도한다', async ({ page }, testInfo) => {
  const { current, other } = sources()
  await installApi(page, current)
  let valid = false
  await page.route(`**/api/v1/teams/${TEAM_ID}/seasons/${OTHER_SEASON_ID}/workspace`, route => route.fulfill({ json: valid ? other : current }))
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  await page.getByLabel('검색할 시즌').selectOption('all')
  await expect(page.getByText('이전 운영 시즌 시즌을 불러오지 못했습니다. 검색 결과에서 제외했습니다.')).toBeVisible()
  await expect(page.getByRole('heading', { name: /불러온 시즌에서/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: '지난 시즌의 문제 선정 기준' })).toHaveCount(0)
  valid = true
  await page.getByRole('button', { name: '누락된 시즌 다시 불러오기' }).click()
  await expect(page.getByRole('heading', { name: '지난 시즌의 문제 선정 기준' })).toBeVisible()
  await page.route(`**/api/v1/teams/${TEAM_ID}/seasons/${OTHER_SEASON_ID}/workspace`, route => route.fulfill({ status: 403,
    json: { code: 'WORKSPACE_ACCESS_DENIED', message: '접근 권한이 없습니다.' } }))
  await page.getByLabel('검색할 시즌').selectOption('current')
  await page.getByLabel('검색할 시즌').selectOption('all')
  await expect(page.getByRole('button', { name: '누락된 시즌 다시 불러오기' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '지난 시즌의 문제 선정 기준' })).toHaveCount(0)
})
