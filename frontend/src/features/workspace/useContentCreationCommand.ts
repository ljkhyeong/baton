import { useState } from 'react'
import type { UseMutationResult } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import type { WorkspaceScope } from './api'
import {
  clearPendingContentCreation,
  hasPendingContentCreation,
  prepareContentCreation,
  runWithContentCreationLock,
} from './pendingContentCreation'
import type {
  ContentCreationOperation,
  ContentCreationRequestByOperation,
} from './pendingContentCreation'
import {
  useCreateDecisionMutation,
  useCreateHandoffItemMutation,
  useCreateRoleMutation,
  useCreateRoleResourceMutation,
  useCreateRoutineMutation,
  useCreateSeasonRoundMutation,
} from './queries'
import type { IdempotentCreateCommand } from './queries'
import type {
  Decision,
  HandoffItem,
  Role,
  RoleResource,
  Routine,
  SeasonRound,
} from './types'

type ContentCreationResultByOperation = {
  role: Role
  routine: Routine
  round: SeasonRound
  decision: Decision
  handoffItem: HandoffItem
  roleResource: RoleResource
}

type ContentCreationMutation<Operation extends ContentCreationOperation> =
  UseMutationResult<
    ContentCreationResultByOperation[Operation],
    Error,
    IdempotentCreateCommand<ContentCreationRequestByOperation[Operation]>
  >

const terminalContentCreationCodes = new Set([
  'IDEMPOTENCY_KEY_REUSED',
  'IDEMPOTENCY_REPLAY_EXPIRED',
  'INVALID_INPUT',
  'ROLE_NAME_CONFLICT',
  'ROUND_NAME_CONFLICT',
  'TEAM_NOT_FOUND',
  'SEASON_NOT_FOUND',
  'MEMBER_NOT_FOUND',
  'ROLE_NOT_FOUND',
])

export const pendingStorageRequiredMessage = '요청을 안전하게 저장할 수 없습니다. 시크릿 창이 아닌 일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const pendingCreationLimitMessage = '확인되지 않은 생성 요청이 20개 남아 새 요청을 시작할 수 없습니다. 이전에 제출했던 같은 내용을 다시 제출해 결과를 확인한 뒤 시도해 주세요.'
const contentCreationBusyMessage = '다른 탭에서 콘텐츠 생성 요청을 처리 중입니다. 그 탭의 결과를 확인한 뒤 다시 시도해 주세요.'
const contentCreationLockUnsupportedMessage = '이 브라우저에서는 탭 사이의 콘텐츠 생성 요청을 안전하게 조정할 수 없습니다. 브라우저를 최신 버전으로 업데이트한 뒤 다시 시도해 주세요.'
const contentCreationLockFailedMessage = '콘텐츠 생성 요청의 안전 잠금을 확인하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.'

export function isTerminalContentCreationError(error: unknown) {
  return error instanceof ApiError && terminalContentCreationCodes.has(error.code)
}

function preparationError(reason: 'storageUnavailable' | 'pendingLimitReached') {
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
    setStorageError('')
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
      const preparation = prepareContentCreation(scope, operation, request)
      if (preparation.status === 'blocked') return preparation

      const { idempotencyKey } = preparation
      try {
        const result = await mutation.mutateAsync({ request, idempotencyKey })
        clearPendingContentCreation(scope, operation, request, idempotencyKey)
        onSuccess(result, request)
      } catch (error) {
        if (isTerminalContentCreationError(error)) {
          clearPendingContentCreation(scope, operation, request, idempotencyKey)
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

export function useCreateRoleCommand(scope: WorkspaceScope) {
  const mutation = useCreateRoleMutation(scope)
  return useContentCreationCommand(scope, 'role', mutation)
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
