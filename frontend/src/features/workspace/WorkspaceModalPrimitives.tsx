import { useEffect, useId, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { isTerminalContentCreationError } from './useContentCreationCommand'
import { useFocusBoundary } from './useFocusBoundary'
import { mutationError } from './workspacePresentation'

export type CreationModalStatus = {
  pending: boolean
  error: unknown
  storageError: string
  recoveryAvailable: boolean
}

export type SaveResult = boolean | void | Promise<boolean | void>

export function useSubmissionLock(pending: boolean) {
  const [starting, setStarting] = useState(false)
  const closeGuardRef = useRef(pending)
  const observedPendingRef = useRef(pending)
  const submissionPending = pending || starting
  closeGuardRef.current = submissionPending

  useEffect(() => {
    if (pending) {
      observedPendingRef.current = true
      return
    }
    if (!observedPendingRef.current) return

    observedPendingRef.current = false
    closeGuardRef.current = false
    setStarting(false)
  }, [pending])

  const start = (result: SaveResult) => {
    if (result === false) return
    closeGuardRef.current = true
    setStarting(true)
    if (result instanceof Promise) {
      void result.then(
        () => {
          observedPendingRef.current = false
          closeGuardRef.current = false
          setStarting(false)
        },
        () => {
          observedPendingRef.current = false
          closeGuardRef.current = false
          setStarting(false)
        },
      )
    }
  }

  return { closeGuardRef, pending: submissionPending, start }
}

export function contentCreationError(error: unknown) {
  if (error instanceof ApiError
    && (error.code === 'IDEMPOTENCY_KEY_REUSED' || error.code === 'IDEMPOTENCY_REPLAY_EXPIRED')) {
    return '이전 생성 요청을 더 재생할 수 없습니다. 목록에 항목이 이미 생겼는지 확인한 뒤, 필요하면 다시 제출해 주세요.'
  }
  if (isTerminalContentCreationError(error)) return mutationError(error)
  return `${mutationError(error)} 입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.`
}

export function ModalShell({
  title,
  description,
  className,
  kicker = 'BATON',
  closeDisabled = false,
  closeGuardRef,
  onClose,
  children,
}: {
  title: string
  description: string
  className?: string
  kicker?: ReactNode
  closeDisabled?: boolean
  closeGuardRef?: RefObject<boolean>
  onClose: () => void
  children: ReactNode
}) {
  const dialogRef = useRef<HTMLElement>(null)
  const titleId = useId()
  const descriptionId = useId()
  useFocusBoundary({
    active: true,
    closeDisabled,
    closeGuardRef,
    containerRef: dialogRef,
    onClose,
  })
  const closeBlocked = () => closeDisabled || Boolean(closeGuardRef?.current)

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(event) =>
        !closeBlocked() && event.currentTarget === event.target && onClose()}
    >
      <section
        ref={dialogRef}
        className={className ? `modal ${className}` : 'modal'}
        role="dialog"
        aria-modal="true"
        aria-busy={closeDisabled || undefined}
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        tabIndex={-1}
      >
        <button
          type="button"
          className="modal-close"
          onClick={() => !closeBlocked() && onClose()}
          aria-label="닫기"
          disabled={closeDisabled}
        >
          <Icon name="close" />
        </button>
        <span className="section-kicker">{kicker}</span>
        <h2 id={titleId}>{title}</h2>
        <p id={descriptionId} className="modal-description">{description}</p>
        {children}
      </section>
    </div>
  )
}

export function FormError({
  error,
  formatError = mutationError,
}: {
  error: unknown
  formatError?: (error: unknown) => string
}) {
  return error ? <p className="form-error" role="alert">{formatError(error)}</p> : null
}

export function CreationFormFeedback({
  error,
  storageError,
  recoveryAvailable,
  formatError = contentCreationError,
}: Pick<CreationModalStatus, 'error' | 'storageError' | 'recoveryAvailable'> & {
  formatError?: (error: unknown) => string
}) {
  if (storageError) return <p className="form-error" role="alert">{storageError}</p>
  if (error) return <p className="form-error" role="alert">{formatError(error)}</p>
  if (recoveryAvailable) {
    return (
      <p className="form-retry-notice" role="status">
        이전에 저장 결과를 확인하지 못한 요청이 있습니다. 그때와 같은 내용을 다시 제출하면 새 항목을 만들지 않고 결과를 확인합니다.
      </p>
    )
  }
  return null
}

export function FormActions({
  pending,
  closeGuardRef,
  submitDisabled = false,
  submitLabel,
  pendingLabel,
  onClose,
}: {
  pending: boolean
  closeGuardRef: RefObject<boolean>
  submitDisabled?: boolean
  submitLabel: string
  pendingLabel: string
  onClose: () => void
}) {
  return (
    <div className="form-actions">
      <button
        type="button"
        className="secondary-button"
        onClick={() => !closeGuardRef.current && onClose()}
        disabled={pending}
      >
        취소
      </button>
      <button type="submit" className="primary-button" disabled={pending || submitDisabled}>
        {pending ? pendingLabel : submitLabel}
      </button>
    </div>
  )
}
