import { useState } from 'react'
import type { FormEvent } from 'react'
import { Icon } from '@/shared/ui/Icon'
import {
  CreationFormFeedback,
  FormActions,
  FormError,
  ModalShell,
  useSubmissionLock,
} from './WorkspaceModalPrimitives'
import type { SaveResult } from './WorkspaceModalPrimitives'
import { clampToSeason } from './seasonCalendar'
import {
  getMember,
  isActiveMember,
  memberDisplayName,
} from './workspacePresentation'
import type { RoleHandoffModalMode } from './useWorkspaceRoleHandoffFlow'
import type {
  CancelRoleHandoffRequest,
  ConfirmRoleHandoffRequest,
  Decision,
  HandoffItem,
  Member,
  PrepareRoleHandoffRequest,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  Season,
  TransferRoleHandoffRequest,
} from './types'

function nextCalendarDate(value: string) {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + 1)
  return date.toISOString().slice(0, 10)
}

function roleHandoffIdentityCopy(member: Member | undefined, action: string) {
  const memberName = member ? memberDisplayName(member) : '지정된 구성원'
  return `공유 링크는 사람을 인증하지 않습니다. 이 작업은 ${memberName} 명의로 ${action}했다고 기록됩니다.`
}

export function RoleHandoffModal({
  mode,
  role,
  handoff,
  members,
  season,
  items,
  resources,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onPrepare,
  onTransfer,
  onAccept,
  onCancel,
}: {
  mode: RoleHandoffModalMode
  role: Role
  handoff?: RoleHandoff
  members: Member[]
  season: Season
  items: HandoffItem[]
  resources: RoleResource[]
  pending: boolean
  error: unknown
  storageError: string
  recoveryAvailable: boolean
  onClose: () => void
  onPrepare: (request: PrepareRoleHandoffRequest) => SaveResult
  onTransfer: (request: TransferRoleHandoffRequest) => SaveResult
  onAccept: (request: ConfirmRoleHandoffRequest) => SaveResult
  onCancel: (request: CancelRoleHandoffRequest) => SaveResult
}) {
  const submission = useSubmissionLock(pending)
  const eligibleMembers = members.filter((member) =>
    isActiveMember(member) && member.id !== role.currentMemberId)
  const retainedNextMember = eligibleMembers.find((member) =>
    member.id === (handoff?.toMemberId ?? role.nextMemberId))
  const [toMemberId, setToMemberId] = useState(
    retainedNextMember?.id ?? eligibleMembers[0]?.id ?? '',
  )
  const suggestedStartDate = clampToSeason(
    role.assignmentEndDate
      ? nextCalendarDate(role.assignmentEndDate)
      : role.assignmentStartDate ?? season.startDate,
    season,
  )
  const [incomingStartDate, setIncomingStartDate] = useState(suggestedStartDate)
  const [incomingEndDate, setIncomingEndDate] = useState(season.endDate)
  const [warningAcknowledged, setWarningAcknowledged] = useState(false)
  const [validationMessage, setValidationMessage] = useState('')
  const activeItems = items.filter((item) => item.roleId === role.id && !item.archivedAt)
  const activeResources = resources.filter((resource) => resource.roleId === role.id)
  const incompleteItemCount = activeItems.filter((item) => !item.completed).length
  const warnings = [
    activeItems.length === 0 ? '활성 바통 항목이 없습니다.' : '',
    incompleteItemCount > 0 ? `미완료 바통 항목이 ${incompleteItemCount}개 있습니다.` : '',
    activeResources.length === 0 ? '연결한 참고 자료가 없습니다.' : '',
  ].filter(Boolean)
  const fromMember = getMember(members, handoff?.fromMemberId ?? role.currentMemberId)
  const toMember = getMember(members, handoff?.toMemberId ?? toMemberId)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current) return

    if (mode === 'prepare') {
      if (!toMemberId) {
        setValidationMessage('다음 담당자로 지정할 활동 중 구성원이 필요합니다.')
        return
      }
      if (incomingEndDate && incomingEndDate < incomingStartDate) {
        setValidationMessage('다음 담당 종료일은 시작일보다 빠를 수 없습니다.')
        return
      }
      setValidationMessage('')
      submission.start(onPrepare({
        toMemberId,
        incomingAssignmentStartDate: incomingStartDate,
        incomingAssignmentEndDate: incomingEndDate || null,
      }))
      return
    }

    if (!handoff) return
    if (mode === 'transfer') {
      if (warnings.length > 0 && !warningAcknowledged) return
      submission.start(onTransfer({
        confirmedByMemberId: handoff.fromMemberId,
        warningAcknowledged: warnings.length > 0 && warningAcknowledged,
      }))
      return
    }

    if (mode === 'accept') {
      submission.start(onAccept({ confirmedByMemberId: handoff.toMemberId }))
      return
    }
    submission.start(onCancel({ confirmedByMemberId: handoff.fromMemberId }))
  }

  const modalCopy = {
    prepare: {
      title: '역할 바통 준비 시작',
      description: '다음 담당자와 수락 뒤 적용할 담당 기간을 먼저 확정합니다.',
      submit: '바통 준비 시작',
      pending: '바통 준비하는 중…',
    },
    transfer: {
      title: '바통 전달 전 확인',
      description: '현재 바통북의 준비도를 확인하고 다음 담당자에게 전달합니다.',
      submit: '바통 전달하기',
      pending: '바통 전달하는 중…',
    },
    accept: {
      title: '역할 바통 수락',
      description: '수락하면 역할의 현재 담당자와 담당 기간이 다음 담당자 정보로 바뀝니다.',
      submit: `${toMember?.name ?? '다음 담당자'}님 명의로 수락 기록`,
      pending: '바통 수락하는 중…',
    },
    cancel: {
      title: '역할 바통 취소',
      description: '수락 전 바통을 취소하고 역할과 바통북을 다시 편집할 수 있게 합니다.',
      submit: '바통 전달 취소',
      pending: '바통 취소하는 중…',
    },
  }[mode]

  return (
    <ModalShell
      title={modalCopy.title}
      description={modalCopy.description}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form role-handoff-form" onSubmit={submit}>
        {mode === 'prepare' ? (
          <>
            <label>
              <span>다음 담당자</span>
              <select
                required
                autoFocus
                value={toMemberId}
                onChange={(event) => {
                  setToMemberId(event.target.value)
                  setValidationMessage('')
                }}
              >
                {eligibleMembers.map((member) => (
                  <option key={member.id} value={member.id}>{memberDisplayName(member)}</option>
                ))}
              </select>
            </label>
            <div className="date-grid">
              <label>
                <span>다음 담당 시작일</span>
                <input
                  type="date"
                  required
                  min={season.startDate}
                  max={season.endDate}
                  value={incomingStartDate}
                  onChange={(event) => {
                    setIncomingStartDate(event.target.value)
                    setValidationMessage('')
                  }}
                />
              </label>
              <label>
                <span>다음 담당 종료일</span>
                <input
                  type="date"
                  min={incomingStartDate || season.startDate}
                  max={season.endDate}
                  value={incomingEndDate}
                  onChange={(event) => {
                    setIncomingEndDate(event.target.value)
                    setValidationMessage('')
                  }}
                />
              </label>
            </div>
            <p className="handoff-identity-note">
              준비를 시작해도 현재 담당자는 바뀌지 않습니다. 전달 뒤 다음 담당자가 수락할 때 역할 배정이 갱신됩니다.
            </p>
          </>
        ) : (
          <>
            <div className="handoff-party-summary">
              <span><small>이전 담당자</small><strong>{fromMember ? memberDisplayName(fromMember) : '확인 필요'}</strong></span>
              <Icon name="arrow" size={18} />
              <span><small>다음 담당자</small><strong>{toMember ? memberDisplayName(toMember) : '확인 필요'}</strong></span>
            </div>
            {mode === 'transfer' && (
              <>
                <dl className="handoff-snapshot-grid" aria-label="전달 전 바통북 준비도">
                  <div><dt>활성 항목</dt><dd>{activeItems.length}</dd></div>
                  <div><dt>미완료</dt><dd>{incompleteItemCount}</dd></div>
                  <div><dt>참고 자료</dt><dd>{activeResources.length}</dd></div>
                </dl>
                {warnings.length > 0 && (
                  <div className="handoff-warning-box">
                    <strong>준비도 경고</strong>
                    <ul>{warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
                    <label>
                      <input
                        type="checkbox"
                        checked={warningAcknowledged}
                        onChange={(event) => setWarningAcknowledged(event.target.checked)}
                      />
                      <span>준비도 경고를 확인했습니다</span>
                    </label>
                  </div>
                )}
              </>
            )}
            <p className="handoff-identity-note">
              {roleHandoffIdentityCopy(
                mode === 'accept' ? toMember : fromMember,
                mode === 'accept' ? '수락' : mode === 'transfer' ? '전달' : '취소',
              )}
            </p>
          </>
        )}
        {validationMessage && <p className="form-error" role="alert">{validationMessage}</p>}
        {mode === 'prepare'
          ? (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )
          : <FormError error={error} />}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitDisabled={mode === 'transfer'
            && warnings.length > 0
            && !warningAcknowledged}
          submitLabel={modalCopy.submit}
          pendingLabel={modalCopy.pending}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
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
