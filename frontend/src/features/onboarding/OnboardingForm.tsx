import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { createWorkspace } from '@/features/workspace/api'
import { forgetWorkspaceDeviceState } from '@/features/workspace/deviceState'
import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import {
  resolveIdempotencyJournalFailure,
} from '@/shared/api/idempotencyJournal'
import type {
  IdempotencyJournalFailureResolution,
} from '@/shared/api/idempotencyJournal'
import { isJsonCleanupComplete } from '@/shared/lib/durableStorage'
import {
  readRecentWorkspaces,
  readRecentWorkspacesServerSnapshot,
  repairRecentWorkspaces,
  subscribeRecentWorkspaces,
  saveAccessKey,
} from '@/features/workspace/storage'
import type { RecentWorkspace } from '@/features/workspace/storage'
import type { CreateWorkspaceRequest } from '@/features/workspace/types'
import PendingWorkspaceCreationPanel from './PendingWorkspaceCreationPanel'
import {
  clearPendingWorkspaceCreation,
  isSamePendingWorkspaceCreationItem,
  isSameWorkspaceCreationRequest,
  listPendingWorkspaceCreations,
  preparePendingWorkspaceRecovery,
  prepareWorkspaceCreation,
  runWithWorkspaceCreationLock,
  subscribePendingWorkspaceCreations,
} from './pendingWorkspaceCreation'
import type {
  PendingWorkspaceCreationItem,
  PendingWorkspaceCreationListResult,
} from './pendingWorkspaceCreation'
import {
  MAX_INITIAL_MEMBER_COUNT,
  MAX_MEMBER_NAME_LENGTH,
  MAX_WORKSPACE_NAME_LENGTH,
} from './workspaceCreationConstraints'

function splitMemberNames(value: string) {
  return value.split(/[\n,]/).map((name) => name.trim()).filter(Boolean)
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED') {
    return '생성 뒤 접근 키가 변경되어 이전 결과를 다시 받을 수 없습니다. 작업 공간이 이미 만들어졌을 수 있으니 운영자나 기존 공유 링크를 먼저 확인해 주세요.'
  }
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_KEY_REUSED') {
    return '이전 생성 요청 키가 다른 요청에 사용됐습니다. 입력을 확인한 뒤 새 요청으로 다시 시도해 주세요.'
  }
  if (error instanceof ApiError || error instanceof ApiClientError) return error.message
  return '작업 공간을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

const pendingStorageRequiredMessage = '요청을 안전하게 저장할 수 없습니다. 시크릿 창이 아닌 일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const pendingCreationLimitMessage = '확인하지 못한 생성 요청이 5개 남아 새 요청을 저장할 수 없습니다. 아래에서 같은 요청의 결과를 확인하거나, 이미 확인한 복구 기록을 폐기한 뒤 다시 시도해 주세요.'
const creationBusyMessage = '다른 탭에서 작업 공간 생성 결과를 확인 중입니다. 처리가 끝난 뒤 다시 시도해 주세요.'
const creationLockUnsupportedMessage = '이 브라우저에서는 탭 사이의 생성 요청을 안전하게 조정할 수 없습니다. 브라우저를 최신 버전으로 업데이트하거나 다른 브라우저에서 다시 열어 주세요.'
const creationJournalCleanupRequiredMessage = '브라우저의 임시 요청 기록을 삭제하지 못했습니다. 브라우저 저장을 허용한 뒤 임시 요청 기록 삭제를 다시 시도해 주세요.'
const workspaceCapabilityRemovalFailedMessage = '이 기기에 저장된 작업 공간 접근 권한을 제거하지 못했습니다. 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const workspaceCapabilityPartialRemovalMessage = '접근 키는 제거했지만 최근 작업 공간 목록을 갱신하지 못했습니다. 목록의 링크로는 다시 열 수 없으며, 브라우저 저장을 허용한 뒤 목록을 다시 정리해 주세요.'
const roundContextRemovalFailedMessage = '접근 키와 최근 목록은 제거했지만 이 탭의 ROUND 입장 기록을 정리하지 못했습니다. 탭을 닫아 임시 기록을 지워 주세요.'
const workspaceCreationJournalPolicy = {
  startNewRequestCodes: new Set(['INVALID_INPUT', 'IDEMPOTENCY_KEY_REUSED']),
  confirmBeforeNewRequestCodes: new Set(['IDEMPOTENCY_REPLAY_EXPIRED']),
}

type NewWorkspaceRequestConfirmationReason =
  | 'pendingMissing'
  | 'pendingChanged'
  | 'concurrentAttempt'
  | 'replayExpired'

type NewWorkspaceRequestConfirmation = {
  request: CreateWorkspaceRequest
  reason: NewWorkspaceRequestConfirmationReason
}

type CreateWorkspaceVariables = {
  request: CreateWorkspaceRequest
  idempotencyKey: string
  creationKey?: string
}

type WorkspaceCreationCleanupRetry =
  | {
      kind: 'success'
      variables: CreateWorkspaceVariables
      destination: string
    }
  | {
      kind: 'terminalError'
      variables: CreateWorkspaceVariables
      resolution: Exclude<IdempotencyJournalFailureResolution, 'retrySameRequest'>
    }

function creationConfirmationMessage(
  reason: NewWorkspaceRequestConfirmationReason | 'cleanupRequired',
) {
  switch (reason) {
    case 'pendingMissing':
      return '이 입력의 복구 기록이 다른 탭에서 확인되었거나 폐기되었습니다. 작업 공간이 이미 만들어졌을 수 있으니 최근 목록이나 기존 공유 링크를 먼저 확인해 주세요.'
    case 'pendingChanged':
      return '이 입력의 복구 기록이 다른 탭에서 변경되었습니다. 어느 요청이 처리됐는지 최근 목록이나 다른 탭에서 확인한 뒤 새 요청으로 전환해 주세요.'
    case 'concurrentAttempt':
      return '이 입력을 제출하려는 동안 다른 탭에서 생성 요청을 처리하고 있었습니다. 같은 작업 공간이 이미 만들어졌을 수 있으니 최근 목록이나 다른 탭의 결과를 먼저 확인해 주세요.'
    case 'replayExpired':
      return '이전 생성 요청으로 만든 작업 공간의 접근 키가 이미 변경되어 결과를 다시 받을 수 없습니다. 운영자나 기존 공유 링크로 작업 공간을 확인한 뒤에만 새 요청으로 전환해 주세요.'
    case 'cleanupRequired':
      return creationJournalCleanupRequiredMessage
  }
}

function formatLastOpenedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export default function OnboardingForm() {
  const navigate = useNavigate()
  const teamNameInputRef = useRef<HTMLInputElement>(null)
  const [teamName, setTeamName] = useState('')
  const [seasonName, setSeasonName] = useState('')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [memberNamesInput, setMemberNamesInput] = useState('')
  const [creationKey, setCreationKey] = useState('')
  const [validationMessage, setValidationMessage] = useState('')
  const [creationAttemptPending, setCreationAttemptPending] = useState(false)
  const [newRequestConfirmation, setNewRequestConfirmation] =
    useState<NewWorkspaceRequestConfirmation | null>(null)
  const [cleanupRetry, setCleanupRetry] =
    useState<WorkspaceCreationCleanupRetry | null>(null)
  const [selectedPendingCreation, setSelectedPendingCreation] =
    useState<PendingWorkspaceCreationItem | null>(null)
  const [pendingCreationList, setPendingCreationList] =
    useState<PendingWorkspaceCreationListResult>({ status: 'ready', items: [] })
  const recentWorkspaces = useSyncExternalStore(
    subscribeRecentWorkspaces,
    readRecentWorkspaces,
    readRecentWorkspacesServerSnapshot,
  )

  const createMutation = useMutation({
    mutationFn: ({ request, idempotencyKey, creationKey: operatorKey }: CreateWorkspaceVariables) =>
      createWorkspace(request, { idempotencyKey, creationKey: operatorKey }),
  })

  const refreshPendingCreations = useCallback(() => {
    setPendingCreationList(listPendingWorkspaceCreations())
  }, [])

  useEffect(() => {
    refreshPendingCreations()
    return subscribePendingWorkspaceCreations(refreshPendingCreations)
  }, [refreshPendingCreations])

  useEffect(() => {
    repairRecentWorkspaces()
  }, [])

  const currentDraftRequest: CreateWorkspaceRequest = {
    teamName,
    seasonName,
    startDate,
    endDate,
    memberNames: splitMemberNames(memberNamesInput),
  }
  const selectedPendingMatchesDraft = selectedPendingCreation !== null
    && isSameWorkspaceCreationRequest(selectedPendingCreation.request, currentDraftRequest)
  const pendingCreations = pendingCreationList.status === 'ready'
    ? pendingCreationList.items
    : []
  const selectedPendingKnownMissing = selectedPendingMatchesDraft
    && pendingCreationList.status === 'ready'
    && !pendingCreationList.items.some((item) =>
      isSamePendingWorkspaceCreationItem(item, selectedPendingCreation))
  const newRequestConfirmationMatchesDraft = newRequestConfirmation !== null
    && isSameWorkspaceCreationRequest(newRequestConfirmation.request, currentDraftRequest)
  const activeNewRequestConfirmationReason = newRequestConfirmation?.reason === 'replayExpired'
    ? 'replayExpired'
    : newRequestConfirmationMatchesDraft
      ? newRequestConfirmation.reason
      : null
  const creationConfirmationReason = cleanupRetry
    ? 'cleanupRequired'
    : selectedPendingKnownMissing
      ? 'pendingMissing'
      : activeNewRequestConfirmationReason
  const creationBusy = creationAttemptPending || createMutation.isPending
  const creationSubmitLabel = creationBusy
    ? '작업 공간 확인하는 중…'
    : creationConfirmationReason === 'cleanupRequired'
      ? '임시 요청 기록 삭제 필요'
      : creationConfirmationReason
        ? '기존 결과 확인 필요'
        : selectedPendingMatchesDraft
          ? '같은 생성 결과 확인하기'
          : '작업 공간 만들기'

  useEffect(() => {
    if (newRequestConfirmation?.reason !== 'concurrentAttempt'
      || pendingCreationList.status !== 'ready') return
    const matchingPending = pendingCreationList.items.find((item) =>
      isSameWorkspaceCreationRequest(item.request, newRequestConfirmation.request))
    if (!matchingPending) return

    setSelectedPendingCreation(matchingPending)
    setNewRequestConfirmation(null)
  }, [newRequestConfirmation, pendingCreationList])

  useEffect(() => {
    if (!selectedPendingKnownMissing || !selectedPendingCreation) return
    setNewRequestConfirmation({
      request: selectedPendingCreation.request,
      reason: 'pendingMissing',
    })
    setSelectedPendingCreation(null)
    setValidationMessage((current) => current === creationBusyMessage ? '' : current)
  }, [selectedPendingCreation, selectedPendingKnownMissing])

  const attemptCreation = async (variables: CreateWorkspaceVariables) => {
    try {
      const { teamId, seasonId, accessKey } = await createMutation.mutateAsync(variables)
      const workspacePath = `/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}`
      const saved = saveAccessKey(teamId, accessKey)
      const destination = saved
        ? workspacePath
        : `${workspacePath}#accessKey=${encodeURIComponent(accessKey)}`
      const cleanupResult = clearPendingWorkspaceCreation(
        variables.request,
        variables.idempotencyKey,
      )
      if (!isJsonCleanupComplete(cleanupResult)) {
        setCleanupRetry({ kind: 'success', variables, destination })
        setValidationMessage('')
        refreshPendingCreations()
        return
      }
      refreshPendingCreations()
      navigate(destination)
    } catch (error) {
      const resolution = resolveIdempotencyJournalFailure(
        error,
        workspaceCreationJournalPolicy,
      )
      if (resolution !== 'retrySameRequest') {
        const cleanupResult = clearPendingWorkspaceCreation(
          variables.request,
          variables.idempotencyKey,
        )
        if (!isJsonCleanupComplete(cleanupResult)) {
          setCleanupRetry({ kind: 'terminalError', variables, resolution })
          setValidationMessage(`${errorMessage(error)} ${creationJournalCleanupRequiredMessage}`)
          refreshPendingCreations()
          return
        }

        setSelectedPendingCreation((current) =>
          current?.idempotencyKey === variables.idempotencyKey ? null : current)
        if (resolution === 'confirmBeforeNewRequest') {
          setNewRequestConfirmation({
            request: variables.request,
            reason: 'replayExpired',
          })
        } else {
          setNewRequestConfirmation((current) =>
            current && isSameWorkspaceCreationRequest(current.request, variables.request)
              ? null
              : current)
        }
      }
      refreshPendingCreations()
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (creationBusy) return
    setValidationMessage('')
    createMutation.reset()

    const normalizedTeamName = teamName.trim()
    const normalizedSeasonName = seasonName.trim()
    const memberNames = splitMemberNames(memberNamesInput)
    if (!normalizedTeamName) {
      setValidationMessage('팀 이름을 입력해 주세요.')
      return
    }
    if (!normalizedSeasonName) {
      setValidationMessage('시즌 이름을 입력해 주세요.')
      return
    }
    if (!memberNames.length) {
      setValidationMessage('함께할 구성원을 한 명 이상 입력해 주세요.')
      return
    }
    if (memberNames.length > MAX_INITIAL_MEMBER_COUNT) {
      setValidationMessage(`구성원은 최대 ${MAX_INITIAL_MEMBER_COUNT}명까지 입력해 주세요.`)
      return
    }
    if (memberNames.some((name) => name.length > MAX_MEMBER_NAME_LENGTH)) {
      setValidationMessage(`구성원 이름은 각각 ${MAX_MEMBER_NAME_LENGTH}자 이하로 입력해 주세요.`)
      return
    }
    if (new Set(memberNames).size !== memberNames.length) {
      setValidationMessage('같은 이름은 구분할 수 있게 다르게 입력해 주세요.')
      return
    }
    const request: CreateWorkspaceRequest = {
      teamName: normalizedTeamName,
      seasonName: normalizedSeasonName,
      startDate,
      endDate,
      memberNames,
    }
    const pendingToRecover = selectedPendingCreation
      && isSameWorkspaceCreationRequest(selectedPendingCreation.request, request)
      ? selectedPendingCreation
      : null

    setCreationAttemptPending(true)
    try {
      const lockResult = await runWithWorkspaceCreationLock(async () => {
        if (pendingToRecover) {
          const recovery = preparePendingWorkspaceRecovery(pendingToRecover)
          if (recovery.status === 'blocked') {
            return { status: 'recoveryBlocked' as const, reason: recovery.reason }
          }
          await attemptCreation({
            request: pendingToRecover.request,
            idempotencyKey: recovery.idempotencyKey,
            creationKey: creationKey.trim() || undefined,
          })
          return { status: 'requested' as const }
        }

        const preparation = prepareWorkspaceCreation(request)
        if (preparation.status === 'blocked') {
          return { status: 'creationBlocked' as const, reason: preparation.reason }
        }
        refreshPendingCreations()
        await attemptCreation({
          request,
          idempotencyKey: preparation.idempotencyKey,
          creationKey: creationKey.trim() || undefined,
        })
        return { status: 'requested' as const }
      })

      if (lockResult.status === 'busy') {
        if (pendingToRecover) {
          setValidationMessage(creationBusyMessage)
        } else {
          setNewRequestConfirmation({
            request,
            reason: 'concurrentAttempt',
          })
          refreshPendingCreations()
        }
        return
      }
      if (lockResult.status === 'unsupported') {
        setValidationMessage(creationLockUnsupportedMessage)
        return
      }
      if (lockResult.value.status === 'creationBlocked') {
        refreshPendingCreations()
        setValidationMessage(lockResult.value.reason === 'pendingLimitReached'
          ? pendingCreationLimitMessage
          : pendingStorageRequiredMessage)
        return
      }
      if (lockResult.value.status === 'recoveryBlocked') {
        refreshPendingCreations()
        if (lockResult.value.reason === 'missing') {
          setSelectedPendingCreation(null)
          setNewRequestConfirmation({
            request: pendingToRecover!.request,
            reason: 'pendingMissing',
          })
          setValidationMessage('다른 탭에서 이미 확인하거나 정리한 요청입니다. 같은 입력을 새 요청으로 자동 전환하지 않았습니다.')
        } else if (lockResult.value.reason === 'changed') {
          setSelectedPendingCreation(null)
          setNewRequestConfirmation({
            request: pendingToRecover!.request,
            reason: 'pendingChanged',
          })
          setValidationMessage('다른 탭에서 복구 기록이 변경됐습니다. 같은 입력을 새 요청으로 자동 전환하지 않았습니다.')
        } else {
          setValidationMessage(pendingStorageRequiredMessage)
        }
      }
    } catch (error) {
      setValidationMessage(errorMessage(error))
    } finally {
      setCreationAttemptPending(false)
    }
  }

  const retryCreationJournalCleanup = async () => {
    if (!cleanupRetry || creationBusy) return
    const retry = cleanupRetry
    setValidationMessage('')
    setCreationAttemptPending(true)

    let lockResult: Awaited<ReturnType<typeof runWithWorkspaceCreationLock<boolean>>>
    try {
      lockResult = await runWithWorkspaceCreationLock(async () => {
        const cleanupResult = clearPendingWorkspaceCreation(
          retry.variables.request,
          retry.variables.idempotencyKey,
        )
        return isJsonCleanupComplete(cleanupResult)
      })
    } finally {
      setCreationAttemptPending(false)
    }

    if (lockResult.status === 'busy') {
      setValidationMessage(creationBusyMessage)
      return
    }
    if (lockResult.status === 'unsupported') {
      setValidationMessage(creationLockUnsupportedMessage)
      return
    }
    const cleanupCompleted = lockResult.value
    if (!cleanupCompleted) {
      setValidationMessage(creationJournalCleanupRequiredMessage)
      return
    }

    createMutation.reset()
    setCleanupRetry(null)
    setSelectedPendingCreation((current) =>
      current?.idempotencyKey === retry.variables.idempotencyKey ? null : current)
    refreshPendingCreations()
    if (retry.kind === 'success') {
      setValidationMessage('')
      navigate(retry.destination)
      return
    }
    if (retry.resolution === 'confirmBeforeNewRequest') {
      setNewRequestConfirmation({
        request: retry.variables.request,
        reason: 'replayExpired',
      })
      setValidationMessage('')
    } else {
      setNewRequestConfirmation(null)
      setValidationMessage('브라우저의 임시 요청 기록을 삭제했습니다. 입력을 확인한 뒤 다시 시도해 주세요.')
    }
    requestAnimationFrame(() => teamNameInputRef.current?.focus())
  }

  const loadPendingCreation = (item: PendingWorkspaceCreationItem) => {
    createMutation.reset()
    setValidationMessage('')
    setSelectedPendingCreation(item)
    setNewRequestConfirmation((current) =>
      current?.reason === 'replayExpired' ? current : null)
    setTeamName(item.request.teamName)
    setSeasonName(item.request.seasonName)
    setStartDate(item.request.startDate)
    setEndDate(item.request.endDate)
    setMemberNamesInput(item.request.memberNames.join('\n'))
    requestAnimationFrame(() => teamNameInputRef.current?.focus())
  }

  const startNewCreationRequest = () => {
    createMutation.reset()
    setValidationMessage('')
    setSelectedPendingCreation(null)
    setNewRequestConfirmation(null)
    requestAnimationFrame(() => teamNameInputRef.current?.focus())
  }

  const forgetWorkspace = (workspace: RecentWorkspace) => {
    if (!window.confirm(`${workspace.teamName}의 이 기기 접근 권한을 제거할까요? 저장된 접근 키와 모든 최근 시즌 기록을 함께 지웁니다.`)) return
    const result = forgetWorkspaceDeviceState(workspace.teamId)
    setValidationMessage(result === 'removed'
      ? ''
      : result === 'capability-removal-failed'
        ? workspaceCapabilityRemovalFailedMessage
        : result === 'recent-list-update-failed'
          ? workspaceCapabilityPartialRemovalMessage
          : roundContextRemovalFailedMessage)
  }

  return (
    <main className="onboarding-page">
      <section className="onboarding-story" aria-labelledby="onboarding-title">
        <div className="brand onboarding-brand"><span className="brand-mark" />BATON</div>
        <div>
          <span className="section-kicker">첫 번째 바통</span>
          <h1 id="onboarding-title">사람이 바뀌어도<br />운영은 이어지게.</h1>
          <p>스터디의 담당 업무, 결정 기록, 인수인계 자료를 한곳에서 관리하세요.</p>
        </div>
        <ol className="onboarding-points">
          <li><span>01</span><strong>시즌 설정</strong><small>함께할 기간과 구성원을 정합니다.</small></li>
          <li><span>02</span><strong>역할 등록</strong><small>역할별 업무와 담당자를 정합니다.</small></li>
          <li><span>03</span><strong>인수인계 기록</strong><small>다음 담당자에게 필요한 자료와 주의사항을 남깁니다.</small></li>
        </ol>
      </section>

      <section className="onboarding-form-panel" aria-labelledby="workspace-form-title">
        <div className="onboarding-form-topline">
          <div className="onboarding-form-heading">
            <span className="section-kicker">새 작업 공간</span>
            <h2 id="workspace-form-title">우리 스터디를 시작해요</h2>
            <p>지금 입력한 정보로 첫 시즌과 공유 작업 공간을 만듭니다.</p>
          </div>
          <Link className="onboarding-login-link" to="/login">계정 로그인</Link>
        </div>

        <PendingWorkspaceCreationPanel
          items={pendingCreations}
          busy={creationBusy}
          selectedItem={selectedPendingMatchesDraft ? selectedPendingCreation : null}
          onLoad={loadPendingCreation}
          onRefresh={refreshPendingCreations}
          onFocusForm={() => teamNameInputRef.current?.focus()}
        />

        {creationConfirmationReason && (
          <div className="form-retry-notice pending-recovery-stale" role="status">
            <p>{creationConfirmationMessage(creationConfirmationReason)}</p>
            {creationConfirmationReason === 'cleanupRequired'
              ? (
                  <button
                    type="button"
                    className="text-button"
                    disabled={creationBusy}
                    onClick={() => void retryCreationJournalCleanup()}
                  >
                    임시 요청 기록 삭제 재시도
                  </button>
                )
              : (
                  <button type="button" className="text-button" onClick={startNewCreationRequest}>
                    기존 결과를 확인했고 새 요청으로 전환
                  </button>
                )}
          </div>
        )}

        {recentWorkspaces.length > 0 && (
          <section className="recent-workspaces" aria-labelledby="recent-workspaces-title">
            <div className="recent-workspaces-heading">
              <h3 id="recent-workspaces-title">최근 작업 공간</h3>
              <span>이 브라우저에서 열었던 공간</span>
            </div>
            <ul>
              {recentWorkspaces.map((workspace) => {
                const path = `/teams/${encodeURIComponent(workspace.teamId)}/seasons/${encodeURIComponent(workspace.seasonId)}`
                return (
                  <li key={`${workspace.teamId}:${workspace.seasonId}`}>
                    <Link to={path}>
                      <span><strong>{workspace.teamName}</strong><small>{workspace.seasonName}</small></span>
                      <time dateTime={workspace.lastOpenedAt}>{formatLastOpenedAt(workspace.lastOpenedAt)}</time>
                    </Link>
                    <button
                      type="button"
                      onClick={() => forgetWorkspace(workspace)}
                      aria-label={`${workspace.teamName} ${workspace.seasonName} 이 기기에서 접근 권한 제거`}
                    >
                      이 기기 권한 제거
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        <form className="onboarding-form" onSubmit={submit}>
          <label>
            <span>팀 이름</span>
            <input
              required
              autoFocus
              ref={teamNameInputRef}
              maxLength={MAX_WORKSPACE_NAME_LENGTH}
              value={teamName}
              onChange={(event) => setTeamName(event.target.value)}
              placeholder="예: 알고리즘 한 바퀴"
            />
          </label>
          <label>
            <span>시즌 이름</span>
            <input
              required
              maxLength={MAX_WORKSPACE_NAME_LENGTH}
              value={seasonName}
              onChange={(event) => setSeasonName(event.target.value)}
              placeholder="예: 2026 여름 시즌"
            />
          </label>
          <div className="onboarding-date-row">
            <label><span>시작일</span><input required type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} /></label>
            <label><span>종료일</span><input required type="date" value={endDate} min={startDate || undefined} onChange={(event) => setEndDate(event.target.value)} /></label>
          </div>
          <label>
            <span>구성원 이름</span>
            <textarea
              required
              rows={4}
              value={memberNamesInput}
              onChange={(event) => setMemberNamesInput(event.target.value)}
              placeholder={'박민서\n김준호\n최유진'}
              aria-describedby="member-names-help"
            />
            <small id="member-names-help">줄바꿈 또는 쉼표로 구분해 주세요. 최대 100명, 이름은 각각 100자까지 입력할 수 있어요.</small>
          </label>
          <label>
            <span>파일럿 생성 코드 <small>(선택)</small></span>
            <input
              type="password"
              autoComplete="off"
              value={creationKey}
              onChange={(event) => setCreationKey(event.target.value)}
              placeholder="운영자에게 받은 코드"
            />
            <small>워크스페이스 생성 요청에만 사용하며 이 브라우저에 저장하지 않습니다.</small>
          </label>

          {(validationMessage || createMutation.error) && (
            <p className="form-error" role="alert">{validationMessage || errorMessage(createMutation.error)}</p>
          )}

          <button
            type="submit"
            className="primary-button onboarding-submit"
            disabled={creationBusy || creationConfirmationReason !== null}
          >
            {creationSubmitLabel}
          </button>
        </form>
      </section>
    </main>
  )
}
