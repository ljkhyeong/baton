import { expect, test } from '@playwright/test'
import type { CreateWorkspaceRequest } from '../../src/features/workspace/types'
import { installApi, pendingCreationEntries } from './support/workspaceApiHarness'

test('시작 템플릿 선택과 응답 유실 복구가 같은 생성 요청을 유지한다 @smoke @responsive', async ({ page }) => {
  const api = await installApi(page)
  api.commitNextWorkspaceCreationThenTimeout()
  await page.goto('/')
  await expect(page.getByRole('combobox', { name: '시작 구성', exact: true })).toHaveValue('')
  await page.getByRole('combobox', { name: '시작 구성', exact: true }).selectOption('STUDY_V1')
  await expect(page.getByRole('region', { name: '시작 구성 미리보기' })).toContainText('학습 자료 준비')
  await page.getByLabel('팀 이름').fill('템플릿 복구 스터디')
  await page.getByLabel('시즌 이름').fill('2026 가을')
  await page.getByLabel('시작일').fill('2026-09-01')
  await page.getByLabel('종료일').fill('2026-11-30')
  await page.getByLabel('구성원 이름').fill('박민서\n김준호')
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page.getByRole('alert')).toContainText('작업 공간 생성 응답을 확인하지 못했습니다.')
  const pending = await pendingCreationEntries(page)
  expect(pending).toHaveLength(1)
  expect(JSON.parse(pending[0]!.normalizedPayload).template).toBe('STUDY_V1')

  await page.reload()
  await page.locator('.pending-workspaces summary').click()
  const recovery = page.getByRole('region', { name: '확인되지 않은 작업 공간 생성 요청' })
  await expect(recovery).toContainText('스터디 기본 구성')
  await recovery.getByRole('button', { name: '템플릿 복구 스터디 2026 가을 저장된 입력 불러오기' }).click()
  await expect(page.getByRole('combobox', { name: '시작 구성', exact: true })).toHaveValue('STUDY_V1')
  await page.getByRole('button', { name: '같은 생성 결과 확인하기' }).click()
  await expect(page).toHaveURL(/\/teams\/[^/]+\/seasons\/[^/]+$/)

  const attempts = api.calls.filter(call => call.method === 'POST' && call.path === '/api/v1/workspaces')
  expect(attempts).toHaveLength(2)
  expect(attempts[1]!.headers['idempotency-key']).toBe(attempts[0]!.headers['idempotency-key'])
  expect((attempts[1]!.body as CreateWorkspaceRequest).template).toBe('STUDY_V1')
  await expect.poll(async () => (await pendingCreationEntries(page)).length).toBe(0)
})
