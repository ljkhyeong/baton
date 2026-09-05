import { useEffect, useState } from 'react'
import type { UseMutationResult } from '@tanstack/react-query'
import {
  resolveIdempotencyJournalFailure,
} from '@/shared/api/idempotencyJournal'
import { isJsonCleanupComplete } from '@/shared/lib/durableStorage'
import type { WorkspaceScope } from './api'
import {
  clearPendingContentCreationCleanup,
  hasPendingContentCreation,
  markPendingContentCreationCleanupRequired,
  pendingContentCreationCleanupRetry,
  prepareContentCreation,
  runWithContentCreationLock,
  subscribePendingContentCreationCleanup,
} from './pendingContentCreation'
import type {
  ContentCreationOperation,
  ContentCreationRequestByOperation,
  PendingContentCreationCleanupRetry,
} from './pendingContentCreation'
import {
  useCreateDecisionMutation,
  useCreateHandoffItemMutation,
  useCreateMemberMutation,
  useCreateRoleMutation,
  useCreateRoleResourceMutation,
  useCreateRoutineMutation,
  useCreateSeasonRoundMutation,
  usePrepareRoleHandoffMutation,
} from './queries'
import type { IdempotentCreateCommand } from './queries'
import type {
  Decision,
  HandoffItem,
  Member,
  PrepareRoleHandoffResponse,
  Role,
  RoleResource,
  Routine,
  SeasonRound,
} from './types'

type ContentCreationResultByOperation = {
  member: Member
  role: Role
  routine: Routine
  round: SeasonRound
  decision: Decision
  handoffItem: HandoffItem
  roleResource: RoleResource
  roleHandoff: PrepareRoleHandoffResponse
}

type ContentCreationMutation<Operation extends ContentCreationOperation> =
  UseMutationResult<
    ContentCreationResultByOperation[Operation],
    Error,
    IdempotentCreateCommand<ContentCreationRequestByOperation[Operation]>
  >

const terminalContentCreationCodes = new Set([
  'IDEMPOTENCY_KEY_REUSED',
  'INVALID_INPUT',
  'MEMBER_NAME_CONFLICT',
  'ROLE_NAME_CONFLICT',
  'ROUND_NAME_CONFLICT',
  'ROLE_HANDOFF_STATE_CONFLICT',
  'TEAM_NOT_FOUND',
  'SEASON_NOT_FOUND',
  'MEMBER_NOT_FOUND',
  'ROLE_NOT_FOUND',
])
const contentCreationJournalPolicy = {
  startNewRequestCodes: terminalContentCreationCodes,
  confirmBeforeNewRequestCodes: new Set(['IDEMPOTENCY_REPLAY_EXPIRED']),
}

export const pendingStorageRequiredMessage = '요청을 안전하게 저장할 수 없습니다. 시크릿 창이 아닌 일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const pendingCreationLimitMessage = '확인되지 않은 생성 요청이 20개 남아 새 요청을 시작할 수 없습니다. 이전에 제출했던 같은 내용을 다시 제출해 결과를 확인한 뒤 시도해 주세요.'
const contentCreationBusyMessage = '다른 탭에서 콘텐츠 생성 요청을 처리 중입니다. 그 탭의 결과를 확인한 뒤 다시 시도해 주세요.'
const contentCreationLockUnsupportedMessage = '이 브라우저에서는 새 항목을 만들 수 없습니다. 브라우저를 업데이트한 뒤 다시 시도해 주세요.'
const contentCreationLockFailedMessage = '새 항목을 만들 수 없습니다. 새로고침한 뒤 다시 시도해 주세요.'
const contentCreationCleanupRequiredMessage = '브라우저의 임시 기록을 정리하지 못해 요청을 다시 보내지 않았습니다. 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const contentCreationCleanupCompletedMessage = '브라우저의 임시 기록을 정리했습니다. 목록과 입력을 확인한 뒤 다시 제출해 주세요.'
const guardedContentCreationMessage = '응답을 확인하지 못한 이전 생성 요청이 남아 새 요청을 시작하지 않았습니다. 이전에 제출한 같은 작업과 내용을 다시 제출해 결과를 확인해 주세요.'

export function isTerminalContentCreationError(error: unknown) {
  return resolveIdempotencyJournalFailure(error, contentCreationJournalPolicy) !== 'retrySameRequest'
}

function preparationError(
  reason:
    | 'storageUnavailable'
    | 'pendingLimitReached'
    | 'cleanupRequired'
    | 'guardedRequestPending',
) {
  if (reason === 'cleanupRequired') return contentCreationCleanupRequiredMessage
  if (reason === 'guardedRequestPending') return guardedContentCreationMessage
  return reason === 'pendingLimitReached'
    ? pendingCreationLimitMessage
    : pendingStorageRequiredMessage
}

function useContentCreationCommand<Operation extends ContentCreationOperation>(
  scope: WorkspaceScope,
  operation: Operation,
  mutation: ContentCreationMutation<Operation>,
) {
  const [storageError, setStorageError] = useState('')
  const [lockPending, setLockPending] = useState(false)

  const reset = () => {
    mutation.reset()
    setStorageError(pendingContentCreationCleanupRetry()
      ? contentCreationCleanupRequiredMessage
      : '')
  }

  const submit = (
    request: ContentCreationRequestByOperation[Operation],
    onSuccess: (
      result: ContentCreationResultByOperation[Operation],
      request: ContentCreationRequestByOperation[Operation],
    ) => void,
  ) => {
    if (lockPending || mutation.isPending) return false
    setLockPending(true)
    setStorageError('')
    return runWithContentCreationLock(async () => {
      const cleanupRetry = pendingContentCreationCleanupRetry()
      if (cleanupRetry) {
        const cleanupResult = clearPendingContentCreationCleanup(cleanupRetry)
        return isJsonCleanupComplete(cleanupResult)
          ? { status: 'cleanupCompleted' as const }
          : { status: 'cleanupBlocked' as const }
      }

      const preparation = prepareContentCreation(scope, operation, request)
      if (preparation.status === 'blocked') return preparation

      const { idempotencyKey } = preparation
      try {
        const result = await mutation.mutateAsync({ request, idempotencyKey })
        const cleanupRetry = markPendingContentCreationCleanupRequired(
          scope,
          operation,
          request,
          idempotencyKey,
        )
        const cleanupResult = clearPendingContentCreationCleanup(cleanupRetry)
        if (!isJsonCleanupComplete(cleanupResult)) {
          setStorageError(contentCreationCleanupRequiredMessage)
        }
        onSuccess(result, request)
      } catch (error) {
        if (isTerminalContentCreationError(error)) {
          const cleanupRetry = markPendingContentCreationCleanupRequired(
            scope,
            operation,
            request,
            idempotencyKey,
          )
          const cleanupResult = clearPendingContentCreationCleanup(cleanupRetry)
          if (!isJsonCleanupComplete(cleanupResult)) {
            setStorageError(contentCreationCleanupRequiredMessage)
          }
        }
      }
      return { status: 'requested' as const }
    }).then((lockResult) => {
      if (lockResult.status === 'busy') {
        mutation.reset()
        setStorageError(contentCreationBusyMessage)
        return false
      }
      if (lockResult.status === 'unsupported') {
        mutation.reset()
        setStorageError(contentCreationLockUnsupportedMessage)
        return false
      }
      if (lockResult.status === 'failed') {
        mutation.reset()
        setStorageError(contentCreationLockFailedMessage)
        return false
      }
      if (lockResult.value.status === 'blocked') {
        mutation.reset()
        setStorageError(preparationError(lockResult.value.reason))
        return false
      }
      if (lockResult.value.status === 'cleanupBlocked') {
        mutation.reset()
        setStorageError(contentCreationCleanupRequiredMessage)
        return false
      }
      if (lockResult.value.status === 'cleanupCompleted') {
        mutation.reset()
        setStorageError(contentCreationCleanupCompletedMessage)
        return false
      }
      return true
    }).finally(() => {
      setLockPending(false)
    })
  }

  return {
    error: mutation.error,
    hasPending: () => hasPendingContentCreation(scope, operation),
    isPending: mutation.isPending || lockPending,
    reset,
    storageError,
    submit,
  }
}

export function usePendingContentCreationCleanupCommand() {
  const [cleanupRetry, setCleanupRetry] = useState<PendingContentCreationCleanupRetry | null>(
    () => pendingContentCreationCleanupRetry(),
  )
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState(
    cleanupRetry ? contentCreationCleanupRequiredMessage : '',
  )

  useEffect(() => {
    const syncCleanupRetry = () => {
      const nextRetry = pendingContentCreationCleanupRetry()
      setCleanupRetry(nextRetry)
      if (nextRetry) setMessage(contentCreationCleanupRequiredMessage)
    }

    const unsubscribe = subscribePendingContentCreationCleanup(syncCleanupRetry)
    syncCleanupRetry()
    return unsubscribe
  }, [])

  const retryCleanup = () => {
    if (!cleanupRetry || pending) return false
    setPending(true)
    setMessage('')
    return runWithContentCreationLock(
      async () => isJsonCleanupComplete(
        clearPendingContentCreationCleanup(cleanupRetry),
      ),
    ).then((lockResult) => {
      if (lockResult.status === 'busy') {
        setMessage(contentCreationBusyMessage)
        return false
      }
      if (lockResult.status === 'unsupported') {
        setMessage(contentCreationLockUnsupportedMessage)
        return false
      }
      if (lockResult.status === 'failed') {
        setMessage(contentCreationLockFailedMessage)
        return false
      }
      if (!lockResult.value) {
        setMessage(contentCreationCleanupRequiredMessage)
        return false
      }

      setCleanupRetry(null)
      setMessage(contentCreationCleanupCompletedMessage)
      return true
    }).finally(() => setPending(false))
  }

  return {
    cleanupRequired: Boolean(cleanupRetry),
    message,
    pending,
    retryCleanup,
  }
}

export function useCreateRoleCommand(scope: WorkspaceScope) {
  const mutation = useCreateRoleMutation(scope)
  return useContentCreationCommand(scope, 'role', mutation)
}

export function useCreateMemberCommand(scope: WorkspaceScope) {
  const mutation = useCreateMemberMutation(scope)
  return useContentCreationCommand(scope, 'member', mutation)
}

export function useCreateRoutineCommand(scope: WorkspaceScope) {
  const mutation = useCreateRoutineMutation(scope)
  return useContentCreationCommand(scope, 'routine', mutation)
}

export function useCreateSeasonRoundCommand(scope: WorkspaceScope) {
  const mutation = useCreateSeasonRoundMutation(scope)
  return useContentCreationCommand(scope, 'round', mutation)
}

export function useCreateDecisionCommand(scope: WorkspaceScope) {
  const mutation = useCreateDecisionMutation(scope)
  return useContentCreationCommand(scope, 'decision', mutation)
}

export function useCreateHandoffItemCommand(scope: WorkspaceScope) {
  const mutation = useCreateHandoffItemMutation(scope)
  return useContentCreationCommand(scope, 'handoffItem', mutation)
}

export function useCreateRoleResourceCommand(scope: WorkspaceScope) {
  const mutation = useCreateRoleResourceMutation(scope)
  return useContentCreationCommand(scope, 'roleResource', mutation)
}

export function usePrepareRoleHandoffCommand(scope: WorkspaceScope) {
  const mutation = usePrepareRoleHandoffMutation(scope)
  return useContentCreationCommand(scope, 'roleHandoff', mutation)
}
