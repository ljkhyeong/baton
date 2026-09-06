import { useId, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { isTerminalContentCreationError } from './useContentCreationCommand'
import { mutationError } from './workspacePresentation'

export type CreationModalStatus = {
  pending: boolean
  error: unknown
  storageError: string
  recoveryAvailable: boolean
}

export type SaveResult = false | void | Promise<unknown>

export function useSubmissionLock(pending: boolean) {
  const [starting, setStarting] = useState(false)
  const closeGuardRef = useRef(pending)
  const submissionPending = pending || starting
  closeGuardRef.current = submissionPending

  const start = (result: SaveResult) => {
    if (!result) return
    closeGuardRef.current = true
    setStarting(true)
    void result.then(
      () => setStarting(false),
      () => setStarting(false),
    )
  }

  return { closeGuardRef, pending: submissionPending, start }
}

export function contentCreationError(error: unknown) {
  if (error instanceof ApiError
    && (error.code === 'IDEMPOTENCY_KEY_REUSED' || error.code === 'IDEMPOTENCY_REPLAY_EXPIRED')) {
    return '이전에 추가한 항목을 다시 확인할 수 없습니다. 목록에 이미 있는지 확인한 뒤, 없으면 다시 추가하세요.'
  }
  if (isTerminalContentCreationError(error)) return mutationError(error)
  return `${mutationError(error)} 같은 내용으로 다시 제출하면 중복으로 만들지 않고 저장 여부를 확인합니다.`
}

export function ModalShell({
  title,
  description,
  className,
  kicker = 'BATON',
  closeDisabled = false,
  closeGuardRef,
  initialFocusRef,
  onClose,
  children,
}: {
  title: string
  description: string
  className?: string
  kicker?: ReactNode
  closeDisabled?: boolean
  closeGuardRef?: RefObject<boolean>
  initialFocusRef?: RefObject<HTMLElement | null>
  onClose: () => void
  children: ReactNode
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descriptionId = useId()
  const closeBlocked = () => closeDisabled || Boolean(closeGuardRef?.current)

  useLayoutEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    dialog.showModal()
    const initialFocus = initialFocusRef?.current
      ?? dialog.querySelector<HTMLElement>([
        'input:not([disabled]):not([type="hidden"])',
        'select:not([disabled])',
        'textarea:not([disabled])',
      ].join(','))
      ?? dialog.querySelector<HTMLElement>('button:not([disabled])')
    initialFocus?.focus()
    return () => {
      if (dialog.open) dialog.close()
    }
  }, [])

  return (
    <dialog
      ref={dialogRef}
      className="modal-backdrop"
      aria-busy={closeDisabled || undefined}
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => {
        event.preventDefault()
        if (!closeBlocked()) onClose()
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && closeBlocked()) {
          event.preventDefault()
          event.stopPropagation()
        }
      }}
      onMouseDown={(event) =>
        !closeBlocked() && event.currentTarget === event.target && onClose()}
    >
      <section
        className={className ? `modal ${className}` : 'modal'}
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
    </dialog>
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
        저장 결과를 확인하지 못했습니다. 같은 내용으로 다시 제출하면 중복 없이 결과를 확인합니다.
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
