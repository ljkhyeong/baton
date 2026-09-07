import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { WorkspaceScope } from '@/features/workspace/api'
import { formatLocalDate } from '@/features/workspace/workspacePresentation'
import { getReviewSchedule, configureReviewSchedule, reviewScheduleKey, dueReviewsKey } from './api'

export function ResourceReviewSchedulePanel({ scope, resourceId, accountId, editable }: {
  scope: WorkspaceScope; resourceId: string; accountId: string; editable: boolean
}) {
  const client = useQueryClient()
  const query = useQuery({ queryKey: reviewScheduleKey(scope, resourceId), queryFn: () => getReviewSchedule(scope, resourceId) })
  const [draft, setDraft] = useState<{ enabled: boolean; intervalDays: string; nextReviewOn: string } | null>(null)
  const data = query.data
  const values = draft ?? { enabled: data?.intervalDays != null, intervalDays: String(data?.intervalDays ?? 30), nextReviewOn: data?.nextReviewOn ?? data?.today ?? '' }
  const save = useMutation({ mutationFn: () => configureReviewSchedule(scope, resourceId, {
    expectedAccountId: accountId, expectedVersion: data!.version,
    intervalDays: values.enabled ? Number(values.intervalDays) : undefined,
    nextReviewOn: values.enabled ? values.nextReviewOn : undefined,
  }), onSuccess: result => { client.setQueryData(reviewScheduleKey(scope, resourceId), result); setDraft(null); void client.invalidateQueries({ queryKey: dueReviewsKey(scope) }) },
  onError: () => { void query.refetch() } })
  return <section className="resource-review-schedule" aria-label="자료 재확인 주기">
    <h4>재확인 주기</h4>
    {query.isPending ? <p role="status">재확인 일정을 불러오고 있습니다.</p>
      : query.isError ? <p role="alert">{query.error.message} <button type="button" onClick={() => void query.refetch()}>다시 불러오기</button></p>
        : <p>{data!.nextReviewOn ? `${data!.intervalDays}일마다 확인 · 다음 확인일 ${formatLocalDate(data!.nextReviewOn)}${data!.reviewDue ? ' · 재확인할 때입니다.' : ''}` : '정기 확인이 꺼져 있습니다.'}</p>}
    {editable && data && <form onSubmit={event => { event.preventDefault(); if (!query.isError && !query.isFetching && !save.isPending) save.mutate() }}>
      <fieldset disabled={save.isPending || query.isError || query.isFetching}>
        <label className="review-toggle"><input type="checkbox" checked={values.enabled} onChange={event => setDraft({ ...values, enabled: event.target.checked })} />정기 확인</label>
        {values.enabled && <>
          <label>확인 간격(일)<input type="number" min={1} max={365} required value={values.intervalDays} onChange={event => setDraft({ ...values, intervalDays: event.target.value })} /></label>
          <label>다음 확인일<input type="date" required value={values.nextReviewOn} onChange={event => setDraft({ ...values, nextReviewOn: event.target.value })} /></label>
          <p>‘사용 가능’으로 기록한 날부터 설정한 일수 뒤가 다음 확인일이 됩니다. ‘수정 필요’이면 날짜를 유지합니다.</p>
        </>}
        <button type="submit">{save.isPending ? '주기 저장 중…' : '재확인 주기 저장'}</button>
      </fieldset>
    </form>}
    {save.isError && <p role="alert">{save.error.message} 저장된 일정을 확인한 뒤 다시 저장해 주세요.</p>}
    {save.isSuccess && <p role="status">재확인 주기를 저장했습니다.</p>}
  </section>
}
