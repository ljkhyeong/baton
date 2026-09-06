import { expect, test, type Page } from '@playwright/test'
import { installApi, navigation, openSharedWorkspace, SCOPE_PATH, recordedCall } from './support/workspaceApiHarness'

const VIDEO = 'https://youtu.be/dQw4w9WgXcQ'
const THUMBNAIL = 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg'

async function openForm(page: Page, project: string) {
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: { authenticated: false } }))
  await openSharedWorkspace(page)
  await navigation(page, project).getByRole('button', { name: '역할', exact: true }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  await page.getByLabel('선택한 역할 상세').getByRole('button', { name: '자료 추가' }).click()
  return page.getByRole('dialog', { name: '참고 자료 추가' })
}

test('영상 제목과 썸네일을 저장하고 재조회와 수정에서 재사용한다', async ({ page }, info) => {
  const api = await installApi(page)
  api.commitNextContentCreationThenTimeout('roleResource')
  let previews = 0
  await page.route(`**${SCOPE_PATH}/resource-link-preview?*`, route => {
    previews++
    return route.fulfill({ json: { title: '인수인계 소개 영상', thumbnailUrl: THUMBNAIL } })
  })
  await page.route('https://i.ytimg.com/**', route => route.fulfill({
    contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90"><rect width="160" height="90" fill="#ddd"/></svg>',
  }))
  const dialog = await openForm(page, info.project.name)
  await dialog.getByLabel('링크', { exact: true }).fill(VIDEO)
  await expect(dialog.getByLabel('자료 이름')).toHaveValue('인수인계 소개 영상')
  await expect(dialog.getByAltText('자료 썸네일')).toHaveAttribute('src', THUMBNAIL)
  await dialog.getByRole('button', { name: '자료 연결하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('같은 내용으로 다시 제출하면')
  await dialog.getByRole('button', { name: '자료 연결하기' }).click()
  expect((await recordedCall(api, 'POST', `${SCOPE_PATH}/role-resources`)).body).toMatchObject({
    title: '인수인계 소개 영상', url: VIDEO, thumbnailUrl: THUMBNAIL,
  })
  await expect(dialog).not.toBeVisible()
  await page.reload()
  await navigation(page, info.project.name).getByRole('button', { name: '역할', exact: true }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  await expect(page.locator('.resource-links img')).toHaveAttribute('src', THUMBNAIL)
  await page.getByRole('button', { name: '인수인계 소개 영상 자료 수정', exact: true }).click()
  await expect(page.getByRole('dialog').getByAltText('자료 썸네일')).toBeVisible()
  const edit = page.getByRole('dialog', { name: '참고 자료 수정' })
  await edit.getByLabel('링크', { exact: true }).fill('https://example.com/changed')
  await edit.getByLabel('링크', { exact: true }).fill(VIDEO)
  await expect(edit.getByAltText('자료 썸네일')).toHaveAttribute('src', THUMBNAIL)
  expect(previews).toBe(1)
  await page.screenshot({ path: `../output/playwright/resource-preview-${info.project.name}.png`, fullPage: true, animations: 'disabled' })
})

test('직접 입력한 이름을 유지하고 조회 실패 뒤에도 수동으로 등록한다', async ({ page }, info) => {
  const api = await installApi(page)
  let previews = 0
  await page.route(`**${SCOPE_PATH}/resource-link-preview?*`, route => {
    previews++
    return route.fulfill({ json: { title: '자동 제목', thumbnailUrl: null } })
  })
  const dialog = await openForm(page, info.project.name)
  await dialog.getByLabel('자료 이름').fill('직접 정한 자료 이름')
  await dialog.getByLabel('링크', { exact: true }).fill(VIDEO)
  await expect.poll(() => previews).toBe(1)
  await expect(dialog.getByLabel('자료 이름')).toHaveValue('직접 정한 자료 이름')
  await page.route(`**${SCOPE_PATH}/resource-link-preview?*`, route => route.fulfill({ json: { title: null, thumbnailUrl: null } }))
  await dialog.getByLabel('링크', { exact: true }).fill('https://vimeo.com/76979871')
  await expect(dialog.getByText('정보를 가져오지 못했습니다. 자료 이름을 직접 입력해 주세요.')).toBeVisible()
  await expect(dialog.getByLabel('자료 이름')).toHaveValue('직접 정한 자료 이름')
  await dialog.getByRole('button', { name: '자료 연결하기' }).click()
  expect((await recordedCall(api, 'POST', `${SCOPE_PATH}/role-resources`)).body).toMatchObject({ title: '직접 정한 자료 이름' })
})

test('링크 변경 후 늦게 도착한 이전 제목과 썸네일을 사용하지 않는다', async ({ page }, info) => {
  await installApi(page)
  let firstRequested = false
  let release = () => {}
  const delayed = new Promise<void>(resolve => { release = resolve })
  await page.route(`**${SCOPE_PATH}/resource-link-preview?*`, async route => {
    if (new URL(route.request().url()).searchParams.get('url') === VIDEO) {
      firstRequested = true
      await delayed
      await route.fulfill({ json: { title: '이전 영상', thumbnailUrl: THUMBNAIL } }).catch(() => {})
    } else {
      await route.fulfill({ json: { title: '바꾼 영상', thumbnailUrl: null } })
    }
  })
  const dialog = await openForm(page, info.project.name)
  await dialog.getByLabel('링크', { exact: true }).fill(VIDEO)
  await expect.poll(() => firstRequested).toBe(true)
  await dialog.getByLabel('링크', { exact: true }).fill('https://vimeo.com/76979871')
  await expect(dialog.getByLabel('자료 이름')).toHaveValue('바꾼 영상')
  release()
  await dialog.getByLabel('링크', { exact: true }).fill('https://docs.example.com/manual')
  await expect(dialog.getByLabel('자료 이름')).toHaveValue('')
  await expect(dialog.getByAltText('자료 썸네일')).toHaveCount(0)
})
