import { expect, test } from '@playwright/test'
import type { Locator } from '@playwright/test'
import type {
  CreateMemberRequest,
} from '../../src/features/workspace/types'
import type { ContentCreationOperation } from '../../src/features/workspace/pendingContentCreation'
import {
  TEAM_ID,
  SEASON_ID,
  MEMBER_ONE_ID,
  MEMBER_TWO_ID,
  MEMBER_THREE_ID,
  ROLE_ID,
  ROUTINE_ID,
  ACCESS_KEY,
  WORKSPACE_PATH,
  SCOPE_PATH,
  CONTENT_CREATION_PATHS,
  PENDING_CONTENT_CREATION_STORAGE_PREFIX,
  installApi,
  openSharedWorkspace,
  openMemberCreationDialog,
  navigation,
  blockContentCreationStorage,
  failNextJournalCleanup,
  CONTENT_CREATION_CLEANUP_RELEASE_KEY,
  blockContentCreationCleanupUntilReleased,
  CONTENT_CREATION_GUARD_FAILURE_RELEASE_KEY,
  failContentCreationMarkerAndCleanupUntilReleased,
  pendingContentCreationEntries,
  recordedCall,
  expectScopedCall,
  expectPendingCreationDialogLocked,
} from './support/workspaceApiHarness'

test('@smoke 기존 팀에 구성원을 추가하고 중복과 응답 유실을 안전하게 처리한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const memberPath = `${SCOPE_PATH}/members`
  const openMemberDialog = async () => {
    return openMemberCreationDialog(page)
  }

  const dialog = await openMemberDialog()
  const nameInput = dialog.getByLabel('구성원 이름')
  await expect(nameInput).toBeFocused()
  await nameInput.fill(' 박민서 ')
  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText(
    '이미 등록된 구성원 이름입니다. 같은 이름이면 구분할 별칭을 붙여 주세요.',
  )
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === memberPath))
    .toHaveLength(0)

  api.rejectNextMemberAsConflict()
  await nameInput.fill('서버 충돌 구성원')
  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText(
    '이미 등록된 구성원 이름입니다. 같은 이름이면 구분할 별칭을 붙여 주세요.',
  )
  const conflictCall = await recordedCall(api, 'POST', memberPath)
  expectScopedCall(conflictCall, { name: '서버 충돌 구성원' })

  api.commitNextContentCreationThenTimeout('member')
  await nameInput.fill('이서준(응답 복구)')
  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('중복으로 만들지 않고 저장 여부를 확인합니다.')
  await expect(dialog.getByText(/이전에 저장 결과를 확인하지 못한 요청/)).toHaveCount(0)

  await dialog.getByRole('button', { name: '구성원 추가하기' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.locator('.toast[role="status"]')).toContainText(
    '이서준(응답 복구)님을 팀 구성원으로 추가했어요.',
  )

  const replayCalls = api.calls.filter(
    (call) => call.method === 'POST'
      && call.path === memberPath
      && (call.body as CreateMemberRequest | undefined)?.name === '이서준(응답 복구)',
  )
  expect(replayCalls).toHaveLength(2)
  expect(replayCalls[0]?.headers['idempotency-key']).toBe(
    replayCalls[1]?.headers['idempotency-key'],
  )
  expect(api.projection().members.filter((member) => member.name === '이서준(응답 복구)'))
    .toHaveLength(1)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await expect(roleDialog.getByLabel('현재 담당자').getByRole('option', {
    name: '이서준(응답 복구)',
  })).toHaveCount(1)
})

test('구성원 표시 이름과 활동 상태를 관리하고 기존 기록만 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const manageMembersButton = page.getByRole('button', { name: '구성원 관리' })
  await manageMembersButton.click()
  const managementDialog = page.getByRole('dialog', { name: '구성원 관리' })
  await expect(managementDialog.getByRole('list', { name: '팀 구성원' }))
    .toContainText('박민서')

  await managementDialog.getByRole('button', { name: '박민서 이름 수정' }).click()
  const editDialog = page.getByRole('dialog', { name: '구성원 이름 수정' })
  await expect(editDialog.getByLabel('구성원 이름')).toHaveValue('박민서')
  await editDialog.getByLabel('구성원 이름').fill('박민서(리드)')
  await editDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(managementDialog).toBeVisible()
  await expect(managementDialog.getByText('박민서(리드)', { exact: true })).toBeVisible()
  expectScopedCall(
    await recordedCall(api, 'PUT', `${SCOPE_PATH}/members/${MEMBER_ONE_ID}`),
    { name: '박민서(리드)' },
  )

  const deactivateButton = managementDialog
    .getByRole('button', { name: '박민서(리드) 활동 종료' })
  await deactivateButton.focus()
  await deactivateButton.press('Enter')
  await expect(page.locator('.toast[role="status"]')).toContainText(
    '박민서(리드)님의 활동을 종료했어요.',
  )
  expectScopedCall(
    await recordedCall(
      api,
      'PATCH',
      `${SCOPE_PATH}/members/${MEMBER_ONE_ID}/deactivation`,
    ),
    { deactivated: true },
  )

  const reactivateButton = managementDialog
    .getByRole('button', { name: '박민서(리드) 활동 재개' })
  await expect(reactivateButton).toBeFocused()
  await expect(managementDialog.getByRole('list', { name: '팀 구성원' }))
    .toContainText('활동 종료')
  await managementDialog.getByRole('button', { name: '닫기' }).click()

  const roleRow = page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' })
  await expect(roleRow).toContainText('박민서(리드) · 활동 종료')
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleEditDialog = page.getByRole('dialog', { name: '역할 수정' })
  const retainedOwner = roleEditDialog.getByLabel('현재 담당자').getByRole('option', {
    name: '박민서(리드) (활동 종료 · 기존 선택)',
  })
  await expect(roleEditDialog.getByLabel('현재 담당자')).toHaveValue(MEMBER_ONE_ID)
  await expect(retainedOwner).toHaveAttribute('disabled', '')
  await roleEditDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '역할 추가' }).click()
  const newRoleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await expect(newRoleDialog.getByLabel('현재 담당자').getByRole('option', {
    name: /박민서\(리드\)/,
  })).toHaveCount(0)
  await newRoleDialog.getByRole('button', { name: '닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  const decisionTitle = '한 회차의 문제 수를 5개로 정한다'
  await expect(page.getByRole('article').filter({
    has: page.getByRole('heading', { name: decisionTitle }),
  }).getByText('박민서(리드)', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: `${decisionTitle} 수정` }).click()
  const decisionEditDialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  const retainedAuthor = decisionEditDialog.getByLabel('작성자').getByRole('option', {
    name: '박민서(리드) (활동 종료 · 기존 작성자)',
  })
  await expect(decisionEditDialog.getByLabel('작성자')).toHaveValue(MEMBER_ONE_ID)
  await expect(retainedAuthor).toHaveAttribute('disabled', '')
  await decisionEditDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '결정 남기기', exact: true }).click()
  const newDecisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await expect(newDecisionDialog.getByLabel('작성자').getByRole('option', {
    name: /박민서\(리드\)/,
  })).toHaveCount(0)
  await newDecisionDialog.getByRole('button', { name: '닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await manageMembersButton.click()
  const reactivateMemberButton = managementDialog.getByRole('button', {
    name: '박민서(리드) 활동 재개',
  })
  await reactivateMemberButton.focus()
  await reactivateMemberButton.press('Enter')
  await expect(page.locator('.toast[role="status"]')).toContainText('박민서(리드)님의 활동을 재개했어요.')
  await expect(managementDialog.getByRole('button', {
    name: '박민서(리드) 활동 종료',
  })).toBeFocused()
  await managementDialog.getByRole('button', { name: '닫기' }).click()

  await page.getByRole('button', { name: '역할 추가' }).click()
  await expect(page.getByRole('dialog', { name: '새 역할 만들기' })
    .getByLabel('현재 담당자')
    .getByRole('option', { name: '박민서(리드)' })).toBeEnabled()
})

test('@smoke 서버 작업 공간에서 역할을 만들고 reload 후에도 유지한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.reject(new Error('denied')) },
    })
  })
  await openSharedWorkspace(page)
  expectScopedCall(await recordedCall(api, 'GET', `${SCOPE_PATH}/workspace`))

  const shareButton = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar').getByRole('button', { name: '공유' })
    : page.locator('.sidebar').getByRole('button', { name: '공유' })
  await shareButton.focus()
  await shareButton.press('Enter')
  await expect(page.locator('.toast[role="status"]')).toHaveText(/직접 복사할 링크를 열었어요/)
  const shareDialog = page.getByRole('dialog', { name: '공유 링크 직접 복사' })
  const shareLink = shareDialog.getByLabel('공유 링크')
  const expectedShareUrl = `${new URL(page.url()).origin}${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`
  await expect(shareLink).toHaveValue(expectedShareUrl)
  await expect(shareLink).toBeFocused()
  expect(await shareLink.evaluate((input: HTMLInputElement) => [input.selectionStart, input.selectionEnd])).toEqual([0, expectedShareUrl.length])
  await shareDialog.getByRole('button', { name: '확인' }).click()
  await expect(shareButton).toBeFocused()

  const manageAccessButton = testInfo.project.name === 'mobile'
    ? page.locator('.mobile-topbar').getByRole('button', { name: '링크 관리' })
    : page.locator('.sidebar').getByRole('button', { name: '링크 관리' })
  await manageAccessButton.focus()
  await manageAccessButton.press('Enter')
  const accessKeyDialog = page.getByRole('dialog', { name: '공유 링크 관리' })
  await expect.poll(() => accessKeyDialog.evaluate((element) =>
    element.contains(document.activeElement))).toBe(true)
  await accessKeyDialog.getByRole('button', { name: '현재 링크 복사' }).click()
  const chainedShareDialog = page.getByRole('dialog', { name: '공유 링크 직접 복사' })
  await expect(chainedShareDialog.getByLabel('공유 링크')).toBeFocused()
  await chainedShareDialog.getByRole('button', { name: '확인' }).click()
  await expect(manageAccessButton).toBeFocused()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await dialog.getByLabel('역할 이름').fill('질문 큐레이터')
  await dialog.getByLabel('역할 목적').fill('막힌 지점을 모아 다음 세션에서 함께 풉니다.')
  await dialog.getByLabel('현재 담당자').selectOption(MEMBER_ONE_ID)
  await dialog.getByLabel('다음 담당자').selectOption(MEMBER_TWO_ID)
  await dialog.getByLabel('담당 시작일').fill('2026-07-20')
  await dialog.getByLabel('담당 종료일').fill('2026-09-17')
  await dialog.getByLabel('담당 업무').fill('질문 수집\n공통 막힘 정리')
  await dialog.getByLabel('주의사항').fill('질문 목록이 개인 메모에만 남을 수 있어요.')
  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(page.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })).toBeVisible()
  const roleCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  expectScopedCall(roleCall, {
    name: '질문 큐레이터',
    purpose: '막힌 지점을 모아 다음 세션에서 함께 풉니다.',
    currentMemberId: MEMBER_ONE_ID,
    nextMemberId: MEMBER_TWO_ID,
    assignmentStartDate: '2026-07-20',
    assignmentEndDate: '2026-09-17',
    responsibilities: ['질문 수집', '공통 막힘 정리'],
    risk: '질문 목록이 개인 메모에만 남을 수 있어요.',
  })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await expect(page.locator('.role-row-open').filter({ hasText: '질문 큐레이터' })).toBeVisible()
  expect(await page.evaluate(() => ['baton-roles', 'baton-routines', 'baton-decisions', 'baton-handoff'].map((key) => localStorage.getItem(key)))).toEqual([null, null, null, null])
})

test('@webkit dialog는 Escape로 닫히고 진입 버튼으로 focus를 돌려보낸다', async ({ page }, testInfo) => {
  await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()

  const opener = page.getByRole('button', { name: '역할 추가' })
  await opener.focus()
  await opener.press('Enter')

  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('역할 이름')).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(opener).toBeFocused()
})

test('@operations 역할과 반복 업무 정의를 수정해도 기존 회차의 실행 스냅샷은 유지한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()

  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(roleDialog.getByLabel('역할 이름')).toHaveValue('문제 큐레이터')
  await expect(roleDialog.getByLabel('현재 담당자')).toHaveValue(MEMBER_ONE_ID)
  await roleDialog.getByLabel('역할 이름').fill('문제 운영 큐레이터')
  await roleDialog.getByLabel('역할 목적').fill('문제 선정과 진행 기준을 함께 관리합니다.')
  await roleDialog.getByLabel('현재 담당자').selectOption(MEMBER_TWO_ID)
  await roleDialog.getByLabel('다음 담당자').selectOption(MEMBER_THREE_ID)
  await roleDialog.getByLabel('담당 시작일').fill('2026-07-10')
  await roleDialog.getByLabel('담당 종료일').fill('2026-09-10')
  await roleDialog.getByLabel('담당 업무').fill('문제 6개 선정\n진행 순서 공유')
  await roleDialog.getByLabel('주의사항').fill('선정 기준이 오래된 문서에 남아 있어요.')
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(page.locator('.toast[role="status"]')).toContainText('역할 정보를 수정했어요.')
  await expect(page.locator('.role-row-open').filter({ hasText: '문제 운영 큐레이터' })).toBeVisible()
  const roleCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/roles/${ROLE_ID}`)
  expectScopedCall(roleCall, {
    name: '문제 운영 큐레이터',
    purpose: '문제 선정과 진행 기준을 함께 관리합니다.',
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: MEMBER_THREE_ID,
    assignmentStartDate: '2026-07-10',
    assignmentEndDate: '2026-09-10',
    responsibilities: ['문제 6개 선정', '진행 순서 공유'],
    risk: '선정 기준이 오래된 문서에 남아 있어요.',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
  await page.getByRole('button', { name: '문제 5개 선정 반복 업무 수정' }).click()

  const routineDialog = page.getByRole('dialog', { name: '반복 업무 수정' })
  await expect(routineDialog.getByLabel('반복 업무 이름')).toHaveValue('문제 5개 선정')
  await expect(routineDialog.getByLabel('담당 역할')).toHaveValue(ROLE_ID)
  await routineDialog.getByLabel('반복 업무 이름').fill('문제 6개 선정')
  await routineDialog.getByLabel('업무 시점').selectOption('DURING')
  await routineDialog.getByLabel('기한 설명').fill('목요일 20:00')
  await routineDialog.getByLabel('세부 설명').fill('난이도와 풀이 시간을 확인해 여섯 문제를 확정합니다.')
  await routineDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(page.locator('.toast[role="status"]')).toContainText('반복 업무 정보를 수정했어요.')
  const beforePhase = page.locator('.rhythm-phase').filter({ has: page.getByRole('heading', { name: '모임 전' }) })
  const snapshottedRoutine = beforePhase.locator('.routine-row').filter({ hasText: '문제 5개 선정' })
  await expect(snapshottedRoutine).toContainText('그래프 2개 · DP 2개 · 구현 1개')
  await expect(snapshottedRoutine).toContainText('수요일 18:00')
  await expect(page.getByRole('button', { name: '문제 6개 선정 반복 업무 수정' })).toBeVisible()
  await expect(page.locator('.routine-row').filter({ hasText: '문제 6개 선정' })).toHaveCount(0)
  const routineCall = await recordedCall(api, 'PUT', `${SCOPE_PATH}/routines/${ROUTINE_ID}`)
  expectScopedCall(routineCall, {
    title: '문제 6개 선정',
    phase: 'DURING',
    dueLabel: '목요일 20:00',
    ownerRoleId: ROLE_ID,
    detail: '난이도와 풀이 시간을 확인해 여섯 문제를 확정합니다.',
    deadlineDayOffset: -1,
    deadlineTime: '22:00',
  })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await expect(page.locator('.role-row-open').filter({ hasText: '문제 운영 큐레이터' })).toBeVisible()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
  await expect(page.locator('.rhythm-phase').filter({ has: page.getByRole('heading', { name: '모임 전' }) }).locator('.routine-row').filter({ hasText: '문제 5개 선정' })).toBeVisible()
  await expect(page.getByRole('button', { name: '문제 6개 선정 반복 업무 수정' })).toBeVisible()
})

test('@operations @webkit 역할과 반복 업무 수정 충돌은 입력만 보존하고 최신 폼을 다시 연다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 이름').fill('내 화면의 낡은 역할 수정')

  api.conflictNextRoleUpdate({
    ...api.projection().roles[0]!,
    name: '다른 구성원이 갱신한 역할',
    purpose: '서버에서 먼저 갱신한 최신 역할 목적입니다.',
    responsibilities: ['최신 문제 기준 관리', '변경 내용 공유'],
    risk: '최신 기준이 구성원에게 아직 전파되지 않았어요.',
  })
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(roleDialog).toBeHidden()
  await expect(page.locator('.toast[role="status"]')).toContainText('다른 사람이 수정한 내용을 불러왔어요')
  const draft = page.getByLabel('저장하지 못한 입력 내용 (읽기 전용)')
  await expect(draft).toHaveValue(/내 화면의 낡은 역할 수정/)
  await expect(draft).toHaveValue(/담당 업무\n문제 5개 선정\n난이도 균형 확인/)
  await expect(draft).toHaveJSProperty('readOnly', true)
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async () => { throw new DOMException('복사 권한 없음', 'NotAllowedError') } },
    })
  })
  await page.getByRole('button', { name: '입력 내용 복사' }).click()
  await expect(page.getByText('자동 복사를 사용할 수 없습니다. 선택된 내용을 직접 복사해 주세요.')).toBeVisible()
  await expect(draft).toBeFocused()
  expect(await draft.evaluate((element) => {
    const input = element as HTMLTextAreaElement
    return input.value.slice(input.selectionStart, input.selectionEnd)
  })).toContain('내 화면의 낡은 역할 수정')
  await page.getByRole('button', { name: '다른 구성원이 갱신한 역할 역할 수정' }).click()
  const reopenedRoleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(reopenedRoleDialog.getByLabel('역할 이름')).toHaveValue('다른 구성원이 갱신한 역할')
  await expect(reopenedRoleDialog.getByLabel('역할 목적'))
    .toHaveValue('서버에서 먼저 갱신한 최신 역할 목적입니다.')
  await expect(reopenedRoleDialog.getByLabel('담당 업무'))
    .toHaveValue('최신 문제 기준 관리\n변경 내용 공유')
  await expect(reopenedRoleDialog.getByLabel('주의사항'))
    .toHaveValue('최신 기준이 구성원에게 아직 전파되지 않았어요.')
  await reopenedRoleDialog.getByRole('button', { name: '닫기' }).click()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
  await page.getByRole('button', { name: '문제 5개 선정 반복 업무 수정' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 업무 수정' })
  await routineDialog.getByLabel('반복 업무 이름').fill('내 화면의 낡은 반복 업무 수정')

  api.conflictNextRoutineUpdate({
    ...api.projection().routines[0]!,
    title: '다른 구성원이 갱신한 반복 업무',
    phase: 'AFTER',
    dueLabel: '금요일 22:00',
    detail: '서버에서 먼저 갱신한 최신 반복 업무 설명입니다.',
  })
  await routineDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(routineDialog).toBeHidden()
  await expect(page.locator('.toast[role="status"]')).toContainText('다른 사람이 수정한 내용을 불러왔어요')
  await expect(draft).toHaveValue(/내 화면의 낡은 반복 업무 수정/)
  await expect(draft).not.toHaveValue(/내 화면의 낡은 역할 수정/)
  await page.getByRole('button', { name: '다른 구성원이 갱신한 반복 업무 반복 업무 수정' }).click()
  const reopenedRoutineDialog = page.getByRole('dialog', { name: '반복 업무 수정' })
  await expect(reopenedRoutineDialog.getByLabel('반복 업무 이름')).toHaveValue('다른 구성원이 갱신한 반복 업무')
  await expect(reopenedRoutineDialog.getByLabel('업무 시점')).toHaveValue('AFTER')
  await expect(reopenedRoutineDialog.getByLabel('기한 설명')).toHaveValue('금요일 22:00')
  await expect(reopenedRoutineDialog.getByLabel('세부 설명'))
    .toHaveValue('서버에서 먼저 갱신한 최신 반복 업무 설명입니다.')

  expect(api.calls.filter(
    (call) => call.method === 'PUT' && call.path === `${SCOPE_PATH}/roles/${ROLE_ID}`,
  )).toHaveLength(1)
  expect(api.calls.filter(
    (call) => call.method === 'PUT' && call.path === `${SCOPE_PATH}/routines/${ROUTINE_ID}`,
  )).toHaveLength(1)
  const browserStorage = await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]))
  expect(browserStorage).not.toContain('내 화면의 낡은')
  await page.reload()
  await page.getByText('내 업무와 확인할 자료', { exact: true }).click()
  await expect(page.getByRole('heading', { name: '내 담당 업무' })).toBeVisible()
  await expect(draft).toHaveCount(0)
})

test('@operations @webkit 충돌 초안은 세션 조회 실패와 같은 계정으로 복구한 뒤에도 유지한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  let sessionFails = false
  await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'draft-test-csrf' } }))
  await page.route('**/api/v1/auth/session', (route) => route.fulfill(sessionFails
    ? { status: 503, json: { code: 'SERVICE_UNAVAILABLE', message: '로그인 상태를 잠시 확인할 수 없습니다.' } }
    : { json: {
      authenticated: true, accountId: '8e448211-66ae-44ab-9888-c4960648c22b',
      csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'draft-test-csrf',
    } }))
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const dialog = page.getByRole('dialog', { name: '역할 수정' })
  await dialog.getByLabel('역할 이름').fill('일시적인 오류에도 보존할 입력')
  api.conflictNextRoleUpdate({ ...api.projection().roles[0]!, name: '최신 역할' })
  await dialog.getByRole('button', { name: '변경 저장' }).click()
  const draft = page.getByLabel('저장하지 못한 입력 내용 (읽기 전용)')
  await expect(draft).toHaveValue(/일시적인 오류에도 보존할 입력/)

  sessionFails = true
  await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘' }).click()
  await page.getByText('내 업무와 확인할 자료', { exact: true }).click()
  const panel = page.getByRole('region', { name: '내 담당 업무' })
  await expect(panel).toContainText('로그인 상태를 확인하지 못했습니다')
  await expect(draft).toHaveValue(/일시적인 오류에도 보존할 입력/)
  sessionFails = false
  await panel.getByRole('button', { name: '다시 확인' }).click()
  await expect(panel).not.toContainText('로그인 상태를 확인하지 못했습니다')
  await expect(draft).toHaveValue(/일시적인 오류에도 보존할 입력/)
})

for (const transition of ['계정 변경', '접근 권한 상실'] as const) {
  test(`@operations 충돌 초안은 ${transition} 뒤 폐기한다`, async ({ page }, testInfo) => {
    const api = await installApi(page)
    let accountId = '8e448211-66ae-44ab-9888-c4960648c22b'
    let sessionReads = 0
    await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: { csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'draft-test-csrf' } }))
    await page.route('**/api/v1/auth/session', async (route) => {
      sessionReads += 1
      await route.fulfill({ json: {
        authenticated: true, accountId, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'draft-test-csrf',
      } })
    })
    await openSharedWorkspace(page)
    await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
    await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
    const dialog = page.getByRole('dialog', { name: '역할 수정' })
    await dialog.getByLabel('역할 이름').fill('이 계정에서 작성한 초안')
    api.conflictNextRoleUpdate({ ...api.projection().roles[0]!, name: '최신 역할' })
    await dialog.getByRole('button', { name: '변경 저장' }).click()
    const draft = page.getByLabel('저장하지 못한 입력 내용 (읽기 전용)')
    await expect(draft).toHaveValue(/이 계정에서 작성한 초안/)
    if (transition === '계정 변경') {
      accountId = '8e448211-66ae-44ab-9888-c4960648c22c'
      const readsBeforeChange = sessionReads
      await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
      await expect.poll(() => sessionReads).toBeGreaterThan(readsBeforeChange)
      await expect(draft).toHaveCount(0)
      accountId = '8e448211-66ae-44ab-9888-c4960648c22b'
      const readsBeforeReturn = sessionReads
      await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
      await expect.poll(() => sessionReads).toBeGreaterThan(readsBeforeReturn)
      await expect(draft).toHaveCount(0)
    } else {
      api.rotateAccessKeyFromAnotherDevice()
      await page.getByRole('button', { name: '지금 새로고침' }).click()
      await expect(page.getByRole('heading', { name: '작업 공간을 불러오지 못했어요' })).toBeVisible()
      await expect(draft).toHaveCount(0)
    }
  })
}

test('@operations 역할 수정 충돌 뒤 최신 조회가 실패하면 재편집을 막고 새로고침 후 최신 폼을 연다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const roleUpdatePath = `${SCOPE_PATH}/roles/${ROLE_ID}`
  const workspacePath = `${SCOPE_PATH}/workspace`
  const rolePutCount = () => api.calls.filter(
    (call) => call.method === 'PUT' && call.path === roleUpdatePath,
  ).length
  const workspaceGetCount = () => api.calls.filter(
    (call) => call.method === 'GET' && call.path === workspacePath,
  ).length

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 이름').fill('내 화면의 낡은 역할 수정')

  api.conflictNextRoleUpdate({
    ...api.projection().roles[0]!,
    name: '다른 구성원이 갱신한 역할',
    purpose: '서버에서 먼저 갱신한 최신 역할 목적입니다.',
    responsibilities: ['최신 문제 기준 관리', '변경 내용 공유'],
    risk: '최신 기준이 구성원에게 아직 전파되지 않았어요.',
  })
  api.makeWorkspaceGetsUnavailable()
  const getsBeforeConflict = workspaceGetCount()

  await roleDialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(roleDialog).toBeHidden()
  await expect.poll(rolePutCount).toBe(1)
  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeConflict)
  const syncStatus = page.locator('.workspace-sync-status')
  await expect(syncStatus).toContainText('최신 내용을 확인한 뒤 다시 수정하세요.')
  await expect(page.getByLabel('저장하지 못한 입력 내용 (읽기 전용)')).toHaveValue(/내 화면의 낡은 역할 수정/)
  await expect(page.getByRole('button', { name: '입력 내용 복사' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '다른 구성원이 갱신한 역할 역할 수정' }))
    .toHaveCount(0)
  await expect(page.getByRole('button', { name: '문제 큐레이터 역할 수정' })).toBeDisabled()
  await expect(page.locator('.main-surface')).toBeFocused()
  await expect(page.getByRole('dialog', { name: '역할 수정' })).toHaveCount(0)
  await expect.poll(rolePutCount).toBe(1)

  api.restoreWorkspaceGets()
  const getsBeforeRecovery = workspaceGetCount()
  await page.getByRole('button', { name: '최신 내용 다시 확인' }).click()

  await expect.poll(workspaceGetCount).toBeGreaterThan(getsBeforeRecovery)
  await expect(syncStatus).toContainText('화면 갱신')
  const latestEditButton = page.getByRole('button', {
    name: '다른 구성원이 갱신한 역할 역할 수정',
  })
  await expect(latestEditButton).toBeVisible()
  await latestEditButton.click()

  const reopenedRoleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(reopenedRoleDialog.getByLabel('역할 이름')).toHaveValue('다른 구성원이 갱신한 역할')
  await expect(reopenedRoleDialog.getByLabel('역할 목적'))
    .toHaveValue('서버에서 먼저 갱신한 최신 역할 목적입니다.')
  await expect(reopenedRoleDialog.getByLabel('담당 업무'))
    .toHaveValue('최신 문제 기준 관리\n변경 내용 공유')
  await expect(reopenedRoleDialog.getByLabel('주의사항'))
    .toHaveValue('최신 기준이 구성원에게 아직 전파되지 않았어요.')
  await expect.poll(rolePutCount).toBe(1)
})

test('@operations 수정 저장 중에는 닫기와 배경 클릭으로 dialog를 닫지 않는다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  api.holdNextRoleUpdate()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await roleDialog.getByLabel('역할 이름').fill('저장 중인 문제 큐레이터')
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()
  await recordedCall(api, 'PUT', `${SCOPE_PATH}/roles/${ROLE_ID}`)

  const roleClose = roleDialog.getByRole('button', { name: '닫기' })
  await expect(roleDialog).toHaveAttribute('aria-busy', 'true')
  await expect(roleClose).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(roleDialog).toBeVisible()
  await roleClose.click({ force: true })
  await expect(roleDialog).toBeVisible()
  api.releaseRoleUpdate()
  await expect(roleDialog).toHaveCount(0)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
  api.holdNextRoutineUpdate()
  await page.getByRole('button', { name: '문제 5개 선정 반복 업무 수정' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 업무 수정' })
  await routineDialog.getByLabel('기한 설명').fill('저장 완료 후 공개')
  await routineDialog.getByRole('button', { name: '변경 저장' }).click()
  await recordedCall(api, 'PUT', `${SCOPE_PATH}/routines/${ROUTINE_ID}`)

  await expect(routineDialog).toHaveAttribute('aria-busy', 'true')
  await expect(routineDialog.getByRole('button', { name: '닫기' })).toBeDisabled()
  await page.locator('.modal-backdrop').click({ position: { x: 5, y: 5 }, force: true })
  await expect(routineDialog).toBeVisible()
  api.releaseRoutineUpdate()
  await expect(routineDialog).toHaveCount(0)
})

test('모든 콘텐츠 생성은 서버 응답 전 dialog 종료와 재진입을 막는다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  const memberDialog = await openMemberCreationDialog(page)
  await memberDialog.getByLabel('구성원 이름').fill('생성 잠금 구성원')
  await expectPendingCreationDialogLocked({
    api,
    dialog: memberDialog,
    operation: 'member',
    page,
    submitLabel: '구성원 추가하기',
  })

  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('생성 잠금 역할')
  await roleDialog.getByLabel('역할 목적').fill('응답 전에는 같은 역할을 다시 제출하지 않습니다.')
  await expectPendingCreationDialogLocked({
    api,
    dialog: roleDialog,
    operation: 'role',
    page,
    submitLabel: '역할 만들기',
  })
  await expect(page.locator('.role-row-open').filter({ hasText: '생성 잠금 역할' })).toHaveCount(1)

  if (testInfo.project.name === 'mobile') {
    await page.locator('.role-row-open').filter({ hasText: '생성 잠금 역할' }).click()
  }
  await page.getByRole('button', { name: '자료 추가' }).click()
  const resourceDialog = page.getByRole('dialog', { name: '참고 자료 추가' })
  await resourceDialog.getByLabel('자료 이름').fill('생성 잠금 자료')
  await resourceDialog.getByLabel('링크').fill('https://docs.example.com/pending-lock')
  await expectPendingCreationDialogLocked({
    api,
    dialog: resourceDialog,
    operation: 'roleResource',
    page,
    submitLabel: '자료 추가',
  })
  if (testInfo.project.name === 'mobile') {
    await page.getByRole('dialog', { name: /선택한 역할 상세/ })
      .getByRole('button', { name: '상세 닫기' })
      .click()
  }

  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
  await page.getByRole('button', { name: '반복 업무 추가' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 업무 만들기' })
  await routineDialog.getByLabel('반복 업무 이름').fill('생성 잠금 반복 업무')
  await routineDialog.getByLabel('기한 설명').fill('모임 하루 전')
  await routineDialog.getByLabel('세부 설명').fill('서버 응답을 받은 뒤에만 생성 화면을 닫습니다.')
  await expectPendingCreationDialogLocked({
    api,
    dialog: routineDialog,
    operation: 'routine',
    page,
    submitLabel: '반복 업무 만들기',
  })

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('모임 날짜').fill('2026-07-31')
  await expectPendingCreationDialogLocked({
    api,
    dialog: roundDialog,
    operation: 'round',
    page,
    submitLabel: '회차 만들기',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()
  const decisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await decisionDialog.getByLabel('무엇을 결정했나요?').fill('생성 요청은 응답까지 한 화면에서 기다린다')
  await decisionDialog.getByLabel('왜 이 선택을 했나요?').fill('중복 요청과 완료 callback 유실을 막기 위해서입니다.')
  await expectPendingCreationDialogLocked({
    api,
    dialog: decisionDialog,
    operation: 'decision',
    page,
    submitLabel: '결정 기록하기',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가', exact: true }).click()
  const handoffDialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await handoffDialog.getByLabel('남길 내용').fill('생성 요청이 끝날 때까지 dialog 유지')
  await expectPendingCreationDialogLocked({
    api,
    dialog: handoffDialog,
    operation: 'handoffItem',
    page,
    submitLabel: '항목 추가하기',
  })

  await page.getByRole('tab', { name: /문제 큐레이터/ }).click()
  await page.getByRole('button', { name: '인수인계 준비 시작' }).click()
  const roleHandoffDialog = page.getByRole('dialog', { name: '역할 인수인계 준비 시작' })
  await expectPendingCreationDialogLocked({
    api,
    dialog: roleHandoffDialog,
    operation: 'roleHandoff',
    page,
    submitLabel: '인수인계 준비 시작',
  })

  for (const operation of Object.keys(CONTENT_CREATION_PATHS) as ContentCreationOperation[]) {
    const path = CONTENT_CREATION_PATHS[operation]
    expect(api.calls.filter((call) => call.method === 'POST' && call.path === path)).toHaveLength(1)
  }
  expect(api.projection().roles.filter((role) => role.name === '생성 잠금 역할')).toHaveLength(1)
})

test('생성 재시도 정보를 내구 저장할 수 없으면 콘텐츠 POST를 보내지 않는다', async ({ page }, testInfo) => {
  await blockContentCreationStorage(page)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const expectStorageBlock = async (dialog: Locator, submitLabel: string) => {
    await dialog.getByRole('button', { name: submitLabel }).click()
    await expect(dialog.getByRole('alert')).toContainText('일반 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도하세요.')
    await dialog.getByRole('button', { name: '닫기' }).click()
  }

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  const memberDialog = await openMemberCreationDialog(page)
  await memberDialog.getByLabel('구성원 이름').fill('저장 차단 구성원')
  await expectStorageBlock(memberDialog, '구성원 추가하기')

  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('저장 차단 역할')
  await roleDialog.getByLabel('역할 목적').fill('중복 요청을 보내지 않는지 확인합니다.')
  await expectStorageBlock(roleDialog, '역할 만들기')

  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  const roleInspector = page.getByLabel('선택한 역할 상세')
  await roleInspector.getByRole('button', { name: '자료 추가' }).click()
  const resourceDialog = page.getByRole('dialog', { name: '참고 자료 추가' })
  await resourceDialog.getByLabel('자료 이름').fill('저장 차단 자료')
  await resourceDialog.getByLabel('링크').fill('https://docs.example.com/storage-blocked')
  await expectStorageBlock(resourceDialog, '자료 추가')
  if (testInfo.project.name === 'mobile') {
    await roleInspector.getByRole('button', { name: '상세 닫기' }).click()
  }

  await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
  await page.getByRole('button', { name: '반복 업무 추가' }).click()
  const routineDialog = page.getByRole('dialog', { name: '반복 업무 만들기' })
  await routineDialog.getByLabel('반복 업무 이름').fill('저장 차단 반복 업무')
  await routineDialog.getByLabel('기한 설명').fill('수요일 18:00')
  await routineDialog.getByLabel('세부 설명').fill('저장 가능한 경우에만 전송합니다.')
  await expectStorageBlock(routineDialog, '반복 업무 만들기')

  await page.getByRole('button', { name: '회차 만들기' }).click()
  const roundDialog = page.getByRole('dialog', { name: '회차 만들기' })
  await roundDialog.getByLabel('회차 이름').fill('저장 차단 회차')
  await expectStorageBlock(roundDialog, '회차 만들기')

  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()
  const decisionDialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await decisionDialog.getByLabel('무엇을 결정했나요?').fill('저장 가능한 요청만 보낸다')
  await decisionDialog.getByLabel('왜 이 선택을 했나요?').fill('응답 유실 뒤 중복 생성을 막기 위해서입니다.')
  await expectStorageBlock(decisionDialog, '결정 기록하기')

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()
  const handoffDialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await handoffDialog.getByLabel('남길 내용').fill('저장 차단 확인')
  await expectStorageBlock(handoffDialog, '항목 추가하기')

  const contentPaths = new Set(Object.values(CONTENT_CREATION_PATHS))
  expect(api.calls.filter((call) => call.method === 'POST' && contentPaths.has(call.path))).toHaveLength(0)
})

test('legacy 콘텐츠 pending의 request guard를 저장하지 못하면 replay POST를 보내지 않는다', async ({ page }, testInfo) => {
  const legacyKey = 'legacy-content-guard-key-000000000001'
  await page.addInitScript(({ prefix, idempotencyKey, teamId, seasonId, roleId }) => {
    const storageKey = `${prefix}${idempotencyKey}`
    localStorage.setItem(storageKey, JSON.stringify({
      teamId,
      seasonId,
      operation: 'handoffItem',
      normalizedPayload: JSON.stringify({
        roleId,
        label: 'legacy guard upgrade 확인',
        category: 'RESPONSIBILITY',
      }),
      idempotencyKey,
      createdAt: 1,
    }))

    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key === storageKey && value.includes('"requestGuard":true')) {
        throw new DOMException('Storage guard upgrade disabled', 'SecurityError')
      }
      originalSetItem.call(this, key, value)
    }
  }, {
    prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX,
    idempotencyKey: legacyKey,
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    roleId: ROLE_ID,
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('역할').selectOption(ROLE_ID)
  await dialog.getByLabel('남길 내용').fill('legacy guard upgrade 확인')
  await dialog.getByLabel('항목 종류').selectOption('RESPONSIBILITY')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert')).toContainText(
    '일반 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도하세요.',
  )
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(0)
  const pending = await pendingContentCreationEntries(page)
  expect(pending).toEqual([expect.objectContaining({ idempotencyKey: legacyKey })])
  expect(pending[0]?.requestGuard).toBeUndefined()
})

test('콘텐츠 생성 성공 뒤 cleanup이 실패하면 다음 POST 전에 기록부터 정리한다', async ({ page }, testInfo) => {
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX },
    'baton-e2e-content-success-cleanup-failure',
  )
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const openRoleCreation = async (name: string) => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
    await page.getByRole('button', { name: '역할 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
    await dialog.getByLabel('역할 이름').fill(name)
    await dialog.getByLabel('역할 목적').fill('완료 기록 정리 경계를 확인합니다.')
    return dialog
  }

  const firstDialog = await openRoleCreation('cleanup 성공 첫 역할')
  await firstDialog.getByRole('button', { name: '역할 만들기' }).click()
  await expect(firstDialog).toHaveCount(0)

  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      operation: 'role',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
    }),
  ])

  const secondDialog = await openRoleCreation('cleanup 성공 둘째 역할')
  await expect(secondDialog.getByRole('alert')).toContainText(
    '임시 기록을 정리하지 못해 요청을 다시 보내지 않았습니다.',
  )
  await secondDialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(secondDialog.getByRole('alert')).toContainText('임시 기록을 정리했습니다.')
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`,
  )).toHaveLength(1)

  await secondDialog.getByRole('button', { name: '역할 만들기' }).click()
  await expect(secondDialog).toHaveCount(0)
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('콘텐츠 cleanup 실패는 reload와 다른 작업 전환 뒤에도 새 키 발급을 막고 명시적으로 복구한다', async ({ page, context }, testInfo) => {
  await blockContentCreationCleanupUntilReleased(page)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const roleDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await roleDialog.getByLabel('역할 이름').fill('reload cleanup 역할')
  await roleDialog.getByLabel('역할 목적').fill('완료 기록 정리 경계를 확인합니다.')
  await roleDialog.getByRole('button', { name: '역할 만들기' }).click()
  await expect(roleDialog).toHaveCount(0)

  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  const cleanupBanner = page.getByRole('alert', { name: '새 항목 추가를 위한 임시 기록 삭제' })
  await expect(cleanupBanner).toContainText('새 항목을 추가하려면 브라우저의 임시 기록을 지워야 합니다.')
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      operation: 'role',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
      cleanupRequired: true,
    }),
  ])

  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /남은 업무 \d+개/ })).toBeVisible()
  await expect(cleanupBanner).toBeVisible()

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()
  const handoffDialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await handoffDialog.getByLabel('남길 내용').fill('다른 작업의 새 키는 아직 만들지 않기')
  await handoffDialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(handoffDialog.getByRole('alert')).toContainText(
    '임시 기록을 정리하지 못해 요청을 다시 보내지 않았습니다.',
  )
  expect(api.calls.filter((call) =>
    call.method === 'POST' && Object.values(CONTENT_CREATION_PATHS).includes(call.path),
  )).toHaveLength(1)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({ idempotencyKey: firstAttempt.headers['idempotency-key'] }),
  ])

  await handoffDialog.getByRole('button', { name: '닫기' }).click()
  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  await openSharedWorkspace(peerPage)
  const peerCleanupBanner = peerPage.getByRole('alert', {
    name: '새 항목 추가를 위한 임시 기록 삭제',
  })
  await peerCleanupBanner.getByRole('button', { name: '임시 기록 정리' }).click()
  await expect(peerCleanupBanner).toHaveCount(0)
  await expect(cleanupBanner).toHaveCount(0)
  await peerPage.close()

  await page.evaluate((releaseKey) => {
    sessionStorage.setItem(releaseKey, 'true')
  }, CONTENT_CREATION_CLEANUP_RELEASE_KEY)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)

  await page.getByRole('button', { name: '항목 추가' }).click()
  const recoveredDialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await recoveredDialog.getByLabel('남길 내용').fill('cleanup 뒤 새 작업 허용')
  await recoveredDialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(page.getByRole('checkbox', { name: 'cleanup 뒤 새 작업 허용' })).toBeVisible()

  const contentAttempts = api.calls.filter((call) =>
    call.method === 'POST' && Object.values(CONTENT_CREATION_PATHS).includes(call.path),
  )
  expect(contentAttempts).toHaveLength(2)
  expect(contentAttempts[1]?.headers['idempotency-key'])
    .not.toBe(firstAttempt.headers['idempotency-key'])
})

test('콘텐츠 request guard는 marker와 cleanup 전체 실패 뒤 reload에도 같은 키 재확인만 허용한다', async ({ page }, testInfo) => {
  await failContentCreationMarkerAndCleanupUntilReleased(page)
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const openHandoffCreation = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
    await page.getByRole('button', { name: '항목 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
    await dialog.getByLabel('남길 내용').fill('guard 원본 요청 재확인')
    return dialog
  }

  const firstDialog = await openHandoffCreation()
  await firstDialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(firstDialog).toHaveCount(0)

  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  const pendingAfterFailure = await pendingContentCreationEntries(page)
  expect(pendingAfterFailure).toEqual([
    expect.objectContaining({
      operation: 'handoffItem',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
      requestGuard: true,
    }),
  ])
  expect(pendingAfterFailure[0]?.cleanupRequired).toBeUndefined()
  await expect(page.getByRole('alert', { name: '새 항목 추가를 위한 임시 기록 삭제' }))
    .toBeVisible()

  await page.evaluate((releaseKey) => {
    sessionStorage.setItem(releaseKey, 'true')
  }, CONTENT_CREATION_GUARD_FAILURE_RELEASE_KEY)
  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /남은 업무 \d+개/ })).toBeVisible()
  await expect(page.getByRole('alert', { name: '새 항목 추가를 위한 임시 기록 삭제' }))
    .toHaveCount(0)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const otherOperationDialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await otherOperationDialog.getByLabel('역할 이름').fill('guard가 막을 새 역할')
  await otherOperationDialog.getByLabel('역할 목적')
    .fill('원래 저장 요청부터 확인해야 합니다.')
  await otherOperationDialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(otherOperationDialog.getByRole('alert')).toContainText(
    '이전 항목이 저장됐는지 확인하지 못해 새 항목을 추가하지 않았습니다.',
  )
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`,
  )).toHaveLength(0)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      idempotencyKey: firstAttempt.headers['idempotency-key'],
      requestGuard: true,
    }),
  ])

  await otherOperationDialog.getByRole('button', { name: '닫기' }).click()
  const retryDialog = await openHandoffCreation()
  await retryDialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(retryDialog).toHaveCount(0)

  const replayAttempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )
  expect(replayAttempts).toHaveLength(2)
  expect(replayAttempts[1]?.headers['idempotency-key'])
    .toBe(firstAttempt.headers['idempotency-key'])
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('콘텐츠 생성 성공 응답이 손상되면 journal을 유지하고 같은 요청으로 결과를 회수한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', 'mutation 응답 검증과 journal 복구는 데스크톱 Chromium에서 한 번만 검증합니다.')
  const api = await installApi(page)
  api.returnMalformedNextContentCreationResponse('role')
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await dialog.getByLabel('역할 이름').fill('손상 응답 복구 역할')
  await dialog.getByLabel('역할 목적')
    .fill('성공 응답을 검증한 뒤에만 journal을 정리합니다.')
  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  const alert = dialog.getByRole('alert')
  await expect(alert).toContainText('서버 응답을 확인할 수 없습니다.')
  await expect(alert).toContainText(
    '같은 내용으로 다시 제출하면 중복으로 만들지 않고 저장 여부를 확인합니다.',
  )
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/roles`)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      operation: 'role',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
      requestGuard: true,
    }),
  ])
  expect(api.projection().roles.filter((role) => role.name === '손상 응답 복구 역할'))
    .toHaveLength(1)

  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(dialog).toHaveCount(0)
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  expect(api.projection().roles.filter((role) => role.name === '손상 응답 복구 역할'))
    .toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@operations 반복 업무 응답 유실 뒤 마감 변경을 새 요청으로 구분하고 원래 결과도 복구한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '반복 업무 journal 요청 동일성은 데스크톱 Chromium에서 한 번만 검증합니다.')
  const api = await installApi(page)
  api.commitNextContentCreationThenTimeout('routine')
  await openSharedWorkspace(page)

  const openAndFillRoutine = async (deadlineDayOffset: string, deadlineTime: string) => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '일정' }).click()
    await page.getByRole('button', { name: '반복 업무 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '반복 업무 만들기' })
    await dialog.getByLabel('반복 업무 이름').fill('마감 journal 경계 확인')
    await dialog.getByLabel('기한 설명').fill('모임 전에 확인')
    await dialog.getByLabel('마감 기준일').selectOption(deadlineDayOffset)
    await dialog.getByLabel('마감 시각').fill(deadlineTime)
    await dialog.getByLabel('세부 설명').fill('마감 규칙도 요청 동일성에 포함합니다.')
    return dialog
  }

  const firstDialog = await openAndFillRoutine('-3', '19:00')
  await firstDialog.getByRole('button', { name: '반복 업무 만들기' }).click()
  await expect(firstDialog.getByRole('alert')).toContainText(
    '같은 내용으로 다시 제출하면 중복으로 만들지 않고 저장 여부를 확인합니다.',
  )
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/routines`)

  await firstDialog.getByLabel('마감 기준일').selectOption('0')
  await firstDialog.getByLabel('마감 시각').fill('20:00')
  await firstDialog.getByRole('button', { name: '반복 업무 만들기' }).click()
  await expect(firstDialog.getByRole('alert')).toContainText(
    '이전 항목이 저장됐는지 확인하지 못해 새 항목을 추가하지 않았습니다.',
  )

  let attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/routines`,
  )
  expect(attempts).toHaveLength(1)
  const pendingAfterChangedRequest = await pendingContentCreationEntries(page)
  expect(pendingAfterChangedRequest).toHaveLength(1)
  expect(pendingAfterChangedRequest[0]?.requestGuard).toBe(true)
  expect(JSON.parse(pendingAfterChangedRequest[0]!.normalizedPayload)).toMatchObject({
    deadlineDayOffset: -3,
    deadlineTime: '19:00',
  })

  await firstDialog.getByLabel('마감 기준일').selectOption('-3')
  await firstDialog.getByLabel('마감 시각').fill('19:00')
  await firstDialog.getByRole('button', { name: '반복 업무 만들기' }).click()
  await expect(firstDialog).toHaveCount(0)

  attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/routines`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)

  const changedRequestDialog = await openAndFillRoutine('0', '20:00')
  await changedRequestDialog.getByRole('button', { name: '반복 업무 만들기' }).click()
  await expect(changedRequestDialog).toHaveCount(0)

  attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/routines`,
  )
  expect(attempts).toHaveLength(3)
  expect(attempts[2]?.headers['idempotency-key'])
    .not.toBe(firstAttempt.headers['idempotency-key'])
  expect(api.projection().routines.filter(
    (routine) => routine.title === '마감 journal 경계 확인',
  )).toHaveLength(2)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('확인되지 않은 생성 요청이 한도에 이르면 기존 요청 정리를 안내한다', async ({ page }, testInfo) => {
  await page.addInitScript(({ prefix, count }) => {
    for (let index = 0; index < count; index += 1) {
      const idempotencyKey = `pending-content-${String(index).padStart(32, '0')}`
      localStorage.setItem(`${prefix}${idempotencyKey}`, JSON.stringify({
        teamId: 'another-team',
        seasonId: 'another-season',
        operation: 'handoffItem',
        normalizedPayload: JSON.stringify({
          roleId: `another-role-${index}`,
          label: `미확인 인수인계 ${index}`,
          category: 'ADVICE',
        }),
        idempotencyKey,
        createdAt: index,
      }))
    }
  }, { prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX, count: 20 })
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '역할 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '새 역할 만들기' })
  await dialog.getByLabel('역할 이름').fill('스물한 번째 역할')
  await dialog.getByLabel('역할 목적').fill('한도 안내를 확인합니다.')
  await dialog.getByRole('button', { name: '역할 만들기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('저장 여부를 확인하지 못한 항목이 20개 있습니다.')
  await expect(dialog.getByRole('alert')).toContainText('이전과 같은 내용을 다시 제출해 저장됐는지 확인한 뒤 새 항목을 추가하세요.')
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/roles`)).toHaveLength(0)
})
