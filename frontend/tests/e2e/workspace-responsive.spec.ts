import { expect, test } from '@playwright/test'
import {
  ROLE_ID,
  ROUND_ONE_ID,
  ACCESS_KEY,
  WORKSPACE_PATH,
  contrastRatio,
  expectVisibleFocus,
  makeProjection,
  installApi,
  openSharedWorkspace,
  navigation,
} from './support/workspaceApiHarness'

test('@responsive 주 메뉴는 현재 화면과 작은 화면의 조작 영역을 전달한다', async ({ page }, testInfo) => {
  await installApi(page)
  await openSharedWorkspace(page)

  const primaryNavigation = navigation(page, testInfo.project.name)
  const todayButton = primaryNavigation.getByRole('button', { name: '오늘' })
  const rolesButton = primaryNavigation.getByRole('button', { name: '역할' })
  await expect(todayButton).toHaveAttribute('aria-current', 'page')
  await expect(rolesButton).not.toHaveAttribute('aria-current')

  await rolesButton.click()
  await expect(todayButton).not.toHaveAttribute('aria-current')
  await expect(rolesButton).toHaveAttribute('aria-current', 'page')

  if (testInfo.project.name !== 'mobile') return

  for (const width of [320, 375]) {
    await page.setViewportSize({ width, height: 844 })

    const topbar = page.locator('.mobile-topbar')
    const workspaceActions = [
      topbar.getByRole('button', { name: '공유' }),
      topbar.getByRole('button', { name: '키 관리' }),
    ]
    const navigationButtons = await primaryNavigation.getByRole('button').all()

    for (const button of [...workspaceActions, ...navigationButtons]) {
      const label = await button.textContent()
      const size = await button.evaluate((element) => ({
        height: element.getBoundingClientRect().height,
      }))
      expect(size.height, `${width}px 화면의 ${label} 조작 높이`).toBeGreaterThanOrEqual(44)
    }

    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  }
})

test('@responsive 390x844에서 구성원 관리 동작과 focus 복귀를 유지한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const opener = page.getByRole('button', { name: '구성원 관리' })
  await opener.click()
  const managementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(managementDialog).toBeInViewport()

  const editButton = managementDialog.getByRole('button', { name: '박민서 이름 수정' })
  const deactivateButton = managementDialog.getByRole('button', { name: '박민서 활동 종료' })
  await expect.poll(async () => (await editButton.boundingBox())?.height ?? 0)
    .toBeGreaterThanOrEqual(44)
  await expect.poll(async () => (await deactivateButton.boundingBox())?.height ?? 0)
    .toBeGreaterThanOrEqual(44)

  await editButton.click()
  const editDialog = page.getByRole('dialog', { name: '구성원 이름 수정' })
  await expect(editDialog.getByLabel('구성원 이름')).toBeFocused()
  await editDialog.getByRole('button', { name: '취소' }).click()
  await expect.poll(() => managementDialog.evaluate((element) =>
    element.contains(document.activeElement))).toBe(true)

  await managementDialog.getByRole('button', { name: '구성원 추가' }).click()
  const createDialog = page.getByRole('dialog', { name: '구성원 추가' })
  await expect(createDialog.getByLabel('구성원 이름')).toBeFocused()
  await createDialog.getByRole('button', { name: '취소' }).click()
  await expect.poll(() => managementDialog.evaluate((element) =>
    element.contains(document.activeElement))).toBe(true)

  await page.keyboard.press('Escape')
  await expect(opener).toBeFocused()
})

test('@responsive 모바일 역할 상세는 닫힌 대화상자 접근을 차단하고 Escape 뒤 역할 행으로 돌아간다', async ({ page }, testInfo) => {
  test.skip(!['mobile', 'webkit'].includes(testInfo.project.name), '모바일과 WebKit 프로젝트에서 실행합니다.')
  await page.setViewportSize({ width: 390, height: 844 })
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const inspector = page.locator('.inspector')
  const hiddenClose = inspector.locator('.inspector-close')
  const opener = page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' })

  await expect(opener).toHaveAccessibleName(/역할 상세 열기/)
  await expect(inspector).toHaveJSProperty('open', false)
  expect(await hiddenClose.evaluate((element: HTMLElement) => {
    element.focus()
    return document.activeElement === element
  })).toBe(false)

  await opener.click()
  const drawer = page.getByRole('dialog', { name: /선택한 역할 상세: 문제 큐레이터/ })
  const close = drawer.getByRole('button', { name: '상세 닫기' })
  await expect(drawer).toHaveJSProperty('open', true)
  await expect(close).toBeFocused()
  expect(await opener.evaluate((element: HTMLElement) => {
    element.focus()
    return document.activeElement === element
  })).toBe(false)

  const addResource = drawer.getByRole('button', { name: '자료 추가' })
  await addResource.click()
  const resourceDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await expect(resourceDialog.getByLabel('자료 이름')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(resourceDialog).toHaveCount(0)
  await expect(drawer).toBeVisible()
  await expect.poll(() => drawer.evaluate((element) =>
    element.contains(document.activeElement))).toBe(true)

  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(inspector).toHaveJSProperty('open', false)
  await expect(opener).toBeFocused()
})

test('@responsive 보조 문구와 경고 및 키보드 focus 대비를 유지한다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.rounds.find((round) => round.id === ROUND_ONE_ID)!.archivedAt = '2026-07-21T12:00:00Z'
  await installApi(page, initialProjection)
  await openSharedWorkspace(page)

  const palette = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    const color = (name: string) => style.getPropertyValue(name).trim()
    return {
      canvas: color('--canvas'),
      faint: color('--faint'),
      focusRing: color('--focus-ring'),
      muted: color('--muted'),
      nav: color('--nav'),
      warning: color('--warning'),
      warningSoft: color('--warning-soft'),
    }
  })

  expect(contrastRatio(palette.faint, palette.canvas)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.muted, palette.canvas)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.muted, palette.warningSoft)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.warning, palette.warningSoft)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(palette.focusRing, palette.canvas)).toBeGreaterThanOrEqual(3)
  expect(contrastRatio(palette.focusRing, palette.nav)).toBeGreaterThanOrEqual(3)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  const archiveSummary = page.getByText('보관한 회차 1개', { exact: true })
  await archiveSummary.focus()
  await page.keyboard.press('Tab')
  await page.keyboard.press('Shift+Tab')
  await expect(archiveSummary).toBeFocused()
  await expectVisibleFocus(archiveSummary, palette.canvas)

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^바통/ }).click()
  const selectedRoleTab = page.getByRole('tab', { selected: true })
  await selectedRoleTab.focus()
  await page.keyboard.press('Tab')

  const tabPanel = page.getByRole('tabpanel')
  await expect(tabPanel).toBeFocused()
  await expectVisibleFocus(tabPanel, palette.canvas)

  const prepareButton = tabPanel.getByRole('button', { name: '바통 준비 시작' })
  if (testInfo.project.name === 'webkit') {
    await prepareButton.focus()
  } else {
    await page.keyboard.press('Tab')
  }
  await expect(prepareButton).toBeFocused()
  await expectVisibleFocus(prepareButton, palette.canvas)

  const checkbox = tabPanel.getByRole('checkbox', { name: '역할의 한 줄 목적' })
  if (testInfo.project.name === 'webkit') {
    await checkbox.focus()
  } else {
    await page.keyboard.press('Tab')
  }
  await expect(checkbox).toBeFocused()
  const visibleCheckbox = checkbox.locator('xpath=following-sibling::span[contains(@class, "custom-check")]')
  await expectVisibleFocus(visibleCheckbox, palette.canvas)
})

test('@responsive 역할 상세는 desktop 보조 패널과 1100px drawer 경계를 구분한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '데스크톱 프로젝트에서 breakpoint를 검증합니다.')
  await installApi(page)
  await openSharedWorkspace(page)

  const inspector = page.locator('.inspector')
  const addResource = inspector.getByRole('button', { name: '자료 추가' })
  await expect(inspector).toHaveJSProperty('tagName', 'ASIDE')
  await addResource.focus()
  await expect(addResource).toBeFocused()

  await page.setViewportSize({ width: 1100, height: 800 })
  await expect(inspector).toHaveJSProperty('tagName', 'DIALOG')
  await expect(inspector).toHaveJSProperty('open', false)
  await expect(page.locator('.main-surface')).toBeFocused()

  await page.locator('.sidebar').getByRole('button', { name: '역할' }).click()
  const opener = page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' })
  await opener.click()
  const drawer = page.getByRole('dialog', { name: /선택한 역할 상세: 문제 큐레이터/ })
  await expect(drawer.getByRole('button', { name: '상세 닫기' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(opener).toBeFocused()

  await opener.click()
  const reopenedDrawer = page.getByRole('dialog', { name: /선택한 역할 상세: 문제 큐레이터/ })
  await reopenedDrawer.getByRole('button', { name: '자료 추가' }).focus()
  await page.setViewportSize({ width: 1280, height: 800 })
  await expect(reopenedDrawer).toHaveCount(0)
  await expect(inspector).toHaveJSProperty('tagName', 'ASIDE')
  await expect(page.locator('.main-surface')).toBeFocused()
})

test('@responsive 390x844에서 루틴 추가와 완료를 수행할 수 있다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '루틴 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await expect(dialog).toBeInViewport()
  await dialog.getByLabel('루틴 이름').fill('다음 문제 예고')
  await dialog.getByLabel('운영 단계').selectOption('AFTER')
  await dialog.getByLabel('담당 역할').selectOption(ROLE_ID)
  await dialog.getByLabel('언제까지').fill('금요일 20:00')
  await dialog.getByLabel('세부 설명').fill('다음 주 주제를 한 줄로 공유합니다.')
  await dialog.getByRole('button', { name: '루틴 만들기' }).click()
  await expect(page.locator('.routine-row').filter({ hasText: '다음 문제 예고' })).toContainText('다음 회차부터')
  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await expect(roundDialog).toBeInViewport()
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-31')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘' }).click()

  const todayChecklist = page.getByRole('region', { name: '3회차 루틴 완료하기' })
  const todayToggle = todayChecklist.getByRole('button', { name: '다음 문제 예고 완료 처리' })
  await todayToggle.scrollIntoViewIfNeeded()
  await expect(todayToggle).toBeInViewport()
  await todayToggle.click()
  await expect(todayChecklist.getByRole('button', { name: '다음 문제 예고 완료 취소' })).toBeVisible()
})

test('@smoke 일시적인 조회 오류에서 다시 시도할 수 있다', async ({ page }) => {
  const api = await installApi(page)
  api.makeWorkspaceGetsUnavailable()
  await page.goto(`${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`)
  await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
  api.restoreWorkspaceGets()
  await page.getByRole('button', { name: '다시 시도하기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
})
