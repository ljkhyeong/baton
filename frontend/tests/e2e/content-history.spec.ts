import { expect, test } from '@playwright/test'
import { TEAM_ID, SEASON_ID, DECISION_ID, SCOPE_PATH,
  installApi, makeProjection, navigation, openSharedWorkspace } from './support/workspaceApiHarness'

test('@memory @responsive 수정 이력은 결정 변경 뒤 갱신되고 보관함에서도 조회된다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  const original = projection.decisions[0]!.reason
  const changed = '충분한 토론 시간을 확보합니다. <img src=x onerror=alert(1)>'
  const api = await installApi(page, projection)
  await page.route(`**${SCOPE_PATH}/decisions/${DECISION_ID}/changes`, route => route.fulfill({ json: {
    teamId: TEAM_ID, seasonId: SEASON_ID, recordKind: 'DECISION', recordId: DECISION_ID,
    changes: api.projection().decisions[0]!.reason === original ? [] : [{ id: '00000000-0000-4000-8000-000000000099',
      actorAccountId: null, actorName: '공유 키 사용자', changedAt: '2026-09-05T03:00:00Z',
      fields: [{ fieldName: '이유', beforeValue: original, afterValue: changed }] }],
  } }))
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
  const entry = page.locator(`[data-decision-id="${DECISION_ID}"]`)
  await entry.getByText('수정 이력', { exact: true }).click()
  await expect(entry.getByText('아직 남겨진 수정 이력이 없습니다.')).toBeVisible()
  await entry.getByRole('button', { name: /수정$/ }).click()
  const dialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  await dialog.getByLabel('왜 이 선택을 했나요?').fill(changed)
  await dialog.getByRole('button', { name: '변경 저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const history = entry.locator('.content-history')
  await expect(history.getByText(original, { exact: true })).toBeVisible()
  await expect(history.getByText(changed, { exact: true })).toBeVisible()
  await expect(history.locator('img')).toHaveCount(0)
  await entry.getByRole('button', { name: /보관$/ }).click()
  await page.getByText('보관한 결정 1개', { exact: true }).click()
  await page.locator('.archive-row').getByText('수정 이력', { exact: true }).click()
  await expect(page.locator('.archive-row .content-history').getByText(original, { exact: true })).toBeVisible()
})
