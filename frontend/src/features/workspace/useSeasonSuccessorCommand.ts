import { useEffect, useState } from 'react'
import { resolveIdempotencyJournalFailure } from '@/shared/api/idempotencyJournal'
import { isJsonCleanupComplete } from '@/shared/lib/durableStorage'
import type { WorkspaceScope } from './api'
import {
  clearPendingSeasonSuccessor,
  confirmedSeasonSuccessorCleanupRetry,
  prepareSeasonSuccessor,
  runWithSeasonSuccessorLock,
} from './pendingSeasonSuccessor'
import type { SeasonSuccessorCleanupRetry } from './pendingSeasonSuccessor'
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
const cleanupRequiredMessage = '이전 시즌 시작 요청의 완료 기록을 정리하지 못했습니다. 브라우저 저장을 허용한 뒤 완료 기록 정리를 다시 확인해 주세요.'
const cleanupCompletedMessage = '이전 시즌 시작 요청의 완료 기록을 정리했습니다. 입력을 확인한 뒤 다시 제출해 주세요.'

export function useSeasonSuccessorCommand(
  scope: WorkspaceScope,
  confirmedSourceSeasonId: string | null,
) {
  const mutation = useCreateNextSeasonMutation(scope)
  const [storageError, setStorageError] = useState('')
  const [lockPending, setLockPending] = useState(false)
  const [cleanupRetry, setCleanupRetry] = useState<SeasonSuccessorCleanupRetry | null>(null)

  useEffect(() => {
    const confirmedRetry = confirmedSeasonSuccessorCleanupRetry(
      scope.teamId,
      confirmedSourceSeasonId,
    )
    if (confirmedRetry) {
      setCleanupRetry(confirmedRetry)
      setStorageError(cleanupRequiredMessage)
      return
    }

    if (cleanupRetry?.teamId !== scope.teamId
      || cleanupRetry.sourceSeasonId !== scope.seasonId) {
      setCleanupRetry(null)
      setStorageError('')
    }
  }, [confirmedSourceSeasonId, scope.seasonId, scope.teamId])

  const reset = () => {
    mutation.reset()
    const confirmedRetry = confirmedSeasonSuccessorCleanupRetry(
      scope.teamId,
      confirmedSourceSeasonId,
    )
    if (confirmedRetry) setCleanupRetry(confirmedRetry)
    setStorageError(confirmedRetry || cleanupRetry ? cleanupRequiredMessage : '')
  }

  const retryCleanup = () => {
    if (!cleanupRetry || lockPending || mutation.isPending) return false
    setLockPending(true)
    setStorageError('')

    return runWithSeasonSuccessorLock(
      scope.teamId,
      async () => isJsonCleanupComplete(
        clearPendingSeasonSuccessor(
          cleanupRetry.teamId,
          cleanupRetry.sourceSeasonId,
          cleanupRetry.request,
          cleanupRetry.idempotencyKey,
        ),
      ),
    ).then((lockResult) => {
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
      if (!lockResult.value) {
        mutation.reset()
        setStorageError(cleanupRequiredMessage)
        return false
      }

      mutation.reset()
      setCleanupRetry(null)
      setStorageError(cleanupCompletedMessage)
      return true
    }).finally(() => {
      setLockPending(false)
    })
  }

  const submit = (
    request: CreateNextSeasonRequest,
    onSuccess: (result: CreateNextSeasonResponse) => void,
  ) => {
    if (cleanupRetry) return retryCleanup()
    if (lockPending || mutation.isPending) return false
    setLockPending(true)
    setStorageError('')

    return runWithSeasonSuccessorLock(scope.teamId, async () => {
      const preparation = prepareSeasonSuccessor(scope.teamId, scope.seasonId, request)
      if (preparation.status === 'blocked') return preparation

      const { idempotencyKey } = preparation
      const retry: SeasonSuccessorCleanupRetry = {
        teamId: scope.teamId,
        sourceSeasonId: scope.seasonId,
        request,
        idempotencyKey,
      }
      try {
        const result = await mutation.mutateAsync({ request, idempotencyKey })
        const cleanup = clearPendingSeasonSuccessor(
          scope.teamId,
          scope.seasonId,
          request,
          idempotencyKey,
        )
        if (!isJsonCleanupComplete(cleanup)) {
          setCleanupRetry(retry)
          setStorageError(cleanupRequiredMessage)
        }
        onSuccess(result)
      } catch (error) {
        if (resolveIdempotencyJournalFailure(
          error,
          seasonSuccessorJournalPolicy,
        ) !== 'retrySameRequest') {
          const cleanup = clearPendingSeasonSuccessor(
            scope.teamId,
            scope.seasonId,
            request,
            idempotencyKey,
          )
          if (!isJsonCleanupComplete(cleanup)) {
            setCleanupRetry(retry)
            setStorageError(cleanupRequiredMessage)
          }
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
    cleanupConfirmed: Boolean(
      cleanupRetry && cleanupRetry.sourceSeasonId === confirmedSourceSeasonId,
    ),
    cleanupRequired: Boolean(cleanupRetry),
    isPending: mutation.isPending || lockPending,
    reset,
    retryCleanup,
    storageError,
    submit,
  }
}
