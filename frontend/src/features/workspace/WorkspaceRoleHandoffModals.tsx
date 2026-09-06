import { DecisionText } from './records/DecisionText'
import { useState } from 'react'
import { useWorkspacePrint } from './useWorkspacePrint'
import type { FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { addCalendarDays } from '@/shared/lib/calendarDate'
import { Icon } from '@/shared/ui/Icon'
import { handoffCategoryLabel } from './records/recordSearch'
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

function roleHandoffIdentityCopy(member: Member | undefined, action: string, accountAccessEnabled: boolean) {
  const memberName = member ? memberDisplayName(member) : '지정된 구성원'
  if (accountAccessEnabled) return `${memberName}님의 계정으로 로그인해야 ${action}할 수 있습니다.`
  return `공유 링크로 접속하면 본인 확인 없이 ${memberName}님이 ${action}한 것으로 기록됩니다.`
}

export function RoleHandoffModal({
  accountAccessEnabled = false,
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
  accountAccessEnabled?: boolean
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
      ? addCalendarDays(role.assignmentEndDate, 1)
      : role.assignmentStartDate ?? season.startDate,
    season,
  )
  const [incomingStartDate, setIncomingStartDate] = useState(suggestedStartDate)
  const [incomingEndDate, setIncomingEndDate] = useState(season.endDate)
  const [warningAcknowledged, setWarningAcknowledged] = useState(false)
  const activeItems = items.filter((item) => item.roleId === role.id && !item.archivedAt)
  const activeResources = resources.filter((resource) => resource.roleId === role.id)
  const incompleteItemCount = activeItems.filter((item) => !item.completed).length
  const warnings = [
    activeItems.length === 0 ? '체크리스트에 항목이 없습니다.' : '',
    incompleteItemCount > 0 ? `미완료 인수인계 항목이 ${incompleteItemCount}개 있습니다.` : '',
    activeResources.length === 0 ? '연결한 참고 자료가 없습니다.' : '',
  ].filter(Boolean)
  const fromMember = getMember(members, handoff?.fromMemberId ?? role.currentMemberId)
  const toMember = getMember(members, handoff?.toMemberId ?? toMemberId)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current) return

    if (mode === 'prepare') {
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
      title: '역할 인수인계 준비 시작',
      description: '누가 언제부터 이 역할을 맡을지 정하세요.',
      submit: '인수인계 준비 시작',
      pending: '인수인계 준비하는 중…',
    },
    transfer: {
      title: '인수인계 전달 전 확인',
      description: '미완료 항목과 자료를 확인한 뒤 다음 담당자에게 전달합니다.',
      submit: '인수인계 전달하기',
      pending: '인수인계 전달하는 중…',
    },
    accept: {
      title: '역할 인수인계 수락',
      description: '수락하면 다음 담당자로 교체되고 아래 담당 기간이 적용됩니다.',
      submit: `${toMember?.name ?? '다음 담당자'}님으로 인수인계 수락`,
      pending: '인수인계 수락하는 중…',
    },
    cancel: {
      title: '역할 인수인계 취소',
      description: '인수인계를 취소하면 역할과 인수인계 문서를 다시 수정할 수 있습니다.',
      submit: '인수인계 취소',
      pending: '인수인계 취소하는 중…',
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
                onChange={(event) => setToMemberId(event.target.value)}
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
                  onChange={(event) => setIncomingStartDate(event.target.value)}
                />
              </label>
              <label>
                <span>다음 담당 종료일</span>
                <input
                  type="date"
                  min={incomingStartDate || season.startDate}
                  max={season.endDate}
                  value={incomingEndDate}
                  onChange={(event) => setIncomingEndDate(event.target.value)}
                />
              </label>
            </div>
            <p className="handoff-identity-note">
              현재 담당자는 그대로입니다. 다음 담당자가 내용을 확인하고 수락하면 담당자가 바뀝니다.
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
                <dl className="handoff-snapshot-grid" aria-label="전달 전 체크리스트와 자료 현황">
                  <div><dt>체크리스트 항목</dt><dd>{activeItems.length}</dd></div>
                  <div><dt>미완료</dt><dd>{incompleteItemCount}</dd></div>
                  <div><dt>참고 자료</dt><dd>{activeResources.length}</dd></div>
                </dl>
                {warnings.length > 0 && (
                  <div className="handoff-warning-box">
                    <strong>전달 전 확인사항</strong>
                    <ul>{warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
                    <label>
                      <input
                        type="checkbox"
                        checked={warningAcknowledged}
                        onChange={(event) => setWarningAcknowledged(event.target.checked)}
                      />
                      <span>미완료 항목과 자료 누락을 확인했습니다</span>
                    </label>
                  </div>
                )}
              </>
            )}
            <p className="handoff-identity-note">
              {roleHandoffIdentityCopy(
                mode === 'accept' ? toMember : fromMember,
                mode === 'accept' ? '수락' : mode === 'transfer' ? '전달' : '취소',
                accountAccessEnabled,
              )}
            </p>
          </>
        )}
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
  workspaceLabel,
  members,
  routines,
  decisions,
  resources,
  items,
  progress,
  onClose,
}: {
  role: Role
  workspaceLabel: string
  members: Member[]
  routines: Routine[]
  decisions: Decision[]
  resources: RoleResource[]
  items: HandoffItem[]
  progress: number
  onClose: () => void
}) {
  const printBook = useWorkspacePrint()

  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedDecisions = decisions.filter((decision) => decision.roleIds.includes(role.id))
  const remainingItems = items.filter((item) => !item.completed)
  return createPortal(
    <ModalShell
      className="handoff-book"
      title={`${role.name} 인수인계 문서`}
      description={`${workspaceLabel} · ${owner ? memberDisplayName(owner) : '이전 담당자'} → ${next ? memberDisplayName(next) : '다음 담당자'} · 전달할 업무와 자료입니다.`}
      onClose={onClose}
    >
      <div className="book-preview">
        <div className="book-progress">
          <span>체크리스트 완료율</span>
          <strong>{progress}%</strong>
        </div>
        <section>
          <span>01 · 역할 목적</span>
          <p>{role.purpose}</p>
          <h3>담당 업무</h3>
          <ul>{role.responsibilities.map((responsibility, index) => (
            <li key={index}>{responsibility}</li>
          ))}</ul>
          <h3>주의할 점</h3>
          <p>{role.risk || '등록된 주의사항이 없습니다.'}</p>
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
            : <p>연결된 반복 업무가 아직 없습니다.</p>}
        </section>
        <section>
          <span>03 · 중요한 결정</span>
          {relatedDecisions.length
            ? relatedDecisions.map((decision) => (
                <blockquote key={decision.id}>
                  “{decision.title}”
                  <DecisionText text={decision.reason} format={decision.textFormat} />
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
          <span>05 · 인수인계 기록</span>
          <p>{remainingItems.length ? `미완료 항목 ${remainingItems.length}개` : '미완료 항목이 없습니다.'}</p>
          {items.length
            ? (
                <ul>
                  {items.map((item) => (
                    <li key={item.id}>
                      <span>{item.label}</span>
                      <small>{handoffCategoryLabel[item.category]} · {item.completed ? '완료' : '미완료'}</small>
                    </li>
                  ))}
                </ul>
              )
            : <p>등록된 인수인계 항목이 없습니다.</p>}
        </section>
        <div className="book-actions">
          <p>현재 화면의 기록을 출력하거나 PDF로 저장합니다. 공유 링크가 인쇄물에 남지 않도록 아래 버튼으로 인쇄해 주세요.</p>
          <button type="button" className="primary-button full-button" onClick={printBook}>
            인쇄 / PDF 저장
          </button>
          <button type="button" className="secondary-button full-button" onClick={onClose}>
            미리보기 닫기
          </button>
        </div>
      </div>
    </ModalShell>,
    document.body,
  )
}
