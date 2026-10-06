import { useRef, type ReactNode } from 'react'
import { formatInstant } from '@/shared/lib/dateTimeFormat'
import { Icon } from '@/shared/ui/Icon'
import { nearestRelayRole, RelayCard } from './WorkspaceRelayCard'
import {
  ActionableEmpty,
  PageHeader,
  RoundControl,
  RoutineRow,
  routineTimelineItems,
} from './WorkspaceViews'
import type {
  ContinuitySignal,
  RoleHandoff,
  Routine,
  RoutineExecution,
  RoutineTimingStatus,
  SeasonRound,
  ViewKey,
  WorkspaceProjection,
} from './types'

const continuitySeverityCopy = {
  CRITICAL: '긴급',
  WARNING: '주의',
} satisfies Record<ContinuitySignal['severity'], string>

export function TodayView({
  workspace,
  personalWork,
  weeklyBrief,
  calendarLabel,
  calendarDate,
  roleHandoffs,
  handoffProgress,
  rounds,
  archivedRoundCount,
  selectedRound,
  pendingCount,
  completedCount,
  onSelectRound,
  onAddRound,
  onSelectRole,
  onToggleRoutine,
  onNavigate,
  onOpenRoleHandoff,
  onOpenContinuitySignal,
  onAddRole,
  onAddRoutine,
  onEditRoutine,
  selectedRoundBusy,
  selectedRoundOperationPending,
  changesDisabled = false,
}: {
  workspace: WorkspaceProjection
  personalWork: ReactNode
  weeklyBrief: ReactNode
  calendarLabel: string
  calendarDate: string
  roleHandoffs: RoleHandoff[]
  handoffProgress: (roleId: string) => number
  rounds: SeasonRound[]
  archivedRoundCount: number
  selectedRound?: SeasonRound
  pendingCount: number
  completedCount: number
  onSelectRound: (roundId: string) => void
  onAddRound: () => void
  onSelectRole: (id: string) => void
  onToggleRoutine: (execution: RoutineExecution) => void
  onNavigate: (key: ViewKey) => void
  onOpenRoleHandoff: (roleId: string) => void
  onOpenContinuitySignal: (signal: ContinuitySignal) => void
  onAddRole: () => void
  onAddRoutine: () => void
  onEditRoutine: (routine: Routine) => void
  selectedRoundBusy: boolean
  selectedRoundOperationPending: boolean
  changesDisabled?: boolean
}) {
  const { roles, routines, members, season } = workspace
  const timingPriority: Record<RoutineTimingStatus, number> = {
    OVERDUE: 0,
    IN_PROGRESS: 1,
    PLANNED: 2,
    UNSCHEDULED: 3,
    COMPLETED: 4,
  }
  const orderedRoutines = routineTimelineItems(routines, selectedRound).sort((left, right) => {
    return timingPriority[left.execution?.timingStatus ?? 'UNSCHEDULED']
      - timingPriority[right.execution?.timingStatus ?? 'UNSCHEDULED']
  })
  const executionCount = selectedRound?.routineExecutions.length ?? 0
  const nextDeadline = orderedRoutines
    .flatMap(({ execution }) => execution?.deadlineAt && execution.status !== 'DONE' ? [execution.deadlineAt] : [])
    .sort((left, right) => Date.parse(left) - Date.parse(right))[0]
  const signalCount = workspace.continuitySignals.length
  const relay = nearestRelayRole(roles, calendarDate)
  const roundComplete = executionCount > 0 && completedCount === executionCount
  const signalSectionRef = useRef<HTMLElement>(null)
  const openSignals = () => {
    signalSectionRef.current?.scrollIntoView({ block: 'start' })
    signalSectionRef.current?.focus({ preventScroll: true })
  }
  return (
    <>
      <PageHeader
        eyebrow={calendarLabel}
        title={`남은 업무 ${pendingCount}개`}
        action={(
          <button type="button" className="secondary-button" onClick={roles.length ? onAddRoutine : onAddRole} disabled={changesDisabled}>
            <Icon name="plus" size={15} />{roles.length ? '업무 추가' : '역할 추가'}
          </button>
        )}
      />
      <section className="relay-board" aria-labelledby="relay-title">
        <div className="today-list-toolbar">
          <RoundControl
            compact
            rounds={rounds}
            selectedRound={selectedRound}
            archivedRoundCount={archivedRoundCount}
            hasRoutines={Boolean(routines.length)}
            onSelect={onSelectRound}
            onCreate={onAddRound}
            changesDisabled={changesDisabled}
          />
          <h2 id="relay-title" className="visually-hidden">이번 회차 업무</h2>
          <div className="round-meta">
            <strong>{roundComplete ? '모두 완료' : `${completedCount}/${executionCount} 완료`}</strong>
            {nextDeadline && <span>다음 마감 {formatInstant(nextDeadline, season.timeZone)}</span>}
            {signalCount > 0 && (
              <button type="button" className="summary-jump" aria-label={`조치할 항목 ${signalCount}건 보기`} onClick={openSignals}>
                조치할 항목 {signalCount}건
              </button>
            )}
          </div>
        </div>
        {!orderedRoutines.length ? (
          <ActionableEmpty icon="rhythm" title="아직 반복 업무가 없습니다" description="담당 역할을 정하고 반복할 업무를 등록하세요." actionLabel={roles.length ? '첫 반복 업무 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} disabled={changesDisabled} />
        ) : selectedRound ? (
          <section className="today-round-checklist" aria-label={`${selectedRound.name} 반복 업무 완료하기`}>
            <progress
              className="round-completion-track"
              aria-label="이번 회차 업무 완료율"
              value={completedCount}
              max={Math.max(1, selectedRound.routineExecutions.length)}
            />
            <div className="today-list-columns" aria-hidden="true"><span>업무</span><span>상태 · 마감</span><span>담당자</span></div>
            <div className="today-task-list" role="list">
              {orderedRoutines.map(({ id, routine, execution }) => {
                const ownerRoleId = execution?.ownerRoleId ?? routine?.ownerRoleId
                return (
                  <div key={id} role="listitem">
                    <RoutineRow
                      routine={routine}
                      execution={execution}
                      role={roles.find((item) => item.id === ownerRoleId)}
                      members={members}
                      timeZone={season.timeZone}
                      onToggle={onToggleRoutine}
                      onSelectRole={onSelectRole}
                      onEdit={onEditRoutine}
                      pending={selectedRoundBusy}
                      operationPending={selectedRoundOperationPending}
                    />
                  </div>
                )
              })}
            </div>
          </section>
        ) : (
          <ActionableEmpty icon="rhythm"
            title={archivedRoundCount
              ? '모든 회차가 보관되어 있습니다.'
              : '아직 만든 회차가 없습니다'}
            description={archivedRoundCount
              ? '일정 화면의 보관함에서 회차를 복원하거나 새 회차를 만들어 주세요.'
              : '회차를 만들면 등록한 반복 업무가 추가됩니다.'}
            actionLabel={archivedRoundCount ? '일정에서 회차 관리하기' : '첫 회차 만들기'}
            onAction={archivedRoundCount ? () => onNavigate('rhythm') : onAddRound}
            disabled={!archivedRoundCount && changesDisabled}
          />
        )}
      </section>
      {relay && (
        <RelayCard
          role={relay.role}
          days={relay.days}
          members={members}
          roleHandoffs={roleHandoffs}
          progress={handoffProgress(relay.role.id)}
          onOpenHandoff={onOpenRoleHandoff}
        />
      )}
      {workspace.continuitySignals.length > 0 && (
        <div className="today-lower">
          <section className="plain-section" aria-labelledby="continuity-radar-title" ref={signalSectionRef} tabIndex={-1}>
            <div className="section-heading compact">
              <h2 id="continuity-radar-title">조치할 항목</h2>
              <span className="continuity-count">
                {workspace.continuitySignals.length}개
              </span>
            </div>
            <div className="signal-list" role="list">
              {workspace.continuitySignals.map((signal) => (
                <div
                  key={`${signal.type}:${signal.roleId}:${signal.routineId ?? ''}`}
                  role="listitem"
                >
                  <button
                    type="button"
                    className="signal-row"
                    onClick={() => onOpenContinuitySignal(signal)}
                  >
                    <span className={`signal-symbol ${signal.severity.toLowerCase()}`}>
                      <Icon name="alert" size={15} />
                    </span>
                    <span className="signal-copy">
                      <span className="signal-heading">
                        <strong>{signal.title}</strong>
                        <span className={`signal-severity ${signal.severity.toLowerCase()}`}>
                          {continuitySeverityCopy[signal.severity]}
                        </span>
                      </span>
                      <small>{signal.reason}</small>
                      <span className="signal-action">{signal.recommendedAction}</span>
                    </span>
                    <Icon name="chevron" size={16} />
                  </button>
                </div>
              ))}
            </div>
          </section>
        </div>
      )}
      <div className="today-more">
        <details className="today-secondary">
          <summary><strong>내 업무와 확인할 자료</strong><span>내가 맡은 업무와 재확인할 자료</span></summary>
          <div className="today-support">{personalWork}</div>
        </details>
        {weeklyBrief}
      </div>
    </>
  )
}
