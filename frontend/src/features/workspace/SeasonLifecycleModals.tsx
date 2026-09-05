import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { addCalendarDays } from '@/shared/lib/calendarDate'
import { Icon } from '@/shared/ui/Icon'
import { FormError, ModalShell } from './WorkspaceModalPrimitives'
import { formatLocalDate } from './workspacePresentation'
import type {
  CreateNextSeasonRequest,
  Role,
  Routine,
  SeasonSummary,
  UpdateSeasonRequest,
} from './types'

type SeasonStatus = 'active' | 'upcoming' | 'date-passed' | 'ended'

const seasonStatusCopy = {
  active: '운영 중',
  upcoming: '시작 전',
  'date-passed': '종료일 지남',
  ended: '종료됨',
} satisfies Record<SeasonStatus, string>

function seasonStatus(season: SeasonSummary, calendarDate: string): SeasonStatus {
  if (season.endedAt) return 'ended'
  if (season.startDate > calendarDate) return 'upcoming'
  if (season.endDate < calendarDate) return 'date-passed'
  return 'active'
}

function formatEndedAt(endedAt: string) {
  const parsed = new Date(endedAt)
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  }).format(parsed)
}

export function SeasonSwitcherModal({
  teamName,
  currentSeason,
  seasons,
  calendarDate,
  endingPending,
  endingError,
  onClose,
  onSelect,
  onEdit,
  onToggleEnding,
  onCreateNext,
}: {
  teamName: string
  currentSeason: SeasonSummary
  seasons: SeasonSummary[]
  calendarDate: string
  endingPending: boolean
  endingError: unknown
  onClose: () => void
  onSelect: (seasonId: string) => void
  onEdit: () => void
  onToggleEnding: () => void
  onCreateNext: () => void
}) {
  const orderedSeasons = useMemo(
    () => [...seasons].sort((left, right) =>
      right.startDate.localeCompare(left.startDate)
      || right.id.localeCompare(left.id)),
    [seasons],
  )
  const hasSuccessor = seasons.some((season) =>
    season.previousSeasonId === currentSeason.id)

  return (
    <ModalShell
      className="season-modal"
      kicker="시즌"
      title={`${teamName} 시즌`}
      description="과거 기록은 그대로 읽고, 운영할 시즌을 선택하거나 다음 시즌을 준비하세요."
      closeDisabled={endingPending}
      onClose={onClose}
    >
      <div className="season-list" role="group" aria-label="팀 시즌">
        {orderedSeasons.map((season) => {
          const status = seasonStatus(season, calendarDate)
          const current = season.id === currentSeason.id
          return (
            <button
              key={season.id}
              type="button"
              className={`season-list-item ${current ? 'current' : ''}`}
              aria-current={current ? 'page' : undefined}
              disabled={current || endingPending}
              onClick={() => onSelect(season.id)}
            >
              <span>
                <strong>{season.name}</strong>
                <small>{formatLocalDate(season.startDate)} — {formatLocalDate(season.endDate)}</small>
              </span>
              <span className={`season-status season-status-${status}`}>
                {current ? '현재 · ' : ''}{seasonStatusCopy[status]}
              </span>
            </button>
          )
        })}
      </div>

      <section className="season-current-actions" aria-label="현재 시즌 관리">
        <div>
          <strong>{currentSeason.name}</strong>
          <small>
            {currentSeason.endedAt
              ? `${formatEndedAt(currentSeason.endedAt)}에 종료`
              : '현재 기록을 보존한 채 시즌 상태를 관리합니다.'}
          </small>
        </div>
        <div className="season-action-grid">
          <button
            type="button"
            className="secondary-button"
            disabled={Boolean(currentSeason.endedAt) || endingPending}
            onClick={onEdit}
          >
            시즌 정보 수정
          </button>
          <button
            type="button"
            className={currentSeason.endedAt ? 'secondary-button' : 'danger-button'}
            disabled={endingPending}
            onClick={onToggleEnding}
          >
            {endingPending
              ? '상태 바꾸는 중…'
              : currentSeason.endedAt ? '시즌 다시 열기' : '시즌 종료'}
          </button>
          <button
            type="button"
            className="primary-button"
            disabled={hasSuccessor || endingPending}
            onClick={onCreateNext}
          >
            <Icon name="arrow" size={15} />
            {hasSuccessor ? '다음 시즌이 이미 있어요' : '다음 시즌 시작'}
          </button>
        </div>
        <FormError error={endingError} />
      </section>
    </ModalShell>
  )
}

export function SeasonEditModal({
  season,
  pending,
  error,
  onClose,
  onSave,
}: {
  season: SeasonSummary
  pending: boolean
  error: unknown
  onClose: () => void
  onSave: (request: UpdateSeasonRequest) => void
}) {
  const [name, setName] = useState(season.name)
  const [startDate, setStartDate] = useState(season.startDate)
  const [endDate, setEndDate] = useState(season.endDate)
  const [validationError, setValidationError] = useState('')

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const normalizedName = name.trim()
    if (!normalizedName) {
      setValidationError('시즌 이름을 입력해 주세요.')
      return
    }
    setValidationError('')
    onSave({ name: normalizedName, startDate, endDate })
  }

  return (
    <ModalShell
      className="season-modal"
      kicker="시즌"
      title="시즌 정보 수정"
      description="기존 회차와 담당 기간을 포함할 수 있는 범위 안에서 이름과 기간을 바꿀 수 있습니다."
      closeDisabled={pending}
      onClose={onClose}
    >
      <form onSubmit={submit}>
        <div className="form-grid">
          <label className="full">
            <span>시즌 이름</span>
            <input
              required
              value={name}
              maxLength={100}
              autoFocus
              onChange={(event) => {
                setName(event.target.value)
                setValidationError('')
              }}
            />
          </label>
          <label>
            <span>시작일</span>
            <input
              type="date"
              required
              value={startDate}
              onChange={(event) => setStartDate(event.target.value)}
            />
          </label>
          <label>
            <span>종료일</span>
            <input
              type="date"
              required
              min={startDate}
              value={endDate}
              onChange={(event) => setEndDate(event.target.value)}
            />
          </label>
        </div>
        {validationError && <p className="form-error" role="alert">{validationError}</p>}
        <FormError error={error} />
        <div className="form-actions">
          <button type="button" className="secondary-button" disabled={pending} onClick={onClose}>
            취소
          </button>
          <button type="submit" className="primary-button" disabled={pending}>
            {pending ? '저장하는 중…' : '시즌 정보 저장'}
          </button>
        </div>
      </form>
    </ModalShell>
  )
}

function defaultNextSeasonDates(source: SeasonSummary) {
  const sourceStart = new Date(`${source.startDate}T00:00:00Z`)
  const sourceEnd = new Date(`${source.endDate}T00:00:00Z`)
  const durationDays = Math.round((sourceEnd.getTime() - sourceStart.getTime()) / 86_400_000)
  const startDate = addCalendarDays(source.endDate, 1)
  return { startDate, endDate: addCalendarDays(startDate, durationDays) }
}

export function NextSeasonModal({
  sourceSeason,
  roles,
  routines,
  cleanupRequired,
  pending,
  error,
  storageError,
  onClose,
  onSave,
}: {
  sourceSeason: SeasonSummary
  roles: Role[]
  routines: Routine[]
  cleanupRequired: boolean
  pending: boolean
  error: unknown
  storageError: string
  onClose: () => void
  onSave: (request: CreateNextSeasonRequest) => void
}) {
  const defaultDates = useMemo(() => defaultNextSeasonDates(sourceSeason), [sourceSeason])
  const [name, setName] = useState(`${sourceSeason.name} 다음 시즌`)
  const [startDate, setStartDate] = useState(defaultDates.startDate)
  const [endDate, setEndDate] = useState(defaultDates.endDate)
  const [selectedRoleIds, setSelectedRoleIds] = useState(
    () => new Set(roles.map((role) => role.id)),
  )
  const [selectedRoutineIds, setSelectedRoutineIds] = useState(
    () => new Set(routines.map((routine) => routine.id)),
  )
  const [validationError, setValidationError] = useState('')

  const toggleRole = (roleId: string, selected: boolean) => {
    setSelectedRoleIds((current) => {
      const next = new Set(current)
      if (selected) next.add(roleId)
      else next.delete(roleId)
      return next
    })
    if (!selected) {
      setSelectedRoutineIds((current) => {
        const next = new Set(current)
        routines
          .filter((routine) => routine.ownerRoleId === roleId)
          .forEach((routine) => next.delete(routine.id))
        return next
      })
    }
  }

  const toggleRoutine = (routine: Routine, selected: boolean) => {
    setSelectedRoutineIds((current) => {
      const next = new Set(current)
      if (selected) next.add(routine.id)
      else next.delete(routine.id)
      return next
    })
    if (selected) {
      setSelectedRoleIds((current) => new Set(current).add(routine.ownerRoleId))
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const normalizedName = name.trim()
    const request: CreateNextSeasonRequest = {
      name: normalizedName,
      startDate,
      endDate,
      copyRoleIds: [...selectedRoleIds],
      copyRoutineIds: [...selectedRoutineIds],
    }
    if (cleanupRequired) {
      setValidationError('')
      onSave(request)
      return
    }
    if (!normalizedName) {
      setValidationError('다음 시즌 이름을 입력해 주세요.')
      return
    }
    if (!startDate || !endDate || startDate > endDate) {
      setValidationError('다음 시즌의 시작일과 종료일을 확인해 주세요.')
      return
    }
    const invalidRoutine = routines.find((routine) =>
      selectedRoutineIds.has(routine.id) && !selectedRoleIds.has(routine.ownerRoleId))
    if (invalidRoutine) {
      setValidationError(`${invalidRoutine.title} 반복 업무의 담당 역할도 함께 선택해 주세요.`)
      return
    }
    setValidationError('')
    onSave(request)
  }

  return (
    <ModalShell
      className="season-modal"
      kicker="시즌"
      title="다음 시즌 시작"
      description="가져올 역할과 반복 업무만 고르고, 과거 실행과 결정은 현재 시즌에 그대로 보존합니다."
      closeDisabled={pending}
      onClose={onClose}
    >
      <form onSubmit={submit}>
        <div className="season-copy-boundary">
          <Icon name="spark" size={17} />
          <p>
            역할의 목적·책임과 선택한 반복 업무 정의를 복사합니다.
            담당자·담당 기간은 비워 두며, 회차 실행·결정·인수인계 기록은 복사하지 않습니다.
          </p>
        </div>
        <div className="form-grid">
          <label className="full">
            <span>다음 시즌 이름</span>
            <input
              value={name}
              maxLength={100}
              autoFocus
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <label>
            <span>시작일</span>
            <input
              type="date"
              value={startDate}
              onChange={(event) => setStartDate(event.target.value)}
            />
          </label>
          <label>
            <span>종료일</span>
            <input
              type="date"
              value={endDate}
              onChange={(event) => setEndDate(event.target.value)}
            />
          </label>
        </div>

        <fieldset className="season-copy-options">
          <legend>가져올 역할</legend>
          {roles.length ? roles.map((role) => (
            <label key={role.id}>
              <input
                type="checkbox"
                checked={selectedRoleIds.has(role.id)}
                onChange={(event) => toggleRole(role.id, event.target.checked)}
              />
              <span><strong>{role.name}</strong><small>{role.purpose}</small></span>
            </label>
          )) : <p>가져올 역할이 없습니다.</p>}
        </fieldset>

        <fieldset className="season-copy-options">
          <legend>가져올 반복 업무</legend>
          {routines.length ? routines.map((routine) => {
            const owner = roles.find((role) => role.id === routine.ownerRoleId)
            return (
              <label key={routine.id}>
                <input
                  type="checkbox"
                  checked={selectedRoutineIds.has(routine.id)}
                  onChange={(event) => toggleRoutine(routine, event.target.checked)}
                />
                <span>
                  <strong>{routine.title}</strong>
                  <small>{owner?.name ?? '연결 역할 없음'} · {routine.dueLabel}</small>
                </span>
              </label>
            )
          }) : <p>가져올 반복 업무가 없습니다.</p>}
        </fieldset>

        {validationError && <p className="form-error" role="alert">{validationError}</p>}
        <FormError error={error} />
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <div className="form-actions">
          <button type="button" className="secondary-button" disabled={pending} onClick={onClose}>
            취소
          </button>
          <button type="submit" className="primary-button" disabled={pending}>
            {pending
              ? cleanupRequired ? '임시 요청 기록 삭제 중…' : '다음 시즌 만드는 중…'
              : cleanupRequired ? '임시 요청 기록 삭제 재시도' : '현재 시즌을 닫고 시작'}
          </button>
        </div>
      </form>
    </ModalShell>
  )
}

export function SeasonSuccessorCleanupBanner({
  pending,
  onRetry,
}: {
  pending: boolean
  onRetry: () => void
}) {
  return (
    <section
      className="season-ended-banner"
      role="alert"
      aria-label="시즌 시작 임시 요청 기록 삭제"
    >
      <div>
        <Icon name="alert" size={18} />
        <span>
          <strong>이전 시즌 시작은 완료됐습니다.</strong>
          <small>임시 기록은 브라우저에 남은 시즌 시작 요청 정보입니다. 정리한 뒤 다음 시즌을 시작할 수 있습니다.</small>
        </span>
      </div>
      <div>
        <button
          type="button"
          className="secondary-button"
          disabled={pending}
          onClick={onRetry}
        >
          {pending ? '임시 요청 기록 삭제 중…' : '임시 요청 기록 삭제 재시도'}
        </button>
      </div>
    </section>
  )
}

export function SeasonEndedBanner({
  season,
  onSwitchSeason,
  onCreateNext,
}: {
  season: SeasonSummary
  onSwitchSeason: () => void
  onCreateNext: () => void
}) {
  if (!season.endedAt) return null
  return (
    <section className="season-ended-banner" aria-label="종료된 시즌 안내">
      <div>
        <Icon name="check" size={18} />
        <span>
          <strong>이 시즌은 읽기 전용입니다.</strong>
          <small>{formatEndedAt(season.endedAt)}에 종료되어 기록을 바꿀 수 없습니다.</small>
        </span>
      </div>
      <div>
        <button type="button" className="secondary-button" onClick={onSwitchSeason}>
          다른 시즌 보기
        </button>
        <button type="button" className="primary-button" onClick={onCreateNext}>
          다음 시즌 시작
        </button>
      </div>
    </section>
  )
}
