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

function BatonNoticeFrame({
  label,
  title,
  detail,
  actionLabel,
  onAction,
}: {
  label: string
  title: string
  detail: string
  actionLabel: string
  onAction: () => void
}) {
  return (
    <section className="baton-notice" aria-label={label}>
      <span className="baton-notice-mark" aria-hidden="true" />
      <span className="baton-notice-copy"><strong>{title}</strong><small>{detail}</small></span>
      <button type="button" className="baton-notice-action" onClick={onAction}>{actionLabel}</button>
    </section>
  )
}

// 팀 전체 보기: 수락을 기다리는 인계를 먼저, 없으면 담당 기간이 가장 가까운 역할을 보여 준다.
export function TeamBatonNotice({
  roles,
  members,
  roleHandoffs,
  calendarDate,
  progress,
  onOpenHandoff,
}: {
  roles: Role[]
  members: Member[]
  roleHandoffs: RoleHandoff[]
  calendarDate: string
  progress: (roleId: string) => number
  onOpenHandoff: (roleId: string) => void
}) {
  const awaitingRole = roles.find((role) => latestRoleHandoff(roleHandoffs, role.id)?.status === 'TRANSFERRED')
  const relay = nearestRelayRole(roles, calendarDate)
  const role = awaitingRole ?? relay?.role
  if (!role) return null
  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const runners = `${owner ? memberDisplayName(owner) : '담당자 미정'} → ${next ? memberDisplayName(next) : '다음 담당자 미정'}`
  return (
    <BatonNoticeFrame
      label="다가오는 인계"
      title={awaitingRole
        ? `${role.name} 바통이 넘어가는 중입니다`
        : `${role.name} ${relayDueCopy(relay?.days ?? 0)}`}
      detail={awaitingRole ? `${runners} · 수락 대기` : `${runners} · 인수인계 준비 ${progress(role.id)}%`}
      actionLabel="인수인계 열기"
      onAction={() => onOpenHandoff(role.id)}
    />
  )
}

// 내 할 일 보기: 나에게 넘어온 바통만 보여 준다.
export function ReceivedBatonNotice({
  role,
  handoff,
  members,
  onOpenHandoff,
}: {
  role: Role
  handoff: RoleHandoff
  members: Member[]
  onOpenHandoff: (roleId: string) => void
}) {
  const from = getMember(members, handoff.fromMemberId)
  return (
    <BatonNoticeFrame
      label={`${role.name} 바통 도착`}
      title={`${role.name} 바통이 도착했습니다`}
      detail={`${from ? `${memberDisplayName(from)}님이 넘겼습니다. ` : ''}확인하고 수락해 주세요.`}
      actionLabel="확인하기"
      onAction={() => onOpenHandoff(role.id)}
    />
  )
}
