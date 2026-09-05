import type { ReactNode } from 'react'
import { Icon } from '@/shared/ui/Icon'
import {
  ActionableEmpty,
  PageHeader,
  PrimaryButton,
  RoundControl,
  RoutineRow,
  formatInstant,
  roundOriginLabel,
  roundTimingStatusCopy,
  routineTimelineItems,
  routineTimingStatusCopy,
} from './WorkspaceViews'
import {
  getMember,
  isActiveMember,
  memberDisplayName,
} from './workspacePresentation'
import type {
  ContinuitySignal,
  Routine,
  RoutineExecution,
  RoutineTimingStatus,
  SeasonRound,
  ViewKey,
  WorkspaceProjection,
} from './types'

const continuitySeverityCopy = {
  CRITICAL: '지금 확인',
  WARNING: '미리 확인',
} satisfies Record<ContinuitySignal['severity'], string>

export function TodayView({
  workspace,
  personalWork,
  weeklyBrief,
  calendarLabel,
  rounds,
  archivedRoundCount,
  selectedRound,
  pendingCount,
  completedCount,
  onSelectRound,
  onAddRound,
  onSelectRole,
  onOpenDecision,
  onToggleRoutine,
  onNavigate,
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
  rounds: SeasonRound[]
  archivedRoundCount: number
  selectedRound?: SeasonRound
  pendingCount: number
  completedCount: number
  onSelectRound: (roundId: string) => void
  onAddRound: () => void
  onSelectRole: (id: string) => void
  onOpenDecision: () => void
  onToggleRoutine: (execution: RoutineExecution) => void
  onNavigate: (key: ViewKey) => void
  onOpenContinuitySignal: (signal: ContinuitySignal) => void
  onAddRole: () => void
  onAddRoutine: () => void
  onEditRoutine: (routine: Routine) => void
  selectedRoundBusy: boolean
  selectedRoundOperationPending: boolean
  changesDisabled?: boolean
}) {
  const { roles, routines, decisions, members, season } = workspace
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
  return (
    <>
      <PageHeader
        eyebrow={`${calendarLabel} · ${season.name}`}
        title={`${pendingCount}개의 바통이 남았어요`}
        description="이번 운영에서 멈춘 흐름과 다음 담당자를 확인하세요."
        action={<PrimaryButton onClick={onOpenDecision} disabled={changesDisabled || !roles.length || !members.some(isActiveMember)}>결정 남기기</PrimaryButton>}
      />
      {personalWork}
      {weeklyBrief}
      <RoundControl
        rounds={rounds}
        selectedRound={selectedRound}
        archivedRoundCount={archivedRoundCount}
        hasRoutines={Boolean(routines.length)}
        onSelect={onSelectRound}
        onCreate={onAddRound}
        changesDisabled={changesDisabled}
      />
      <section className="relay-board" aria-labelledby="relay-title">
        <div className="section-heading">
          <div><span className="section-kicker">이번 운영</span><h2 id="relay-title">바통 라인</h2></div>
          <div className="round-meta">
            <strong>{completedCount}/{selectedRound?.routineExecutions.length ?? 0}</strong>
            <span>
              {selectedRound
                ? `${roundOriginLabel(selectedRound)} · ${roundTimingStatusCopy[selectedRound.timingStatus]} · ${selectedRound.name}`
                : `회차 준비 · ${season.name}`}
            </span>
          </div>
        </div>
        {!orderedRoutines.length ? (
          <ActionableEmpty title="아직 운영 루틴이 없어요" description="첫 반복 업무를 역할과 연결해 보세요." actionLabel={roles.length ? '첫 루틴 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} disabled={changesDisabled} />
        ) : selectedRound ? (
          <div className="relay-line" role="list">
            {orderedRoutines.map(({ id, routine, execution }, index) => {
              const displayRoutine = execution ?? routine
              if (!displayRoutine) return null
              const role = roles.find((item) => item.id === displayRoutine.ownerRoleId)
              const member = getMember(members, role?.currentMemberId)
              return (
                <div className="relay-step-item" role="listitem" key={id}>
                  <button type="button" className={`relay-step ${execution?.timingStatus.toLowerCase() ?? 'future'}`} onClick={() => role && onSelectRole(role.id)}>
                    <span className="relay-index">{String(index + 1).padStart(2, '0')}</span><span className="relay-node"><span /></span>
                    <span className="relay-status">{execution ? routineTimingStatusCopy[execution.timingStatus] : '다음 회차부터'}</span><strong>{displayRoutine.title}</strong>
                    <small>{member ? memberDisplayName(member) : '담당자 미정'} · {displayRoutine.dueLabel}</small>
                  </button>
                </div>
              )
            })}
          </div>
        ) : (
          <ActionableEmpty
            title={archivedRoundCount
              ? '현재 운영에 꺼내 둔 회차가 없어요'
              : '아직 운영 회차가 없어요'}
            description={archivedRoundCount
              ? '운영 화면의 보관함에서 회차를 복원하거나 새 회차를 만들어 주세요.'
              : '준비한 루틴을 이번 운영의 실행 목록으로 복사해 보세요.'}
            actionLabel={archivedRoundCount ? '운영에서 회차 관리하기' : '첫 회차 만들기'}
            onAction={archivedRoundCount ? () => onNavigate('rhythm') : onAddRound}
            disabled={!archivedRoundCount && changesDisabled}
          />
        )}
      </section>
      {selectedRound && orderedRoutines.length > 0 && (
        <section className="today-round-checklist plain-section" aria-labelledby="today-round-checklist-title">
          <div className="section-heading compact">
            <div>
              <span className="section-kicker">이번 회차 체크리스트</span>
              <h2 id="today-round-checklist-title">{selectedRound.name} 루틴 완료하기</h2>
            </div>
            <span className="today-round-progress">
              {completedCount}/{selectedRound.routineExecutions.length} 완료
            </span>
          </div>
          {orderedRoutines.map(({ id, routine, execution }) => {
            const ownerRoleId = execution?.ownerRoleId ?? routine?.ownerRoleId
            return (
              <RoutineRow
                key={id}
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
            )
          })}
        </section>
      )}
      <div className="today-lower">
        <section className="plain-section" aria-labelledby="continuity-radar-title">
          <div className="section-heading compact">
            <div>
              <h2 id="continuity-radar-title">확인이 필요한 업무</h2>
            </div>
            <span className="continuity-count">
              {workspace.continuitySignals.length}개
            </span>
          </div>
          {workspace.continuitySignals.length ? (
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
          ) : (
            <p className="quiet-state">
              자동 점검에서 확인된 주의 항목이 없습니다.
            </p>
          )}
        </section>
        <section className="plain-section decision-glimpse">
          <div className="section-heading compact"><div><span className="section-kicker">최근 변경</span><h2>결정 기록</h2></div><button type="button" className="text-button" onClick={() => onNavigate('memory')}>전체 기록 <Icon name="arrow" size={14} /></button></div>
          {decisions[0] ? (
            <button type="button" className="decision-preview" onClick={() => onNavigate('memory')}>
              <time>{formatInstant(decisions[0].createdAt)}</time><blockquote>“{decisions[0].title}”</blockquote><p>{decisions[0].reason}</p><span>{decisions[0].authorName} 기록</span>
            </button>
          ) : <p className="quiet-state">아직 남긴 결정이 없어요.</p>}
        </section>
      </div>
    </>
  )
}
