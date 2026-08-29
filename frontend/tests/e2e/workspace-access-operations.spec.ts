import { expect, test } from '@playwright/test'
import type { Dialog, Page } from '@playwright/test'
import type {
  SeasonRound,
} from '../../src/features/workspace/types'
import {
  TEAM_ID,
  SEASON_ID,
  MEMBER_TWO_ID,
  ROLE_ID,
  SECOND_ROLE_ID,
  ROUTINE_ID,
  SECOND_ROUTINE_ID,
  HANDOFF_TWO_ID,
  ROUND_ONE_ID,
  ROUND_TWO_ID,
  CREATED_ROUND_ID,
  AUTOMATIC_ROUND_ID,
  ROUND_TWO_ROUTINE_TWO_EXECUTION_ID,
  CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID,
  AUTOMATIC_ROUND_ROUTINE_ONE_EXECUTION_ID,
  AUTOMATIC_ROUND_ROUTINE_TWO_EXECUTION_ID,
  ACCESS_KEY,
  ROTATED_ACCESS_KEY,
  SECOND_ROTATED_ACCESS_KEY,
  WORKSPACE_PATH,
  SCOPE_PATH,
  PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  makeProjection,
  installApi,
  openSharedWorkspace,
  navigation,
  blockBrowserStorage,
  failNextAccessKeyRotationCleanup,
  pendingContentCreationEntries,
  recordedCall,
  expectScopedCall,
} from './support/workspaceApiHarness'

test('공유 링크 fragment를 지울 때 React Router history 상태를 보존한다', async ({ page }) => {
  const api = await installApi(page)
  api.holdWorkspaceGets()
  let workspaceGetsReleased = false

  try {
    await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
    await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
    const expectedHistoryState = await page.evaluate(() => {
      const state = {
        ...(window.history.state as Record<string, unknown> | null),
        usr: { source: 'shared-workspace-link' },
      }
      window.history.replaceState(state, '', window.location.href)
      return state
    })

    api.releaseWorkspaceGets()
    workspaceGetsReleased = true
    await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
    await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
    expect(await page.evaluate(() => window.history.state)).toEqual(expectedHistoryState)

    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: /사람이 바뀌어도/ })).toBeVisible()
    await page.goBack()
    await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))
    await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
    expect(await page.evaluate(() => window.history.state)).toEqual(expectedHistoryState)
  } finally {
    if (!workspaceGetsReleased) api.releaseWorkspaceGets()
  }
})

test('@smoke 접근 키를 바꾸면 저장 키와 새 공유 링크를 함께 교체한다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.reject(new Error('denied')) },
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await page.evaluate((accessKey) => {
    window.history.replaceState(window.history.state, '', `${window.location.pathname}#accessKey=${accessKey}`)
  }, ACCESS_KEY)
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  await expect(keyDialog.getByText('이전 공유 링크는 즉시 열리지 않습니다.')).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

  const rotateCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expectScopedCall(rotateCall)
  expect(rotateCall.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,64}$/)
  await expect.poll(() => api.calls.some((call) =>
    call.method === 'GET'
      && call.path === `${SCOPE_PATH}/workspace`
      && call.headers['x-baton-access-key'] === ROTATED_ACCESS_KEY,
  )).toBeTruthy()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
  await expect(page).toHaveURL(new RegExp(`${WORKSPACE_PATH}$`))

  await workspaceChrome.getByRole('button', { name: '공유' }).click()
  const shareLink = page.getByRole('dialog', { name: '공유 링크 직접 복사' }).getByLabel('공유 링크')
  await expect(shareLink).toHaveValue(new RegExp(`#accessKey=${ROTATED_ACCESS_KEY}$`))
  await page.getByRole('dialog', { name: '공유 링크 직접 복사' }).getByRole('button', { name: '확인' }).click()

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
})

test('접근 키 회전 응답이 손상되면 기존 키와 URL 및 journal을 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await page.evaluate((accessKey) => {
    window.history.replaceState(
      window.history.state,
      '',
      `${window.location.pathname}#accessKey=${accessKey}`,
    )
  }, ACCESS_KEY)
  api.returnMalformedNextAccessKeyRotationResponse()

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

  await expect(keyDialog.getByRole('alert')).toContainText(
    '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  )
  const rotateCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expect(JSON.parse(await page.evaluate(
    (key) => localStorage.getItem(key) ?? 'null',
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  ))?.idempotencyKey).toBe(rotateCall.headers['idempotency-key'])
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    `baton-access-key:${TEAM_ID}`,
  )).toBe(ACCESS_KEY)
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
})

test('접근 키 회전은 서버 응답 전 dialog 종료와 재진입을 막는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: () => {
          document.documentElement.dataset.batonShareAttempted = 'true'
          return Promise.resolve()
        },
      },
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  const rotationPath = `${SCOPE_PATH}/access-key/rotate`
  const rotationCallCount = () => api.calls.filter(
    (call) => call.method === 'POST' && call.path === rotationPath,
  ).length
  let confirmationCount = 0
  const acceptConfirmation = async (dialog: Dialog) => {
    confirmationCount += 1
    await dialog.accept()
  }
  page.on('dialog', acceptConfirmation)
  api.holdAccessKeyRotations()

  try {
    await keyDialog.getByRole('button', { name: '접근 키 바꾸기' })
      .evaluate((button: HTMLButtonElement) => {
        button.click()
        button.click()
        const dialog = button.closest('[role="dialog"]')
        dialog?.querySelector<HTMLButtonElement>('.secondary-button')?.click()
        dialog?.querySelector<HTMLButtonElement>('.modal-close')?.click()
        dialog?.parentElement?.dispatchEvent(new MouseEvent('mousedown', {
          bubbles: true,
          cancelable: true,
        }))
        document.dispatchEvent(new KeyboardEvent('keydown', {
          bubbles: true,
          cancelable: true,
          key: 'Escape',
        }))
      })

    await expect.poll(() => confirmationCount).toBe(1)
    await expect.poll(rotationCallCount).toBe(1)
    await expect(keyDialog).toBeVisible()
    await expect(keyDialog).toHaveAttribute('aria-busy', 'true')
    const closeButton = keyDialog.getByRole('button', { name: '닫기' })
    await expect(closeButton).toBeDisabled()
    await expect(keyDialog.getByRole('button', { name: '현재 링크 복사' })).toBeDisabled()
    await expect(keyDialog.getByRole('button', { name: '접근 키 바꾸는 중…' })).toBeDisabled()
    expect(await page.evaluate(() =>
      document.documentElement.dataset.batonShareAttempted)).toBeUndefined()

    await page.keyboard.press('Escape')
    await expect(keyDialog).toBeVisible()
    await closeButton.click({ force: true })
    await expect(keyDialog).toBeVisible()
    await page.locator('.modal-backdrop').click({ position: { x: 5, y: 5 }, force: true })
    await expect(keyDialog).toBeVisible()
    expect(rotationCallCount()).toBe(1)

    const rotateCall = [...api.calls].reverse().find(
      (call) => call.method === 'POST' && call.path === rotationPath,
    )
    expect(JSON.parse(await page.evaluate(
      (key) => localStorage.getItem(key) ?? 'null',
      PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
    ))?.idempotencyKey).toBe(rotateCall?.headers['idempotency-key'])
  } finally {
    page.off('dialog', acceptConfirmation)
    api.releaseAccessKeyRotations()
  }

  await expect(keyDialog).toHaveCount(0)
  expect(rotationCallCount()).toBe(1)
  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
  ).toBeNull()
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    `baton-access-key:${TEAM_ID}`,
  )).toBe(ROTATED_ACCESS_KEY)
})

test('접근 키 회전 journal은 탭 간 요청 완료까지 같은 임계 구역에서 보호한다', async ({ page, context }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  await openSharedWorkspace(peerPage)

  const openKeyManagement = async (target: Page) => {
    const workspaceChrome = testInfo.project.name === 'mobile'
      ? target.locator('.mobile-topbar')
      : target.locator('.sidebar')
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
    return target.getByRole('dialog', { name: '공유 접근 키 관리' })
  }
  const rotationCalls = () => api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  )

  api.holdAccessKeyRotations()
  try {
    const firstDialog = await openKeyManagement(page)
    page.once('dialog', (dialog) => dialog.accept())
    await firstDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
    const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)

    const peerDialog = await openKeyManagement(peerPage)
    peerPage.once('dialog', (dialog) => dialog.accept())
    await peerDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

    await expect(peerDialog.getByRole('alert')).toContainText(
      '다른 탭에서 접근 키 변경 결과를 확인 중입니다.',
    )
    expect(rotationCalls()).toHaveLength(1)
    expect(JSON.parse(await page.evaluate(
      (key) => localStorage.getItem(key) ?? 'null',
      PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
    ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

    api.releaseAccessKeyRotations()

    await expect(firstDialog).toHaveCount(0)
    await expect.poll(() =>
      page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
    ).toBeNull()
    expect(rotationCalls()).toHaveLength(1)
  } finally {
    api.releaseAccessKeyRotations()
    await peerPage.close()
  }
})

test('Web Locks를 사용할 수 없으면 접근 키 회전 요청을 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: undefined,
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()

  await expect(keyDialog.getByRole('alert')).toContainText(
    '탭 사이의 접근 키 변경을 안전하게 조정할 수 없습니다.',
  )
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  )).toHaveLength(0)
  expect(await page.evaluate(
    (key) => localStorage.getItem(key),
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  )).toBeNull()
})

test('접근 키 회전 완료 기록을 전혀 정리하지 못하면 과거 결과를 성공으로 오인하지 않는다', async ({ page }, testInfo) => {
  await failNextAccessKeyRotationCleanup(page)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })
  const acceptConfirmation = (dialog: Dialog) => dialog.accept()
  page.on('dialog', acceptConfirmation)

  try {
    const rotateButton = () => keyDialog.getByRole('button', { name: '접근 키 바꾸기' })
    await rotateButton().click()
    await expect(keyDialog.getByRole('alert')).toContainText('완료 기록을 정리하지 못했습니다.')
    const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
    expect(JSON.parse(await page.evaluate(
      (key) => localStorage.getItem(key) ?? 'null',
      PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
    ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])
    expect(await page.evaluate(
      (key) => localStorage.getItem(key),
      `baton-access-key:${TEAM_ID}`,
    )).toBe(ROTATED_ACCESS_KEY)

    await keyDialog.getByRole('button', { name: '닫기' }).click()
    await page.reload()
    await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()

    await rotateButton().click()
    await expect(keyDialog.getByRole('alert')).toContainText('접근 키는 이번 요청에서 새로 바뀌지 않았습니다.')
    const replayAttempts = api.calls.filter(
      (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
    )
    expect(replayAttempts).toHaveLength(2)
    expect(replayAttempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
    await expect.poll(() =>
      page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
    ).toBeNull()

    await rotateButton().click()
    await expect(keyDialog).toHaveCount(0)
    const attempts = api.calls.filter(
      (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
    )
    expect(attempts).toHaveLength(3)
    expect(attempts[2]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
    expect(await page.evaluate(
      (key) => localStorage.getItem(key),
      `baton-access-key:${TEAM_ID}`,
    )).toBe(SECOND_ROTATED_ACCESS_KEY)
  } finally {
    page.off('dialog', acceptConfirmation)
  }
})

test('@smoke 폐기된 접근 키 링크는 같은 앱 세션의 캐시를 재사용하지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(({ storageKey, accessKey, recentWorkspace }) => {
    localStorage.setItem(storageKey, accessKey)
    localStorage.setItem('baton-recent-workspaces:v1', JSON.stringify([recentWorkspace]))
  }, {
    storageKey: `baton-access-key:${TEAM_ID}`,
    accessKey: ACCESS_KEY,
    recentWorkspace: {
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      teamName: '알고리즘 한 바퀴',
      seasonName: '2026 여름 시즌',
      lastOpenedAt: '2026-07-24T00:00:00.000Z',
    },
  })
  await installApi(page)
  await page.goto('/')
  await page.getByRole('region', { name: '최근 작업 공간' })
    .getByRole('link', { name: /알고리즘 한 바퀴.*2026 여름 시즌/ })
    .click()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' })
    .getByRole('button', { name: '접근 키 바꾸기' })
    .click()

  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`),
  ).toBe(ROTATED_ACCESS_KEY)

  await page.evaluate(() => {
    document.documentElement.dataset.batonSameDocument = 'true'
  })
  await page.goBack()
  await expect(page.getByRole('heading', { level: 1, name: /사람이 바뀌어도/ })).toBeVisible()
  await page.goForward()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  expect(await page.evaluate(() =>
    document.documentElement.dataset.batonSameDocument)).toBe('true')
  const deniedResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === `${SCOPE_PATH}/workspace`
        && response.request().headers()['x-baton-access-key'] === ACCESS_KEY,
    { timeout: 5_000 },
  )
  const navigationResponse = await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)

  expect(navigationResponse).toBeNull()
  expect((await deniedResponse).status()).toBe(403)
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  expect(await page.evaluate((key) =>
    localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
})

test('접근 키 회전 후 브라우저 저장이 실패하면 새 키를 fragment에 보존한다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith('baton-access-key:')) throw new DOMException('Storage disabled', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  })
  const api = await installApi(page)
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()

  await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ROTATED_ACCESS_KEY}`)
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBeNull()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()

  await page.reload()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ROTATED_ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  const reloadedGet = [...api.calls].reverse().find((call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`)
  expect(reloadedGet?.headers['x-baton-access-key']).toBe(ROTATED_ACCESS_KEY)
})

test('회전 pending을 내구 저장할 수 없으면 reload 후에도 API를 호출하지 않는다', async ({ page }) => {
  await blockBrowserStorage(page)
  const api = await installApi(page)

  const tryRotation = async () => {
    const workspaceChrome = page.viewportSize()?.width === 390
      ? page.locator('.mobile-topbar')
      : page.locator('.sidebar')
    await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
    await expect(page.getByRole('alert')).toContainText('일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.')
  }

  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await tryRotation()

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  await expect(page).toHaveURL(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await tryRotation()

  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)).toHaveLength(0)
})

test('만료된 접근 키 회전 기록은 지우고 다음 명시적 시도에 새 키를 사용한다', async ({ page }, testInfo) => {
  await failNextAccessKeyRotationCleanup(page)
  const api = await installApi(page)
  api.expireNextAccessKeyRotationReplay()
  await openSharedWorkspace(page)

  const workspaceChrome = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  const keyDialog = page.getByRole('dialog', { name: '공유 접근 키 관리' })

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(keyDialog.getByText(/새 요청으로 다시 시도해 주세요/)).toBeVisible()
  await expect(keyDialog.getByText(/이전 접근 키 변경 기록을 정리하지 못했습니다/)).toBeVisible()
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expect(JSON.parse(await page.evaluate(
    (key) => localStorage.getItem(key) ?? 'null',
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

  page.once('dialog', (dialog) => dialog.accept())
  await keyDialog.getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('status')).toContainText('접근 키를 바꿨어요.')
  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
  ).toBeNull()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  ).length).toBe(2)
  const attempts = api.calls.filter((call) =>
    call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@smoke 응답이 유실된 접근 키 회전을 403 화면에서 같은 멱등 키로 복구한다', async ({ page }) => {
  const api = await installApi(page)
  api.commitNextAccessKeyRotationThenTimeout()
  await openSharedWorkspace(page)

  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('alert')).toContainText('접근 키 변경 응답을 확인하지 못했습니다.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  expect(firstAttempt.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,64}$/)

  await page.reload()
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' })
    .evaluate((button: HTMLButtonElement) => {
      button.click()
      button.click()
    })

  await expect.poll(() => api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`).length).toBe(2)
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()
  expect(await page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  const recoveredGet = [...api.calls].reverse().find((call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`)
  expect(recoveredGet?.headers['x-baton-access-key']).toBe(ROTATED_ACCESS_KEY)
})

test('충돌 pending 복구가 403이면 반복을 멈추고 최신 공유 링크 확인을 안내한다', async ({ page }) => {
  await failNextAccessKeyRotationCleanup(page)
  const api = await installApi(page)
  api.conflictNextAccessKeyRotation()
  await openSharedWorkspace(page)

  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()

  await expect(page.getByRole('alert')).toContainText('다른 접근 키 변경을 처리하고 있습니다.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/access-key/rotate`)
  const pendingAfterConflict = await page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)
  expect(JSON.parse(pendingAfterConflict ?? 'null')?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

  api.rotateAccessKeyFromAnotherDevice()
  await page.reload()
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' }).click()

  await expect(page.getByText('다른 기기에서 더 최신 접근 키 변경이 완료된 것으로 보입니다.')).toBeVisible()
  await expect(page.getByText('이전 접근 키 변경 기록을 정리하지 못했습니다. 브라우저 저장을 허용한 뒤 완료 기록 정리를 다시 확인해 주세요.')).toBeVisible()
  await expect(page.getByText('작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.')).toBeVisible()
  await expect(page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' })).toHaveCount(0)
  expect(JSON.parse(await page.evaluate(
    (key) => localStorage.getItem(key) ?? 'null',
    PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY,
  ))?.idempotencyKey).toBe(firstAttempt.headers['idempotency-key'])

  await page.getByRole('button', { name: '완료 기록 정리 다시 확인' }).click()
  await expect(page.getByRole('button', { name: '완료 기록 정리 다시 확인' })).toHaveCount(0)
  await expect(page.getByText('이전 접근 키 변경 기록을 정리했습니다. 최신 공유 링크로 다시 열어 주세요.')).toBeVisible()
  await expect.poll(() =>
    page.evaluate((key) => localStorage.getItem(key), PENDING_ACCESS_KEY_ROTATION_STORAGE_KEY),
  ).toBeNull()

  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
})

test('만료된 접근 키 복구 기록을 지우고 최신 공유 링크 확인을 안내한다', async ({ page }) => {
  const api = await installApi(page)
  api.commitNextAccessKeyRotationThenTimeout()
  await openSharedWorkspace(page)

  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('dialog', { name: '공유 접근 키 관리' }).getByRole('button', { name: '접근 키 바꾸기' }).click()
  await expect(page.getByRole('alert')).toContainText('접근 키 변경 응답을 확인하지 못했습니다.')
  api.expireAccessKeyRotationHistory()

  await page.reload()
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '접근 키 변경 완료 확인/복구' }).click()

  await expect(page.getByRole('alert')).toContainText('더 최신 접근 키 변경이 완료되어 이전 결과를 자동 복구할 수 없습니다.')
  await expect(page.getByText('작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.')).toBeVisible()
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-pending-access-key-change:v1:${TEAM_ID}`)).toBeNull()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(attempts[0]?.headers['idempotency-key'])
})

test('손상된 회전 pending 저장소를 무시하고 정상 멱등 키로 replay한다', async ({ page }) => {
  const pendingStorageKey = `baton-pending-access-key-change:v1:${TEAM_ID}`
  const malformedIdempotencyKey = 'invalid key'
  await page.addInitScript(({ storageKey, invalidKey }) => {
    localStorage.setItem(storageKey, JSON.stringify({ operation: 'recover', idempotencyKey: invalidKey }))
  }, { storageKey: pendingStorageKey, invalidKey: malformedIdempotencyKey })

  const api = await installApi(page)
  api.commitNextAccessKeyRotationThenTimeout()
  await openSharedWorkspace(page)
  const workspaceChrome = page.viewportSize()?.width === 390
    ? page.locator('.mobile-topbar')
    : page.locator('.sidebar')
  await workspaceChrome.getByRole('button', { name: '키 관리' }).click()

  const rotate = async () => {
    const rotateButton = page.getByRole('dialog', { name: '공유 접근 키 관리' })
      .getByRole('button', { name: '접근 키 바꾸기' })
    await rotateButton.focus()
    page.once('dialog', (dialog) => dialog.accept())
    await page.keyboard.press('Enter')
  }
  await rotate()
  await expect(page.getByRole('alert')).toContainText('접근 키 변경 응답을 확인하지 못했습니다.')
  await rotate()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`,
  ).length).toBe(2)
  const attempts = api.calls.filter((call) =>
    call.method === 'POST' && call.path === `${SCOPE_PATH}/access-key/rotate`)
  expect(attempts).toHaveLength(2)
  expect(attempts[0]?.headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  expect(attempts[0]?.headers['idempotency-key']).not.toBe(malformedIdempotencyKey)
  expect(attempts[1]?.headers['idempotency-key']).toBe(attempts[0]?.headers['idempotency-key'])
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), `baton-access-key:${TEAM_ID}`)).toBe(ROTATED_ACCESS_KEY)
  await expect.poll(() => api.calls.some((call) =>
    call.method === 'GET'
      && call.path === `${SCOPE_PATH}/workspace`
      && call.headers['x-baton-access-key'] === ROTATED_ACCESS_KEY,
  )).toBeTruthy()
})

test('@smoke @responsive @continuity 조직 연속성 레이더는 이유와 다음 행동을 보여 주고 관련 역할을 연다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.roles.push({
    id: SECOND_ROLE_ID,
    name: '기록자',
    purpose: '결정과 근거를 다음 회차에 이어 줍니다.',
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: null,
    assignmentStartDate: '2026-07-02',
    assignmentEndDate: '2026-07-20',
    responsibilities: ['결정과 근거 정리'],
    risk: '결정 근거가 채팅에만 남을 수 있어요.',
  })
  projection.continuitySignals = [
    {
      type: 'ROLE_SUCCESSOR_MISSING',
      severity: 'CRITICAL',
      roleId: SECOND_ROLE_ID,
      routineId: null,
      title: '기록자 후임 공백',
      reason: '기록자 역할의 담당 기간이 오늘 끝나지만 다음 담당자가 없습니다.',
      recommendedAction: '다음 담당자를 정하고 역할 바통 준비를 시작하세요.',
      relevantDate: '2026-07-20',
    },
    {
      type: 'ROLE_PREPARATION_INCOMPLETE',
      severity: 'WARNING',
      roleId: ROLE_ID,
      routineId: null,
      title: '문제 큐레이터 준비 부족',
      reason: '위험 신호가 있지만 역할 자료와 미완료 바통 항목을 먼저 정리해야 합니다.',
      recommendedAction: '역할 화면과 바통북에서 빠진 책임, 항목과 자료를 보완하세요.',
      relevantDate: null,
    },
  ]

  await installApi(page, projection)
  await openSharedWorkspace(page)

  const radar = page.getByRole('region', { name: '조직 연속성 레이더' })
  await expect(radar).toBeVisible()
  await expect(radar.locator('.continuity-count')).toHaveText('2개')
  const signals = radar.getByRole('listitem')
  await expect(signals).toHaveCount(2)
  await expect(signals.nth(0)).toContainText('기록자 후임 공백')
  await expect(signals.nth(0)).toContainText('오늘 끝나지만 다음 담당자가 없습니다')
  await expect(signals.nth(0)).toContainText('다음 담당자를 정하고 역할 바통 준비를 시작하세요')
  await expect(signals.nth(1)).toContainText('문제 큐레이터 준비 부족')

  const primarySignal = signals.nth(0).getByRole('button')
  await primarySignal.focus()
  await expect(primarySignal).toBeFocused()
  const signalBox = await primarySignal.boundingBox()
  expect(signalBox?.height ?? 0).toBeGreaterThanOrEqual(44)
  expect(await primarySignal.evaluate((element) =>
    element.scrollWidth <= element.clientWidth,
  )).toBeTruthy()
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )).toBeTruthy()

  await primarySignal.click()
  await expect(page.getByLabel('선택한 역할 상세: 기록자')).toBeVisible()
  if (testInfo.project.name === 'mobile') {
    await expect(page.getByRole('button', { name: '상세 닫기' })).toBeFocused()
  } else {
    await expect(page.getByRole('heading', { level: 1, name: '사람이 바뀌어도 역할은 남아요' }))
      .toBeVisible()
    await expect(page.locator('.role-row.selected .role-row-open')).toBeFocused()
    await expect(page.locator('.role-row.selected')).toContainText('기록자')
  }
})

test('@continuity 반복 지연 신호는 해당 루틴이 있는 운영 화면으로 초점을 옮긴다', async ({ page }) => {
  const projection = makeProjection()
  projection.continuitySignals = [{
    type: 'ROUTINE_REPEATEDLY_OVERDUE',
    severity: 'CRITICAL',
    roleId: ROLE_ID,
    routineId: ROUTINE_ID,
    title: '문제 5개 선정 반복 지연',
    reason: '문제 5개 선정 루틴이 서로 다른 3개 회차에서 마감 뒤에도 완료되지 않았습니다.',
    recommendedAction: '루틴의 담당, 마감과 실행 방법을 다시 정하고 밀린 회차를 정리하세요.',
    relevantDate: null,
  }]

  await installApi(page, projection)
  await openSharedWorkspace(page)
  await page.getByRole('region', { name: '조직 연속성 레이더' })
    .getByRole('button')
    .click()

  await expect(page.getByRole('heading', { level: 1, name: '우리 팀은 이렇게 움직여요' }))
    .toBeVisible()
  await expect(page.locator(`.routine-row[data-routine-id="${ROUTINE_ID}"] .routine-copy`))
    .toBeFocused()
})

test('@continuity 미완료 바통 신호는 해당 역할의 바통 탭으로 초점을 옮긴다', async ({ page }) => {
  const projection = makeProjection()
  projection.continuitySignals = [{
    type: 'HANDOFF_INCOMPLETE',
    severity: 'WARNING',
    roleId: ROLE_ID,
    routineId: null,
    title: '문제 큐레이터 바통 전달 대기',
    reason: '바통 항목 준비는 끝났지만 아직 전달하지 않았습니다.',
    recommendedAction: '현재 담당자가 준비된 바통을 다음 담당자에게 전달하세요.',
    relevantDate: '2026-07-27',
  }]

  await installApi(page, projection)
  await openSharedWorkspace(page)
  await page.getByRole('region', { name: '조직 연속성 레이더' })
    .getByRole('button')
    .click()

  await expect(page.getByRole('heading', { level: 1, name: '다음 사람이 헤매지 않도록' }))
    .toBeVisible()
  await expect(page.getByRole('tab', { name: /문제 큐레이터/ })).toBeFocused()
})

test('자동 회차와 지연 상태를 오늘 화면에서 구분하고 직접 수정을 막는다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  const automaticRound = projection.rounds.find((round) => round.id === ROUND_TWO_ID)
  expect(automaticRound).toBeDefined()
  automaticRound!.origin = 'AUTOMATIC'
  automaticRound!.scheduledOccurrenceDate = automaticRound!.meetingDate
  automaticRound!.scheduledAt = '2026-07-17T10:00:00Z'
  automaticRound!.timingStatus = 'OVERDUE'
  automaticRound!.routineExecutions[1]!.timingStatus = 'OVERDUE'

  await installApi(page, projection)
  await openSharedWorkspace(page)

  await expect(page.locator('.round-meta')).toContainText('자동 생성 · 지연 · 2회차')
  await expect(page.locator('.relay-status').filter({ hasText: '지연' })).toBeVisible()
  const relayList = page.getByRole('region', { name: '바통 라인' }).getByRole('list')
  const relayItems = relayList.getByRole('listitem')
  await expect(relayItems).toHaveCount(2)
  const overdueItem = relayItems.filter({ hasText: '풀이 노트 정리' })
  await expect(overdueItem).toContainText('지연')
  await expect(overdueItem.getByRole('button', { name: /풀이 노트 정리/ })).toBeVisible()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 수정' }))
    .toHaveAttribute('title', '자동 회차는 반복 설정으로 관리합니다')
})

test('@operations 새 자동 회차는 관련 기본 선택을 갱신하되 사용자가 고른 회차는 보존한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const sourceRound = api.projection().rounds.find((round) => round.id === ROUND_ONE_ID)
  expect(sourceRound).toBeDefined()
  const automaticRound: SeasonRound = {
    ...sourceRound!,
    id: AUTOMATIC_ROUND_ID,
    name: '자동 3회차',
    meetingDate: '2026-07-24',
    origin: 'AUTOMATIC',
    scheduledOccurrenceDate: '2026-07-24',
    scheduledAt: '2026-07-24T10:00:00Z',
    timingStatus: 'OVERDUE',
    routineExecutions: sourceRound!.routineExecutions.map((execution, index) => ({
      ...execution,
      id: index === 0
        ? AUTOMATIC_ROUND_ROUTINE_ONE_EXECUTION_ID
        : AUTOMATIC_ROUND_ROUTINE_TWO_EXECUTION_ID,
      roundId: AUTOMATIC_ROUND_ID,
      timingStatus: index === 0 ? 'OVERDUE' : 'PLANNED',
    })),
  }
  api.addRoundFromAnotherDevice(automaticRound)

  await expect(page.getByLabel('운영 회차')).toHaveValue(AUTOMATIC_ROUND_ID)

  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
  const workspaceGetCount = () => api.calls.filter(
    (call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length
  const getsBeforeRefresh = workspaceGetCount()
  const refreshButton = page.getByRole('button', { name: '지금 새로고침' })
  await expect(refreshButton).toBeEnabled()
  await refreshButton.click()
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeRefresh)
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_ONE_ID)
})

test('@operations 시즌 시간대와 격주 일정을 저장해 자동 회차 운영 카드를 갱신한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  await expect(page.getByRole('heading', { name: '자동 회차가 꺼져 있어요' })).toBeVisible()
  await page.getByRole('button', { name: '설정하기' }).click()

  const dialog = page.getByRole('dialog', { name: '자동 회차 설정' })
  await dialog.getByLabel('시즌 시간대').fill('Asia/Seoul')
  await dialog.getByLabel('첫 자동 회차').fill('2026-08-06')
  await dialog.getByLabel('모임 시각').fill('20:30')
  await dialog.getByLabel('반복 주기').selectOption('BIWEEKLY')
  await dialog.getByLabel('미리 만들 기간').selectOption('14')
  await dialog.getByRole('button', { name: '자동 회차 저장' }).click()

  await expect(page.getByRole('heading', { name: '격주 20:30' })).toBeVisible()
  await expect(page.locator('.round-schedule-card')).toContainText(
    'Asia/Seoul · 자동 생성 중 · 다음 발생 2026. 8. 6.',
  )
  const scheduleCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/round-schedule`)
  expectScopedCall(scheduleCall, {
    timeZone: 'Asia/Seoul',
    firstMeetingDate: '2026-08-06',
    meetingTime: '20:30',
    recurrence: 'BIWEEKLY',
    generationLeadDays: 14,
    enabled: true,
  })
})

test('@operations 루틴과 회차를 내구 생성하고 선택한 회차의 완료 상태를 독립적으로 저장한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await page.getByRole('button', { name: '루틴 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await dialog.getByLabel('루틴 이름').fill('회고 질문 준비')
  await dialog.getByLabel('운영 단계').selectOption({ label: '모임 전' })
  await dialog.getByLabel('담당 역할').selectOption(ROLE_ID)
  await dialog.getByLabel('언제까지').fill('목요일 19:00')
  await dialog.getByLabel('세부 설명').fill('지난 회차에서 이어갈 질문 두 개를 고릅니다.')
  await dialog.getByRole('button', { name: '루틴 만들기' }).click()

  const routineCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/routines`)
  expectScopedCall(routineCall, {
    title: '회고 질문 준비',
    phase: 'BEFORE',
    dueLabel: '목요일 19:00',
    ownerRoleId: ROLE_ID,
    detail: '지난 회차에서 이어갈 질문 두 개를 고릅니다.',
    deadlineDayOffset: -1,
    deadlineTime: '22:00',
  })

  const futureRoutine = page.locator('.routine-row').filter({ hasText: '회고 질문 준비' })
  await expect(futureRoutine).toContainText('다음 회차부터')
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 처리' })).toHaveCount(0)

  api.commitNextContentCreationThenTimeout('round')
  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await expect(roundDialog.getByLabel('회차 이름')).toHaveValue('3회차')
  await expect(roundDialog.getByLabel('모임 날짜')).toHaveAttribute('min', '2026-07-02')
  await expect(roundDialog.getByLabel('모임 날짜')).toHaveAttribute('max', '2026-09-17')
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-24')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()

  await expect(roundDialog.getByRole('alert')).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')
  const firstRoundAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/rounds`)
  expectScopedCall(firstRoundAttempt, { name: '3회차', meetingDate: '2026-07-24' })
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      operation: 'round',
      idempotencyKey: firstRoundAttempt.headers['idempotency-key'],
    }),
  ])

  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(CREATED_ROUND_ID)
  const roundAttempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/rounds`)
  expect(roundAttempts).toHaveLength(2)
  expect(roundAttempts[1]?.headers['idempotency-key']).toBe(firstRoundAttempt.headers['idempotency-key'])
  expect(api.projection().rounds.filter((round) => round.id === CREATED_ROUND_ID)).toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)

  const createdRoutineToggle = page.getByRole('button', { name: '회고 질문 준비 완료 처리' })
  await createdRoutineToggle.click()
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
  const completionCall = await recordedCall(api, 'PATCH', `${SCOPE_PATH}/rounds/${CREATED_ROUND_ID}/routine-executions/${CREATED_ROUND_NEW_ROUTINE_EXECUTION_ID}/completion`)
  expectScopedCall(completionCall, { completed: true })

  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
  await expect(page.locator('.routine-row').filter({ hasText: '회고 질문 준비' })).toContainText('다음 회차부터')
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toHaveCount(0)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await page.getByLabel('운영 회차').selectOption(CREATED_ROUND_ID)
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
})

test('@operations @responsive 루틴 정의를 보관해도 과거 실행을 완료하고 복원한 정의는 다음 회차부터 사용한다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.season.roundSchedule = {
    firstMeetingDate: '2026-07-23',
    meetingTime: '20:00:00',
    recurrence: 'WEEKLY',
    generationLeadDays: 7,
    enabled: true,
    nextOccurrenceDate: '2026-07-23',
  }
  initialProjection.seasons = initialProjection.seasons.map((season) => ({
    ...season,
    roundSchedule: initialProjection.season.roundSchedule,
  }))
  const api = await installApi(page, initialProjection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  const archiveButton = page.getByRole('button', { name: '문제 5개 선정 루틴 보관' })
  if (testInfo.project.name === 'mobile') {
    await expect.poll(async () => (await archiveButton.boundingBox())?.height ?? 0)
      .toBeGreaterThanOrEqual(44)
  }

  api.holdNextRoutineArchive()
  await archiveButton.click()
  const archivePath = `${SCOPE_PATH}/routines/${ROUTINE_ID}/archive`
  expectScopedCall(await recordedCall(api, 'PATCH', archivePath), { archived: true })
  await expect(archiveButton).toBeDisabled()
  await expect(archiveButton).toHaveAttribute('aria-busy', 'true')
  api.releaseRoutineArchive()

  const archiveSummary = page.getByText('보관한 루틴 1개', { exact: true })
  await expect(archiveSummary).toBeFocused()
  const historicalRow = page.locator('.routine-row').filter({ hasText: '문제 5개 선정' })
  await expect(historicalRow).toContainText('정의 보관됨')
  await expect(historicalRow.getByRole('button', { name: '문제 5개 선정 완료 취소' }))
    .toBeEnabled()
  await historicalRow.getByRole('button', { name: '문제 5개 선정 완료 취소' }).click()
  await historicalRow.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  if (testInfo.project.name === 'mobile') {
    await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  }
  const inspector = page.getByLabel('선택한 역할 상세')
  await expect(inspector.locator('.next-event')).toContainText('풀이 노트 정리')
  await expect(inspector.locator('.next-event')).not.toContainText('문제 5개 선정')
  if (testInfo.project.name === 'mobile') {
    await inspector.getByRole('button', { name: '상세 닫기' }).click()
  }

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  await page.getByRole('button', { name: '바통북 미리보기' }).click()
  const preview = page.getByRole('dialog', { name: '문제 큐레이터 바통북' })
  const routineSection = preview.locator('.book-preview > section')
    .filter({ hasText: '02 · 반복하는 일' })
  await expect(routineSection).toContainText('풀이 노트 정리')
  await expect(routineSection).not.toContainText('문제 5개 선정')
  await preview.getByRole('button', { name: '미리보기 닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '풀이 노트 정리 루틴 보관' }).click()
  await expect(page.getByText('보관한 루틴 2개', { exact: true })).toBeFocused()
  await expect(page.getByRole('button', { name: '회차 만들기' })).toBeDisabled()
  await expect(page.getByText(/Asia\/Seoul · 활성 루틴 대기 중/)).toBeVisible()
  await page.getByText('보관한 루틴 2개', { exact: true }).click()
  await page.getByRole('button', { name: '풀이 노트 정리 루틴 복원' }).click()
  await expect(page.getByRole('button', { name: '풀이 노트 정리 루틴 보관' })).toBeFocused()

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-24')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(CREATED_ROUND_ID)
  expect(api.projection().rounds.find((round) => round.id === CREATED_ROUND_ID)
    ?.routineExecutions.map((execution) => execution.routineId))
    .toEqual([SECOND_ROUTINE_ID])

  const routineArchiveShelf = page.locator('.routine-archive-shelf')
  if (await routineArchiveShelf.getAttribute('open') === null) {
    await page.getByText('보관한 루틴 1개', { exact: true }).click()
  }
  const restoreButton = page.getByRole('button', { name: '문제 5개 선정 루틴 복원' })
  if (testInfo.project.name === 'mobile') {
    await expect.poll(async () => (await restoreButton.boundingBox())?.height ?? 0)
      .toBeGreaterThanOrEqual(44)
  }
  await restoreButton.click()

  const archiveCalls = api.calls.filter((call) =>
    call.method === 'PATCH' && call.path === archivePath)
  expect(archiveCalls).toHaveLength(2)
  expectScopedCall(archiveCalls[1]!, { archived: false })
  const restoredRow = page.locator('.routine-row').filter({ hasText: '문제 5개 선정' })
  await expect(restoredRow).toContainText('다음 회차부터')
  await expect(restoredRow.getByRole('button', { name: '문제 5개 선정 완료 처리' }))
    .toHaveCount(0)
  await expect(page.getByRole('button', { name: '문제 5개 선정 루틴 보관' })).toBeFocused()
})

test('@operations 회차 정보를 정정하고 보관·복원해도 실행 기록과 선택 회차를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  const executionSnapshot = structuredClone(
    api.projection().rounds
      .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions,
  )
  expect(executionSnapshot).toBeDefined()

  await page.getByRole('button', { name: '회차 수정' }).click()
  const editDialog = page.getByRole('dialog', { name: '회차 정보 수정' })
  await expect(editDialog.getByLabel('회차 이름')).toHaveValue('2회차')
  await expect(editDialog.getByLabel('모임 날짜')).toHaveValue('2026-07-17')

  await editDialog.getByLabel('회차 이름').fill('1회차')
  await editDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(editDialog.getByRole('alert'))
    .toContainText('같은 시즌에 동일한 회차 이름을 사용할 수 없습니다.')

  await editDialog.getByLabel('회차 이름').fill('심화 풀이 모임')
  await editDialog.getByLabel('모임 날짜').fill('2026-07-18')
  await editDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(editDialog).toHaveCount(0)

  const updateCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}`)
  expectScopedCall(updateCall, { name: '심화 풀이 모임', meetingDate: '2026-07-18' })
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByLabel('운영 회차').locator('option:checked')).toContainText('심화 풀이 모임')
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()

  await page.getByRole('button', { name: '심화 풀이 모임 회차 보관' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_ONE_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
  const archiveCall = await recordedCall(
    api,
    'PATCH',
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/archive`,
  )
  expectScopedCall(archiveCall, { archived: true })

  await page.getByText('보관한 회차 1개', { exact: true }).click()
  const archivedRow = page.locator('.archive-row').filter({ hasText: '심화 풀이 모임' })
  await expect(archivedRow).toContainText('1/2 완료')
  await archivedRow.getByRole('button', { name: '심화 풀이 모임 회차 복원' }).click()

  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()
  const restoreCall = api.calls.filter((call) =>
    call.method === 'PATCH' && call.path === `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/archive`,
  ).at(-1)
  expect(restoreCall).toBeDefined()
  expectScopedCall(restoreCall!, { archived: false })
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions)
    .toEqual(executionSnapshot)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await expect(page.getByLabel('운영 회차')).toHaveValue(ROUND_TWO_ID)
  await expect(page.getByLabel('운영 회차').locator('option:checked')).toContainText('심화 풀이 모임')
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()
})

test('@operations 오늘 화면에서 선택한 회차의 루틴을 완료하고 취소한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const checklist = page.getByRole('region', { name: '2회차 루틴 완료하기' })
  const completeButton = checklist.getByRole('button', { name: '풀이 노트 정리 완료 처리' })
  await expect(checklist.getByText('1/2 완료')).toBeVisible()
  await completeButton.scrollIntoViewIfNeeded()
  await expect(completeButton).toBeInViewport()
  await completeButton.click()

  await expect(checklist.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeEnabled()
  await expect(checklist.getByText('2/2 완료')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '0개의 바통이 남았어요' })).toBeVisible()

  const completionPath =
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`
  const completeCall = await recordedCall(api, 'PATCH', completionPath)
  expectScopedCall(completeCall, { completed: true })

  await checklist.getByRole('button', { name: '풀이 노트 정리 완료 취소' }).click()

  await expect(checklist.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeEnabled()
  await expect(checklist.getByText('1/2 완료')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '1개의 바통이 남았어요' })).toBeVisible()

  const completionCalls = api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === completionPath,
  )
  expect(completionCalls).toHaveLength(2)
  expectScopedCall(completionCalls[1]!, { completed: false })
})

test('@operations 완료 저장 중에는 같은 회차 관리만 잠근다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()

  api.holdNextRoutineCompletion()
  await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()

  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 상태 변경 중' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 상태 변경 중' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '2회차 회차 보관' })).toBeDisabled()

  await page.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '회차 수정' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '1회차 회차 보관' })).toBeEnabled()
  await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()
  await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeEnabled()

  api.releaseRoutineCompletion()
  await expect.poll(() => api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions
    .find((execution) => execution.id === ROUND_TWO_ROUTINE_TWO_EXECUTION_ID)?.status)
    .toBe('DONE')
  await page.getByLabel('운영 회차').selectOption(ROUND_TWO_ID)
  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeEnabled()
})

test('@operations 다른 기기의 루틴 완료 변경을 열린 화면에 자동 반영한다', async ({ page, browser }, testInfo) => {
  const api = await installApi(page)
  const peerContext = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    viewport: testInfo.project.name === 'mobile'
      ? { width: 390, height: 844 }
      : { width: 1280, height: 720 },
  })
  const peerPage = await peerContext.newPage()
  await api.attachPage(peerPage)

  try {
    await openSharedWorkspace(page)
    await openSharedWorkspace(peerPage)

    for (const clientPage of [page, peerPage]) {
      await navigation(clientPage, testInfo.project.name).getByRole('button', { name: '운영' }).click()
      await clientPage.getByLabel('운영 회차').selectOption(ROUND_ONE_ID)
      await expect(clientPage.getByRole('button', { name: '문제 5개 선정 완료 처리' })).toBeVisible()
    }

    await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()
    await expect(page.getByRole('button', { name: '문제 5개 선정 완료 취소' })).toBeVisible()
    await expect(peerPage.getByRole('button', { name: '문제 5개 선정 완료 취소' }))
      .toBeVisible({ timeout: 15_000 })
  } finally {
    await peerContext.close()
  }
})

test('@operations 오늘 화면의 루틴 완료 저장 실패를 서버 상태로 되돌리고 알린다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.failNextRoutineCompletion()
  await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()

  await expect(page.getByRole('status')).toHaveText(/완료 상태를 바꾸지 못했어요.*루틴 상태를 저장하지 못했습니다/)
  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '1개의 바통이 남았어요' })).toBeVisible()
  const failureCall = await recordedCall(
    api,
    'PATCH',
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`,
  )
  expectScopedCall(failureCall, { completed: true })
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions
    .find((execution) => execution.routineId === SECOND_ROUTINE_ID)?.status).toBe('WAITING')
})

test('@operations @handoff 완료 충돌은 공용 복구로 상대 사용자의 최신 상태를 다시 불러온다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const workspaceGetCount = () => api.calls.filter(
    (call) => call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length
  const routineCompletionPath =
    `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`
  const handoffCompletionPath = `${SCOPE_PATH}/handoff-items/${HANDOFF_TWO_ID}/completion`
  const completionPatchCount = (path: string) => api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === path,
  ).length

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  api.conflictNextRoutineCompletion(false)
  const getsBeforeRoutineConflict = workspaceGetCount()
  await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()

  await expect.poll(() => completionPatchCount(routineCompletionPath)).toBe(1)
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeRoutineConflict)
  await expect(page.getByRole('status')).toContainText('다른 구성원이 먼저 바꾼 최신 작업 공간을 불러왔어요.')
  await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 처리' })).toBeVisible()
  expect(api.projection().rounds
    .find((round) => round.id === ROUND_TWO_ID)?.routineExecutions
    .find((execution) => execution.id === ROUND_TWO_ROUTINE_TWO_EXECUTION_ID)?.status)
    .toBe('WAITING')
  expectScopedCall(await recordedCall(api, 'PATCH', routineCompletionPath), { completed: true })
  expect(completionPatchCount(routineCompletionPath)).toBe(1)

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  const handoffCheckbox = page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })
  api.conflictNextHandoffCompletion(false)
  const getsBeforeHandoffConflict = workspaceGetCount()
  await handoffCheckbox.click()

  await expect.poll(() => completionPatchCount(handoffCompletionPath)).toBe(1)
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeHandoffConflict)
  await expect(page.getByRole('status')).toContainText('다른 구성원이 먼저 바꾼 최신 작업 공간을 불러왔어요.')
  await expect(handoffCheckbox).not.toBeChecked()
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_TWO_ID)?.completed).toBe(false)
  expectScopedCall(await recordedCall(api, 'PATCH', handoffCompletionPath), { completed: true })
  expect(completionPatchCount(handoffCompletionPath)).toBe(1)
})

test('동기화 실패에도 기존 내용을 유지하고 수동으로 다시 확인한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.makeWorkspaceGetsUnavailable()
  await page.getByRole('button', { name: '지금 새로고침' }).click()

  const syncStatus = page.locator('.workspace-sync-status')
  await expect(syncStatus).toContainText('최신 내용을 확인하지 못했어요')
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()

  api.restoreWorkspaceGets()
  await page.getByRole('button', { name: '지금 새로고침' }).click()
  await expect(syncStatus).toContainText('화면 갱신')
})

test('창 포커스와 네트워크 복구 때 즉시 최신 내용을 확인한다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  const workspaceGetCount = () => api.calls.filter((call) =>
    call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length

  const initialGets = workspaceGetCount()
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect.poll(workspaceGetCount, { timeout: 3_000 }).toBeGreaterThan(initialGets)

  const getsAfterFocus = workspaceGetCount()
  await page.evaluate(() => {
    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))
  })
  await expect.poll(workspaceGetCount, { timeout: 3_000 }).toBeGreaterThan(getsAfterFocus)
})

test('접근 거부 뒤에는 retry와 focus 및 reconnect 동기화를 멈춘다', async ({ page }) => {
  const api = await installApi(page)
  const workspaceGetCount = () => api.calls.filter((call) =>
    call.method === 'GET' && call.path === `${SCOPE_PATH}/workspace`,
  ).length

  await page.goto(`${WORKSPACE_PATH}#accessKey=invalid-access-key`)
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  const deniedGets = workspaceGetCount()
  expect(deniedGets).toBeGreaterThan(0)

  await page.evaluate(() => {
    window.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))
  })
  await page.waitForTimeout(2_300)

  expect(workspaceGetCount()).toBe(deniedGets)
})

test('다른 기기에서 접근 키가 바뀌면 자동 동기화가 편집 화면을 닫는다', async ({ page }) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  api.rotateAccessKeyFromAnotherDevice()

  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' }))
    .toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('워크스페이스 접근 권한이 없습니다.')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toHaveCount(0)
})
