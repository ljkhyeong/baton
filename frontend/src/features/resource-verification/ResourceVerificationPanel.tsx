import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import type { WorkspaceScope } from '@/features/workspace/api'
import { workspaceKeys } from '@/features/workspace/queries'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
import { ResourceReviewSchedulePanel } from './ResourceReviewSchedulePanel'
import { getVerificationHistory, verifyResource, reviewScheduleKey, dueReviewsKey, type VerifyResourceRequest } from './api'
import './resource-verification.scss'

export function ResourceVerificationPanel({ scope, resourceId, disabled, onManageMembership }: {
  scope: WorkspaceScope; resourceId: string; disabled: boolean; onManageMembership: () => void
}) {
  const [open, setOpen] = useState(false)
  return <details className="resource-verification" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>자료 확인 기록</summary>
    {open && <VerificationContent key={`${scope.teamId}:${scope.seasonId}:${resourceId}:${scope.accessKey}`}
      scope={scope} resourceId={resourceId} disabled={disabled} onManageMembership={onManageMembership} />}
  </details>
}

function VerificationContent({ scope, resourceId, disabled, onManageMembership }: {
  scope: WorkspaceScope; resourceId: string; disabled: boolean; onManageMembership: () => void
}) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const membership = useCurrentAccountMembership({ ...scope, accountId })
  const client = useQueryClient()
  const key = [...workspaceKeys.team(scope.teamId), 'seasons', scope.seasonId, 'resource-verifications', resourceId, scope.accessKey, accountId]
  const history = useQuery({ queryKey: key, queryFn: () => getVerificationHistory(scope, resourceId) })
  const [status, setStatus] = useState<VerifyResourceRequest['status']>('CONFIRMED')
  const [note, setNote] = useState('')
  const mutation = useMutation({
    mutationFn: () => verifyResource(scope, resourceId, {
      expectedAccountId: accountId, resourceVersion: history.data!.resourceVersion, status, note: note.trim() || undefined,
    }),
    onSuccess: data => { client.setQueryData(key, data); setNote(''); void client.invalidateQueries({ queryKey: reviewScheduleKey(scope, resourceId) }); void client.invalidateQueries({ queryKey: dueReviewsKey(scope) }) },
    onError: () => { void history.refetch() },
  })
  const latest = history.data?.verifications[0]
  return <div>
    <p>링크가 열리고 내용이 맞는지 확인한 뒤 결과를 남기세요.</p>
    {history.isPending ? <p role="status">확인 기록을 불러오고 있습니다.</p>
      : history.isError ? <p role="alert">{history.error.message} <button type="button" onClick={() => void history.refetch()}>다시 불러오기</button></p>
        : <>
          <strong>{!latest ? '아직 확인한 기록이 없습니다.' : !latest.current ? '자료가 변경되어 재확인이 필요합니다.'
            : latest.status === 'CONFIRMED' ? '사용할 수 있는 자료로 확인했습니다.' : '자료 수정이 필요합니다.'}</strong>
          <ol>{history.data.verifications.map(row => <li key={row.id}>
            <span>{row.memberName} · {formatInstant(row.verifiedAt)}</span>
            <span>{row.status === 'CONFIRMED' ? '사용 가능' : '수정 필요'}{!row.current && ' · 변경 전 확인 기록'}</span>
            {row.note && <p>{row.note}</p>}
          </li>)}</ol>
        </>}
    <ResourceReviewSchedulePanel scope={scope} resourceId={resourceId} accountId={accountId} editable={!disabled && Boolean(membership.data?.claimed)} />
    {!disabled && (membership.data?.claimed ? <form onSubmit={event => {
      event.preventDefault()
      if (!history.data || mutation.isPending || !accountId) return
      mutation.mutate()
    }}>
      <label>확인 결과<select value={status} onChange={event => setStatus(event.target.value as VerifyResourceRequest['status'])}>
        <option value="CONFIRMED">사용 가능</option><option value="NEEDS_UPDATE">수정 필요</option>
      </select></label>
      <label>확인 메모<textarea maxLength={500} rows={2} value={note} onChange={event => setNote(event.target.value)} /></label>
      <button type="submit" disabled={mutation.isPending || !history.data || history.isError || history.isFetching}>
        {mutation.isPending ? '확인 기록 저장 중…' : '내 확인 기록 남기기'}
      </button>
    </form> : <button type="button" onClick={onManageMembership}>내 이름 선택 후 확인 기록 남기기</button>)}
    {mutation.isError && <p role="alert">{mutation.error.message} 최신 자료를 다시 확인한 뒤 기록해 주세요.</p>}
    {mutation.isSuccess && <p role="status">확인 기록을 저장했습니다.</p>}
  </div>
}
