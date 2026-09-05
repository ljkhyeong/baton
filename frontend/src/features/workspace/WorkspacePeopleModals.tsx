import { useId, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import {
  contentCreationError,
  CreationFormFeedback,
  FormActions,
  FormError,
  ModalShell,
  useSubmissionLock,
} from './WorkspaceModalPrimitives'
import type {
  CreationModalStatus,
  SaveResult,
} from './WorkspaceModalPrimitives'
import {
  isActiveMember,
  memberSelectionOptions,
} from './workspacePresentation'
import type {
  CreateMemberRequest,
  CreateRoleRequest,
  Member,
  Role,
  Season,
  UpdateMemberRequest,
  UpdateRoleRequest,
} from './types'

export type RoleFormRequest = CreateRoleRequest & UpdateRoleRequest
export type MemberFormRequest = CreateMemberRequest & UpdateMemberRequest

const duplicateMemberNameMessage = '이미 등록된 구성원 이름입니다. 같은 이름이면 구분할 별칭을 붙여 주세요.'

function memberCreationError(error: unknown) {
  if (error instanceof ApiError && error.code === 'MEMBER_NAME_CONFLICT') {
    return duplicateMemberNameMessage
  }
  return contentCreationError(error)
}
export function MemberManagementModal({
  members,
  accountMembershipPanel,
  pendingMemberId,
  error,
  changesDisabled,
  onAdd,
  onEdit,
  onToggleDeactivation,
  onClose,
}: {
  members: Member[]
  accountMembershipPanel?: ReactNode
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
        {accountMembershipPanel}
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
  assignmentLocked = false,
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
  assignmentLocked?: boolean
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
  const currentMemberOptions = memberSelectionOptions(members, role?.currentMemberId)
  const nextMemberOptions = memberSelectionOptions(members, role?.nextMemberId)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current) return
    const effectiveCurrentMemberId = assignmentLocked && role
      ? role.currentMemberId ?? ''
      : currentMemberId
    const effectiveNextMemberId = assignmentLocked && role
      ? role.nextMemberId ?? ''
      : nextMemberId
    const effectiveAssignmentStartDate = assignmentLocked && role
      ? role.assignmentStartDate ?? ''
      : assignmentStartDate
    const effectiveAssignmentEndDate = assignmentLocked && role
      ? role.assignmentEndDate ?? ''
      : assignmentEndDate
    submission.start(onSave({
      name: name.trim(),
      purpose: purpose.trim(),
      currentMemberId: effectiveCurrentMemberId || null,
      nextMemberId: effectiveNextMemberId || null,
      assignmentStartDate: effectiveAssignmentStartDate || null,
      assignmentEndDate: effectiveAssignmentEndDate || null,
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
            placeholder="예: 질문 담당"
          />
        </label>
        <label>
          <span>역할 목적</span>
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
              disabled={assignmentLocked}
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
            <select
              value={nextMemberId}
              disabled={assignmentLocked}
              onChange={(event) => setNextMemberId(event.target.value)}
            >
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
              disabled={assignmentLocked}
              onChange={(event) => setAssignmentStartDate(event.target.value)}
            />
          </label>
          <label>
            <span>담당 종료일</span>
            <input
              type="date"
              min={assignmentStartDate || undefined}
              value={assignmentEndDate}
              disabled={assignmentLocked}
              onChange={(event) => setAssignmentEndDate(event.target.value)}
            />
          </label>
        </div>
        {assignmentLocked && (
          <small className="form-hint">
            인수인계 준비 중에는 담당자와 담당 기간이 전달 기록에 고정됩니다.
            역할 설명과 책임은 계속 보완할 수 있어요.
          </small>
        )}
        <label>
          <span>담당 업무</span>
          <textarea
            value={responsibilities}
            onChange={(event) => setResponsibilities(event.target.value)}
            placeholder={'질문 수집\n자주 막히는 문제 정리'}
            rows={3}
          />
          <small>줄바꿈 또는 쉼표로 구분해 주세요.</small>
        </label>
        <label>
          <span>주의사항</span>
          <textarea
            value={risk}
            onChange={(event) => setRisk(event.target.value)}
            placeholder="예: 자료가 개인 계정에만 저장되어 있어요"
            rows={2}
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
          submitLabel={editing ? '변경 저장' : '역할 만들기'}
          pendingLabel={editing ? '역할 저장하는 중…' : '역할 만드는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

function splitList(value: string) {
  return [...new Set(value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean))]
}
