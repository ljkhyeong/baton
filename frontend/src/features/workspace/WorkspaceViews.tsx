import { useId, useRef } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '@/shared/ui/Icon'
import {
  categoryCopy,
  formatLocalDate,
  getMember,
  isActiveMember,
  latestRoleHandoff,
  memberDisplayName,
  phaseCopy,
} from './workspacePresentation'
import {
  daysUntil,
  seasonProgress,
} from './seasonCalendar'
import type { WorkspaceConflictRecoveryStatus } from './useWorkspaceConflictRecovery'
import { useFocusBoundary } from './useFocusBoundary'
import type {
  ContinuitySignal,
  Decision,
  HandoffItem,
  Member,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  RoutineExecution,
  RoutinePhase,
  RoutineTimingStatus,
  Season,
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

function formatDateRange(startDate?: string | null, endDate?: string | null) {
  if (!startDate && !endDate) return '담당 기간 미정'
  return `${formatLocalDate(startDate)} — ${formatLocalDate(endDate)}`
}

function formatInstant(value: string, timeZone?: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
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

function formatLocalTime(value: string) {
  return value.slice(0, 5)
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
          <button type="button" className={view === item.key ? 'active' : ''} key={item.key} onClick={() => onNavigate(item.key)}>
            <Icon name={item.icon} /><span>{item.label}</span>
            {item.key === 'handoff'
              && (workspace.roleHandoffs?.some((handoff) => handoff.status === 'TRANSFERRED')
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
        <button type="button" className={view === item.key ? 'active' : ''} key={item.key} onClick={() => onNavigate(item.key)}>
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

function PageHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return (
    <header className="page-header">
      <div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>
      {action && <div className="page-action">{action}</div>}
    </header>
  )
}

function PrimaryButton({ children, onClick, icon = true, disabled = false }: { children: ReactNode; onClick: () => void; icon?: boolean; disabled?: boolean }) {
  return <button type="button" className="primary-button" onClick={onClick} disabled={disabled}>{icon && <Icon name="plus" size={16} />}{children}</button>
}

function ActionableEmpty({ title, description, actionLabel, onAction, disabled = false }: { title: string; description: string; actionLabel: string; onAction: () => void; disabled?: boolean }) {
  return (
    <div className="empty-state actionable-empty">
      <Icon name="spark" size={28} /><strong>{title}</strong><p>{description}</p>
      <button type="button" className="secondary-button" disabled={disabled} onClick={onAction}>{actionLabel}</button>
    </div>
  )
}

function RoundControl({
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

export function TodayView({ workspace, calendarLabel, rounds, archivedRoundCount, selectedRound, pendingCount, completedCount, onSelectRound, onAddRound, onSelectRole, onOpenDecision, onToggleRoutine, onNavigate, onOpenContinuitySignal, onAddRole, onAddRoutine, onEditRoutine, selectedRoundBusy, changesDisabled = false }: {
  workspace: WorkspaceProjection
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
  const orderedRoutines = [...routines].sort((left, right) => {
    const leftExecution = selectedRound?.routineExecutions
      .find((execution) => execution.routineId === left.id)
    const rightExecution = selectedRound?.routineExecutions
      .find((execution) => execution.routineId === right.id)
    return timingPriority[leftExecution?.timingStatus ?? 'UNSCHEDULED']
      - timingPriority[rightExecution?.timingStatus ?? 'UNSCHEDULED']
  })
  return (
    <>
      <PageHeader
        eyebrow={`${calendarLabel} · ${season.name}`}
        title={`${pendingCount}개의 바통이 남았어요`}
        description="이번 운영에서 멈춘 흐름과 다음 담당자를 확인하세요."
        action={<PrimaryButton onClick={onOpenDecision} disabled={changesDisabled || !roles.length || !members.some(isActiveMember)}>결정 남기기</PrimaryButton>}
      />
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
        {!routines.length ? (
          <ActionableEmpty title="아직 운영 루틴이 없어요" description="첫 반복 업무를 역할과 연결해 보세요." actionLabel={roles.length ? '첫 루틴 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} disabled={changesDisabled} />
        ) : selectedRound ? (
          <div className="relay-line" role="list">
            {orderedRoutines.map((routine, index) => {
              const execution = selectedRound.routineExecutions.find((item) => item.routineId === routine.id)
              const displayRoutine = execution ?? routine
              const role = roles.find((item) => item.id === displayRoutine.ownerRoleId)
              const member = getMember(members, role?.currentMemberId)
              return (
                <button type="button" className={`relay-step ${execution?.timingStatus.toLowerCase() ?? 'future'}`} key={routine.id} onClick={() => role && onSelectRole(role.id)} role="listitem">
                  <span className="relay-index">{String(index + 1).padStart(2, '0')}</span><span className="relay-node"><span /></span>
                  <span className="relay-status">{execution ? routineTimingStatusCopy[execution.timingStatus] : '다음 회차부터'}</span><strong>{displayRoutine.title}</strong>
                  <small>{member ? memberDisplayName(member) : '담당자 미정'} · {displayRoutine.dueLabel}</small>
                </button>
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
      {selectedRound && routines.length > 0 && (
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
          {orderedRoutines.map((routine) => {
            const execution = selectedRound.routineExecutions.find((item) => item.routineId === routine.id)
            const ownerRoleId = execution?.ownerRoleId ?? routine.ownerRoleId
            return (
              <RoutineRow
                key={routine.id}
                routine={routine}
                execution={execution}
                role={roles.find((item) => item.id === ownerRoleId)}
                members={members}
                timeZone={season.timeZone}
                onToggle={onToggleRoutine}
                onSelectRole={onSelectRole}
                onEdit={onEditRoutine}
                pending={selectedRoundBusy}
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

export function RolesView({
  roles,
  roleHandoffs,
  members,
  selectedRoleId,
  onSelectRole,
  onManageMembers,
  onAddRole,
  onEditRole,
  handoffProgress,
  changesDisabled = false,
}: {
  roles: Role[]
  roleHandoffs: RoleHandoff[]
  members: Member[]
  selectedRoleId: string
  onSelectRole: (id: string) => void
  onManageMembers: () => void
  onAddRole: () => void
  onEditRole: (role: Role) => void
  handoffProgress: (id: string) => number
  changesDisabled?: boolean
}) {
  return (
    <>
      <PageHeader
        eyebrow="팀의 책임 지도"
        title="사람이 바뀌어도 역할은 남아요"
        description="현재 담당자와 다음 담당자, 반복되는 책임을 한눈에 확인하세요."
        action={(
          <div className="action-cluster">
            <button
              type="button"
              className="secondary-button"
              onClick={onManageMembers}
              disabled={changesDisabled}
            >
              <Icon name="roles" size={15} /> 구성원 관리
            </button>
            <PrimaryButton onClick={onAddRole} disabled={changesDisabled}>역할 추가</PrimaryButton>
          </div>
        )}
      />
      {roles.length ? (
        <section className="role-directory">
          <div className="directory-head"><span>역할과 목적</span><span>현재 담당자</span><span>다음 담당자</span><span>바통 준비</span></div>
          {roles.map((role) => {
            const owner = getMember(members, role.currentMemberId)
            const next = getMember(members, role.nextMemberId)
            const handoff = latestRoleHandoff(roleHandoffs, role.id)
            const roleLocked = handoff?.status === 'TRANSFERRED'
            return (
              <div className={`role-row ${selectedRoleId === role.id ? 'selected' : ''}`} key={role.id}>
                <button
                  type="button"
                  className="role-row-open"
                  onClick={() => onSelectRole(role.id)}
                >
                  <span className="role-main"><span className="role-glyph"><Icon name="roles" size={17} /></span><span><strong>{role.name}<span className="visually-hidden"> 역할 상세 열기</span></strong><small>{role.purpose}</small></span></span>
                  <span className="person-cell">{owner ? <><span className="avatar" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{memberDisplayName(owner)}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></> : <em>담당자 미정</em>}</span>
                  <span className="next-cell">{next ? <><span className="avatar" style={{ background: next.tone }}>{next.initials}</span>{memberDisplayName(next)}</> : <em>아직 미정</em>}</span>
                  <span className="progress-cell"><strong>{roleLocked ? '수락 대기' : `${handoffProgress(role.id)}%`}</strong><span className="thin-progress"><i style={{ width: `${handoffProgress(role.id)}%` }} /></span><Icon name="chevron" size={16} /></span>
                </button>
                <button type="button" className="inline-edit-button" aria-label={`${role.name} 역할 수정`} disabled={changesDisabled || roleLocked} onClick={() => onEditRole(role)}>수정</button>
              </div>
            )
          })}
        </section>
      ) : <ActionableEmpty title="아직 역할이 없어요" description="사람보다 오래 남을 첫 책임을 역할로 만들어 보세요." actionLabel="첫 역할 만들기" onAction={onAddRole} disabled={changesDisabled} />}
      <p className="directory-note"><Icon name="spark" size={15} /> 사람을 먼저 초대하기보다, 팀에 꼭 필요한 책임부터 역할로 정리해 보세요.</p>
    </>
  )
}

export function RhythmView({
  season,
  roles,
  routines,
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
  onConfigureRoundSchedule,
  busyRoundIds,
  changesDisabled = false,
}: {
  season: Season
  roles: Role[]
  routines: Routine[]
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
  onConfigureRoundSchedule: () => void
  busyRoundIds: ReadonlySet<string>
  changesDisabled?: boolean
}) {
  const phases: RoutinePhase[] = ['BEFORE', 'DURING', 'AFTER']
  return (
    <>
      <PageHeader eyebrow="반복되는 운영 리듬" title="우리 팀은 이렇게 움직여요" description="매번 설명하던 일을 루틴으로 만들고, 완료되면 다음 역할로 넘깁니다." action={<PrimaryButton onClick={onAddRoutine} disabled={changesDisabled}>루틴 추가</PrimaryButton>} />
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
              ? `${season.timeZone} · ${season.roundSchedule.enabled ? '자동 생성 중' : '일시중지'}`
              : `${season.timeZone} 기준 반복 일정을 설정해 보세요.`}
            {season.roundSchedule?.nextOccurrenceDate
              ? ` · 다음 발생 ${formatLocalDate(season.roundSchedule.nextOccurrenceDate)}`
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
      {routines.length ? (
        <div className="rhythm-timeline">
          {phases.map((phase, phaseIndex) => (
            <section className="rhythm-phase" key={phase}>
              <div className="phase-marker"><span>{String(phaseIndex + 1).padStart(2, '0')}</span><h2>{phaseCopy[phase]}</h2></div>
              <div className="phase-content">
                {routines.filter((routine) => {
                  const execution = selectedRound?.routineExecutions.find((item) => item.routineId === routine.id)
                  return (execution?.phase ?? routine.phase) === phase
                }).map((routine) => {
                  const execution = selectedRound?.routineExecutions.find((item) => item.routineId === routine.id)
                  const ownerRoleId = execution?.ownerRoleId ?? routine.ownerRoleId
                  return (
                    <RoutineRow
                      key={routine.id}
                      routine={routine}
                      execution={execution}
                      role={roles.find((role) => role.id === ownerRoleId)}
                      members={members}
                      timeZone={season.timeZone}
                      onToggle={onToggleRoutine}
                      onSelectRole={onSelectRole}
                      onEdit={onEditRoutine}
                      pending={changesDisabled || Boolean(selectedRound && busyRoundIds.has(selectedRound.id))}
                    />
                  )
                })}
              </div>
            </section>
          ))}
        </div>
      ) : <ActionableEmpty title="아직 반복 루틴이 없어요" description="모임 전·중·후에 반복할 일을 역할과 연결해 주세요." actionLabel={roles.length ? '첫 루틴 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} disabled={changesDisabled} />}
      {routines.length > 0 && <button type="button" className="add-routine-line" disabled={changesDisabled} onClick={onAddRoutine}><Icon name="plus" size={15} /> 반복할 일 추가하기</button>}
    </>
  )
}

function RoutineRow({ routine, execution, role, members, timeZone, onToggle, onSelectRole, onEdit, pending }: { routine: Routine; execution?: RoutineExecution; role?: Role; members: Member[]; timeZone: string; onToggle: (execution: RoutineExecution) => void; onSelectRole: (id: string) => void; onEdit: (routine: Routine) => void; pending: boolean }) {
  const displayRoutine = execution ?? routine
  const member = getMember(members, role?.currentMemberId)
  return (
    <div
      className={`routine-row ${execution?.timingStatus.toLowerCase() ?? 'future'}`}
      data-routine-id={routine.id}
    >
      {execution ? (
        <button type="button" className="check-button" disabled={pending} onClick={() => onToggle(execution)} aria-label={`${displayRoutine.title} ${execution.status === 'DONE' ? '완료 취소' : '완료 처리'}`} aria-busy={pending}>
          {execution.status === 'DONE' && <Icon name="check" size={14} />}
        </button>
      ) : <span className="check-button check-button-unavailable" aria-hidden="true" />}
      <button type="button" className="routine-copy" onClick={() => role && onSelectRole(role.id)}><span><strong>{displayRoutine.title}</strong><small>{displayRoutine.detail}</small>{execution ? <small className={`timing-label ${execution.timingStatus.toLowerCase()}`}>{routineTimingStatusCopy[execution.timingStatus]}{execution.deadlineAt ? ` · ${formatInstant(execution.deadlineAt, timeZone)}` : ''}</small> : <small className="routine-round-note">다음 회차부터</small>}</span><time>{displayRoutine.dueLabel}</time></button>
      <button type="button" className="routine-owner" onClick={() => role && onSelectRole(role.id)}>{member && <span className="avatar" style={{ background: member.tone }}>{member.initials}</span>}<span><strong>{role?.name ?? '연결된 역할 없음'}</strong><small>{member ? memberDisplayName(member) : '담당자 미정'}</small></span></button>
      <button type="button" className="inline-edit-button" aria-label={`${routine.title} 루틴 수정`} disabled={pending} onClick={() => onEdit(routine)}>수정</button>
    </div>
  )
}

export function MemoryView({
  decisions,
  archivedDecisions,
  roles,
  members,
  onOpenDecision,
  onAddRole,
  onManageMembers,
  onSelectRole,
  onEditDecision,
  onUpdateArchive,
  archivePending,
  changesDisabled = false,
}: {
  decisions: Decision[]
  archivedDecisions: Decision[]
  roles: Role[]
  members: Member[]
  onOpenDecision: () => void
  onAddRole: () => void
  onManageMembers: () => void
  onSelectRole: (id: string) => void
  onEditDecision: (decision: Decision) => void
  onUpdateArchive: (decision: Decision, archived: boolean) => void
  archivePending: boolean
  changesDisabled?: boolean
}) {
  const canCreateDecision = roles.length > 0 && members.some(isActiveMember)
  return (
    <>
      <PageHeader eyebrow="팀의 결정 원장" title="결과뿐 아니라 이유도 남겨두세요" description="채팅에서 사라질 결정을 다음 시즌도 이해할 수 있는 기록으로 바꿉니다." action={<PrimaryButton onClick={onOpenDecision} disabled={changesDisabled || !canCreateDecision}>결정 남기기</PrimaryButton>} />
      {decisions.length ? (
        <section className="memory-ledger">
          <div className="memory-rule"><span>최근 결정</span><span>{decisions.length}개의 기록</span></div>
          {decisions.map((decision, index) => (
            <article className="decision-entry" key={decision.id}>
              <div className="decision-number">{String(decisions.length - index).padStart(2, '0')}</div>
              <div className="decision-body">
                <div className="decision-heading">
                  <div>
                    <div className="decision-meta"><time>{formatInstant(decision.createdAt)}</time><span>{decision.authorName}</span></div>
                    <h2>{decision.title}</h2>
                  </div>
                  <div className="record-actions">
                    <button
                      type="button"
                      aria-label={`${decision.title} 수정`}
                      disabled={changesDisabled}
                      onClick={() => onEditDecision(decision)}
                    >
                      수정
                    </button>
                    <button
                      type="button"
                      aria-label={`${decision.title} 보관`}
                      disabled={changesDisabled || archivePending}
                      onClick={() => onUpdateArchive(decision, true)}
                    >
                      보관
                    </button>
                  </div>
                </div>
                <div className="decision-reason"><span>이유</span><p>{decision.reason}</p></div><div className="decision-alternative"><span>검토한 다른 선택</span><p>{decision.alternative}</p></div>
                <div className="decision-tags">{decision.roleIds.map((roleId) => { const role = roles.find((item) => item.id === roleId); return role ? <button type="button" key={roleId} onClick={() => onSelectRole(roleId)}>{role.name}</button> : null })}</div>
              </div>
            </article>
          ))}
        </section>
      ) : (
        <ActionableEmpty
          title={archivedDecisions.length ? '현재 원장에 꺼내 둔 결정이 없어요' : '아직 결정 기록이 없어요'}
          description={archivedDecisions.length
            ? '아래 보관함에서 다시 필요한 결정을 복원하거나 새 결정을 남겨 보세요.'
            : '운영 방식이 바뀌는 순간, 결과와 이유를 함께 남겨 보세요.'}
          actionLabel={canCreateDecision
            ? (archivedDecisions.length ? '새 결정 남기기' : '첫 결정 남기기')
            : roles.length ? '활동 중 구성원 준비하기' : '첫 역할 만들기'}
          onAction={canCreateDecision
            ? onOpenDecision
            : roles.length ? onManageMembers : onAddRole}
          disabled={changesDisabled}
        />
      )}
      {archivedDecisions.length > 0 && (
        <details className="archive-shelf">
          <summary>보관한 결정 {archivedDecisions.length}개</summary>
          <div className="archive-list">
            {archivedDecisions.map((decision) => (
              <div className="archive-row" key={decision.id}>
                <span>
                  <strong>{decision.title}</strong>
                  <small>
                    {decision.archivedAt
                      ? `${formatInstant(decision.archivedAt)} 보관`
                      : '보관됨'}
                  </small>
                </span>
                <button
                  type="button"
                  aria-label={`${decision.title} 복원`}
                  disabled={changesDisabled || archivePending}
                  onClick={() => onUpdateArchive(decision, false)}
                >
                  복원
                </button>
              </div>
            ))}
          </div>
        </details>
      )}
    </>
  )
}

export function HandoffView({
  roles,
  roleHandoffs,
  members,
  season,
  calendarDate,
  selectedRoleId,
  handoffItems,
  archivedItems,
  onSelectRole,
  onToggle,
  onEditItem,
  onUpdateArchive,
  progress,
  onPreview,
  onAddItem,
  onAddRole,
  onPrepareHandoff,
  onTransferHandoff,
  onAcceptHandoff,
  onCancelHandoff,
  busyItemIds,
  handoffTransitionPending = false,
  changesDisabled = false,
}: {
  roles: Role[]
  roleHandoffs: RoleHandoff[]
  members: Member[]
  season: Season
  calendarDate: string
  selectedRoleId: string
  handoffItems: HandoffItem[]
  archivedItems: HandoffItem[]
  onSelectRole: (id: string) => void
  onToggle: (id: string) => void
  onEditItem: (item: HandoffItem) => void
  onUpdateArchive: (item: HandoffItem, archived: boolean) => void
  progress: (id: string) => number
  onPreview: () => void
  onAddItem: () => void
  onAddRole: () => void
  onPrepareHandoff: (role: Role) => void
  onTransferHandoff: (role: Role, handoff: RoleHandoff) => void
  onAcceptHandoff: (role: Role, handoff: RoleHandoff) => void
  onCancelHandoff: (role: Role, handoff: RoleHandoff) => void
  busyItemIds: ReadonlySet<string>
  handoffTransitionPending?: boolean
  changesDisabled?: boolean
}) {
  const tabSetId = useId()
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])
  const selectedIndex = Math.max(0, roles.findIndex((role) => role.id === selectedRoleId))
  const selected = roles[selectedIndex] ?? roles[0]
  if (!selected) {
    return <><PageHeader eyebrow="역할 인수인계" title="첫 역할부터 만들어 주세요" description="역할이 생기면 책임과 운영 맥락을 바통북으로 정리할 수 있습니다." /><ActionableEmpty title="넘겨줄 역할이 아직 없어요" description="팀의 첫 책임을 역할로 추가해 주세요." actionLabel="첫 역할 만들기" onAction={onAddRole} disabled={changesDisabled} /></>
  }
  const panelId = `${tabSetId}-panel`
  const selectedTabId = `${tabSetId}-tab-${selected.id}`
  const activateTab = (index: number) => {
    const role = roles[index]
    if (!role) return
    onSelectRole(role.id)
    window.requestAnimationFrame(() => {
      const tab = tabRefs.current[index]
      tab?.focus()
      tab?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
    })
  }
  const handleTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    let nextIndex: number | undefined
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % roles.length
    if (event.key === 'ArrowLeft') nextIndex = (index - 1 + roles.length) % roles.length
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = roles.length - 1
    if (nextIndex === undefined) return

    event.preventDefault()
    activateTab(nextIndex)
  }
  const items = handoffItems.filter((item) => item.roleId === selected.id)
  const selectedArchivedItems = archivedItems.filter((item) => item.roleId === selected.id)
  const selectedHandoff = latestRoleHandoff(roleHandoffs, selected.id)
  const selectedChangesDisabled = changesDisabled || selectedHandoff?.status === 'TRANSFERRED'
  const next = getMember(
    members,
    selectedHandoff && selectedHandoff.status !== 'CANCELLED'
      ? selectedHandoff.toMemberId
      : selected.nextMemberId,
  )
  const handoffSummaryTitle = selectedHandoff?.status === 'ACCEPTED'
    ? `${next?.name ?? '다음 담당자'}님이 이어받은 바통`
    : selectedHandoff?.status === 'CANCELLED'
      ? '다음 전달을 다시 준비하는 바통'
      : next
        ? isActiveMember(next)
          ? `${next.name}님에게 넘길 바통`
          : `${next.name}님은 활동을 종료했어요`
        : '다음 담당자를 기다리는 바통'
  const remainingDays = daysUntil(season.endDate, calendarDate)
  return (
    <>
      <PageHeader
        eyebrow={remainingDays >= 0 ? `시즌 종료까지 ${remainingDays}일` : `${formatLocalDate(season.endDate)} 시즌 종료`}
        title="다음 사람이 헤매지 않도록"
        description="역할의 책임과 맥락을 바통북으로 정리해 다음 담당자에게 넘깁니다."
        action={<div className="action-cluster"><button type="button" className="secondary-button" disabled={selectedChangesDisabled} onClick={onAddItem}><Icon name="plus" size={15} /> 항목 추가</button><PrimaryButton onClick={onPreview} icon={false}>바통북 미리보기</PrimaryButton></div>}
      />
      <div className="handoff-role-tabs" role="tablist" aria-label="역할별 바통" aria-orientation="horizontal">
        {roles.map((role, index) => {
          const active = selected.id === role.id
          const handoff = latestRoleHandoff(roleHandoffs, role.id)
          return (
            <button
              ref={(element) => {
                tabRefs.current[index] = element
              }}
              id={`${tabSetId}-tab-${role.id}`}
              type="button"
              role="tab"
              aria-controls={panelId}
              aria-selected={active}
              tabIndex={active ? 0 : -1}
              className={active ? 'active' : ''}
              key={role.id}
              onClick={() => onSelectRole(role.id)}
              onKeyDown={(event) => handleTabKeyDown(event, index)}
            >
              <span>{role.name}</span>
              <strong>{handoff?.status === 'TRANSFERRED' ? '수락 대기' : `${progress(role.id)}%`}</strong>
            </button>
          )
        })}
      </div>
      <section
        id={panelId}
        className="handoff-workspace"
        role="tabpanel"
        aria-labelledby={selectedTabId}
        tabIndex={0}
      >
        <div className="handoff-summary"><span className="section-kicker">{selected.name}</span><h2>{handoffSummaryTitle}</h2><p>{next && !isActiveMember(next) ? '활동 중인 다음 담당자를 정한 뒤 바통을 이어 주세요.' : selected.purpose}</p><div className="handoff-score"><strong>{progress(selected.id)}%</strong><span><i style={{ width: `${progress(selected.id)}%` }} /></span><small>{items.filter((item) => item.completed).length}/{items.length} 항목 준비됨</small></div></div>
        <div className="handoff-checklist">
          <div
            className={`handoff-lifecycle-card ${selectedHandoff?.status.toLowerCase() ?? 'ready'}`}
            aria-live="polite"
          >
            {!selectedHandoff || selectedHandoff.status === 'ACCEPTED'
              || selectedHandoff.status === 'CANCELLED' ? (
                <>
                  <span className="handoff-state-label">
                    {selectedHandoff?.status === 'ACCEPTED'
                      ? '최근 바통 수락 완료'
                      : selectedHandoff?.status === 'CANCELLED'
                        ? '최근 바통 취소'
                        : '전달 전'}
                  </span>
                  <strong>
                    {selectedHandoff?.status === 'ACCEPTED'
                      ? `${next?.name ?? '다음 담당자'}님의 수락을 기록했어요`
                      : '다음 담당자와 역할 기간을 정해 준비를 시작하세요'}
                  </strong>
                  <p>
                    {selected.currentMemberId && selected.assignmentStartDate
                      ? '준비 단계에서는 바통북을 계속 다듬을 수 있고, 전달한 뒤에는 수락 또는 취소까지 내용이 잠깁니다.'
                      : '바통 준비를 시작하려면 역할의 현재 담당자와 담당 시작일을 먼저 정해야 합니다.'}
                  </p>
                  <button
                    type="button"
                    className="primary-button"
                    disabled={changesDisabled
                      || handoffTransitionPending
                      || !selected.currentMemberId
                      || !selected.assignmentStartDate}
                    onClick={() => onPrepareHandoff(selected)}
                  >
                    바통 준비 시작
                  </button>
                </>
              ) : selectedHandoff.status === 'PREPARING' ? (
                <>
                  <span className="handoff-state-label">준비 중</span>
                  <strong>{next?.name ?? '다음 담당자'}님에게 전달할 바통을 검토하세요</strong>
                  <p>
                    수락 뒤 담당 기간은 {formatDateRange(
                      selectedHandoff.incomingAssignmentStartDate,
                      selectedHandoff.incomingAssignmentEndDate,
                    )}입니다.
                  </p>
                  <div className="handoff-lifecycle-actions">
                    <button
                      type="button"
                      className="primary-button"
                      disabled={changesDisabled || handoffTransitionPending}
                      onClick={() => onTransferHandoff(selected, selectedHandoff)}
                    >
                      바통 전달 검토
                    </button>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={changesDisabled || handoffTransitionPending}
                      onClick={() => onCancelHandoff(selected, selectedHandoff)}
                    >
                      준비 취소
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <span className="handoff-state-label">수락 대기</span>
                  <strong>{next?.name ?? '다음 담당자'}님의 수락을 기다리고 있어요</strong>
                  <p>전달한 바통북은 수락하거나 취소하기 전까지 역할·체크리스트·자료를 수정할 수 없습니다.</p>
                  <dl className="handoff-transfer-snapshot" aria-label="전달 시점 바통북 준비도">
                    <div><dt>활성 항목</dt><dd>{selectedHandoff.activeItemCount ?? 0}</dd></div>
                    <div><dt>미완료</dt><dd>{selectedHandoff.incompleteItemCount ?? 0}</dd></div>
                    <div><dt>참고 자료</dt><dd>{selectedHandoff.resourceCount ?? 0}</dd></div>
                  </dl>
                  <div className="handoff-lifecycle-actions">
                    <button
                      type="button"
                      className="primary-button"
                      disabled={changesDisabled || handoffTransitionPending}
                      onClick={() => onAcceptHandoff(selected, selectedHandoff)}
                    >
                      바통 수락
                    </button>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={changesDisabled || handoffTransitionPending}
                      onClick={() => onCancelHandoff(selected, selectedHandoff)}
                    >
                      전달 취소
                    </button>
                  </div>
                </>
              )}
          </div>
          {items.length ? items.map((item) => {
            const busy = selectedChangesDisabled || busyItemIds.has(item.id)
            return (
              <div className={`handoff-item-row ${item.completed ? 'done' : ''}`} key={item.id}>
                <label className="handoff-item-toggle">
                  <input type="checkbox" checked={item.completed} disabled={busy} onChange={() => onToggle(item.id)} />
                  <span className="custom-check">{item.completed && <Icon name="check" size={14} />}</span>
                  <span><strong>{item.label}</strong><small>{categoryCopy[item.category]}</small></span>
                </label>
                <div className="record-actions">
                  <button
                    type="button"
                    aria-label={`${item.label} 수정`}
                    disabled={busy}
                    onClick={() => onEditItem(item)}
                  >
                    수정
                  </button>
                  <button
                    type="button"
                    aria-label={`${item.label} 보관`}
                    disabled={busy}
                    onClick={() => onUpdateArchive(item, true)}
                  >
                    보관
                  </button>
                </div>
              </div>
            )
          }) : (
            <ActionableEmpty
              title={selectedArchivedItems.length ? '현재 체크리스트가 비어 있어요' : '아직 바통북 항목이 없어요'}
              description={selectedArchivedItems.length
                ? '아래 보관함에서 다시 필요한 항목을 복원하거나 새 항목을 추가해 주세요.'
                : '다음 담당자가 알아야 할 책임, 자료와 조언을 추가해 주세요.'}
              actionLabel={selectedArchivedItems.length ? '새 항목 추가하기' : '첫 항목 추가하기'}
              onAction={onAddItem}
              disabled={selectedChangesDisabled}
            />
          )}
        </div>
      </section>
      {selectedArchivedItems.length > 0 && (
        <details className="archive-shelf">
          <summary>보관한 바통 {selectedArchivedItems.length}개</summary>
          <div className="archive-list">
            {selectedArchivedItems.map((item) => (
              <div className="archive-row" key={item.id}>
                <span>
                  <strong>{item.label}</strong>
                  <small>
                    {item.archivedAt ? `${formatInstant(item.archivedAt)} 보관` : '보관됨'}
                    {item.completed ? ' · 준비 완료 유지' : ''}
                  </small>
                </span>
                <button
                  type="button"
                  aria-label={`${item.label} 복원`}
                  disabled={selectedChangesDisabled || busyItemIds.has(item.id)}
                  onClick={() => onUpdateArchive(item, false)}
                >
                  복원
                </button>
              </div>
            ))}
          </div>
        </details>
      )}
    </>
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
  changesDisabled?: boolean
}) {
  const inspectorRef = useRef<HTMLElement>(null)
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const activeOverlay = overlay && open && !blocked
  const inaccessibleOverlay = overlay && (!open || blocked)
  useFocusBoundary({
    active: activeOverlay,
    containerRef: inspectorRef,
    initialFocusRef: closeButtonRef,
    onClose,
  })

  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedRoutine = routines.find((routine) => routine.ownerRoleId === role.id)
  const relatedDecision = decisions.find((decision) => decision.roleIds.includes(role.id))
  const inspector = (
    <aside
      ref={inspectorRef}
      className={`inspector ${open ? 'is-open' : ''}`}
      role={activeOverlay ? 'dialog' : undefined}
      aria-modal={activeOverlay || undefined}
      aria-label={`선택한 역할 상세: ${role.name}`}
      aria-hidden={inaccessibleOverlay || undefined}
      inert={inaccessibleOverlay}
      tabIndex={overlay ? -1 : undefined}
    >
      <button ref={closeButtonRef} type="button" className="inspector-close" onClick={onClose} aria-label="상세 닫기"><Icon name="close" /></button><div className="inspector-topline"><span>선택한 역할</span><span className="live-dot">운영 중</span></div><h2>{role.name}</h2><p className="inspector-purpose">{role.purpose}</p>
      <div className="owner-block"><span className="block-label">현재 담당자</span>{owner ? <div><span className="avatar avatar-large" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{memberDisplayName(owner)}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></div> : <p className="muted-copy">현재 담당자가 정해지지 않았어요.</p>}</div>
      {role.risk && <div className="risk-note"><Icon name="alert" size={17} /><span><strong>기억이 끊길 수 있어요</strong>{role.risk}</span></div>}
      <div className="inspector-section"><span className="block-label">핵심 책임</span><ul>{role.responsibilities.length ? role.responsibilities.map((item) => <li key={item}><Icon name="check" size={13} />{item}</li>) : <li className="muted">아직 정리된 책임이 없어요.</li>}</ul></div>
      <div className="inspector-section resource-section">
        <div className="resource-section-heading"><span className="block-label">참고 자료</span><button type="button" disabled={changesDisabled} onClick={onAddResource}><Icon name="plus" size={13} /> 자료 추가</button></div>
        {resources.length ? (
          <ul className="resource-links">
            {resources.map((resource) => (
              <li key={resource.id}>
                <span>
                  <a href={resource.url} target="_blank" rel="noopener noreferrer" aria-label={`${resource.title} 새 창에서 열기`}>{resource.title}</a>
                  {resource.description && <small>{resource.description}</small>}
                </span>
                <button type="button" aria-label={`${resource.title} 자료 수정`} disabled={changesDisabled} onClick={() => onEditResource(resource)}>수정</button>
              </li>
            ))}
          </ul>
        ) : <p className="muted-copy resource-empty">연결된 자료가 아직 없어요.</p>}
      </div>
      {relatedRoutine && <div className="inspector-section next-event"><span className="block-label">다음 루틴</span><strong>{relatedRoutine.title}</strong><small>{relatedRoutine.dueLabel} · {relatedRoutine.detail}</small></div>}
      {relatedDecision && <div className="inspector-section linked-decision"><span className="block-label">연결된 결정</span><p>“{relatedDecision.title}”</p><small>{formatInstant(relatedDecision.createdAt)}</small></div>}
      <div className="inspector-handoff"><div><span className="block-label">{handoff?.status === 'TRANSFERRED' ? '바통 수락 대기' : '바통 준비도'}</span><strong>{progress}%</strong></div><div className="thin-progress"><i style={{ width: `${progress}%` }} /></div><p>{handoff?.status === 'TRANSFERRED' ? '수락 또는 취소 전까지 역할과 바통북을 수정할 수 없어요.' : next ? `다음 담당자 · ${memberDisplayName(next)}` : '다음 담당자가 아직 정해지지 않았어요.'}</p><button type="button" onClick={onOpenHandoff}>{handoff?.status === 'TRANSFERRED' ? '바통 수락 확인하기' : '바통 정리하기'} <Icon name="arrow" size={15} /></button></div>
    </aside>
  )
  return overlay ? createPortal(inspector, document.body) : inspector
}
