import { ApiError } from '@/shared/api/ApiError'
import { mutationError } from './workspacePresentation'
import { isWorkspaceAccessDenied } from './api'

type WorkspaceAccessKeyRecoveryProps = {
  pendingIdempotencyKey: string | null
  rotationError: unknown
  storageError: string
  pending: boolean
  cleanupRetryAvailable: boolean
  onRecover: () => void
  onRetryCleanup: () => void
}

function isExpiredIdempotencyReplay(error: unknown) {
  return error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED'
}

export function hasWorkspaceAccessKeyRecovery({
  pendingIdempotencyKey,
  rotationError,
}: Pick<WorkspaceAccessKeyRecoveryProps, 'pendingIdempotencyKey' | 'rotationError'>) {
  return Boolean(pendingIdempotencyKey)
    || isExpiredIdempotencyReplay(rotationError)
    || isWorkspaceAccessDenied(rotationError)
}

export function WorkspaceAccessKeyRecovery({
  pendingIdempotencyKey,
  rotationError,
  storageError,
  pending,
  cleanupRetryAvailable,
  onRecover,
  onRetryCleanup,
}: WorkspaceAccessKeyRecoveryProps) {
  if (isExpiredIdempotencyReplay(rotationError)) {
    return (
      <div className="workspace-key-fallback">
        <p className="form-error" role="alert">공유 링크가 다시 변경되어 이전 링크를 복구할 수 없습니다.</p>
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
        {cleanupRetryAvailable && (
          <>
            <p>이전에 링크를 바꾸려던 임시 기록을 지웁니다.</p>
            <button type="button" className="secondary-button" onClick={onRetryCleanup}>
              임시 기록 정리
            </button>
          </>
        )}
      </div>
    )
  }

  if (isWorkspaceAccessDenied(rotationError)) {
    return (
      <div className="workspace-key-fallback">
        <p className="form-error" role="alert">다른 기기에서 공유 링크를 변경한 것으로 보입니다.</p>
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
        {cleanupRetryAvailable && (
          <>
            <p>이전에 링크를 바꾸려던 임시 기록을 지웁니다.</p>
            <button type="button" className="secondary-button" onClick={onRetryCleanup}>
              임시 기록 정리
            </button>
          </>
        )}
      </div>
    )
  }

  if (!pendingIdempotencyKey) return null

  return (
    <div className="workspace-key-fallback">
      <p>공유 링크가 바뀌었지만 새 링크를 받지 못했을 수 있습니다.</p>
      {rotationError ? <p className="form-error" role="alert">{mutationError(rotationError)}</p> : null}
      {storageError && <p className="form-error" role="alert">{storageError}</p>}
      <button
        type="button"
        className="primary-button"
        disabled={pending}
        onClick={onRecover}
      >
        {pending ? '변경 결과 확인하는 중…' : '변경된 공유 링크 확인'}
      </button>
    </div>
  )
}
