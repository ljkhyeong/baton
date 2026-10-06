import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import '@/features/team-access/my-teams.scss'
import { Icon } from '@/shared/ui/Icon'
import type { WorkspaceConflictRecoveryStatus } from './useWorkspaceConflictRecovery'
import { PageHeader, PrimaryButton } from './WorkspaceViews'
import type { ViewKey, WorkspaceProjection } from './types'

const navItems: {
  key: ViewKey
  label: string
  icon: Parameters<typeof Icon>[0]['name']
}[] = [
  { key: 'today', label: '할 일', icon: 'today' },
  { key: 'roles', label: '역할', icon: 'roles' },
  { key: 'rhythm', label: '일정', icon: 'rhythm' },
  { key: 'memory', label: '기록', icon: 'memory' },
]

const recordViews: { key: ViewKey; label: string }[] = [
  { key: 'memory', label: '결정' },
  { key: 'records', label: '검색' },
]

// 기록 검색은 기록 메뉴, 인수인계는 역할 메뉴 안의 화면으로 둔다.
function navKey(view: ViewKey): ViewKey {
  if (view === 'records') return 'memory'
  if (view === 'handoff') return 'roles'
  return view
}

function hasHandoffToCheck(workspace: WorkspaceProjection) {
  return workspace.roleHandoffs.some((handoff) => handoff.status === 'TRANSFERRED')
    || workspace.handoffItems.some((candidate) => !candidate.completed)
}

// 점은 버튼 이름에 섞이지 않게 숨기고, 설명 문구는 버튼 밖에 둔 뒤 aria-describedby로 연결한다.
function HandoffDotDescription({ id }: { id: string }) {
  return <span id={id} className="visually-hidden">확인할 인수인계 있음</span>
}

function formatSyncTime(value: number) {
  return new Intl.DateTimeFormat('ko-KR', { timeStyle: 'short' }).format(new Date(value))
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

export function WorkspaceHeader({
  workspace,
  view,
  onNavigate,
  onSwitchSeason,
  onShare,
  onManageAccess,
}: {
  workspace: WorkspaceProjection
  view: ViewKey
  onNavigate: (key: ViewKey) => void
  onSwitchSeason: () => void
  onShare: () => void
  onManageAccess: () => void
}) {
  const handoffWaiting = hasHandoffToCheck(workspace)
  return (
    <header className="workspace-header">
      <span className="brand"><span className="brand-mark" aria-hidden="true" /><span className="visually-hidden">BATON</span></span>
      <button
        type="button"
        className="workspace-switcher"
        aria-label={`현재 시즌 ${workspace.season.name}. 시즌 전환`}
        onClick={onSwitchSeason}
      >
        <strong>{workspace.team.name}</strong><small>{workspace.season.name}</small>
        <Icon name="chevron" size={15} />
      </button>
      <nav className="header-nav" aria-label="주 메뉴">
        {navItems.map((item) => (
          <button
            type="button"
            className={navKey(view) === item.key ? 'active' : ''}
            key={item.key}
            aria-current={navKey(view) === item.key ? 'page' : undefined}
            aria-describedby={item.key === 'roles' && handoffWaiting ? 'header-handoff-dot' : undefined}
            onClick={() => onNavigate(item.key)}
          >
            {item.label}
            {item.key === 'roles' && handoffWaiting && <span className="nav-dot" aria-hidden="true" />}
          </button>
        ))}
        {handoffWaiting && <HandoffDotDescription id="header-handoff-dot" />}
      </nav>
      <span className="header-actions">
        <Link className="team-list-link" to="/my-teams">내 팀</Link>
        <button type="button" onClick={onShare} title="작업 공간 공유">공유</button>
        <button type="button" onClick={onManageAccess}>{workspace.team.accountAccessEnabled ? '권한 관리' : '링크 관리'}</button>
      </span>
    </header>
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
        <span className="mobile-team-copy"><strong>{teamName}</strong><small>{seasonName}</small></span>
        <Icon name="chevron" size={15} />
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
  workspace,
  view,
  onNavigate,
}: {
  workspace: WorkspaceProjection
  view: ViewKey
  onNavigate: (key: ViewKey) => void
}) {
  const handoffWaiting = hasHandoffToCheck(workspace)
  return (
    <nav className="mobile-nav" aria-label="모바일 주 메뉴">
      {navItems.map((item) => (
        <button
          type="button"
          className={navKey(view) === item.key ? 'active' : ''}
          key={item.key}
          aria-current={navKey(view) === item.key ? 'page' : undefined}
          aria-describedby={item.key === 'roles' && handoffWaiting ? 'mobile-handoff-dot' : undefined}
          onClick={() => onNavigate(item.key)}
        >
          <Icon name={item.icon} size={20} /><span>{item.label}</span>
          {item.key === 'roles' && handoffWaiting && <span className="nav-dot" aria-hidden="true" />}
        </button>
      ))}
      {handoffWaiting && <HandoffDotDescription id="mobile-handoff-dot" />}
    </nav>
  )
}

export function RecordsHeader({
  view,
  decisionCount,
  createDisabled,
  onOpenDecision,
  onNavigate,
}: {
  view: ViewKey
  decisionCount: number
  createDisabled: boolean
  onOpenDecision: () => void
  onNavigate: (key: ViewKey) => void
}) {
  return (
    <>
      <PageHeader
        eyebrow={`결정 ${decisionCount}개`}
        title="기록"
        action={view === 'memory'
          ? <PrimaryButton onClick={onOpenDecision} disabled={createDisabled}>결정 남기기</PrimaryButton>
          : undefined}
      />
      <RecordsSwitch view={view} onNavigate={onNavigate} />
    </>
  )
}

function RecordsSwitch({
  view,
  onNavigate,
}: {
  view: ViewKey
  onNavigate: (key: ViewKey) => void
}) {
  return (
    <nav className="records-switch" aria-label="기록 보기">
      {recordViews.map((item) => (
        <button
          type="button"
          className={view === item.key ? 'active' : ''}
          key={item.key}
          aria-current={view === item.key ? 'page' : undefined}
          onClick={() => onNavigate(item.key)}
        >
          {item.label}
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
      aria-label="임시 기록 삭제 필요"
    >
      <div>
        <Icon name="alert" size={18} />
        <span>
          <strong>새 항목을 추가하려면 남아 있는 임시 기록을 먼저 삭제하세요.</strong>
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
          {pending ? '임시 기록 삭제 중…' : '임시 기록 삭제'}
        </button>
      </div>
    </section>
  )
}
