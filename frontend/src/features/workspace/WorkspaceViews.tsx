import { useLayoutEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { RoundRoomResourceActions } from '@/features/round/RoundRoomResourceActions'
import { Icon } from '@/shared/ui/Icon'
import {
  formatLocalDate,
  getMember,
  isActiveMember,
  memberDisplayName,
} from './workspacePresentation'
import {
  seasonProgress,
} from './seasonCalendar'
import type { WorkspaceConflictRecoveryStatus } from './useWorkspaceConflictRecovery'
import type { WorkspaceScope } from './api'
import type {
  ContinuitySignal,
  Decision,
  Member,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  RoutineExecution,
  RoutineTimingStatus,
  SeasonRound,
  ViewKey,
  WorkspaceProjection,
} from './types'

const navItems: { key: ViewKey; label: string; icon: Parameters<typeof Icon>[0]['name'] }[] = [
  { key: 'today', label: '오늘', icon: 'today' },
  { key: 'roles', label: '역할', icon: 'roles' },
  { key: 'rhythm', label: '운영', icon: 'rhythm' },
  { key: 'memory', label: '기록', icon: 'memory' },
  { key: 'handoff', label: '바통', icon: 'handoff' },
  { key: 'records', label: '탐색', icon: 'search' },
]

const routineTimingStatusCopy = {
  UNSCHEDULED: '자동 판정 없음',
  PLANNED: '예정',
  IN_PROGRESS: '진행',
  OVERDUE: '지연',
  COMPLETED: '완료',
} satisfies Record<RoutineTimingStatus, string>

const roundTimingStatusCopy = {
  PLANNED: '예정',
  IN_PROGRESS: '진행',
  OVERDUE: '지연',
  COMPLETED: '완료',
} as const

const continuitySeverityCopy = {
  CRITICAL: '지금 확인',
  WARNING: '미리 확인',
} satisfies Record<ContinuitySignal['severity'], string>

function roundOriginLabel(round: SeasonRound) {
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

function formatSyncTime(value: number) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
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

export function WorkspaceState({ title, description, busy = false, action }: { title: string; description: string; busy?: boolean; action?: ReactNode }) {
  return (
    <main className="remote-state-page">
      <section className="remote-state" aria-live="polite" aria-busy={busy}>
        <div className="brand remote-state-brand"><span className="brand-mark" />BATON</div>
        {busy && <span className="loading-mark" aria-hidden="true" />}
        <h1>{title}</h1>
        <p>{description}</p>
        {action}
      </section>
    </main>
  )
}

export function Sidebar({ workspace, calendarDate, view, onNavigate, onSwitchSeason, onShare, onManageAccess }: { workspace: WorkspaceProjection; calendarDate: string; view: ViewKey; onNavigate: (key: ViewKey) => void; onSwitchSeason: () => void; onShare: () => void; onManageAccess: () => void }) {
  const progress = seasonProgress(workspace.season, calendarDate)
  const activeMemberCount = workspace.members.filter(isActiveMember).length
  return (
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark" />BATON</div>
      <div className="workspace-label">현재 팀</div>
      <button
        type="button"
        className="workspace-switcher"
        aria-label={`현재 시즌 ${workspace.season.name}. 시즌 전환`}
        onClick={onSwitchSeason}
      >
        <span className="workspace-symbol">{workspace.team.name.slice(0, 1)}</span>
        <span><strong>{workspace.team.name}</strong><small>{workspace.season.name}</small></span>
        <Icon name="chevron" size={15} />
      </button>
      <nav className="side-nav" aria-label="주 메뉴">
        {navItems.map((item) => (
          <button
            type="button"
            className={view === item.key ? 'active' : ''}
            key={item.key}
            aria-current={view === item.key ? 'page' : undefined}
            onClick={() => onNavigate(item.key)}
          >
            <Icon name={item.icon} /><span>{item.label}</span>
            {item.key === 'handoff'
              && (workspace.roleHandoffs.some((handoff) => handoff.status === 'TRANSFERRED')
                || workspace.handoffItems.some((candidate) => !candidate.completed))
              && <span className="nav-dot" aria-label="확인할 바통 있음" />}
          </button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <div className="season-mini">
          <div><span>시즌 진행</span><strong>{progress.elapsedWeeks} / {progress.totalWeeks}주</strong></div>
          <div className="mini-progress"><span style={{ width: `${progress.percent}%` }} /></div>
          <small>{formatLocalDate(workspace.season.endDate)} 종료</small>
        </div>
        <div className="profile-row">
          <span className="avatar avatar-dark">{activeMemberCount}</span>
          <span><strong>{activeMemberCount}명 활동 중</strong><small>{workspace.season.name}</small></span>
          <span className="profile-actions">
            <button type="button" onClick={onShare} title="공유 링크 복사">공유</button>
            <button type="button" onClick={onManageAccess}>키 관리</button>
          </span>
        </div>
      </div>
    </aside>
  )
}

export function MobileTopbar({ teamName, seasonName, onSwitchSeason, onShare, onManageAccess }: { teamName: string; seasonName: string; onSwitchSeason: () => void; onShare: () => void; onManageAccess: () => void }) {
  return (
    <header className="mobile-topbar">
      <div className="brand"><span className="brand-mark" />BATON</div>
      <button
        type="button"
        className="mobile-team"
        aria-label={`${teamName} ${seasonName}. 시즌 전환`}
        onClick={onSwitchSeason}
      >
        <span>{teamName}</span>
        <small>{seasonName}</small>
      </button>
      <span className="mobile-workspace-actions">
        <button type="button" className="mobile-share" onClick={onShare}>공유</button>
        <button type="button" className="mobile-share" onClick={onManageAccess}>키 관리</button>
      </span>
    </header>
  )
}

export function MobileNav({ view, onNavigate }: { view: ViewKey; onNavigate: (key: ViewKey) => void }) {
  return (
    <nav className="mobile-nav" aria-label="모바일 주 메뉴">
      {navItems.map((item) => (
        <button
          type="button"
          className={view === item.key ? 'active' : ''}
          key={item.key}
          aria-current={view === item.key ? 'page' : undefined}
          onClick={() => onNavigate(item.key)}
        >
          <Icon name={item.icon} size={20} /><span>{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

export function WorkspaceSyncStatus({
  updatedAt,
  syncing,
  failed,
  conflictRecoveryStatus,
  onRefresh,
}: {
  updatedAt: number
  syncing: boolean
  failed: boolean
  conflictRecoveryStatus?: WorkspaceConflictRecoveryStatus
  onRefresh: () => void
}) {
  const conflictUnresolved = Boolean(conflictRecoveryStatus)
  const needsAttention = failed || conflictUnresolved
  const message = conflictRecoveryStatus === 'refreshing'
    ? '동시 수정이 감지되어 최신 내용을 확인하는 중…'
    : conflictRecoveryStatus === 'failed'
      ? '동시 수정이 감지됐어요 · 최신 기록을 확인해야 다시 수정할 수 있어요.'
      : failed
        ? '최신 내용을 확인하지 못했어요 · 저장된 내용 표시 중'
        : syncing
          ? '다른 구성원의 변경을 확인하는 중…'
          : `${formatSyncTime(updatedAt)}에 화면 갱신`

  return (
    <div className={`workspace-sync-status ${needsAttention ? 'sync-failed' : ''}`}>
      <span className="sync-dot" aria-hidden="true" />
      <span aria-hidden={needsAttention ? true : undefined}>{message}</span>
      <span className="sync-announcement" aria-live="polite" aria-atomic="true">
        {needsAttention ? message : ''}
      </span>
      <button
        type="button"
        disabled={syncing}
        onClick={onRefresh}
        aria-label={conflictUnresolved ? '최신 내용 다시 확인' : '지금 새로고침'}
      >
        {syncing
          ? '확인 중…'
          : conflictUnresolved
            ? '최신 내용 다시 확인'
            : '새로고침'}
      </button>
    </div>
  )
}

export function ContentCreationCleanupBanner({
  message,
  pending,
  onRetry,
}: {
  message: string
  pending: boolean
  onRetry: () => void
}) {
  return (
    <section
      className="season-ended-banner"
      role="alert"
      aria-label="콘텐츠 생성 완료 기록 정리"
    >
      <div>
        <Icon name="alert" size={18} />
        <span>
          <strong>이전 콘텐츠 생성 요청의 완료 기록을 정리해야 합니다.</strong>
          <small>{message}</small>
        </span>
      </div>
      <div>
        <button
          type="button"
          className="secondary-button"
          disabled={pending}
          onClick={onRetry}
        >
          {pending ? '완료 기록 정리하는 중…' : '완료 기록 정리 다시 확인'}
        </button>
      </div>
    </section>
  )
}

export function PageHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return (
    <header className="page-header">
      <div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>
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
      <Icon name="spark" size={28} /><strong>{title}</strong><p>{description}</p>
      <button type="button" className="secondary-button" disabled={disabled} onClick={onAction}>{actionLabel}</button>
    </div>
  )
}

export function RoundControl({
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
        <span>운영 회차</span>
        <select
          aria-label="운영 회차"
          value={selectedRound?.id ?? ''}
          disabled={!rounds.length}
          onChange={(event) => onSelect(event.target.value)}
        >
          {!rounds.length && (
            <option value="">
              {archivedRoundCount ? '현재 운영할 회차가 없습니다' : '아직 만든 회차가 없습니다'}
            </option>
          )}
          {rounds.map((round) => (
            <option key={round.id} value={round.id}>
              {round.name} · {roundOriginLabel(round)} · {roundTimingStatusCopy[round.timingStatus]}
              {' · '}{formatLocalDate(round.meetingDate)}
            </option>
          ))}
        </select>
      </label>
      <div className="round-control-actions" role="group" aria-label="회차 관리">
        {onEdit && (
          <button
            type="button"
            className="secondary-button"
            disabled={!selectedRound || selectedRoundBusy || selectedRound.origin === 'AUTOMATIC'}
            title={selectedRound?.origin === 'AUTOMATIC'
              ? '자동 회차는 반복 설정으로 관리합니다'
              : undefined}
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
            aria-label={selectedRound ? `${selectedRound.name} 회차 보관` : '선택한 회차 보관'}
            onClick={() => selectedRound && onArchive(selectedRound)}
          >
            보관
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
      </div>
      {!hasRoutines && (
        <p id="round-create-hint">반복 루틴을 하나 이상 만든 뒤 운영 회차를 만들 수 있어요.</p>
      )}
    </section>
  )
}

export function TodayView({ workspace, personalWork, weeklyBrief, calendarLabel, rounds, archivedRoundCount, selectedRound, pendingCount, completedCount, onSelectRound, onAddRound, onSelectRole, onOpenDecision, onToggleRoutine, onNavigate, onOpenContinuitySignal, onAddRole, onAddRoutine, onEditRoutine, selectedRoundBusy, selectedRoundOperationPending, changesDisabled = false }: {
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
              <span className="section-kicker">주의가 필요한 곳</span>
              <h2 id="continuity-radar-title">조직 연속성 레이더</h2>
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
              현재 규칙에서 먼저 살필 연속성 공백을 찾지 못했어요.
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
      <button type="button" className="routine-copy" onClick={() => role && onSelectRole(role.id)}><span><strong>{displayRoutine.title}</strong><small>{displayRoutine.detail}</small>{execution ? <small className={`timing-label ${execution.timingStatus.toLowerCase()}`}>{routineTimingStatusCopy[execution.timingStatus]}{execution.deadlineAt ? ` · ${formatInstant(execution.deadlineAt, timeZone)}` : ''}</small> : <small className="routine-round-note">다음 회차부터</small>}</span><time>{displayRoutine.dueLabel}</time></button>
      <button type="button" className="routine-owner" onClick={() => role && onSelectRole(role.id)}>{member && <span className="avatar" style={{ background: member.tone }}>{member.initials}</span>}<span><strong>{role?.name ?? '연결된 역할 없음'}</strong><small>{member ? memberDisplayName(member) : '담당자 미정'}</small></span></button>
      {routine ? (
        <span className="routine-actions">
          <button type="button" className="inline-edit-button" aria-label={`${routine.title} 루틴 수정`} disabled={pending || archivePending} onClick={() => onEdit(routine)}>수정</button>
          {onArchive && (
            <button
              type="button"
              className="inline-edit-button routine-archive-button"
              aria-label={`${routine.title} 루틴 보관`}
              aria-busy={archivePending}
              disabled={pending || archivePending}
              onClick={() => onArchive(routine, true)}
            >
              {archivePending ? '보관 중' : '보관'}
            </button>
          )}
        </span>
      ) : (
        <span className="routine-definition-note">정의 보관됨</span>
      )}
    </div>
  )
}

export function RoleInspector({
  role,
  members,
  decisions,
  routines,
  resources,
  handoff,
  progress,
  open,
  overlay,
  blocked,
  onClose,
  onOpenHandoff,
  onAddResource,
  onEditResource,
  onUpdateResourceArchive,
  onManageMembership,
  roundRoomScope,
  changesDisabled = false,
}: {
  role: Role
  members: Member[]
  decisions: Decision[]
  routines: Routine[]
  resources: RoleResource[]
  handoff?: RoleHandoff
  progress: number
  open: boolean
  overlay: boolean
  blocked: boolean
  onClose: () => void
  onOpenHandoff: () => void
  onAddResource: () => void
  onEditResource: (resource: RoleResource) => void
  onUpdateResourceArchive: (resource: RoleResource, archived: boolean) => void
  onManageMembership: () => void
  roundRoomScope: WorkspaceScope
  changesDisabled?: boolean
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const closeButtonRef = useRef<HTMLButtonElement>(null)

  useLayoutEffect(() => {
    const dialog = dialogRef.current
    if (!dialog || !overlay) return

    if (open && !blocked) {
      if (!dialog.open) dialog.showModal()
      closeButtonRef.current?.focus()
      return
    }

    if (dialog.open) dialog.close()
  }, [blocked, open, overlay])

  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedRoutine = routines.find((routine) => routine.ownerRoleId === role.id)
  const relatedDecision = decisions.find((decision) => decision.roleIds.includes(role.id))
  const activeResources = resources.filter((resource) => !resource.archivedAt)
  const archivedResources = resources.filter((resource) => resource.archivedAt)
  const inspectorContent = (
    <>
      <button ref={closeButtonRef} type="button" className="inspector-close" onClick={onClose} aria-label="상세 닫기"><Icon name="close" /></button><div className="inspector-topline"><span>선택한 역할</span><span className="live-dot">운영 중</span></div><h2>{role.name}</h2><p className="inspector-purpose">{role.purpose}</p>
      <div className="owner-block"><span className="block-label">현재 담당자</span>{owner ? <div><span className="avatar avatar-large" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{memberDisplayName(owner)}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></div> : <p className="muted-copy">현재 담당자가 정해지지 않았어요.</p>}</div>
      {role.risk && <div className="risk-note"><Icon name="alert" size={17} /><span><strong>기억이 끊길 수 있어요</strong>{role.risk}</span></div>}
      <div className="inspector-section"><span className="block-label">핵심 책임</span><ul>{role.responsibilities.length ? role.responsibilities.map((item) => <li key={item}><Icon name="check" size={13} />{item}</li>) : <li className="muted">아직 정리된 책임이 없어요.</li>}</ul></div>
      <div className="inspector-section resource-section">
        <div className="resource-section-heading"><span className="block-label">참고 자료</span><button type="button" disabled={changesDisabled} onClick={onAddResource}><Icon name="plus" size={13} /> 자료 추가</button></div>
        {activeResources.length ? (
          <ul className="resource-links">
            {activeResources.map((resource) => (
              <li key={resource.id}>
                <span>
                  <a href={resource.url} target="_blank" rel="noopener noreferrer" aria-label={`${resource.title} 새 창에서 열기`}>{resource.title}</a>
                  {resource.description && <small>{resource.description}</small>}
                </span>
                <div className="resource-row-actions">
                  <RoundRoomResourceActions
                    {...roundRoomScope}
                    resourceId={resource.id}
                    changesDisabled={changesDisabled}
                    onManageMembership={onManageMembership}
                  />
                  <button type="button" aria-label={`${resource.title} 자료 수정`} disabled={changesDisabled} onClick={() => onEditResource(resource)}>수정</button>
                  <button type="button" aria-label={`${resource.title} 자료 보관`} disabled={changesDisabled} onClick={() => onUpdateResourceArchive(resource, true)}>보관</button>
                </div>
              </li>
            ))}
          </ul>
        ) : <p className="muted-copy resource-empty">연결된 자료가 아직 없어요.</p>}
        {archivedResources.length > 0 && (
          <details className="record-archived">
            <summary>자료 보관함 {archivedResources.length}개</summary>
            <ul className="resource-links">
              {archivedResources.map((resource) => (
                <li key={resource.id}>
                  <span>
                    <strong>{resource.title}</strong>
                    <small>{formatInstant(resource.archivedAt!)} 보관</small>
                  </span>
                  <button
                    type="button"
                    aria-label={`${resource.title} 자료 복원`}
                    disabled={changesDisabled}
                    onClick={() => onUpdateResourceArchive(resource, false)}
                  >
                    복원
                  </button>
                </li>
              ))}
            </ul>
          </details>
        )}
      </div>
      {relatedRoutine && <div className="inspector-section next-event"><span className="block-label">다음 루틴</span><strong>{relatedRoutine.title}</strong><small>{relatedRoutine.dueLabel} · {relatedRoutine.detail}</small></div>}
      {relatedDecision && <div className="inspector-section linked-decision"><span className="block-label">연결된 결정</span><p>“{relatedDecision.title}”</p><small>{formatInstant(relatedDecision.createdAt)}</small></div>}
      <div className="inspector-handoff"><div><span className="block-label">{handoff?.status === 'TRANSFERRED' ? '바통 수락 대기' : '바통 준비도'}</span><strong>{progress}%</strong></div><div className="thin-progress"><i style={{ width: `${progress}%` }} /></div><p>{handoff?.status === 'TRANSFERRED' ? '수락 또는 취소 전까지 역할과 바통북을 수정할 수 없어요.' : next ? `다음 담당자 · ${memberDisplayName(next)}` : '다음 담당자가 아직 정해지지 않았어요.'}</p><button type="button" onClick={onOpenHandoff}>{handoff?.status === 'TRANSFERRED' ? '바통 수락 확인하기' : '바통 정리하기'} <Icon name="arrow" size={15} /></button></div>
    </>
  )

  if (overlay) {
    return createPortal(
      <dialog
        ref={dialogRef}
        className={`inspector ${open ? 'is-open' : ''}`}
        aria-label={`선택한 역할 상세: ${role.name}`}
        onCancel={(event) => {
          event.preventDefault()
          onClose()
        }}
      >
        {inspectorContent}
      </dialog>,
      document.body,
    )
  }

  return (
    <aside
      className={`inspector ${open ? 'is-open' : ''}`}
      aria-label={`선택한 역할 상세: ${role.name}`}
    >
      {inspectorContent}
    </aside>
  )
}
