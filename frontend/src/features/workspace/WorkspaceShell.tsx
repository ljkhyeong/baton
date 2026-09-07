import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import '@/features/team-access/my-teams.scss'
import { Icon } from '@/shared/ui/Icon'
import type { WorkspaceConflictRecoveryStatus } from './useWorkspaceConflictRecovery'
import { seasonProgress } from './seasonCalendar'
import { formatLocalDate, isActiveMember } from './workspacePresentation'
import type { ViewKey, WorkspaceProjection } from './types'

const navItems: {
  key: ViewKey
  label: string
  icon: Parameters<typeof Icon>[0]['name']
}[] = [
  { key: 'today', label: '오늘', icon: 'today' },
  { key: 'roles', label: '역할', icon: 'roles' },
  { key: 'rhythm', label: '일정', icon: 'rhythm' },
  { key: 'memory', label: '기록', icon: 'memory' },
  { key: 'handoff', label: '인수인계', icon: 'handoff' },
  { key: 'records', label: '검색', icon: 'search' },
]

function formatSyncTime(value: number) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}

export function WorkspaceState({
  title,
  description,
  busy = false,
  action,
}: {
  title: string
  description: string
  busy?: boolean
  action?: ReactNode
}) {
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

export function Sidebar({
  workspace,
  calendarDate,
  view,
  onNavigate,
  onSwitchSeason,
  onShare,
  onManageAccess,
}: {
  workspace: WorkspaceProjection
  calendarDate: string
  view: ViewKey
  onNavigate: (key: ViewKey) => void
  onSwitchSeason: () => void
  onShare: () => void
  onManageAccess: () => void
}) {
  const progress = seasonProgress(workspace.season, calendarDate)
  const activeMemberCount = workspace.members.filter(isActiveMember).length
  return (
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark" />BATON</div>
      <div className="workspace-label">현재 팀 <Link className="team-list-link" to="/my-teams">내 팀</Link></div>
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
              && <span className="nav-dot" aria-label="확인할 인수인계 있음" />}
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
            <button type="button" onClick={onManageAccess}>{workspace.team.accountAccessEnabled ? '권한 관리' : '링크 관리'}</button>
          </span>
        </div>
      </div>
    </aside>
  )
}

export function MobileTopbar({
  accountAccessEnabled = false,
  teamName,
  seasonName,
  onSwitchSeason,
  onShare,
  onManageAccess,
}: {
  accountAccessEnabled?: boolean
  teamName: string
  seasonName: string
  onSwitchSeason: () => void
  onShare: () => void
  onManageAccess: () => void
}) {
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
        <Link className="team-list-link" to="/my-teams">내 팀</Link>
        <button type="button" className="mobile-share" onClick={onShare}>공유</button>
        <button type="button" className="mobile-share" onClick={onManageAccess}>{accountAccessEnabled ? '권한 관리' : '링크 관리'}</button>
      </span>
    </header>
  )
}

export function MobileNav({
  view,
  onNavigate,
}: {
  view: ViewKey
  onNavigate: (key: ViewKey) => void
}) {
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
    ? '다른 사람이 수정한 내용을 불러오는 중…'
    : conflictRecoveryStatus === 'failed'
      ? '다른 사람이 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 수정하세요.'
      : failed
        ? '최신 내용을 불러오지 못했습니다. 마지막으로 불러온 내용을 표시합니다.'
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
      aria-label="새 항목 추가를 위한 임시 기록 삭제"
    >
      <div>
        <Icon name="alert" size={18} />
        <span>
          <strong>새 항목을 추가하려면 브라우저의 임시 기록을 지워야 합니다.</strong>
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
          {pending ? '임시 기록 정리 중…' : '임시 기록 정리'}
        </button>
      </div>
    </section>
  )
}
