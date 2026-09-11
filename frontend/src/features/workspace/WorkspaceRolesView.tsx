import { Icon } from '@/shared/ui/Icon'
import {
  ActionableEmpty,
  formatDateRange,
  PageHeader,
  PrimaryButton,
} from './WorkspaceViews'
import {
  getMember,
  latestRoleHandoff,
  memberDisplayName,
} from './workspacePresentation'
import type { Member, Role, RoleHandoff } from './types'

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
  memberManagementDisabled = changesDisabled,
}: {
  roles: Role[]
  roleHandoffs: RoleHandoff[]
  members: Member[]
  selectedRoleId: string
  onSelectRole: (id: string, options?: { opener?: HTMLElement }) => void
  onManageMembers: () => void
  onAddRole: () => void
  onEditRole: (role: Role) => void
  handoffProgress: (id: string) => number
  changesDisabled?: boolean
  memberManagementDisabled?: boolean
}) {
  return (
    <>
      <PageHeader
        title="역할과 담당자"
        description="현재 담당자와 다음 담당자, 담당 업무를 확인하세요."
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
            <PrimaryButton onClick={onAddRole} disabled={changesDisabled}>역할 추가</PrimaryButton>
          </div>
        )}
      />
      {roles.length ? (
        <section className="role-directory">
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
                  <span className="role-main"><span className="role-glyph"><Icon name="roles" size={17} /></span><span><strong>{role.name}<span className="visually-hidden"> 역할 상세 열기</span></strong><small>{role.purpose}</small></span></span>
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
      ) : <ActionableEmpty title="아직 역할이 없어요" description="담당 업무를 역할로 등록하세요." actionLabel="첫 역할 만들기" onAction={onAddRole} disabled={changesDisabled} />}
    </>
  )
}
