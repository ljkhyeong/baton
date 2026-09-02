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
        eyebrow="팀의 책임 지도"
        title="사람이 바뀌어도 역할은 남아요"
        description="현재 담당자와 다음 담당자, 반복되는 책임을 한눈에 확인하세요."
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
          <div className="directory-head"><span>역할과 목적</span><span>현재 담당자</span><span>다음 담당자</span><span>바통 준비</span></div>
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
                  <span className="person-cell">{owner ? <><span className="avatar" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{memberDisplayName(owner)}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></> : <em>담당자 미정</em>}</span>
                  <span className="next-cell">{next ? <><span className="avatar" style={{ background: next.tone }}>{next.initials}</span>{memberDisplayName(next)}</> : <em>아직 미정</em>}</span>
                  <span className="progress-cell"><strong>{roleLocked ? '수락 대기' : `${progress}%`}</strong><span className="thin-progress"><i style={{ width: `${progress}%` }} /></span><Icon name="chevron" size={16} /></span>
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
