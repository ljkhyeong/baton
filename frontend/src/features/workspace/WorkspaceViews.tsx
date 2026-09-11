import type { ReactNode } from 'react'
import { Icon } from '@/shared/ui/Icon'
import {
  formatLocalDate,
  getMember,
  memberDisplayName,
} from './workspacePresentation'
import type {
  Member,
  Role,
  Routine,
  RoutineExecution,
  RoutineTimingStatus,
  SeasonRound,
} from './types'

export const routineTimingStatusCopy = {
  UNSCHEDULED: '마감 미설정',
  PLANNED: '예정',
  IN_PROGRESS: '진행',
  OVERDUE: '지연',
  COMPLETED: '완료',
} satisfies Record<RoutineTimingStatus, string>

export const roundTimingStatusCopy = {
  PLANNED: '예정',
  IN_PROGRESS: '진행',
  OVERDUE: '지연',
  COMPLETED: '완료',
} as const

export function roundOriginLabel(round: SeasonRound) {
  return round.origin === 'AUTOMATIC' ? '자동 생성' : '수동 생성'
}

export function formatDateRange(startDate?: string | null, endDate?: string | null) {
  if (!startDate && !endDate) return '담당 기간 미정'
  return `${formatLocalDate(startDate)} — ${formatLocalDate(endDate)}`
}

export function formatInstant(value: string, timeZone?: string) {
  const date = new Date(value)
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    ...(timeZone ? { timeZone } : {}),
  }).format(date)
}

export function formatLocalTime(value: string) {
  return value.slice(0, 5)
}

type RoutineTimelineItem = {
  id: string
  routine?: Routine
  execution?: RoutineExecution
}

export function routineTimelineItems(routines: Routine[], selectedRound?: SeasonRound) {
  const routinesById = new Map(routines.map((routine) => [routine.id, routine]))
  const executionIds = new Set<string>()
  const items: RoutineTimelineItem[] = []

  selectedRound?.routineExecutions.forEach((execution) => {
    executionIds.add(execution.routineId)
    items.push({
      id: execution.routineId,
      routine: routinesById.get(execution.routineId),
      execution,
    })
  })
  routines.forEach((routine) => {
    if (!executionIds.has(routine.id)) items.push({ id: routine.id, routine })
  })
  return items
}

export function PageHeader({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description: string; action?: ReactNode }) {
  return (
    <header className="page-header">
      <div>{eyebrow && <span className="eyebrow">{eyebrow}</span>}<h1>{title}</h1><p>{description}</p></div>
      {action && <div className="page-action">{action}</div>}
    </header>
  )
}
export function PrimaryButton({ children, onClick, icon = true, disabled = false }: { children: ReactNode; onClick: () => void; icon?: boolean; disabled?: boolean }) {
  return <button type="button" className="primary-button" onClick={onClick} disabled={disabled}>{icon && <Icon name="plus" size={16} />}{children}</button>
}

export function ActionableEmpty({ title, description, actionLabel, onAction, disabled = false }: { title: string; description: string; actionLabel: string; onAction: () => void; disabled?: boolean }) {
  return (
    <div className="empty-state actionable-empty">
      <Icon name="memory" size={24} /><strong>{title}</strong><p>{description}</p>
      <button type="button" className="secondary-button" disabled={disabled} onClick={onAction}>{actionLabel}</button>
    </div>
  )
}

export function RoundControl({
  compact = false,
  rounds,
  selectedRound,
  archivedRoundCount = 0,
  hasRoutines,
  onSelect,
  onCreate,
  onEdit,
  onArchive,
  selectedRoundBusy = false,
  changesDisabled = false,
}: {
  compact?: boolean
  rounds: SeasonRound[]
  selectedRound?: SeasonRound
  archivedRoundCount?: number
  hasRoutines: boolean
  onSelect: (roundId: string) => void
  onCreate: () => void
  onEdit?: (round: SeasonRound) => void
  onArchive?: (round: SeasonRound) => void
  selectedRoundBusy?: boolean
  changesDisabled?: boolean
}) {
  return (
    <section className="round-control" aria-label="회차 전환 도구">
      <label>
        <span>회차</span>
        <select
          aria-label="회차"
          value={selectedRound?.id ?? ''}
          disabled={!rounds.length}
          onChange={(event) => onSelect(event.target.value)}
        >
          {!rounds.length && (
            <option value="">
              {archivedRoundCount ? '진행할 회차가 없습니다' : '아직 만든 회차가 없습니다'}
            </option>
          )}
          {rounds.map((round) => (
            <option key={round.id} value={round.id}>
              {round.name}{!compact && ` · ${roundOriginLabel(round)} · ${roundTimingStatusCopy[round.timingStatus]}`}
              {' · '}{formatLocalDate(round.meetingDate)}
            </option>
          ))}
        </select>
      </label>
      {!compact && <div className="round-control-actions" role="group" aria-label="회차 관리">
        {onEdit && (
          <button
            type="button"
            className="secondary-button"
            disabled={!selectedRound || selectedRoundBusy}
            onClick={() => selectedRound && onEdit(selectedRound)}
          >
            회차 수정
          </button>
        )}
        {onArchive && (
          <button
            type="button"
            className="secondary-button"
            disabled={!selectedRound || selectedRoundBusy}
            aria-label={selectedRound
              ? `${selectedRound.name} 회차 ${selectedRound.origin === 'AUTOMATIC' ? '건너뛰기' : '보관'}`
              : '선택한 회차 보관'}
            onClick={() => selectedRound && onArchive(selectedRound)}
          >
            {selectedRound?.origin === 'AUTOMATIC' ? '이번 회차 건너뛰기' : '보관'}
          </button>
        )}
        <button
          type="button"
          className="secondary-button"
          disabled={!hasRoutines || changesDisabled}
          aria-describedby={!hasRoutines ? 'round-create-hint' : undefined}
          onClick={onCreate}
        >
          <Icon name="plus" size={15} /> 회차 만들기
        </button>
      </div>}
      {!hasRoutines && !compact && (
        <p id="round-create-hint">반복 업무를 하나 이상 만든 뒤 회차를 만들 수 있어요.</p>
      )}
    </section>
  )
}

export function RoutineRow({ routine, execution, role, members, timeZone, onToggle, onSelectRole, onEdit, onArchive, pending, operationPending = false, archivePending = false }: { routine?: Routine; execution?: RoutineExecution; role?: Role; members: Member[]; timeZone: string; onToggle: (execution: RoutineExecution) => void; onSelectRole: (id: string) => void; onEdit: (routine: Routine) => void; onArchive?: (routine: Routine, archived: boolean) => void; pending: boolean; operationPending?: boolean; archivePending?: boolean }) {
  const displayRoutine = execution ?? routine
  if (!displayRoutine) return null
  const member = getMember(members, role?.currentMemberId)
  const routineId = execution?.routineId ?? routine?.id ?? ''
  return (
    <div
      className={`routine-row ${execution?.timingStatus.toLowerCase() ?? 'future'}`}
      data-routine-id={routineId}
      data-execution-id={execution?.id}
    >
      {execution ? (
        <button type="button" className="check-button" disabled={pending} onClick={() => onToggle(execution)} aria-label={`${displayRoutine.title} ${operationPending ? '완료 상태 변경 중' : execution.status === 'DONE' ? '완료 취소' : '완료 처리'}`} aria-busy={operationPending || undefined}>
          {execution.status === 'DONE' && <Icon name="check" size={14} />}
        </button>
      ) : <span className="check-button check-button-unavailable" aria-hidden="true" />}
      <button type="button" className="routine-copy" onClick={() => role && onSelectRole(role.id)}>
        <span className="routine-text"><strong>{displayRoutine.title}</strong><small>{displayRoutine.detail}</small></span>
        <span className="routine-schedule">
          {execution ? <>
            <span className={`timing-label ${execution.timingStatus.toLowerCase()}`}>{routineTimingStatusCopy[execution.timingStatus]}</span>
            {execution.deadlineAt && <time dateTime={execution.deadlineAt}>{formatInstant(execution.deadlineAt, timeZone)}</time>}
          </> : <small className="routine-round-note">다음 회차부터</small>}
          <small className="routine-due-label">{displayRoutine.dueLabel}</small>
        </span>
      </button>
      <button type="button" className="routine-owner" onClick={() => role && onSelectRole(role.id)}>{member && <span className="avatar" style={{ background: member.tone }}>{member.initials}</span>}<span><strong>{role?.name ?? '연결된 역할 없음'}</strong><small>{member ? memberDisplayName(member) : '담당자 미정'}</small></span></button>
      {routine ? (
        <span className="routine-actions">
          <button type="button" className="inline-edit-button" aria-label={`${routine.title} 반복 업무 수정`} disabled={pending || archivePending} onClick={() => onEdit(routine)}>수정</button>
          {onArchive && (
            <button
              type="button"
              className="inline-edit-button routine-archive-button"
              aria-label={`${routine.title} 반복 업무 보관`}
              aria-busy={archivePending}
              disabled={pending || archivePending}
              onClick={() => onArchive(routine, true)}
            >
              {archivePending ? '보관 중' : '보관'}
            </button>
          )}
        </span>
      ) : (
        <span className="routine-definition-note">반복 업무 보관됨</span>
      )}
    </div>
  )
}
