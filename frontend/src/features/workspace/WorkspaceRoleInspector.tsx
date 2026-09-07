import { useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { RoundRoomResourceActions } from '@/features/round/RoundRoomResourceActions'
import { Icon } from '@/shared/ui/Icon'
import { ContentChangePanel } from '@/features/content-history/ContentChangePanel'
import { ResourceVerificationPanel } from '@/features/resource-verification/ResourceVerificationPanel'
import { PreviousRoleRecords } from './PreviousRoleRecords'
import type { WorkspaceScope } from './api'
import { formatDateRange, formatInstant } from './WorkspaceViews'
import { getMember, memberDisplayName } from './workspacePresentation'
import type {
  Decision,
  Member,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
} from './types'

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
  previousSeasonId,
  onCopyPreviousResource,
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
  previousSeasonId: string | null
  onCopyPreviousResource: (resource: RoleResource) => void
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
      <button ref={closeButtonRef} type="button" className="inspector-close" onClick={onClose} aria-label="상세 닫기"><Icon name="close" /></button><div className="inspector-topline"><span>선택한 역할</span></div><h2>{role.name}</h2><p className="inspector-purpose">{role.purpose}</p>
      <div className="owner-block"><span className="block-label">현재 담당자</span>{owner ? <div><span className="avatar avatar-large" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{memberDisplayName(owner)}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></div> : <p className="muted-copy">현재 담당자가 정해지지 않았어요.</p>}</div>
      {role.risk && <div className="risk-note"><Icon name="alert" size={17} /><span><strong>주의사항</strong>{role.risk}</span></div>}
      <div className="inspector-section"><span className="block-label">담당 업무</span><ul>{role.responsibilities.length ? role.responsibilities.map((item) => <li key={item}><Icon name="check" size={13} />{item}</li>) : <li className="muted">등록된 담당 업무가 없습니다.</li>}</ul></div>
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
                <ContentChangePanel scope={roundRoomScope} kind="ROLE_RESOURCE" recordId={resource.id} />
                <ResourceVerificationPanel scope={roundRoomScope} resourceId={resource.id}
                  disabled={changesDisabled} onManageMembership={onManageMembership} />
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
                  <ContentChangePanel scope={roundRoomScope} kind="ROLE_RESOURCE" recordId={resource.id} />
                </li>
              ))}
            </ul>
          </details>
        )}
      </div>
      {relatedRoutine && <div className="inspector-section next-event"><span className="block-label">담당 반복 업무</span><strong>{relatedRoutine.title}</strong><small>{relatedRoutine.dueLabel} · {relatedRoutine.detail}</small></div>}
      {relatedDecision && <div className="inspector-section linked-decision"><span className="block-label">관련 결정</span><p>“{relatedDecision.title}”</p><small>{formatInstant(relatedDecision.createdAt)}</small></div>}
      {previousSeasonId && role.previousRoleId && (
        <PreviousRoleRecords
          key={`${roundRoomScope.teamId}:${roundRoomScope.seasonId}:${role.id}`}
          scope={roundRoomScope}
          previousSeasonId={previousSeasonId}
          previousRoleId={role.previousRoleId}
          changesDisabled={changesDisabled}
          onCopyResource={onCopyPreviousResource}
        />
      )}
      <div className="inspector-handoff"><div><span className="block-label">체크리스트 완료율</span><strong>{progress}%</strong></div><div className="thin-progress"><i style={{ width: `${progress}%` }} /></div><p>{handoff?.status === 'TRANSFERRED' ? '수락 대기 중입니다. 수락 또는 취소 전까지 역할과 인수인계 문서를 수정할 수 없습니다.' : next ? `다음 담당자 · ${memberDisplayName(next)}` : '다음 담당자가 아직 정해지지 않았어요.'}</p><button type="button" onClick={onOpenHandoff}>{handoff?.status === 'TRANSFERRED' ? '인수인계 수락 확인하기' : '인수인계 보기'} <Icon name="arrow" size={15} /></button></div>
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
