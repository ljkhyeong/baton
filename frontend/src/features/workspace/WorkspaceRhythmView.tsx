import { Icon } from '@/shared/ui/Icon'
import { PublicHolidayPanel } from '@/features/calendar/PublicHolidayPanel'
import { clampToSeason, pilotCalendarDate } from './seasonCalendar'
import {
  ActionableEmpty,
  formatInstant,
  formatLocalTime,
  PageHeader,
  PrimaryButton,
  RoundControl,
  RoutineRow,
  routineTimelineItems,
} from './WorkspaceViews'
import { formatLocalDate } from './workspacePresentation'
import { phaseCopy } from './workspacePresentation'
import type {
  Member,
  Role,
  Routine,
  RoutineExecution,
  RoutinePhase,
  Season,
  SeasonRound,
} from './types'

export function RhythmView({
  season,
  roles,
  routines,
  archivedRoutines,
  rounds,
  archivedRounds,
  selectedRound,
  members,
  onSelectRound,
  onAddRound,
  onEditRound,
  onUpdateRoundArchive,
  onSelectRole,
  onToggleRoutine,
  onAddRoutine,
  onAddRole,
  onEditRoutine,
  onUpdateRoutineArchive,
  onConfigureRoundSchedule,
  busyRoundIds,
  busyRoutineIds,
  changesDisabled = false,
}: {
  season: Season
  roles: Role[]
  routines: Routine[]
  archivedRoutines: Routine[]
  rounds: SeasonRound[]
  archivedRounds: SeasonRound[]
  selectedRound?: SeasonRound
  members: Member[]
  onSelectRound: (roundId: string) => void
  onAddRound: () => void
  onEditRound: (round: SeasonRound) => void
  onUpdateRoundArchive: (round: SeasonRound, archived: boolean) => void
  onSelectRole: (id: string) => void
  onToggleRoutine: (execution: RoutineExecution) => void
  onAddRoutine: () => void
  onAddRole: () => void
  onEditRoutine: (routine: Routine) => void
  onUpdateRoutineArchive: (routine: Routine, archived: boolean) => void
  onConfigureRoundSchedule: () => void
  busyRoundIds: ReadonlySet<string>
  busyRoutineIds: ReadonlySet<string>
  changesDisabled?: boolean
}) {
  const phases: RoutinePhase[] = ['BEFORE', 'DURING', 'AFTER']
  const timelineItems = routineTimelineItems(routines, selectedRound)
  const roundScheduleWaitingForRoutine = Boolean(season.roundSchedule?.enabled)
    && routines.length === 0
  return (
    <>
      <PageHeader eyebrow="회차별 진행 관리" title="반복 업무" description="반복 업무를 등록하고 회차별 완료 여부를 확인합니다." action={<PrimaryButton onClick={onAddRoutine} disabled={changesDisabled}>반복 업무 추가</PrimaryButton>} />
      <section
        className={`round-schedule-card ${season.roundSchedule?.enabled ? 'active' : ''}`}
        aria-labelledby="round-schedule-title"
      >
        <div>
          <span className="section-kicker">자동 회차</span>
          <h2 id="round-schedule-title">
            {season.roundSchedule
              ? `${season.roundSchedule.recurrence === 'WEEKLY' ? '매주' : '격주'} ${formatLocalTime(season.roundSchedule.meetingTime)}`
              : '자동 회차가 꺼져 있어요'}
          </h2>
          <p>
            {season.roundSchedule
              ? `${season.timeZone} · ${roundScheduleWaitingForRoutine
                ? '회차에 추가할 업무 없음'
                : season.roundSchedule.enabled ? '자동 생성 켜짐' : '일시중지'}`
              : `${season.timeZone} 기준 반복 일정을 설정해 보세요.`}
            {season.roundSchedule?.nextOccurrenceDate
              ? ` · 다음 예정일 ${formatLocalDate(season.roundSchedule.nextOccurrenceDate)}`
              : ''}
          </p>
        </div>
        <button
          type="button"
          className="secondary-button"
          disabled={changesDisabled}
          onClick={onConfigureRoundSchedule}
        >
          {season.roundSchedule ? '자동 회차 설정' : '설정하기'}
        </button>
      </section>
      <RoundControl
        rounds={rounds}
        selectedRound={selectedRound}
        archivedRoundCount={archivedRounds.length}
        hasRoutines={Boolean(routines.length)}
        onSelect={onSelectRound}
        onCreate={onAddRound}
        onEdit={onEditRound}
        onArchive={(round) => onUpdateRoundArchive(round, true)}
        selectedRoundBusy={changesDisabled || Boolean(selectedRound && busyRoundIds.has(selectedRound.id))}
        changesDisabled={changesDisabled}
      />
      {season.timeZone === 'Asia/Seoul' && <PublicHolidayPanel
        key={selectedRound?.id ?? season.id}
        meetingDate={selectedRound?.meetingDate ?? season.roundSchedule?.nextOccurrenceDate}
        date={selectedRound?.meetingDate ?? season.roundSchedule?.nextOccurrenceDate
          ?? clampToSeason(pilotCalendarDate(), season)}
      />}
      {archivedRounds.length > 0 && (
        <details className="archive-shelf round-archive-shelf">
          <summary>보관한 회차 {archivedRounds.length}개</summary>
          <div className="archive-list">
            {archivedRounds.map((round) => {
              const completed = round.routineExecutions.filter(
                (execution) => execution.status === 'DONE',
              ).length
              return (
                <div className="archive-row" key={round.id}>
                  <span>
                    <strong>{round.name}</strong>
                    <small>
                      {round.meetingDate ? formatLocalDate(round.meetingDate) : '날짜 미정'}
                      {' · '}
                      {completed}/{round.routineExecutions.length} 완료
                      {round.archivedAt ? ` · ${formatInstant(round.archivedAt)} 보관` : ''}
                    </small>
                  </span>
                  <button
                    type="button"
                    aria-label={`${round.name} 회차 복원`}
                    disabled={changesDisabled || busyRoundIds.has(round.id)}
                    onClick={() => onUpdateRoundArchive(round, false)}
                  >
                    복원
                  </button>
                </div>
              )
            })}
          </div>
        </details>
      )}
      {timelineItems.length ? (
        <div className="rhythm-timeline">
          {phases.map((phase, phaseIndex) => (
            <section className="rhythm-phase" key={phase}>
              <div className="phase-marker"><span>{String(phaseIndex + 1).padStart(2, '0')}</span><h2>{phaseCopy[phase]}</h2></div>
              <div className="phase-content">
                {timelineItems.filter(({ routine, execution }) => {
                  return (execution?.phase ?? routine?.phase) === phase
                }).map(({ id, routine, execution }) => {
                  const ownerRoleId = execution?.ownerRoleId ?? routine?.ownerRoleId
                  return (
                    <RoutineRow
                      key={id}
                      routine={routine}
                      execution={execution}
                      role={roles.find((role) => role.id === ownerRoleId)}
                      members={members}
                      timeZone={season.timeZone}
                      onToggle={onToggleRoutine}
                      onSelectRole={onSelectRole}
                      onEdit={onEditRoutine}
                      onArchive={onUpdateRoutineArchive}
                      pending={changesDisabled || Boolean(selectedRound && busyRoundIds.has(selectedRound.id))}
                      operationPending={Boolean(selectedRound && busyRoundIds.has(selectedRound.id))}
                      archivePending={Boolean(routine && busyRoutineIds.has(routine.id))}
                    />
                  )
                })}
              </div>
            </section>
          ))}
        </div>
      ) : <ActionableEmpty title={archivedRoutines.length ? '사용 중인 반복 업무가 없어요' : '아직 반복 업무가 없어요'} description={archivedRoutines.length ? '보관함에서 다시 필요한 반복 업무를 복원하거나 새 반복 업무를 추가해 주세요.' : '모임마다 반복할 업무와 담당 역할을 정하세요.'} actionLabel={roles.length ? '새 반복 업무 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} disabled={changesDisabled} />}
      {timelineItems.length > 0 && <button type="button" className="add-routine-line" disabled={changesDisabled} onClick={onAddRoutine}><Icon name="plus" size={15} /> 반복할 일 추가하기</button>}
      {archivedRoutines.length > 0 && (
        <details className="archive-shelf routine-archive-shelf">
          <summary>보관한 반복 업무 {archivedRoutines.length}개</summary>
          <div className="archive-list">
            {archivedRoutines.map((routine) => {
              const busy = busyRoutineIds.has(routine.id)
              const owner = roles.find((role) => role.id === routine.ownerRoleId)
              return (
                <div className="archive-row" key={routine.id} data-archived-routine-id={routine.id}>
                  <span>
                    <strong>{routine.title}</strong>
                    <small>
                      {owner?.name ?? '연결된 역할 없음'} · {routine.dueLabel}
                      {routine.archivedAt ? ` · ${formatInstant(routine.archivedAt)} 보관` : ''}
                    </small>
                  </span>
                  <button
                    type="button"
                    aria-label={`${routine.title} 반복 업무 복원`}
                    aria-busy={busy}
                    disabled={changesDisabled || busy}
                    onClick={() => onUpdateRoutineArchive(routine, false)}
                  >
                    {busy ? '복원 중' : '복원'}
                  </button>
                </div>
              )
            })}
          </div>
        </details>
      )}
    </>
  )
}
