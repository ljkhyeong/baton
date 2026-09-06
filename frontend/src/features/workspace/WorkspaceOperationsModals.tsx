import { useState } from 'react'
import type { FormEvent } from 'react'
import { clampToSeason, pilotCalendarDate } from './seasonCalendar'
import {
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
  formatLocalDate,
  phaseCopy,
} from './workspacePresentation'
import type {
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  Role,
  Routine,
  RoutinePhase,
  Season,
  SeasonRound,
  UpdateRoutineRequest,
  UpdateRoundScheduleRequest,
  UpdateSeasonRoundRequest,
} from './types'

export type RoutineFormRequest = CreateRoutineRequest & UpdateRoutineRequest
type RoundScheduleFormRequest = UpdateRoundScheduleRequest
export type SeasonRoundFormRequest = CreateSeasonRoundRequest & UpdateSeasonRoundRequest

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
      title={editing ? '반복 업무 수정' : '반복 업무 만들기'}
      description={editing
        ? '업무 시점, 담당 역할, 마감 안내를 수정하세요.'
        : '반복할 업무와 담당 역할, 마감을 정합니다.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <label>
          <span>반복 업무 이름</span>
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
            <span>업무 시점</span>
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
          <span>마감 안내</span>
          <input
            required
            value={dueLabel}
            onChange={(event) => setDueLabel(event.target.value)}
            placeholder="예: 수요일 18:00"
          />
        </label>
        <div className="form-grid">
          <label>
            <span>마감 기준일</span>
            <select
              value={deadlineDayOffset}
              onChange={(event) => setDeadlineDayOffset(event.target.value)}
            >
              <option value="">마감 설정 안 함</option>
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
            <span>마감 시각</span>
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
          ‘마감 안내’는 설명용입니다. 지연 여부는 마감 기준일과 시각을 시즌 시간대로 계산합니다.
        </p>
        <label>
          <span>세부 설명</span>
          <textarea
            required
            value={detail}
            onChange={(event) => setDetail(event.target.value)}
            placeholder="완료 기준이나 다음 담당자가 알아야 할 내용을 적어주세요"
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
          submitLabel={editing ? '변경 저장' : '반복 업무 만들기'}
          pendingLabel={editing ? '반복 업무 저장하는 중…' : '반복 업무 만드는 중…'}
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
      description="정한 주기에 맞춰 회차를 자동으로 만듭니다."
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
          일시 중지해도 기존 회차와 완료 기록은 남습니다. 자동 생성을 켜려면 사용 중인 모든 반복 업무에 마감을 설정하세요.
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
        ? '이름과 모임 날짜를 수정합니다. 날짜를 바꾸면 기존 마감 규칙에 따라 업무 마감도 바뀌며, 완료 표시는 유지됩니다.'
        : '등록된 반복 업무로 이번 회차의 체크리스트를 만듭니다. 이후 반복 업무를 수정해도 이미 만든 회차는 바뀌지 않습니다.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        {round?.origin === 'AUTOMATIC' && (
          <p className="form-hint">
            처음 예정된 날짜는 {formatLocalDate(round.scheduledOccurrenceDate)}입니다.
            이 회차만 날짜를 바꾸며 모임 시각과 다른 회차의 반복 일정은 유지됩니다.
            이번 모임을 쉬려면 일정 화면에서 ‘이번 회차 건너뛰기’를 선택하세요.
          </p>
        )}
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
