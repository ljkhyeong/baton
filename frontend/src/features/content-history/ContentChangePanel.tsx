import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { operations } from '@/generated/api'
import type { WorkspaceScope } from '@/features/workspace/api'
import { workspaceKeys } from '@/features/workspace/queries'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isSameUuid, isUuid } from '@/shared/api/responseValidation'
import './content-history.scss'

type History = operations['getDecisionChanges']['responses'][200]['content']['application/json']
type Kind = 'DECISION' | 'ROLE_RESOURCE'
function getHistory(scope: WorkspaceScope, kind: Kind, recordId: string) {
  const collection = kind === 'DECISION' ? 'decisions' : 'role-resources'
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/${collection}/${recordId}/changes`, {
    method: 'GET', headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: value => {
      if (!isJsonObject(value) || !isSameUuid(value.teamId, scope.teamId) || !isSameUuid(value.seasonId, scope.seasonId)
        || !isSameUuid(value.recordId, recordId) || value.recordKind !== kind || !Array.isArray(value.changes)
        || value.changes.length > 50 || !value.changes.every((change: unknown) => isJsonObject(change)
          && isUuid(change.id) && (change.actorAccountId == null || isUuid(change.actorAccountId))
          && typeof change.actorName === 'string' && isInstant(change.changedAt) && Array.isArray(change.fields)
          && change.fields.every((field: unknown) => isJsonObject(field) && typeof field.fieldName === 'string'
            && (field.beforeValue == null || typeof field.beforeValue === 'string')
            && (field.afterValue == null || typeof field.afterValue === 'string')))) {
        throw new Error('수정 이력을 읽지 못했습니다.')
      }
      return value as History
    },
  })
}
export function ContentChangePanel({ scope, kind, recordId }: { scope: WorkspaceScope; kind: Kind; recordId: string }) {
  const [open, setOpen] = useState(false)
  const history = useQuery({ queryKey: [...workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey, scope.accountId),
    'content-changes', kind, recordId], queryFn: () => getHistory(scope, kind, recordId), enabled: open })
  return <details className="content-history" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>수정 이력</summary>
    {open && <div>
      <p>최근 50건의 수정·보관·복원을 표시합니다. 이력 기능 도입 전의 수정은 포함하지 않습니다.</p>
      {history.isPending ? <p role="status">수정 이력을 불러오고 있습니다.</p>
        : history.isError ? <p role="alert">{history.error.message} <button type="button" onClick={() => void history.refetch()}>다시 불러오기</button></p>
          : history.data.changes.length === 0 ? <p>아직 남겨진 수정 이력이 없습니다.</p>
            : <ol>{history.data.changes.map(change => <li key={change.id}>
              <strong>{change.actorName} · {formatInstant(change.changedAt)}</strong>
              <dl>{change.fields.map((field, index) => <div key={index}>
                <dt>{field.fieldName}</dt><dd><span>변경 전</span><pre>{field.beforeValue || '값 없음'}</pre></dd>
                <dd><span>변경 후</span><pre>{field.afterValue || '값 없음'}</pre></dd>
              </div>)}</dl>
            </li>)}</ol>}
    </div>}
  </details>
}
