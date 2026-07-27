import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

type BrowserRequestResult =
  | {
    ok: true
    value: unknown
  }
  | {
    ok: false
    name: string
    message: string
    kind?: string
    status?: number
    code?: string
  }

async function apiRequestFromBrowser(
  page: Page,
  path: string,
  options: { timeoutMs?: number } = {},
): Promise<BrowserRequestResult> {
  return page.evaluate(async ({ requestPath, requestOptions }) => {
    const { apiRequest } = await import('/src/shared/api/client.ts')

    try {
      const value = await apiRequest<unknown>(requestPath, requestOptions)
      return { ok: true as const, value }
    } catch (error) {
      const apiError = error as Error & {
        kind?: string
        status?: number
        code?: string
      }
      return {
        ok: false as const,
        name: apiError.name,
        message: apiError.message,
        kind: apiError.kind,
        status: apiError.status,
        code: apiError.code,
      }
    }
  }, { requestPath: path, requestOptions: options })
}

test.beforeEach(async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '공용 전송 계층은 데스크톱 Chromium에서 한 번만 검증합니다.')
  await page.goto('/')
})

test('@smoke 연결이 끊기면 network 오류로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/network', (route) => route.abort('connectionreset'))

  await expect(apiRequestFromBrowser(page, '/api-client-test/network')).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'network',
    message: '서버에 연결하지 못해 요청 결과를 확인할 수 없습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.',
  })
})

test('@smoke 응답 제한 시간을 넘기면 timeout 오류로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/timeout', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 100))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ delayed: true }),
    })
  })

  await expect(apiRequestFromBrowser(page, '/api-client-test/timeout', { timeoutMs: 20 })).resolves.toEqual({
    ok: false,
    name: 'ApiClientError',
    kind: 'timeout',
    message: '서버 응답이 늦어 요청 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  })
})

test('@smoke 성공 응답이 JSON이 아니거나 손상되면 invalid-response로 분류한다', async ({ page }) => {
  await page.route('**/api-client-test/plain-text', (route) => route.fulfill({
    status: 200,
    contentType: 'text/plain',
    body: 'BATON is ready',
  }))
  await page.route('**/api-client-test/malformed-json', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"ready":',
  }))

  const expectedError = {
    ok: false,
    name: 'ApiClientError',
    kind: 'invalid-response',
    message: '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  }
  await expect(apiRequestFromBrowser(page, '/api-client-test/plain-text')).resolves.toEqual(expectedError)
  await expect(apiRequestFromBrowser(page, '/api-client-test/malformed-json')).resolves.toEqual(expectedError)
})

test('@smoke HTTP 오류는 계약 정보를 보존하고 손상된 오류 본문은 공용 값으로 대체한다', async ({ page }) => {
  await page.route('**/api-client-test/conflict', (route) => route.fulfill({
    status: 409,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 'WORKSPACE_CONTENT_CONFLICT',
      message: '다른 구성원이 먼저 내용을 변경했습니다.',
    }),
  }))
  await page.route('**/api-client-test/malformed-error', (route) => route.fulfill({
    status: 502,
    contentType: 'application/json',
    body: JSON.stringify({ code: 1, message: {} }),
  }))

  await expect(apiRequestFromBrowser(page, '/api-client-test/conflict')).resolves.toEqual({
    ok: false,
    name: 'ApiError',
    status: 409,
    code: 'WORKSPACE_CONTENT_CONFLICT',
    message: '다른 구성원이 먼저 내용을 변경했습니다.',
  })
  await expect(apiRequestFromBrowser(page, '/api-client-test/malformed-error')).resolves.toEqual({
    ok: false,
    name: 'ApiError',
    status: 502,
    code: 'UNKNOWN_ERROR',
    message: '요청을 처리하지 못했습니다.',
  })
})
