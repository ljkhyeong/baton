import { DecisionText } from './records/DecisionText'
import { useState } from 'react'
import type { WorkspaceScope } from './api'
import { useWorkspaceQuery } from './queries'
import { FormError } from './WorkspaceModalPrimitives'
import { handoffCategoryLabel } from './records/recordSearch'
import type { RoleResource } from './types'
import './previous-role-records.scss'

type PreviousRoleRecordsProps = {
  scope: WorkspaceScope
  previousSeasonId: string
  previousRoleId: string
  changesDisabled: boolean
  onCopyResource: (resource: RoleResource) => void
}

export function PreviousRoleRecords(props: PreviousRoleRecordsProps) {
  const [expanded, setExpanded] = useState(false)
  return (
    <details className="previous-role-records" onToggle={(event) => setExpanded(event.currentTarget.open)}>
      <summary>이전 시즌 기록 보기</summary>
      {expanded && <PreviousRoleRecordContents {...props} />}
    </details>
  )
}

function PreviousRoleRecordContents({
  scope,
  previousSeasonId,
  previousRoleId,
  changesDisabled,
  onCopyResource,
}: PreviousRoleRecordsProps) {
  const query = useWorkspaceQuery({ ...scope, seasonId: previousSeasonId })
  if (query.isError) {
    return <div role="alert">
      <p>이전 시즌 기록을 불러오지 못했습니다.</p>
      <FormError error={query.error} />
      <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>다시 불러오기</button>
    </div>
  }
  if (!query.data) return <p role="status">이전 시즌 기록을 불러오고 있습니다.</p>

  const previous = query.data
  const role = previous.roles.find((candidate) => candidate.id === previousRoleId)
  if (!role) return <p role="alert">이전 시즌에서 연결된 역할을 찾을 수 없습니다.</p>

  const resources = previous.resources.filter((resource) => resource.roleId === role.id)
  const decisions = previous.decisions.filter((decision) => decision.roleIds.includes(role.id))
  const items = previous.handoffItems.filter((item) => item.roleId === role.id)
  return (
    <div>
      <p><strong>{previous.season.name} · {role.name}</strong></p>
      <p className="previous-role-copy">{role.purpose}</p>
      {role.responsibilities.length > 0 && <p className="previous-role-copy">당시 책임: {role.responsibilities.join(', ')}</p>}
      {role.risk && <p className="previous-role-copy">주의할 점: {role.risk}</p>}
      <p>이전 시즌의 기록입니다. 자료를 선택하면 현재 시즌의 추가 양식으로 이어집니다.</p>
      <h3>참고 자료</h3>
      {resources.length ? <ul>
        {resources.map((resource) => <li key={resource.id}>
          <a href={resource.url} target="_blank" rel="noopener noreferrer">{resource.title}</a>
          {resource.archivedAt && <small>보관한 자료</small>}
          {resource.description && <p className="previous-role-copy">{resource.description}</p>}
          <button type="button" disabled={changesDisabled || query.isFetching}
            aria-label={`${resource.title} 자료를 현재 시즌에 연결`}
            onClick={() => onCopyResource(resource)}>현재 시즌에 연결</button>
        </li>)}
      </ul> : <p>이 역할에 남긴 자료가 없습니다.</p>}
      <h3>결정과 이유</h3>
      {decisions.length ? <ul>
        {decisions.map((decision) => <li key={decision.id}>
          <strong>{decision.title}</strong>
          {decision.archivedAt && <small>보관한 결정</small>}
          <DecisionText text={decision.reason} format={decision.textFormat} />
          {decision.alternative && <div><small>검토한 다른 선택</small><DecisionText text={decision.alternative} format={decision.textFormat} /></div>}
          <small>작성자: {decision.authorName}</small>
        </li>)}
      </ul> : <p>이 역할에 연결한 결정이 없습니다.</p>}
      <h3>인수인계 항목</h3>
      {items.length ? <ul>
        {items.map((item) => <li key={item.id}>
          <strong>{item.label}</strong>
          <small>{handoffCategoryLabel[item.category]} · {item.completed ? '준비 완료' : '미완료'}{item.archivedAt ? ' · 보관' : ''}</small>
        </li>)}
      </ul> : <p>이 역할에 남긴 인수인계 항목이 없습니다.</p>}
    </div>
  )
}
