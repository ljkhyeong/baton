import { expect, test } from '@playwright/test'

const creationKey = process.env.BATON_FULLSTACK_CREATION_KEY

if (!creationKey) {
  throw new Error('BATON_FULLSTACK_CREATION_KEY가 필요합니다.')
}

test('빈 DB에서 파일럿을 만들고 다른 브라우저와 완료 상태를 공유한다', async ({ browser, context, page }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('풀스택 검증 스터디')
  await page.getByLabel('시즌 이름').fill('2026 파일럿 시즌')
  await page.getByLabel('시작일').fill('2026-07-01')
  await page.getByLabel('종료일').fill('2026-12-31')
  await page.getByLabel('구성원 이름').fill('박민서\n김준호')
  await page.getByLabel(/파일럿 생성 코드/).fill(creationKey)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(/\/teams\/[0-9a-f-]+\/seasons\/[0-9a-f-]+$/)
  await expect(page.locator('.workspace-switcher')).toContainText('풀스택 검증 스터디')
  const workspacePath = new URL(page.url()).pathname

  await page.locator('.sidebar').getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('질문 큐레이터')
  await roleDialog.getByLabel('이 역할이 존재하는 이유').fill('막힌 지점을 모아 다음 모임으로 연결합니다.')
  await roleDialog.getByLabel('현재 담당자').selectOption({ label: '박민서' })
  await roleDialog.getByLabel('다음 담당자').selectOption({ label: '김준호' })
  await roleDialog.getByLabel('담당 시작일').fill('2026-07-01')
  await roleDialog.getByLabel('담당 종료일').fill('2026-12-31')
  await roleDialog.getByLabel('핵심 책임').fill('질문 수집\n공통 막힘 정리')
  await roleDialog.getByLabel('위험 신호').fill('질문이 개인 메모에만 남을 수 있어요.')
  await roleDialog.getByRole('button', { name: '역할 만들기' }).click()

  const roleRow = page.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })
  await expect(roleRow).toBeVisible()
  await roleRow.click()
  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '자료 추가' }).click()
  const resourceDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await resourceDialog.getByLabel('자료 이름').fill('질문 정리 가이드')
  await resourceDialog.getByLabel('링크').fill('https://docs.example.com/questions')
  await resourceDialog.getByLabel('자료 설명').fill('질문을 분류하고 다음 모임으로 넘기는 기준입니다.')
  await resourceDialog.getByRole('button', { name: '자료 연결하기' }).click()
  await expect(inspector.getByRole('link', { name: '질문 정리 가이드 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/questions')

  await page.locator('.sidebar').getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '루틴 추가' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 루틴 만들기' })
  await routineDialog.getByLabel('루틴 이름').fill('회고 질문 준비')
  await routineDialog.getByLabel('운영 단계').selectOption({ label: '모임 전' })
  await routineDialog.getByLabel('담당 역할').selectOption({ label: '질문 큐레이터' })
  await routineDialog.getByLabel('언제까지').fill('목요일 19:00')
  await routineDialog.getByLabel('세부 설명').fill('지난 회차에서 이어갈 질문 두 개를 고릅니다.')
  await routineDialog.getByRole('button', { name: '루틴 만들기' }).click()

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('회차 이름').fill('1회차')
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-24')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 처리' })).toBeVisible()

  await page.locator('.sidebar').getByRole('button', { name: '공유' }).click()
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toMatch(/#accessKey=.+/)
  const shareUrl = await page.evaluate(() => navigator.clipboard.readText())
  expect(new URL(shareUrl).pathname).toBe(workspacePath)
  expect(new URL(shareUrl).hash).toMatch(/^#accessKey=.+/)

  const peerContext = await browser.newContext({ baseURL: new URL(page.url()).origin })
  try {
    const peerPage = await peerContext.newPage()
    await peerPage.goto(shareUrl)
    await expect(peerPage.locator('.workspace-switcher')).toContainText('풀스택 검증 스터디')
    await peerPage.locator('.sidebar').getByRole('button', { name: '역할' }).click()
    const peerRoleRow = peerPage.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })
    await expect(peerRoleRow).toBeVisible()
    await peerRoleRow.click()
    await expect(peerPage.getByLabel('선택한 역할 상세')
      .getByRole('link', { name: '질문 정리 가이드 새 창에서 열기' }))
      .toHaveAttribute('href', 'https://docs.example.com/questions')

    await page.locator('.sidebar').getByRole('button', { name: '운영' }).click()
    await peerPage.locator('.sidebar').getByRole('button', { name: '운영' }).click()
    await peerPage.getByRole('button', { name: '회고 질문 준비 완료 처리' }).click()
    await expect(peerPage.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
    await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()

    await page.reload()
    await page.locator('.sidebar').getByRole('button', { name: '운영' }).click()
    await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
  } finally {
    await peerContext.close()
  }

  expect(await page.evaluate(() => [
    'baton-roles',
    'baton-routines',
    'baton-decisions',
    'baton-handoff',
  ].map((key) => localStorage.getItem(key)))).toEqual([null, null, null, null])
})
