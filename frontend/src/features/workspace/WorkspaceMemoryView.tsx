import { ContentChangePanel } from '@/features/content-history/ContentChangePanel'
import type { WorkspaceScope } from './api'
import { DecisionText } from './records/DecisionText'
import {
  ActionableEmpty,
  formatInstant,
  PageHeader,
  PrimaryButton,
} from './WorkspaceViews'
import { isActiveMember } from './workspacePresentation'
import type { Decision, Member, Role } from './types'

export function MemoryView({
  scope,
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
  scope: WorkspaceScope
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
      <PageHeader title="결정 기록" description="결정한 내용과 이유, 검토한 대안을 기록합니다." action={<PrimaryButton onClick={onOpenDecision} disabled={changesDisabled || !canCreateDecision}>결정 남기기</PrimaryButton>} />
      {decisions.length ? (
        <section className="memory-ledger">
          <div className="memory-rule"><span>최근 결정</span><span>{decisions.length}개의 기록</span></div>
          {decisions.map((decision) => (
            <article
              className="decision-entry"
              data-decision-id={decision.id}
              key={decision.id}
              tabIndex={-1}
            >
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
                <div className="decision-reason"><span>이유</span><DecisionText text={decision.reason} format={decision.textFormat} /></div><div className="decision-alternative"><span>검토한 대안</span><DecisionText text={decision.alternative} format={decision.textFormat} /></div>
                <ContentChangePanel scope={scope} kind="DECISION" recordId={decision.id} />
                <div className="decision-tags">{decision.roleIds.map((roleId) => { const role = roles.find((item) => item.id === roleId); return role ? <button type="button" key={roleId} onClick={() => onSelectRole(roleId)}>{role.name}</button> : null })}</div>
              </div>
            </article>
          ))}
        </section>
      ) : (
        <ActionableEmpty
          title={archivedDecisions.length ? '모든 결정이 보관되어 있습니다.' : '아직 결정 기록이 없어요'}
          description={archivedDecisions.length
            ? '아래 보관함에서 다시 필요한 결정을 복원하거나 새 결정을 남겨 보세요.'
            : '무엇을 결정했고 왜 그렇게 정했는지 남겨 보세요.'}
          actionLabel={canCreateDecision
            ? (archivedDecisions.length ? '새 결정 남기기' : '첫 결정 남기기')
            : roles.length ? '구성원 관리' : '첫 역할 만들기'}
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
                <div className="archive-record-body">
                  <strong>{decision.title}</strong>
                  <small>
                    {decision.archivedAt
                      ? `${formatInstant(decision.archivedAt)} 보관`
                      : '보관됨'}
                  </small>
                  <ContentChangePanel scope={scope} kind="DECISION" recordId={decision.id} />
                </div>
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
