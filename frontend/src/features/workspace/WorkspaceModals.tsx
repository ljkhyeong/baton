import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent, ReactNode, RefObject } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { isTerminalContentCreationError } from './useContentCreationCommand'
import { useFocusBoundary } from './useFocusBoundary'
import {
  categoryCopy,
  formatLocalDate,
  getMember,
  mutationError,
  phaseCopy,
} from './workspacePresentation'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  Decision,
  HandoffCategory,
  HandoffItem,
  Member,
  Role,
  RoleResource,
  Routine,
  RoutinePhase,
  Season,
  SeasonRound,
  UpdateSeasonRoundRequest,
  UpdateRoleRequest,
  UpdateRoleResourceRequest,
  UpdateRoutineRequest,
  UpdateDecisionRequest,
  UpdateHandoffItemRequest,
} from './types'

type CreationModalStatus = {
  pending: boolean
  error: unknown
  storageError: string
  recoveryAvailable: boolean
}

type SaveResult = boolean | void | Promise<boolean | void>

function useSubmissionLock(pending: boolean) {
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

export type RoleFormRequest = CreateRoleRequest & UpdateRoleRequest
export type RoleResourceFormRequest = CreateRoleResourceRequest & UpdateRoleResourceRequest
export type RoutineFormRequest = CreateRoutineRequest & UpdateRoutineRequest
export type SeasonRoundFormRequest = CreateSeasonRoundRequest & UpdateSeasonRoundRequest
export type DecisionFormRequest = CreateDecisionRequest & UpdateDecisionRequest
export type HandoffItemFormRequest = CreateHandoffItemRequest & UpdateHandoffItemRequest

function localTodayValue() {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function clampToSeason(value: string, season: Season) {
  if (value < season.startDate) return season.startDate
  if (value > season.endDate) return season.endDate
  return value
}

function contentCreationError(error: unknown) {
  if (error instanceof ApiError
    && (error.code === 'IDEMPOTENCY_KEY_REUSED' || error.code === 'IDEMPOTENCY_REPLAY_EXPIRED')) {
    return '이전 생성 요청을 더 재생할 수 없습니다. 목록에 항목이 이미 생겼는지 확인한 뒤, 필요하면 다시 제출해 주세요.'
  }
  if (isTerminalContentCreationError(error)) return mutationError(error)
  return `${mutationError(error)} 입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.`
}

function ModalShell({
  title,
  description,
  closeDisabled = false,
  closeGuardRef,
  onClose,
  children,
}: {
  title: string
  description: string
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
        className="modal"
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
        <span className="section-kicker">BATON</span>
        <h2 id={titleId}>{title}</h2>
        <p id={descriptionId} className="modal-description">{description}</p>
        {children}
      </section>
    </div>
  )
}

function FormError({ error }: { error: unknown }) {
  return error ? <p className="form-error" role="alert">{mutationError(error)}</p> : null
}

function CreationFormFeedback({
  error,
  storageError,
  recoveryAvailable,
}: Pick<CreationModalStatus, 'error' | 'storageError' | 'recoveryAvailable'>) {
  if (storageError) return <p className="form-error" role="alert">{storageError}</p>
  if (error) return <p className="form-error" role="alert">{contentCreationError(error)}</p>
  if (recoveryAvailable) {
    return (
      <p className="form-retry-notice" role="status">
        이전에 저장 결과를 확인하지 못한 요청이 있습니다. 그때와 같은 내용을 다시 제출하면 새 항목을 만들지 않고 결과를 확인합니다.
      </p>
    )
  }
  return null
}

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

export function DecisionModal({
  roles,
  members,
  selectedRoleId,
  decision,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  roles: Role[]
  members: Member[]
  selectedRoleId: string
  decision?: Decision
  onClose: () => void
  onSave: (decision: DecisionFormRequest) => SaveResult
}) {
  const editing = Boolean(decision)
  const submission = useSubmissionLock(pending)
  const [title, setTitle] = useState(decision?.title ?? '')
  const [reason, setReason] = useState(decision?.reason ?? '')
  const [alternative, setAlternative] = useState(decision?.alternative ?? '')
  const [roleIds, setRoleIds] = useState<string[]>(
    decision?.roleIds.length
      ? decision.roleIds
      : [selectedRoleId || roles[0]?.id || ''].filter(Boolean),
  )
  const [authorMemberId, setAuthorMemberId] = useState(
    decision?.authorMemberId ?? members[0]?.id ?? '',
  )
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!title.trim() || !reason.trim() || !roleIds.length || !authorMemberId
      || submission.closeGuardRef.current) return
    submission.start(onSave({
      title: title.trim(),
      reason: reason.trim(),
      alternative: alternative.trim() || (editing ? '' : '별도 대안을 검토하지 않음'),
      authorMemberId,
      roleIds,
    }))
  }
  return (
    <ModalShell
      title={editing ? '결정 기록 수정' : '결정과 이유 남기기'}
      description={editing
        ? '잘못 적은 내용과 작성자, 관련 역할을 바로잡습니다. 처음 기록한 시각은 그대로 남아요.'
        : '나중에 ‘왜 이렇게 했지?’라는 질문에 답할 수 있도록 맥락을 함께 적어주세요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>무엇을 바꾸기로 했나요?</span>
          <input
            autoFocus
            required
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="예: 세션 시작 시간을 30분 앞당긴다"
          />
        </label>
        <label>
          <span>왜 이 선택을 했나요?</span>
          <textarea
            required
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="반복된 문제나 관찰한 근거를 적어주세요"
            rows={3}
          />
        </label>
        <label>
          <span>검토한 다른 선택</span>
          <input
            value={alternative}
            onChange={(event) => setAlternative(event.target.value)}
            placeholder="예: 세션 시간을 30분 연장하기"
          />
        </label>
        <label>
          <span>작성자</span>
          <select
            required
            value={authorMemberId}
            onChange={(event) => setAuthorMemberId(event.target.value)}
          >
            {members.map((member) => (
              <option key={member.id} value={member.id}>{member.name}</option>
            ))}
          </select>
        </label>
        <fieldset className="modal-choice-group">
          <legend>영향받는 역할</legend>
          <div className="modal-choice-list">
            {roles.map((role) => (
              <label key={role.id}>
                <input
                  type="checkbox"
                  checked={roleIds.includes(role.id)}
                  onChange={(event) => setRoleIds((current) =>
                    event.target.checked
                      ? [...current, role.id]
                      : current.filter((roleId) => roleId !== role.id))}
                />
                <span>{role.name}</span>
              </label>
            ))}
          </div>
          {!roleIds.length && <small className="form-hint">관련 역할을 하나 이상 선택해 주세요.</small>}
        </fieldset>
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '결정 기록하기'}
          pendingLabel={editing ? '결정 저장하는 중…' : '결정 기록하는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

export function RoleModal({
  members,
  season,
  role,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  members: Member[]
  season: Season
  role?: Role
  onClose: () => void
  onSave: (request: RoleFormRequest) => SaveResult
}) {
  const editing = Boolean(role)
  const submission = useSubmissionLock(pending)
  const [name, setName] = useState(role?.name ?? '')
  const [purpose, setPurpose] = useState(role?.purpose ?? '')
  const [currentMemberId, setCurrentMemberId] = useState(role?.currentMemberId ?? '')
  const [nextMemberId, setNextMemberId] = useState(role?.nextMemberId ?? '')
  const [assignmentStartDate, setAssignmentStartDate] = useState(
    role ? role.assignmentStartDate ?? '' : season.startDate,
  )
  const [assignmentEndDate, setAssignmentEndDate] = useState(
    role ? role.assignmentEndDate ?? '' : season.endDate,
  )
  const [responsibilities, setResponsibilities] = useState(role?.responsibilities.join('\n') ?? '')
  const [risk, setRisk] = useState(role?.risk ?? '')
  const [validationMessage, setValidationMessage] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current) return
    setValidationMessage('')
    if (assignmentStartDate && assignmentEndDate && assignmentEndDate < assignmentStartDate) {
      setValidationMessage('담당 종료일은 시작일보다 빠를 수 없습니다.')
      return
    }
    submission.start(onSave({
      name: name.trim(),
      purpose: purpose.trim(),
      currentMemberId: currentMemberId || null,
      nextMemberId: nextMemberId || null,
      assignmentStartDate: assignmentStartDate || null,
      assignmentEndDate: assignmentEndDate || null,
      responsibilities: splitList(responsibilities),
      risk: risk.trim() || null,
    }))
  }
  return (
    <ModalShell
      title={editing ? '역할 수정' : '새 역할 만들기'}
      description={editing
        ? '담당자와 기간, 책임처럼 달라진 역할 정보를 현재 운영에 맞게 고쳐주세요.'
        : '사람의 직함보다, 팀에 계속 남아야 할 책임과 담당 기간을 정리해 주세요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>역할 이름</span>
          <input
            autoFocus
            required
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="예: 질문 큐레이터"
          />
        </label>
        <label>
          <span>이 역할이 존재하는 이유</span>
          <textarea
            required
            value={purpose}
            onChange={(event) => setPurpose(event.target.value)}
            placeholder="이 역할이 팀에서 해결하는 문제를 적어주세요"
            rows={3}
          />
        </label>
        <div className="form-grid">
          <label>
            <span>현재 담당자</span>
            <select
              value={currentMemberId}
              onChange={(event) => setCurrentMemberId(event.target.value)}
            >
              <option value="">담당자 미정</option>
              {members.map((member) => (
                <option key={member.id} value={member.id}>{member.name}</option>
              ))}
            </select>
          </label>
          <label>
            <span>다음 담당자</span>
            <select value={nextMemberId} onChange={(event) => setNextMemberId(event.target.value)}>
              <option value="">다음 담당자 미정</option>
              {members.map((member) => (
                <option key={member.id} value={member.id}>{member.name}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="form-grid">
          <label>
            <span>담당 시작일</span>
            <input
              type="date"
              value={assignmentStartDate}
              onChange={(event) => setAssignmentStartDate(event.target.value)}
            />
          </label>
          <label>
            <span>담당 종료일</span>
            <input
              type="date"
              min={assignmentStartDate || undefined}
              value={assignmentEndDate}
              onChange={(event) => setAssignmentEndDate(event.target.value)}
            />
          </label>
        </div>
        <label>
          <span>핵심 책임</span>
          <textarea
            value={responsibilities}
            onChange={(event) => setResponsibilities(event.target.value)}
            placeholder={'질문 수집\n공통 막힘 정리'}
            rows={3}
          />
          <small>줄바꿈 또는 쉼표로 구분해 주세요.</small>
        </label>
        <label>
          <span>위험 신호</span>
          <textarea
            value={risk}
            onChange={(event) => setRisk(event.target.value)}
            placeholder="예: 자료가 개인 계정에만 저장되어 있어요"
            rows={2}
          />
        </label>
        {validationMessage && (
          <p className="form-error" role="alert">{validationMessage}</p>
        )}
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '역할 만들기'}
          pendingLabel={editing ? '역할 저장하는 중…' : '역할 만드는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

export function RoleResourceModal({
  roles,
  selectedRoleId,
  resource,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  roles: Role[]
  selectedRoleId: string
  resource?: RoleResource
  onClose: () => void
  onSave: (request: RoleResourceFormRequest) => SaveResult
}) {
  const editing = Boolean(resource)
  const submission = useSubmissionLock(pending)
  const titleInputRef = useRef<HTMLInputElement>(null)
  const [roleId, setRoleId] = useState(
    (resource?.roleId ?? selectedRoleId) || roles[0]?.id || '',
  )
  const [title, setTitle] = useState(resource?.title ?? '')
  const [url, setUrl] = useState(resource?.url ?? '')
  const [description, setDescription] = useState(resource?.description ?? '')
  const [titleValidationMessage, setTitleValidationMessage] = useState('')
  const [urlValidationMessage, setUrlValidationMessage] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current || !roleId) return
    if (!title.trim()) {
      setTitleValidationMessage('자료 이름을 입력해 주세요.')
      titleInputRef.current?.focus()
      return
    }
    const normalizedUrl = url.trim()
    try {
      const parsed = new URL(normalizedUrl)
      if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:')
        || !parsed.hostname || parsed.username || parsed.password) {
        throw new Error('invalid url')
      }
    } catch {
      setUrlValidationMessage('사용자 정보 없이 http 또는 https로 시작하는 전체 링크를 입력해 주세요.')
      return
    }
    setTitleValidationMessage('')
    setUrlValidationMessage('')
    submission.start(onSave({
      roleId,
      title: title.trim(),
      url: normalizedUrl,
      description: description.trim() || null,
    }))
  }
  return (
    <ModalShell
      title={editing ? '참고 자료 수정' : '역할에 참고 자료 연결'}
      description="문서나 외부 링크를 역할에 연결해, 담당자가 바뀌어도 같은 자료를 바로 찾게 합니다."
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" noValidate onSubmit={submit}>
        <label>
          <span>역할</span>
          <select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>
            {roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}
          </select>
        </label>
        <label>
          <span>자료 이름</span>
          <input
            ref={titleInputRef}
            autoFocus
            required
            maxLength={200}
            aria-invalid={Boolean(titleValidationMessage)}
            aria-describedby={titleValidationMessage ? 'role-resource-title-error' : undefined}
            value={title}
            onChange={(event) => {
              setTitle(event.target.value)
              setTitleValidationMessage('')
            }}
            placeholder="예: 질문 정리 가이드"
          />
        </label>
        {titleValidationMessage && (
          <p id="role-resource-title-error" className="form-error" role="alert">
            {titleValidationMessage}
          </p>
        )}
        <label>
          <span>링크</span>
          <input
            type="url"
            required
            maxLength={2048}
            autoCapitalize="none"
            spellCheck={false}
            aria-invalid={Boolean(urlValidationMessage)}
            aria-describedby={urlValidationMessage ? 'role-resource-url-error' : undefined}
            value={url}
            onChange={(event) => {
              setUrl(event.target.value)
              setUrlValidationMessage('')
            }}
            placeholder="https://docs.example.com/guide"
          />
        </label>
        <label>
          <span>자료 설명</span>
          <textarea
            maxLength={1000}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="이 자료를 언제, 어떻게 사용하는지 적어주세요"
            rows={3}
          />
        </label>
        {urlValidationMessage && (
          <p id="role-resource-url-error" className="form-error" role="alert">
            {urlValidationMessage}
          </p>
        )}
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '자료 연결하기'}
          pendingLabel={editing ? '자료 저장하는 중…' : '자료 연결하는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

export function RoutineModal({
  roles,
  selectedRoleId,
  routine,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  roles: Role[]
  selectedRoleId: string
  routine?: Routine
  onClose: () => void
  onSave: (request: RoutineFormRequest) => SaveResult
}) {
  const editing = Boolean(routine)
  const submission = useSubmissionLock(pending)
  const [title, setTitle] = useState(routine?.title ?? '')
  const [phase, setPhase] = useState<RoutinePhase>(routine?.phase ?? 'BEFORE')
  const [dueLabel, setDueLabel] = useState(routine?.dueLabel ?? '')
  const [ownerRoleId, setOwnerRoleId] = useState(
    (routine?.ownerRoleId ?? selectedRoleId) || roles[0]?.id || '',
  )
  const [detail, setDetail] = useState(routine?.detail ?? '')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current || !ownerRoleId) return
    submission.start(onSave({
      title: title.trim(),
      phase,
      dueLabel: dueLabel.trim(),
      ownerRoleId,
      detail: detail.trim(),
    }))
  }
  return (
    <ModalShell
      title={editing ? '루틴 수정' : '반복 루틴 만들기'}
      description={editing
        ? '운영 단계와 담당 역할, 기한 문구를 현재 반복 방식에 맞게 고쳐주세요.'
        : '모임 전·중·후에 누가 무엇을 넘길지 운영 리듬에 추가합니다.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>루틴 이름</span>
          <input
            autoFocus
            required
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="예: 문제 5개 선정"
          />
        </label>
        <div className="form-grid">
          <label>
            <span>운영 단계</span>
            <select
              value={phase}
              onChange={(event) => setPhase(event.target.value as RoutinePhase)}
            >
              {(Object.keys(phaseCopy) as RoutinePhase[]).map((value) => (
                <option key={value} value={value}>{phaseCopy[value]}</option>
              ))}
            </select>
          </label>
          <label>
            <span>담당 역할</span>
            <select
              required
              value={ownerRoleId}
              onChange={(event) => setOwnerRoleId(event.target.value)}
            >
              {roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}
            </select>
          </label>
        </div>
        <label>
          <span>언제까지</span>
          <input
            required
            value={dueLabel}
            onChange={(event) => setDueLabel(event.target.value)}
            placeholder="예: 수요일 18:00"
          />
        </label>
        <label>
          <span>세부 설명</span>
          <textarea
            required
            value={detail}
            onChange={(event) => setDetail(event.target.value)}
            placeholder="완료 기준이나 다음 역할이 알아야 할 내용을 적어주세요"
            rows={3}
          />
        </label>
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '루틴 만들기'}
          pendingLabel={editing ? '루틴 저장하는 중…' : '루틴 만드는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

export function SeasonRoundModal({
  season,
  roundCount,
  round,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  season: Season
  roundCount: number
  round?: SeasonRound
  onClose: () => void
  onSave: (request: SeasonRoundFormRequest) => SaveResult
}) {
  const editing = Boolean(round)
  const submission = useSubmissionLock(pending)
  const [name, setName] = useState(round?.name ?? `${roundCount + 1}회차`)
  const [meetingDate, setMeetingDate] = useState(
    round ? round.meetingDate ?? '' : clampToSeason(localTodayValue(), season),
  )
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current || !name.trim() || !meetingDate) return
    submission.start(onSave({ name: name.trim(), meetingDate }))
  }
  return (
    <ModalShell
      title={editing ? '회차 정보 수정' : '회차 만들기'}
      description={editing
        ? '회차 이름과 모임 날짜만 바꿉니다. 루틴 실행과 완료 상태는 그대로 유지됩니다.'
        : '현재 루틴을 이번 운영의 실행 목록으로 복사합니다. 이후 루틴을 바꿔도 이 회차의 기록은 그대로 남아요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>회차 이름</span>
          <input
            autoFocus
            required
            maxLength={100}
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="예: 3회차"
          />
        </label>
        <label>
          <span>모임 날짜</span>
          <input
            type="date"
            required
            min={season.startDate}
            max={season.endDate}
            value={meetingDate}
            onChange={(event) => setMeetingDate(event.target.value)}
          />
          <small>
            {formatLocalDate(season.startDate)}부터 {formatLocalDate(season.endDate)} 사이에서 선택해 주세요.
          </small>
        </label>
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '회차 만들기'}
          pendingLabel={editing ? '회차 저장하는 중…' : '회차 만드는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

export function HandoffItemModal({
  roles,
  selectedRoleId,
  item,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  roles: Role[]
  selectedRoleId: string
  item?: HandoffItem
  onClose: () => void
  onSave: (item: HandoffItemFormRequest) => SaveResult
}) {
  const editing = Boolean(item)
  const submission = useSubmissionLock(pending)
  const [roleId, setRoleId] = useState(item?.roleId ?? selectedRoleId ?? roles[0]?.id ?? '')
  const [label, setLabel] = useState(item?.label ?? '')
  const [category, setCategory] = useState<HandoffCategory>(
    item?.category ?? 'RESPONSIBILITY',
  )
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current || !roleId) return
    submission.start(onSave({ roleId, label: label.trim(), category }))
  }
  return (
    <ModalShell
      title={editing ? '바통북 항목 수정' : '바통북 항목 추가'}
      description={editing
        ? '잘못 적은 역할, 내용이나 분류를 고칩니다. 준비 완료 표시는 그대로 유지돼요.'
        : '다음 담당자가 바로 움직이려면 꼭 알아야 할 내용 하나를 남겨주세요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>역할</span>
          <select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>
            {roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}
          </select>
        </label>
        <label>
          <span>남길 내용</span>
          <input
            autoFocus
            required
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            placeholder="예: 문제 선정 기준 문서 링크"
          />
        </label>
        <label>
          <span>항목 종류</span>
          <select
            value={category}
            onChange={(event) => setCategory(event.target.value as HandoffCategory)}
          >
            {(Object.keys(categoryCopy) as HandoffCategory[]).map((value) => (
              <option key={value} value={value}>{categoryCopy[value]}</option>
            ))}
          </select>
        </label>
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '항목 추가하기'}
          pendingLabel={editing ? '항목 저장하는 중…' : '항목 추가하는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

function FormActions({
  pending,
  closeGuardRef,
  submitLabel,
  pendingLabel,
  onClose,
}: {
  pending: boolean
  closeGuardRef: RefObject<boolean>
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
      <button type="submit" className="primary-button" disabled={pending}>
        {pending ? pendingLabel : submitLabel}
      </button>
    </div>
  )
}

function splitList(value: string) {
  return [...new Set(value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean))]
}

export function HandoffPreview({
  role,
  members,
  routines,
  decisions,
  resources,
  items,
  progress,
  onClose,
}: {
  role: Role
  members: Member[]
  routines: Routine[]
  decisions: Decision[]
  resources: RoleResource[]
  items: HandoffItem[]
  progress: number
  onClose: () => void
}) {
  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedDecisions = decisions.filter((decision) => decision.roleIds.includes(role.id))
  const remainingItems = items.filter((item) => !item.completed)
  return (
    <ModalShell
      title={`${role.name} 바통북`}
      description={`${owner?.name ?? '이전 담당자'}에서 ${next?.name ?? '다음 담당자'}에게 이어질 역할 기록입니다.`}
      onClose={onClose}
    >
      <div className="book-preview">
        <div className="book-progress">
          <span>준비도</span>
          <strong>{progress}%</strong>
        </div>
        <section>
          <span>01 · 역할의 목적</span>
          <p>{role.purpose}</p>
        </section>
        <section>
          <span>02 · 반복하는 일</span>
          {routines.length
            ? (
                <ul>
                  {routines.map((routine) => (
                    <li key={routine.id}>{routine.title} · {routine.dueLabel}</li>
                  ))}
                </ul>
              )
            : <p>연결된 반복 루틴이 아직 없습니다.</p>}
        </section>
        <section>
          <span>03 · 중요한 결정</span>
          {relatedDecisions.length
            ? relatedDecisions.map((decision) => (
                <blockquote key={decision.id}>
                  “{decision.title}”
                  <small>{decision.reason}</small>
                </blockquote>
              ))
            : <p>연결된 결정이 아직 없습니다.</p>}
        </section>
        <section>
          <span>04 · 참고 자료</span>
          {resources.length
            ? (
                <ul className="book-resource-links">
                  {resources.map((resource) => (
                    <li key={resource.id}>
                      <a
                        href={resource.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        aria-label={`${resource.title} 새 창에서 열기`}
                      >
                        {resource.title}
                      </a>
                      {resource.description && <small>{resource.description}</small>}
                    </li>
                  ))}
                </ul>
              )
            : <p>연결된 참고 자료가 아직 없습니다.</p>}
        </section>
        <section>
          <span>05 · 남은 정리</span>
          {remainingItems.length
            ? (
                <ul>
                  {remainingItems.map((item) => <li key={item.id}>{item.label}</li>)}
                </ul>
              )
            : <p>남은 정리가 없습니다.</p>}
        </section>
        <button type="button" className="primary-button full-button" onClick={onClose}>
          미리보기 닫기
        </button>
      </div>
    </ModalShell>
  )
}
