import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { createWorkspace } from '@/features/workspace/api'
import { forgetWorkspaceDeviceState } from '@/features/workspace/deviceState'
import {
  readRecentWorkspaces,
  readRecentWorkspacesServerSnapshot,
  repairRecentWorkspaces,
  saveAccessKey,
  subscribeRecentWorkspaces,
} from '@/features/workspace/storage'
import type { RecentWorkspace } from '@/features/workspace/storage'
import type { CreateWorkspaceRequest } from '@/features/workspace/types'
import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import { resolveIdempotencyJournalFailure } from '@/shared/api/idempotencyJournal'
import type { IdempotencyJournalFailureResolution } from '@/shared/api/idempotencyJournal'
import { isJsonCleanupComplete } from '@/shared/lib/durableStorage'
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
} from './workspaceCreationConstraints'

function splitMemberNames(value: string) {
  return value.split(/[\n,]/).map((name) => name.trim()).filter(Boolean)
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED') {
    return '공유 링크가 변경되어 작업 공간을 다시 열 수 없습니다. 이미 만들어졌을 수 있으니 운영자에게 최신 링크를 요청하세요.'
  }
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_KEY_REUSED') {
    return '이전에 보낸 내용과 입력이 달라 작업 공간을 만들 수 없습니다. 기존 작업 공간이 있는지 확인한 뒤 다시 제출하세요.'
  }
  if (error instanceof ApiError || error instanceof ApiClientError) return error.message
  return '작업 공간을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

const pendingStorageRequiredMessage = '입력을 저장하지 못해 작업 공간을 만들지 않았습니다. 일반 창을 사용하거나 사이트 데이터 저장을 허용한 뒤 다시 시도해 주세요.'
const pendingCreationLimitMessage = '생성 결과를 확인하지 못한 작업 공간이 5개입니다. 아래 목록에서 결과를 확인하거나 필요 없는 항목을 삭제해 주세요.'
const creationBusyMessage = '다른 탭에서 생성 결과를 확인하고 있습니다. 끝난 뒤 다시 시도해 주세요.'
const creationLockUnsupportedMessage = '이 브라우저에서는 작업 공간을 만들거나 생성 결과를 확인할 수 없습니다. 브라우저를 업데이트한 뒤 다시 시도해 주세요.'
const creationJournalCleanupRequiredMessage = '생성 확인에 사용한 임시 기록을 삭제하지 못했습니다. 사이트 데이터 저장을 허용한 뒤 다시 시도해 주세요.'
const workspaceCapabilityRemovalFailedMessage = '이 기기의 공유 링크를 삭제하지 못했습니다. 사이트 데이터 저장을 허용한 뒤 다시 시도해 주세요.'
const workspaceCapabilityPartialRemovalMessage = '공유 링크는 삭제했지만 최근 방문 목록은 남아 있습니다. 사이트 데이터 저장을 허용한 뒤 다시 삭제해 주세요.'
const roundContextRemovalFailedMessage = '공유 링크와 최근 방문 목록은 삭제했지만 ROUND 입장 정보가 남아 있습니다. 이 탭을 닫으면 삭제됩니다.'
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

function confirmationMessage(
  reason: NewWorkspaceRequestConfirmationReason | 'cleanupRequired',
) {
  switch (reason) {
    case 'pendingMissing':
      return '다른 탭에서 생성 결과를 확인했거나 임시 기록을 삭제했습니다. 최근 작업 공간이나 기존 공유 링크를 확인해 주세요.'
    case 'pendingChanged':
      return '다른 탭에서 임시 기록을 변경했습니다. 해당 탭이나 ‘최근 작업 공간’에서 생성 결과를 확인해 주세요.'
    case 'concurrentAttempt':
      return '다른 탭에서 같은 작업 공간을 만들고 있습니다. 해당 탭이나 ‘최근 작업 공간’에서 생성 결과를 확인해 주세요.'
    case 'replayExpired':
      return '저장된 공유 링크가 만료됐습니다. 운영자에게 최신 링크를 받아 기존 작업 공간을 확인해 주세요.'
    case 'cleanupRequired':
      return creationJournalCleanupRequiredMessage
  }
}

export function useOnboardingWorkspaceFlow() {
  const navigate = useNavigate()
  const teamNameInputRef = useRef<HTMLInputElement>(null)
  const [template, setTemplate] = useState<CreateWorkspaceRequest['template']>()
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
    ...(template ? { template } : {}),
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
    ? '생성 결과 확인 중…'
    : creationConfirmationReason === 'cleanupRequired'
      ? '임시 기록 삭제 필요'
      : creationConfirmationReason
        ? '기존 작업 공간 확인 필요'
        : selectedPendingMatchesDraft
          ? '생성 결과 다시 확인'
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
      if (window.location.pathname === '/') navigate(destination)
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
      ...(template ? { template } : {}),
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
          setValidationMessage('다른 탭에서 생성 결과를 확인했거나 임시 기록을 삭제했습니다. 최근 작업 공간이나 기존 공유 링크를 확인해 주세요.')
        } else if (lockResult.value.reason === 'changed') {
          setSelectedPendingCreation(null)
          setNewRequestConfirmation({
            request: pendingToRecover!.request,
            reason: 'pendingChanged',
          })
          setValidationMessage('다른 탭에서 임시 기록을 변경했습니다. 해당 탭에서 생성 결과를 확인해 주세요.')
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
      if (window.location.pathname === '/') navigate(retry.destination)
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
      setValidationMessage('임시 기록을 삭제했습니다. 입력을 확인한 뒤 다시 시도해 주세요.')
    }
    requestAnimationFrame(() => teamNameInputRef.current?.focus())
  }

  const loadPendingCreation = (item: PendingWorkspaceCreationItem) => {
    createMutation.reset()
    setValidationMessage('')
    setSelectedPendingCreation(item)
    setNewRequestConfirmation((current) =>
      current?.reason === 'replayExpired' ? current : null)
    setTemplate(item.request.template)
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
    if (!window.confirm(`이 기기에서 ${workspace.teamName}의 공유 링크와 최근 방문 목록을 지울까요? 팀의 기록은 삭제되지 않습니다.`)) return
    const result = forgetWorkspaceDeviceState(workspace.teamId)
    setValidationMessage(result === 'removed'
      ? ''
      : result === 'capability-removal-failed'
        ? workspaceCapabilityRemovalFailedMessage
        : result === 'recent-list-update-failed'
          ? workspaceCapabilityPartialRemovalMessage
          : roundContextRemovalFailedMessage)
  }

  return {
    teamNameInputRef,
    form: {
      template,
      setTemplate,
      teamName,
      setTeamName,
      seasonName,
      setSeasonName,
      startDate,
      setStartDate,
      endDate,
      setEndDate,
      memberNamesInput,
      setMemberNamesInput,
      creationKey,
      setCreationKey,
      submit,
    },
    pending: {
      items: pendingCreations,
      selectedItem: selectedPendingMatchesDraft ? selectedPendingCreation : null,
      refresh: refreshPendingCreations,
      load: loadPendingCreation,
    },
    creation: {
      busy: creationBusy,
      confirmationReason: creationConfirmationReason,
      confirmationMessage: creationConfirmationReason
        ? confirmationMessage(creationConfirmationReason)
        : '',
      submitLabel: creationSubmitLabel,
      feedbackMessage: validationMessage
        || (createMutation.error ? errorMessage(createMutation.error) : ''),
      retryJournalCleanup: retryCreationJournalCleanup,
      startNewRequest: startNewCreationRequest,
    },
    recentWorkspaces,
    forgetWorkspace,
  }
}
