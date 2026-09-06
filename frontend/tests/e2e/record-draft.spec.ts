import { expect, test } from '@playwright/test'
import { installApi, navigation, openSharedWorkspace } from './support/workspaceApiHarness'

test('@memory @responsive 새로고침 뒤 결정 초안을 불러오고 저장 성공 뒤 초안을 지운다', async ({ page }, testInfo) => {
  await installApi(page)
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { authenticated: false } }))
  await openSharedWorkspace(page)
  const open = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
    await page.getByRole('button', { name: '결정 남기기' }).click()
  }
  await open()
  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 결정했나요?').fill('지운 내용은 초안에 남지 않아야 한다')
  await expect(dialog.getByText('현재 탭에 초안을 저장했습니다.')).toBeVisible()
  await dialog.getByLabel('무엇을 결정했나요?').fill('')
  await expect(dialog.getByText('처음 내용으로 되돌려 저장된 초안을 지웠습니다.')).toBeVisible()
  await page.reload()
  await open()
  await expect(dialog.getByRole('button', { name: '초안 불러오기' })).toHaveCount(0)
  await dialog.getByLabel('무엇을 결정했나요?').fill('미리 회고 질문을 준비한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('모임 중 질문을 생각하느라 회고가 늦어지기 때문입니다.')
  await expect(dialog.getByText('현재 탭에 초안을 저장했습니다.')).toBeVisible()
  await page.reload()
  await open()
  await expect(dialog.getByLabel('무엇을 결정했나요?')).toHaveValue('')
  await dialog.getByRole('button', { name: '초안 불러오기' }).click()
  await expect(dialog.getByLabel('왜 이 선택을 했나요?')).toHaveValue('모임 중 질문을 생각하느라 회고가 늦어지기 때문입니다.')
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()
  await expect(page.getByRole('heading', { name: '미리 회고 질문을 준비한다' })).toBeVisible()
  await page.reload()
  await open()
  await expect(dialog.getByRole('button', { name: '초안 불러오기' })).toHaveCount(0)
  await expect(dialog.getByLabel('무엇을 결정했나요?')).toHaveValue('')
})

test('@memory 초안 삭제와 저장소 실패가 현재 입력과 서버 저장을 막지 않는다', async ({ page }, testInfo) => {
  await installApi(page)
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { authenticated: false } }))
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()
  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 결정했나요?').fill('주간 회고')
  await expect(dialog.getByText('현재 탭에 초안을 저장했습니다.')).toBeVisible()
  await dialog.getByRole('button', { name: '저장된 초안 삭제' }).click()
  await expect(dialog.getByLabel('무엇을 결정했나요?')).toHaveValue('주간 회고')
  await expect(dialog.getByRole('button', { name: '저장된 초안 삭제' })).toHaveCount(0)
  await page.evaluate(() => {
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = function (key, value) {
      if (key.startsWith('baton:record-draft:')) throw new DOMException('저장 불가', 'QuotaExceededError')
      return original.call(this, key, value)
    }
  })
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('운영 개선 사항을 놓치지 않기 위해서')
  await expect(dialog.getByText('초안을 저장하지 못했습니다.', { exact: false })).toBeVisible()
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()
  await expect(page.getByRole('heading', { name: '주간 회고' })).toBeVisible()
})

test('@memory 계정이 바뀌면 다른 계정의 초안을 불러오지 않는다', async ({ page }, testInfo) => {
  await installApi(page)
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { authenticated: false } }))
  let accountId = '00000000-0000-4000-8000-000000000101'
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { authenticated: true, accountId, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'csrf' } }))
  await openSharedWorkspace(page)
  const open = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '기록', exact: true }).click()
    await page.getByRole('button', { name: '결정 남기기' }).click()
  }
  await open()
  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 결정했나요?').fill('첫 계정의 미완성 기록')
  await expect(dialog.getByText('현재 탭에 초안을 저장했습니다.')).toBeVisible()
  accountId = '00000000-0000-4000-8000-000000000102'
  await page.reload()
  await open()
  await expect(dialog.getByRole('button', { name: '초안 불러오기' })).toHaveCount(0)
  await expect(dialog.getByLabel('무엇을 결정했나요?')).toHaveValue('')
})
