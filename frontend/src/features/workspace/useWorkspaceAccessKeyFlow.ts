import { useRef, useState } from 'react'
import { resolveIdempotencyJournalFailure } from '@/shared/api/idempotencyJournal'
import { isJsonCleanupComplete } from '@/shared/lib/durableStorage'
import { saveAccessKey } from './storage'
import { isWorkspaceAccessDenied, type WorkspaceScope } from './api'
import {
  clearPendingAccessKeyRotation,
  idempotencyKeyForAccessKeyRotation,
  pendingAccessKeyRotation,
  runWithAccessKeyRotationLock,
} from './pendingAccessKeyChange'
import { useRotateAccessKeyMutation } from './queries'
import { pendingStorageRequiredMessage } from './useContentCreationCommand'

type ToastTone = 'success' | 'error'

type WorkspaceAccessKeyFlowOptions = {
  scope: WorkspaceScope
  currentAccessKey: string
  accountAccessEnabled?: boolean
  onAccessKeyChange: (accessKey: string) => void
  onCloseModal: () => void
  onOpenShareLink: () => void
  notify: (message: string, tone?: ToastTone) => void
}

const rotationCleanupErrorMessage = '새 공유 링크를 발급했지만 임시 기록을 지우지 못했습니다. 새 링크를 보관하고 브라우저 저장을 허용한 뒤 다시 시도하세요.'
const rotationJournalCleanupErrorMessage = '이전 공유 링크의 임시 기록을 지우지 못했습니다. 브라우저 저장을 허용한 뒤 다시 시도하세요.'
const staleRotationReplayMessage = '임시 기록을 지웠습니다. 새 링크는 아직 발급하지 않았습니다. ‘공유 링크 재발급’을 다시 누르세요.'
const rotationBusyMessage = '다른 탭에서 새 공유 링크를 확인하고 있습니다. 그 탭에서 끝낸 뒤 다시 시도하세요.'
const rotationLockUnsupportedMessage = '이 브라우저에서는 공유 링크를 재발급할 수 없습니다. 브라우저를 업데이트한 뒤 다시 시도해 주세요.'
const rotationLockFailedMessage = '공유 링크를 재발급할 수 없습니다. 새로고침한 뒤 다시 시도해 주세요.'
const accessKeyRotationJournalPolicy = {
  startNewRequestCodes: new Set(['INVALID_INPUT', 'IDEMPOTENCY_KEY_REUSED']),
  confirmBeforeNewRequestCodes: new Set(['IDEMPOTENCY_REPLAY_EXPIRED']),
}

function replaceAccessKeyFragment(accessKey?: string) {
  const fragment = accessKey ? `#accessKey=${encodeURIComponent(accessKey)}` : ''
  window.history.replaceState(
    window.history.state,
    '',
    `${window.location.pathname}${window.location.search}${fragment}`,
  )
}

export function useWorkspaceAccessKeyFlow({
  scope,
  currentAccessKey,
  accountAccessEnabled = false,
  onAccessKeyChange,
  onCloseModal,
  onOpenShareLink,
  notify,
}: WorkspaceAccessKeyFlowOptions) {
  const { teamId, seasonId } = scope
  const rotateAccessKeyMutation = useRotateAccessKeyMutation(scope)
  const [rotationStorageError, setRotationStorageError] = useState('')
  const [rotationCleanupRetryKey, setRotationCleanupRetryKey] = useState<string | null>(null)
  const [rotationLockPending, setRotationLockPending] = useState(false)
  const rotationRequestInFlightRef = useRef(false)
  const pendingRotationIdempotencyKey = pendingAccessKeyRotation(teamId)
  const shareUrl = `${window.location.origin}/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}${accountAccessEnabled ? '' : `#accessKey=${encodeURIComponent(currentAccessKey)}`}`

  const clearRotationJournal = (idempotencyKey: string) => {
    const cleanupResult = clearPendingAccessKeyRotation(teamId, idempotencyKey)
    if (isJsonCleanupComplete(cleanupResult)) {
      setRotationCleanupRetryKey((current) => current === idempotencyKey ? null : current)
      return true
    }

    setRotationCleanupRetryKey(idempotencyKey)
    setRotationStorageError(rotationJournalCleanupErrorMessage)
    return false
  }

  const ensureRotationJournalReady = () => {
    if (!rotationCleanupRetryKey) return true
    if (!clearRotationJournal(rotationCleanupRetryKey)) return false
    setRotationStorageError('')
    return true
  }

  const reportRotationLockFailure = (status: 'busy' | 'unsupported' | 'failed') => {
    rotateAccessKeyMutation.reset()
    setRotationStorageError(status === 'busy'
      ? rotationBusyMessage
      : status === 'unsupported'
        ? rotationLockUnsupportedMessage
        : rotationLockFailedMessage)
  }

  const retryRotationJournalCleanup = async () => {
    if (!rotationCleanupRetryKey || rotationRequestInFlightRef.current) return
    rotationRequestInFlightRef.current = true
    setRotationLockPending(true)
    try {
      const cleanupKey = rotationCleanupRetryKey
      const lockResult = await runWithAccessKeyRotationLock(
        teamId,
        async () => clearRotationJournal(cleanupKey),
      )
      if (lockResult.status !== 'completed') {
        reportRotationLockFailure(lockResult.status)
        return
      }
      if (!lockResult.value) return
      setRotationStorageError('브라우저의 임시 기록을 정리했습니다. 최신 공유 링크로 다시 열어 주세요.')
    } finally {
      rotationRequestInFlightRef.current = false
      setRotationLockPending(false)
    }
  }

  const finishAccessKeyRotation = (rotatedAccessKey: string, idempotencyKey: string) => {
    const pendingCleared = clearRotationJournal(idempotencyKey)
    if (rotatedAccessKey === currentAccessKey) {
      const message = pendingCleared
        ? staleRotationReplayMessage
        : '임시 기록을 지우지 못해 새 링크를 발급하지 않았습니다. 브라우저 저장을 허용한 뒤 다시 시도하세요.'
      setRotationStorageError(message)
      notify(message, 'error')
      return
    }

    onAccessKeyChange(rotatedAccessKey)
    const saved = saveAccessKey(teamId, rotatedAccessKey)
    replaceAccessKeyFragment(saved ? undefined : rotatedAccessKey)
    if (!pendingCleared) setRotationStorageError(rotationCleanupErrorMessage)
    if (saved) {
      if (pendingCleared) {
        onCloseModal()
        notify('공유 링크를 재발급했어요. 이제 새 링크만 사용할 수 있습니다.')
      } else {
        notify(rotationCleanupErrorMessage, 'error')
      }
    } else {
      onOpenShareLink()
      const message = pendingCleared
        ? '새 공유 링크를 브라우저에 저장하지 못했습니다. 표시된 링크를 안전한 곳에 보관하세요.'
        : '새 공유 링크가 브라우저에 저장됐는지 확인하지 못했습니다. 표시된 링크를 안전한 곳에 보관하세요. 임시 기록도 지우지 못했습니다.'
      notify(message, 'error')
    }
  }

  const handleAccessKeyRotationError = (
    error: unknown,
    idempotencyKey: string,
    recovering = false,
  ) => {
    const resolution = resolveIdempotencyJournalFailure(
      error,
      accessKeyRotationJournalPolicy,
    )
    if (resolution !== 'retrySameRequest' || (recovering && isWorkspaceAccessDenied(error))) {
      clearRotationJournal(idempotencyKey)
    }
  }

  const recoverPendingAccessKeyRotation = async () => {
    if (!pendingRotationIdempotencyKey
      || rotationRequestInFlightRef.current
      || rotateAccessKeyMutation.isPending) return
    rotationRequestInFlightRef.current = true
    setRotationLockPending(true)
    try {
      const lockResult = await runWithAccessKeyRotationLock(teamId, async () => {
        if (rotationCleanupRetryKey) {
          ensureRotationJournalReady()
          return
        }

        const idempotencyKey = pendingAccessKeyRotation(teamId)
        if (!idempotencyKey) {
          rotateAccessKeyMutation.reset()
          setRotationStorageError('다른 탭에서 임시 기록을 지웠습니다. 최신 공유 링크가 있는지 확인하세요.')
          return
        }

        setRotationStorageError('')
        try {
          await rotateAccessKeyMutation.mutateAsync(idempotencyKey, {
            onSuccess: ({ accessKey }) => finishAccessKeyRotation(accessKey, idempotencyKey),
          })
        } catch (error) {
          handleAccessKeyRotationError(error, idempotencyKey, true)
        }
      })
      if (lockResult.status !== 'completed') reportRotationLockFailure(lockResult.status)
    } finally {
      rotationRequestInFlightRef.current = false
      setRotationLockPending(false)
    }
  }

  const copyShareLink = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(shareUrl)
      notify('공유 링크를 복사했어요.')
    } catch {
      onOpenShareLink()
      notify('자동 복사가 차단되어 직접 복사할 링크를 열었어요.', 'error')
    }
  }

  const rotateWorkspaceAccessKey = async () => {
    if (rotationRequestInFlightRef.current || rotateAccessKeyMutation.isPending) return false
    rotationRequestInFlightRef.current = true
    const confirmed = window.confirm('공유 링크를 재발급할까요? 지금까지 공유한 링크는 즉시 사용할 수 없게 됩니다.')
    if (!confirmed) {
      rotationRequestInFlightRef.current = false
      return false
    }

    setRotationLockPending(true)
    try {
      const lockResult = await runWithAccessKeyRotationLock(teamId, async () => {
        if (!ensureRotationJournalReady()) return false

        const idempotencyKey = idempotencyKeyForAccessKeyRotation(teamId)
        if (!idempotencyKey) {
          rotateAccessKeyMutation.reset()
          setRotationStorageError(pendingStorageRequiredMessage)
          return false
        }

        rotateAccessKeyMutation.reset()
        setRotationStorageError('')
        try {
          await rotateAccessKeyMutation.mutateAsync(idempotencyKey, {
            onSuccess: ({ accessKey }) => finishAccessKeyRotation(accessKey, idempotencyKey),
          })
        } catch (error) {
          handleAccessKeyRotationError(error, idempotencyKey)
        }
        return true
      })
      if (lockResult.status !== 'completed') {
        reportRotationLockFailure(lockResult.status)
        return false
      }
      return lockResult.value
    } finally {
      rotationRequestInFlightRef.current = false
      setRotationLockPending(false)
    }
  }

  return {
    copyShareLink,
    pendingRotationIdempotencyKey,
    recoverPendingAccessKeyRotation,
    retryRotationJournalCleanup,
    rotateWorkspaceAccessKey,
    rotationCleanupRetryAvailable: Boolean(rotationCleanupRetryKey),
    rotationError: rotateAccessKeyMutation.error,
    rotationPending: rotationLockPending || rotateAccessKeyMutation.isPending,
    rotationStorageError,
    shareUrl,
  }
}
