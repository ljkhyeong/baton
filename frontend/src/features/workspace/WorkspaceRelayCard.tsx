import { Icon } from '@/shared/ui/Icon'
import { daysUntil } from './seasonCalendar'
import { getMember, latestRoleHandoff, memberDisplayName } from './workspacePresentation'
import type { Member, Role, RoleHandoff } from './types'

function relayDueCopy(days: number) {
  if (days > 0) return `인계까지 D-${days}`
  if (days === 0) return '오늘 인계'
  return `인계일 ${-days}일 지남`
}

// 담당 기간이 끝나는 날이 가장 가까운 역할을 고른다. 다가오는 날을 지난 날보다 먼저 본다.
export function nearestRelayRole(roles: Role[], today: string) {
  return roles
    .flatMap((role) => role.currentMemberId && role.assignmentEndDate
      ? [{ role, days: daysUntil(role.assignmentEndDate, today) }]
      : [])
    .sort((left, right) => Number(left.days < 0) - Number(right.days < 0)
      || Math.abs(left.days) - Math.abs(right.days))[0]
}

export function RelayCard({
  role,
  days,
  members,
  roleHandoffs,
  progress,
  onOpenHandoff,
}: {
  role: Role
  days: number
  members: Member[]
  roleHandoffs: RoleHandoff[]
  progress: number
  onOpenHandoff: (roleId: string) => void
}) {
  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const awaitingAcceptance = latestRoleHandoff(roleHandoffs, role.id)?.status === 'TRANSFERRED'
  return (
    <section className="relay-card" aria-label="인수인계 릴레이">
      <div className="relay-card-head">
        <strong>{role.name} 릴레이</strong>
        <span className={days < 0 ? 'relay-due overdue' : 'relay-due'}>{relayDueCopy(days)}</span>
      </div>
      <div className="relay-track" aria-hidden="true">
        <span className="relay-runner" style={owner ? { background: owner.tone } : undefined}>{owner?.initials ?? '?'}</span>
        <span className="relay-lane"><i style={{ width: `${progress}%` }} /></span>
        <span className="relay-runner" style={next ? { background: next.tone } : undefined}>{next?.initials ?? '?'}</span>
      </div>
      <div className="relay-card-foot">
        <span>{owner ? `${memberDisplayName(owner)} 담당` : '담당자 미정'}</span>
        <span>{awaitingAcceptance ? '수락 대기' : `인수인계 준비 ${progress}%`}</span>
        <span>{next ? `${memberDisplayName(next)}에게` : '다음 담당자 미정'}</span>
      </div>
      <button type="button" className="text-button relay-open" onClick={() => onOpenHandoff(role.id)}>
        인수인계 열기 <Icon name="arrow" size={14} />
      </button>
    </section>
  )
}
