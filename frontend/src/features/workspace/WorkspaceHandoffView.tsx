import { useId, useRef } from 'react'
import type { KeyboardEvent } from 'react'
import { Icon } from '@/shared/ui/Icon'
import {
  ActionableEmpty,
  formatDateRange,
  formatInstant,
  PageHeader,
  PrimaryButton,
} from './WorkspaceViews'
import {
  categoryCopy,
  formatLocalDate,
  getMember,
  isActiveMember,
  latestRoleHandoff,
} from './workspacePresentation'
import { daysUntil } from './seasonCalendar'
import type {
  HandoffItem,
  Member,
  Role,
  RoleHandoff,
  Season,
} from './types'

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
    return <><PageHeader eyebrow="역할 인수인계" title="첫 역할부터 만들어 주세요" description="역할을 만들면 담당 업무와 자료를 인수인계 문서로 정리할 수 있습니다." /><ActionableEmpty title="넘겨줄 역할이 아직 없어요" description="담당할 업무를 역할로 등록하세요." actionLabel="첫 역할 만들기" onAction={onAddRole} disabled={changesDisabled} /></>
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
  const selectedProgress = progress(selected.id)
  const selectedHandoff = latestRoleHandoff(roleHandoffs, selected.id)
  const selectedChangesDisabled = changesDisabled || selectedHandoff?.status === 'TRANSFERRED'
  const next = getMember(
    members,
    selectedHandoff && selectedHandoff.status !== 'CANCELLED'
      ? selectedHandoff.toMemberId
      : selected.nextMemberId,
  )
  const handoffSummaryTitle = selectedHandoff?.status === 'ACCEPTED'
    ? `${next?.name ?? '다음 담당자'}님이 이어받은 인수인계`
    : selectedHandoff?.status === 'CANCELLED'
      ? '다음 전달을 다시 준비하는 인수인계'
      : next
        ? isActiveMember(next)
          ? `${next.name}님에게 넘길 인수인계`
          : `${next.name}님은 활동을 종료했어요`
        : '다음 담당자를 기다리는 인수인계'
  const remainingDays = daysUntil(season.endDate, calendarDate)
  return (
    <>
      <PageHeader
        eyebrow={remainingDays >= 0 ? `시즌 종료까지 ${remainingDays}일` : `${formatLocalDate(season.endDate)} 시즌 종료`}
        title="역할 인수인계"
        description="담당 업무와 참고 자료를 정리해 다음 담당자에게 전달합니다."
        action={<div className="action-cluster"><button type="button" className="secondary-button" disabled={selectedChangesDisabled} onClick={onAddItem}><Icon name="plus" size={15} /> 항목 추가</button><PrimaryButton onClick={onPreview} icon={false}>인수인계 문서 미리보기</PrimaryButton></div>}
      />
      <div className="handoff-role-tabs" role="tablist" aria-label="역할별 인수인계" aria-orientation="horizontal">
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
        <div className="handoff-summary"><span className="section-kicker">{selected.name}</span><h2>{handoffSummaryTitle}</h2><p>{next && !isActiveMember(next) ? '활동 중인 다음 담당자를 정한 뒤 인수인계를 이어 주세요.' : selected.purpose}</p><div className="handoff-score"><strong>{selectedProgress}%</strong><span><i style={{ width: `${selectedProgress}%` }} /></span><small>{items.filter((item) => item.completed).length}/{items.length} 항목 완료</small></div></div>
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
                      ? '최근 인수인계 수락 완료'
                      : selectedHandoff?.status === 'CANCELLED'
                        ? '최근 인수인계 취소'
                        : '전달 전'}
                  </span>
                  <strong>
                    {selectedHandoff?.status === 'ACCEPTED'
                      ? `${next?.name ?? '다음 담당자'}님의 수락을 기록했어요`
                      : '다음 담당자와 역할 기간을 정해 준비를 시작하세요'}
                  </strong>
                  <p>
                    {selected.currentMemberId && selected.assignmentStartDate
                      ? '준비 단계에서는 인수인계 문서를 계속 다듬을 수 있고, 전달한 뒤에는 수락 또는 취소까지 내용이 잠깁니다.'
                      : '인수인계 준비를 시작하려면 역할의 현재 담당자와 담당 시작일을 먼저 정해야 합니다.'}
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
                    인수인계 준비 시작
                  </button>
                </>
              ) : selectedHandoff.status === 'PREPARING' ? (
                <>
                  <span className="handoff-state-label">준비 중</span>
                  <strong>{next?.name ?? '다음 담당자'}님에게 전달할 인수인계를 검토하세요</strong>
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
                      인수인계 전달 검토
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
                  <p>전달한 인수인계 문서는 수락하거나 취소하기 전까지 역할·체크리스트·자료를 수정할 수 없습니다.</p>
                  <dl className="handoff-transfer-snapshot" aria-label="전달 시점 체크리스트와 자료 현황">
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
                      인수인계 수락
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
              <div
                className={`handoff-item-row ${item.completed ? 'done' : ''}`}
                data-handoff-item-id={item.id}
                key={item.id}
                tabIndex={-1}
              >
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
              title={selectedArchivedItems.length ? '현재 체크리스트가 비어 있어요' : '아직 인수인계 항목이 없어요'}
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
          <summary>보관한 인수인계 {selectedArchivedItems.length}개</summary>
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
