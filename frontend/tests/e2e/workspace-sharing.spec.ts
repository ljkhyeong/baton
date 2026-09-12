import { expect, test } from '@playwright/test'
import { ACCESS_KEY, WORKSPACE_PATH, installApi, openSharedWorkspace } from './support/workspaceApiHarness'

test('@webkit 공유 창이 열려 있는 동안 중복 요청을 막고 현재 공유 키를 전달한다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'share', { configurable: true, value: (data: ShareData) => {
      document.documentElement.dataset.sharedWorkspace = JSON.stringify(data)
      document.documentElement.dataset.shareCount = String(Number(document.documentElement.dataset.shareCount ?? 0) + 1)
      return new Promise<void>(resolve => document.addEventListener('baton-test-share-complete', () => resolve(), { once: true }))
    } })
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
      writeText: async () => { document.documentElement.dataset.clipboardUsed = 'true' },
    } })
  })
  await installApi(page)
  await openSharedWorkspace(page)
  const chrome = testInfo.project.name === 'mobile' ? page.locator('.mobile-topbar') : page.locator('.sidebar')
  const share = chrome.getByRole('button', { name: '공유', exact: true })
  await share.click()
  await share.click()
  expect(await page.evaluate(() => document.documentElement.dataset.shareCount)).toBe('1')
  expect(await page.evaluate(() => JSON.parse(document.documentElement.dataset.sharedWorkspace ?? 'null')))
    .toEqual({ url: new URL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`, page.url()).href })
  await page.evaluate(() => document.dispatchEvent(new Event('baton-test-share-complete')))
  await share.click()
  expect(await page.evaluate(() => document.documentElement.dataset.shareCount)).toBe('2')
  await page.evaluate(() => document.dispatchEvent(new Event('baton-test-share-complete')))
  expect(await page.evaluate(() => document.documentElement.dataset.clipboardUsed)).toBeUndefined()
  await expect(page.getByRole('dialog', { name: '공유 링크 직접 복사' })).toHaveCount(0)
})

for (const [mode, title] of [
  ['unsupported', '기기 공유를 지원하지 않으면 링크를 복사한다'],
  ['denied', '기기 공유가 차단되면 링크를 복사한다'],
  ['cancelled', '기기 공유를 취소하면 링크를 복사하지 않는다'],
] as const) {
  test(`@webkit ${title}`, async ({ page }, testInfo) => {
    await page.addInitScript((shareMode) => {
      Object.defineProperty(navigator, 'share', { configurable: true, value: shareMode === 'unsupported' ? undefined : async () => {
        document.documentElement.dataset.shareAttempted = 'true'
        throw new DOMException('공유 종료', shareMode === 'cancelled' ? 'AbortError' : 'NotAllowedError')
      } })
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
        writeText: async (url: string) => { document.documentElement.dataset.copiedWorkspace = url },
      } })
    }, mode)
    await installApi(page)
    await openSharedWorkspace(page)
    const chrome = testInfo.project.name === 'mobile' ? page.locator('.mobile-topbar') : page.locator('.sidebar')
    await chrome.getByRole('button', { name: '공유', exact: true }).click()
    if (mode === 'cancelled') {
      expect(await page.evaluate(() => document.documentElement.dataset.shareAttempted)).toBe('true')
      expect(await page.evaluate(() => document.documentElement.dataset.copiedWorkspace)).toBeUndefined()
      await expect(page.getByRole('dialog', { name: '공유 링크 직접 복사' })).toHaveCount(0)
      await expect(page.getByRole('status').filter({ hasText: '공유 링크를 복사했어요.' })).toHaveCount(0)
    } else {
      await expect(page.getByRole('status')).toContainText('공유 링크를 복사했어요.')
      expect(await page.evaluate(() => document.documentElement.dataset.copiedWorkspace))
        .toBe(new URL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`, page.url()).href)
    }
  })
}
