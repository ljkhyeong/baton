import { useState } from 'react'
import { resolveIdempotencyJournalFailure } from '@/shared/api/idempotencyJournal'
import { isVerifiedJsonCleanupComplete } from '@/shared/lib/durableStorage'
import type { WorkspaceScope } from './api'
import {
  clearPendingSeasonSuccessor,
  prepareSeasonSuccessor,
  runWithSeasonSuccessorLock,
} from './pendingSeasonSuccessor'
import { useCreateNextSeasonMutation } from './queries'
import type {
  CreateNextSeasonRequest,
  CreateNextSeasonResponse,
} from './types'

const seasonSuccessorJournalPolicy = {
  startNewRequestCodes: new Set([
    'IDEMPOTENCY_KEY_REUSED',
    'INVALID_INPUT',
    'SEASON_NAME_CONFLICT',
    'SEASON_SUCCESSOR_EXISTS',
    'SEASON_NOT_FOUND',
    'ROLE_NOT_FOUND',
    'ROUTINE_NOT_FOUND',
  ]),
  confirmBeforeNewRequestCodes: new Set(['IDEMPOTENCY_REPLAY_EXPIRED']),
}

const storageRequiredMessage = '다음 시즌 요청을 안전하게 저장할 수 없습니다. 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const differentRequestPendingMessage = '이 팀에 결과를 확인하지 못한 다른 시즌 시작 요청이 남아 있습니다. 처음 입력한 내용으로 다시 시도해 결과를 확인해 주세요.'
const busyMessage = '다른 탭에서 다음 시즌을 시작하고 있습니다. 그 탭의 결과를 확인한 뒤 다시 시도해 주세요.'
const lockUnsupportedMessage = '이 브라우저에서는 탭 사이의 시즌 시작을 안전하게 조정할 수 없습니다. 최신 브라우저에서 다시 시도해 주세요.'
const lockFailedMessage = '다음 시즌 시작의 안전 잠금을 확인하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.'
const cleanupMessage = '다음 시즌은 만들었지만 브라우저의 완료 기록을 정리하지 못했습니다. 브라우저 저장을 허용해 주세요.'

export function useSeasonSuccessorCommand(scope: WorkspaceScope) {
  const mutation = useCreateNextSeasonMutation(scope)
  const [storageError, setStorageError] = useState('')
  const [lockPending, setLockPending] = useState(false)

  const reset = () => {
    mutation.reset()
    setStorageError('')
  }

  const submit = (
    request: CreateNextSeasonRequest,
    onSuccess: (result: CreateNextSeasonResponse) => void,
  ) => {
    if (lockPending || mutation.isPending) return false
    setLockPending(true)
    setStorageError('')

    return runWithSeasonSuccessorLock(scope.teamId, async () => {
      const preparation = prepareSeasonSuccessor(scope.teamId, scope.seasonId, request)
      if (preparation.status === 'blocked') return preparation

      const { idempotencyKey } = preparation
      try {
        const result = await mutation.mutateAsync({ request, idempotencyKey })
        const cleanup = clearPendingSeasonSuccessor(
          scope.teamId,
          scope.seasonId,
          request,
          idempotencyKey,
        )
        if (!isVerifiedJsonCleanupComplete(cleanup)) setStorageError(cleanupMessage)
        onSuccess(result)
      } catch (error) {
        if (resolveIdempotencyJournalFailure(
          error,
          seasonSuccessorJournalPolicy,
        ) !== 'retrySameRequest') {
          clearPendingSeasonSuccessor(
            scope.teamId,
            scope.seasonId,
            request,
            idempotencyKey,
          )
        }
      }
      return { status: 'requested' as const }
    }).then((lockResult) => {
      if (lockResult.status === 'busy') {
        mutation.reset()
        setStorageError(busyMessage)
        return false
      }
      if (lockResult.status === 'unsupported') {
        mutation.reset()
        setStorageError(lockUnsupportedMessage)
        return false
      }
      if (lockResult.status === 'failed') {
        mutation.reset()
        setStorageError(lockFailedMessage)
        return false
      }
      if (lockResult.value.status === 'blocked') {
        mutation.reset()
        setStorageError(lockResult.value.reason === 'storageUnavailable'
          ? storageRequiredMessage
          : differentRequestPendingMessage)
        return false
      }
      return true
    }).finally(() => {
      setLockPending(false)
    })
  }

  return {
    error: mutation.error,
    isPending: mutation.isPending || lockPending,
    reset,
    storageError,
    submit,
  }
}
