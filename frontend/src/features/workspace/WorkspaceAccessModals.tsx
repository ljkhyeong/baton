import { Icon } from '@/shared/ui/Icon'
import {
  FormError,
  ModalShell,
  useSubmissionLock,
} from './WorkspaceModalPrimitives'
import type { SaveResult } from './WorkspaceModalPrimitives'

export function ShareLinkFallback({
  shareUrl,
  onClose,
}: {
  shareUrl: string
  onClose: () => void
}) {
  return (
    <ModalShell
      title="공유 링크 직접 복사"
      description="브라우저가 자동 복사를 허용하지 않았어요. 아래 링크를 선택해 복사한 뒤 구성원에게 전달해 주세요."
      onClose={onClose}
    >
      <div className="share-link-fallback">
        <label htmlFor="share-link-value">공유 링크</label>
        <input
          id="share-link-value"
          autoFocus
          readOnly
          value={shareUrl}
          onFocus={(event) => event.currentTarget.select()}
          onClick={(event) => event.currentTarget.select()}
        />
        <p>이 링크를 가진 사람은 작업 공간을 읽고 수정할 수 있어요.</p>
        <button type="button" className="primary-button full-button" onClick={onClose}>확인</button>
      </div>
    </ModalShell>
  )
}

export function AccessKeyModal({
  pending,
  error,
  storageError,
  onClose,
  onShare,
  onRotate,
}: {
  pending: boolean
  error: unknown
  storageError: string
  onClose: () => void
  onShare: () => void
  onRotate: () => SaveResult
}) {
  const submission = useSubmissionLock(pending)
  const shareCurrentLink = () => {
    if (!submission.closeGuardRef.current) onShare()
  }
  const rotate = () => {
    if (submission.closeGuardRef.current) return
    submission.start(onRotate())
  }

  return (
    <ModalShell
      title="공유 접근 키 관리"
      description="공유 링크를 전달하거나, 링크가 외부에 알려졌을 때 접근 키를 새로 발급할 수 있습니다."
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <div className="access-key-management">
        <div className="access-key-notice">
          <Icon name="alert" size={18} />
          <p>
            <strong>키를 바꾸면 이전 공유 링크는 즉시 열리지 않습니다.</strong>
            구성원에게 새 공유 링크를 다시 전달해 주세요.
          </p>
        </div>
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <FormError error={error} />
        <div className="form-actions">
          <button type="button" className="secondary-button" onClick={shareCurrentLink} disabled={submission.pending}>
            현재 링크 복사
          </button>
          <button type="button" className="danger-button" onClick={rotate} disabled={submission.pending}>
            {submission.pending ? '접근 키 바꾸는 중…' : '접근 키 바꾸기'}
          </button>
        </div>
      </div>
    </ModalShell>
  )
}
