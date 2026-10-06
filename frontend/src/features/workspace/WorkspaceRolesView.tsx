import { formatInstant } from '@/shared/lib/dateTimeFormat'
import { Icon } from '@/shared/ui/Icon'
import { ReceivedBatonNotice } from './WorkspaceBatonNotice'
import {
  ActionableEmpty,
  formatDateRange,
  PageHeader,
  PrimaryButton,
} from './WorkspaceViews'
import {
  getMember,
  isActiveMember,
  latestRoleHandoff,
  memberDisplayName,
} from './workspacePresentation'
import type { Member, Role, RoleHandoff, SeasonRound } from './types'

function MyRoles({
  me,
  roles,
  roleHandoffs,
  members,
  rounds,
  timeZone,
  handoffProgress,
  onOpenHandoff,
}: {
  me: Member
  roles: Role[]
  roleHandoffs: RoleHandoff[]
  members: Member[]
  rounds: SeasonRound[]
  timeZone: string
  handoffProgress: (id: string) => number
  onOpenHandoff: (roleId: string) => void
}) {
  const myRoles = roles.filter((role) => role.currentMemberId === me.id)
  const receiving = roles.flatMap((role) => {
    const handoff = latestRoleHandoff(roleHandoffs, role.id)
    return handoff?.status === 'TRANSFERRED' && handoff.toMemberId === me.id ? [{ role, handoff }] : []
  })
  if (!myRoles.length && !receiving.length) return null
  const nextTask = (roleId: string) => rounds
    .flatMap((round) => round.routineExecutions)
    .filter((execution) => execution.ownerRoleId === roleId && execution.status !== 'DONE')
    .sort((left, right) => (left.deadlineAt ?? 'z').localeCompare(right.deadlineAt ?? 'z'))[0]
  return (
    <section className="my-roles" aria-label="내 역할">
      {receiving.map(({ role, handoff }) => (
        <ReceivedBatonNotice key={handoff.id} role={role} handoff={handoff} members={members} onOpenHandoff={onOpenHandoff} />
      ))}
      {myRoles.map((role) => {
        const task = nextTask(role.id)
        const next = getMember(members, role.nextMemberId)
        const handoff = latestRoleHandoff(roleHandoffs, role.id)
        const progress = handoffProgress(role.id)
        return (
          <article className="my-role" key={role.id} aria-labelledby={`my-role-${role.id}`}>
            <div className="my-role-head">
              <h2 id={`my-role-${role.id}`}>{role.name}</h2>
              <span>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</span>
            </div>
            <p className="my-role-line">
              <span>다음 할 일</span>
              <strong>{task ? `${task.title}${task.deadlineAt ? ` · ${formatInstant(task.deadlineAt, timeZone)}` : ''}` : '남은 업무가 없습니다'}</strong>
            </p>
            <button type="button" className="my-role-handoff" onClick={() => onOpenHandoff(role.id)}>
              <span>넘겨줄 준비</span>
              <strong>
                {next ? `${memberDisplayName(next)}님에게` : '다음 담당자 미정'}
                {' · '}
                {handoff?.status === 'TRANSFERRED' ? '수락 대기' : `준비 ${progress}%`}
              </strong>
              <span className="thin-progress"><i style={{ width: `${progress}%` }} /></span>
            </button>
          </article>
        )
      })}
    </section>
  )
}

export function RolesView({
  me,
  roles,
  roleHandoffs,
  members,
  rounds,
  timeZone,
  selectedRoleId,
  onSelectRole,
  onManageMembers,
  onAddRole,
  onEditRole,
  onOpenHandoff,
  handoffProgress,
  changesDisabled = false,
  memberManagementDisabled = changesDisabled,
}: {
  me?: Member
  roles: Role[]
  roleHandoffs: RoleHandoff[]
  members: Member[]
  rounds: SeasonRound[]
  timeZone: string
  selectedRoleId: string
  onSelectRole: (id: string, options?: { opener?: HTMLElement }) => void
  onManageMembers: () => void
  onAddRole: () => void
  onEditRole: (role: Role) => void
  onOpenHandoff: (roleId?: string) => void
  handoffProgress: (id: string) => number
  changesDisabled?: boolean
  memberManagementDisabled?: boolean
}) {
  return (
    <>
      <PageHeader
        eyebrow={`역할 ${roles.length}개 · 활동 중인 구성원 ${members.filter(isActiveMember).length}명`}
        title="역할과 담당자"
        action={(
          <div className="action-cluster">
            <button
              type="button"
              className="secondary-button"
              onClick={onManageMembers}
              disabled={memberManagementDisabled}
            >
              <Icon name="roles" size={15} /> 구성원 관리
            </button>
            <button type="button" className="secondary-button" onClick={() => onOpenHandoff()}>
              <Icon name="handoff" size={15} /> 인수인계
            </button>
            <PrimaryButton onClick={onAddRole} disabled={changesDisabled}>역할 추가</PrimaryButton>
          </div>
        )}
      />
      {me && (
        <MyRoles
          me={me}
          roles={roles}
          roleHandoffs={roleHandoffs}
          members={members}
          rounds={rounds}
          timeZone={timeZone}
          handoffProgress={handoffProgress}
          onOpenHandoff={onOpenHandoff}
        />
      )}
      {roles.length ? (
        <section className="role-directory" aria-label="모든 역할">
          <div className="directory-head"><span>역할과 목적</span><span>현재 담당자</span><span>다음 담당자</span><span>인수인계 준비</span></div>
          {roles.map((role) => {
            const owner = getMember(members, role.currentMemberId)
            const next = getMember(members, role.nextMemberId)
            const handoff = latestRoleHandoff(roleHandoffs, role.id)
            const roleLocked = handoff?.status === 'TRANSFERRED'
            const progress = handoffProgress(role.id)
            return (
              <div className={`role-row ${selectedRoleId === role.id ? 'selected' : ''}`} key={role.id}>
                <button
                  type="button"
                  className="role-row-open"
                  onClick={(event) => onSelectRole(role.id, { opener: event.currentTarget })}
                >
                  <span className="role-main"><span><strong>{role.name}<span className="visually-hidden"> 역할 상세 열기</span></strong><small>{role.purpose}</small></span></span>
                  <span className="person-cell">
                    <span className="role-person-label">현재</span>
                    {owner ? <><span className="avatar" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{memberDisplayName(owner)}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></> : <em>담당자 미정</em>}
                  </span>
                  <span className="next-cell">
                    <span className="role-person-label">다음</span>
                    {next ? <><span className="avatar" style={{ background: next.tone }}>{next.initials}</span><span>{memberDisplayName(next)}</span></> : <em>아직 미정</em>}
                  </span>
                  <span className="progress-cell"><span className="role-progress-label">인수인계 준비</span><strong>{roleLocked ? '수락 대기' : `${progress}%`}</strong><span className="thin-progress"><i style={{ width: `${progress}%` }} /></span><Icon name="chevron" size={16} /></span>
                </button>
                <button type="button" className="inline-edit-button" aria-label={`${role.name} 역할 수정`} disabled={changesDisabled || roleLocked} onClick={() => onEditRole(role)}>수정</button>
              </div>
            )
          })}
        </section>
      ) : <ActionableEmpty icon="roles" title="아직 역할이 없습니다" description="담당 업무를 역할로 등록하세요." actionLabel="첫 역할 만들기" onAction={onAddRole} disabled={changesDisabled} />}
    </>
  )
}
