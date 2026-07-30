import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent, ReactNode, RefObject } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { isTerminalContentCreationError } from './useContentCreationCommand'
import { useFocusBoundary } from './useFocusBoundary'
import { pilotCalendarDate } from './seasonCalendar'
import {
  categoryCopy,
  formatLocalDate,
  getMember,
  isActiveMember,
  memberDisplayName,
  memberSelectionOptions,
  mutationError,
  phaseCopy,
} from './workspacePresentation'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateMemberRequest,
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
  UpdateRoundScheduleRequest,
  UpdateDecisionRequest,
  UpdateHandoffItemRequest,
  UpdateMemberRequest,
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
export type MemberFormRequest = CreateMemberRequest & UpdateMemberRequest
export type RoleResourceFormRequest = CreateRoleResourceRequest & UpdateRoleResourceRequest
export type RoutineFormRequest = CreateRoutineRequest & UpdateRoutineRequest
export type RoundScheduleFormRequest = UpdateRoundScheduleRequest
export type SeasonRoundFormRequest = CreateSeasonRoundRequest & UpdateSeasonRoundRequest
export type DecisionFormRequest = CreateDecisionRequest & UpdateDecisionRequest
export type HandoffItemFormRequest = CreateHandoffItemRequest & UpdateHandoffItemRequest

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

const duplicateMemberNameMessage = '이미 등록된 구성원 이름입니다. 같은 이름이면 구분할 별칭을 붙여 주세요.'

function memberCreationError(error: unknown) {
  if (error instanceof ApiError && error.code === 'MEMBER_NAME_CONFLICT') {
    return duplicateMemberNameMessage
  }
  return contentCreationError(error)
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

function FormError({
  error,
  formatError = mutationError,
}: {
  error: unknown
  formatError?: (error: unknown) => string
}) {
  return error ? <p className="form-error" role="alert">{formatError(error)}</p> : null
}

function CreationFormFeedback({
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
    decision?.authorMemberId ?? members.find(isActiveMember)?.id ?? '',
  )
  const authorOptions = memberSelectionOptions(members, decision?.authorMemberId)
  const existingAuthor = decision
    ? members.find((member) => member.id === decision.authorMemberId)
    : undefined
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
            {authorOptions.map((member) => (
              <option
                key={member.id}
                value={member.id}
                disabled={!isActiveMember(member)}
              >
                {isActiveMember(member)
                  ? member.name
                  : `${member.name} (활동 종료 · 기존 작성자)`}
              </option>
            ))}
          </select>
          {existingAuthor && !isActiveMember(existingAuthor) && (
            <small>활동을 종료한 기존 작성자는 유지할 수 있지만 새로 선택할 수는 없어요.</small>
          )}
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

export function MemberManagementModal({
  members,
  pendingMemberId,
  error,
  changesDisabled,
  onAdd,
  onEdit,
  onToggleDeactivation,
  onClose,
}: {
  members: Member[]
  pendingMemberId: string | null
  error: unknown
  changesDisabled?: boolean
  onAdd: () => void
  onEdit: (member: Member) => void
  onToggleDeactivation: (member: Member) => void
  onClose: () => void
}) {
  const activeMembers = members.filter(isActiveMember)
  const orderedMembers = [...members].sort((left, right) => {
    const activityOrder = Number(!isActiveMember(left)) - Number(!isActiveMember(right))
    return activityOrder !== 0 ? activityOrder : left.name.localeCompare(right.name, 'ko')
  })
  const actionsDisabled = Boolean(pendingMemberId) || changesDisabled

  const memberRow = (member: Member) => {
    const active = isActiveMember(member)
    const pending = pendingMemberId === member.id
    return (
      <li className="member-management-row" key={member.id} aria-busy={pending || undefined}>
        <span className="avatar" style={{ background: member.tone }}>{member.initials}</span>
        <span className="member-management-identity">
          <strong>{member.name}</strong>
          <small>{active ? '활동 중' : '활동 종료'}</small>
        </span>
        <span className="member-management-actions">
          <button
            type="button"
            onClick={() => onEdit(member)}
            disabled={actionsDisabled}
            aria-label={`${member.name} 이름 수정`}
          >
            이름 수정
          </button>
          <button
            type="button"
            className={active ? 'member-deactivate-button' : undefined}
            onClick={() => {
              if (!actionsDisabled) onToggleDeactivation(member)
            }}
            aria-disabled={actionsDisabled || undefined}
            aria-label={`${member.name} ${active ? '활동 종료' : '다시 활성화'}`}
          >
            {pending ? '처리 중…' : active ? '활동 종료' : '다시 활성화'}
          </button>
        </span>
      </li>
    )
  }

  return (
    <ModalShell
      title="구성원 관리"
      description="표시 이름과 활동 여부를 관리합니다. 활동을 종료해도 기존 역할과 결정 기록의 이름은 남습니다."
      closeDisabled={Boolean(pendingMemberId)}
      onClose={onClose}
    >
      <div className="member-management">
        <div className="member-management-heading">
          <span>활동 중 {activeMembers.length}명 · 전체 {members.length}명</span>
          <button
            type="button"
            className="primary-button"
            onClick={onAdd}
            disabled={actionsDisabled}
          >
            <Icon name="plus" size={14} /> 구성원 추가
          </button>
        </div>
        {orderedMembers.length > 0
          ? (
              <ul className="member-management-list" aria-label="팀 구성원">
                {orderedMembers.map(memberRow)}
              </ul>
            )
          : <p className="member-management-empty">등록된 구성원이 없어요. 새 구성원을 추가해 주세요.</p>}
        <p className="member-management-note">
          활동을 종료한 구성원은 새 담당자와 새 결정 작성자 선택에서 제외됩니다.
        </p>
        <FormError error={error} />
      </div>
    </ModalShell>
  )
}

export function MemberModal({
  members,
  member,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onCancel,
  onSave,
}: CreationModalStatus & {
  members: Member[]
  member?: Member
  onClose: () => void
  onCancel?: () => void
  onSave: (request: MemberFormRequest) => SaveResult
}) {
  const editing = Boolean(member)
  const submission = useSubmissionLock(pending)
  const [name, setName] = useState(member?.name ?? '')
  const [validationMessage, setValidationMessage] = useState('')
  const validationId = useId()

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current) return

    const normalizedName = name.trim()
    if (!normalizedName) {
      setValidationMessage('구성원 이름을 입력해 주세요.')
      return
    }
    if ((!recoveryAvailable || editing)
      && members.some((candidate) =>
        candidate.id !== member?.id && candidate.name.trim() === normalizedName)) {
      setValidationMessage(duplicateMemberNameMessage)
      return
    }

    setValidationMessage('')
    submission.start(onSave({ name: normalizedName }))
  }

  return (
    <ModalShell
      title={editing ? '구성원 이름 수정' : '구성원 추가'}
      description={editing
        ? '기존 역할과 결정 기록에서도 이 표시 이름을 사용합니다.'
        : '역할을 맡거나 결정 작성자로 선택할 사람을 현재 팀에 추가해 주세요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>구성원 이름</span>
          <input
            autoFocus
            required
            maxLength={100}
            value={name}
            aria-invalid={Boolean(validationMessage) || undefined}
            aria-describedby={validationMessage ? validationId : undefined}
            onChange={(event) => {
              setName(event.target.value)
              if (validationMessage) setValidationMessage('')
            }}
            placeholder="예: 이서준 또는 이서준(백엔드)"
          />
          <small>같은 이름의 사람이 있다면 구분할 별칭을 함께 적어 주세요.</small>
        </label>
        {validationMessage && (
          <p id={validationId} className="form-error" role="alert">{validationMessage}</p>
        )}
        {editing
          ? <FormError error={error} formatError={memberCreationError} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
                formatError={memberCreationError}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '구성원 추가하기'}
          pendingLabel={editing ? '이름 저장하는 중…' : '구성원 추가하는 중…'}
          onClose={onCancel ?? onClose}
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
  const currentMemberOptions = memberSelectionOptions(members, role?.currentMemberId)
  const nextMemberOptions = memberSelectionOptions(members, role?.nextMemberId)
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
              {currentMemberOptions.map((member) => (
                <option
                  key={member.id}
                  value={member.id}
                  disabled={!isActiveMember(member)}
                >
                  {isActiveMember(member)
                    ? member.name
                    : `${member.name} (활동 종료 · 기존 선택)`}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>다음 담당자</span>
            <select value={nextMemberId} onChange={(event) => setNextMemberId(event.target.value)}>
              <option value="">다음 담당자 미정</option>
              {nextMemberOptions.map((member) => (
                <option
                  key={member.id}
                  value={member.id}
                  disabled={!isActiveMember(member)}
                >
                  {isActiveMember(member)
                    ? member.name
                    : `${member.name} (활동 종료 · 기존 선택)`}
                </option>
              ))}
            </select>
          </label>
        </div>
        {role && (currentMemberOptions.some((member) => !isActiveMember(member))
          || nextMemberOptions.some((member) => !isActiveMember(member))) && (
          <small className="form-hint">
            활동을 종료한 기존 담당자는 유지할 수 있지만 다른 역할에 새로 배정할 수는 없어요.
          </small>
        )}
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
  const [deadlineDayOffset, setDeadlineDayOffset] = useState(
    routine?.deadlineDayOffset == null ? '-1' : String(routine.deadlineDayOffset),
  )
  const [deadlineTime, setDeadlineTime] = useState(
    routine?.deadlineTime?.slice(0, 5) ?? '22:00',
  )
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
      ...(deadlineDayOffset === ''
        ? {}
        : {
            deadlineDayOffset: Number(deadlineDayOffset),
            deadlineTime,
          }),
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
        <div className="form-grid">
          <label>
            <span>실제 마감일</span>
            <select
              value={deadlineDayOffset}
              onChange={(event) => setDeadlineDayOffset(event.target.value)}
            >
              <option value="">자동 판정 안 함</option>
              <option value="-7">모임 7일 전</option>
              <option value="-3">모임 3일 전</option>
              <option value="-2">모임 2일 전</option>
              <option value="-1">모임 하루 전</option>
              <option value="0">모임 당일</option>
              <option value="1">모임 다음 날</option>
              <option value="2">모임 2일 후</option>
              <option value="3">모임 3일 후</option>
              <option value="7">모임 7일 후</option>
            </select>
          </label>
          <label>
            <span>실제 마감 시각</span>
            <input
              type="time"
              required={deadlineDayOffset !== ''}
              disabled={deadlineDayOffset === ''}
              value={deadlineTime}
              onChange={(event) => setDeadlineTime(event.target.value)}
            />
          </label>
        </div>
        <p className="form-hint">
          실제 마감은 시즌 시간대로 계산하며, 기한 문구는 팀이 읽기 쉬운 설명으로 함께 남습니다.
        </p>
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

export function RoundScheduleModal({
  season,
  pending,
  error,
  onClose,
  onSave,
}: {
  season: Season
  pending: boolean
  error: unknown
  onClose: () => void
  onSave: (request: RoundScheduleFormRequest) => SaveResult
}) {
  const schedule = season.roundSchedule
  const submission = useSubmissionLock(pending)
  const [timeZone, setTimeZone] = useState(season.timeZone)
  const [firstMeetingDate, setFirstMeetingDate] = useState(
    schedule?.firstMeetingDate
      ?? clampToSeason(pilotCalendarDate(new Date(), season.timeZone), season),
  )
  const [meetingTime, setMeetingTime] = useState(
    schedule?.meetingTime.slice(0, 5) ?? '19:00',
  )
  const [recurrence, setRecurrence] = useState<'WEEKLY' | 'BIWEEKLY'>(
    schedule?.recurrence ?? 'WEEKLY',
  )
  const [generationLeadDays, setGenerationLeadDays] = useState(
    String(schedule?.generationLeadDays ?? 7),
  )
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current) return
    submission.start(onSave({
      timeZone: timeZone.trim(),
      firstMeetingDate,
      meetingTime,
      recurrence,
      generationLeadDays: Number(generationLeadDays),
      enabled,
    }))
  }

  return (
    <ModalShell
      title="자동 회차 설정"
      description="시즌 시간대를 기준으로 가까운 주간·격주 회차만 미리 만들어요."
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>시즌 시간대</span>
          <input
            autoFocus
            required
            maxLength={64}
            autoCapitalize="none"
            spellCheck={false}
            value={timeZone}
            onChange={(event) => setTimeZone(event.target.value)}
            placeholder="Asia/Seoul"
          />
        </label>
        <div className="form-grid">
          <label>
            <span>첫 자동 회차</span>
            <input
              type="date"
              required
              min={season.startDate}
              max={season.endDate}
              value={firstMeetingDate}
              onChange={(event) => setFirstMeetingDate(event.target.value)}
            />
          </label>
          <label>
            <span>모임 시각</span>
            <input
              type="time"
              required
              value={meetingTime}
              onChange={(event) => setMeetingTime(event.target.value)}
            />
          </label>
        </div>
        <div className="form-grid">
          <label>
            <span>반복 주기</span>
            <select
              value={recurrence}
              onChange={(event) =>
                setRecurrence(event.target.value as 'WEEKLY' | 'BIWEEKLY')}
            >
              <option value="WEEKLY">매주</option>
              <option value="BIWEEKLY">격주</option>
            </select>
          </label>
          <label>
            <span>미리 만들 기간</span>
            <select
              value={generationLeadDays}
              onChange={(event) => setGenerationLeadDays(event.target.value)}
            >
              <option value="0">당일</option>
              <option value="3">3일 전</option>
              <option value="7">7일 전</option>
              <option value="14">14일 전</option>
              <option value="30">30일 전</option>
            </select>
          </label>
        </div>
        <label className="check-field">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(event) => setEnabled(event.target.checked)}
          />
          <span>자동 회차 생성 사용</span>
        </label>
        <p className="form-hint">
          일시중지해도 이미 생성된 회차와 완료 기록은 남습니다. 자동 생성은 실제 마감이 설정된 루틴만 사용합니다.
        </p>
        <FormError error={error} />
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel="자동 회차 저장"
          pendingLabel="자동 회차 저장하는 중…"
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
    round
      ? round.meetingDate ?? ''
      : clampToSeason(pilotCalendarDate(new Date(), season.timeZone), season),
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
  onOpenResource,
  busyResourceIds,
  resourceOpenErrors,
  onClose,
}: {
  role: Role
  members: Member[]
  routines: Routine[]
  decisions: Decision[]
  resources: RoleResource[]
  items: HandoffItem[]
  progress: number
  onOpenResource: (resource: RoleResource) => void
  busyResourceIds: ReadonlySet<string>
  resourceOpenErrors: Readonly<Record<string, string>>
  onClose: () => void
}) {
  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedDecisions = decisions.filter((decision) => decision.roleIds.includes(role.id))
  const remainingItems = items.filter((item) => !item.completed)
  return (
    <ModalShell
      title={`${role.name} 바통북`}
      description={`${owner ? memberDisplayName(owner) : '이전 담당자'}에서 ${next ? memberDisplayName(next) : '다음 담당자'}에게 이어질 역할 기록입니다.`}
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
                      <button
                        type="button"
                        className="book-resource-navigation-button"
                        disabled={busyResourceIds.has(resource.id)}
                        aria-label={`${resource.title} ${busyResourceIds.has(resource.id) ? '여는 중' : '열기'}`}
                        aria-describedby={resourceOpenErrors[resource.id]
                          ? `handoff-resource-open-error-${resource.id}`
                          : undefined}
                        onClick={() => onOpenResource(resource)}
                      >
                        {busyResourceIds.has(resource.id) ? '여는 중…' : resource.title}
                      </button>
                      {resource.description && <small>{resource.description}</small>}
                      {resourceOpenErrors[resource.id] && (
                        <small
                          id={`handoff-resource-open-error-${resource.id}`}
                          className="resource-open-error"
                          role="alert"
                        >
                          {resourceOpenErrors[resource.id]}
                        </small>
                      )}
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
