import { expect, test } from '@playwright/test'

const creationKey = process.env.BATON_FULLSTACK_CREATION_KEY

if (!creationKey) {
  throw new Error('BATON_FULLSTACK_CREATION_KEY가 필요합니다.')
}

test('빈 DB에서 파일럿 기록과 완료 상태를 만들고 다른 브라우저와 공유한다', async ({ browser, context, page }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/')

  await page.getByLabel('팀 이름').fill('풀스택 검증 스터디')
  await page.getByLabel('시즌 이름').fill('2026 파일럿 시즌')
  await page.getByLabel('시작일').fill('2026-07-01')
  await page.getByLabel('종료일').fill('2026-12-31')
  await page.getByLabel('구성원 이름').fill('박민서\n김준호')
  await page.getByLabel(/작업 공간 생성 코드/).fill(creationKey)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()

  await expect(page).toHaveURL(/\/teams\/[0-9a-f-]+\/seasons\/[0-9a-f-]+$/)
  await expect(page.locator('.workspace-switcher')).toContainText('풀스택 검증 스터디')
  const workspacePath = new URL(page.url()).pathname

  await page.locator('.sidebar').getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '구성원 관리' }).click()
  const memberManagementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await memberManagementDialog.getByRole('button', { name: '구성원 추가' }).click()
  const memberDialog = page.getByRole('dialog', { name: '구성원 추가' })
  await memberDialog.getByLabel('구성원 이름').fill('이서준')
  await memberDialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(page.getByRole('status')).toContainText('이서준님을 팀 구성원으로 추가했어요.')

  await page.getByRole('button', { name: '구성원 관리' }).click()
  await memberManagementDialog.getByRole('button', { name: '이서준 이름 수정' }).click()
  const memberEditDialog = page.getByRole('dialog', { name: '구성원 이름 수정' })
  await memberEditDialog.getByLabel('구성원 이름').fill('이서준(운영)')
  await memberEditDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(memberManagementDialog.getByText('이서준(운영)', { exact: true })).toBeVisible()

  await memberManagementDialog
    .getByRole('button', { name: '이서준(운영) 활동 종료' })
    .click()
  await memberManagementDialog
    .getByRole('button', { name: '이서준(운영) 활동 재개' })
    .click()
  await expect(memberManagementDialog.getByRole('list', { name: '팀 구성원' }))
    .toContainText('이서준(운영)')
  await expect(memberManagementDialog.getByRole('button', {
    name: '이서준(운영) 활동 종료',
  })).toBeFocused()
  await memberManagementDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('질문 큐레이터')
  await roleDialog.getByLabel('역할 목적').fill('막힌 지점을 모아 다음 모임으로 연결합니다.')
  await roleDialog.getByLabel('현재 담당자').selectOption({ label: '박민서' })
  await roleDialog.getByLabel('다음 담당자').selectOption({ label: '이서준(운영)' })
  await roleDialog.getByLabel('담당 시작일').fill('2026-07-01')
  await roleDialog.getByLabel('담당 종료일').fill('2026-12-31')
  await roleDialog.getByLabel('담당 업무').fill('질문 수집\n공통 막힘 정리')
  await roleDialog.getByLabel('주의사항').fill('질문이 개인 메모에만 남을 수 있어요.')
  await roleDialog.getByRole('button', { name: '역할 만들기' }).click()

  const roleRow = page.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })
  await expect(roleRow).toBeVisible()
  await roleRow.click()
  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '자료 추가' }).click()
  const resourceDialog = page.getByRole('dialog', { name: '참고 자료 추가' })
  await resourceDialog.getByLabel('자료 이름').fill('질문 정리 가이드')
  await resourceDialog.getByLabel('링크').fill('https://docs.example.com/questions')
  await resourceDialog.getByLabel('자료 설명').fill('질문을 분류하고 다음 모임으로 넘기는 기준입니다.')
  await resourceDialog.getByRole('button', { name: '자료 추가' }).click()
  await expect(inspector.getByRole('link', { name: '질문 정리 가이드 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/questions')

  await page.locator('.sidebar').getByRole('button', { name: '일정' }).click()
  await page.getByRole('button', { name: '반복 업무 추가' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 업무 만들기' })
  await routineDialog.getByLabel('반복 업무 이름').fill('회고 질문 준비')
  await routineDialog.getByLabel('업무 시점').selectOption({ label: '모임 전' })
  await routineDialog.getByLabel('담당 역할').selectOption({ label: '질문 큐레이터' })
  await routineDialog.getByLabel('기한 설명').fill('목요일 19:00')
  await routineDialog.getByLabel('세부 설명').fill('지난 회차에서 이어갈 질문 두 개를 고릅니다.')
  await routineDialog.getByRole('button', { name: '반복 업무 만들기' }).click()

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('회차 이름').fill('1회차')
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-24')
  await roundDialog.getByRole('button', { name: '회차 만들기' }).click()
  await page.getByRole('button', { name: '회고 질문 준비 완료 처리' }).click()
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()

  await page.getByRole('button', { name: '회차 수정' }).click()
  const roundEditDialog = page.getByRole('dialog', { name: '회차 정보 수정' })
  await roundEditDialog.getByLabel('회차 이름').fill('첫 파일럿 모임')
  await roundEditDialog.getByLabel('모임 날짜').fill('2026-07-25')
  await roundEditDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(page.getByLabel('회차', { exact: true }).locator('option:checked')).toContainText('첫 파일럿 모임')

  await page.getByRole('button', { name: '첫 파일럿 모임 회차 보관' }).click()
  await expect(page.getByLabel('회차', { exact: true })).toHaveValue('')
  await page.getByText('보관한 회차 1개', { exact: true }).click()
  await page.getByRole('button', { name: '첫 파일럿 모임 회차 복원' }).click()
  await expect(page.getByLabel('회차', { exact: true }).locator('option:checked')).toContainText('첫 파일럿 모임')
  await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()

  await page.locator('.sidebar').getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기', exact: true }).click()
  const decisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await decisionDialog.getByLabel('무엇을 결정했나요?').fill('질문 정리를 모임 전날에 마친다')
  await decisionDialog.getByLabel('왜 이 선택을 했나요?').fill('모임 직전에 질문을 모으면 비슷한 문제를 묶을 시간이 부족합니다.')
  await decisionDialog.getByLabel('검토한 대안').fill('모임 시간을 늘린다')
  await decisionDialog.getByLabel('작성자').selectOption({ label: '박민서' })
  await decisionDialog.getByRole('checkbox', { name: '질문 큐레이터' }).check()
  await decisionDialog.getByRole('button', { name: '결정 기록하기' }).click()

  const originalDecisionTitle = '질문 정리를 모임 전날에 마친다'
  const revisedDecisionTitle = '질문 정리를 모임 이틀 전에 마친다'
  await expect(page.getByRole('heading', { name: originalDecisionTitle })).toBeVisible()
  await page.getByRole('button', { name: `${originalDecisionTitle} 수정` }).click()
  const decisionEditDialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  await decisionEditDialog.getByLabel('무엇을 결정했나요?').fill(revisedDecisionTitle)
  await decisionEditDialog.getByLabel('왜 이 선택을 했나요?').fill('질문을 미리 분류하고 답변 담당을 정할 시간이 필요합니다.')
  await decisionEditDialog.getByLabel('작성자').selectOption({ label: '김준호' })
  await decisionEditDialog.getByRole('checkbox', { name: '질문 큐레이터' }).check()
  await decisionEditDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(page.getByRole('heading', { name: revisedDecisionTitle })).toBeVisible()

  await page.getByRole('button', { name: `${revisedDecisionTitle} 보관` }).click()
  await expect(page.getByRole('heading', { name: revisedDecisionTitle })).toHaveCount(0)
  const decisionArchiveSummary = page.getByText('보관한 결정 1개', { exact: true })
  await decisionArchiveSummary.click()
  await page.getByRole('button', { name: `${revisedDecisionTitle} 복원` }).click()
  await expect(page.getByRole('heading', { name: revisedDecisionTitle })).toBeVisible()

  await page.reload()
  await page.locator('.sidebar').getByRole('button', { name: '기록' }).click()
  const reloadedDecision = page.locator('.decision-entry').filter({ hasText: revisedDecisionTitle })
  await expect(reloadedDecision.getByRole('heading', { name: revisedDecisionTitle })).toBeVisible()
  await expect(reloadedDecision.getByText('김준호', { exact: true })).toBeVisible()

  await page.locator('.sidebar').getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가', exact: true }).click()
  const handoffDialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await handoffDialog.getByLabel('역할').selectOption({ label: '질문 큐레이터' })
  await handoffDialog.getByLabel('남길 내용').fill('질문 분류 기준 공유')
  await handoffDialog.getByLabel('항목 종류').selectOption({ label: '조언' })
  await handoffDialog.getByRole('button', { name: '항목 추가하기' }).click()

  const originalHandoffLabel = '질문 분류 기준 공유'
  const revisedHandoffLabel = '질문 분류 기준과 예외 공유'
  const createdHandoff = page.getByRole('checkbox', { name: originalHandoffLabel })
  await expect(createdHandoff).not.toBeChecked()
  await createdHandoff.click()
  await expect(createdHandoff).toBeChecked()
  await page.getByRole('button', { name: `${originalHandoffLabel} 수정` }).click()

  const handoffEditDialog = page.getByRole('dialog', { name: '인수인계 항목 수정' })
  await handoffEditDialog.getByLabel('남길 내용').fill(revisedHandoffLabel)
  await handoffEditDialog.getByLabel('항목 종류').selectOption({ label: '자료' })
  await handoffEditDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(page.getByRole('checkbox', { name: revisedHandoffLabel })).toBeChecked()

  await page.getByRole('button', { name: `${revisedHandoffLabel} 보관` }).click()
  await expect(page.getByRole('checkbox', { name: revisedHandoffLabel })).toHaveCount(0)
  const handoffArchiveSummary = page.getByText('보관한 체크리스트 항목 1개', { exact: true })
  await handoffArchiveSummary.click()
  await page.getByRole('button', { name: `${revisedHandoffLabel} 복원` }).click()
  await expect(page.getByRole('checkbox', { name: revisedHandoffLabel })).toBeChecked()

  await page.reload()
  await page.locator('.sidebar').getByRole('button', { name: /^인수인계/ }).click()
  await expect(page.getByRole('checkbox', { name: revisedHandoffLabel })).toBeChecked()

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
    await expect(peerRoleRow).toContainText('이서준')
    await peerRoleRow.click()
    await expect(peerPage.getByLabel('선택한 역할 상세')
      .getByRole('link', { name: '질문 정리 가이드 새 창에서 열기' }))
      .toHaveAttribute('href', 'https://docs.example.com/questions')

    await peerPage.locator('.sidebar').getByRole('button', { name: '기록' }).click()
    await expect(peerPage.getByRole('heading', { name: revisedDecisionTitle })).toBeVisible()
    await peerPage.locator('.sidebar').getByRole('button', { name: /^인수인계/ }).click()
    await expect(peerPage.getByRole('checkbox', { name: revisedHandoffLabel })).toBeChecked()

    await page.locator('.sidebar').getByRole('button', { name: '일정' }).click()
    await peerPage.locator('.sidebar').getByRole('button', { name: '일정' }).click()
    await expect(peerPage.getByLabel('회차', { exact: true }).locator('option:checked')).toContainText('첫 파일럿 모임')
    await expect(peerPage.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
    await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()

    await peerPage.getByRole('button', { name: '회고 질문 준비 완료 취소' }).click()
    await expect(peerPage.getByRole('button', { name: '회고 질문 준비 완료 처리' })).toBeVisible()
    await expect(page.getByRole('button', { name: '회고 질문 준비 완료 처리' }))
      .toBeVisible({ timeout: 15_000 })

    await peerPage.getByRole('button', { name: '회고 질문 준비 완료 처리' }).click()
    await expect(peerPage.getByRole('button', { name: '회고 질문 준비 완료 취소' })).toBeVisible()
    await expect(page.getByRole('button', { name: '회고 질문 준비 완료 취소' }))
      .toBeVisible({ timeout: 15_000 })
  } finally {
    await peerContext.close()
  }

  const [, , teamId, , sourceSeasonId] = workspacePath.split('/')
  const accessKeyBeforeSeasonChange = await page.evaluate(
    (currentTeamId) => localStorage.getItem(`baton-access-key:${currentTeamId}`),
    teamId,
  )
  expect(accessKeyBeforeSeasonChange).toBeTruthy()

  await page.locator('.workspace-switcher').click()
  const seasonSwitcherDialog = page.getByRole('dialog', {
    name: '풀스택 검증 스터디 시즌',
  })
  await seasonSwitcherDialog.getByRole('button', { name: '다음 시즌 시작' }).click()

  const nextSeasonDialog = page.getByRole('dialog', { name: '다음 시즌 시작' })
  await expect(nextSeasonDialog.getByRole('checkbox', {
    name: /^질문 큐레이터/,
  })).toBeChecked()
  await expect(nextSeasonDialog.getByRole('checkbox', {
    name: /^회고 질문 준비/,
  })).toBeChecked()
  await nextSeasonDialog.getByLabel('다음 시즌 이름').fill('2027 파일럿 시즌')
  await nextSeasonDialog.getByRole('button', {
    name: '현재 시즌 종료하고 만들기',
  }).click()

  await expect(page).toHaveURL(new RegExp(
    `/teams/${teamId}/seasons/(?!${sourceSeasonId}$)[0-9a-f-]+$`,
  ))
  const nextSeasonPath = new URL(page.url()).pathname
  expect(nextSeasonPath).not.toBe(workspacePath)
  expect(await page.evaluate(
    (currentTeamId) => localStorage.getItem(`baton-access-key:${currentTeamId}`),
    teamId,
  )).toBe(accessKeyBeforeSeasonChange)

  await page.locator('.sidebar').getByRole('button', { name: '역할' }).click()
  const copiedRoleRow = page.locator('.role-row-open').filter({
    hasText: '질문 큐레이터',
  })
  await expect(copiedRoleRow).toBeVisible()
  await expect(copiedRoleRow).toContainText('담당자 미정')

  await page.locator('.sidebar').getByRole('button', { name: '일정' }).click()
  await expect(page.getByText('회고 질문 준비', { exact: true }).first()).toBeVisible()
  await expect(page.getByLabel('회차', { exact: true })).toHaveValue('')

  await page.locator('.workspace-switcher').click()
  await page.getByRole('dialog', {
    name: '풀스택 검증 스터디 시즌',
  }).getByRole('button', { name: /2026 파일럿 시즌/ }).click()

  await expect(page).toHaveURL(workspacePath)
  await expect(page.getByText('이 시즌은 읽기 전용입니다.')).toBeVisible()
  await page.locator('.sidebar').getByRole('button', { name: '일정' }).click()
  await expect(page.getByLabel('회차', { exact: true }).locator('option:checked'))
    .toContainText('첫 파일럿 모임')
  await expect(page.getByRole('button', {
    name: '회고 질문 준비 완료 취소',
  })).toBeDisabled()
  await expect(page.getByRole('button', { name: '반복 업무 추가' })).toBeDisabled()

  await page.locator('.sidebar').getByRole('button', { name: '기록' }).click()
  await expect(page.getByRole('heading', { name: revisedDecisionTitle })).toBeVisible()
  await expect(page.getByRole('button', {
    name: `${revisedDecisionTitle} 수정`,
  })).toBeDisabled()

  expect(await page.evaluate(() => [
    'baton-roles',
    'baton-routines',
    'baton-decisions',
    'baton-handoff',
  ].map((key) => localStorage.getItem(key)))).toEqual([null, null, null, null])
})


test('시작 템플릿으로 만든 역할과 반복 업무를 실제 DB에서 다시 불러온다', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('템플릿 선택').selectOption('STUDY_V1')
  await page.getByLabel('팀 이름').fill('템플릿 풀스택 스터디')
  await page.getByLabel('시즌 이름').fill('템플릿 첫 시즌')
  await page.getByLabel('시작일').fill('2026-07-01')
  await page.getByLabel('종료일').fill('2026-12-31')
  await page.getByLabel('구성원 이름').fill('박민서')
  await page.getByLabel(/작업 공간 생성 코드/).fill(creationKey)
  await page.getByRole('button', { name: '작업 공간 만들기' }).click()
  await expect(page).toHaveURL(/\/teams\/[0-9a-f-]+\/seasons\/[0-9a-f-]+$/)
  await page.reload()
  await page.locator('.sidebar').getByRole('button', { name: '역할', exact: true }).click()
  await expect(page.locator('.role-row-open')).toHaveCount(3)
  await expect(page.locator('.role-row-open').filter({ hasText: '학습 준비 담당' })).toBeVisible()
  await page.locator('.sidebar').getByRole('button', { name: '일정', exact: true }).click()
  await expect(page.getByText('학습 자료 준비', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('회고와 결정 정리', { exact: true }).first()).toBeVisible()
})
