import { ApiError } from '@/shared/api/ApiError'
import { mutationError } from './workspacePresentation'
import { isWorkspaceAccessDenied } from './useWorkspaceAccessKeyFlow'

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
        <p className="form-error" role="alert">더 최신 접근 키 변경이 완료되어 이전 결과를 자동 복구할 수 없습니다.</p>
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
        {cleanupRetryAvailable && (
          <button type="button" className="secondary-button" onClick={onRetryCleanup}>
            임시 요청 기록 삭제 재시도
          </button>
        )}
      </div>
    )
  }

  if (isWorkspaceAccessDenied(rotationError)) {
    return (
      <div className="workspace-key-fallback">
        <p className="form-error" role="alert">다른 기기에서 더 최신 접근 키 변경이 완료된 것으로 보입니다.</p>
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
        {cleanupRetryAvailable && (
          <button type="button" className="secondary-button" onClick={onRetryCleanup}>
            임시 요청 기록 삭제 재시도
          </button>
        )}
      </div>
    )
  }

  if (!pendingIdempotencyKey) return null

  return (
    <div className="workspace-key-fallback">
      <p>접근 키 변경은 서버에 반영됐지만 응답을 받지 못했을 수 있습니다.</p>
      {rotationError ? <p className="form-error" role="alert">{mutationError(rotationError)}</p> : null}
      {storageError && <p className="form-error" role="alert">{storageError}</p>}
      <button
        type="button"
        className="primary-button"
        disabled={pending}
        onClick={onRecover}
      >
        {pending ? '변경 결과 확인하는 중…' : '접근 키 변경 결과 확인'}
      </button>
    </div>
  )
}
