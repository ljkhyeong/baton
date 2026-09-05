import { expect, test } from '@playwright/test'
import {
  TEAM_ID,
  SEASON_ID,
  MEMBER_ONE_ID,
  MEMBER_TWO_ID,
  MEMBER_THREE_ID,
  ROLE_ID,
  SECOND_ROLE_ID,
  DECISION_ID,
  HANDOFF_ONE_ID,
  CREATED_HANDOFF_ID,
  CREATED_ROLE_RESOURCE_ID,
  ROLE_HANDOFF_ID,
  SECOND_ROLE_RESOURCE_ID,
  ROUND_TWO_ID,
  ROUND_TWO_ROUTINE_TWO_EXECUTION_ID,
  ACCESS_KEY,
  WORKSPACE_PATH,
  SCOPE_PATH,
  PENDING_CONTENT_CREATION_STORAGE_PREFIX,
  makeProjection,
  installApi,
  openSharedWorkspace,
  navigation,
  failNextJournalCleanup,
  pendingContentCreationEntries,
  recordedCall,
  expectScopedCall,
} from './support/workspaceApiHarness'

test('@memory 결정과 작성자를 서버 기록으로 남긴다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()

  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('회고를 10분 먼저 시작한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('다음 액션을 정리할 시간이 자주 부족했기 때문입니다.')
  await dialog.getByLabel('검토한 다른 선택').fill('모임을 10분 연장한다')
  await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
  await expect(dialog.getByRole('checkbox', { name: '문제 큐레이터' })).toBeChecked()
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '회고를 10분 먼저 시작한다' })).toBeVisible()
  await expect(page.getByText('김준호', { exact: true })).toBeVisible()
  const decisionCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  expectScopedCall(decisionCall, {
    title: '회고를 10분 먼저 시작한다',
    reason: '다음 액션을 정리할 시간이 자주 부족했기 때문입니다.',
    alternative: '모임을 10분 연장한다',
    authorMemberId: MEMBER_TWO_ID,
    roleIds: [ROLE_ID],
  })
  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await expect(page.getByRole('heading', { name: '회고를 10분 먼저 시작한다' })).toBeVisible()
})

test('@memory 결정 기록을 수정하고 보관·복원해 원문 시각을 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()

  const originalTitle = '한 회차의 문제 수를 5개로 정한다'
  const updatedTitle = '한 회차의 문제 수를 네 개로 조정한다'
  await page.getByRole('button', { name: `${originalTitle} 수정` }).click()

  const dialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  await expect(dialog.getByLabel('작성자')).toHaveValue(MEMBER_ONE_ID)
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill(updatedTitle)
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('각 풀이를 끝까지 설명할 시간을 확보하기 위해서입니다.')
  await dialog.getByLabel('검토한 다른 선택').fill('문제 난이도를 낮춘다')
  await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
  await dialog.getByRole('button', { name: '변경 저장' }).click()

  const updatePath = `${SCOPE_PATH}/decisions/${DECISION_ID}`
  expectScopedCall(await recordedCall(api, 'PUT', updatePath), {
    title: updatedTitle,
    reason: '각 풀이를 끝까지 설명할 시간을 확보하기 위해서입니다.',
    alternative: '문제 난이도를 낮춘다',
    authorMemberId: MEMBER_TWO_ID,
    roleIds: [ROLE_ID],
  })
  await expect(dialog).toHaveCount(0)
  const updatedDecision = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: updatedTitle }),
  })
  await expect(updatedDecision).toBeVisible()
  await expect(updatedDecision.getByText('김준호', { exact: true })).toBeVisible()
  expect(api.projection().decisions.find((decision) => decision.id === DECISION_ID)).toMatchObject({
    createdAt: '2026-07-03T12:00:00Z',
    authorMemberId: MEMBER_TWO_ID,
    archivedAt: null,
  })

  await page.getByRole('button', { name: `${updatedTitle} 보관` }).click()
  const archivePath = `${updatePath}/archive`
  expectScopedCall(await recordedCall(api, 'PATCH', archivePath), { archived: true })
  await expect(page.getByRole('heading', { name: updatedTitle })).toHaveCount(0)

  const archiveSummary = page.getByText('보관한 결정 1개', { exact: true })
  await archiveSummary.scrollIntoViewIfNeeded()
  await archiveSummary.click()
  await page.getByRole('button', { name: `${updatedTitle} 복원` }).click()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  ).length).toBe(2)
  const archiveCalls = api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  )
  expectScopedCall(archiveCalls[0]!, { archived: true })
  expectScopedCall(archiveCalls[1]!, { archived: false })
  await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible()
  expect(api.projection().decisions.find((decision) => decision.id === DECISION_ID)).toMatchObject({
    createdAt: '2026-07-03T12:00:00Z',
    archivedAt: null,
  })
})
test('@memory 결정 저장 응답 유실 뒤 reload해도 같은 요청으로 결과를 회수한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.commitNextContentCreationThenTimeout('decision')
  await openSharedWorkspace(page)

  const openAndFillDecision = async () => {
    await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
    await page.getByRole('button', { name: '결정 남기기' }).click()
    const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
    await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('응답 유실 재시도 규칙을 유지한다')
    await dialog.getByLabel('왜 이 선택을 했나요?').fill('같은 결정이 두 번 저장되는 것을 막기 위해서입니다.')
    await dialog.getByLabel('검토한 다른 선택').fill('사용자가 직접 중복을 정리한다')
    await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
    await expect(dialog.getByRole('checkbox', { name: '문제 큐레이터' })).toBeChecked()
    return dialog
  }

  const firstDialog = await openAndFillDecision()
  await firstDialog.getByRole('button', { name: '결정 기록하기' }).click()
  await expect(firstDialog.getByRole('alert')).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')

  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  const pendingAfterTimeout = await pendingContentCreationEntries(page)
  expect(pendingAfterTimeout).toHaveLength(1)
  expect(pendingAfterTimeout[0]).toMatchObject({
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    operation: 'decision',
    idempotencyKey: firstAttempt.headers['idempotency-key'],
  })
  expect(api.projection().decisions.filter((decision) => decision.title === '응답 유실 재시도 규칙을 유지한다')).toHaveLength(1)

  await page.reload()
  const retryDialog = await openAndFillDecision()
  await expect(retryDialog.getByRole('status').filter({ hasText: '저장 결과를 확인하지 못했습니다.' })).toContainText('저장 결과를 확인하지 못했습니다.')
  await retryDialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '응답 유실 재시도 규칙을 유지한다' })).toBeVisible()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/decisions`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).toBe(firstAttempt.headers['idempotency-key'])
  expect(api.projection().decisions.filter((decision) => decision.title === '응답 유실 재시도 규칙을 유지한다')).toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@memory 결정 생성 연결이 끊겨도 같은 요청으로 안전하게 재제출한다', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === 'mobile', '전송 오류 복구 의미는 데스크톱 Chromium에서 한 번만 검증합니다.')
  const api = await installApi(page)
  let firstIdempotencyKey: string | undefined
  await page.route(
    (url) => url.pathname === `${SCOPE_PATH}/decisions`,
    async (route) => {
      firstIdempotencyKey = route.request().headers()['idempotency-key']
      await route.abort('connectionreset')
    },
    { times: 1 },
  )
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  await page.getByRole('button', { name: '결정 남기기' }).click()

  const dialog = page.getByRole('dialog', { name: '결정과 이유 남기기' })
  await dialog.getByLabel('무엇을 바꾸기로 했나요?').fill('연결 오류에도 같은 결정을 다시 확인한다')
  await dialog.getByLabel('왜 이 선택을 했나요?').fill('응답을 모를 때 새 요청을 만들지 않기 위해서입니다.')
  await dialog.getByLabel('검토한 다른 선택').fill('목록에서 수동으로 중복을 찾는다')
  await dialog.getByLabel('작성자').selectOption(MEMBER_TWO_ID)
  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  const alert = dialog.getByRole('alert')
  await expect(alert).toContainText('서버에 연결하지 못해 요청 결과를 확인할 수 없습니다.')
  await expect(alert).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')
  expect(firstIdempotencyKey).toMatch(/^[A-Za-z0-9._~-]{32,200}$/)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      teamId: TEAM_ID,
      seasonId: SEASON_ID,
      operation: 'decision',
      idempotencyKey: firstIdempotencyKey,
    }),
  ])

  await dialog.getByRole('button', { name: '결정 기록하기' }).click()

  await expect(page.getByRole('heading', { name: '연결 오류에도 같은 결정을 다시 확인한다' })).toBeVisible()
  const retry = await recordedCall(api, 'POST', `${SCOPE_PATH}/decisions`)
  expect(retry.headers['idempotency-key']).toBe(firstIdempotencyKey)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@records 결정·인수인계·자료를 한 흐름에서 검색하고 원본 기록으로 돌아간다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.roles.push({
    previousRoleId: null,
    id: SECOND_ROLE_ID,
    name: '회고 진행자',
    purpose: '회고 질문을 정리하고 다음 행동을 확정합니다.',
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: null,
    assignmentStartDate: '2026-07-02',
    assignmentEndDate: '2026-09-17',
    responsibilities: ['회고 질문 정리'],
    risk: null,
  })
  initialProjection.handoffItems[1]!.archivedAt = '2026-07-20T03:00:00Z'
  initialProjection.resources.push({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '문제 선정 운영 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '다음 담당자가 바로 적용할 운영 기준입니다.',
    createdAt: '2026-07-05T03:00:00Z',
    archivedAt: null,
  })
  initialProjection.resources.push({
    id: SECOND_ROLE_RESOURCE_ID,
    roleId: SECOND_ROLE_ID,
    title: '회고 질문 가이드',
    url: 'https://docs.example.com/retrospective',
    description: '회고 진행자가 질문 순서를 정할 때 사용합니다.',
    createdAt: '2026-07-03T15:30:00Z',
    archivedAt: null,
  })
  await installApi(page, initialProjection)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  let search = page.getByRole('search', { name: '결정, 인수인계와 자료 검색' })
  await expect(page.getByRole('heading', { name: '5개의 기록을 찾았어요' })).toBeVisible()
  expect(await search.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)

  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('풀이 비교 문제 큐레이터')
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  const decisionResult = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: '한 회차의 문제 수를 5개로 정한다' }),
  })
  await expect(decisionResult).toContainText('풀이를 비교하는 시간을 확보하기 위해서입니다.')
  await expect(decisionResult).toContainText('모임 시간을 늘리기')
  await expect(decisionResult).toContainText('문제 큐레이터')
  await expect(decisionResult).toContainText('박민서')
  await decisionResult.getByRole('button', {
    name: '한 회차의 문제 수를 5개로 정한다 결정 기록에서 보기',
  }).click()

  const decisionEntry = page.locator(`[data-decision-id="${DECISION_ID}"]`)
  await expect(decisionEntry).toBeVisible()
  await expect(decisionEntry).toBeFocused()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  search = page.getByRole('search', { name: '결정, 인수인계와 자료 검색' })
  await expect(search.getByLabel('무엇을 다시 찾고 있나요?'))
    .toHaveValue('풀이 비교 문제 큐레이터')
  await search.getByRole('button', { name: '검색 조건 지우기' }).click()
  await search.getByLabel('관련 역할').selectOption(SECOND_ROLE_ID)
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '회고 질문 가이드' })).toBeVisible()

  await search.getByRole('button', { name: '검색 조건 지우기' }).click()
  await search.getByLabel('기록 종류').selectOption('resource')
  await search.getByLabel('시작일').fill('2026-07-04')
  await search.getByLabel('종료일').fill('2026-07-04')
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '회고 질문 가이드' })).toBeVisible()

  await search.getByRole('button', { name: '검색 조건 지우기' }).click()
  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('역할의 한 줄 목적')
  await search.getByLabel('기록 종류').selectOption('handoff')
  await page.getByRole('button', {
    name: '역할의 한 줄 목적 인수인계 문서에서 보기',
  }).click()
  const handoffItem = page.locator(`[data-handoff-item-id="${HANDOFF_ONE_ID}"]`)
  await expect(handoffItem).toBeVisible()
  await expect(handoffItem).toBeFocused()

  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  search = page.getByRole('search', { name: '결정, 인수인계와 자료 검색' })
  await expect(search.getByLabel('무엇을 다시 찾고 있나요?')).toHaveValue('역할의 한 줄 목적')
  await search.getByRole('button', { name: '검색 조건 지우기' }).click()
  await search.getByLabel('상태').selectOption('archived')
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  const archivedResult = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: '자주 생기는 문제와 대응법' }),
  })
  await expect(archivedResult).toContainText('기록 시각 미상')
  await expect(archivedResult.getByRole('button', { name: '인수인계 문서에서 보기' })).toHaveCount(0)

  await search.getByLabel('시작일').fill('2026-07-01')
  await expect(page.getByRole('heading', { name: '0개의 기록을 찾았어요' })).toBeVisible()
  await expect(page.getByText('생성 시각을 알 수 없는 이전 기록 1개는 기간 검색에서 제외했습니다.')).toBeVisible()

  await search.getByRole('button', { name: '검색 조건 지우기' }).click()
  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('docs.example.com')
  await expect(page.getByRole('heading', { name: '0개의 기록을 찾았어요' })).toBeVisible()
  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('운영 기준')
  await search.getByLabel('기록 종류').selectOption('resource')
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  await expect(page.getByRole('link', { name: '문제 선정 운영 문서 자료 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/problem-selection')
  await page.getByRole('button', { name: '문제 선정 운영 문서 역할에서 보기' }).click()
  const roleInspector = page.getByLabel(/선택한 역할 상세/)
  await expect(roleInspector.getByRole('link', {
    name: '문제 선정 운영 문서 새 창에서 열기',
  })).toBeVisible()
})

test('@records 보관한 역할 자료는 보관 기록으로만 탐색한다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.resources.push({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '보관한 문제 선정 기준',
    url: 'https://docs.example.com/archived-problem-selection',
    description: '이전 시즌에 사용한 문제 선정 기준입니다.',
    createdAt: '2026-07-06T03:00:00Z',
    archivedAt: '2026-07-20T03:00:00Z',
  })
  await installApi(page, projection)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  const search = page.getByRole('search', { name: '결정, 인수인계와 자료 검색' })
  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('보관한 문제 선정 기준')
  await search.getByLabel('기록 종류').selectOption('resource')
  await search.getByLabel('상태').selectOption('active')
  await expect(page.getByRole('heading', { name: '0개의 기록을 찾았어요' })).toBeVisible()

  await search.getByLabel('상태').selectOption('archived')
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  const result = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: '보관한 문제 선정 기준' }),
  })
  await expect(result).toContainText('보관됨')
  await expect(result.getByRole('link', { name: '보관한 문제 선정 기준 자료 새 창에서 열기' }))
    .toHaveAttribute('href', 'https://docs.example.com/archived-problem-selection')
  await expect(result.getByRole('button', { name: /역할에서 보기/ })).toHaveCount(0)
})

test('@handoff 역할 인수인계를 준비하고 경고 확인 후 전달·수락해 역할 배정을 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '인수인계 준비 시작' }).click()

  const prepareDialog = page.getByRole('dialog', { name: '역할 인수인계 준비 시작' })
  await expect(prepareDialog.getByLabel('다음 담당자')).toHaveValue(MEMBER_TWO_ID)
  await prepareDialog.getByLabel('다음 담당 시작일').fill('2026-09-17')
  await prepareDialog.getByLabel('다음 담당 종료일').fill('2026-09-17')
  await prepareDialog.getByRole('button', { name: '인수인계 준비 시작' }).click()

  await expect(prepareDialog).toBeHidden()
  await expect(page.getByText('준비 중', { exact: true })).toBeVisible()
  await expect(page.getByText('김준호님에게 전달할 인수인계를 검토하세요')).toBeVisible()
  const prepareCall = await recordedCall(
    api,
    'POST',
    `${SCOPE_PATH}/roles/${ROLE_ID}/handoffs`,
  )
  expectScopedCall(prepareCall, {
    toMemberId: MEMBER_TWO_ID,
    incomingAssignmentStartDate: '2026-09-17',
    incomingAssignmentEndDate: '2026-09-17',
  })

  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.getByRole('button', { name: '문제 큐레이터 역할 수정' }).click()
  const roleDialog = page.getByRole('dialog', { name: '역할 수정' })
  await expect(roleDialog).toContainText(
    '인수인계 준비 중에는 담당자와 담당 기간이 전달 기록에 고정됩니다.',
  )
  await expect(roleDialog.getByLabel('현재 담당자')).toBeDisabled()
  await expect(roleDialog.getByLabel('다음 담당자')).toBeDisabled()
  await expect(roleDialog.getByLabel('담당 시작일')).toBeDisabled()
  await expect(roleDialog.getByLabel('담당 종료일')).toBeDisabled()
  await expect(roleDialog.getByLabel('이 역할이 존재하는 이유')).toBeEnabled()
  await roleDialog.getByLabel('이 역할이 존재하는 이유')
    .fill('인수인계 준비 중에도 역할 설명은 계속 보완합니다.')
  await roleDialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(roleDialog).toBeHidden()
  expectScopedCall(
    await recordedCall(api, 'PUT', `${SCOPE_PATH}/roles/${ROLE_ID}`),
    {
      name: '문제 큐레이터',
      purpose: '인수인계 준비 중에도 역할 설명은 계속 보완합니다.',
      currentMemberId: MEMBER_ONE_ID,
      nextMemberId: MEMBER_TWO_ID,
      assignmentStartDate: '2026-07-02',
      assignmentEndDate: '2026-09-17',
      responsibilities: ['문제 5개 선정', '난이도 균형 확인'],
      risk: '문제 선정 기준이 개인 메모에만 있어요.',
    },
  )

  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '인수인계 전달 검토' }).click()
  const transferDialog = page.getByRole('dialog', { name: '인수인계 전달 전 확인' })
  const readiness = transferDialog.getByLabel('전달 전 체크리스트와 자료 현황')
  await expect(readiness).toContainText('활성 항목2')
  await expect(readiness).toContainText('미완료1')
  await expect(readiness).toContainText('참고 자료0')
  await expect(transferDialog).toContainText('공유 링크는 사람을 인증하지 않습니다.')
  await expect(transferDialog).toContainText('박민서 명의로 전달했다고 기록됩니다.')
  const transferButton = transferDialog.getByRole('button', { name: '인수인계 전달하기' })
  await expect(transferButton).toBeDisabled()
  await transferDialog.getByLabel('미완료 항목과 자료 누락을 확인했습니다').check()
  await expect(transferButton).toBeEnabled()
  await transferButton.click()

  await expect(transferDialog).toBeHidden()
  await expect(page.getByText('수락 대기', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('전달한 인수인계 문서는 수락하거나 취소하기 전까지')).toBeVisible()
  await expect(page.getByRole('button', { name: '항목 추가', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '자주 생기는 문제와 대응법 수정' }))
    .toBeDisabled()
  const transferCall = await recordedCall(
    api,
    'PATCH',
    `${SCOPE_PATH}/roles/${ROLE_ID}/handoffs/${ROLE_HANDOFF_ID}/transfer`,
  )
  expectScopedCall(transferCall, {
    confirmedByMemberId: MEMBER_ONE_ID,
    warningAcknowledged: true,
  })

  await page.getByRole('button', { name: '인수인계 수락', exact: true }).click()
  const acceptDialog = page.getByRole('dialog', { name: '역할 인수인계 수락' })
  await expect(acceptDialog).toContainText('공유 링크는 사람을 인증하지 않습니다.')
  await expect(acceptDialog).toContainText('김준호 명의로 수락했다고 기록됩니다.')
  await acceptDialog.getByRole('button', { name: '김준호님 명의로 수락 기록' }).click()

  await expect(acceptDialog).toBeHidden()
  await expect(page.getByText('최근 인수인계 수락 완료')).toBeVisible()
  await expect(page.getByText('김준호님의 수락을 기록했어요')).toBeVisible()
  const acceptCall = await recordedCall(
    api,
    'PATCH',
    `${SCOPE_PATH}/roles/${ROLE_ID}/handoffs/${ROLE_HANDOFF_ID}/acceptance`,
  )
  expectScopedCall(acceptCall, { confirmedByMemberId: MEMBER_TWO_ID })
  expect(api.projection().roleHandoffs[0]).toMatchObject({
    id: ROLE_HANDOFF_ID,
    status: 'ACCEPTED',
    activeItemCount: 2,
    incompleteItemCount: 1,
    resourceCount: 0,
    warningAcknowledged: true,
  })
  expect(api.projection().roles[0]).toMatchObject({
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: null,
    assignmentStartDate: '2026-09-17',
    assignmentEndDate: '2026-09-17',
  })

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await expect(page.getByText('최근 인수인계 수락 완료')).toBeVisible()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await expect(page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }))
    .toContainText('김준호')
})

test('@handoff 역할 탭은 방향키로 순환하고 선택한 tabpanel을 연결한다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.roles.push({
    previousRoleId: null,
    id: SECOND_ROLE_ID,
    name: '질문 큐레이터',
    purpose: '구성원이 막힌 지점을 다음 모임의 질문으로 정리합니다.',
    currentMemberId: MEMBER_TWO_ID,
    nextMemberId: MEMBER_THREE_ID,
    assignmentStartDate: '2026-07-02',
    assignmentEndDate: '2026-09-17',
    responsibilities: ['막힌 지점 수집', '질문 순서 정리'],
    risk: '',
  })
  await installApi(page, projection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()

  const tablist = page.getByRole('tablist', { name: '역할별 인수인계' })
  const first = tablist.getByRole('tab', { name: /^문제 큐레이터/ })
  const second = tablist.getByRole('tab', { name: /^질문 큐레이터/ })
  const panel = page.getByRole('tabpanel')

  await expect(first).toHaveAttribute('aria-selected', 'true')
  await expect(first).toHaveAttribute('tabindex', '0')
  await expect(second).toHaveAttribute('tabindex', '-1')
  await expect(first).toHaveAttribute('aria-controls', await panel.getAttribute('id') ?? '')
  await expect(panel).toHaveAttribute('aria-labelledby', await first.getAttribute('id') ?? '')

  await first.focus()
  await page.keyboard.press('ArrowRight')
  await expect(second).toBeFocused()
  await expect(second).toHaveAttribute('aria-selected', 'true')
  await expect(second).toHaveAttribute('tabindex', '0')
  await expect(first).toHaveAttribute('tabindex', '-1')
  await expect(panel).toHaveAttribute('aria-labelledby', await second.getAttribute('id') ?? '')
  await expect(page.getByRole('heading', { name: '최유진님에게 넘길 인수인계' })).toBeVisible()

  await page.keyboard.press('ArrowRight')
  await expect(first).toBeFocused()
  await page.keyboard.press('End')
  await expect(second).toBeFocused()
  await page.keyboard.press('Home')
  await expect(first).toBeFocused()
})

test('@handoff 역할 자료 생성 응답 유실 뒤 같은 요청으로 결과를 회수한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.commitNextContentCreationThenTimeout('roleResource')
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()

  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '자료 추가' }).click()
  const createDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await createDialog.getByLabel('역할').selectOption(ROLE_ID)
  await createDialog.getByLabel('링크').fill('https://docs.example.com/problem-selection')
  await createDialog.getByRole('button', { name: '자료 연결하기' }).click()
  await expect(createDialog.getByRole('alert')).toContainText('자료 이름을 입력해 주세요.')
  await expect(createDialog.getByLabel('자료 이름')).toBeFocused()
  expect(api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/role-resources`)).toHaveLength(0)

  await createDialog.getByLabel('자료 이름').fill('문제 선정 기준 문서')
  await createDialog.getByLabel('자료 설명').fill('매주 문제 후보를 고를 때 확인하는 기준입니다.')
  await createDialog.getByRole('button', { name: '자료 연결하기' }).click()
  await expect(createDialog.getByRole('alert')).toContainText('입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.')

  const firstCreateCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/role-resources`)
  expectScopedCall(firstCreateCall, {
    roleId: ROLE_ID,
    title: '문제 선정 기준 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '매주 문제 후보를 고를 때 확인하는 기준입니다.',
  })
  const pendingAfterTimeout = (await pendingContentCreationEntries(page))
    .filter((entry) => entry.operation === 'roleResource')
  expect(pendingAfterTimeout).toHaveLength(1)
  expect(pendingAfterTimeout[0]?.idempotencyKey).toBe(firstCreateCall.headers['idempotency-key'])
  expect(api.projection().resources.filter((resource) => resource.title === '문제 선정 기준 문서')).toHaveLength(1)

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  await inspector.getByRole('button', { name: '자료 추가' }).click()
  const retryDialog = page.getByRole('dialog', { name: '역할에 참고 자료 연결' })
  await retryDialog.getByLabel('역할').selectOption(ROLE_ID)
  await retryDialog.getByLabel('자료 이름').fill('문제 선정 기준 문서')
  await retryDialog.getByLabel('링크').fill('https://docs.example.com/problem-selection')
  await retryDialog.getByLabel('자료 설명').fill('매주 문제 후보를 고를 때 확인하는 기준입니다.')
  await expect(retryDialog.getByRole('status').filter({ hasText: '저장 결과를 확인하지 못했습니다.' })).toContainText('저장 결과를 확인하지 못했습니다.')
  await retryDialog.getByRole('button', { name: '자료 연결하기' }).click()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/role-resources`,
  ).length).toBe(2)
  const createAttempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/role-resources`,
  )
  expect(createAttempts).toHaveLength(2)
  expect(createAttempts[1]?.headers['idempotency-key']).toBe(firstCreateCall.headers['idempotency-key'])
  expect(api.projection().resources.filter((resource) => resource.title === '문제 선정 기준 문서')).toHaveLength(1)
  await expect.poll(async () => (await pendingContentCreationEntries(page))
    .filter((entry) => entry.operation === 'roleResource').length).toBe(0)

  const createdLink = inspector.getByRole('link', {
    name: '문제 선정 기준 문서 새 창에서 열기',
  })
  await expect(createdLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection')
  await expect(createdLink).toHaveAttribute('target', '_blank')
  await expect(createdLink).toHaveAttribute('rel', 'noopener noreferrer')
})

test('@handoff 역할 자료를 수정하고 인수인계 문서와 다시 불러온 화면에서 확인한다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.resources.push({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '문제 선정 기준 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '매주 문제 후보를 고를 때 확인하는 기준입니다.',
    createdAt: '2026-07-06T03:00:00Z',
    archivedAt: null,
  })
  const api = await installApi(page, initialProjection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()

  const inspector = page.getByLabel('선택한 역할 상세')

  await inspector.getByRole('button', { name: '문제 선정 기준 문서 자료 수정' }).click()
  const updateDialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await updateDialog.getByLabel('자료 이름').fill('문제 선정 기준 최신본')
  await updateDialog.getByLabel('링크').fill('https://docs.example.com/problem-selection-v2')
  await updateDialog.getByLabel('자료 설명').fill('난이도와 풀이 시간을 같이 확인하는 최신 기준입니다.')
  await updateDialog.getByRole('button', { name: '변경 저장' }).click()

  const updateCall = await recordedCall(
    api,
    'PUT',
    `${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}`,
  )
  expectScopedCall(updateCall, {
    roleId: ROLE_ID,
    title: '문제 선정 기준 최신본',
    url: 'https://docs.example.com/problem-selection-v2',
    description: '난이도와 풀이 시간을 같이 확인하는 최신 기준입니다.',
  })
  const updatedLink = inspector.getByRole('link', {
    name: '문제 선정 기준 최신본 새 창에서 열기',
  })
  await expect(updatedLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection-v2')
  await expect(updatedLink).toHaveAttribute('target', '_blank')
  await expect(updatedLink).toHaveAttribute('rel', 'noopener noreferrer')

  if (testInfo.project.name === 'mobile') {
    await inspector.getByRole('button', { name: '상세 닫기' }).click()
  }
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '인수인계 문서 미리보기' }).click()
  const preview = page.getByRole('dialog', { name: '문제 큐레이터 인수인계 문서' })
  const previewLink = preview.getByRole('link', {
    name: '문제 선정 기준 최신본 새 창에서 열기',
  })
  await expect(previewLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection-v2')
  await expect(previewLink).toHaveAttribute('target', '_blank')
  await expect(previewLink).toHaveAttribute('rel', 'noopener noreferrer')
  await expect(preview.getByText('난이도와 풀이 시간을 같이 확인하는 최신 기준입니다.')).toBeVisible()
  await preview.getByRole('button', { name: '미리보기 닫기' }).click()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  const reloadedLink = page.getByLabel('선택한 역할 상세').getByRole('link', {
    name: '문제 선정 기준 최신본 새 창에서 열기',
  })
  await expect(reloadedLink).toHaveAttribute('href', 'https://docs.example.com/problem-selection-v2')

  const storedProductData = await page.evaluate((needles) => {
    const matches: string[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const value = localStorage.getItem(localStorage.key(index) ?? '') ?? ''
      if (needles.some((needle) => value.includes(needle))) matches.push(value)
    }
    return matches
  }, [
    '문제 선정 기준 문서',
    'https://docs.example.com/problem-selection',
    '문제 선정 기준 최신본',
    'https://docs.example.com/problem-selection-v2',
  ])
  expect(storedProductData).toEqual([])
})

test('@handoff 역할 자료를 보관함으로 옮기고 복원한다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.resources.push({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '문제 선정 기준 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '매주 문제 후보를 고를 때 확인하는 기준입니다.',
    createdAt: '2026-07-06T03:00:00Z',
    archivedAt: null,
  })
  const api = await installApi(page, initialProjection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()

  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '문제 선정 기준 문서 자료 보관' }).click()
  await expect(inspector.getByRole('link', {
    name: '문제 선정 기준 문서 새 창에서 열기',
  })).toHaveCount(0)
  await inspector.getByText('자료 보관함 1개').click()
  const restore = inspector.getByRole('button', { name: '문제 선정 기준 문서 자료 복원' })
  await expect(restore).toBeVisible()
  expectScopedCall(
    await recordedCall(
      api,
      'PATCH',
      `${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/archive`,
    ),
    { archived: true },
  )

  await restore.click()
  await expect(inspector.getByRole('link', {
    name: '문제 선정 기준 문서 새 창에서 열기',
  })).toBeVisible()
  const archiveCalls = api.calls.filter(
    (call) => call.method === 'PATCH'
      && call.path === `${SCOPE_PATH}/role-resources/${CREATED_ROLE_RESOURCE_ID}/archive`,
  )
  expect(archiveCalls.map((call) => call.body)).toEqual([
    { archived: true },
    { archived: false },
  ])
})

test('@handoff 역할 자료 충돌은 낡은 폼을 닫고 최신 내용을 다시 연다', async ({ page }, testInfo) => {
  const initialProjection = makeProjection()
  initialProjection.resources.push({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '문제 선정 기준 문서',
    url: 'https://docs.example.com/problem-selection',
    description: '기존 기준입니다.',
    createdAt: '2026-07-06T03:00:00Z',
    archivedAt: null,
  })
  const api = await installApi(page, initialProjection)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()

  const inspector = page.getByLabel('선택한 역할 상세')
  await inspector.getByRole('button', { name: '문제 선정 기준 문서 자료 수정' }).click()
  const dialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await dialog.getByLabel('자료 이름').fill('내 화면의 낡은 수정')
  await dialog.getByLabel('링크').fill('https://docs.example.com/stale-edit')

  api.conflictNextRoleResourceUpdate({
    id: CREATED_ROLE_RESOURCE_ID,
    roleId: ROLE_ID,
    title: '다른 구성원이 갱신한 기준',
    url: 'https://docs.example.com/remote-edit',
    description: '서버의 최신 기준입니다.',
    createdAt: '2026-07-06T03:00:00Z',
    archivedAt: null,
  })
  await dialog.getByRole('button', { name: '변경 저장' }).click()

  await expect(dialog).toBeHidden()
  await expect(page.locator('.toast[role="status"]')).toContainText('다른 구성원이 먼저 바꾼 최신 작업 공간을 불러왔어요')
  const latestLink = inspector.getByRole('link', {
    name: '다른 구성원이 갱신한 기준 새 창에서 열기',
  })
  await expect(latestLink).toHaveAttribute('href', 'https://docs.example.com/remote-edit')

  await inspector.getByRole('button', { name: '다른 구성원이 갱신한 기준 자료 수정' }).click()
  const reopenedDialog = page.getByRole('dialog', { name: '참고 자료 수정' })
  await expect(reopenedDialog.getByLabel('자료 이름')).toHaveValue('다른 구성원이 갱신한 기준')
  await expect(reopenedDialog.getByLabel('링크')).toHaveValue('https://docs.example.com/remote-edit')
  await expect(reopenedDialog.getByLabel('자료 설명')).toHaveValue('서버의 최신 기준입니다.')
})

test('@handoff 재사용할 수 없는 생성 요청은 pending을 지우고 다음 제출에 새 키를 쓴다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  api.rejectNextContentCreationAsReused('handoffItem')
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('재사용 종료 확인')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('목록에 항목이 이미 생겼는지 확인한 뒤, 필요하면 다시 제출해 주세요.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)

  await dialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(page.getByRole('checkbox', { name: '재사용 종료 확인' })).toBeVisible()
  const attempts = api.calls.filter((call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`)
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff 콘텐츠 terminal 기록 cleanup이 실패하면 같은 키 재전송을 막는다', async ({ page }, testInfo) => {
  await failNextJournalCleanup(
    page,
    { storagePrefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX },
    'baton-e2e-content-terminal-cleanup-failure',
  )
  const api = await installApi(page)
  api.rejectNextContentCreationAsReused('handoffItem')
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('terminal cleanup 재전송 차단')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('완료 기록을 정리하지 못해 같은 요청을 다시 보내지 않았습니다.')
  const firstAttempt = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({
      operation: 'handoffItem',
      idempotencyKey: firstAttempt.headers['idempotency-key'],
    }),
  ])

  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert')).toContainText('완료 기록을 정리했습니다.')
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(1)

  await dialog.getByRole('button', { name: '항목 추가하기' }).click()
  await expect(page.getByRole('checkbox', { name: 'terminal cleanup 재전송 차단' })).toBeVisible()
  const attempts = api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )
  expect(attempts).toHaveLength(2)
  expect(attempts[1]?.headers['idempotency-key']).not.toBe(firstAttempt.headers['idempotency-key'])
})

test('@handoff 같은 인수인계 생성 요청의 탭 경합은 한 번만 전송한다', async ({ page, context }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)

  const peerPage = await context.newPage()
  await api.attachPage(peerPage)
  api.holdNextContentCreation('handoffItem')

  try {
    await openSharedWorkspace(peerPage)
    await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
    await navigation(peerPage, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()

    await page.getByRole('button', { name: '항목 추가' }).click()
    const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
    await dialog.getByLabel('역할').selectOption(ROLE_ID)
    await dialog.getByLabel('남길 내용').fill('멀티탭 생성 잠금 확인')
    await dialog.getByLabel('항목 종류').selectOption('RESPONSIBILITY')

    await peerPage.getByRole('button', { name: '항목 추가' }).click()
    const peerDialog = peerPage.getByRole('dialog', { name: '인수인계 항목 추가' })
    await peerDialog.getByLabel('역할').selectOption(ROLE_ID)
    await peerDialog.getByLabel('남길 내용').fill('멀티탭 생성 잠금 확인')
    await peerDialog.getByLabel('항목 종류').selectOption('RESPONSIBILITY')

    await dialog.getByRole('button', { name: '항목 추가하기' }).click()
    const handoffCreateCalls = () => api.calls.filter(
      (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
    ).length
    await expect.poll(handoffCreateCalls).toBe(1)

    await peerDialog.getByRole('button', { name: '항목 추가하기' }).click()
    await expect(peerDialog.getByRole('alert'))
      .toContainText('다른 탭에서 콘텐츠 생성 요청을 처리 중입니다.')
    expect(handoffCreateCalls()).toBe(1)

    api.releaseContentCreation()

    await expect(dialog).toHaveCount(0)
    await expect.poll(() =>
      api.projection().handoffItems.filter((item) => item.label === '멀티탭 생성 잠금 확인').length,
    ).toBe(1)
    await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
    expect(handoffCreateCalls()).toBe(1)
  } finally {
    api.releaseContentCreation()
    await peerPage.close()
  }
})

test('@handoff Web Locks를 사용할 수 없으면 인수인계 생성 요청을 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: undefined,
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('Web Locks 미지원 차단')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert'))
    .toContainText('탭 사이의 콘텐츠 생성 요청을 안전하게 조정할 수 없습니다.')
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(0)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff Web Locks 요청이 실패하면 인수인계 생성 요청을 보내지 않는다', async ({ page }, testInfo) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: () => Promise.reject(new Error('Web Locks request failed')),
      },
    })
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('Web Locks 요청 실패 차단')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  await expect(dialog.getByRole('alert'))
    .toContainText('콘텐츠 생성 요청의 안전 잠금을 확인하지 못했습니다.')
  expect(api.calls.filter(
    (call) => call.method === 'POST' && call.path === `${SCOPE_PATH}/handoff-items`,
  )).toHaveLength(0)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(0)
})

test('@handoff 한 탭의 성공은 다른 탭이 보관한 같은 내용의 pending을 지우지 않는다', async ({ page }, testInfo) => {
  const firstKey = 'content-race-key-00000000000000000001'
  const secondKey = 'content-race-key-00000000000000000002'
  await page.addInitScript(({ prefix, teamId, seasonId, roleId, first, second }) => {
    const normalizedPayload = JSON.stringify({
      roleId,
      label: '멀티탭 복구 보존',
      category: 'RESPONSIBILITY',
    })
    const records = [
      { idempotencyKey: first, createdAt: 1 },
      { idempotencyKey: second, createdAt: 2 },
    ]
    records.forEach(({ idempotencyKey, createdAt }) => {
      localStorage.setItem(`${prefix}${idempotencyKey}`, JSON.stringify({
        teamId,
        seasonId,
        operation: 'handoffItem',
        normalizedPayload,
        idempotencyKey,
        createdAt,
        requestGuard: true,
      }))
    })
  }, {
    prefix: PENDING_CONTENT_CREATION_STORAGE_PREFIX,
    teamId: TEAM_ID,
    seasonId: SEASON_ID,
    roleId: ROLE_ID,
    first: firstKey,
    second: secondKey,
  })
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()
  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('남길 내용').fill('멀티탭 복구 보존')
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  const call = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  expect(call.headers['idempotency-key']).toBe(firstKey)
  await expect.poll(async () => (await pendingContentCreationEntries(page)).length).toBe(1)
  expect(await pendingContentCreationEntries(page)).toEqual([
    expect.objectContaining({ idempotencyKey: secondKey, requestGuard: true }),
  ])
})

test('@handoff @webkit 인수인계 문서 인쇄 중에는 주소의 접근 키를 숨기고 종료 뒤 복원한다', async ({ page, browserName }, testInfo) => {
  await page.addInitScript(() => {
    const originalSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function setItem(key, value) {
      if (key.startsWith('baton-access-key:')) throw new DOMException('저장소 사용 불가', 'SecurityError')
      originalSetItem.call(this, key, value)
    }
  })
  await installApi(page)
  const sharedPath = `${WORKSPACE_PATH}#accessKey=${ACCESS_KEY}`
  await page.goto(sharedPath)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '인수인계 문서 미리보기' }).click()
  const preview = page.getByRole('dialog', { name: '문제 큐레이터 인수인계 문서' })
  await expect(page).toHaveURL(sharedPath)

  await page.evaluate(() => {
    window.print = () => { document.documentElement.dataset.printUrl = window.location.href }
  })
  await preview.getByRole('button', { name: '인쇄 / PDF 저장' }).click()
  await expect(page).toHaveURL(WORKSPACE_PATH)
  expect(await page.locator('html').getAttribute('data-print-url')).toBe(page.url())
  if (browserName === 'chromium') {
    await page.pdf({
      path: testInfo.outputPath('baton-book.pdf'), format: 'A4', displayHeaderFooter: true,
      margin: { top: '20mm', bottom: '20mm', left: '15mm', right: '15mm' },
    })
  } else {
    await page.evaluate(() => window.dispatchEvent(new Event('afterprint')))
  }
  await expect(page).toHaveURL(sharedPath)
  await preview.getByRole('button', { name: '미리보기 닫기' }).click()
  await page.reload()
  await expect(page.getByRole('heading', { level: 1, name: /이번 회차 미완료 업무 \d+개/ })).toBeVisible()
  await expect(page).toHaveURL(sharedPath)
})

test('@handoff @webkit 인수인계 문서는 완료한 항목과 역할 맥락을 보존하고 기록만 출력한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await page.getByRole('button', { name: '항목 추가' }).click()

  const dialog = page.getByRole('dialog', { name: '인수인계 항목 추가' })
  await dialog.getByLabel('역할').selectOption(ROLE_ID)
  await dialog.getByLabel('남길 내용').fill('문제 선정 기준 문서 링크')
  await dialog.getByLabel('항목 종류').selectOption({ label: '자료' })
  await dialog.getByRole('button', { name: '항목 추가하기' }).click()

  const createCall = await recordedCall(api, 'POST', `${SCOPE_PATH}/handoff-items`)
  expectScopedCall(createCall, { roleId: ROLE_ID, label: '문제 선정 기준 문서 링크', category: 'RESOURCE' })
  const checkbox = page.getByRole('checkbox', { name: '문제 선정 기준 문서 링크' })
  await expect(checkbox).not.toBeChecked()
  api.holdNextHandoffCompletion()
  await checkbox.click()
  await expect(checkbox).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 선정 기준 문서 링크 수정' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '문제 선정 기준 문서 링크 보관' })).toBeDisabled()
  await expect(page.getByRole('checkbox', { name: '역할의 한 줄 목적' })).toBeEnabled()
  await expect(page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })).toBeEnabled()
  api.releaseHandoffCompletion()
  await expect(checkbox).toBeChecked()
  const completionCall = await recordedCall(api, 'PATCH', `${SCOPE_PATH}/handoff-items/${CREATED_HANDOFF_ID}/completion`)
  expectScopedCall(completionCall, { completed: true })

  await page.getByRole('button', { name: '인수인계 문서 미리보기' }).click()
  const preview = page.getByRole('dialog', { name: '문제 큐레이터 인수인계 문서' })
  await expect(preview.getByText('문제 5개 선정', { exact: true })).toBeVisible()
  await expect(preview.getByText('난이도 균형 확인')).toBeVisible()
  await expect(preview.getByText('문제 선정 기준이 개인 메모에만 있어요.')).toBeVisible()
  await expect(preview.getByText('자주 생기는 문제와 대응법')).toBeVisible()
  const completedItem = preview.getByRole('listitem').filter({ hasText: '문제 선정 기준 문서 링크' })
  await expect(completedItem).toContainText('자료 · 정리 완료')
  await expect(preview.getByText('남은 정리 1건')).toBeVisible()
  await expect(preview.getByRole('button', { name: '인쇄 / PDF 저장' })).toBeVisible()
  await page.emulateMedia({ media: 'print' })
  await expect(page.locator('#root')).toBeHidden()
  await expect(preview.getByRole('button', { name: '인쇄 / PDF 저장' })).toBeHidden()
  await expect(completedItem).toBeVisible()
  await expect(preview.locator('.handoff-book')).toHaveCSS('max-height', 'none')
  await expect(preview.locator('.handoff-book')).toHaveCSS('overflow-y', 'visible')
  await page.emulateMedia({ media: 'screen' })
  await preview.getByRole('button', { name: '미리보기 닫기' }).click()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await expect(page.getByRole('checkbox', { name: '문제 선정 기준 문서 링크' })).toBeChecked()
})

test('@handoff 인수인계 완료 실패 롤백이 동시에 성공한 회차 상태를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()

  api.holdNextHandoffCompletion()
  api.failNextHandoffCompletion()
  api.holdWorkspaceGets()

  const handoffCheckbox = page.getByRole('checkbox', { name: '자주 생기는 문제와 대응법' })
  try {
    await handoffCheckbox.click()
    await expect(handoffCheckbox).toBeChecked()

    await navigation(page, testInfo.project.name).getByRole('button', { name: '오늘' }).click()
    await page.getByRole('button', { name: '풀이 노트 정리 완료 처리' }).click()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 상태 변경 중' }))
      .toBeDisabled()
    await recordedCall(
      api,
      'PATCH',
      `${SCOPE_PATH}/rounds/${ROUND_TWO_ID}/routine-executions/${ROUND_TWO_ROUTINE_TWO_EXECUTION_ID}/completion`,
    )

    api.releaseHandoffCompletion()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 상태 변경 중' }))
      .toBeDisabled()

    api.releaseWorkspaceGets()
    await expect(page.getByRole('button', { name: '풀이 노트 정리 완료 취소' })).toBeVisible()

    await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
    await expect(handoffCheckbox).not.toBeChecked()
  } finally {
    api.releaseWorkspaceGets()
  }
})

test('@handoff 완료한 인수인계 항목을 수정하고 보관·복원해 완료 상태를 보존한다', async ({ page }, testInfo) => {
  const api = await installApi(page)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()

  const originalLabel = '역할의 한 줄 목적'
  const updatedLabel = '역할의 한 줄 목적과 성공 기준'
  await expect(page.getByRole('checkbox', { name: originalLabel })).toBeChecked()
  await page.getByRole('button', { name: `${originalLabel} 수정` }).click()

  const dialog = page.getByRole('dialog', { name: '인수인계 항목 수정' })
  await dialog.getByLabel('남길 내용').fill(updatedLabel)
  await dialog.getByLabel('항목 종류').selectOption('ADVICE')
  await dialog.getByRole('button', { name: '변경 저장' }).click()

  const updatePath = `${SCOPE_PATH}/handoff-items/${HANDOFF_ONE_ID}`
  expectScopedCall(await recordedCall(api, 'PUT', updatePath), {
    roleId: ROLE_ID,
    label: updatedLabel,
    category: 'ADVICE',
  })
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toBeChecked()
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_ONE_ID)).toMatchObject({
    completed: true,
    archivedAt: null,
  })

  await page.getByRole('button', { name: `${updatedLabel} 보관` }).click()
  const archivePath = `${updatePath}/archive`
  expectScopedCall(await recordedCall(api, 'PATCH', archivePath), { archived: true })
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toHaveCount(0)
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_ONE_ID)).toMatchObject({
    completed: true,
    archivedAt: '2026-07-21T12:00:00Z',
  })

  const archiveSummary = page.getByText('보관한 인수인계 1개', { exact: true })
  await archiveSummary.scrollIntoViewIfNeeded()
  await archiveSummary.click()
  await page.getByRole('button', { name: `${updatedLabel} 복원` }).click()

  await expect.poll(() => api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  ).length).toBe(2)
  const archiveCalls = api.calls.filter(
    (call) => call.method === 'PATCH' && call.path === archivePath,
  )
  expectScopedCall(archiveCalls[0]!, { archived: true })
  expectScopedCall(archiveCalls[1]!, { archived: false })
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toBeChecked()

  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: /^인수인계/ }).click()
  await expect(page.getByRole('checkbox', { name: updatedLabel })).toBeChecked()
  expect(api.projection().handoffItems.find((item) => item.id === HANDOFF_ONE_ID)).toMatchObject({
    completed: true,
    archivedAt: null,
  })
})

test('@memory @records @responsive 결정 Markdown은 명시적으로 전환하고 안전한 미리보기와 검색을 제공한다', async ({ page }, testInfo) => {
  const initial = makeProjection()
  initial.decisions[0]!.reason = '**이전 별표는 그대로**'
  const api = await installApi(page, initial)
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  let entry = page.locator(`[data-decision-id="${DECISION_ID}"]`)
  await expect(entry.getByText('**이전 별표는 그대로**', { exact: true })).toBeVisible()
  await expect(entry.locator('.decision-reason strong')).toHaveCount(0)
  await entry.getByRole('button', { name: /수정$/ }).click()
  const dialog = page.getByRole('dialog', { name: '결정 기록 수정' })
  await dialog.getByRole('combobox', { name: '본문 형식' }).selectOption('MARKDOWN')
  const markdown = '**회고 준비**\n\n- 질문 수집\n- `회의록` 작성\n\n[공식 근거](https://example.com/hidden-link-destination)\n\n[실행 링크](javascript:alert%281%29)\n\n![외부 이미지](https://example.com/image.png)\n\n<iframe src="https://example.com"></iframe>'
  await dialog.getByLabel('왜 이 선택을 했나요?').fill(markdown)
  await dialog.getByRole('button', { name: '미리보기', exact: true }).click()
  const preview = dialog.getByLabel('결정 본문 미리보기')
  await expect(preview.locator('strong', { hasText: '회고 준비' })).toBeVisible()
  await expect(preview.getByRole('link', { name: '공식 근거' })).toHaveAttribute('href', 'https://example.com/hidden-link-destination')
  await expect(preview.locator('img, iframe, script, a[href^="javascript:"]')).toHaveCount(0)
  await dialog.getByRole('button', { name: '변경 저장' }).click()
  await expect(dialog).not.toBeVisible()
  expect(api.projection().decisions[0]!.textFormat).toBe('MARKDOWN')
  expect(api.projection().decisions[0]!.reason).toBe(markdown)
  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '기록' }).click()
  entry = page.locator(`[data-decision-id="${DECISION_ID}"]`)
  await expect(entry.locator('code', { hasText: '회의록' })).toBeVisible()
  await expect(entry.locator('img, iframe, a[href^="javascript:"]')).toHaveCount(0)
  await entry.screenshot({ path: testInfo.outputPath('decision-markdown.png') })
  await navigation(page, testInfo.project.name).getByRole('button', { name: '탐색' }).click()
  const search = page.getByRole('search', { name: '결정, 인수인계와 자료 검색' })
  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('회고 준비 질문 수집')
  await expect(page.getByRole('heading', { name: '1개의 기록을 찾았어요' })).toBeVisible()
  await search.getByLabel('무엇을 다시 찾고 있나요?').fill('hidden-link-destination')
  await expect(page.getByRole('article')).toHaveCount(0)
})


test('@memory @responsive 자료 확인은 로그인한 구성원의 기록과 변경 후 재확인을 표시한다', async ({ page }, testInfo) => {
  const projection = makeProjection()
  projection.resources.push({ id: CREATED_ROLE_RESOURCE_ID, roleId: ROLE_ID, title: '운영 안내',
    url: 'https://example.com/guide', description: null, archivedAt: null, createdAt: '2026-09-05T00:00:00Z' })
  await installApi(page, projection)
  const accountId = '8e448211-66ae-44ab-9888-c4960648c22b'
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true, accountId, csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'verification-csrf',
  } }))
  await page.route('**/api/v1/auth/csrf', route => route.fulfill({ json: {
    csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'verification-csrf',
  } }))
  await page.route('**/api/v1/account-memberships/current?*', route => route.fulfill({ json: {
    claimed: true, accountId, teamId: TEAM_ID, memberId: MEMBER_ONE_ID, claimedAt: '2026-09-05T00:00:00Z',
  } }))
  const resource = projection.resources.find(item => item.roleId === ROLE_ID)!
  await page.route('**/role-resources/*/verifications/schedule', route => route.fulfill({ json: {
    teamId: TEAM_ID, seasonId: SEASON_ID, resourceId: resource.id, version: -1,
    intervalDays: null, nextReviewOn: null, today: '2026-09-05', reviewDue: false,
  } }))
  let version = 0
  let verified = false
  await page.route('**/role-resources/*/verifications', route => {
    if (route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({ expectedAccountId: accountId, resourceVersion: 0,
        status: 'NEEDS_UPDATE', note: '접근 권한을 요청해야 합니다' })
      expect(route.request().headers()['x-csrf-token']).toBe('verification-csrf')
      verified = true
    }
    return route.fulfill({ json: { teamId: TEAM_ID, seasonId: SEASON_ID, resourceId: resource.id,
      resourceVersion: version, verifications: verified ? [{ id: '00000000-0000-4000-8000-000000000105',
        resourceVersion: 0, memberId: MEMBER_ONE_ID, memberName: '박민서', url: resource.url,
        status: 'NEEDS_UPDATE', note: '접근 권한을 요청해야 합니다', verifiedAt: '2026-09-05T03:00:00Z', current: version === 0 }] : [],
    } })
  })
  await openSharedWorkspace(page)
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  const panel = page.locator('.resource-verification').first()
  await panel.locator('summary').click()
  await expect(panel.getByText('아직 확인한 기록이 없습니다.')).toBeVisible()
  await panel.getByLabel('확인 결과').selectOption('NEEDS_UPDATE')
  await panel.getByLabel('확인 메모').fill('접근 권한을 요청해야 합니다')
  await panel.getByRole('button', { name: '내 확인 기록 남기기' }).click()
  await expect(panel.getByText('확인 기록을 저장했습니다.')).toBeVisible()
  await expect(panel.getByText('자료 수정이 필요합니다.')).toBeVisible()
  version = 1
  await page.reload()
  await navigation(page, testInfo.project.name).getByRole('button', { name: '역할' }).click()
  await page.locator('.role-row-open').filter({ hasText: '문제 큐레이터' }).click()
  await panel.locator('summary').click()
  await expect(panel.getByText('자료가 변경되어 재확인이 필요합니다.')).toBeVisible()
  await expect(panel.getByText('수정 필요 · 이전 자료 확인')).toBeVisible()
})
