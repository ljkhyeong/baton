import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.goto('/')
})

test('@smoke 핵심 작업 공간을 탐색할 수 있다', async ({ page }, testInfo) => {
  await expect(page.getByRole('heading', { level: 1, name: /바통이 남았어요/ })).toBeVisible()
  const navigationName = testInfo.project.name === 'mobile' ? '모바일 주 메뉴' : '주 메뉴'
  await page.getByRole('navigation', { name: navigationName }).getByRole('button', { name: '역할' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '사람이 바뀌어도 역할은 남아요' })).toBeVisible()
})

test('@operations 반복 업무를 완료할 수 있다', async ({ page }, testInfo) => {
  const navigationName = testInfo.project.name === 'mobile' ? '모바일 주 메뉴' : '주 메뉴'
  await page.getByRole('navigation', { name: navigationName }).getByRole('button', { name: '운영' }).click()
  await page.getByRole('button', { name: '문제 5개 선정 완료 처리' }).click()
  await expect(page.getByRole('status')).toHaveText(/이번 바통을 넘겼어요/)
})

test('@memory 결정과 이유를 기록할 수 있다', async ({ page }, testInfo) => {
  const navigationName = testInfo.project.name === 'mobile' ? '모바일 주 메뉴' : '주 메뉴'
  await page.getByRole('navigation', { name: navigationName }).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()

  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('회고를 10분 먼저 시작한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('다음 액션을 정리할 시간이 자주 부족했기 때문입니다.')
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '회고를 10분 먼저 시작한다' })).toBeVisible()
})

test('@handoff 바통북을 미리 볼 수 있다', async ({ page }, testInfo) => {
  const navigationName = testInfo.project.name === 'mobile' ? '모바일 주 메뉴' : '주 메뉴'
  await page.getByRole('navigation', { name: navigationName }).getByRole('button', { name: /^바통/ }).click()
  await expect(page.getByRole('heading', { level: 1, name: '다음 사람이 헤매지 않도록' })).toBeVisible()
  await page.getByRole('button', { name: '바통북 미리보기' }).click()
  await expect(page.getByRole('dialog', { name: '문제 큐레이터 바통북' })).toBeVisible()
})

test('@responsive 모바일에서 주요 탐색 메뉴를 사용할 수 있다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', '모바일 프로젝트에서만 실행합니다.')

  await page.getByRole('navigation', { name: '모바일 주 메뉴' }).getByRole('button', { name: '운영' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '우리 팀은 이렇게 움직여요' })).toBeVisible()
})
