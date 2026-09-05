import { useQuery } from '@tanstack/react-query'
import type { WorkspaceScope } from '@/features/workspace/api'
import { formatLocalDate } from '@/features/workspace/workspacePresentation'
import { ApiError } from '@/shared/api/ApiError'
import { dueReviewsKey, getDueReviews } from './api'
import './resource-verification.scss'

export function DueResourceReviewsPanel({ scope, timeZone, ended, onOpenRole }: {
  scope: WorkspaceScope; timeZone: string; ended: boolean; onOpenRole: (roleId: string) => void
}) {
  const query = useQuery({ queryKey: dueReviewsKey(scope), queryFn: () => getDueReviews(scope, timeZone),
    enabled: !ended, staleTime: 30_000, refetchOnWindowFocus: true,
    refetchInterval: current => current.state.error instanceof ApiError && [401, 403].includes(current.state.error.status) ? false : 30_000,
  })
  if (ended) return null
  return <section className="due-resource-reviews" aria-labelledby="due-reviews-title">
    <div className="due-reviews-heading"><h2 id="due-reviews-title">재확인할 자료</h2>
      <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>자료 목록 새로고침</button></div>
    {query.isPending ? <p role="status">확인 기한을 불러오고 있습니다.</p>
      : query.isError ? <p role="alert">{query.error.message}</p>
        : <>
          <p>{formatLocalDate(query.data.today)} · {timeZone} 기준으로 확인 기한이 된 자료입니다.</p>
          {query.data.resources.length === 0 ? <p>지금 재확인할 자료가 없습니다.</p>
            : <ul>{query.data.resources.map(row => <li key={row.resourceId}>
              <button type="button" onClick={() => onOpenRole(row.roleId)}>
                <strong>{row.title}</strong><span>{row.roleName} · {row.memberName ?? '담당자 지정 필요'}</span>
                <small>{formatLocalDate(row.nextReviewOn)} 확인 기한 · 자료 확인하기</small>
              </button>
            </li>)}</ul>}
        </>}
  </section>
}
